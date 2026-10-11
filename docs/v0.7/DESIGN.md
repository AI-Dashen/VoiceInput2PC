# 万能输入法 v0.7 实施契约

基线 v0.6.1 / 6fb92cf；原任务 8f54db34-b8cd-43ee-99dd-5a677d43f976。

## 范围与状态机
手机继续使用第三方IME，App不是InputMethodService。状态：PAUSED → STARTING → ACTIVE → SENDING → ACTIVE；网络/回执不确定 → UNCERTAIN；已发送前缀修订 → EDIT_CONFLICT。后台/输入连接中断 → PAUSED。存储失败不发送。
持续追加不需“新一段”。已经确认输入到电脑的前缀改动，仅记录修订，不发送退格，不自动重放旧正文；核对后保留后续文字可恢复。

## 当前焦点
用户确认的是持续输入到Windows当前光标，不是固定初始窗口。每条已确认文本注入之前，由Windows接收端读取当前焦点，SendInput之前再检查同一焦点；注入过程中改变焦点/部分成功时停止后续自动输入、不自动重试。保留pinned模式用于旧行为测试/显式兼容。120秒无文字空闲暂停保留，有正常输入持续续期。

## 协议
现有protocol=2保留；新接收端session响应声明next_seq=1。手机新段包含{id,text,session,seq}，单飞队列。已保存同一消息重试保持id/session/seq/text不变。旧接收端无next_seq则继续旧协议。接收端持久化session摘要+seq→message_id/text_hash，重复相同消息回原回执；同序号异编号或异文字拒绝；跳号拒绝，不注入、不静默跳洞。禁止将session原值写入历史库。

## 历史
SQLite保存，不自动删除历史。分页仅限制单次展示，不限制保留总量。手机保存稳定文本修订、时间、来源、逻辑会话、修订号、目标设备非秘密标识、发送状态、message_id、seq。临时composing不作为确认文本导出；已有待确认请求继续使用耐久outbox。查询按关键词与时间范围；TXT/MD/JSON/CSV流式导出，Android用SAF选目的地，不新增存储权限。

## 专家裁决
Jev、Claude、Antigravity已实际调用，原始返回另存。Claude的跨消息固定焦点建议与用户确认的当前焦点要求冲突，采用每次注入焦点一致性校验。Antigravity把App视为InputMethodService不符合本项目事实，不能照搬其服务生命周期建议。其远程Backspace建议不采纳：无法证明目标已有文本归属，继续只输入普通文字。采纳composition/batch边界、稳定历史、SAF导出、同编号选路去重等建议。

## G0已知缺项
新工作树不含tailcatbridge.aar，构建需复用既有可信产物或定位官方CI产物。Windows打包和焦点注入必须真实Windows验证；手机主机测试不能替代。

## 验收
单元：同编号重放仅一次、乱序拒绝、同序号异文拒绝、旧协议兼容、回执丢失/DB失败不重放；历史超过100条全部保留及4格式完整导出。
真机PENDING：IME临时转写/修订/删除/后台与锁屏；LAN→跨网→LAN代理选路；Windows窗口切换/焦点/UAC失败；APK启动/历史导出。
