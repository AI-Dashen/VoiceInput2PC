# 万能输入法 v0.8｜实施架构

日期：2026-10-13。状态：设计候选，等待效果图确认。

```text
Android APK
├── Setup / Workspace Activity
│   ├── 启用输入法、模型状态与隐私设置
│   ├── LongTextEditor
│   └── WindowsTarget / v0.7 Relay
├── UniversalInputMethodService
│   ├── KeyboardView (26-key / T9 / symbols)
│   ├── VoicePanel (idle/listening/recognizing/preview)
│   └── InputConnectionTarget
└── Shared Core
    ├── InputSessionController
    ├── KeyboardEngine -> RimeEngineAdapter
    ├── SpeechEngine -> SenseVoiceAdapter
    ├── PersonalLexicon
    ├── TargetRouter
    ├── ModelLifecycle
    └── PrivacyPolicy
```

## 自有 Contract

```kotlin
interface TextEngine {
    fun openSession(config: TextSessionConfig): TextSession
}

interface SpeechProvider {
    val mode: SpeechMode // LOCAL / AUTO
    suspend fun warmUp(): WarmUpResult
    suspend fun recognize(audio: AudioSource, generation: Long): SpeechResult
    suspend fun cancel(generation: Long)
}

interface InputTarget {
    val targetId: String
    suspend fun preview(event: CompositionEvent): TargetResult
    suspend fun commit(event: StableTextEvent): TargetResult
    suspend fun undo(receipt: CommitReceipt): TargetResult
}
```

Core 不 import 豆包、BiBi 或 Trime 的业务包。Rime Ice 以有版本和许可记录的 scheme/resource layer 接入；SenseVoice 通过可替换 Provider 接入；Windows Relay 只实现 `InputTarget`。

## 进程策略

第一个 Probe 保持 Activity 与 IME 同一应用进程，先验证单份 native 模型、生命周期和实际 PSS。稳定性证据若显示 Activity 会带崩 IME，再将设置/工作区移到 `:ui` 进程，并通过明确的 IPC 共享轻量状态。无论是否分进程，同 APK 都共享 UID 和权限。

## 单写者与幂等

所有 commit 经 `InputCommitQueue` 串行化。稳定事件包含：

- `eventId`
- `sessionId`
- `generation`
- `targetId`
- `revision`
- `textHash`

目标返回 `CommitReceipt`。同一 `eventId + targetId + revision` 只能成功一次。IME target 与 Windows target 互斥，不能在同一稳定事件上双写。

## 个人语言画像

`LexiconEntry(term, pinyin, weight, source, updatedAt)` 是单一真源：

- 生成 Rime 用户词典增量；
- 为 ASR final 提供受阈值约束的后处理候选；
- 只学习用户确认后的改动；
- 密码、隐身和禁止个性化字段不读写；
- 支持导出、清除和禁用。

SenseVoice 当前不承诺原生热词偏置，因此 ASR 侧只能称“后处理纠错”，不能称模型已学习。

## Probe 顺序

### Probe A：IME + AudioRecord

用户主动点击录音；键盘隐藏、取消、来电、焦点改变立即停录。验证旧 generation 不回填。

### Probe B：Local ASR

固定 SenseVoice INT8，测冷/热加载、PSS、final 延迟、取消、数字和中文标点。断网运行并验证零云回退。

### Probe C：Rime

最小全拼 schema 打通 composing/candidate/commit；1000 次焦点切换零误提交。随后才接 Rime Ice 资源和九宫格。

## 回退

- v0.7 保持原分支与可安装包。
- v0.8 使用独立 debug applicationId，可与 v0.7 并存。
- 每个 native 组件固定版本/hash；失败时可回到上一可执行构建。
