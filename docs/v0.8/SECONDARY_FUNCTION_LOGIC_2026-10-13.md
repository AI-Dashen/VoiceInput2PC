# v0.8 一级/二级功能逻辑

日期：2026-10-13
状态：CODE_COMPLETE（尚未真机验收）

## 已实现

- 一级输入状态 `PRIMARY` 与二级功能状态 `SECONDARY`。
- 二级包含 `HUB`、`TRANSFER`、`STATISTICS` 三种状态。
- 一级“功能”键进入二级；二级“返回”、Android Back 或再次点击功能键返回一级。
- 进入二级时保存编辑会话 generation、输入模式、原始组字和光标/选区位置。
- 返回只接受同一编辑会话；输入框已经变化时丢弃旧快照，不向编辑器写入旧内容。
- 二级期间输入按键不修改宿主输入框，不提交正在组字的内容。
- 换输入框、输入法隐藏、输入会话结束时清除二级状态。
- 密码等敏感字段禁用功能入口。
- 跨设备入口显示真实配对状态，并进入现有跨设备输入工作区。
- 统计入口进入现有本机输入历史、查询和导出页面；趋势图表尚未实现。

## 验证

- `python3 tests/test_v08_keyboard.py`：PASS。
- `gradle -p android :app:compileDebugJavaWithJavac --no-daemon`：PASS。
- `gradle -p android :app:assembleDebug --no-daemon`：PASS。
- APK：`android/app/build/outputs/apk/debug/app-debug.apk`。

## 尚未验证

- 真机点击功能键、二级导航与 Android Back。
- 拼音/九宫格正在组字时进入二级再返回。
- 输入框切换、锁屏、分屏和手势返回。
- 完整统计聚合与趋势图表。

## 外部评审

- Jev：低影响项目开发，建议 Android 实现核对。
- Claude：完成状态机评审；确认二级不得提交组字、快照必须绑定编辑会话、候选返回后重算。
- Antigravity：本轮 180 秒超时，无有效意见；未把超时当成通过。
