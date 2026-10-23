# 系统输入法没有历史：根因与修复契约

日期：2026-10-15。仅静态读取 v0.8 当前工程；未读真实用户数据库、未运行或切换输入法、未 Root、未动态注入。以下行号来自修复前源码。源码断点已确认；手机已安装包是否完全同源、现有数据库内容及真机结果尚未核验。

## 核心根因

`UniversalInputMethodService.routeStable`（562–571）本机分支只有 `getCurrentInputConnection → commitText(text,1)`，**没有写历史**，也没有检查 commitText 返回值。IME 内唯一 `history.record` 在 `recordRemote`（630–632）；它仅由发送到电脑的回执、拒绝、结果未知分支调用（609–625）。所以正常使用本机系统输入法打字或确认语音，现有逻辑根本不会产生本地历史行。这个缺失足以解释用户反馈，无须先假设数据库坏了。

来源目录均为 `android/app/src/main/java/io/github/amxooo/voiceinput2pc/`：

| 路径 | 当前行为 | 证据 |
| --- | --- | --- |
| 中文候选 | selectCandidate → routeStable | `UniversalInputMethodService.java:327–334` |
| 英文/数字/符号稳定文本 | commitLiteral → routeStable | `:345–349` |
| 语音最终确认 | session.commit → routeStable | `:491–500` |
| 行内拼音 | syncComposition → setComposingText，不是稳定上屏 | `:311–324` |
| 本机 routeStable | commitText，无历史写入 | `:562–571` |
| 电脑草稿 routeStable | targets.commitStable，持久化偏好，无历史写入 | `:564–567,642–651` |
| 电脑发送结果 | INSERTED/SAVED_NOT_INSERTED/REJECTED/DELIVERY_UNKNOWN → recordRemote | `:609–632` |
| 本机 Enter | editorAction 或直接 commitText("\n",1)，绕过 routeStable | `:396–411` |
| 本机选区删除 | commitText("",1)，不应误作为新稳定输入 | `:383–388` |

## App 与 IME 并非独立历史库

`InputHistoryStore.java:13` 统一打开 `input-history.db`。IME `:62` 与 MainActivity `:201` 都使用 `new InputHistoryStore(this)`，同一安装包下正常解析到同一私有数据库路径。Manifest 中只有 LocalAsrService 声明独立 `:local_asr` 进程（14行）；IME 服务（16–27行）及 MainActivity（34行）均无 process 属性。两个 SQLiteOpenHelper 对象不表示两份数据库，也不能据此要求导入/同步两个库。

SQLiteHelper 每个对象上的 synchronized 仅限制自身；跨 Helper 并发仍由 SQLite 事务处理。当前 record 在事务中读取同 session 最后一行并插入（24–45行）。本次未证明有锁失败，更未证明有损坏，不应为本次缺历史修改数据库版本或清空数据库。

## 读取与入口链完整

- `InputHistoryStore.search`（47–52行）只按关键词、日期、id 游标查询，**不按来源、状态或设备过滤**，新增本机行自动能够显示。
- `HistoryScreen.java:47–49,169–170,190–212` 默认空关键词、全日期、最大 id 游标；第一页 50 条，额外取一条判定下一页。SQL instr(text,'') 对普通文本匹配，不会把本机记录全部排除。
- `HistoryDateRange.java:14–18` 空日期范围为 0 到 Long.MAX_VALUE，日期采用本地日界线，结束日期包含整天。
- IME `openInputStatistics`（693–697行）发送 EXTRA_OPEN_HISTORY；MainActivity 首次创建（221–223行）和新 Intent（234–240行）都会打开 HistoryScreen。
- HomeActivity（165–166行）也发送同一 Extra。MainActivity `showHistory`（100–113行）复用同一 history helper。
- MainActivity 具有独立保存链：`save`（641–649行）记录稳定草稿及修订；电脑回执（795–797行）记录插入结果；电脑剪贴板接收（903–907行）更换 session/source 后调用 save。这解释为何“App工作区有历史”不能证明“系统输入法已接历史”。

限制：MainActivity.showHistory 在 busy 时拒绝打开；有 tracker 时先 save，可能增加一条工作区修订（102行）。HistoryScreen 进入时刷新，显示期间无数据库订阅；恢复旧筛选也可能暂时看不到新行。这些是次要体验问题，不是本机写入完全缺失的主因。

## 敏感字段与历史写入必须分开守护

`UniversalInputMethodService.java:170,180,992–996` 根据 inputType 识别数字密码、文本密码、可见密码、Web 密码；敏感字段禁语音与跨设备，切英文模式。普通本机 routeStable 仍需让密码输入成功，因此不能在该函数开头统一 return。正确契约是“本机照常 commit，历史入口排除敏感文本”。

当前 InputHistoryStore 无任何敏感字段参数/判断（24–45行）；未来仅在 UI 禁历史按钮无法保护数据库。必须在提交记录协调层显式执行敏感策略。不能只靠输入法当前状态在异步写盘时再判断，因为焦点可能已变，应捕获提交时字段身份和敏感标志。未知 EditorInfo 时建议按不能确认可记历史处理，但不影响输入本身。`IME_FLAG_NO_PERSONALIZED_LEARNING` 的历史策略目前未定义，应作为本地历史策略明确处理，不能声称已有实现。

## 去重与统计的两个坑

InputHistoryStore（30–37行）仅比较同 logical_session **最后一行**的 text/status/message_id/seq。它为工作区同一快照重复保存去重，但不是通用事件幂等键；source/device 不参与比较。

1. 若新增 IME 本地记录时每次用相同 session、固定状态、空 message_id、seq=0，则连续输入两个相同字符（如“哈哈”或“11”）会误删第二个真实提交。每次真实稳定提交需要独立事件 id；同事件重试复用 id，两个相同输入必须不同 id。
2. 现有去重只看最后一行；跨事件后重放旧事件仍可能重复。严格“重试只记一次”应在写入层检查稳定事件标识，或新增局部唯一索引并进行明确迁移。不要为无实际重试机制的最小补丁随意扩大迁移。

IME 今日统计（825–833行）直接 COUNT(*)、SUM(length(text))，包括工作区草稿修订、失败/未知远端发送以及状态变化的重复快照，且没有今日结束上界。它统计的是“今日保存事件/事件文本长度”，**不是净输入字数**。即使补上本机记录，也不能继续把该数字当精准净输入量。

建议先明确文案为“记录数/记录文字量”，或设计为“本机成功提交统计”和“历史事件数”分开。若做输入量统计，应只汇总定义明确的提交事件（本地接口接受、远端 INSERTED），按事件身份去重；不能直接把已有所有事件都加起来。SQLite length 使用码点尺度，Java String.length 使用 UTF-16，emoji 会不同，应统一且标注统计口径。

## 修复契约（根代理实施）

1. 为本地成功稳定提交接入历史。所有候选、字母、数字、符号、最终语音都通过统一入口；拼音 preedit、语音 partial、尚未确认预览、清组字、取消、删除空字符串不写本机成功输入历史。
2. 取得当前连接后实际调用 commitText，捕获 false/异常/无连接；只有 true 才记为接口接受。不能把 true 命名成“已验证宿主显示”；raw终端也只能按接口接受口径描述。失败返回明确给调用方，不清语音草稿或显示“已输入”。
3. 记录时捕获 editor/session identity、目标类别、敏感标志、提交源、文本与独立 eventId。每个真实提交一次，多次同字不合并；重试同一事件幂等。source 可用 ime_local，device 可用 phone 或本地标识，status 用与接口接受语义一致的 LOCAL_COMMITTED 等值，避免与电脑插入回执混淆。
4. 在单一历史入口阻止密码/未知上下文字段记录。过滤只影响历史，不能阻止密码键入；不能为了记录读取宿主整框内容或其他应用文本。
5. 历史保存失败不能撤销已经完成的宿主提交，也不能提示用户重发该文本。显示“已输入，记录保存失败”并保留非秘密错误类型用于诊断。当前 recordRemote 保存失败设置 message 后立即被回执分支覆盖（612–615、619–620、624–625行），应一并保留错误可见性。
6. Enter 明确区分 editorAction 与换行文本：搜索/发送动作不是输入文本历史；若目标是完整文字历史，实际 commitText 的换行需由同一提交入口记录。删除与移动是编辑动作，不当作新增文字记录。初期历史可定义为“已提交片段”，不要伪称为其他App最终文档或净输入结果。
7. SQL schema v1 足以补 source/status/message_id，不必清库。界面读取共享现有库；改统计口径时保持历史修订可查看/导出。若异步写入，必须保证打开历史前已排队写入可见，并在Service销毁时安全完成/清理；初期小规模同步写入应评估按键卡顿。

薄 EditorAdapter 可返回 `CommitResult { accepted, failureKind, editorIdentity, eventId }`，只管理连接调用、提交时上下文和命令结果；独立 HistoryRecorder 接收此结果执行敏感过滤和持久化。InputSession 继续管理语音/组字状态，避免为一条历史修复重写整个输入法。

## 必要验收

- 单元测试用 fake commit connection 和 fake history sink：成功提交一条；false/异常/null 不产成功历史；同字连续提交两条；同event重试只一条；敏感提交成功但历史零条；composing/partial/cancel 零条。
- 验证四个真实入口：中文选候选、英文数字符号、最终语音、raw-editor 文本；本地未配对时同样能记录，不能依赖设备ID或远端回执。
- 跨对象共享数据库测试：IME helper 写后，用 App helper 默认查询可见；查询关键词/日期、分页及导出包含本机行；旧 v1 数据保留。
- 失败恢复：commit失败保留语音预览；history失败不重复向宿主发送、不显示保存成功；远端历史失败信息不被覆盖。
- 真机验收使用专用测试文字，输入后打开输入记录显示新行；重启界面后仍有记录。密码字段验证只检查记录不存在，日志不能输出测试密码。
- 统计对修订、失败状态、重复回执、emoji、跨日给出明确口径。未实现净输入计量时使用“记录”文案。

本报告仅完成源码根因与契约；未替用户执行历史迁移、读取、恢复或删除。修复前已漏写的本机输入无法由现有数据库凭空还原。
