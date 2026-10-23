# 逐段九宫格消歧、选字保留与删除回退

日期：2026-10-15。本增量取代《T9_IMPLEMENTATION_FEASIBILITY》早期“仅首音节、部分选字后禁用”的实现限制。现已在原有生产Java Engine→生产JNI→真实librime 1.17.0链路验证逐段选择、编辑、撤销和部分选字后的继续组合。没有安装APK或操作Android应用；宿主UI端到端由主调度最后统一验收。

## 实施范围

修改 `T9Disambiguation.java`、`ChineseInputEngine.java`、`RimeChineseInputEngine.java`、`KeyboardController.java`、独立 `tests/T9DisambiguationTest.java`。没有修改Service、AndroidTest或其他测试runner。沿用已有mixed prism与419音节索引；资源0.8.2和JNI二进制无需再次变更，没有重编词典。

## API与语义

- `Snapshot.numericInput`：原始数字及用户手动输入的分隔符；`rawInput`仍是Rime实际混合输入，如`ni'hao'746`。
- `Snapshot.syllableSegments`：每段有 `start/end/syllable/locked`；offset索引numericInput，不能索引rawInput。空syllable表示尚未约束的数字范围或没有显式音节约束的已选字范围。
- `activeSyllableSegment`：当前可操作段的索引；全部已约束且没有未选尾部时为-1。
- `syllables`：当前段真实词典拼写候选，完整音节数字码必须与当前位置相符。
- `chooseSyllable(s)`：选当前音节并推进；空串撤销已激活段及依赖尾部约束，未激活已有段时撤销最后可编辑音节。
- `activateSyllable(i)`：回到一个未锁定段改选。改变该段音节后丢弃后续依赖约束，恢复其原始数字。
- `canUndoSyllable`：是否存在可撤销的音节约束。它不表示可以撤销Rime拥有的已选汉字。
- `selectedSyllable`保留兼容，值为最近一个仍保留的显式音节约束；不能再将它解释为整串唯一音节。
- `KeyboardController.setLearningEnabled(boolean)`委托引擎，供Service落实编辑器禁止个性化学习要求。

## 保留Rime已选汉字的方法

没有clear/replay整个组合，也没有把已选汉字提前提交宿主。通过同一个session的公共set_input只更改已选前缀之后的输入。

锁定边界不按候选字数或comment长度猜测。本schema没有preedit格式转换：未选尾部是输入字母/数字及Rime插入的空格/分隔符，因此将context选区开始之后的尾部与实际raw作严格后缀匹配，再按字母/数字一对一映射回numericInput边界。实际raw必须与本状态模型生成的input完全一致；尾部字符集合与后缀必须通过验证。任何不满足的context进入保护态，停止重建输入和消歧，保留Rime原生键处理，直到reset；不会猜测边界覆盖已选字。

Rime的selected-prefix范围设为只读：不能activate或撤销其音节约束；已选字之后的未完成音节仍可选、改、撤销和继续输入。

## 删除回退的真实行为

1. 尚未选汉字时删除一个原始数字/手动分隔符；如果删除触及某个已约束音节，撤销该音节约束后重新译码。更早完整音节保留。
2. 已有Rime选字片段时先交给Rime原生Backspace。当前库先重新打开最近的已选汉字片段，raw不变，确认区解除；没有把字从宿主删除。
3. 后续Backspace再删除原始末尾数字。该顺序来自真实执行，而非自定义伪回放。

## 实证结果

见 [完整Java→JNI日志](t9-feasibility-evidence/host-multisegment-result.log)。Android的Context/AssetManager在Termux JVM中仅以文件stub提供，生产引擎、JNI和librime均为真实代码。关键结果：

| 操作 | 实际Rime状态 |
| --- | --- |
| 64426746542依次选ni/hao/pin/jia | raw=`ni'hao'pin'jia`；候选包含“你好贫家” |
| 撤销第四音节 | raw=`ni'hao'pin'542`；待选音节含ji/jia/li/lia |
| 删除尾码后回第二段选gao | raw=`ni'gao'74654`；前ni保留，后续约束撤销 |
| 激活第一段后撤销 | 原始数字`6442674654`恢复，所有依赖约束清除 |
| 64426部分选“你” | preedit=`你426`，UTF16 selectionStart=1，待选音节含hao |
| 在“你”之后选择hao | raw=`64'hao`，preedit=`你'hao`，已选“你”保留 |
| 再输入746542并选pin/jia | raw=`64'hao'pin'jia`，preedit仍以已选“你”开头 |
| 撤销尾约束 | 保留已选“你” |
| Backspace | 先重新打开选字，raw不变；再次才删尾数字 |
| ni/hao两段删除跨边界 | `ni'42`→`ni`→`6`→空；约束随实际数字删除 |
| 最终选“你好” | get_commit仅返回一次“你好”，随后raw空且再次select无提交 |
| private方案 | 同样支持逐段ni/hao，词典不学习配置保持 |
| 全拼回归 | nihao仍包含真实“你好”候选 |

独立纯Java测试也通过：四段、逐段撤销、激活改选、手动分隔符、数字选字后的锁定范围、尾部继续消歧、不匹配context保护、删除跨边界、旧Snapshot构造器与面板显示兼容。

C级补充证据：[选字后尾部替换](t9-feasibility-evidence/tail-result.log)、[原生删除序列](t9-feasibility-evidence/delete-result.log)。可复核运行源码在 `t9-feasibility-evidence/multisegment-host-src/`；输入均为合成测试文本，无用户数据。

## 剩余边界

本轮实现完整逐段约束操作，没有实现豆包私有排序、纠错和模型算法。音节候选按当前字典合法拼写列出，不冒充概率排名。已确认的raw/未选尾部映射依赖当前无preedit格式转换的自有schema；未来改变formatter必须重新验证此契约。APK安装、实际编辑器的界面与系统输入体验尚未在本子任务中验证，由主调度集中完成。
