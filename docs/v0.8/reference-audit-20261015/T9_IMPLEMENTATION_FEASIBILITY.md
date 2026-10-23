# 保留 Rime 实现九宫格音节消歧：可实施性核验

核验日期：2026-10-15。结论：**保留现有 Rime 可实现明确音节约束，已在本机 Termux 用现有 librime 1.17.0 和真实雾凇词典运行成功隔离原型。无需更换引擎，也无需读取豆包进程。** 尚未接入 APK，Android UI/编辑器端到端验收为 PENDING。

本次只读业务源码/豆包已有二进制；只在 `native-deps/feasibility-probe/` 构建与运行隔离探针，生成独立 schema/prism/user 目录。没有修改正式 schema、词库、JNI 或业务代码，没有下载、Root、注入或启动 Android 应用。小型可复核证据放在 [t9-feasibility-evidence](t9-feasibility-evidence/)；45MiB 源词典和构建副本留在被 Git 忽略的 native-deps。

## 一、公共 API 到底提供什么

本机 SDK：`native-deps/root/data/data/com.termux/files/usr/include/rime_api.h`；实际动态库：`android/app/src/main/jniLibs/arm64-v8a/librime.so`。探针输出 `version=1.17.0 set_input=1 highlight=1`，不是仅检查头文件。

| 可导出字段/动作 | SDK 行号 | 本机结果及边界 |
| --- | --- | --- |
| composition.preedit、length、cursor_pos、sel_start/sel_end | 119–125 | 输入64426返回 `preedit=64 426`, `len=6`, `sel=0:6`；数字未自动全部转换成拼音 |
| candidate.text / comment | 127–131 | 候选“你好”同时返回 comment=`ni hao`；单字或已选后候选的 comment 可为空，不能强制当完整音节真值 |
| page_size/page_no/is_last_page/highlighted_candidate_index | 133–141 | 原型返回 page=0/highlighted=0；正式 JNI 目前只导出部分分页信息 |
| commit_text_preview | 155–163 | 同一次64426返回 `你好`；它是预览，不能代替 get_commit 当最终提交 |
| get_input / get_caret_pos / set_caret_pos | 430–442 | raw=64426、caret=5；移到2后候选和preview仅解释前段；raw caret 与显示 preedit 光标不同 |
| select_candidate / 当前页选择 / 候选迭代 | 436–452 | `ni'hao` 选“你”后保持组合，完整选择则真实 get_commit；不是每次选择都提交 |
| set_input | 493 | 可把数字前缀换为明确字母音节；真实有效，详见下一节 |
| highlight_candidate | 502–505 | 运行时接口存在；本次未测试高亮动作效果，不能标为实测成功 |

**偏移单位必须转换。** 本机已选“你”后 `preedit=你hao`、`len=6`、`sel=3:6`，说明这些长度/偏移按 UTF-8 字节表达，不是 Java UTF-16 索引。JNI 必须一并导出或可靠转换，不能直接 `substring(sel_start)`。

公共 C API 没有语义化的 `get_syllable_filters`、`select_syllable`、完整 segment lattice 或每条候选原始拼音码数组。`RimeCandidate.reserved` 是不透明保留值，不能当拼音结构强制转换。不能靠“公共 API 存在”声称所有九宫格侧栏逻辑已经现成提供。

## 二、已验证的约束路线：混合字母/数字 prism

当前正式 `scripts/rime/schemas/t9.schema.yaml:15–25` 只允许数字，使用 `xlit/abcdefghijklmnopqrstuvwxyz/22233344455566677778889999/`，因此精确字母音节不能直接作为输入约束。

仓库已有参考 `research/upstream/rime-ice/t9.schema.yaml:111–125` 及 `native-deps/upstream-t9.schema.yaml` 使用8条 derive 规则（保留原字母，并派生数字/混合拼写）。本次只提取这8条规则，仍使用自有简化 engine 配置；没有照搬其 `t9_processor` 或 Lua 依赖。

```yaml
speller:
  alphabet: 'abcdefghijklmnopqrstuvwxyz23456789'
  initials: 'abcdefghijklmnopqrstuvwxyz23456789'
  delimiter: " '"
  algebra:
    - derive/[abc]/2/
    - derive/[def]/3/
    - derive/[ghi]/4/
    - derive/[jkl]/5/
    - derive/[mno]/6/
    - derive/[pqrs]/7/
    - derive/[tuv]/8/
    - derive/[wxyz]/9/
translator:
  dictionary: rime_ice
  prism: t9mixed
```

隔离原型保留真实 `rime_ice.table.bin` 和源词典，只部署新 prism。新 prism 约103KiB，已有纯数字 prism约13KiB（实际文件，非内存测量）。`deploy_schema` 最初报 `unknown deployment task: schema_update`；补上公共 `deployer_initialize(&traits)` 后 deploy=1。部署属于构建期工具，不能据此把 IME 内高内存编译重新启用。

实际结果见 [result.log](t9-feasibility-evidence/result.log)：

| engine raw | 首批真实候选 | 解释 |
| --- | --- | --- |
| 64426 | 你好、你敢、你搞、蜜柑、米糕… | 同一数字码保留歧义 |
| ni426 | 你好、你敢、你搞、拟好… | 精确ni约束，尾部426仍歧义 |
| ni'426 | 你好、你敢、你搞、拟好… | 加显式音节边界同样有效，建议生成此形式 |
| mi426 | 蜜柑、米糕、密函、密告… | 换成mi约束，候选随之改变 |
| ni'hao | 你好、拟好、你… | 两个精确音节仍由真实词典给汉字候选 |
| ni'hao'746542 | 你好贫家、你好奇、你好… | 字母前缀+数字尾部可继续组合，结果排名不代表质量已验收 |

这不是简单按候选 comment 做 UI 过滤；约束进入 Rime 输入，重新执行真实译码/组句。原始用户数字串应另外保存，不能被 `ni'426` 覆盖，否则撤销约束时无法还原用户操作。

## 三、音节列表如何真实枚举

建议在词库构建阶段，从当前实际启用词典的拼音码生成音节表与 digit→syllables 索引，并随 schema/prism 版本发布。对本机 `cn_dicts/8105.dict.yaml` 只提取第二列单音节小写码，实得419个不同音节；这是该文件的集合，不声称包含全部启用词典的码。输入64426的可匹配首音节有 `m / mi / n / ng / ni / o`，连 `m/n/ng` 这些少见读音也确实来自字表，不能随意写死删去。

实施流程：

1. 自有状态持有原始数字D、选定的音节约束列表 `{digitStart,digitLength,syllable}`、当前位置、已选汉字片段关联状态。
2. 用码表匹配当前位置数字的完整音节前缀；允许输入尚未完结时的音节前缀候选要单独标记，不能把未完成前缀伪称已确定完整音节。
3. 对剩余数字做动态规划分段，避免只给出导致后段完全不可解释的路径。若允许尾部未完成拼音，应把“可作为任一合法音节前缀”的终态纳入，而非要求所有尾码都是完整音节。
4. 选择 `ni` 消耗原始数字64，生成 engine input=`ni'426`，调用 `set_input` 并取新 context；撤销约束回到64426。选择下一音节hao得到`ni'hao`。
5. 可用独立、不学习的查询 session验证约束分支是否实际产生候选。查询结果应有缓存、候选读取上限与耗时限制，不为每次渲染穷举全部路径。

码表列表是拼写候选集合，不能伪称 Rime 的概率排名。候选 comment可用于展示、给已出现音节加权，但仅第一页comment不能提供完备列表；单字comment为空和spelling_hints截断都已需要处理。若后续加入模糊音、简拼或额外代数规则，生成器必须同步对应规则，否则侧栏与引擎会不一致。

**本次状态：**混合码的真实译码与约束是本机成功；419项枚举是真实数据提取；动态规划、查询session验证与侧栏排序仍为设计/PENDING，未冒称已经实现。

## 四、已选汉字片段与确认：不能重置整个会话

真实测试：`ni'hao` 当前页第2项为“你”；选择后：

```
raw=ni'hao preedit=你hao sel=3:6 preview=你好
candidates=好/号/毫/豪/浩/耗/昊
```

接着不清空 session，`set_input("ni'hao")` 保留已选“你”；再 `set_input("ni'426")` 仍返回 `preedit=你426 sel=3:6`，尾部候选变为“好/高/感/干/告/敢/赶”。说明**已确认前缀不变时，当前库的 set_input 能保留这次已选汉字片段**，已经不是纯理论。

但不能泛化为任意编辑都能保留：在前段插入/改音节、删除到前段、重建 session、先 clear_composition 后回放原串都未验证。正式实现应保留“已选片段+其覆盖的原始数字范围”，约束动作默认限定未选尾部；用户修改已选前段时显式撤销后续片段并重建。原始输入相同但用户选了不同同音字时，仅存 raw 无法恢复原选择。

默认 Rime 行为已测试：完整候选选择后 raw/preedit清空、`get_commit=1`；空格键也产真实commit。选择部分候选则保留组合。探针曾临时设置 `_auto_commit=false` 对比，此时完整选择只确认不自动commit；最终保存的原型已移除此设置，报告结果均为默认值。正式代码不应引入该内部选项。

建议返回结构：`ActionResult {applied, committedText?, snapshot}`，避免用 null 同时表示未完成、无效、不可用。最终上屏只能使用 get_commit 的文本。清空剩余候选不等于必然提交成功。

## 五、豆包 native 可以继续追踪到什么程度

已有二进制保留大量动态符号，不能再笼统称“native全黑盒/无法再分析”。本次静态反汇编确认：

- `libkeyboard.so` `controller::BoardController::SelectUserSyllableFilter` **0x232a90–0x232ab0**：调用 `InputModel::GetInstance`，尾调用 `InputModel::SelectUserSyllableFilter`。
- `InputModel::Impl::SelectUserSyllableFilter` **0x258a68–0x258ba8**：0x258b24取`GetShell`，0x258b34经vtable调用，再通知一组监听器。未完成vtable类型映射，不能仅凭这条间接调用证明具体目标。
- `libshell.so` `ShellImpl::SelectUsrSyllableFilter` **0x19ff14**：0x19ff50调`InputMode::GetImeData`，0x19ff58调`ImeData::InsertUserSyllableFilterSeq`；特定模式分支0x19ff90调用`ConvertUtils::UpdatePinyin9LLMComp`。可确定它保存音节过滤序列，不能据函数名推断完整LLM计算细节。
- 同库导出 `ImeData::GetUserSyllableFilterSeq` **0x1865f4**、`PopUserSyllableFilterSeq` **0x186f34**、`InsertUserSyllableFilterSeq` **0x187210**、`ShellImpl::GetShowSyllableFilterList` **0x1a00bc**、`ConvertUtils::GetShowSyllableFilterList` **0x161c90**。
- `libkeyboard.so` 导出 `Pinyin9Layout::UpdateFilter(bool)` **0x2f14c0** 和 `OnUserSyllableFilterSelected` **0x2f1da8**。

已保存两段最短反汇编证据。下一轮如需继续，可静态追踪GetShowSyllableFilterList的数据来源、Pop调用者、vtable与候选快照构建；无需进程注入。现有证据已经足以说明其架构有“过滤序列”，仍不足以复刻切分、搜索、排序与撤销算法。业务可独立实现上述经真实验证的Rime路线，不应依赖复制对方native实现。

## 六、可执行实施次序与最小验收

1. **先做状态协议**：JNI完整导出raw/preedit/comment/preview/selection/menu/caret，UTF-8偏移转UTF-16，定义明确提交结果；保留现有候选/提交能力。
2. **构建期添加混合prism与同版本音节索引**：public/private学习模式均覆盖；在正式资源中变更version以触发部署；旧纯数字方案可保留作为回退。不要在手机IME进程编译词典。
3. **建立独立九宫格状态所有者**：原始数字、音节约束、已选片段、修订号与schema版本；动作按顺序进入同一Rime session；候选点击携带生成它的修订号，过期点击拒绝。
4. **再做UI**：拼音解释/待选尾部/音节轨道/汉字候选各自消费明确字段；comment缺失时用raw/preedit诚实回退，不造音节。宿主兼容策略单独验收。
5. **最小原型验收**：64426→选ni→结果不再含mi首音节→撤销恢复→选mi→继续键入→删除跨约束边界；ni'hao选“你”后替换尾部音节保持“你”→选“好”仅提交一次；分页、过期点击、空候选、模式切换、私密不学习与原全拼回归。

当前明确阻塞不在 Rime 是否支持，而在尚未实现和验收的状态层、音节索引生成/分段策略与 UI。资源占用仅比较了prism文件大小；键延迟、长串候选性能、内存峰值与Android完整输入体验仍PENDING。未来三年保留公共C API、schema与独立状态模型可减少对Rime内部C++ ABI依赖；若需要超过公共API的分段语义，再做小型专用扩展，不能一开始引入无法维护的私有结构强转。

## 七、同日首音节实现增量（主调度授权后）

上述“尚未接入”的分析阶段之后，主调度授权了限定文件实现。现已落地最小首音节消歧，Service由主调度独立集成：

- `ChineseInputEngine.Snapshot`新增 `preedit/cursor/selectionStart/selectionEnd/candidateComments/syllables/selectedSyllable`，保留旧构造器；选择音节返回boolean，空字符串撤销约束。
- `T9Disambiguation`保存用户原始数字和首音节约束，列表来自419条实际词典音节；只提供匹配数字前缀的完整音节。动态规划和完整句子侧栏排序仍未实施，不将它们冒称已完成。
- `RimeChineseInputEngine`使用公共 `set_input`，选过部分汉字后禁用消歧，保留Rime内部已选片段；重输/提交后清除限制。`KeyboardController.displayPreedit`只供面板显示真实comment或preedit，原宿主兼容API保持原语义。
- JNI同时导出候选文字/comment，UTF-8字节偏移转UTF-16；仅重编自有rimebridge.so，未覆盖其他成功库。
- `scripts/rime/update-t9-data.py`局部构建t9/private 0.8.2，真实部署耗时0.67秒，未生成新的完整词典table；419条音节资源。资源version=`4d1e62e70ddf80592bca7fa2bfd1e1709e99a5ed8bd21dcedbbeac6a40ca9368`。
- 原资产备份：`native-deps/t9-update-20261015-215901/backup/`；原JNI备份在 `native-deps/feasibility-probe/*-original-rimebridge.so`。未改动源字典和原全拼prism。

验证：独立 `tests/T9DisambiguationTest.java`通过约束/撤销/跨边界删除/锁定已选片段/旧Snapshot兼容/面板解释；`tests/test_rime_packaging.py`更新旧xlit专属断言为真实mixed derive规范，全部7项通过，依赖闭包/16KiB对齐/隐私与运行时不编译词典检查仍保留。

进一步运行了**生产Java Engine → 生产JNI → 真实librime和正式资源**：仅用文件实现的Android Context/AssetManager stub在Termux JVM运行，无Android应用操作。默认和private方案、mi/ni约束、撤销、非法音节、最终commit、部分选“你”保持、UTF16偏移、全拼回归均成功，见 [host-result.log](t9-feasibility-evidence/host-result.log)。该结果验证真实JNI链和译码，不能代替Android输入法UI/宿主端到端验收。

复核主步骤：

```sh
python scripts/rime/update-t9-data.py
# 自有JNI按 scripts/rime/build-native.sh 的clang参数编译；不必重拷整个库闭包。
javac -d /tmp/t9-test android/app/src/main/java/io/github/amxooo/voiceinput2pc/ChineseInputEngine.java android/app/src/main/java/io/github/amxooo/voiceinput2pc/T9Disambiguation.java android/app/src/main/java/io/github/amxooo/voiceinput2pc/KeyboardController.java tests/T9DisambiguationTest.java
java -cp /tmp/t9-test io.github.amxooo.voiceinput2pc.T9DisambiguationTest
python -m unittest discover -s tests -p test_rime_packaging.py
```

命令以 `voiceinput2pc-v08/` 为cwd。前两条修改派生资源，仅在需要重建时执行；已经通过的本轮验证无需重复。后续全量词典更新后需重新执行局部脚本以同步音节索引。

## 八、逐段实现已取代首音节限制

本轮后续已完成多段选择、按段改选/撤销、删除回退，以及部分选字后保留Rime选字继续约束尾部。详见 [逐段实现与真实JNI证据](T9_MULTISEGMENT_IMPLEMENTATION.md)。此前第七节“部分选字后禁用消歧”属于已被取代的中间实现，不是当前结论。
