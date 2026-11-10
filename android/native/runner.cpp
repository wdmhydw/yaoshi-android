#include "model_loader.hpp"
#include "transcribe.hpp"
#include "subtitle.hpp"
#include "backend.hpp"
#include "ggml-cpu.h"
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <exception>
#include <unistd.h>
#include <fstream>
#include <iostream>
#include <sstream>
#ifdef MTD_ANDROID_JNI
#include <jni.h>
#include <fcntl.h>
#endif
#ifdef MTD_VERIFY_OPENCL_Q8
void verify_opencl_q8_rows(mt::ModelLoader&,const char*);
void verify_opencl_attention();
#endif

static void atomic_file(const std::string& path,const std::string& text) {
    std::string temporary=path+".part";
    { std::ofstream f(temporary,std::ios::binary);f<<text;f.close();if(!f)throw std::runtime_error("result write failed"); }
    if(std::rename(temporary.c_str(),path.c_str())!=0)throw std::runtime_error("result rename failed");
}

// One model per job; commands are generated local paths, not shell commands.
static int serve(const char* model_path,const char* directory) {
    if(dup2(STDERR_FILENO,STDOUT_FILENO)<0)return 1;
    try {
        mt::ModelLoader model;
        if(!model.load(model_path))return 1;
#ifdef MTD_VERIFY_OPENCL_Q8
        verify_opencl_q8_rows(model,model_path);
        verify_opencl_attention();
#endif
        const char* requested=std::getenv("MTD_DEVICE");
        if(requested&&std::strcmp(requested,"cpu")&&ggml_backend_is_cpu(mt::backend()))
            throw std::runtime_error("Requested GPU backend is unavailable");
        model.promote_small_f16_to_f32();
        const std::string dir=directory;
        atomic_file(dir+"/ready","ready");
        std::string line;
        while(std::getline(std::cin,line)) {
            if(line=="quit")return 0;
            std::istringstream input(line);int threads=0;std::string path;
            if(!(input>>threads>>path)||(threads<2||threads>4))return 2;
            // A persistent backend needs its thread count updated explicitly.
            auto backend=mt::backend();if(ggml_backend_is_cpu(backend)){
                ggml_backend_cpu_set_n_threads(backend,threads);
                std::fprintf(stderr,"[mt I] chunk CPU threads: %d\n",threads);
            }
            std::string transcript=mt::transcribe_wav(model,path,1024);
            atomic_file(dir+"/response.json",mt::to_json(mt::subtitle_segments_from_transcript(transcript,false)));
        }
        return 0;
    }catch(const std::exception& e){std::fprintf(stderr,"session error: %s\n",e.what());return 1;}
}

#ifdef MTD_ANDROID_JNI
extern "C" JNIEXPORT jint JNICALL Java_win_youhoo_moss_GpuEngineService_runSession(
        JNIEnv* env,jclass,jstring backend,jstring model,jstring directory,jint input,jint threads) {
    auto string_value=[&](jstring s){const char* p=env->GetStringUTFChars(s,nullptr);std::string value(p);env->ReleaseStringUTFChars(s,p);return value;};
    const std::string mode=string_value(backend),path=string_value(model),job=string_value(directory);
    const int log=open((job+"/engine.log").c_str(),O_WRONLY|O_CREAT|O_TRUNC,0600);
    if(log<0)return 1;
    bool redirected=dup2(input,STDIN_FILENO)>=0&&dup2(log,STDOUT_FILENO)>=0&&dup2(log,STDERR_FILENO)>=0;close(log);
    if(!redirected)return 1;
    setenv("MTD_DEVICE",mode.c_str(),1);setenv("MTD_THREADS",std::to_string(threads).c_str(),1);
    setenv("MTD_PROGRESS_FILE",(job+"/live.json").c_str(),1);setenv("TMPDIR",job.c_str(),1);
    setenv("GGML_OPENCL_KERNEL_CACHE_DIR",(job.substr(0,job.find_last_of('/'))+"/opencl-kernels").c_str(),1);
    std::cin.clear();
    return serve(path.c_str(),job.c_str());
}
#endif

// Keep vendor driver diagnostics off the machine-readable result channel.
// Some Mali drivers print directly to stdout during Vulkan initialization.
int main(int argc, char** argv) {
    if(argc==4&&!std::strcmp(argv[1],"serve"))return serve(argv[2],argv[3]);
    if (argc < 4 || std::strcmp(argv[1], "transcribe") != 0) {
        std::fprintf(stderr, "usage: libmoss_cpu.so transcribe model.gguf audio.wav [--max-new N] [--format json]\n");
        return 2;
    }
    int max_new = 1024;
    for (int i = 4; i < argc; ++i) {
        if (!std::strcmp(argv[i], "--max-new") && i + 1 < argc) max_new = std::atoi(argv[++i]);
        else if (!std::strcmp(argv[i], "--format") && i + 1 < argc && !std::strcmp(argv[i + 1], "json")) ++i;
        else { std::fprintf(stderr, "unknown option: %s\n", argv[i]); return 2; }
    }
    if (max_new < 1 || max_new > 8192) return 2;
    int result_fd = dup(STDOUT_FILENO);
    if (result_fd < 0) return 1;
    FILE* result = fdopen(result_fd, "w");
    if (!result) { close(result_fd); return 1; }
    if (dup2(STDERR_FILENO, STDOUT_FILENO) < 0) { std::fclose(result); return 1; }
    try {
        mt::ModelLoader model;
        if (!model.load(argv[2])) { std::fprintf(stderr, "model load failed\n"); std::fclose(result); return 1; }
        model.promote_small_f16_to_f32();
        std::string transcript = mt::transcribe_wav(model, argv[3], max_new);
        if (transcript.empty()) { std::fprintf(stderr, "transcription failed\n"); std::fclose(result); return 1; }
        std::string json = mt::to_json(mt::subtitle_segments_from_transcript(transcript, false));
        bool written = std::fwrite(json.data(), 1, json.size(), result) == json.size();
        int closed = std::fclose(result);
        return written && closed == 0 ? 0 : 1;
    } catch (const std::exception& e) {
        std::fprintf(stderr, "transcription error: %s\n", e.what());
        std::fclose(result);
        return 1;
    }
}
