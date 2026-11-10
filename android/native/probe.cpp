#include <android/NeuralNetworks.h>
#include <vulkan/vulkan.h>
#include <cstdio>
#include <dlfcn.h>
#include <initializer_list>

int main() {
    uint32_t count = 0;
    int code = ANeuralNetworks_getDeviceCount(&count);
    std::printf("NNAPI enumeration=%d count=%u\n", code, count);
    for (uint32_t i = 0; i < count; ++i) {
        ANeuralNetworksDevice* dev = nullptr;
        const char *name = "?", *version = "?";
        int32_t type = -1; int64_t level = -1;
        ANeuralNetworks_getDevice(i, &dev);
        ANeuralNetworksDevice_getName(dev, &name);
        ANeuralNetworksDevice_getVersion(dev, &version);
        ANeuralNetworksDevice_getType(dev, &type);
        ANeuralNetworksDevice_getFeatureLevel(dev, &level);
        std::printf("NNAPI device=%s version=%s type=%d feature=%lld\n", name, version, type, (long long)level);
        ANeuralNetworksModel* model = nullptr;
        ANeuralNetworksModel_create(&model);
        uint32_t dims[] = {4};
        ANeuralNetworksOperandType tensor = {ANEURALNETWORKS_TENSOR_FLOAT32, 1, dims, 0, 0};
        ANeuralNetworksOperandType scalar = {ANEURALNETWORKS_INT32, 0, nullptr, 0, 0};
        ANeuralNetworksModel_addOperand(model, &tensor);
        ANeuralNetworksModel_addOperand(model, &tensor);
        ANeuralNetworksModel_addOperand(model, &scalar);
        ANeuralNetworksModel_addOperand(model, &tensor);
        int32_t activation = 0;
        ANeuralNetworksModel_setOperandValue(model, 2, &activation, sizeof(activation));
        uint32_t ins[] = {0,1,2}, out[] = {3}, modelins[] = {0,1};
        ANeuralNetworksModel_addOperation(model, ANEURALNETWORKS_ADD, 3, ins, 1, out);
        ANeuralNetworksModel_identifyInputsAndOutputs(model, 2, modelins, 1, out);
        ANeuralNetworksModel_finish(model);
        const ANeuralNetworksDevice* devices[] = {dev};
        bool supported = false;
        int support = ANeuralNetworksModel_getSupportedOperationsForDevices(model, devices, 1, &supported);
        std::printf("  ADD support_query=%d supported=%d\n", support, supported);
        ANeuralNetworksCompilation* compilation = nullptr;
        int compile = ANeuralNetworksCompilation_createForDevices(model, devices, 1, &compilation);
        if (compile == 0) compile = ANeuralNetworksCompilation_finish(compilation);
        if (compile == 0) {
            ANeuralNetworksExecution* execution = nullptr;
            ANeuralNetworksExecution_create(compilation, &execution);
            float a[] = {1,2,3,4}, b[] = {10,20,30,40}, c[4] = {};
            ANeuralNetworksExecution_setInput(execution, 0, nullptr, a, sizeof(a));
            ANeuralNetworksExecution_setInput(execution, 1, nullptr, b, sizeof(b));
            ANeuralNetworksExecution_setOutput(execution, 0, nullptr, c, sizeof(c));
            int run = ANeuralNetworksExecution_compute(execution);
            std::printf("  ADD execution=%d output=%.1f,%.1f,%.1f,%.1f\n", run,c[0],c[1],c[2],c[3]);
            ANeuralNetworksExecution_free(execution);
        } else std::printf("  ADD compilation=%d\n",compile);
        if (compilation) ANeuralNetworksCompilation_free(compilation);
        ANeuralNetworksModel_free(model);
    }
    VkApplicationInfo app = {VK_STRUCTURE_TYPE_APPLICATION_INFO};
    app.pApplicationName = "MossProbe"; app.apiVersion = VK_API_VERSION_1_1;
    VkInstanceCreateInfo info = {VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO}; info.pApplicationInfo = &app;
    VkInstance instance{};
    VkResult vk = vkCreateInstance(&info,nullptr,&instance);
    std::printf("Vulkan create=%d\n",vk);
    if (vk == VK_SUCCESS) {
        VkPhysicalDevice devices[16]; uint32_t n = 16;
        vkEnumeratePhysicalDevices(instance,&n,devices);
        for(uint32_t i=0;i<n;++i) {
            VkPhysicalDeviceProperties p{}; vkGetPhysicalDeviceProperties(devices[i],&p);
            std::printf("Vulkan device=%s api=%u.%u.%u driver=%u\n",p.deviceName,VK_VERSION_MAJOR(p.apiVersion),VK_VERSION_MINOR(p.apiVersion),VK_VERSION_PATCH(p.apiVersion),p.driverVersion);
        }
        vkDestroyInstance(instance,nullptr);
    }
    for (const char* lib : {"libOpenCL.so","libxnn_runtime.so","libxnnapi.so","libNPURuntime.so","libOpenVX.so","libovxlib.so"}) {
        void* h=dlopen(lib,RTLD_NOW|RTLD_LOCAL);
        std::printf("Library %s: %s\n",lib,h?"loadable":dlerror());
        if(h) dlclose(h);
    }
}
