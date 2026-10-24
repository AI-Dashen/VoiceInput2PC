# 万能输入法系统优化计划

日期：2026-10-24  
状态：`DESIGNED / IMPLEMENTATION_PENDING`

## 1. 当前真实基线

- 手机当前安装：`0.8.35-stabilization`（versionCode 45）。
- 当前源码：`0.8.36-stable-memory`（versionCode 46，尚未由用户完成真机验收）。
- 输入法主进程约69—75MB PSS，主要问题位于独立`:local_asr`进程。
- 自有ASR模型资产约159MB：Encoder外部权重119.1MB、Decoder 13.9MB、Logit外部权重25.1MB，其余图和词表约1.2MB。
- 自有ASR热态约1.2GB；主要为`[anon:libc_malloc]`中的MNN权重展开、重排和工作区。
- 豆包真实对标：普通短输入约105—149MB PSS；长输入峰值约718MB PSS；停止约5分钟仍约569MB。
- 当前同时常驻Encoder RuntimeManager/Module、自有Decoder、Logit Interpreter/Session，并使用`Precision_High + Memory_Normal + Power_High + 2线程`。

## 2. 目标与门禁

### 内存

- 普通30秒以内听写：PSS目标`<=300MB`。
- 3分钟长听写：峰值PSS目标`<=800MB`，不得持续单调增长。
- PSS+SwapPss：普通听写目标`<=350MB`。
- 最后一次识别后90秒内：`:local_asr`退出；主进程不退出。
- 连续30次跨App识别：基线、峰值和空闲值不逐轮增长。

### 质量与延迟

- 固定10段普通话样本逐字结果不得退化；另统计CER。
- 停说到最终文字P50/P95分别记录；任何优化不得把P95劣化超过10%，除非换来明确且经用户接受的内存收益。
- 冷启动、60秒热复用和空闲释放后的再启动分别测量。

### 稳定性

- 连续输入、取消、无声、跨App、进程回收、内存压力和录音批转写均有明确终态。
- 不在同一实验中同时改变精度、内存模式、线程数和模型格式。

## 3. 实施顺序

### P0：只增加内存账单，不改变推理结果

在进程启动、RuntimeManager创建、Encoder Module加载、Decoder加载、Logit Session创建、首次Encoder、首次Logit、完成、取消和释放后记录：

- `/proc/self/smaps_rollup`的RSS/PSS/SwapPss；
- Android Native Heap；
- MNN RuntimeManager与Session的`MEMORY`自报值；
- 阶段耗时和输入帧数。

验收结果必须能回答Encoder、Decoder、Logit和临时工作区各自带来多少增量。

### P1：低风险独立实验

1. 只对Logit测试`releaseModel()`，确认Session之后是否仍支持当前固定shape执行。
2. Decoder的13.9MB只读文件改为mmap候选，对照现有vector加载；收益小则不引入复杂度。
3. 分别测试Encoder和Logit的`Memory_Low`，不同时开启。
4. 检查Encoder Module与Logit Interpreter能否共享RuntimeManager；先测内存与延迟，再决定是否合并。
5. 识别完成后释放仅用于本轮的输入、输出和临时变量；保留必要模型热态。

### P2：量化与权重常驻策略

- 核实Encoder/Logit图中量化元数据和实际执行内核，证明权重是否在加载时展开为FP32。
- 对比量化常驻、外部权重文件映射和MNN外部权重目录；任何路线都用同一10段语料验证Token、文本、CER和P95。
- 若MNN通用路径仍无法接近目标，才评估把已经恢复的低比特LUT内核用于最大矩阵层；不直接重写完整引擎。

### P3：生命周期与产品状态机

- 保留0.8.36的60秒热复用、75秒兜底和专用进程退出。
- 模型加载、监听、识别、预览、取消、释放和冷启动必须形成单一状态机；禁止旧回调复活新会话。
- ASR内存达标后，再处理连续输入、九宫格/26键和界面细节。

## 4. 测试矩阵

- 固定音频：1秒、10秒、30秒、180秒。
- 操作：完成、取消、无声、识别中切换App、空闲90秒、再次冷启动。
- 连续性：30轮短输入、5轮长输入、输入法窗口重建和目标编辑器切换。
- 宿主：普通EditText、WebView、Termux、微信类聊天框。
- 每个候选只改变一个主要变量并保留原始基线。

## 5. 开发原则纠正

后续顺序固定为：使用环境与流程→参考基线→资源预算→运行时/工具选型→组件原型→状态机→实现→自动测试→用户真机验收。功能能运行但资源门禁未通过时，不标记为完成。

## 当前执行结果

- ASR 组件内存拆分、低内存MNN构建、10条中文语料回归、APK构建已完成。
- 当前状态：TESTED；等待用户安装后做真人语音与空闲回收验收。
- 实测详见 V0.8.37_LOW_MEMORY_ASR_2026-10-24.md。
