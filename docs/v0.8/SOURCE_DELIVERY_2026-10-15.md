# 0.8 开发源码同步（2026-10-15）

本次同步最新本机0.8输入法开发代码，保留0.7测试版Release和main展示文档，不将0.8标成稳定发布。全部源码位于feature/universal-input-v08。

## 已同步范围

自有Java IME和编辑器会话、语音生命周期、6秒端点停顿/超时恢复、中文引擎接口及Rime JNI、九宫格/26键/符号、输出目标路由、设置与传输页面、Android测试源码、纯Java/Python测试、Rime构建脚本/schema、依赖来源manifest及许可证。源码与模型权利分开，不将第三方组件标成自有。

## 不随Git提交

- 闭源ASR模型 model.flute、libaudioeffect.so及其依赖和预编译JNI库；不授予第三方组件再分发权。
- native-deps中的包、源码缓存、词库用户数据库、查询历史和测试临时文件。
- 编译字典、AAR、APK、录音/测试音频、现场UI dump、个人配对和签名文件。

## 构建前提

Android SDK36、Build Tools36.0.0、项目Gradle/AGP、JDK17+。本机使用Termux原生环境，没有Debian/proot。

1. Tailcat bridge：按已有scripts/build_tailcat_android_bridge.py及相关说明构建/恢复可信的android/app/libs/tailcatbridge.aar；preBuild缺少它会明确失败。
2. Rime：scripts/rime/download-dependencies.sh列出官方Termux包版本；scripts/rime/build-native.sh生成JNI和隔离依赖。官方版本退仓须重新核验，不能假装脚本永远可用。脚本不自动执行下载。
3. 词典：按android/app/src/main/assets/rime/source-manifest.json中的官方固定commit恢复原始字典到native-deps/rime-source；scripts/rime/precompile-data.py生成APK内字典。许可随源码保留，编译后data须遵守原许可。
4. 本地ASR：Java/JNI接口源码已提供，但完整运行仍需要合法取得且匹配ABI的私有模型及运行库。仓库不提供或自动下载。JNI源码位于android/app/src/main/cpp/offline_asr_jni.c，Java加载名为offlineasr。未配置时不得声称完整离线语音可用。
5. 构建：gradle -p android :app:assembleDebug --offline --no-daemon。release签名只从环境注入，不提交签名秘密。

直接克隆缺少上述外部依赖，不是开箱即用的完整离线语音发行包。本次没有创建包含私有组件的公开APK Release。

## 本次验证

本机已有依赖环境assembleDebug成功，7秒，34项任务（1项执行、33项最新）；这不是干净克隆可复现证明，也不是新真机交互验收。

Python全量首次执行82项：1个版本断言过期、12个环境相关错误（缺少cryptography、tkinter、Windows API/powershell.exe）。版本断言已更新为实际versionCode10/versionName0.8.0-local-input。相关回归实际通过34项：输入会话/键盘4项（内部运行纯Java断言）、Rime打包7项、跨设备核心13项、发布身份及配置10项。Windows专用PowerShell检查未运行；不声称全量测试已通过。

Claude已独立复核上传边界：不上传私有依赖、测试数据或凭据，明确源码与可构建性差别。未采纳其不必要的全量历史重写建议，保留既有Git历史。
