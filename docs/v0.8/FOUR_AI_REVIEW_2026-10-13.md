# 万能输入法 v0.8｜四方会审记录

日期：2026-10-13。状态：四方均有真实返回；专家意见由 Codex 依据源码、APK 元数据和 Android 约束裁决。

## 分工

- **Codex**：核对现有 v0.7、开源源码、豆包官方 APK 元数据和静态结构；裁决产品范围与实施 Gate。
- **Jev 1.13.0**：快速分类为 informational，Codex 路由；高影响概率 0.28，不建议仅因规划本身要求额外确认。
- **Claude**：审查产品闭环、输入状态机、个人词条、验收与三年风险。
- **Antigravity / Gemini 3.8 Flash Low**：审查 Android 16 IME、麦克风、进程、librime/SenseVoice 与真机指标。

## 共识

1. 不是把 v0.7 放大，而是建立 `InputSession → Engine → Target` 的输入系统。
2. 拼音 preedit、ASR partial 与稳定文本必须分层，稳定事件才能写历史或 Relay。
3. 焦点切换、取消、过期回调与重复回调是 P0 风险，必须先于 UI 丰富度验证。
4. Rime Ice 是中文方案参考，豆包是产品体验参考，BiBi 是离线语音实现参考；本产品拥有自己的 UI 和路由。
5. Local 模式必须通过抓包或网络计数验证零云回退。
6. 标准 Bluetooth HID 没有 Unicode 中文语义，不能作为通用中文输出承诺。
7. 同 APK 多进程共享 UID/权限，多进程只用于稳定性与资源回收。

## 分歧与 Codex 裁决

### 首版是否包含九宫格

Claude建议首版砍掉 T9 以收缩范围；原用户目标明确要求完整中文输入，并特别提出雾凇作为参考。裁决：26 键是 P0；九宫格保留为 P1 且必须真消歧，不作为第一个 Probe 的阻塞项。

### Activity 与 IME 是否分进程

Antigravity建议 `:ui` 与 IME 分进程以隔离崩溃；现计划早期倾向同进程共享模型。裁决：Probe 先同进程减少变量并测真实 PSS/稳定性；只有证据显示 Activity 生命周期或 OOM 会影响 IME 时，再做独立进程与显式 IPC。不能为架构漂亮提前引入跨进程模型复制风险。

### 敏感字段范围

Claude把 `NO_PERSONALIZED_LEARNING` 与密码一起建议全部禁用语音。裁决：密码字段全部禁用；`NO_PERSONALIZED_LEARNING` 至少禁用学习与历史，是否禁语音由字段语义和真机兼容测试决定，避免过度禁止普通隐私输入。

### 性能阈值

专家给出的 300ms 冷启动、ASR TTFT 300ms、RTF 0.2、CER 8% 均是目标，不是当前设备证据。首个 Probe 记录分位数后冻结门槛；不在实测前宣布达标。

## 最终结论

方向通过，状态 `DESIGNED`。进入正式 Android 实现前只剩一个产品 Gate：用户查看并认可 Antigravity 效果图与关键流程。其后按 IME 录音、离线 ASR、librime 候选三个 Probe 顺序实施。
