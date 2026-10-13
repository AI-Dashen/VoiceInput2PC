# Probe A｜Android IME 与本地录音生命周期

日期：2026-10-13。当前状态：`CODE_COMPLETE` / `BUILD_PASS` / `PENDING_DEVICE_VERIFICATION`。

## 已实现

- 注册真实 `InputMethodService` 与中文 subtype。
- 自有 26 键 Probe 界面、composing、删除、空格、显式回车。
- `InputSession` 用 generation 隔离输入框切换后的旧录音/旧识别回调。
- 密码字段禁用语音；文字键盘仍可工作。
- 麦克风只在输入法可见且用户点击后创建 `AudioRecord`。
- 键盘收起、焦点结束、取消和 Service 销毁均停止并释放录音。
- Probe A 不保存 PCM，不做识别，不声称 ASR 已接入。
- 独立设置页由用户主动授权麦克风、打开系统输入法设置和选择输入法。
- v0.7 Windows Relay 代码保留。

## 本机代码验证

- `InputSessionTest`：15 项状态/过期回调/重复提交检查通过。
- 相关便携回归：38 tests PASS。
- `gradle :app:assembleDebug`：PASS。
- 全量 Python discovery：70 项中 58 项通过，12 项因 Termux 缺 Windows `powershell.exe` / `WinDLL`、Tkinter 与 Python cryptography 依赖而未运行；不是本次 Android 代码断言失败。

## 尚未验证

- Android 设置中启用该 Probe 输入法。
- 真机输入框键入、焦点切换与密码字段行为。
- 麦克风授权后录音开始、取消、收起释放。
- 来电/锁屏/应用切换中断。
- 中文候选、SenseVoice final、Local 零网络。

以上项目未完成前，Probe A 不标 `VERIFIED`。
