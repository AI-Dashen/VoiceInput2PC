# 自有语音草稿编辑最小重构计划

2026-10-15，仅静态审阅，未修改业务代码。范围为当前 v08 输入法草稿→切布局→键盘编辑→显式上屏；保留本地语音引擎、Rime/键盘引擎及跨端发送协议。验收目标采用用户已认可的显式上屏契约。以下行号基于本轮读取的代码；后续改动后需随定位调整。

源码目录：`android/app/src/main/java/io/github/amxooo/voiceinput2pc/`。除明确写 InputSession/OutputTargetController 外，行号均属于 UniversalInputMethodService.java。

## 1. 已确认的问题

| 位置 | 当前行为 | 用户可见后果 |
| --- | --- | --- |
| :248-287 switchMode/commitPreviewBeforeModeSwitch | 切模式时 PREVIEW 被 commit 后 routeStable，随后新建会话 | 切26键/T9/符号就自动上屏或进入远端待发区 |
| :912-964 render | voiceSurface=voiceMode或busy或isPreview；语音大区隐藏键盘、候选和utilities | 单删自动commit后，PREVIEW仍让键盘无法出现 |
| :430-436 editPreview；:913-915 render | 编辑只改 EditText；suspendedPreview仍旧，render用它覆盖EditText | 候选/删除/字符编辑后下一次render可能回滚 |
| :345-350 commitLiteral | 不区分PREVIEW，直接routeStable | 中文键盘的直出标点等可能绕过草稿直达本机/远端；不能只修英文type分支 |
| :413-420 moveCursor | 远端目标分支优先于PREVIEW | 编辑远端语音草稿时左右键动的是已确认远端待发区 |
| :491-500 confirmVoice；:562-571 routeStable | 先session.commit清空，路由void且忽略commitText返回值，再清草稿并报成功 | 无InputConnection或commitText失败会丢草稿并假报成功 |
| :543-552 onError | 只校验generation；恢复PREVIEW失败后session.cancel | 同generation的迟到error若落在可编辑PREVIEW可取消用户草稿 |
| :555-559 visibleVoiceDraft | EditText空就回退旧suspended/prefix | 用户清空草稿后可能被旧副本复活 |
| :154-177 beginEditor | 同字段restarting也无条件reset/begin | 暂存草稿可能因同字段restart丢失；此项按生命周期阶段处理 |

## 2. 最小状态原则

继续以 InputSession.PREVIEW 表示“待用户确认的草稿”，不把切键盘当作提交。voiceMode只负责展示语音区或文字布局，不再由PREVIEW强制覆盖布局。普通无语音草稿的26键/T9输入仍走现有直接输入路径，不将所有键入改为批量上屏。

草稿必须有一个权威值，允许空字符串是有效编辑结果。建议以 InputSession.preview 为权威；EditText为视图，suspendedPreview只作运行识别中的可见partial缓存，不能反向覆盖稳定PREVIEW。增加集中 replaceDraft/applyDraftEdit 辅助方法，同步权威值、视图、选择区及需要的缓存。给EditText加受控TextWatcher处理用户原生选区删除/粘贴，程序化setText需防重入。若最小实现仍保留多个副本，每次变更必须原子同步，render禁止再从旧副本恢复。

语音引擎回调仍用generation，不改模型、采样、静音结束或IPC。增加纯状态方法“结束当前识别并保留草稿”：递增generation但保留字段identity和文字，进入PREVIEW；没有文字则进入IDLE。调用speech.cancel并清理segment prefix。这样旧partial/final/error全部过期；额外让onError/onResult只处理合法运行状态，避免迟到回调改变稳定草稿。不要为保留草稿直接调用begin后丢文字，也不要仅cancel引擎而复用旧generation。

## 3. 按依赖顺序实施

1. **草稿一致性与回调隔离**：增加InputSession纯方法支持暂停保留并递增generation；统一draft读取/编辑；稳定PREVIEW不以“空→回退旧副本”读取；回调有generation及合法状态门禁。保留现有resumeListening递增generation机制。
2. **提交接受结果**：把routeStable改成有返回值的接受结果，至少成功/拒绝。当前本机连接为null或commitText=false都拒绝；捕获异常保留草稿并显示重试提示。远端commitStable=true仅表示加入待发送缓冲，不表示电脑收到。confirmVoice先读取、校验、尝试目的地接受，接受成功后才session.commit/reset/清视图；失败不改draft、selection、PREVIEW。不得自动重试或换目标。普通键入可继续调用同一返回值方法。
3. **模式切换与布局**：删除commitPreviewBeforeModeSwitch调用；忙碌时走第1步的暂停保留；稳定草稿保留；voiceMode=false再切keyboard.mode。不清草稿或其选区。render用voiceMode/busy控制大语音面板，文字布局下PREVIEW显示紧凑可编辑草稿区，同时显示组字/候选/键盘。右侧动作仍为上屏。草稿区应有固定可用高度或最大行数，不能与keys同时weight=1导致布局压缩失控。
4. **所有编辑出口统一**：英文/数字/符号直出、中文选候选、空格均优先写草稿；commitLiteral在PREVIEW时改为applyDraftEdit；syncComposition在PREVIEW仅更新本地组字/候选视图，不给宿主InputConnection或远端缓冲setComposing。候选确认只插草稿，未完成拼音不串入宿主。SHIFT只改大小写。
5. **删除、光标、确认优先级**：按下一节固定作用域；光标PREVIEW优先于remote，按Unicode code point移动，不拆代理对。上屏遇未完成拼音时维持现有明确阻止提示，不暗选候选。模式切换遇未完成拼音必须有确定策略；最小安全实现可阻止并提示先选词/删除，避免clearComposition静默丢组字；如产品要求无阻切换，则另保存composition snapshot，不能顺便扩大本次重构。
6. **生命周期/功能页**：同字段有效restart与临时隐藏恢复权威草稿、布局和选区，真实finish/换字段/密码框清理并使回调过期。功能页返回不重新begin；目标切换在PREVIEW仍按现有规则拒绝。已有远端持久缓冲与重试ID逻辑保持原义。每次render只呈现状态，不触发上屏/发送。

上述顺序中1、2是基础；3依赖1；4、5依赖1和3；6依赖稳定的草稿/布局模型。可以同一变更落地，但不要在1尚未完成时只删除自动提交调用。

## 4. 编辑动作的确定作用域

| 状态/动作 | 操作目标 | 禁止的隐式行为 |
| --- | --- | --- |
| LISTENING/RECOGNIZING期间删除/移动 | 保持现有忙碌保护，先明确停止再编辑 | 不删除宿主或远端待发送文字 |
| PREVIEW且拼音非空，删除 | 当前拼音rawInput，刷新候选 | 不删稳定草稿、不删宿主 |
| PREVIEW且无拼音，删除 | 草稿选区；无选区删光标前一个code point | 草稿为空也不向宿主/远端缓冲穿透 |
| PREVIEW移动光标 | 草稿选区/光标；拼音未决先提示确认 | 不因remote而改远端pending cursor |
| PREVIEW字符/符号/数字/空格/候选 | 草稿当前位置或选区替换 | 不routeStable、不setComposing宿主 |
| 普通文本模式且拼音非空 | 保持现有拼音编辑 | 不扩大到批量草稿 |
| 普通模式无拼音，删除/移动 | 已选本机编辑器或远端待发区 | 不复活旧语音草稿 |
| PREVIEW上屏 | 目的地接受后才清草稿 | 不把本机返回false、无连接、远端拒绝说成功 |
| 远端PREVIEW上屏 | 加入远端待发区；显示已加入待发送 | 不自动执行网络发送 |
| 远端发送 | 保留现有独立发送、回执、同ID重试 | 不把排队/接收保存混为已插入电脑窗口 |

## 5. 完整验收流程集

以下是本次改动的完整场景集，不是全部输入法产品功能的穷举；先纯逻辑测试，后布局/Android测试，真机操作仍由主调度核对授权范围。

| 编号 | 前置与动作 | 结果/证明点 |
| --- | --- | --- |
| F01 | 新字段→开始语音→partial→final | PREVIEW可编辑，无宿主提交；语音引擎流程不变 |
| F02 | LISTENING有partial→切26键/T9/符号/数字 | 停录并保留可见草稿、显示目标键盘；不提交 |
| F03 | RECOGNIZING有partial→切布局→旧final/error/partial到达 | 草稿、布局、用户修改均不被旧回调覆盖 |
| F04 | 忙碌无文字→切键盘 | 停录，目标键盘可直接普通输入，无幽灵草稿 |
| F05 | PREVIEW→26键/T9→拼音→选候选/空格选词 | 候选可见，文字只进草稿，无宿主composing |
| F06 | PREVIEW→英文/SHIFT/数字/符号/空格 | 插入/替换草稿选区；render后不回滚 |
| F07 | 草稿有选区→删除；光标前为emoji→删除/左右 | 正确选区与code point行为，不拆代理对 |
| F08 | 草稿删空→切模式/render/继续语音 | 空值保留，无旧suspended/prefix复活，无向外删除穿透 |
| F09 | PREVIEW拼音未决→删除/上屏/切模式 | 删除先删拼音；上屏明确阻止；模式策略可预期 |
| F10 | PREVIEW修改→继续语音→final/空结果/错误 | 新段追加已修改草稿；失败保留原稿；旧段回调无效 |
| F11 | 本机上屏成功→重复点击 | 恰好一次提交，成功后清草稿；不重复写入 |
| F12 | 本机无连接/commitText=false/异常→上屏→恢复连接重试 | 草稿与选区保留，无假成功；显式重试后一次提交 |
| F13 | 远端目标有pending→语音草稿→左右/删除/候选 | 只编辑语音草稿，不改原pending；上屏后合入一次 |
| F14 | 远端上屏拒绝 | 保留语音草稿；不网络发送、不清空 |
| F15 | 远端上屏成功→发送→拒绝/未知/成功回执 | 上屏与发送分开；复用既有请求ID/保护前缀语义 |
| F16 | 同字段临时隐藏/同字段restart/功能页返回 | 草稿、布局、选区按契约恢复，不产生提交 |
| F17 | 换字段/应用/密码框/真实finish/destroy | 清理并使旧回调无效，不串写、不保留敏感草稿 |
| F18 | 无语音草稿→普通26键/T9/数字/终端/远端输入 | 原有直输、组字、终端删除及远端缓冲不回归 |

实现后的测试应覆盖这些可观察行为，避免只检查方法名存在。InputSession纯方法测试与Android可控InputConnection假对象可覆盖核心失败/重复提交；实际本地识别与设备插入成功必须另记真实证据，不能用构建成功代替。
