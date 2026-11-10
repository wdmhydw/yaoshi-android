# 从源码构建

此仓库提供已验证的 Windows x64 主机构建入口。需要 Python 3.10+、JDK 17、Visual Studio 的 x64 C++ 工具链、Android SDK Platform 36、Build Tools 36.0.0 与 NDK 30.0.16248370。准备一个只含 ASCII 字符的中间产物目录。着色器生成器需要该目录。以下为 PowerShell，在仓库根目录运行。

```powershell
$jdk = $env:JAVA_HOME
$sdk = $env:ANDROID_SDK_ROOT
$ndk = "$sdk/ndk/30.0.16248370"
$work = 'C:/Build/yaoshi'
$vcvars = & "${env:ProgramFiles(x86)}/Microsoft Visual Studio/Installer/vswhere.exe" -latest -products '*' -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -find 'VC/Auxiliary/Build/vcvars64.bat'

python android/scripts/prepare_sources.py
python android/scripts/prepare_sources.py --verify-only
python android/scripts/prepare_speaker.py --cache "$work/speaker"
python android/scripts/build_shader_generator.py --vcvars "$vcvars" --work "$work/shader-tools"
python android/scripts/build_shaders.py --generator "$work/shader-tools/vulkan-shaders-gen.exe" --glslc "$ndk/shader-tools/windows-x86_64/glslc.exe" --source "$work/shader-tools/source" --output "$work/shaders"
python android/scripts/build_native.py --ndk "$ndk"
python android/scripts/build_native.py --ndk "$ndk" --vulkan --shaders "$work/shaders"
python android/scripts/build_native.py --ndk "$ndk" --opencl --opencl-headers "$PWD/vendor/OpenCL-Headers"
python android/scripts/build_apk.py --sdk "$sdk" --jdk "$jdk" --work "$work/apk" --test-install
adb install -r android/out/yaoshi-qa.apk
```

测试包使用独立 ID `win.youhoo.moss.qa`。普通开发构建省略 `--test-install`。正式签名使用 `--release --keystore <你的密钥文件> --key-alias <别名>`，通过当前进程的 `YAOSHI_STORE_PASSWORD` 和 `YAOSHI_KEY_PASSWORD` 提供密码。已有签名轮换历史可传入 `--lineage <文件>`。脚本验证签名与 16 KB 对齐并生成构建摘要。语音识别 GGUF 由应用首次使用时获取，离线声纹模型在构建阶段按固定摘要准备。[依赖锁定清单](../android/dependencies.lock.json)

## 回归检查

```powershell
python android/scripts/test_ai.py --jdk "$jdk"
python android/scripts/test_live_preview.py --jdk "$jdk"
python android/scripts/test_speakers.py --jdk "$jdk"
node --test android/tests/preview.test.cjs android/tests/transcript.test.cjs android/tests/timeline.test.cjs android/tests/orb-lifecycle.test.mjs
New-Item -ItemType Directory -Force "$work/model-test" | Out-Null
& "$jdk/bin/javac.exe" -encoding UTF-8 -d "$work/model-test" android/app/src/main/java/win/youhoo/moss/AsrModelFile.java android/tests/AsrModelFileTest.java
& "$jdk/bin/java.exe" -cp "$work/model-test" win.youhoo.moss.AsrModelFileTest
```

在安装测试包的设备上从空状态执行“获取模型”，尝试暂停再恢复，然后运行内置中文示例。在记录页打开该条转写并试用回听、菜单和导出。
