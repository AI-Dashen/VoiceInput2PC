# 模式切换与会话边界静态审阅

日期：2026-10-15。仅审阅既有 JADX Java 输出与 v08 源码；未启动应用、动态注入或操作系统。静态证据不代表手机运行 VERIFIED，也不代表完整还原。

豆包路径基于 `analysis/doubao-ime-20261013/jadx-src/sources/`；v08 路径基于 `voiceinput2pc-v08/android/app/src/main/java/io/github/amxooo/voiceinput2pc/`。JADX 有混淆、错误类型重建和不完整方法，局部变量类型不能直接当作原始设计。

## 已证实入口与边界

- `com/bytedance/android/input/constants/KeyboardInputMode.java:6-11`：PY26=0、PY9=1、ENGLISH26=2、DOUBLE_SPELL=3、HANDWRITING=4；没有语音、符号。输入方案、临时面板和语音状态分开管理。
- `com/bytedance/android/input/keyboard/areacontrol/InputViewRoot.java:2251-2262`：工具箱 VOICE_INPUT 关闭工具箱、处理完整输入模式限制后调用 KeyboardJni.DoFunctionKey(6,"tool")。枚举映射见 `com/bytedance/common_biz/tool_box/ToolboxItemAction.java:6`。function key 后续完整 native 行为未知。
- 同一 InputViewRoot.java:2280-2316：工具箱九宫格、26键、手写、双拼映射 KeyboardInputMode 后调用 setChineseBoard，关闭工具箱并更新选中状态。native 是否提交、保留、转换当前 composing 不可从此确定。
- 同一 InputViewRoot.java:585-668：J 回调捕获 **HandWritingBoardView**，因此是**手写面板**中英/123/符号入口，不能泛化所有主拼音键盘切换。AsrManager.V() 为真时只 AsrStopCommand(false,reason)；否则 true 停止后再切面板。中英分支还显式 stopInputAndCommitPinyin、finishPreedit(true)、setInputMode(ENGLISH26)；数字/符号分别 switchKeyboard(5)/(3)。不能声称所有切换都只保留草稿。
- `com/bytedance/android/input/speech/AsrManager.java:3516-3520`：V() 仅 KTryStart/KStart。`com/bytedance/android/doubaoime/KeyboardJni.java:1554-1565`：true 会 l0(0) 后 T0(true,reason)，false 只 T0(false,reason)。AsrManager.java:3402-3442 把参数称为 noWaitResult，并继续按状态/文字分支，不能简单翻译为保存/丢弃。
- InputViewRoot.java:2765-2773 手写查字返回明确记录“stop asr and commit voice preedit”并 AsrStopCommand(true)；只证明局部提交意图。

## 两种选择器

工具箱 KEYBOARD_SELECT（ToolboxItemAction.java:8）在 InputViewRoot.java:2268-2272 调 ToolboxKeyboardView.v，是内部布局选择。

系统选择器直接入口为 `com/bytedance/android/doubaoime/activity/ImeGuideActivity.java:159-174`：step two showInputMethodPicker；step one INPUT_METHOD_SETTINGS。:118-141 根据启用/选中状态调整步骤。全量 Java 仅找到这一处直接 showInputMethodPicker，不证明 native、反射、间接入口不存在。

v08 `HomeActivity.java:119-125` 已有启用/选择按钮；:170-193 区分未启用、已启用、已选中，已选中后显示“切换其他输入法”。不能将这个系统选择入口判为缺失，或与内部26键/T9选择混淆。

## 生命周期

- `com/bytedance/android/doubaoime/ImeService.java:1891-1903`：onStartInput 记录包名/restarting，restarting 有额外 o(editorInfo)；:2169 把启动视图交给 KeyboardJni。
- 同一 ImeService.java:1709-1714：onFinishInput 调 AsrManager.m0、KeyboardJni.onFinishInput。AsrManager.java:3608-3615 按设置决定是否 T0(true,"onFinishInput")。
- ImeService.java:1735-1761：onFinishInputView 调 KeyboardJni.onFinishInputView、AsrManager.n0；AsrManager.java:3618-3645 停 ASR、清回调/提示窗并重置部分状态。
- KeyboardJni.java:5675-5693：finishInput 清 restart/preedit 起点；finishInputView 调 native finishInputView，清 composition、候选缓存，记录模式策略退出时间。
- `com/bytedance/android/input/keyboard/input_mode_strategy/KeyboardInputModeStrategy.java:26-50`：实验开启、非密码、当前英文且不同于上次中文时，切应用或超时可恢复中文。调用点 KeyboardJni.java:7804、InputViewRoot.java:6899,6926。这是条件策略，不是每次无条件恢复。

这些体现编辑器、视图、ASR和偏好的分层；不足以确认完整 session ID、防串写、旋转恢复、native composing 所有权。

## v08 确定问题与有限整改建议

`UniversalInputMethodService.java:248-287`：switchMode 忙碌时取消识别并尝试恢复可见文字，遇 PREVIEW 随即 commitPreviewBeforeModeSwitch。后者 session.commit 后 routeStable(text)，再 begin 同一目标新会话。切模式因此可能实际提交或进入跨端路由；这是本项目源码确定行为，与豆包 native 未知部分分开。用户显式上屏契约优先，参考应用局部提交行为不能推翻它。

建议消除模式切换自动提交，并将草稿状态与展示布局独立保存。只删 commit 不够：render():912-925 使用 voiceMode || busy() || isPreview 强制显示语音大区，PREVIEW 仍会占键盘空间；须同时明确键入编辑哪份草稿、拼音 composing 怎样进入草稿、符号数字插入点。

现有生命周期可复用：同文件 :154-184 按字段 identity 初始化；:187-203 在临时隐藏且 PREVIEW 时保留草稿，finishInput 则失效；:205-208 关闭资源。但 onStartInput 即使 restarting=true 仍 beginEditor（:154-177），不能依据注释声称所有同字段 restart 都保留草稿。

后续验收应覆盖识别中切模式不提交、延迟 final 不覆盖用户编辑、同字段临时隐藏恢复、换字段不串写、草稿恢复后显示所选布局。本轮未改业务代码、未做真机验证。

## native 未知边界

KeyboardJni.java:7289 setChineseBoard、:7307 setInputMode、:7830 startInputView(StartInputViewParams)、:7834 switchKeyboard 均 native。内部切换顺序、提交/保留、缓存和回滚不可见；主拼音键盘全部触摸路径、语音返回26键/T9的完整行为、所有系统选择器入口未完整确认。完整行为复现与真机验收为 PENDING。
