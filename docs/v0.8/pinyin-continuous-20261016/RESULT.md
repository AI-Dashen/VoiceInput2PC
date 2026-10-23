# 全拼连续输入故障修复（2026-10-16）

## 用户问题与范围
连续打拼音后停止出候选。本轮仅改输入事务，不改界面布局；未切换系统当前输入法。不能把本轮确认的回调缺陷认定为用户所有卡顿的唯一根因。

## 证据
- 源码及实际打包schema：全拼无24位上限，enable_sentence=true；24位限制仅旧T9。
- 相同原生Rime库与词典的Termux Probe能生成长句候选。原rime-probe命令返回6是其硬编码预期“你好”与长句不匹配，不是引擎崩溃或候选为空。
- 修复前手机APK内真实Rime + EditText/InputConnection测试：3项中2通过、1失败。没有组字范围但光标与输入匹配的回调令rawInput从j变空（device-before.log）。回调由测试注入，尚未捕获用户原App触发的同类回调。
- 修复后手机17项PASS，1.484秒：6项连续全拼、10项语音事务、1项原生引擎。覆盖超过24字母长句、简拼、候选提交、删除继续、无范围回调、过期回调和真实移动光标保留文字。
- 主机键盘回归4/4 PASS，2.360秒。
- Gradle debug及AndroidTest构建成功，15秒；通过统一安装器更新自身App与测试包，未清数据。

## 修改
UniversalInputMethodService：记录本次拼音写入的期望光标；支持getExtractedText的宿主核对实际光标过滤过期通知；不再因组字范围缺失但光标匹配而清空拼音；真正移动时finishComposingText保留已显示文字。
新增PinyinContinuousIntegrationTest，用真实Rime库验证，不用字典mock冒充引擎。

## 分工
Claude通过既有ask-claude实际返回54.395秒，审查候选/状态机；独立Codex复审指出不支持extract的边界。
Gemini官网账号已核对cooocvc@gmail.com，网页显示Gemini Flash-Lite，底层ID未知；建议时间窗100ms忽略回调没有采用，会掩盖真实移动。
Jev实际完成风险分类。Codex执行真实测试、修复与验收。

## 已知限制
未进行用户原宿主App连续真人输入复测，不能声称所有卡住均已消除。不支持getExtractedText且没有composing范围的宿主，其过期回调与真实移动无法用此证据完全区分；初始光标也未知时需进一步适配。未设置新硬长度上限，不以限制输入掩盖问题。
本次实现连续拼音组词修复，不等于已实现“选完一个词后预测下一个词”的独立联想能力。
外观仍为用户已否定的0.8.5原型布局，紧凑触点和键盘改版仍在设计讨论，不能称本轮已完成。
