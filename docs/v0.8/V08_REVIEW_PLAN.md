# 万能输入法 v0.8｜自有输入法三方研究方案

日期：2026-10-12，2026-10-13更新。状态：方向与效果图已确认；Probe A代码与构建完成，真机验证PENDING。未下载ASR模型、未启用系统输入法、未操作Android系统设置。v0.7保留。

## 1. 已确认的目标
一个APK：在万能输入法正文内使用，也能作为系统输入法在其他App输入框使用。我们自己负责品牌、键盘界面、交互、编辑/提交状态与电脑链路；不把他人整仓改名，也不宣称第三方组件版权属于我们。
本地中文语音、中文文字键盘是同等核心功能。首版面向一加15，架构保持标准Android API；其他手机兼容性需另验收。

## 2. 成熟产品怎么研究
有源码的项目优先读源码、接口和状态机，比反编译APK信息完整且可追踪。APK资源/字节码分析仅用于确有必要的行为核对，不复制未知来源实现、付费资源或认证逻辑。不能因为逆向出来就自动获得代码使用权。
不选一个所谓“全方面最好”整仓搬运，而是逐项研究：BiBi的录音/离线ASR/取消；Rime的中文组词候选；FUTO的键盘与语音组合交互。最终界面、交互和胶水代码由我们实现，组件保留来源。

## 3. 候选比较与推荐
| 对象 | 核实的价值 | v0.8决定 |
|---|---|---|
| BiBi Keyboard | Apache2.0；源码有SenseVoice离线解码、伪流式预览、独立标点管理、对外语音接口 | 借鉴并按许可复用必要语音模块，不复制其整套App/云供应商/Pro能力；不当作已具备完整中文键盘 |
| sherpa-onnx + SenseVoice INT8 | 官方有Android、Java/Kotlin与中文离线识别路线 | 优先本地ASR候选；先CPU，必须本机验证，不默认GPU/NPU |
| librime | 核心BSD-3-Clause，成熟中文输入引擎 | 中文26键/九宫格解码优先候选，自写JNI薄适配与键盘界面；词库/方案及依赖许可另核对 |
| Trime同文 | Android Rime输入法可作生命周期/候选/布局参考，整App GPLv3 | 不直接复制整套UI/JNI并误称全部BSD；如复用其代码需明确遵守该部分许可 |
| FUTO Keyboard | 已有语音+文字整合；Source First1.1 | 交互研究参考，不作为本版默认代码基础；过去中文体验失败不能用上游说明替代本机测试 |

结论：自有键盘外壳 + 可替换语音Provider + 可替换中文输入引擎 + 原v0.7提交/电脑传输。模型训练不属于本版。

## 4. 一个安装包、两入口、共用核心
```text
万能输入法 APK
├ App正文/电脑工作区
│   └ 自有键盘 → 本地草稿 → 已确认段落 → v0.7 Relay → Windows当前光标
├ Android InputMethodService
│   └ 同一自有键盘 → 当前InputConnection → 其他App当前输入框
└ 同进程共享核心
    ├ KeyboardEngine：拼音/候选/选词/编辑
    ├ LocalAsrProvider：录音/VAD/离线ASR/可选标点
    ├ InputSession：临时结果、稳定结果、取消、目标与revision
    ├ ModelManager：校验、懒加载、单实例、卸载
    └ HistoryPolicy：App正文历史；其他App默认不保存正文
```
首版不做两个模型进程。Application单例只能保证同进程，不能夸称跨进程共用。两个入口同时争用麦克风时串行仲裁，不能两个录音器同时工作。
App内键盘通过EditorTarget写草稿，系统IME通过InputConnection写宿主；共用逻辑，不同时走两条提交路径。既有Java核心保留，新模块可用Kotlin/Android View；不为引入Compose而重写所有现有代码。

## 5. v0.8必须有的功能
1. 中文26键全拼：组字、拼音显示、候选栏、候选翻页/展开、选择后上屏。
2. 中文九宫格：字母键到拼音歧义解码、拼音选择列、候选选词。不是显示9个数字就算完成。
3. 英文、数字、常用中英文符号、空格、换行、删除、选区替换。
4. 本地语音：开始/结束/取消、连续分段、临时结果与已确认结果区分、清楚显示录音/识别状态。
5. 标点与数字规范化：本地处理，保留原识别可纠正；标点模型独立按需加载。不得编造人名、数字或自动改写语义。
6. 同一编辑区语音/打字混用，光标插入与选区替换，不总把文字堆到末尾。
7. 系统IME启用引导、语言/布局/键盘高度/振动/模型设置，缺模型时文字键盘仍可用。
8. 原电脑选择、持续输入、去重/回执、历史、四格式导出继续工作。
不纳入：全局AI聊天、自动发送消息、自动控制其他App、云端ASR、LLM润色、后台常听、唤醒词、模型自训练、自动静默升级模型。

## 6. 最重要的提交契约
- 临时语音、拼音组字：只显示，不传电脑，不作为最终历史文本。
- final语音或候选确认：生成稳定文本事件；每条事件有本地session、目标、revision及id。
- App电脑工作区：用户点开始输入后，沿用v0.7连续确认段自动输入，不改成每段都要求手动发送。开启前清楚显示目标电脑。
- 其他App：只向当前宿主InputConnection上屏，不调用电脑Relay。首版不增加“从其他App顺便发电脑”开关。
- 输入框切换/输入连接失效/取消/新录音：旧任务回调被generation隔离，不得把旧结果打入新的输入框。
- 已发到电脑的前缀再修订：保留v0.7暂停与核对恢复，不能自动远程退格。未提交的本地部分正常编辑。
- 重复回调、按钮连点、网络重试不能造成重复文字。
- 密码字段关闭语音、学习、历史和电脑链路；NO_PERSONALIZED_LEARNING等标志至少关闭个性化学习与记录，不擅自把所有该标志字段当密码框。
- 不自动按宿主App的“发送”按钮；键盘换行/EditorAction必须遵从真实字段且用户明确操作。

## 7. 离线模型与资源方案
优先候选：官方文档引用的csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/model.int8.onnx。远端metadata显示239,233,841字节（约239MB/228MiB），tokens.txt 315,894字节。LFS预期SHA256 c71f0ce00bec95b07744e116345e33d8cbbe08cef896382cf907bf4b51a2cd51。这里只查询了元数据，未下载或本机验证。
APK包含功能代码，模型是可导入/按明确清单准备的资源包；这是同一个App，不要求另装BiBi/FUTO，也不要求账号或API Key。
SenseVoice走OfflineRecognizer类的离线分段推理；VAD判断句子边界，不把非流式模型声称为真流式。连续录音按时间段切分，预览与final分开。不能用简单“重复字符串删除”合并，避免删掉正常重复话语。
模型只加载一份。先使用2个CPU推理线程作候选参数，实际延迟、内存和发热待测；不沿用生图的GPU预算。空闲/键盘收起提供卸载策略，不能每句话都重新加载。Native推理若不支持即时abort，取消先保证禁止回填，后台有界等待释放，不承诺立刻硬中断。
离线ASR、拼音、标点不发网络请求，模型缺失/损坏明确报错，无Google/云端静默fallback。电脑通信仍需LAN或跨网连接：本地语音离线不等于跨网发送也无需网络。

## 8. 三方意见与Codex裁决
Claude：强调临时/稳定/提交边界、防双重上屏、字段隐私和中文候选。初次建议先单入口/逐段手动发送，被用户确认双入口与既有连续输入契约覆盖；补充意见已真实返回。
Antigravity：首次实际返回成功，认为Activity+IME共享Android核心可行，重点为麦克风生命周期、单份模型、JNI依赖。校正补充返回PARTIAL（有正文但上游状态非完整成功），不声称全部审查通过。
Codex核对后纠正：librime是BSD而非GPL；Trime整个App另算。SenseVoice是离线decode，不照搬OnlineRecognizer。标点可能需独立模型。AIDL或独立目录不能自动免除许可条件。不采用“GPL词库一律不能用”或“移动到下载包就不需遵守许可”的说法；代码/词库/模型各自记录许可。实际传输保留v0.7 HTTPS/Tailcat，不照抄补充意见中新的WebSocket/AIDL电脑协议。
Jev：实际分类成功，仅初始路由；不参与执行或决定权限。专家意见不是本机成功证据。

## 9. 先做三个小验证，再进入完整实现
P1离线ASR：固定一份可信INT8模型、取消隔离、中文/数字/标点；验证无云回退、同实例复用及冷/热启动。
P2中文键盘：26键+9键都真正生成拼音候选，验证许可清单、JNI构建与候选组合，避免只有英语/数字盘。
P3两入口：同APK内部编辑与系统IME共享逻辑，其他App不走Relay，焦点改变/密码/键盘收起安全处理。
Antigravity效果图已生成并获用户确认，候选图见 `design/product-direction-candidate.jpg`。Probe A工程代码已经开始实施。启用系统IME和麦克风授权由用户在Android正式界面完成，不能用Root绕过。用户之前要求自己最终测，未经新同意不自动循环切换手机界面。
三个验证没有解决前不堆更多功能，不更换为其他Linux系统，也不伪造可运行结果。

## 10. 完整交付与验收
开发交付：一个v0.8 APK、模型资源清单/来源/hash、可回退v0.7、Windows兼容性说明、全部功能按钮接线、中文使用说明。维护方式：固定组件版本/接口，模型包独立可更新、升级前后使用同一中文评测集；长期可替换ASR或拼音引擎，不依赖某个外部App永远安装。
最终用户实测至少包括：
1 离线中文语音成功；缺模型明确失败，文字键盘继续可用。
2 26键“nihaoshijie”选出中文；9键有拼音消歧与中文候选。
3 中英文、数字、标点及混合输入。
4 光标中间插入、选区替换、删除、长按删除，语音结果不跳末尾。
5 来电/取消/收起/焦点切换后旧结果不误上屏。
6 外部App正常输入，电脑收到零条；密码内容不入历史。
7 App工作区连续语音→Windows当前光标，无每段额外确认。
8 断线、重连、重复回调不重发；已发送前缀修改清楚暂停。
9 冷启动与热启动分别记录，两个入口没有重复加载模型。
10 App历史搜索/四格式完整导出保持；旧版与数据可恢复。
开发量预估：核心验证2–4个有效开发日；完整中文26键/九宫格+语音+双入口约2–4周，包括迭代验收。这是排期估计，尚非实测耗时；拼音native构建、词库与9键解码是主要不确定项。

## 11. 来源与核验范围
- BiBi官方仓库与Apache2.0：https://github.com/BryceWG/BiBi-Keyboard ，源码核对commit 3a49d74e1edacbe034ece76e27eec58598c1a382。具体文件：ImeKeyboardViewFactory、SenseVoicePseudoStreamDelegate、SherpaPunctuationManager。
- FUTO：https://github.com/futo-org/android-keyboard ，当前核对commit 70a5d390c505a6bbcc4e14966e5628e43ca3f1fc。
- Rime核心与LICENSE：https://github.com/rime/librime ，commit 7bc3fb0005a03aff1c086611e4d52bb7f6444fdd；https://raw.githubusercontent.com/rime/librime/master/LICENSE。
- Trime整App许可：https://github.com/osfans/trime ，https://raw.githubusercontent.com/osfans/trime/develop/LICENSE。
- Android IME官方：https://developer.android.com/develop/ui/views/touch-and-input/creating-input-method。
- SenseVoice/sherpa官方：https://k2-fsa.github.io/sherpa/onnx/sense-voice/index.html。
- 模型元数据：https://huggingface.co/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/tree/main。
本轮核验公开资料/少量源码/远端元数据和既有工程；未克隆候选整仓、未下载模型、未构建v0.8，不宣称模型/键盘已在一加15运行成功。
