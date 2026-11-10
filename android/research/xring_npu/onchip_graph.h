#pragma once
#include "npu_probe_runtime.h"
#include <cstddef>
#include <memory>

namespace onchip {
constexpr uint32_t reservation = 8u << 20;
constexpr uint32_t page = 64u << 10;
constexpr uint32_t dot_va = page; // offset within the graph's own L2B VA range
struct Allocation { uint32_t size, offset; };
struct Dim { uint32_t x,y,z; };
struct Arg { const void* data; uint32_t bytes, type; };
struct KernelParams {
    Dim grid, threads, origin, dispatch;
    uint32_t shared_bytes, padding;
    H function; Arg *args;
    uint32_t grid_id, queue_id, interleave, traversal;
};
static_assert(sizeof(KernelParams)==0x58 && offsetof(KernelParams,function)==0x38);
struct Descriptor {
    H h{}; int(*destroy)(H){};
    ~Descriptor(){if(h)destroy(h);}
};
struct GraphScope {
    Resources& r;
    ~GraphScope(){
        if(r.executable){r.a.midaSubgraphExecDestroy(r.executable);r.executable=nullptr;}
        if(r.graph){r.a.midaSubgraphDestroy(r.graph);r.graph=nullptr;}
    }
};
struct Tile {
    H desc; uint64_t va; bool l2b;
    std::array<int,4> box, extent;
};
inline std::vector<uint8_t> file(const char* path,size_t limit){
    std::unique_ptr<FILE,decltype(&std::fclose)> f(std::fopen(path,"rb"),std::fclose);
    if(!f||std::fseek(f.get(),0,SEEK_END))throw std::runtime_error("file_open");
    long n=std::ftell(f.get());
    if(n<1||size_t(n)>limit)throw std::runtime_error("file_extent");
    std::rewind(f.get());std::vector<uint8_t> v(n);
    if(std::fread(v.data(),1,n,f.get())!=size_t(n))throw std::runtime_error("file_read");
    return v;
}
struct Nodes {
    using Encode=int(*)(void*,H,H,int,int,const int*,const int*,const int*,const int*,int,int,int,bool);
    using Insert=int(*)(H*,H,uint32_t,void*);
    using Conv=int(*)(H,H,uint32_t,H,H,H,H,H,H,H,H,H);
    using Reorder=int(*)(H,H,H,H,H,H,H,H,H,H,H);
    Api &a;
    Insert alloc,free,barrier,kernel;
    Encode encode;
    Conv conv;
    Reorder reorder;
    explicit Nodes(Api &api):a(api){
        alloc=symbol<Insert>(a.umd,"midaSubgraphAddL2bAllocNode");
        free=symbol<Insert>(a.umd,"midaSubgraphAddL2bFreeNode");
        barrier=symbol<Insert>(a.umd,"midaSubgraphAddBarrierNode");
        kernel=symbol<Insert>(a.umd,"midaSubgraphAddKernelNode");
        conv=symbol<Conv>(a.xnnl,"xnnlConvCreateGraph");
        reorder=symbol<Reorder>(a.xnnl,"xnnlConvDataReorder");
        Dl_info info{};auto stock=dlsym(a.xnnl,"xnnlDemoAddCreateKernel");
        if(!stock||!dladdr(stock,&info)||uintptr_t(stock)-uintptr_t(info.dli_fbase)!=0x61754c)
            throw std::runtime_error("unrecognized_xnnl");
        encode=reinterpret_cast<Encode>(uintptr_t(info.dli_fbase)+0x571e60);
        auto umd_node=dlsym(a.umd,"midaSubgraphAddKernelNode");
        if(!umd_node||!dladdr(umd_node,&info)||uintptr_t(umd_node)-uintptr_t(info.dli_fbase)!=0x5f5a8)
            throw std::runtime_error("unrecognized_umd");
    }
    void lease(H graph,uint32_t bytes,bool release=false,uint32_t offset=dot_va){
        Allocation params{(bytes+page-1)/page*page,offset};
        if(!params.size||params.size>reservation||offset%page||offset>reservation-params.size)
            throw std::runtime_error("L2B_reservation");
        H node{};check(release?"l2b_free":"l2b_alloc",(release?free:alloc)(&node,graph,0,&params));
    }
    void fence(H graph){
        uint32_t mode=1;H node{};check("barrier",barrier(&node,graph,0,&mode));
    }
    // The stock Conv policy only admits DDR at its public boundary. Capture
    // the output tensor map in our own uninstantiated graph, preserving its
    // complete tiling and matrix-unit parameters from the vendor wrapper.
    size_t count(H graph) const {
        H *begin{},*end{};std::memcpy(&begin,graph,sizeof(begin));
        std::memcpy(&end,(uint8_t*)graph+sizeof(begin),sizeof(end));
        return begin?size_t(end-begin):0;
    }
    void retarget(H graph,size_t index,H desc,uint64_t old_va,uint32_t new_va,int width,int height){
        if(index>=count(graph))throw std::runtime_error("missing_matrix_node");
        H *begin{};std::memcpy(&begin,graph,sizeof(begin));
        auto *node=(uint8_t*)begin[index];
        uint32_t argc{};std::memcpy(&argc,node+0x30+0x5c,4);
        if(argc!=20)throw std::runtime_error("matrix_arg_count");
        struct Copied { uint32_t* data;uint64_t inline_value;uint32_t bytes,type; };
        Copied *args{};std::memcpy(&args,node+0x30+0x38,sizeof(args));
        if(!args||!args[2].data||args[2].bytes!=64||args[2].type!=1)
            throw std::runtime_error("matrix_output_map");
        alignas(64) std::array<uint32_t,16> ddr{},l2b{};
        int box[4]={width,height,1,1};
        check("addr_ddr",encode(ddr.data(),desc,(H)old_va,0,0,box,box,box,box,1,0,0,false));
        check("addr_l2b",encode(l2b.data(),desc,(H)(uintptr_t)new_va,0,0,box,box,box,box,1,0,0,true));
        auto *map=args[2].data;
        static bool first=true;
        if(first){
            for(int i=0;i<16;++i)std::printf("TMAP i=%d matrix=%08x ddr=%08x l2b=%08x\n",i,map[i],ddr[i],l2b[i]);
            first=false;
        }
        if((map[5]&0xffffff00u)!=(uint32_t(old_va)<<8))throw std::runtime_error("matrix_va");
        // Word 0 bit 22 selects L2B. The VA spans words 5 and 6. Retain
        // every other field, including transaction ID and original split box.
        constexpr uint32_t location_bit=1u<<22;
        if((ddr[0]&location_bit)||!(l2b[0]&location_bit))throw std::runtime_error("map_location");
        for(int i:{0,5,6}){
            uint32_t delta=i==0?location_bit:ddr[i]^l2b[i];
            if((map[i]&delta)!=(ddr[i]&delta))throw std::runtime_error("matrix_map_delta");
            map[i]^=delta;
        }
    }
    H load(H device,const char* path,Descriptor &module){
        auto elf=file(path,512u<<10);
        module.destroy=symbol<int(*)(H)>(a.umd,"midaModuleDestroy");
        check("module",symbol<int(*)(H*,H,const void*,uint32_t)>(a.umd,"midaModuleLoadData")(
                &module.h,device,elf.data(),uint32_t(elf.size())));
        H fn{};check("function",symbol<int(*)(H*,H,const char*)>(a.umd,"midaModuleGetFunction")(
                &fn,module.h,"DemoAddKernel"));return fn;
    }
    void append(H graph,H fn,uint32_t groups,uint32_t threads,const std::array<Tile,3>& tensors){
        alignas(64) uint8_t tma[3][64]{};Arg args[6]{};uint32_t offsets[3]{};
        for(int i=0;i<3;++i){
            const auto &t=tensors[i];
            check("encode",encode(tma[i],t.desc,(H)t.va,0,0,t.box.data(),t.box.data(),
                                  t.extent.data(),t.extent.data(),1,0,0,t.l2b));
            args[i]={tma[i],64,1};args[3+i]={&offsets[i],4,0};
        }
        KernelParams params{{groups,1,1},{threads,1,1},{0,0,0},{groups,1,1},131072,0,fn,args,0,0,1,0};
        H node{};check("arithmetic_node",kernel(&node,graph,0,&params));
    }
};
inline Tile rows(H desc,uint64_t va,bool l2b,int width,int height){
    return {desc,va,l2b,{width,1,1,1},{width,height,1,1}};
}
inline void conv_descriptors(Api& a,Descriptor& cv,Descriptor& ep){
    cv.destroy=symbol<int(*)(H)>(a.xnnl,"xnnlDestroyConvDescriptor");
    ep.destroy=symbol<int(*)(H)>(a.xnnl,"xnnlDestroyEpilogueDescriptor");
    check("conv",symbol<int(*)(H*)>(a.xnnl,"xnnlCreateConvDescriptor")(&cv.h));
    check("ep",symbol<int(*)(H*)>(a.xnnl,"xnnlCreateEpilogueDescriptor")(&ep.h));
    static const int ones[2]={1,1},zero[4]={};  // vendor descriptor borrows these arrays
    using Set=int(*)(H,int,const int*,const int*,const int*,const int*,int,bool,int,bool);
    check("set_conv",symbol<Set>(a.xnnl,"xnnlSetConvDescriptor")(cv.h,4,ones,zero,ones,ones,1,false,0,false));
    using E=int(*)(H,int,int,int,int,int,int,int,int,int,int);
    check("set_ep",symbol<E>(a.xnnl,"xnnlSetEpilogueDescriptor")(ep.h,0,0,0,0,0,0,0,0,0,0));
}
inline uint32_t weight_bytes(Api& a,H cv,H ep,H x,H w,H y){
    using Size=int(*)(H,H,H,H,H,H,uint32_t*,uint32_t*);
    uint32_t bytes=0,side=0;
    check("weight_size",symbol<Size>(a.xnnl,"xnnlConvGetDataReorderSize")(cv,x,w,nullptr,y,ep,&bytes,&side));
    if(!bytes||side)throw std::runtime_error("weight_layout");return bytes;
}
}
