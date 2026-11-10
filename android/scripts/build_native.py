"""Direct NDK build, also usable when host CMake compiler discovery crashes.

Usage: python android/scripts/build_native.py --ndk <NDK>
       [--vulkan --shaders DIR | --opencl --opencl-headers DIR]
"""
import argparse
import concurrent.futures
import hashlib
import json
import os
import re
import shutil
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[2]

def main():
    p = argparse.ArgumentParser()
    p.add_argument('--ndk', type=Path, required=True)
    acceleration=p.add_mutually_exclusive_group()
    acceleration.add_argument('--vulkan', action='store_true')
    acceleration.add_argument('--opencl', action='store_true')
    p.add_argument('--opencl-headers', type=Path)
    p.add_argument('--verify-opencl-q8', action='store_true', help='Test build only: validate Q8 reads and bidirectional attention against independent references')
    p.add_argument('--shaders', type=Path)
    p.add_argument('--jobs', type=int, default=6)
    args = p.parse_args()
    if args.verify_opencl_q8 and not args.opencl: p.error('--verify-opencl-q8 requires --opencl')
    src = ROOT/'vendor/moss-native'
    gg = src/'third_party/ggml'
    mode = 'vulkan' if args.vulkan else 'opencl' if args.opencl else 'cpu'
    out = ROOT/'android/build'/mode
    out.mkdir(parents=True, exist_ok=True)
    host = args.ndk/'toolchains/llvm/prebuilt/windows-x86_64'
    common = ['--target=aarch64-linux-android29', '--sysroot='+str(host/'sysroot'),
              '-O3', '-DNDEBUG', '-fPIC', '-ffunction-sections', '-fdata-sections',
              '-ffile-prefix-map='+str(ROOT)+'=.', '-ffile-prefix-map='+str(args.ndk.resolve())+'=ndk',
              '-march=armv8.2-a+dotprod+fp16', '-D_GNU_SOURCE', '-DGGML_USE_CPU',
              '-DGGML_USE_CPU_REPACK', '-DGGML_SCHED_MAX_COPIES=4',
              '-DGGML_VERSION="0.15.3"', '-DGGML_COMMIT="eced84c"']
    for folder in (src/'include',src/'src',src/'third_party',gg/'include',gg/'src',gg/'src/ggml-cpu'):
        common += ['-I', str(folder)]
    common += ['-I',str(ROOT/'android/native')]
    if args.opencl: common += ['-DMTD_ANDROID_JNI']
    sources = [f for f in (src/'src').glob('*.cpp') if f.name not in ('cli.cpp','generate.cpp','transcribe.cpp')]
    sources += [ROOT/'android/native/runner.cpp',ROOT/'android/native/live_progress.cpp']
    if args.verify_opencl_q8:
        common += ['-DMTD_VERIFY_OPENCL_Q8']
        sources += [ROOT/'android/tests/OpenClQ8ReadCheck.cpp']
    patches={
        'generate.cpp':('        ids.push_back(t);','        ids.push_back(t);\n        android_progress_tokens(ids);'),
        'transcribe.cpp':('    std::vector<int32_t> new_ids =','    android_progress_begin(&tok);\n    std::vector<int32_t> new_ids =')
    }
    for filename,(needle,replacement) in patches.items():
        original=(src/'src'/filename).read_text(encoding='utf8')
        assert original.count(needle)==1, 'Review Android progress hook against upstream'
        fixed='#include "live_progress.hpp"\n'+original.replace(needle,replacement)
        patched=out/filename.replace('.cpp','-android.cpp')
        if not patched.exists() or patched.read_text(encoding='utf8')!=fixed:patched.write_text(fixed,encoding='utf8')
        sources.append(patched)
    sources += [gg/'src'/n for n in ('ggml.c','ggml.cpp','ggml-alloc.c','ggml-backend.cpp',
        'ggml-backend-meta.cpp','ggml-backend-dl.cpp','ggml-backend-reg.cpp','ggml-opt.cpp',
        'ggml-threading.cpp','ggml-quants.c','gguf.cpp')]
    sources += [gg/'src/ggml-cpu'/n for n in ('ggml-cpu.c','ggml-cpu.cpp','repack.cpp',
        'hbm.cpp','quants.c','traits.cpp','amx/amx.cpp','amx/mmq.cpp','binary-ops.cpp',
        'unary-ops.cpp','vec.cpp','ops.cpp','arch/arm/quants.c','arch/arm/repack.cpp')]
    if args.opencl:
        if not args.opencl_headers: p.error('--opencl-headers is required for OpenCL')
        cache_src=ROOT/'vendor/opencl-program-cache/ggml/src/ggml-opencl'
        common += ['-DGGML_USE_OPENCL','-DGGML_OPENCL_SOA_Q','-DGGML_OPENCL_USE_ADRENO_KERNELS',
                   '-DGGML_OPENCL_EMBED_KERNELS','-DGGML_OPENCL_TARGET_VERSION=300','-I',str(args.opencl_headers),'-I',str(out),'-I',str(cache_src)]
        for kernel in (gg/'src/ggml-opencl/kernels').glob('*.cl'):
            kernel_source=kernel.read_text(encoding='utf8')
            if kernel.name=='flash_attn_f32.cl':
                # Partial key tiles must not multiply zero probabilities by
                # uninitialized local values (which may be NaN). All query lanes,
                # including padding lanes, also rendezvous before reusing a tile.
                needle='    for (int k_start = 0; k_start < n_kv; k_start += BLOCK_N) {'
                assert kernel_source.count(needle)==1
                kernel_source=kernel_source.replace(needle,needle+'\n        barrier(CLK_LOCAL_MEM_FENCE);')
                for letter in ('k','v'):
                    needle=f'                l_{letter}[row][col] = ((__global DATA_TYPE4*)({letter}_base + {letter}_row_offset))[col];\n            }}'
                    assert kernel_source.count(needle)==1
                    kernel_source=kernel_source.replace(needle,needle+f' else {{\n                l_{letter}[row][col] = (DATA_TYPE4)(0.0f);\n            }}')
            text=''.join('R"('+line+')"\n' for line in kernel_source.splitlines(keepends=True))
            header=out/(kernel.name+'.h')
            if not header.exists() or header.read_text(encoding='utf8')!=text: header.write_text(text,encoding='utf8')
        original=(gg/'src/ggml-opencl/ggml-opencl.cpp').read_text(encoding='utf8')
        start=original.index('static void ggml_backend_opencl_buffer_get_tensor(')
        needle='        ggml_tensor_extra_cl_q8_0 * extra = (ggml_tensor_extra_cl_q8_0 *)tensor->extra;'
        at=original.index(needle,start)+len(needle)
        fixed=original[:at]+'\n'+(ROOT/'android/native/opencl-q8-read.inc').read_text(encoding='utf8')+original[at:]
        needle='static cl_program build_program_from_source(cl_context ctx, cl_device_id dev, const char* program_buffer, const std::string &compile_opts) {'
        assert fixed.count(needle)==1
        fixed=fixed.replace('#include <CL/cl.h>','#include <CL/cl.h>\n#include "cl-program-cache.h"')
        fixed=fixed.replace(needle,needle+'''
    static std::mutex cache_mutex;
    static std::map<cl_device_id,cl_program_cache_state> caches;
    cl_program_cache_state cache;
    { std::lock_guard<std::mutex> guard(cache_mutex);
      auto found=caches.find(dev);
      if(found==caches.end()) found=caches.emplace(dev,cl_program_cache_init(dev)).first;
      cache=found->second;
    }
    if (cl_program cached = cl_program_cache_try_load(cache, ctx, dev, program_buffer, compile_opts)) return cached;
''')
        at=fixed.index('    return p;',fixed.index(needle))
        fixed=fixed[:at]+'    cl_program_cache_try_save(cache, p, dev, program_buffer, compile_opts);\n'+fixed[at:]
        patched=out/'ggml-opencl-android.cpp'
        if not patched.exists() or patched.read_text(encoding='utf8')!=fixed: patched.write_text(fixed,encoding='utf8')
        sources.append(patched)
        sources.append(cache_src/'cl-program-cache.cpp')
        whisper=src/'src/whisper_encoder.cpp'
        original=whisper.read_text(encoding='utf8')
        start=original.index('        ggml_tensor* scores = ggml_mul_mat(ctx, K, Q);')
        end=original.index('\n        x   = linear(ctx, L.o_w, L.o_b, x);',start)
        fixed=original[:start]+(ROOT/'android/native/opencl-whisper-attention.inc').read_text(encoding='utf8')+original[end:]
        needle='    const float kq_scale = 1.0f / std::sqrt((float)hd);'
        assert fixed.count(needle)==1
        fixed=fixed.replace(needle,needle+'''
    // The pinned OpenCL backend assumes causal attention for a null mask.
    // Whisper is bidirectional: upload one shared, explicitly all-zero mask.
    const int mask_queries = T;
    ggml_tensor* bidirectional_mask = ggml_new_tensor_2d(ctx,GGML_TYPE_F16,T,mask_queries);
    ggml_set_input(bidirectional_mask);
    bool opencl_fused = false;
''')
        needle='        ggml_backend_tensor_set(mel_in, mel.data(), 0, mel.size() * sizeof(float));'
        assert fixed.count(needle)==1
        fixed=fixed.replace(needle,needle+'''
        if (opencl_fused) {
            const std::vector<ggml_fp16_t> zero_mask(static_cast<size_t>(T) * mask_queries,0);
            ggml_backend_tensor_set(bidirectional_mask,zero_mask.data(),0,zero_mask.size()*sizeof(ggml_fp16_t));
        }
''')
        patched=out/'whisper-encoder-android.cpp'
        if not patched.exists() or patched.read_text(encoding='utf8')!=fixed: patched.write_text(fixed,encoding='utf8')
        sources.remove(whisper);sources.append(patched)
    if args.vulkan:
        if not args.shaders: p.error('--shaders is required for Vulkan')
        common += ['-DGGML_USE_VULKAN','-I',str(args.shaders),'-I',str(ROOT/'vendor/Vulkan-Headers/include'),'-I',str(ROOT/'vendor/SPIRV-Headers/include')]
        common += ['-I',str(gg/'src/ggml-vulkan')]
        # Some Vulkan graph paths dispatch more sets than the preparatory count.
        # Grow capacity before taking a descriptor reference; keep all assertions.
        original=(gg/'src/ggml-vulkan/ggml-vulkan.cpp').read_text(encoding='utf8')
        needle='    GGML_ASSERT(ctx->descriptor_set_idx < ctx->descriptor_sets.size());'
        assert original.count(needle)==1, 'Review descriptor patch against updated upstream'
        fixed=original.replace(needle,'''    if (ctx->descriptor_set_idx >= ctx->descriptor_sets.size()) {
        ctx->pipeline_descriptor_set_requirements = std::max(
            ctx->pipeline_descriptor_set_requirements, ctx->descriptor_set_idx + 1);
        ggml_pipeline_allocate_descriptor_sets(ctx);
    }
'''+needle)
        # Some Android 1.1 drivers advertise BDA but expose no core entry point.
        # Resolve it for this logical device, rather than calling a null Hpp slot.
        # Missing entry points keep ggml's ordinary descriptor-based buffer path.
        needle='    bool buffer_device_address;'
        assert fixed.count(needle)==1, 'Review Vulkan BDA device field patch'
        fixed=fixed.replace(needle,needle+'\n    PFN_vkGetBufferDeviceAddress android_get_buffer_address = nullptr;')
        needle='        device->device = device->physical_device.createDevice(device_create_info);'
        assert fixed.count(needle)==1, 'Review Vulkan BDA loader patch'
        fixed=fixed.replace(needle,needle+'''
        if (device->buffer_device_address) {
            device->android_get_buffer_address = reinterpret_cast<PFN_vkGetBufferDeviceAddress>(
                vkGetDeviceProcAddr(static_cast<VkDevice>(device->device), "vkGetBufferDeviceAddress"));
            if (device->android_get_buffer_address == nullptr) {
                device->buffer_device_address = false;
                GGML_LOG_WARN("ggml_vulkan: buffer device address entry point unavailable; using descriptor buffers.\\n");
            }
        }
''')
        needle='''        const vk::BufferDeviceAddressInfo addressInfo(buf->buffer);
        buf->bda_addr = device->device.getBufferAddress(addressInfo);'''
        assert fixed.count(needle)==1, 'Review Vulkan BDA call patch'
        fixed=fixed.replace(needle,'''        const VkBufferDeviceAddressInfo address_info {
            VK_STRUCTURE_TYPE_BUFFER_DEVICE_ADDRESS_INFO, nullptr, static_cast<VkBuffer>(buf->buffer)
        };
        buf->bda_addr = device->android_get_buffer_address(static_cast<VkDevice>(device->device), &address_info);''')
        patched=out/'ggml-vulkan-android.cpp'
        if not patched.exists() or patched.read_text(encoding='utf8')!=fixed: patched.write_text(fixed,encoding='utf8')
        sources += [patched] + sorted(args.shaders.glob('*.comp.cpp'))
    header_digest = hashlib.sha256()
    header_roots = [src/'include',src/'src',src/'third_party',ROOT/'android/native']
    if args.vulkan: header_roots += [ROOT/'vendor/Vulkan-Headers/include',ROOT/'vendor/SPIRV-Headers/include',args.shaders]
    if args.opencl: header_roots += [args.opencl_headers,out,cache_src]
    for header in sorted({p for folder in header_roots for p in folder.rglob('*')
                          if p.suffix in ('.h','.hpp','.inc')}):
        header_digest.update(str(header).encode()); header_digest.update(header.read_bytes())
    header_signature = header_digest.hexdigest()
    def compile_one(source):
        obj = out/(hashlib.sha256(str(source).encode()).hexdigest()[:16]+'.o')
        compiler = host/'bin'/('clang.exe' if source.suffix=='.c' else 'clang++.exe')
        cmd = [str(compiler), *common, '-std=c11' if source.suffix=='.c' else '-std=c++17',
               '-c',str(source),'-o',str(obj)]
        if source.name=='ggml-vulkan-android.cpp': cmd += ['-O1']
        sig = obj.with_suffix('.json')
        signature = json.dumps(cmd)+hashlib.sha256(source.read_bytes()).hexdigest()+header_signature
        if not (obj.exists() and sig.exists() and sig.read_text()==signature):
            r = subprocess.run(cmd, capture_output=True)
            if r.returncode:
                raise RuntimeError(str(source)+'\n'+r.stderr.decode('utf8','replace'))
            sig.write_text(signature)
        return obj
    with concurrent.futures.ThreadPoolExecutor(max_workers=args.jobs) as pool:
        objects = list(pool.map(compile_one,sources))
    executable = out/('libmoss_'+mode+'.so')
    rsp = out/'link.rsp'
    rsp.write_text('\n'.join('"'+str(o).replace('\\','/')+'"' for o in objects),encoding='utf8')
    link = [str(host/'bin/clang++.exe'),*common,'-static-libstdc++','-Wl,--gc-sections',
            '-Wl,-z,max-page-size=16384','@'+str(rsp),'-ldl','-lm','-o',str(executable)]
    if args.vulkan: link += ['-lvulkan']
    if args.opencl:
        link += ['-shared','-Wl,--version-script='+str(ROOT/'android/native/gpu-exports.map')]
    if args.opencl:
        # Link-only symbols: the APK uses Android's declared vendor libOpenCL.so.
        # Never package this stub or a copied proprietary driver.
        nm=subprocess.check_output([str(host/'bin/llvm-nm.exe'),'-u',*[str(obj) for obj in objects]],text=True)
        symbols=sorted({line.split()[-1] for line in nm.splitlines() if line.split() and re.fullmatch(r'cl[A-Z][A-Za-z0-9_]*',line.split()[-1])})
        assert 'clGetPlatformIDs' in symbols
        stub=out/'link-only';stub.mkdir(exist_ok=True)
        (stub/'opencl.c').write_text('\n'.join('void '+symbol+'(void) {}' for symbol in symbols),encoding='utf8')
        subprocess.run([str(host/'bin/clang.exe'),'--target=aarch64-linux-android29','--sysroot='+str(host/'sysroot'),
            '-shared','-fPIC','-Wl,-soname,libOpenCL.so',str(stub/'opencl.c'),'-o',str(stub/'libOpenCL.so')],check=True)
        link += ['-L'+str(stub),'-lOpenCL']
    subprocess.run(link,check=True)
    subprocess.run([str(host/'bin/clang++.exe'),*common,'-std=c++17','-static-libstdc++',
        str(ROOT/'android/native/probe.cpp'),'-lneuralnetworks','-lvulkan','-ldl',
        '-Wl,-z,max-page-size=16384','-o',str(out/'libmoss_probe.so')],check=True)
    subprocess.run([str(host/'bin/clang++.exe'),*common,'-std=c++17','-static-libstdc++','-shared',
        str(ROOT/'android/native/accel_probe.cpp'),'-ldl','-lneuralnetworks',
        '-Wl,-z,max-page-size=16384','-o',str(out/'libmoss_accel.so')],check=True)
    shutil.copyfile(host/'NOTICE',out/'LLVM-NOTICE.txt')
    print('Built',executable,flush=True)

if __name__ == '__main__': main()
