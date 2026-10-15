# v0.8 一级输出目标与跨设备草稿

日期：2026-10-13

## 状态

`CODE_COMPLETE / BUILD_VERIFIED / DEVICE_PENDING`

## 产品规则

- 语音输入是第一核心，跨设备文字传输是第二核心。
- 输入方式与输出目标正交：左侧选择怎么输入，一级目标键选择文字到本机还是受信任设备。
- 远端模式拥有独立草稿，不写入宿主 `InputConnection`。
- 只有候选确认、普通键稳定文字或语音确认结果进入草稿；拼音组字、ASR partial、预览不会发送。
- 发送等待匹配回执；失败恢复草稿，未知结果重试复用同一 request id。
- 密码字段强制本机并禁用目标切换。

## 实现

- `OutputTargetController`：纯 Java 目标、草稿、单在途请求与恢复状态机。
- `RemoteTextSender`：复用 `/session`、`/text`、UUID 幂等和真实回执。一次性发送不携带会随新 session 变化的顺序号，未知结果使用原 UUID 重试。
- `RelayTransportFactory`：MainActivity 与 IME 共用 LAN/Tailcat 构建路径。
- `UniversalInputMethodService`：一级目标键、远端草稿、动态发送键及输入路由。

## 协作

- Claude：输出目标状态机、失败恢复及测试设计。
- Codex：现有协议核实、Android 集成、实现、测试与验收。
- Jev：任务分类。
- Antigravity 不进入本次关键路径。


## 本地验证证据

- `python3 -m unittest tests.test_v07_core tests.test_v08_keyboard tests.test_rime_packaging`：21 项通过。
- `gradle -p android :app:compileDebugJavaWithJavac --no-daemon`：通过。
- `gradle -p android :app:compileDebugAndroidTestJavaWithJavac :app:assembleDebug --no-daemon`：通过。
- APK 签名验证：APK Signature Scheme v2，1 个 signer，通过。
- ZIP 完整性：通过。
- 包名：`io.github.amxooo.voiceinput2pc.v08probe`。
- 版本：`0.8.0-local-input`（versionCode 10）。
- APK 大小：256,457,125 字节。
- SHA-256：`eedd2b7e4ef9ae55de6715c9e21adc2222cfef91db4a39e09adf6ce2deb9ed75`。
- 手机共享目录：`/storage/emulated/0/Pictures/CodexCamera/MobileAI-Exports/Universal-Voice-Input-v0.8-dual-core-debug.apk`。

## 待真机验证

- 本机输入与远端目标切换。
- 远端草稿不污染宿主输入框。
- 远端回执成功后清除已发送前缀。
- 未知结果使用同一 UUID 重试且不重复输入。
- 重试期间新输入保留为下一段草稿。
- 密码字段和新编辑器强制回到本机目标。


## 本轮界面收口

- 日常输入改为三栏：左侧输入方式、中间文字/候选/键盘、右侧删除/目标/功能/回车或发送。
- Launcher 改为单一管理中心，不再把“手机输入/电脑输入”拆成两个产品入口。
- 功能二级页包含设备管理、输入记录与统计、输入法设置，并可安全返回原编辑会话。
- 远端草稿增加私有光标，可插入、删除和移动；未知回执前缀仍受保护。
- 键盘收起时保留已完成的语音预览；切换真实新输入框时清除旧预览。
- 处理 Rime 异步启动失败、宿主光标改变导致组字错位、数字/密码字段模式残留和横屏提取视图问题。

## 2026-10-13 真机反馈修正

- 普通文本框默认进入语音页；数字、邮箱、网址和敏感字段仍进入合适的传统键盘。
- 新增统一 `ImeLayoutSpec`：纵向面板 336dp、横向 280dp、左右功能栏各 48dp。
- 中间区域占据两侧栏之外的全部空间；语音预览与键盘行使用剩余高度，不再依赖 158dp/116dp 等状态魔数。
- 26 键字母字号提升为 16sp；纵屏三行按布局动态等分，横屏仍保证每行不少于 44dp。
- 音频 PCM 改为同步 Binder 背压，不再向单线程识别队列无限堆积；语音页每 0.2 秒显示已收音时长，区分“仍在录音”和“界面卡死”。
- 本地 21 项自动测试、Android 主代码/AndroidTest 编译、APK 构建、v2 签名和 ZIP 完整性均通过；本轮修正仍待用户真机复测。

## 2026-10-13 视觉与语音端点修正

- 选定视觉基线：`三栏结构-细节修正版.png`；真机旧界面判定 REJECTED。
- 输入法键位从 Android 默认 Button 改为扁平 TextView，移除 elevation、系统内边距和厚重选中背景。
- 纵屏总高压缩为 260dp，横屏 252dp；传统键盘隐藏冗余状态栏，中间区域继续占满剩余空间。
- 九宫格改为四行紧凑布局和两行数字/字母标签。
- 新增本地能量端点检测：确认已说话后连续静音约 1.5 秒自动结束录音；8 秒未检测到语音则明确结束，120 秒为硬上限。
- 最终文字自动进入中间可编辑区域，右侧操作变为“上屏”；左侧语音键仅保留“停止”作为提前截断备用。
- 自动测试与 APK 构建已通过；新版仍待安装、真机截图和视觉 QA，因此状态保持 DEVICE_PENDING。
