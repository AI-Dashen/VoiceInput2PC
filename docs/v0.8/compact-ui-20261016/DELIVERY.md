# 紧凑语音输入界面交付（2026-10-16）

状态：原型已更新安装，自动化范围VERIFIED；系统绑定窗口、外部点按收起、真人麦克风及电脑实际回执PENDING。

## 实际开发分工
- Claude：实际编写 CompactImeUiPolicy.java 和 CompactImeUiPolicyTest.java，返回success，33.753秒；原始返回保留claude-code-result.json。代码由Codex解析写入项目并接入真实界面，非只评审。
- Codex界面子任务：UniversalInputMethodService紧凑触点、菜单、键盘重构。
- Codex测试子任务：CompactUiRenderTest和VoiceDockIntegrationTest迁移，保留语音事务。
- 主Codex：代码复审、null guard、Claude模块集成、构建、安装和证据检查。
- 既有Gemini网页评审给出单触点方案；本轮没有让Gemini写代码。Claude新交互评审17.731秒，原文claude-review.json。

## 交付功能
默认单48dp触摸热区/36dp麦克风图形，取消常驻竖栏。点按收音/停止，长按展开键盘、删除、宿主action、更多；识别结束有短删除/action控件。目标选择、历史统计、设置和其他输入法统一从更多进入；远端保留明确目标和草稿。
键盘三行字母加底行：无独立数字行，q-p长按1-0，常用逗号句号直达，数字符号页按需。竖屏220dp、横屏188dp，组字额外48dp，远端草稿额外48dp。
保留全拼连续输入修复；本轮不声称提交后下一词预测已实现。

## 验证
Claude纯Java策略测试all passed；Gradle app/debug+AndroidTest成功18秒。手机Instrumentation19项通过3.19秒，包含2项紧凑布局/按键、10项语音事务、6项真实Rime连续拼音、1项真实原生引擎。未更改当前系统输入法。
previews下4张PNG为Android生产View按照本机DisplayMetrics离屏绘制，模拟编辑器与候选有明确测试标签；不是系统绑定截图，不证明覆盖区域透传或OEM外部点击收起。
本轮APK内部版本仍0.8.5-voice-dock（15），共享文件名标注紧凑界面，未伪造0.8.6版本。
