# Probe A｜Android IME 与本地录音生命周期

日期：2026-10-13。当前状态：`PENDING`。代码与构建通过；真机已验证输入法启用、ASCII 文本提交及本地录音启停，敏感输入框与系统中断场景仍待验证。

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

- 焦点切换时旧回调不得写入新输入框。
- 密码字段必须禁用语音。
- 键盘收起时的录音自动释放（主动点击停止已通过）。
- 来电/锁屏/应用切换中断。
- 中文候选、SenseVoice final、Local 零网络。

以上项目未完成前，Probe A 不标 `VERIFIED`。


## 2026-10-13 真机证据

- 设备：OnePlus 15（PLK110），Android 16。
- 安装包：`io.github.amxooo.voiceinput2pc.v08probe`，版本 `0.8.0-probe-a`。
- Android InputMethodManager 显示该服务为 `mSelectedMethodId`、`mCurId`，且 `mInputShown=true`。
- 用户通过该键盘向当前会话提交了 `eubm`，证明基础按键到 `InputConnection` 的真实链路可用；该结果也证明当前仍只有 ASCII composing，没有中文解码。
- `RECORD_AUDIO` AppOp 为 `allow`；真机日志显示 `AudioRecord` 以 16000 Hz 启动成功，约 4 秒后调用 `stop()` 并完成释放，无崩溃。
- 本轮未验证 SenseVoice 或任何语音转文字；Probe A 不保存录音。
