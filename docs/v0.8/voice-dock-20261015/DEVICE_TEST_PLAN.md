# Voice Dock 实机验证计划

状态：PENDING。这里列的是验收步骤，不代表已经执行。

## 构建入口

在 voiceinput2pc-v08 根目录执行：

```sh
gradle -p android :app:assembleDebug :app:assembleDebugAndroidTest --offline --no-daemon
python -m unittest discover -s tests -p 'test_v08_*.py'
```

默认 debug applicationId 为 `io.github.amxooo.voiceinput2pc.v08probe`，由 `qaPackageSuffix` 属性可覆盖；运行前必须核对实际产物包名，不能混用旧版本测试包。
测试 runner：`android.test.InstrumentationTestRunner`。
App APK：`android/app/build/outputs/apk/debug/app-debug.apk`。
测试 APK：`android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`。

项目脚本中未找到通用受控 instrumentation 启动封装。主调度应复用已有授权设备执行入口，不能在本计划中扩大为任意 ADB/系统操作。APK 安装遵守全局 `bin/install-apk`。`am instrument` 属设备执行环节，构建本身不会执行它。

## 分层证据

1. JVM/Python：验证状态和纯控制器。
2. Android Instrumentation fixture：真实 EditText/InputConnection，但 SpeechInput 为测试替身，手动构造 IME Service；只能证明控件/事务逻辑，不能称为真实语音或系统输入焦点验收。
3. 真实系统绑定 IME：用户认可的测试输入框中验证悬浮触点、窗口高度、切换键盘、光标和应用切换。
4. 真实离线 ASR：`LocalInputDeviceTest` 提供预先存在的公开音频输入，可验证独立识别进程，但不能替代真人麦克风与悬浮交互验证。
5. 跨设备：必须有真实接收端回执；无接收端仅能验证远端路由不泄漏到本机，不能标记电脑输入已成功。

## 必测矩阵

| 编号 | 操作 | 期望 |
|---|---|---|
| D01 | 普通输入框获得焦点 | 默认无大文本预览，不自动启动麦克风；触点权限不足时明确可操作的兼容入口 |
| D02 | 点击麦克风，识别完成 | 最终文字直接进入宿主一次，不需要上屏；发送动作未被调用 |
| D03 | 同字段继续说第二段 | 按当前光标追加，第一段不重复 |
| D04 | 识别中切换到另一App或字段 | 旧partial/final/error全部失效，新输入框没有旧文字 |
| D05 | 识别中用户移动宿主光标 | 旧识别停止/失效，不把旧结果插到新位置 |
| D06 | 点击键盘 | 底部出现26键和数字行；没有九宫格入口；侧边触点隐藏 |
| D07 | 26键中文拼音与英文/符号 | 候选可选；数字直接输入；符号、删除、宿主动作可用 |
| D08 | 键盘点击语音/返回 | 收起键盘后触点可用；单纯收起不得自动录音 |
| D09 | 选择其他输入法 | 任意未完成拼音状态仍可打开系统选择器，不死锁；返回后不串字 |
| D10 | 目标选电脑并识别 | 宿主输入框不写入；远端待发草稿可见且发送目标明确；连接断开不得回落写手机 |
| D11 | 功能菜单 | 记录、统计、设置入口可达；目标切换与功能管理分工清楚 |
| D12 | 密码/私密字段 | 麦克风和历史记录禁用；键盘可用，不输出敏感文本到日志 |
| D13 | 空白录音/超时/识别错误 | 恢复可操作；没有卡住控件；已提交文字不丢失 |
| D14 | 旋转/重建输入View | 不重复提交、不意外录音；有效文字留在宿主 |

## 旧测试迁移

`ImeEditingRegressionTest` 中本机 preview 编辑、上屏确认、主UI九宫格相关断言属于旧产品契约，不能直接作为新版验收。保留其有价值的越界/历史/回执等测试，明确迁移到新语义，不能仅通过删断言得到PASS。新Dock测试应独立覆盖宿主文本写入与模式转换。

## 本轮本地验证结果

- `python -m unittest discover -s tests -p 'test_v08_*.py'`：6/6 PASS（2026-10-15）。其中 Java 子进程执行 InputSession、InputCommitCoordinator、T9Disambiguation（遗留引擎回归）、KeyboardController、ImePanelController、OutputTargetController、ImeLayoutSpec、SpeechEndpointDetector、VoiceDraft 的真实断言。
- `python -m unittest discover -s tests -p 'test_history_browser.py'`：6/6 PASS。
- 新 `VoiceDockIntegrationTest`：10个Android集成用例已编写，编译和设备执行交主调度；本条未声称运行成功。
- 原 `test_v08_keyboard.py` 的旧“本机草稿必须保留/上屏”字符串断言已迁移：保留超时、删除与系统输入法选择器边界，补充不得新增SYSTEM_ALERT_WINDOW/TYPE_APPLICATION_OVERLAY边界；实际文字行为由新Android集成测试覆盖。

### 2026-10-16 增量

- 已把 `VoiceDockPolicyTest` 的15个行为断言加入Python入口。`python -m unittest discover -s tests -p 'test_v08_*.py'`：7/7 PASS，耗时5.431秒。
- `VoiceDockBoundDeviceTest` 为真实系统绑定IME验收入口：只读核对默认IME属于本包，不满足则明确失败为PENDING，绝不更改系统默认输入法。自家MainActivity内临时设置无私人信息的测试输入框，实际showSoftInput，通过UiAutomation查找真实TYPE_INPUT_METHOD窗口，点击键盘并记录触点/宿主窗口边界以及PNG。finally关闭测试Activity/隐藏IME并恢复自动化flags。
- 系统窗口测试输出到 `context.getExternalFilesDir(null)/evidence/voice-dock-bound/`。主调度需从合法应用导出通道同步共享目录。此入口不录音、不发送电脑文字、不修改真实配对、不使用Root或shell。
- 系统窗口测试只证明布局/窗口与真实系统绑定，不能代替真人收音、长时间后台稳定性或跨设备接收验收。
