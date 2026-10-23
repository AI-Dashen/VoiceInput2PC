# 编辑上下文自动检测：需求纠正（待交互确认）

2026-10-16用户反馈：删除/回车应自动检测；微信caret初次不显示，执行删除后可见；ChatGPT复制不工作。复制对象（输入框/聊天正文）已向用户澄清，尚未收到答案。不改生产实现，先统一实际语义。

## 当前真实能力
0.8.10 EditorReadinessPolicy只判断绑定、EditorInfo、Session身份、InputConnection是否存在；不是文字/选区/可删除状态检测。CursorAnchorInfo有报告则记宿主visible，否则UNKNOWN。IMS没有ClipboardManager/performContextMenuAction(copy)复制入口。缺此入口不能证明ChatGPT自身复制失败由输入法导致。

## 应区分的自动判断
- 输入连接是否就绪。
- 有无选区、光标前是否有文字，决定能否后退删除；未知不等于空。
- 宿主EditorInfo动作，决定下方显示发送/搜索/完成/换行，不与删除按相同条件一起开关。
- 当前文字输入/组字状态及录音事务；语音busy仍保留防误提交边界。
- 不以有没有录过音或光标是否正处于闪烁可见相位来决定编辑入口。

## 证据与边界
若做文本状态Probe，只读当前授权输入框最小光标邻域，不保存正文；密码/敏感框沿用关闭策略；Selection起止与内容分开，末端UNKNOWN不能算无内容。InputConnection请求有IPC开销及可能失败，不能每帧同步查询或让AI截图轮询。

微信删除后显示caret是现象，不足以区分焦点、selection刷新、blink绘制或遮挡。不能以先删一字来唤醒或在render循环盲setSelection。自身页面焦点PASS不证明微信PASS。

输入框复制可通过宿主InputConnection.performContextMenuAction(copy)请求，仍需宿主支持；聊天正文/AI回答不是当前输入框的内容，需其自身选择/复制入口，不能把两者混做同一个IME修复。

官方核查（2026-10-16）：https://developer.android.com/reference/android/view/inputmethod/InputConnection ，getSelectedText/getTextBeforeCursor/performContextMenuAction；返回null可能不支持/超时/失效，不等于空输入。
