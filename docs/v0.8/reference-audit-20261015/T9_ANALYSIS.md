# 豆包九宫格：数字事件、拼音消歧、候选与确认

日期：2026-10-15。范围：已有 APK 资源和 JADX 反编译 Java，与自有 v0.8 源码静态对照。没有启动应用、Root、注入、读取应用私有数据或网络请求。本文的“代码事实”只说明静态实现，不代表真机行为 VERIFIED。

路径简写：D=`analysis/doubao-ime-20261013/jadx-src/sources/`；A=`analysis/doubao-ime-20261013/apktool-resources/`；O=`voiceinput2pc-v08/android/app/src/main/`。以下行号均为本次工作区文件行号。

## 结论

可从现有代码确定：豆包九宫格有独立的音节过滤状态和音节选择事件，音节选择不是点击一个汉字候选。其候选对象还分别保存显示文字、预期文字、提交文字、原始文字、拼音显示与拼音分段。自有输入法已经有真实 Rime 数字译码、分页和部分选择后的继续组合机制；关键缺口在于 JNI→引擎接口→控制器→界面没有传递这些中间状态，界面只能显示字符串候选，并以第一候选代替九宫格组合显示。因此继续增加字符串替换、隐藏数字或特判个别词，不能补齐消歧交互。

不能从 Java 判断豆包数字串的切分、搜索、排序、词频、纠错或模型结构；这些行为跨入 native，不能把 Java 的候选快照误称完整拼音引擎源码。

## 可核查链路

| 阶段 | 静态事实 | 证据 |
| --- | --- | --- |
| 数字布局 | `Pinyin9Layout userdata="pinyin9"` 关联 `pinyin9_filter_list`；ABC 发送 `0x32`，DEF 发送 `0x33`，其余映射同理 | A`assets/skin/default/layout/input_kbd_pinyin9.xml:3`, `:9`, `:45`, `:66` |
| 长按/副输入 | 正常数字 keycode 与长按字母/数字 keycode 分开，不能把正常数字键理解为 Java 先枚举 ABC | 同文件 `:45–50`, `:66–83` |
| 触摸入引擎 | Android `MotionEvent` 计算 x/y/action 后调用 `nativeTouch(mNativeViewId,x,y,action,time)`；声明是 native | D`com/bytedance/android/input/keyboard/KeyboardView.java:1012–1056`, `:604` |
| 原生处理边界 | Java 在此不能看到 ButtonChar 命中、数字累积、拼音切分和排序算法。XML 与 nativeTouch 共同证明布局配置和入口，未证明其间每个 native 调用 | 同上 |
| 拼音/候选回传 | `notifyCandidateBarSnapshot(...)` 接收字符串数组、候选索引/类型等数组、拼音文字、音节列表及当前音节；Java 构建候选模型与九宫格快照 | D`com/bytedance/android/doubaoime/KeyboardJni.java:4344`, `:4830`, `:4902–4935` |
| 独立音节状态 | 快照构造器明确检查 `syllableFilters`、`selectedSyllable`、`candidates`、`pinyinHighlightedText`、`pinyinNormalText` | D`com/bytedance/common_biz/more_candidate/E.java:35–53` |
| 音节列表显示 | 视图使用 `e2.j()` 音节列表和 `e2.g()` 当前音节更新独立音节适配器，并分别更新候选适配器 | D`com/bytedance/common_biz/more_candidate/Pinyin9SplitCandidateView.java:357–400` |
| 点击音节 | 音节回调 `h(syllable)`，进一步调用 native `onMoreCandidateSelectSyllable(str)` | 同文件 `:63–75`；D`com/bytedance/android/input/keyboard/CustomToolbar/i.java:95–100`；`CustomToolbar/h.java:220–226`；`KeyboardJni.java:7131` |
| 点击候选 | 候选点击携带对象多个字段，经 `f(...)` 回调；普通扩展候选处理器调用 native `onMoreCandidateCommit(...)`，返回值参与关闭面板处理 | `Pinyin9SplitCandidateView.java:91–115`；`CustomToolbar/h.java:197–211`；`KeyboardJni.java:7125` |
| 横屏特例 | 分栏视图有符号、默认词、普通候选三种点击分支；横屏处理器 i 的普通候选转交候选栏处理器，不能把它的全部分支当成普通竖屏链路 | `Pinyin9SplitCandidateView.java:100–114`；`CustomToolbar/i.java:18–26`, `:54–82` |
| 最终编辑器提交 | `DoCommit(...)` 是 Java 提交入口之一；读取服务/选择状态、处理已有组合区后调用 `InputConnectionWrapper.commitText` | `KeyboardJni.java:1719–1750`, `:1914–1961` |

上表原生回传的时序是接口设计支持的解释；未反汇编原生库，也未运行 trace，故不声称已证明某次按键一定按此精确顺序回调 `DoCommit`。

## 候选与组合不是同一个字符串

D`com/bytedance/common_biz/tool_bar/view/config/k.java:36–56` 的构造器显式区分：`text`、`expectedText`、`commitText`、`originalText`、`pinyinDisplay`、`pinyinSegments`。`Pinyin9SplitCandidateView.java:113` 将类型、索引及多个字符串一起送回处理器，而非直接把列表可见文字写入编辑器。

D`KeyboardJni.java:4216–4228` 还提供两个字符串的独立 `notifyCandidateBarPinyin` 回调；`E.java:39–40` 明确称作高亮和普通拼音文字。由此可以确定界面支持拼音状态的独立表达。不能确定高亮是否始终对应已确认片段、当前待选音节或其他具体语义；需后续限定包名的授权观察或更多 native 证据。

候选扩展不是每次全量重建：`Pinyin9SplitCandidateView.java:365–373` 有追加快照分支；`:281–289` 在接近列表末端、允许加载且尚未加载时传快照 long 值请求更多。`KeyboardJni.java:2914–2925` 有九宫格快照缓存/合并，`:6171` 有重放入口。long 字段可确定用于加载/快照关联，尚不能声称其就是全局 composition generation。

## 与自有 v0.8 的逐层对照

| 机制 | 自有当前代码 | 缺口及影响 |
| --- | --- | --- |
| 数字译码 | `scripts/rime/schemas/t9.schema.yaml:15–25` 把字母表映射到 23456789，使用真实 `script_translator`、雾凇词典、句子与用户词典 | 不是“完全没有九宫格引擎”。已有数字 prism，但不能据此声称具备与豆包相同的消歧算法/质量 |
| 原始输入 vs 拼音 | O`cpp/rime/rimebridge.c:52–66` 优先导出 `get_input()`，只导出页码、hasNext、候选 `.text` | 没有单独导出 `composition.preedit`、光标/选择范围、候选 comment 或分段；即使 Rime 原生状态存在，Java 也看不到 |
| 引擎状态接口 | O`java/io/github/amxooo/voiceinput2pc/ChineseInputEngine.java:10–23` 仅 rawInput、List<String>、page、hasNext、available/error | 没有音节过滤器、选中音节、分段、显示/提交区分、稳定候选标识、状态修订号 |
| 选择音节 | 接口仅 input/backspace/select/翻页/reset（同文件 `:25–37`） | 不存在“选择一个拼音而继续保留数字组合”的显式操作；不能从单纯点击候选补出此能力 |
| 九宫格组合显示 | O`java/.../KeyboardController.java:40–48` 把首汉字候选当 visible composing，纯数字则隐藏 | 会把推荐结果当成组合解释，用户看不到数字实际被解析成哪个拼音；隐藏数字只改变表象 |
| 候选确认 | O`cpp/rime/rimebridge.c:71–83` 将页内索引换算全局索引，调用 Rime select_candidate，仅有真实 get_commit 才返回文字；O`java/.../UniversalInputMethodService.java:327–334` 选后重新同步状态 | 已经保留“未完成选择返回 null 并继续组合”的可能，不能误报完全没有分段选择。缺的是把选择后状态明确展现、区分 partial/final/invalid 的结果类型 |
| 数字输入 | O`java/.../UniversalInputMethodService.java:214–218` 发数字，1 发分词符；`KeyboardController.java:24–29` 发给引擎 | 分词符输入不等于音节列表过滤。需要允许用户选择歧义解释，而非只手动插入分隔符 |
| 呈现 | O`java/.../UniversalInputMethodService.java:939–953` 为字符串候选创建横向项及翻页按钮 | 没有音节轨道、当前音节选中态、明确未完成区或展开候选模型 |
| 编辑器兼容 | O`java/.../EditorCompatibility.java:9–10` 禁用 T9 inline composition；Service `:311–325` 维护内部/目标组合并条件写入编辑器 | 这是当前宿主输入策略，不能把第一候选显示与最终提交混为一谈，也不能据静态分析直接取消兼容策略 |

表中 `java/.../` 均表示 O`java/io/github/amxooo/voiceinput2pc/`。

## 应形成的整体设计任务（建议，尚未实现）

1. 先定义 `CompositionSnapshot`：原始按键、可读拼音、分段/当前段、可选音节、已选解释、候选对象、页/加载状态、修订号。区分“引擎实际上能提供的字段”和“计划实现的字段”，不要由 UI 猜造拼音列表。
2. 定义动作与结果：输入、删除、选择音节、取消音节约束、选择候选、翻页、确认、重输；选择结果区分继续组合、最终提交、过期/无效。数字原串与音节约束由同一状态所有者管理。
3. 先核查现有 Rime 公共 API 与当前库构建实际可提供的 preedit/comment/选择状态；优先完整导出已存在的状态。音节枚举/约束若公共 API 不直接支持，做独立原型选择可维护方案，不能承诺一个 nativeSelectSyllable 包装就能完成。
4. UI 消费快照：拼音解释区、可点音节轨道、汉字候选区分别显示；选音节更新过滤和候选，选字由真实引擎决定是否已最终提交。不要从首候选倒推出拼音状态。
5. 以带歧义的完整会话验收：数字输入→选音节→继续输入→撤销约束/删除→选择部分候选→剩余组合→最终确认；同时覆盖翻页后的索引、快速连按下过期候选、模式/编辑器切换、空候选、取消和重输。具体词的预期需由自有词典实测确定，本文不伪造排名。

## 未确定事项

- native 内部的数字分段、模糊音/纠错、整句搜索、学习与排序权重。
- 音节过滤究竟限制当前段、首段还是全串，以及删除时恢复的精确规则。
- native `onMoreCandidateCommit` 布尔值的全部语义；这里只能证明它影响候选面板关闭，不能直接映射自有 `null`/commit。
- 静态资源支持的长按、单字过滤与默认词是否在当前所有布局、设置、编辑器中启用。

这些都不妨碍先修正自有状态协议与 UI 分层。后续若要进一步研究，需要单独说明具体观察范围与验收，不应以本报告授权推导动态注入或系统操作授权。
