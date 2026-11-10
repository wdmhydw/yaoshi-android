#include "model_loader.hpp"
#include "ggml-backend.h"
#include "gguf.h"
#include "backend.hpp"
#include "ggml-alloc.h"
#include <algorithm>
#include <cmath>
#include <cstdio>
#include <vector>
#include <stdexcept>
#include <cstring>

// Device integration check: compare GPU readback bytes with the original GGUF.
void verify_opencl_q8_rows(mt::ModelLoader& model,const char* path) {
    auto* tensor=model.tensor("token_embd.weight");
    if(!tensor||tensor->type!=GGML_TYPE_Q8_0)throw std::runtime_error("Q8 test tensor missing");
    gguf_init_params params{};params.no_alloc=true;
    gguf_context* meta=gguf_init_from_file(path,params);
    if(!meta)throw std::runtime_error("Q8 test metadata missing");
    const auto id=gguf_find_tensor(meta,"token_embd.weight");
    const size_t base=gguf_get_data_offset(meta)+gguf_get_tensor_offset(meta,id);
    gguf_free(meta);
    FILE* file=std::fopen(path,"rb");if(!file)throw std::runtime_error("Q8 reference missing");
    const size_t row=ggml_row_size(tensor->type,tensor->ne[0]),block=ggml_type_size(tensor->type),total=ggml_nbytes(tensor);
    const std::pair<size_t,size_t> ranges[]={{0,block},{block,block},{row,row},{row*1024,row*7},
        {total/row/2*row,row},{total-row,row},{total-block,block},{0,row*3},{1,37},{row-1,block+2}};
    int passed=0;
    for(auto range:ranges){
        std::vector<unsigned char> actual(range.second),expected(range.second);
        ggml_backend_tensor_get(tensor,actual.data(),range.first,range.second);
        const bool read=std::fseek(file,static_cast<long>(base+range.first),SEEK_SET)==0&&
            std::fread(expected.data(),1,expected.size(),file)==expected.size();
        if(!read||actual!=expected){std::fclose(file);throw std::runtime_error("Q8 readback differs from GGUF at "+std::to_string(range.first));}
        passed++;
    }
    std::fclose(file);std::fprintf(stderr,"[test] OpenCL Q8 readback: %d ranges byte-exact against GGUF\n",passed);
}

// Check the actual OpenCL fused operator against an independent double-precision
// bidirectional softmax reference. The 1500-frame case matches Whisper's length;
// early queries depend on future frames, so an accidental causal mask fails.
void verify_opencl_attention() {
    constexpr int width=64;
    double worst=0;
    for (int length : {33,1500}) {
        const int heads=length==33?2:1;
        auto* ctx=ggml_init({8*1024*1024,nullptr,true});
        if(!ctx)throw std::runtime_error("Attention test allocation failed");
        auto* q=ggml_new_tensor_3d(ctx,GGML_TYPE_F32,width,length,heads);
        auto* k=ggml_new_tensor_3d(ctx,GGML_TYPE_F32,width,length,heads);
        auto* v=ggml_new_tensor_3d(ctx,GGML_TYPE_F32,width,length,heads);
        auto* mask=ggml_new_tensor_2d(ctx,GGML_TYPE_F16,length,length);
        const float scale=1.0f/std::sqrt(float(width));
        auto* out=ggml_flash_attn_ext(ctx,q,k,v,mask,scale,0,0);
        ggml_flash_attn_ext_set_prec(out,GGML_PREC_F32);
        if(!ggml_backend_supports_op(mt::backend(),out)){ggml_free(ctx);throw std::runtime_error("Attention test operator unsupported");}
        auto* graph=ggml_new_graph(ctx);ggml_build_forward_expand(graph,out);
        auto* buffer=ggml_backend_alloc_ctx_tensors(ctx,mt::backend());
        if(!buffer){ggml_free(ctx);throw std::runtime_error("Attention test GPU allocation failed");}
        std::vector<float> qs(width*length*heads),ks(qs.size()),vs(qs.size()),actual(qs.size());
        std::vector<ggml_fp16_t> zeros(length*length,0);
        for(size_t i=0;i<qs.size();++i){
            qs[i]=float(int((i*37+11)%101)-50)/60.0f;
            ks[i]=float(int((i*19+23)%97)-48)/55.0f;
            vs[i]=float(int((i*13+47)%89)-44)/40.0f;
        }
        ggml_backend_tensor_set(q,qs.data(),0,qs.size()*sizeof(float));
        ggml_backend_tensor_set(k,ks.data(),0,ks.size()*sizeof(float));
        ggml_backend_tensor_set(v,vs.data(),0,vs.size()*sizeof(float));
        ggml_backend_tensor_set(mask,zeros.data(),0,zeros.size()*sizeof(ggml_fp16_t));
        const auto status=ggml_backend_graph_compute(mt::backend(),graph);
        if(status!=GGML_STATUS_SUCCESS){ggml_backend_buffer_free(buffer);ggml_free(ctx);throw std::runtime_error("Attention test compute failed");}
        ggml_backend_tensor_get(out,actual.data(),0,actual.size()*sizeof(float));
        ggml_backend_buffer_free(buffer);ggml_free(ctx);
        const std::vector<int> queries=length==33?std::vector<int>{0,1,16,31,32}:std::vector<int>{0,17,749,1499};
        std::vector<double> scores(length);
        for(int h=0;h<heads;++h)for(int query:queries){
            double maximum=-1e30;
            for(int key=0;key<length;++key){
                double dot=0;
                for(int d=0;d<width;++d)dot+=double(qs[d+width*(query+length*h)])*ks[d+width*(key+length*h)];
                scores[key]=dot*scale;maximum=std::max(maximum,scores[key]);
            }
            double sum=0;for(auto& s:scores){s=std::exp(s-maximum);sum+=s;}
            for(int d=0;d<width;++d){
                double expected=0;for(int key=0;key<length;++key)expected+=scores[key]*vs[d+width*(key+length*h)];
                const double got=actual[d+width*(h+heads*query)],error=std::abs(got-expected/sum);
                if(!std::isfinite(got)||error>5e-4){
                    std::fprintf(stderr,"[test] attention mismatch n=%d h=%d q=%d d=%d got=%.9g expected=%.9g error=%.9g\n",length,h,query,d,got,expected/sum,error);
                    throw std::runtime_error("OpenCL bidirectional attention parity failed");
                }
                worst=std::max(worst,error);
            }
        }
    }
    std::fprintf(stderr,"[test] OpenCL bidirectional attention: 33/1500 frames passed, max absolute error %.9g\n",worst);
}
