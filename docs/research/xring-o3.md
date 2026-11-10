# 玄戒 O3 的 INT8 矩阵结果能留在片上吗？

在一台玄戒 O3 平板上，MOSS 编码器第 20 层的 FFN2 可以把 INT8 点积直接交给片上 FP32 缩放和累加，主机只读回最后 6,144,000 字节。这个版本的计算结果与 CPU 逐位一致，但整层仍慢于 CPU。燿识 0.7.0 因此继续使用公开发行的 CPU、Vulkan 和 OpenCL 后端。

## 同一输入，三种放置方式

输入来自同一段 90 秒录音的中间 30 秒。张量为 1,500 帧 × 4,096 输入 × 1,024 输出，包含偏置和残差。计时从主机端 FP32 输入到主机端最终 FP32 输出，包括 NPU 所需的打包、量化、提交、等待和读回，模型加载及建图在计时前完成。

| 第 20 层 FFN2 | 六组中位数的中位数 | 与 CPU 逐位比较 |
| --- | ---: | ---: |
| 四线程 CPU | 72.96 ms | 基准 |
| NPU，INT8 点积中间结果在 DDR | 283.99 ms | 1,536,000 个值全部相同 |
| NPU，INT8 点积中间结果在 L2B | 181.47 ms | 1,536,000 个值全部相同 |

三条路径的六种顺序各执行一遍，每组在新进程中先预热一次，再计时三次。L2B 比同图 DDR 路径缩短 36.1%，但耗时仍为 CPU 的 2.49 倍，六组均未达到比 CPU 快 5% 的门槛。上述数据属于单层。这轮没有测完整录音的耗时或整机能耗。[六组时延和数据量](ffn2-layer20.json)

## 哪些细节改变了输出

1. **按正确的缓存访问顺序检查写回。** 输出先由主机写入哨兵，再结束主机缓存访问。启动设备、同步流，重新开始 CPU 访问后逐位读取。另做跳过启动的负对照，2,048 个哨兵全部保留。此前同类顺序错误曾让可工作的 RMSNorm 表现为无输出。
2. **保留卷积封装生成的描述符。** 用 1×1 INT8 卷积表达线性层，由厂商的权重重排入口生成实际权重布局。先按 DDR 描述符建图，再以同一张量分别生成 DDR/L2B 映射作对照。在这版驱动上，只改输出映射的地址字段和位置位（第 0 个字的 bit 22），保留事务 ID 和拆分参数。探针遇到与预期不同的映射会退出。[探针的映射处理](../../android/research/xring_npu/onchip_graph.h)
3. **在图内完成相邻计算。** 用图中的 L2B 分配、屏障和释放节点管理暂存区。16 次提交，每次包含 64 对矩阵与更新节点。单个面板租用 6 MiB L2B。累计 3 GiB 的矩阵中间写入改为图内的片上地址。这个数是由图布局核算的逻辑字节。硬件 DRAM 计数器未采样。

## 自己检查单个 tile

测试设备为 O3，固件 OS4.0.16.0.XBMCNXM。下面的开发探针依赖这版厂商 ABI 及设备已有的驱动访问权限。小 tile 的用途是验证数据位置、读回和哨兵，不足以复现上表的整层基准。输入生成器只使用合成整数与二进制可精确表示的缩放值。

在仓库的 `android/research/xring_npu` 目录运行，指定 NDK 及从自己设备取得的对应 `libxnnl.so`。组装器核验模板指纹。以下为 Linux 主机示例，Windows 使用相应 NDK 的 `clang++.exe --target=aarch64-linux-android35`。

```sh
python make_demo_packet.py tile.bin
python build_mida_q8_program.py /path/to/device/libxnnl.so dot-update dot-update.elf
"$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android35-clang++" -std=c++17 -O2 -static-libstdc++ int8_onchip_tile.cpp -ldl -o int8_onchip_tile
```

将这三个产物放在同一设备测试目录，在已具备相应访问权限的 shell 中运行。图可视化文件写入传入的可写路径。

```sh
./int8_onchip_tile tile.bin dot-update.elf ddr ./ddr.dot
./int8_onchip_tile tile.bin dot-update.elf l2b ./l2b.dot
./int8_onchip_tile tile.bin dot-update.elf skip ./skip.dot
```

前两项应在三轮中分别显示 `wrong=0 poison=0`。负对照应显示 `wrong=2048 poison=2048` 并返回状态 4。仓库发布探针源码和合成输入生成器。全层基准使用的私人录音及其激活值保存在原测试现场。

## 与上游的关系

驱动结构和调用顺序的起点来自 j4ger 的 o3-experiments。引用的 `mida_types.h` 保留原 MIT 许可，本文的测量覆盖燿识的编码器工作负载。适合回馈上游的是小 tile 的缓存同步负对照及 DDR/L2B 映射差异。产品的性能取舍和录音处理链在这里维护。可直接引用本页和探针。[上游固定版本](https://github.com/j4ger/o3-experiments/tree/0a18df6f5fa53ec37bb2bde706187f5c8e1cc4e7) · [上游对厂商 GEMM 路径的研究](https://github.com/j4ger/o3-experiments/blob/main/docs/NPU-GEMM-VENDOR-PATH.md) · [上游 MIT 许可](../../android/research/xring_npu/upstream/LICENSE)
