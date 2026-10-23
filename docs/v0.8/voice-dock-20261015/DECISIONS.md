# 语音优先侧边入口：实现与验收约定

日期：2026-10-15。用户已授权开发；保留现有未提交工作。

## 职责与真实评审

- Codex：代码定位、实现整合、验证、交付。协作执行核心IME和独立测试。
- Jev：真实jev-1.13.0风险分类成功，详见jev-review.json。
- Claude：实际bin/claude-current 2.1.296，请求claude-opus-5-5；返回成功，未暴露后端模型ID。负责状态、异步写入、远端回执审查。详见claude-review.json（含误报纠正）。
- Gemini：Chromium/CDP官网，发送前核对账号cooocvc@gmail.com；网页显示Gemini Argon，底层ID未知。Android窗口及交互建议成功，见gemini-review.json。SVG原始稿保留；草图中的示例设备和统计不得成为生产数据。

## 产品决定

1. 一级输入方式只有语音和26键。数字为键盘固定行；符号为键盘内层；旧T9引擎不暴力删除，UI不提供T9入口。
2. 本机文字只编辑宿主输入框。默认不自动录音，点语音才开始；识别final直接写入，不再要求上屏。
3. 语音态使用输入法自有窗口的侧边控件，键盘态使用底部面板。优先通过标准Insets触摸region实现，不申请悬浮权限，不另建保活服务。
4. 目标键是一级入口：本机/已保存设备。功能键是二级入口：记录、统计、设置、其他输入法。配对管理复用现有实现。
5. 远端编辑不可借用宿主输入框；保留明确设备标签与精简草稿。只能显式发送，ACK才确认送达；结果未知复用同一请求ID和正文。
6. 不保证手机本地测试等于电脑实时跨设备成功，不在无Probe时显示在线。

## 操作流程

| 起点 | 动作 | 处理 | 下一状态/可见反馈 |
|---|---|---|---|
| 普通宿主输入框 | 获得输入焦点 | 创建新绑定，会话隔离 | 侧边入口，不录音 |
| 侧边待机 | 点语音 | 收音；绑定当前字段和输出目标 | 停止按钮与收音状态 |
| 收音中 | partial | 仅当前有效绑定可更新宿主composing | 宿主输入框出现临时文本 |
| 收音中 | 静音6秒/点停止 | 完成本段识别 | final确定文字，恢复待机 |
| 待机有文字 | 再点语音 | 新generation，从当前光标继续 | 追加而不重写前段 |
| 任意识别 | 切字段/切App | 取消旧识别并使回调失效 | 不向新字段写入旧内容 |
| 识别中 | 用户移动光标 | 保留已显示内容，终止旧事务 | 用户可在新光标启动下一段 |
| 待机/收音 | 点键盘 | 结算当前可见文字；停止旧识别 | 26键展开，dock隐藏 |
| 键盘 | 点语音 | 结束拼音组字，保留既有文字 | dock待机，不自动录音 |
| 键盘 | 返回/收起 | 收起视图 | 不自动录音、不自动发送 |
| 本机 | 点目标选择电脑 | 结算本机文字，选择确切设备 | 独立电脑草稿，不触碰宿主 |
| 电脑草稿 | 点发送 | 持久化请求，再等待回执 | 已送达/拒绝/结果未知 |
| 电脑结果未知 | 重发 | 相同编号和相同正文 | 接收端去重 |
| 有未发远端文字 | 选择另一台电脑 | 不静默重定向原草稿 | 明确阻止或保留原设备归属 |
| 任意输入 | 点功能 | 暂停识别，进入菜单 | 记录/统计/设置/切输入法 |
| 密码/敏感字段 | 输入 | 禁用语音、历史、远端 | 安全键盘 |
| 宿主拒绝写入 | final提交失败 | 不记录假成功、不丢弃结果 | 当前字段可重试，字段变化失效 |

## 对外部建议的核验裁决

Gemini的“InputConnection始终有效”不成立：所有调用必须处理null、false和绑定变化。手势排除不是“每天配额”；本轮不依靠改系统手势或反射。contentInsets设置不保证所有ROM完全隐藏系统导航栏，必须真机实测。SVG里22dp小按钮仅为概念，不作为实际可点击尺寸。

Claude对于onDestroy缺super和RemoteTextSender不回主线程为误报，已有实现满足；重试ID复用也已存在。仅采纳经代码核对的事务建议。final-only是兼容降级策略，不能拿它冒称实时partial已验证。

## 验收范围

- JVM：会话/文字事务、远端重试、历史提交等确定逻辑。
- Android instrumentation：真实Android控件与连接fixture；不能冒称真实系统输入法绑定。
- 真机系统IME：dock透传、宿主高度、26键切换、旋转/返回、字段切换、麦克风。
- 电脑实际ACK：需已有目标电脑真实在线；否则PENDING。

未实际执行的项保持PENDING。

## 官方核对来源

- https://developer.android.com/reference/android/inputmethodservice/InputMethodService.Insets （content/visible insets与touchableRegion定义）
- https://developer.android.com/reference/android/inputmethodservice/InputMethodService （窗口生命周期）
- https://developer.android.com/reference/android/view/inputmethod/InputConnection （composing/commit的返回值与绑定限制）

## 外部专家实际结果

Claude咨询耗时81.409秒，success；Jev success；Gemini网页返回complete=true。三者均为建议或分类，不替代真实设备验收。
