# 豆包输入法离线语音全面分析

日期：2026-10-13  
对象：`com.bytedance.android.doubaoime` 1.4.6（versionCode 100406010）  
设备：OnePlus 15 / Android 16 / arm64-v8a  
结论状态：**PARTIALLY VERIFIED**

> 本报告基于用户明确授权的本机只读分析。没有修改豆包输入法、没有修改 Android 系统、没有导出或复用豆包模型、没有读取账号内容、Token、Cookie 或输入历史。

## 1. 直接结论

1. **可以分析，而且已经分析出足够指导我们自研的关键架构。**
2. 豆包的 177MB 离线包不是普通“单个语音模型”，而是一套完整本地 ASR 资源包：流式模型、非流式最终处理模型、VAD、BPE 词表、80-bin FBank 与 CMVN。
3. 它的产品体验本质是：

```text
16kHz 单声道录音
→ VAD 判断说话段
→ 流式模型持续给临时文字
→ 结束时非流式第二阶段修正/补标点
→ final 替换 partial
```

4. 豆包同时支持：在线、离线、在线失败转离线，以及“所有场景仅离线”。这解释了断网仍能工作的事实。
5. **不能把豆包的闭源模型和运行库直接作为我们的产品依赖。** 模型内容采用自定义 FLUTE 容器，内部负载不是可直接读取的标准文本；授权、格式、运行时耦合和长期兼容均无法满足自有输入法要求。
6. 正确做法是清洁室复现它的**交互契约和分层架构**，使用有明确来源和许可的开源模型/运行库。
7. 现有 v0.8 工程要纠正一个状态：`SpeechProvider` 目前只存在于设计文档，代码里尚未实现；真机代码目前只有 16kHz `AudioRecord`、输入会话和 ASCII Probe。

## 2. 已验证资产

### 2.1 APK 与离线包

| 项目 | 真实结果 |
|---|---:|
| APK | 豆包输入法 1.4.6，arm64-v8a |
| APK 大小 | 约 162MB |
| APK SHA-256 | `fb6483b01151bfb07c6ef1f02127bd597eebf39742a88e2ce123ff3a65bb5416` |
| 离线包大小 | 185,377,526 bytes，约 176.8MiB；UI 显示 177M |
| 离线包 MD5 | `b78e55b937a6f7da432097d2d9dc7214`，与文件名一致 |
| 离线包 SHA-256 | `3a46e175f322822f368d970580271888383cc571996776754a17af669b48867d` |
| 容器 | `FLUTE` v2，自定义索引容器 |
| 存储位置 | 应用私有目录，权限 0600 |

### 2.2 FLUTE 容器条目

| 条目 | 字节数 | 作用判断 |
|---|---:|---|
| `/config.yaml` | 5,964 | 总配置；负载不可直接读 |
| `/vad/config.yaml` | 1,338 | VAD 配置；负载不可直接读 |
| `/asr/token/merges.txt` | 620,622 | GPT2/BPE merge 资源 |
| `/asr/token/vocab.txt` | 935,789 | 词表 |
| `/asr/token/add_special_tokens.txt` | 6,803 | 特殊 Token |
| `/asr/models/no_stream.packed` | 13,907,320 | 非流式最终阶段资源 |
| `/asr/models/stream.packed` | 168,742,432 | 主流式识别资源 |
| `/asr/feature_extractor/fbank.conf` | 89 | FBank 参数 |
| `/asr/feature_extractor/cmvn.txt` | 1,584 | 特征归一化 |
| `/vad/encoder/vad_v4.pth.pthlite` | 1,155,064 | 本地 VAD 模型 |

索引为明文；各负载经过编码、混淆或打包，当前证据不能准确区分具体机制。文件扩展名不代表能够直接作为 YAML、MNN 或 PthLite 文件使用。

## 3. Java/Kotlin 调用链

已经从反编译结果确认：

1. `OfflineModelManager` 通过算法资源管理器查找 `acllmtest`。
2. 下载成功后把真实模型路径写入 `asr_offline_model_path`。
3. `OfflineModelHandle` 调用 `SAMICore.LoadOfflineAsrModel(path)` 预加载；卸载时调用 `UnloadOfflineAsrModel()`。
4. ASR 句柄使用 `SAMICoreIdentify_Streaming_ASR_V2`。
5. 创建参数包含：
   - `sampleRate = 16000`
   - `channel = 1`
   - `enable_punctuation = 1`
   - `work_mode`
   - `asr_model_path`
6. `AudioRecord` 使用 16kHz、单声道、PCM16，将帧送给 `SAMICoreProcess`。
7. 完成段落时向 native 层传：
   - `force_asr_twopass = true`
   - `finish_audio = true`
8. 结果回调解析：
   - `text`
   - `is_vad_finished`
   - `stream_asr_finish`
   - `is_offline_result`
9. 产品层状态明确有：`DEFAULT / OFFLINE / ONLINE / ON_OFF`。
10. 官方界面文字明确说明“弱网或无网时自动切换离线模型”，并提供“开启后所有场景仅用离线语音输入”。

## 4. Native 运行库结构

关键库：

- `libaudioeffect.so`：SAMI/Mammon + FLUTE ASR 核心。
- `libbytenn.so`：ByteNN 推理组件。
- `libkeyboard.so` / `libshell.so`：键盘、拼音、候选和业务整合。

静态字符串和符号确认存在：

- `flute.asr.ntp_cif.AsrExecutor`
- `FeatureExtractorActor`
- `EncoderActor`
- `TgtSpkEncoderActor`
- `DecoderActor`
- `DecoderMixerActor`
- `DecoderPostActor`
- `IntegrationActor`
- `GPT2Tokenizer`
- Beam Search、KV Cache
- `stream partial: {}, non-stream final: {}`
- `non_stream_llm_punc_infer`
- MNNInference / PantherInference
- CPUProvider / NNAPIProvider / OPENCLProvider

### 不能越过的证据边界

- 编译进 OpenCL、NNAPI、MNN 或 Panther 支持，**不等于本机离线识别实际使用这些后端**。
- 被动 `/proc/<pid>/maps` 中出现 Adreno/GLES/Vulkan 库，**不能证明 ASR 在 GPU 上运行**。
- 当前被动快照没有映射 177MB 包，因为采样时没有正在进行离线识别。
- 因此实际后端当前为 `UNKNOWN`，不能标记 GPU、NPU 或 NNAPI VERIFIED。

## 5. 豆包的真实设计价值

值得学习的不是闭源文件，而是以下系统设计：

1. **独立 VAD**：录音生命周期和模型解码解耦。
2. **流式 partial + 非流式 final**：快速反馈与最终质量分别负责。
3. **final 覆盖 partial**：临时文字只使用 composing，final 才提交。
4. **显式 finish**：用户结束、VAD 结束和强制切段都能触发最终阶段。
5. **单一结果协议**：在线与离线结果通过同一事件结构回传，并标记来源。
6. **模型可下载、可校验、可升级、可卸载**：APK 不把所有权重写死。
7. **弱网自动回退 + 全离线开关**：两种用户诉求同时满足。
8. **内存生命周期**：模型预加载和句柄创建分开，避免每句话重新载入。

## 6. 对我们当前 v0.8 的影响

### 当前真实状态

| 能力 | 状态 |
|---|---|
| 系统 IME 注册 | VERIFIED |
| ASCII 按键写入当前 InputConnection | VERIFIED |
| 16kHz 本地录音启停 | VERIFIED |
| 输入 session/generation 隔离代码 | TESTED，部分真机场景 PENDING |
| `SpeechProvider` 文档 Contract | DESIGNED |
| `SpeechProvider` 代码实现 | PENDING |
| 中文 ASR | PENDING |
| 流式 partial | PENDING |
| 非流式 final / 标点 | PENDING |
| 断网零云回退 | PENDING |

### 需要固定的自有接口

```text
AudioSource
VadProvider
StreamingRecognizer
SegmentRecognizer
PostProcessor
ModelStore
RecognitionPolicy
```

统一事件至少包括：

```text
Partial(segmentId, revision, text, stablePrefix)
Endpoint(segmentId, reason)
Final(segmentId, text, source, pass, latency)
Error(code, recoverable)
```

硬规则：

- partial 只写 composing，不进入历史、不发给电脑。
- final 必须覆盖同一 segment 的 partial，且只能提交一次。
- 过期 generation、过期 revision、已取消 session 的回调全部丢弃。
- `finish()` 必须完成当前段的 final；`cancel()` 禁止任何旧结果回填。
- Local 模式禁止静默回退云端；Smart 模式才允许路由变化。
- 模型由 `id + version + size + sha256 + license` 管理，不写死绝对路径。

## 7. 推荐实施路线

### Probe B1：先建立可比较的真实基线

- 实现上述 Provider/Event/ModelStore 代码，而不是把模型直接写进 IME Service。
- 使用一个许可清晰、Android 已有真实实现的离线模型完成：VAD、final、取消、模型复用、断网零云回退。
- SenseVoice INT8 可以作为基线，但不再预设为最终模型。
- 官方 sherpa-onnx 已提供 Android 的 VAD+非流式 ASR、模拟流式和真正两段式示例，因此不需要自己从零造推理框架。

### Probe B2：实现真正的两段式体验

- 小型流式中文模型：负责低延迟 partial 和 endpoint。
- 高质量非流式模型：负责 final、标点、数字规范化和最终纠错。
- 两模型可先各自独立，先证明 UX；随后才研究共享编码器、量化和更小包体。

### 后端优先级

1. **CPU/NEON 基线**：可调试、行为确定、对小块流式输入通常更稳定。
2. **QNN/NPU 小实验**：官方 sherpa-onnx 已有 Qualcomm QNN Android 示例，但具体模型、动态形状、KV cache 和本机驱动仍需真机证明。
3. **OpenCL/GPU**：只在 CPU 基线后做单变量测试，不能因豆包库中有 OpenCL 字符串就照搬。

不采用：复制豆包模型、打包其 native 库、绕过容器或认证、把未知许可资源放进我们的 APK。

## 8. 最小真实评测矩阵

同一批录音、同一 AudioSource 文件注入路径比较：

- 中文安静环境。
- 咖啡厅级噪声。
- 中英混说。
- 日期、金额、电话号码。
- 30–60 秒连续口述。
- 用户本人常用词与海南地名。

核心指标：

| 指标 | 统计方式 |
|---|---|
| 中文 CER | 去标点后统一全/半角，按场景分组 |
| 标点 F1 | 逗号、句号、问号分别统计 |
| 首 partial 延迟 | 语音起点到首个 partial，P50/P95 |
| final 延迟 | endpoint 到 final，P50/P95 |
| partial 稳定性 | 最终被改写字符比例 |
| RTF | 推理耗时 / 音频时长 |
| 冷/热加载 | 首次加载、已驻留再次识别 |
| 内存 | idle-loaded PSS、识别峰值 PSS |
| 发热/电量 | 10 分钟连续口述，对比仅录音基线 |
| 离线真实性 | 断网完整成功，且无网络请求 |

单次“能识别”不能决定模型。必须用相同语料比较至少 P50/P95、CER 和内存。

## 9. 尚未完成的主动运行验证

要证明豆包本机实际用 CPU、OpenCL、NNAPI 还是其他后端，需要在**正在离线识别的 5–10 秒窗口**采集：

- 目标进程 maps 的增量变化；
- 目标线程名和 CPU jiffies 增量；
- 过滤后的 logcat 后端初始化日志；
- 条件允许时采集短 Perfetto，关联 CPU 调度、GPU work period 与 NNAPI/vendor HAL；
- 同时记录模型映射、PSS 和热状态。

验收边界：

- maps 只证明库/文件加载。
- CPU 线程耗时只证明 CPU 参与。
- GPU work period 与识别时间严格相关，才支持“GPU 执行”的结论。
- NNAPI/vendor HAL 调用与推理窗口相关，才支持“NNAPI/NPU 执行”的结论。
- 仍无法取得相关证据时，后端继续标 `UNKNOWN`。

这一步需要用户手动切换到豆包输入法并触发一次“仅离线”识别，或另行批准临时切换系统输入法；本轮没有擅自修改系统设置。

## 10. 多方复核与 Codex 裁决

- **Jev**：将任务判为研究/Android实现约束类，不参与设备执行。
- **Claude**：建议清洁室复现“VAD + 流式首遍 + 非流式 final”，但先用单模型基线把 final、状态机和提交语义跑通。
- **Antigravity**：建议先做只读主动 trace 再判断 CPU/GPU/NPU；流式小块优先 CPU，较大第二遍再测试 GPU；建议权重映射、空闲释放和必要时独立语音进程。
- **Codex 裁决**：接受分层架构和分阶段实施；不接受未经真机证明的后端结论。Antigravity 提到的 mmap、Perfetto 可见性和独立进程收益都必须在我们的运行库与 Android 16 真机上验证，不能直接标 VERIFIED。

## 11. 外部事实核验

- sherpa-onnx 官方 Android 目录明确提供 `SherpaOnnx2Pass`：流式模型首遍、非流式模型第二遍。
- 官方 Python 两段式示例明确说明：首遍用于临时结果和 endpoint，第二遍提供更高准确率 final。
- 官方 Android 文档也提供模拟流式、VAD+非流式识别以及 Qualcomm QNN 示例。
- SenseVoice 源码许可与模型权重许可必须分开；使用具体转换制品前仍要核对对应模型卡与再分发条款。

来源：

- https://github.com/k2-fsa/sherpa-onnx/tree/master/android
- https://github.com/k2-fsa/sherpa-onnx/blob/master/python-api-examples/two-pass-speech-recognition-from-microphone.py
- https://github.com/k2-fsa/sherpa/blob/master/docs/source/onnx/android/prebuilt-apk.rst
- https://github.com/QwenAudio/SenseVoice

## 12. 最终判断

### 已经 VERIFIED

- 177MB 离线包真实存在，大小和哈希已核对。
- 包含流式、非流式、VAD、Tokenizer、FBank、CMVN。
- Java 层真实加载离线模型并向同一 ASR 句柄输入 16kHz 单声道音频。
- 回调协议真实区分 partial/final、VAD 完成和离线结果。
- 产品真实支持弱网/无网自动回退和全离线模式。

### PARTIALLY VERIFIED

- “两段式流式预览 + 最终修正/标点”由多项静态证据强支持，但内部每个模型的准确职责和张量结构未完全解码。
- CPU/OpenCL/NNAPI/NPU 的实际运行选择未知。

### 不应继续做

- 不继续破解或复用豆包权重。
- 不把闭源 `libaudioeffect.so` / `libbytenn.so` 集成进我们的产品。
- 不根据文件大小猜模型参数量。

### 下一开发动作

先把文档中的 `SpeechProvider` 真正实现为代码 Contract 和状态机，再用一个开源离线模型完成 Probe B1。通过同一评测集后，再决定是否进入真正的流式首遍 + 非流式 final。
