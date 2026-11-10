#include <jni.h>
#include <android/NeuralNetworks.h>
#include <dlfcn.h>
#include <string>
#include <initializer_list>
#include <algorithm>
#include <cstdint>

// OpenCL 1.0 ABI, dynamically resolved: no vendor SDK is linked into the APK.
// API declarations: KhronosGroup/OpenCL-Headers, CL/cl.h.
static std::string opencl_devices(void* library) {
    using Platform = struct _cl_platform_id*;
    using Device = struct _cl_device_id*;
    using Platforms = int32_t (*)(uint32_t, Platform*, uint32_t*);
    using Devices = int32_t (*)(Platform, uint64_t, uint32_t, Device*, uint32_t*);
    using Info = int32_t (*)(Device, uint32_t, size_t, void*, size_t*);
    auto platforms = reinterpret_cast<Platforms>(dlsym(library,"clGetPlatformIDs"));
    auto devices = reinterpret_cast<Devices>(dlsym(library,"clGetDeviceIDs"));
    auto info = reinterpret_cast<Info>(dlsym(library,"clGetDeviceInfo"));
    if (!platforms || !devices || !info) return "\n  OpenCL enumeration entry points missing";
    Platform list[16]{}; uint32_t count=0;
    int code=platforms(16,list,&count);
    std::string out="\n  OpenCL platforms="+std::to_string(count)+" status="+std::to_string(code);
    if (code) return out;
    for (uint32_t p=0;p<std::min(count,16u);++p) {
        Device dev[32]{};uint32_t n=0;
        int status=devices(list[p],0xFFFFFFFFu,32,dev,&n);
        out+="\n  Device enumeration status="+std::to_string(status);
        if (status) continue;
        for(uint32_t i=0;i<std::min(n,32u);++i) {
            char name[256]{};uint64_t type=0;
            int a=info(dev[i],0x102B,sizeof(name)-1,name,nullptr);
            int b=info(dev[i],0x1000,sizeof(type),&type,nullptr);
            out+="\n  device="+std::string(name)+" type="+std::to_string(type)+" query="+std::to_string(a)+","+std::to_string(b);
        }
    }
    return out+"\n  Type flags: CPU=2, GPU=4, accelerator=8";
}

// Run inside the app ClassLoader's native namespace, separately from the CLI
// probe: uses-native-library declarations apply here, not to spawned binaries.
extern "C" JNIEXPORT jstring JNICALL
Java_win_youhoo_moss_MainActivity_nativeProbeLibraries(JNIEnv* env, jclass) {
    std::string result = "Application JNI namespace:\n";
    uint32_t count=0;
    int nnapi=ANeuralNetworks_getDeviceCount(&count);
    result+="NNAPI count="+std::to_string(count)+" status="+std::to_string(nnapi)+"\n";
    if (nnapi==0) for(uint32_t i=0;i<count;++i) {
        ANeuralNetworksDevice* device=nullptr;const char* name="?";int32_t type=-1;
        if (ANeuralNetworks_getDevice(i,&device)!=0) continue;
        ANeuralNetworksDevice_getName(device,&name);ANeuralNetworksDevice_getType(device,&type);
        result+="  device="+std::string(name)+" type="+std::to_string(type)+"\n";
    }
    for (const char* library : {"libOpenCL.so", "libxnn_runtime.so", "libxnnapi.so",
                                "libNPURuntime.so", "libOpenVX.so", "libovxlib.so"}) {
        void* handle = dlopen(library, RTLD_NOW | RTLD_LOCAL);
        result += library;
        result += ": ";
        if (handle) {
            result += "loadable";
            if (std::string(library) == "libOpenCL.so") result += opencl_devices(handle);
            if (std::string(library) == "libxnn_runtime.so") {
                for (const char* symbol : {"Xnn_CreateRuntime", "Xnn_CreatePlanFromFile",
                                          "Xnn_RuntimeDeserializeEngineFromXnnPlan", "Xnn_ContextExecute"}) {
                    result += "\n  "; result += symbol;
                    result += dlsym(handle, symbol) ? " present (not invoked)" : " absent";
                }
            }
            dlclose(handle);
        } else {
            const char* error = dlerror();
            result += error ? error : "load failed";
        }
        result += '\n';
    }
    return env->NewStringUTF(result.c_str());
}
