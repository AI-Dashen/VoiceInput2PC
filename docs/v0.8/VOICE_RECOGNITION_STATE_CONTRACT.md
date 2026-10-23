# Voice Recognition State Contract

更新：2026-10-15（v0.8.4）

## 不变量

1. `LISTENING` 与 `RECOGNIZING` 都必须存在用户可操作的退出路径，不能形成不可解除的 UI 锁。
2. 已显示在预览区的非空文字属于可恢复草稿；最终回调缺失、超时、Binder 中断或用户切换模式时不得丢失。
3. 英文、九宫格、26 键和符号模式切换属于显式中断：安全结束当前识别，保留可见草稿并切换键盘；不得把模式切换当成上屏。
4. 左侧“语音”在空闲态直接开始本段，在 `PREVIEW` 显示“续说”并保留草稿；右侧主动作在 `LISTENING` 时停止录音，在 `RECOGNIZING` 时禁用，在 `PREVIEW` 时上屏。
5. 旧 generation 的回调不能修改新输入框、新 App 或新一轮录音。
6. 新输入框开始时取消旧识别、推进 generation、清空旧草稿。
7. 识别启动 15 秒无录音状态则失败恢复；录音结束后 15 秒无最终结果则失败恢复。
8. 错误恢复后必须允许立即继续说、编辑、删除或切换模式。
9. “上屏”只提交文字；提交成功后右侧必须恢复当前宿主的 `EditorInfo` 动作，不能丢失“发送/搜索/完成/下一步/前往/回车”。
10. 首次5秒未检测到人声时，静音PCM不得送入识别核心；释放本轮并恢复可重试状态。

## 关键转换

- `LISTENING -> RECOGNIZING -> PREVIEW -> COMMITTED`
- `LISTENING/RECOGNIZING + mode switch -> PREVIEW(if visible draft) -> new keyboard mode`
- `VOICE_READY + left voice -> LISTENING`
- `PREVIEW + left continue -> LISTENING(with retained draft)`
- `LISTENING + right action -> RECOGNIZING`
- `RECOGNIZING + final result -> PREVIEW`
- `PREVIEW + right action -> host field committed -> host editor action restored`
- `LISTENING/RECOGNIZING + timeout/error -> PREVIEW(if visible draft) | CANCELLED`
- `any active state + editor change -> old generation invalid -> IDLE(new editor)`

## 当前证据

- 自动测试：`tests.test_v08_keyboard` 与 `tests.test_v08_input_session`。
- 真实符号删除：已在 Android 输入框验证。
- 偶发引擎最终回调缺失：防卡与草稿恢复代码已构建安装；仍需真实说话覆盖该随机场景。

## 2026-10-15 v0.8.3 证据（历史）

- Android真机 Instrumentation：79/79 PASS，其中IME流程22项。
- v0.8.3 原“右侧开始/左侧只选模式”已被后续真人体验否定，不再代表当前交互。
- 500字新语音段边界、18/16/14sp字号和最多5行已验证。
- APK内真实Rime九宫格已验证24位上限、第25位拒绝、删除恢复及60轮快速输入/删除。

## 2026-10-15 流程图纠正

逐步操作以 `design-v5/` 的 1 张总览和 5 张分图为待确认基线。旧 `design-v4` 图仅为已拒绝的历史概览，不再指导实现。


## 2026-10-15 v0.8.4 证据

- Android真机 Instrumentation：79/79 PASS，其中真实Android控件用例覆盖“语音→停止→识别中→续说→上屏→宿主发送”。
- 第一段与第二段结果真实合并为同一草稿；400毫秒防穿透窗口已验证。
- 上屏后右侧真实恢复 `IME_ACTION_SEND` 并调用 `performEditorAction`；没有把上屏伪装成发送。
- 5秒无语音端点纯Java测试通过；人声检测前PCM门控已编译安装，真人静音场景仍需使用者最终体验验收。
