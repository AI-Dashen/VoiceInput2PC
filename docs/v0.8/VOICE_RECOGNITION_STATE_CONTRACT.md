# Voice Recognition State Contract

更新：2026-10-13

## 不变量

1. `LISTENING` 与 `RECOGNIZING` 都必须存在用户可操作的退出路径，不能形成不可解除的 UI 锁。
2. 已显示在预览区的非空文字属于可恢复草稿；最终回调缺失、超时、Binder 中断或用户切换模式时不得丢失。
3. 英文、九宫格、26 键和符号模式切换属于显式中断：取消当前识别，提交可见草稿，再切换键盘。
4. `RECOGNIZING` 时再次点击语音属于显式停止：有草稿则转 `PREVIEW`，无草稿则转 `CANCELLED`。
5. 旧 generation 的回调不能修改新输入框、新 App 或新一轮录音。
6. 新输入框开始时取消旧识别、推进 generation、清空旧草稿。
7. 识别启动 15 秒无录音状态则失败恢复；录音结束后 15 秒无最终结果则失败恢复。
8. 错误恢复后必须允许立即继续说、编辑、删除或切换模式。

## 关键转换

- `LISTENING -> RECOGNIZING -> PREVIEW -> COMMITTED`
- `LISTENING/RECOGNIZING + mode switch -> PREVIEW(if visible draft) -> COMMITTED -> new keyboard mode`
- `RECOGNIZING + voice tap -> PREVIEW(if visible draft) | CANCELLED`
- `LISTENING/RECOGNIZING + timeout/error -> PREVIEW(if visible draft) | CANCELLED`
- `any active state + editor change -> old generation invalid -> IDLE(new editor)`

## 当前证据

- 自动测试：`tests.test_v08_keyboard` 与 `tests.test_v08_input_session`。
- 真实符号删除：已在 Android 输入框验证。
- 偶发引擎最终回调缺失：防卡与草稿恢复代码已构建安装；仍需真实说话覆盖该随机场景。
