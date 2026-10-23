# 豆包输入法分工分析：第一轮真实结论

日期：2026-10-15。范围：用户两张截图、已有豆包静态反编译、固定版本Trime源码、自有v0.8代码对照及Claude/Gemini独立咨询。此轮不是新包验收，不修改业务代码。

## 分工

Codex统筹事实核查；Jev任务分类；Claude编辑状态契约；Gemini官网操作流程评审；三条并行源码任务分别研究九宫格、删除编辑、模式生命周期。详细返回及不同意见见EXPERT_REVIEW.md。

## 已查清的工程差距

| 用户问题 | 已找到的机制与证据 | 自有实现差距 |
|---|---|---|
| 九宫格难用 | 豆包独立保存syllableFilters/selectedSyllable，选音节onMoreCandidateSelectSyllable与选字onMoreCandidateCommit分离 | 我们虽有Rime数字译码，但JNI接口只传rawInput和字符串候选，缺少可读拼音、分段、音节动作和选择状态（见rimebridge.c:52–66） |
| 删除不可靠 | 豆包backspaceClickEvent先判断选区，普通删除用sendDownUpKeyEvents(67)，SelectionTracker记录内部删除与选区回调 | 我们的删除分散在Service多个分支，未形成统一编辑命令及回调跟踪契约 |
| 切换操作难用 | 豆包不同面板有不同切换与停录路径，部分进入native，不可将一个分支概括所有面板 | 我们switchMode当前自动commitPreviewBeforeModeSwitch，与已经确认的保留草稿流程冲突 |
| 输入法切出入口消失 | 我们HomeActivity原selected条件隐藏chooseAction，前一轮已改为保留 | 新包虽构建成功但仍待安装后真实验收，不能算整个输入法已修复 |
| 终端输入与普通编辑器不同 | 固定版本Trime明确处理TYPE_NULL回车和组字/提交，并维护composingText | 当前针对Termux的兼容修复仍要用真实操作验证；不能伪称豆包存在同样Termux分支 |

## 建议的完整修改顺序（尚未实施）

1. 冻结已经认可的语音识别引擎和当前视觉方向。
2. 扩展中文引擎快照：原始键、可读拼音、分段、候选对象、可选音节与选中态、修订号；先核查Rime实际可提供字段，不能虚构。
3. 统一编辑命令与结果：输入、删除、选音节、选词、取消、重输、确认、切模式；提交要有成功/失败反馈，不能清掉草稿后忽略宿主写入失败。
4. 定义删除作用域与回调归属：当前编辑焦点、组字、音节约束、语音草稿、宿主选区和远端草稿，任一次动作只由一层消费。
5. 完成音节消歧的独立原型并接入九宫格，再实现手指操作；不从首候选反推拼音。
6. 按语音↔键盘、九宫格↔26键、符号/数字、跨App、终端/普通框/敏感框走完整流程验收。

## 明确不采纳

Gemini提出切键盘自动提交语音草稿，此建议与用户已确认显式上屏规则冲突，不采用。Claude与Gemini的删除优先级建议也不能直接写成豆包真实行为，必须依据编辑焦点与实际状态确定。

## 当前边界

Java/资源可以证明数据模型、回调和部分上屏路径；native内部数字切分、排序、纠错算法和精确按键时序没有完整还原。此报告不声称已彻底逆向全部豆包，不宣称新包已DEVICE_VERIFIED。

## 阅读入口

- T9_ANALYSIS.md：九宫格证据与代码路径
- EDITOR_ANALYSIS.md：删除、光标、选区和编辑跟踪
- MODE_ANALYSIS.md：语音/键盘切换、选择器和生命周期
- EXPERT_REVIEW.md：外部专家实际返回与裁决
- GEMINI_REVIEW.json：本轮Gemini原始评审
- sources.json：Trime固定提交与来源路径
