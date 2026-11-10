#pragma once
/* Shared XRing operator runtime, adapted from the MIT-licensed
 * j4ger/o3-experiments ABI/call sequence pinned in upstream/source.json.
 * No root, policy, clock, binary patch, reset, or CPU fallback implementation.
 * CPU references in the probes only validate hardware output.
 */
#include "upstream/mida_types.h"
#include <dlfcn.h>
#include <unistd.h>
#include <time.h>
#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <stdexcept>
#include <string>
#include <vector>

using H = void *;
static_assert(sizeof(midaDeviceConfig) == 28);
static_assert(sizeof(midaStreamConfig) == 12);
static_assert(sizeof(midaSubgraphConfig) == 160);
static_assert(sizeof(midaSubgraphExecParams) == 32);
static_assert(sizeof(midaLaunchConfig) == 184);
static_assert(sizeof(std::string) == 24);

static void check(const char *name, int rc) {
    if (rc) {
        std::fprintf(stderr, "FAILED %s rc=%d\n", name, rc);
        throw std::runtime_error(name);
    }
}
template<class T> static T symbol(H lib, const char *name) {
    H p = dlsym(lib, name);
    if (!p) throw std::runtime_error(std::string("missing_symbol:") + name);
    return reinterpret_cast<T>(p);
}
static uint64_t micros() {
    timespec t{};
    clock_gettime(CLOCK_MONOTONIC, &t);
    return uint64_t(t.tv_sec) * 1000000 + uint64_t(t.tv_nsec) / 1000;
}
static inline uint16_t half(float f) {
    __fp16 h = static_cast<__fp16>(f);
    uint16_t b;
    std::memcpy(&b, &h, sizeof b);
    return b;
}
static inline float number(uint16_t b) {
    __fp16 h;
    std::memcpy(&h, &b, sizeof h);
    return static_cast<float>(h);
}

struct Api {
    H umd{}, xnnl{};
    int (*midaDeviceGet)(H *, uint32_t, midaDeviceConfig *){};
    int (*midaDeviceRelease)(H){};
    int (*midaStreamCreate)(H *, H, midaStreamConfig *){};
    int (*midaStreamDestroy)(H){};
    int (*midaMemoryAlloc)(H *, H, uint64_t, uint32_t, uint32_t, const char *){};
    int (*midaMemoryFree)(H){};
    H (*midaMemoryGetPtr)(H){};
    uint64_t (*midaMemoryGetDeviceVa)(H){};
    int (*midaMemoryCacheStart)(H){};
    int (*midaMemoryCacheEnd)(H){};
    int (*midaSubgraphCreate)(H *, H, midaSubgraphConfig *){};
    int (*midaSubgraphDestroy)(H){};
    int (*midaSubgraphInstantiate)(H *, H, midaSubgraphExecParams *){};
    int (*midaSubgraphExecDestroy)(H){};
    int (*midaStreamLaunchSubgraphAsync)(H, midaLaunchConfig *, H){};
    int (*midaStreamSynchronize)(H){};
    int (*xnnlCreate)(H *, H){};
    int (*xnnlDestroy)(H){};
    int (*xnnlCreateTensorDescriptor)(H *){};
    int (*xnnlDestroyTensorDescriptor)(H){};
    int (*xnnlSetTensorDescriptor)(H, const int *, unsigned, int, int, int, std::string){};
    int (*xnnlRMSNormalizationCreateGraph)(H, H, int, H, const void *, H,
                                         const void *, H, void *, int, float, int){};
    void load(const char *xnnl_path = "/vendor/lib64/libxnnl.so") {
        umd = dlopen("/vendor/lib64/libxnumd.so", RTLD_NOW | RTLD_GLOBAL);
        if (!umd) throw std::runtime_error(dlerror());
        xnnl = dlopen(xnnl_path, RTLD_NOW | RTLD_GLOBAL);
        if (!xnnl) throw std::runtime_error(dlerror());
#define M(name) name = symbol<decltype(name)>(umd, #name)
        M(midaDeviceGet); M(midaDeviceRelease);
        M(midaStreamCreate); M(midaStreamDestroy);
        M(midaMemoryAlloc); M(midaMemoryFree); M(midaMemoryGetPtr);
        M(midaMemoryGetDeviceVa); M(midaMemoryCacheStart); M(midaMemoryCacheEnd);
        M(midaSubgraphCreate); M(midaSubgraphDestroy);
        M(midaSubgraphInstantiate); M(midaSubgraphExecDestroy);
        M(midaStreamLaunchSubgraphAsync); M(midaStreamSynchronize);
#undef M
#define X(name) name = symbol<decltype(name)>(xnnl, #name)
        X(xnnlCreate); X(xnnlDestroy);
        X(xnnlCreateTensorDescriptor); X(xnnlDestroyTensorDescriptor);
        X(xnnlSetTensorDescriptor); X(xnnlRMSNormalizationCreateGraph);
#undef X
    }
    ~Api() {
        if (xnnl) dlclose(xnnl);
        if (umd) dlclose(umd);
    }
};

struct Resources {
    Api &a;
    H device{}, stream{}, xhandle{}, graph{}, executable{};
    std::vector<H> mem, desc;
    std::vector<uint16_t *> cpu;
    std::vector<uint64_t> va;
    // xnnlSetTensorDescriptor stores the supplied shape pointer (str x1,[x0]
    // at 0x57102c in the pinned device library), rather than copying its values.
    std::vector<std::array<int, 4>> shape;
    explicit Resources(Api &api, size_t buffers = 3)
        : a(api), mem(buffers), desc(buffers), cpu(buffers), va(buffers), shape(buffers) {}
    int release() {
        int failures = 0;
        auto close = [&](H &h, int (*fn)(H), const char *name) {
            if (!h) return;
            int rc = fn(h);
            if (rc) { ++failures; std::printf("cleanup %s rc=%d\n", name, rc); }
            h = nullptr;
        };
        close(executable, a.midaSubgraphExecDestroy, "executable");
        close(graph, a.midaSubgraphDestroy, "graph");
        for (auto &d : desc) close(d, a.xnnlDestroyTensorDescriptor, "descriptor");
        for (auto &m : mem) close(m, a.midaMemoryFree, "memory");
        close(xhandle, a.xnnlDestroy, "xnnl");
        close(stream, a.midaStreamDestroy, "stream");
        close(device, a.midaDeviceRelease, "device");
        return failures;
    }
    ~Resources() { release(); }
};
