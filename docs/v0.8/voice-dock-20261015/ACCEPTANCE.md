# 语音侧边输入改版验收记录

日期：2026-10-16。版本：0.8.5-voice-dock（15）。状态：PENDING，尚未完成系统绑定窗口验收。

## 实际分工
- Codex：整合代码、构建、安装和验收。
- Claude：状态机、连续输入、串字和失败恢复审查；成功返回，见 claude-review.json。
- Gemini 网页：Android 输入法窗口与侧边交互审查及参考图；成功返回，见 gemini-review.json。网页显示 Gemini Argon，底层模型 ID 未知。
- Jev：风险分类和路由；成功返回，见 jev-review.json。
- 独立 Codex 子任务：核心实现、验证用例、Claude 通道核验。

## 实测
- Python/JVM 输入测试：7/7 PASS；包含 VoiceDockPolicy 15项行为断言。
- 历史查询/导出测试：6/6 PASS。
- Debug APK 与 AndroidTest APK：BUILD SUCCESSFUL，20秒，见 build.log。
- 两个 APK 已安装。
- Android EditText/InputConnection 集成测试：10/10 PASS，0.771秒，见 device-integration.log。使用测试语音回调，不代表真实录音成功。
- 系统绑定窗口测试：当前默认输入法为 com.bytedance.android.doubaoime，前置条件失败，见 device-bound.log。未进入窗口行为验收，未修改系统输入法设置。

## 待完成
临时切换当前输入法需按系统设置规则获得本批授权，再验证侧边窗口、触摸区域、键盘展开与真实截图。真实麦克风连续识别、电脑接收回执仍需单独验证。不得把参考图或 Android 输入事务测试称为这些项目已通过。
