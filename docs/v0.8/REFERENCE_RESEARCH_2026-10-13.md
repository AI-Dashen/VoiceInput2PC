# v0.8 参考产品与源码核验

日期：2026-10-13。范围：公开官方资料、开源源码和豆包官方 APK 静态结构；未安装豆包、未动态抓取其数据、未复制闭源实现。

## Rime Ice

- 官方仓库：`iDvel/rime-ice`。
- 本地核对提交：`da1fbe602e38f26db846fa10120ee64c2b0324c0`。
- 许可：GPL-3.0-only；若分发其配置/词库，必须按该许可评估和履行义务。
- 已确认内容：全拼、双拼、英文混输、用户词典、Lua 过滤器、emoji 与多个大词库。
- 仓库确有 `t9.schema.yaml`，但文件注明面向 Hamster/元书等环境。它能提供数字映射和方案思路，不能直接替代 Android 九宫格 UI、触控、消歧与性能实现。

## BiBi Keyboard

- 官方仓库：`BryceWG/BiBi-Keyboard`。
- 本地核对提交：`3a49d74e1edacbe034ece76e27eec58598c1a382`。
- 许可：Apache-2.0。
- 可研究模块：`InputMethodService` 生命周期、SenseVoice 文件/伪流式识别、VAD、标点管理、模型预载/卸载、输入桥接与撤销测试。
- 它是实现参考，不决定本产品 UI 或目标路由。

## 豆包输入法官方样本

官方站点：`https://srf.doubao.com/pc`。站点宣传语音识别、方言/中英混输、领域词汇、复杂环境、文本整理与跨设备能力；这些是产品声明，不等于本机离线能力证据。

官方 Android 元数据接口于 2026-10-13 返回：

- 版本：1.5.1，versionCode 100501006。
- 发布日期：2026-10-09。
- 包名：`com.bytedance.android.doubaoime`。
- APK：官方 `lf-wave.doubaocdn.com` 地址，arm64。
- 大小：170,975,042 bytes。
- SHA-256：`abb24d0a33cee91a9d96783243b75950151bc478ce47da629e5fdf8844607f26`。
- minSdk 26，targetSdk 35。

静态结构确认：

- `ImeService` 使用 `android.permission.BIND_INPUT_METHOD`。
- 设置 Activity 位于 `:Setting` 进程；存在独立 `ImeEngineHostNetworkService`。
- APK 内含 26 键拼音、9 键拼音、英文、数字、符号、五笔、笔画、候选展开、悬浮输入等布局资源。
- 存在 ASR 词典资源、语音/识别动效、`libkeyboard.so`、`libime_net_sdk.so` 等 native 库。
- manifest 同时声明网络、录音、联系人、短信、相机、悬浮窗、安装包、推送等大量权限/组件。我们的本地优先版本不照搬这些范围。

REA Workbench 对 171 MB APK 的完整任务在其 MCP 固定 60 秒处超时，状态为 FAILED。随后使用 `aapt`、`apktool`、`unzip` 完成上述有限静态核对；不能把这次核对称为完整逆向。

## 可采用与不可采用

| 结论 | 决定 |
|---|---|
| 成熟输入法分离 IME 核心与设置/网络进程 | 采用稳定性思路，但不把同 UID 多进程说成权限隔离 |
| 26 键与 9 键必须是独立真实布局/解码链 | 采用 |
| 语音有清楚的 listening/recognizing/correct 状态 | 采用产品原则，自写状态与 UI |
| 大量账号、推送、WebView、在线风控组件 | 不采用 |
| 闭源字典、模型、图形资源、native 库 | 不复制 |
| 标准 Bluetooth HID 可通用发送中文 | 否定；仅可作 ASCII/快捷键应急或配套接收端的自定义传输 |
