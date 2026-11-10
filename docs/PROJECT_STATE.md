# 当前版本

燿识 Android 0.7.0，包名 `win.youhoo.moss`，版本号 19。目标与最低系统均为 API 35，编译 SDK 36，仅打包 arm64-v8a。首页与记录页面由内置 WebView 渲染，识别通过本地原生引擎执行。

模型下载由用户发起的 Android dataSync 前台服务执行。下载、校验和启用使用应用私有目录。进程退出会留下可续传的文件。AI 纪要使用单独的 API 配置。系统温控负责调频。

发布后端为 CPU、Vulkan 和 OpenCL。玄戒探针在独立的研究目录，通过真机证据决定是否进入发行包。[研究结果](research/xring-o3.md)
