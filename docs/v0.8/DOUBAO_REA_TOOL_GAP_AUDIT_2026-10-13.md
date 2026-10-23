# 豆包离线语音逆向工具能力与升级判断

日期：2026-10-13  
范围：只读研究；未修改豆包输入法、Android 系统或模型文件。  
结论：**现有工具满足架构学习，但不足以判定核心权重能否脱离私有运行库独立转换；有必要做定向升级。**

## 1. 本轮真实证据

### 当前工具链

已安装并可运行：JADX 1.5.6、apktool 3.0.3、aapt/aapt2/apksigner/zipalign、Frida 17.2.14、LLVM readelf/nm/objdump/strings、strace/tcpdump、REA Android Workbench v1，以及由工作台受控调用的 Ghidra 12.1.4 ARM64/Bionic headless。

REA Workbench v1 已有真实验收：APK/AAB/APKS/Split、签名、资源、JADX、JNI 图谱、ARM64 Ghidra、动态证据导入、断点恢复，既有 55 项测试通过。

### 大 APK 稳定性缺口

对豆包输入法 1.5.1、170,975,042 字节 APK 的真实分析：

- Workbench 进入分析阶段；
- REA MCP 的 inspect_android_package 使用固定 60 秒；
- 66.143 秒时以 REQUEST_TIMEOUT 失败；
- 整个任务被标记 FAILED；
- 外层 --timeout 没有传入 MCP 内部调用。

这证明当前“大 APK 支持”还没有覆盖豆包这种规模与复杂度。

### Native 深分析能力

对豆包 1.4.6 的 libaudioeffect.so 真实运行 Ghidra：

- 文件：7,462,456 字节
- SHA-256：54e3468cf30eaf2ae23ddb6624026c8d667ef638d9be02f19eaeb686435c8f71
- 架构：AARCH64:LE:64:v8A
- 观察到函数：16,422
- 指定函数 SAMICoreSetProperty 成功反汇编和近似 C 反编译
- 运行耗时：251.965 秒
- 结果：VERIFIED

反编译真实显示 ASR V2 reset、force finish、interrupt 等属性分发路径，说明工作台能够进入私有 Native 运行库，不只是搜索字符串。

同时发现：整库分析产生约 4.2MB 诊断日志；有大量 GccExceptionAnalyzer 异常反汇编提示；现有入口只能按已知函数名选择，不支持“字符串 → 交叉引用 → 所属函数 → 调用链”的自动追踪；默认 APK Native 选择逻辑会选最小 ARM64 库，不一定选到 libaudioeffect 这种关键业务库。

## 2. 能力判断

| 目标 | 当前状态 | 是否满足 |
|---|---|---|
| APK结构、签名、权限、资源 | VERIFIED | 满足 |
| Java/Kotlin调用链 | VERIFIED/PARTIAL | 满足架构研究 |
| Java native方法、loadLibrary、JNI导出关联 | VERIFIED/PARTIAL | 基本满足 |
| 指定Native导出函数反编译 | VERIFIED | 满足 |
| FLUTE容器索引、条目、大小、哈希、熵 | VERIFIED（目前为专项手工流程） | 能研究，但未集成 |
| 豆包两阶段ASR设计判断 | PARTIALLY VERIFIED | 已足够指导自研 |
| 大型APK稳定自动分析 | FAILED（1.5.1真实超时） | 不满足 |
| 找到LoadOfflineAsrModel内部实现和解码路径 | PENDING | 不满足 |
| 证明实际CPU/OpenCL/NNAPI/NPU后端 | UNKNOWN | 不满足 |
| 还原模型张量图、自定义算子和量化格式 | UNKNOWN | 不满足 |
| 将核心权重转换为开放格式并独立运行 | UNKNOWN | 不满足 |

## 3. 是否需要升级

**需要，但应定向升级，不要再堆通用工具。**

现有工具已经足够继续做输入法架构设计；如果目标进一步提高到“判断豆包权重能否脱离 FLUTE/ByteNN/SAMI 独立运行”，必须补齐下面能力。

## 4. 推荐升级顺序

### P0：马上修复

1. **大型 APK 分阶段与超时传递**
   - 去掉 MCP 内部固定 60 秒；
   - 外层 timeout 真实传到 inspect/search；
   - Manifest、DEX、Native、Asset 分阶段并记录心跳；
   - REA provider 失败时保留已完成证据，继续 JADX/资源/Native，不让整单全部失败；
   - 用豆包 1.5.1 作为真实回归目标。

2. **指定 Native 库与定向函数分析**
   - 显式选择 libaudioeffect.so、libbytenn.so；
   - 支持符号名、地址、字符串交叉引用三种入口；
   - 输出 callers/callees、字符串引用函数、文件读取和 provider 创建路径；
   - 避免每次整库重复分析，复用 Ghidra project。

3. **FLUTE 专用只读分析器**
   - 自动解析 v2 索引；
   - 验证 offset/size/重叠/越界；
   - 每条目生成哈希、熵、魔数、压缩/编码候选；
   - 只提取小配置和有限头部样本；
   - 与 Java/JNI/Native 字符串形成关联图；
   - 不导出完整模型，不宣称已解密。

### P1：决定实际运行路径

4. **受控运行时证据采集**
   - 用户真实触发一次“仅离线识别”；
   - 记录模型 fd/maps、线程 CPU 增量、PSS、过滤日志；
   - 条件允许时采集短 Perfetto；
   - 只把 CPU/GPU/NNAPI 与识别时间严格对应的结果记为证据。
   - Perfetto或进程级Root追踪涉及系统/第三方应用运行观测时，按规则单独说明批次后执行。

5. **1.4.6 与 1.5.1 差异分析**
   - 比较 FLUTE 索引、Native 库、模型版本、Java调用与后端字符串；
   - 优先找格式变化和加载函数变化，不重复全量无差别扫描。

### P2：只有前两级仍无法回答时

6. **Frida 定点观测**
   - 仅 Hook 已确认的模型加载、文件读、provider 初始化和 process 边界；
   - 记录调用顺序、路径、大小和返回码；
   - 不抓账号数据、输入历史、Token，不导出完整权重；
   - 不修改 APK。

## 5. 不建议增加

目前不需要：再安装一套普通反编译器、购买 IDA/Hopper、做所谓“万能脱壳”、对离线识别先做全量网络抓包、直接复制豆包运行库和模型进我们的输入法，或安装 Debian/proot。

这些不会解决当前最关键的“超时、定向关联、真实后端证据”问题。

## 6. 三方意见与 Codex裁决

- Jev：任务属于研究/Android约束，低直接操作风险，由 Codex执行验证。
- Claude：现有工具足够架构学习；60秒超时是工程缺陷；先修稳定性与分阶段分析，权重转换不能由现有静态证据下结论。
- Antigravity：P0为大APK分阶段和JNI/FLUTE关联；P1为短时Perfetto/procfs证据；Frida/strace排在P2。
- Codex：采纳定向升级。Ghidra真实结果证明工具不是“完全看不进去”，但现阶段仍缺少从FLUTE加载入口追到解码/算子/后端的自动证据链。

## 7. 最终结论

- **用于学习豆包的产品架构：当前工具够用。**
- **用于深入判断私有核心模型能否转换：当前工具不够。**
- **有必要升级到 REA Android Workbench v1.1，但不需要推倒重来。**
- 最优升级目标是：稳定处理大APK、自动分析FLUTE、定向Native交叉引用、受控运行时追踪。
- 即使完成升级，也只能把“能否转换”从 UNKNOWN 推进到有证据的 PASS/BLOCKED，不能预先保证一定能转换。

