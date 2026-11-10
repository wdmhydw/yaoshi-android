// Compare the stock INT8 conv + private FP32 FMA with DDR and L2B placements.
#include "onchip_graph.h"
using namespace onchip;

int main(int argc,char** argv){
    if(argc!=5)return 2;
    std::string mode=argv[3];bool ddr=mode=="ddr",skip=mode=="skip",inspect=mode=="inspect";
    if(!ddr&&!skip&&!inspect&&mode!="l2b")return 2;
    setvbuf(stdout,nullptr,_IONBF,0);
    constexpr int t=32,k=64,n=64,cells=t*n,width=512,groups=cells/width;
    constexpr size_t xbytes=t*k,wbytes=n*k,ybytes=cells*4,sidebytes=ybytes*2;
    try{
        auto packet=file(argv[1],1u<<20);
        if(packet.size()!=xbytes+wbytes+sidebytes+ybytes)throw std::runtime_error("packet_length");
        Api a;a.load();Descriptor cv,ep;Resources r(a,6);Descriptor module;GraphScope scope{r};Nodes nodes(a);
        midaDeviceConfig dc{};midaStreamConfig sc{};sc.type=1;
        check("device",a.midaDeviceGet(&r.device,0,&dc));check("stream",a.midaStreamCreate(&r.stream,r.device,&sc));
        check("handle",a.xnnlCreate(&r.xhandle,r.device));conv_descriptors(a,cv,ep);
        // Descriptors 0..2 belong to the convolution. The flat tiles at 3..5
        // alias its 32x64 result as four rows of 512, without copying.
        r.shape={{1,1,t,k},{n,1,1,k},{1,1,t,n},{1,1,groups,width},{1,1,groups,width*2},{1,1,groups,width}};
        for(int i=0;i<6;++i){
            check("desc",a.xnnlCreateTensorDescriptor(&r.desc[i]));
            check("shape",a.xnnlSetTensorDescriptor(r.desc[i],r.shape[i].data(),4,2,i<2?4:0,
                  int(!ddr&&i==3),"moss_int8_tile"));
        }
        if(weight_bytes(a,cv.h,ep.h,r.desc[0],r.desc[1],r.desc[2])!=wbytes)throw std::runtime_error("weights_size");
        size_t sizes[]={xbytes,wbytes,ddr?ybytes:0,0,sidebytes,ybytes};
        for(int i=0;i<6;++i)if(sizes[i]){
            check("alloc",a.midaMemoryAlloc(&r.mem[i],r.device,sizes[i]+4096,5,0,"moss_int8_tile"));
            r.cpu[i]=(uint16_t*)a.midaMemoryGetPtr(r.mem[i]);r.va[i]=a.midaMemoryGetDeviceVa(r.mem[i]);
            if(!r.cpu[i]||!r.va[i])throw std::runtime_error("address");
            check("begin",a.midaMemoryCacheStart(r.mem[i]));std::memset(r.cpu[i],0,sizes[i]);
            std::memset((uint8_t*)r.cpu[i]+sizes[i],0xa5,4096);
        }
        std::memcpy(r.cpu[0],packet.data(),xbytes);
        std::array<H,7> aux{};
        check("reorder",nodes.reorder(cv.h,r.desc[0],r.desc[1],packet.data()+xbytes,nullptr,nullptr,
                                      r.desc[2],ep.h,aux.data(),r.cpu[1],nullptr));
        std::memcpy(r.cpu[4],packet.data()+xbytes+wbytes,sidebytes);
        for(H h:r.mem)if(h)check("end",a.midaMemoryCacheEnd(h));
        H fn=nodes.load(r.device,argv[2],module);
        midaSubgraphConfig gc{};check("graph",a.midaSubgraphCreate(&r.graph,r.device,&gc));
        uint64_t partial=ddr?r.va[2]:dot_va;
        if(!ddr)nodes.lease(r.graph,ybytes);
        size_t matrix_index=nodes.count(r.graph);
        // The final array is a build-time placeholder only in the L2B case.
        uint64_t build_address=ddr?r.va[2]:r.va[5];
        check("conv_node",nodes.conv(r.xhandle,r.graph,reservation,cv.h,r.desc[0],(H)r.va[0],
                  r.desc[1],(H)r.va[1],ep.h,nullptr,r.desc[2],(H)build_address));
        if(!ddr)nodes.retarget(r.graph,matrix_index,r.desc[2],build_address,dot_va,n,t);
        nodes.fence(r.graph);
        nodes.append(r.graph,fn,groups,width,{rows(r.desc[3],partial,!ddr,width,groups),
                    rows(r.desc[4],r.va[4],false,width*2,groups),rows(r.desc[5],r.va[5],false,width,groups)});
        nodes.fence(r.graph);if(!ddr)nodes.lease(r.graph,ybytes,true);
        midaSubgraphExecParams cfg{};cfg.regFenceFlag=1;cfg.l2bSize=cfg.vaSize=reservation;
        check("instantiate",a.midaSubgraphInstantiate(&r.executable,r.graph,&cfg));
        check("visualize",symbol<int(*)(H,char*)>(a.umd,"midaSubgraphExecToVisualize")(r.executable,argv[4]));
        std::printf("GRAPH mode=%s pid=%d partial_ddr_bytes=%zu partial_va=0x%llx\n",argv[3],
                    getpid(),sizes[2],(unsigned long long)partial);
        if(inspect)return 0;
        midaLaunchConfig lc{};lc.cu_num=4;lc.ddr_ratio=1;lc.fw_timeout=3000;lc.name="s8_tile_fma";
        const auto* expected=packet.data()+xbytes+wbytes+sidebytes;
        for(int round=0;round<3;++round){
            uint32_t poison=0x7fc07123u+round;
            check("poison",a.midaMemoryCacheStart(r.mem[5]));std::fill_n((uint32_t*)r.cpu[5],cells,poison);
            check("poison_end",a.midaMemoryCacheEnd(r.mem[5]));uint64_t start=micros();
            if(!skip){check("launch",a.midaStreamLaunchSubgraphAsync(r.executable,&lc,r.stream));check("sync",a.midaStreamSynchronize(r.stream));}
            uint64_t elapsed=micros()-start;size_t bad=0,untouched=0;
            for(int i=0;i<6;++i)if(sizes[i]){
                check("read",a.midaMemoryCacheStart(r.mem[i]));
                if(i==5)for(int j=0;j<cells;++j){
                    uint32_t want;std::memcpy(&want,expected+j*4,4);uint32_t got=((uint32_t*)r.cpu[i])[j];
                    if(want!=got&&bad++<2)std::printf("MISMATCH j=%d want=%08x got=%08x\n",j,want,got);
                    untouched+=got==poison;
                }
                auto *g=(uint8_t*)r.cpu[i]+sizes[i];
                if(!std::all_of(g,g+4096,[](uint8_t v){return v==0xa5;}))throw std::runtime_error("red_zone");
                check("read_end",a.midaMemoryCacheEnd(r.mem[i]));
            }
            std::printf("TILE round=%d cells=%d wrong=%zu poison=%zu submit_us=%llu\n",
                         round,cells,bad,untouched,(unsigned long long)elapsed);
            if(skip||bad)return 4;
        }
        check("free_exec",a.midaSubgraphExecDestroy(r.executable));r.executable=nullptr;
        check("free_graph",a.midaSubgraphDestroy(r.graph));r.graph=nullptr;
        check("free_module",module.destroy(module.h));module.h=nullptr;
        return r.release()?1:0;
    }catch(const std::exception& e){std::fprintf(stderr,"FAIL %s\n",e.what());return 1;}
}
