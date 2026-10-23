# 豆包编辑器、删除与组字分层静态分析

日期：2026-10-15。范围：既有 `analysis/doubao-ime-20261013/jadx-src/sources/`，与 `voiceinput2pc-v08/android/app/src/main/java/io/github/amxooo/voiceinput2pc/` 当前代码对照。未运行豆包、未 Root、未注入、未读取应用私有数据、未外发、未修改业务代码。结论是反编译样本的静态证据，不是目标手机行为 `VERIFIED`。下面所有行号指这次现存文本。

## 1. 结论

豆包编辑器层最有借鉴价值的是“编辑命令发出前做内部动作预测，selection 回调到达后归因”，以及将组字文本、稳定提交、语音组字/提交分别标记。普通删除在 Java 可见路径中使用 DEL 按键；它没有在 Wrapper 中提供通用 code-point 删除。不能把这个事实扩大成“豆包所有删除都用 DEL”，因为清空、英文自动空格移除、范围替换等有独立 `deleteSurroundingText` 路线，普通软键盘组字处理也跨越 native。

当前 v0.8 已具备拼音优先删除、语音预览优先编辑、远端草稿路由、Termux/raw-editor DEL 分支。主要结构差距不是缺一个删除 API，而是缺少集中管理编辑命令、实际返回和 selection 回调归因的编辑器适配层。豆包样本自身也存在乐观预测、返回值未使用及反编译不可靠等局限，不应照搬。

## 2. 文件前缀

- `D/` = `analysis/doubao-ime-20261013/jadx-src/sources/com/bytedance/android/`
- `V/` = `voiceinput2pc-v08/android/app/src/main/java/io/github/amxooo/voiceinput2pc/`

## 3. 删除事件的可见链路

| 层 | 静态证据 | 可确认行为 |
| --- | --- | --- |
| 图片键组件 | `D/input/keyboard/component/DeleteButtonKeyComponent.java:13–40` | 只设置删除图标、尺寸和风格，本文件不能证明按键执行算法。 |
| 长按/滑动删除窗口 | `D/input/popup/BackspaceSwipeWindow.java:84–103`；`:591` | Handler 调 `KeyboardJni.backspaceClickEvent()`，有前文时每 50ms 继续；点击也进入该入口。 |
| Java 删除入口 | `D/doubaoime/KeyboardJni.java:2843–2890` | 先检查服务和连接；有选区时 `commitText("",1)`；无选区时记录语音修改上下文，最后 `mImeService.sendDownUpKeyEvents(67)`。 |
| 特殊内部编辑框 | `KeyboardJni.java:2869–2872`；`D/doubaoime/ImeService.java:2532–2551,2586–2588` | `x(ic)` 返回内部 commonPhraseIC 标志；该分支只发 ACTION_DOWN DEL。它不是 Terminal 检测。 |
| 发送和跟踪 | `ImeService.java:2524–2529` | 先给 SelectionTracker 一个 ACTION_UP 事件预测，再调用父类发送按下/抬起。 |
| Native 入站/出站边界 | `KeyboardJni.java:7009–7011,7894–7903` | `handleJavaBackspaceClickEvent/ForDoubleClick` 是 native 声明；`DoFunctionKey(1)` Java 入口调用 backspaceClickEvent。仅凭声明不能证明 native 内部对拼音、候选、长按的完整选择条件。 |

另有手写 Java UI 直接调用 native 删除入口：`D/input/keyboard/areacontrol/InputViewRoot.java:742–743`，`D/input/keyboard/handwriting/HandWritingLookupBoardView.java:137,288`。不能将这些局部调用当成所有普通软键的完整链路。

不同用途的删除必须分开：

- `D/input/editor/InputConnectionWrapper.java:444–456`：先预测删除，再委托宿主 `deleteSurroundingText`；`:459–460` 的 `deleteSurroundingTextInCodePoints` **直接返回 false**。
- `D/input/o/e.java:176–195`：英文自动尾空格移除调用 `deleteSurroundingText(1,0)`，并未检查其返回值就记录 success。这是代码中的乐观记录，不是实机成功证据。
- `KeyboardJni.java:1608,1628`：有最大长度双向清空；`:1955–1974`：提交拦截器可先删前文、稳定提交成功后再删后文；`:8032–8036`：功能码 10 另有单字符周边删除。
- 普通 `backspaceClickEvent` 的选区分支也未检查 `commitText` 返回值（`:2863–2866`）；普通 DEL 路线的返回 true 表示入口完成调度，不能证明目标文本已变更。

## 4. SelectionTracker 的职责与边界

`D/input/editor/SelectionTracker.java:45–60` 划分普通提交、提交清空、组字、删除、清空、Enter、选择、语音提交/组字、手写重置、整段替换等动作。`:62–95` 的队列记录 selection start/end、composing start/end 和动作原因。

预测在命令之前发生：Wrapper 的 `commitText`（`:403–411`）、`setComposingText`（`:729–738`）、`setSelection`（`:749–756`）、`deleteSurroundingText`（`:444–451`）均先调用 Tracker，再委托真实连接。`:583–585` 维护的是操作计数，不是宿主编辑成功次数。此策略能解释异步回调，但宿主返回 false 或连接为空时，预测可能先于真实结果，不能称为完整事务机制。

回调链是 `ImeService.java:2308–2318` → Wrapper `r`（`:679–688`）→ Tracker `G`（`SelectionTracker.java:203–300`）→ KeyboardJni。Tracker 用实际 selection/composing 四元组搜索待处理队列，匹配时提取原因并移除；未匹配时采用当前动作。语音提交/预编辑与手写重置有单独的动作清理条件（`:270–285`）。这比“任何光标变化都视为用户移动”精细，但并非 operation-ID 的严格逐项确认，重复四元组、乱序、拒绝编辑仍需要独立测试。

删除按键预测在 `SelectionTracker.java:572–619`，只在 ACTION_UP 处理 Enter 和 DEL；DEL 遇选区折叠到较小端，普通光标减 1。这里的“1”是位置预测，未见 Unicode 字素簇处理，不足以断言 emoji 删除粒度。组字长度与提交位置预测也使用 `CharSequence.length()`（`:116–151,348–386`）。

## 5. 组字、上屏与光标分层

- `KeyboardJni.UpdatePreedit`（`:2727–2790`）读取 Tracker 的 composing 长度，按策略路由；传统分支 batch 内按需设置 composing region，再 `setComposingText(str,1)`。策略分支在 `D/input/y/a`，不能只看此函数就声称全部走传统分支。
- `KeyboardJni.SetPreeditRange`（`:2568–2589`）将零范围与结束组字区分处理。
- 稳定上屏 `DoCommit`（`:1719–1764,1951–1992`）先清引擎显示状态、执行提交拦截器、准备/清理旧组字，再 commitText；可附带前后删改与尾部提交。`clearCompStr()` 与宿主文本删除不是同一动作。
- Wrapper `o/d/g`（`:605–620,421–440,501–509`）分别标记语音组字、语音提交、结束语音组字；最终仍使用 Android InputConnection，而不是直接向其他应用写文件。
- `KeyboardJni.ShiftCursor`（`:2624–2630`）交给 `D/input/cursor/CursorMoveController.java:u`（`:902`）。其清晰可见路线含 DPAD 按下/抬起（`:1095–1097`）、水平 DPAD（`:1250–1273`）、延迟 120ms 的观测/修正任务（`:1262–1269`）。部分反编译条件明显矛盾，不能把整个 u 方法重建为可靠源码。
- `KeyboardJni.onUpdateSelection`（`:7139–7159`）读取 Tracker 动作；发送给引擎的 `onSelectionUpdated(SelectionUpdatedParams)` 是 native（`:7135`）。native 关联、词边界及候选处理本次未展开，仍未知。

## 6. Terminal 适配的证据范围

在 `D/` 的 Java 源中搜索 `com.termux|terminal|type_null`（大小写不敏感），没有找到明确的 Termux 包名或 TYPE_NULL 编辑器适配；唯一 TerminalState 命中是 clipboard 状态名称，不能当成终端适配证据。普通无选区 DEL 路线可能对终端有用，但“豆包专门为 Termux 实现某算法”没有被证实。native、资源或其他命名仍可能存在适配，本报告不作否定推断。

v0.8 的明确适配见 `V/EditorCompatibility.java:3–10`：inputType class 为 0 或包名 com.termux 时识别 raw editor，并禁用行内组字；T9 也禁用行内组字。`V/UniversalInputMethodService.java:378–381` 对 raw editor 先发 DEL，避免只根据周边删除返回 true 判断 PTY 已变化。这是本项目自己的显式策略，不能说由豆包同名策略验证。

## 7. 与 v0.8 对照及建议

| 关注点 | v0.8 当前代码 | 从样本得到的具体启示 |
| --- | --- | --- |
| 删除优先级 | `UniversalInputMethodService.java:363–393`：预览→拼音→远端→raw DEL→选区→code points→UTF-16→DEL | 已有良好分层，保留；补充目标能力/命令结果记录，不为追随豆包删除 code-point 路线。 |
| 组字与稳定文本 | `:311–343,562–571` | syncComposition 和 routeStable 已分开，但多处直接拿连接且忽略结果；可集中到 EditorAdapter 管理命令类型、连接身份、结果。 |
| 外部光标变化 | `:868–875` | 当前保存 selection 后，凡 activeComposing 且不在 candidatesEnd 就清组字；缺少内部动作队列，宿主回调延迟/乱序需专门验收。豆包的原因归因值得借鉴。 |
| 会话失效 | `InputSession.java:7–39,60–66,114–119` | generation/target/field 能隔离晚到语音回调；这是任务状态身份，不能替代编辑器 selection/composing 回调归因。建议保留两个概念。 |
| 确认提交 | `InputSession.java:98–104`；服务 `:569–571` | session.commit 先清预览并标 COMMITTED，而 routeStable 不检查本地 commitText 结果。应区分“已取出待提交文本”和“目标接受/已观察提交”，失败应保留可恢复草稿。 |
| 预览 Unicode | 服务 `:430–435,418–420` | 删除使用 offsetByCodePoints，而移动光标 current+direction 仍按 UTF-16，可能落在代理项中间；这应独立于豆包用例检查。 |

后续实施优先级：先增加薄 EditorAdapter（编辑器连接身份、组字状态、内部操作原因、有限回调队列、返回结果），再以真实普通 EditText、WebView、Termux、emoji、反向选区、宿主拒绝/无 selection 回调验证。不要直接复制豆包 Tracker 的全部复杂度；不要根据 boolean true 推断 PTY 或宿主文本已改变。当前仅提出改进，未执行实现、编译或真机验收。
