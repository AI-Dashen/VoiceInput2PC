# 万能输入法本地 ASR 内存核查（2026-10-22）

状态：`VERIFIED`（OnePlus 15 真机只读测量）

## 结论

万能输入法主进程内存正常，但独立本地语音识别进程 `:local_asr` 的内存足迹明显过大，超过项目原定 IME 稳态 PSS `<=350MB` 目标。

## 真机证据

- 主进程 `io.github.amxooo.voiceinput2pc`：TOTAL PSS 约 75MB，RSS 约 165MB。
- ASR 进程 `io.github.amxooo.voiceinput2pc:local_asr`：
  - 当前物理 RSS 约 140MB；
  - resident PSS 约 56MB；
  - Swap 约 1.18GB；SwapPss 约 1.16GB；
  - Android `dumpsys meminfo` 总内存足迹约 1.21GB。
- 两个进程合计总内存足迹约 1.29GB。
- ASR 进程已空闲约 2 小时，状态为 cached/empty、oom_adj 999，但仍保留大量已换入 zRAM 的原生分配。
- 活跃识别后的历史快照曾显示 ASR 物理 RSS 约 1.23GB，说明热态确实会实际占用约 1.2GB，而不只是统计误差。

## 主要来源

`/proc/<pid>/smaps` 聚合显示，主要占用来自 `[anon:libc_malloc]`：虚拟分配约 1.99GiB，其中约 1.12GiB 已进入 Swap。对应 MNN 编码器、独立解码器、logit 会话、权重展开及推理工作区。不是输入法界面、APK 图片或普通 Java 堆导致。

## 性质判断

当前短时采样没有发现持续单调增长，因此尚不能认定为传统“内存泄漏”。现有证据更符合：模型热态驻留过大，空闲解绑后原生内存未充分归还，进程又被 Android 作为 cached empty 保留，导致 zRAM 长期承压。

## 修复优先级

1. P0：空闲 60–120 秒且无请求时，显式释放全部 Native/MNN 引擎并结束专用 `:local_asr` 进程；下一次语音再冷启动。
2. P0：加入前后台、录音中、批处理中的状态保护，避免识别过程中误杀。
3. P1：分别测量 encoder/decoder/logit 的峰值，释放 prepare/inference 临时缓冲区，评估 MNN `Memory_Low` 和懒加载。
4. 验收同时考核热态峰值、空闲 2 分钟内存、二次启动延迟和连续识别稳定性，不能只看 APK 大小。
