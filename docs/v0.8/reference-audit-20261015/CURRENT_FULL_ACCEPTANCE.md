# 当前完整输入流程验收矩阵

2026-10-15。独立只读Review当前Service、InputSession、Recognizer、Recorder与既有测试；仅新增本文件。此表描述测试覆盖与代码路径，不宣称本轮执行过测试或完成真机VERIFIED。代码仍在并行修改，主调度应以最后版本重新核对下列定位。

本轮要求是一次完成已认可流程、统一构建验收；下列顺序是测试依赖顺序，不是分阶段安装。保留已有语音/Rime/跨端协议，不以假引擎通过代替目标手机成功。

## 当前阻塞/明确缺口

1. **长按删除未接入**：UniversalInputMethodService.java:160只有railButton单击backspace；该文件没有删除键的长按/触摸重复处理。当前只能单删；若用户已认可长按连续删除，尚未完成。实现必须维持“先拼音→草稿→所选目标”作用域，草稿删空不得穿透宿主；松手/CANCEL/隐藏/销毁必须停重复回调。
2. **录音中临时隐藏丢部分文字**：同文件:215-223对非PREVIEW直接session.cancel；speech.cancel先执行。LISTENING/RECOGNIZING已有partial或续说prefix时，临时隐藏会丢草稿。需要使用本轮已有interruptToDraft保留可见文字并过期旧回调，或明确产品拒绝该隐藏恢复行为，不能把F16标为全通过。
3. **实际识别2分钟强停与10分钟探测器不一致**：OfflineSpeechRecognizer.java:81-83到120秒就finish；SpeechEndpointDetector.java:17及SpeechEndpointDetectorTest.enforcesLongSafetyCeiling使用600000ms。六秒静音本身有实际调用链，但长句并不是仅六秒静音/手动停止/十分钟上限。应统一限制或明确文案/验收约束，不能用探测器单测证明完整录音十分钟。

其余明确测试缺口：成功上屏后Service清预览与重复点击、带真实InputConnection的宿主拒绝/抛异常、运行识别的Service回调竞态、功能页返回、PREVIEW远端编辑隔离和完整终端交互，都未在现有Android编辑回归测试里完整覆盖。

## 证据与运行方式

源码目录 `android/app/src/main/java/io/github/amxooo/voiceinput2pc/`；Android测试目录 `android/app/src/androidTest/java/io/github/amxooo/voiceinput2pc/`。下表 A=ImeEditingRegressionTest，S=InputSessionTest，K=KeyboardControllerTest，O=OutputTargetControllerTest，P=ImePanelControllerTest，E=SpeechEndpointDetectorTest，C=InputCommitCoordinatorTest。JVM测试位于tests/。

可执行纯逻辑入口（项目根目录）：

```sh
python3 -m unittest tests.test_v08_input_session tests.test_v08_keyboard
```

这会运行S/K/O/P/E/VoiceDraft等，并含源码字符串检查；字符串存在不能证明UI路径可达。Android测试编译入口：

```sh
gradle -p android :app:compileDebugAndroidTestJavaWithJavac --offline --no-daemon
```

实际安装/运行Android测试与系统输入法选择交由主调度在当前授权通道执行；本审阅不发设备命令。统一跑A全类并保留instrumentation结果，不能只选择一个通过用例。独立设备音频入口LocalInputDeviceTest是既有识别链验证工具；它不能替代IME Service的草稿/模式/编辑集成验证。

## F01—F18可执行矩阵

每行先准备全新测试字段；声称“不提交”必须用计数InputConnection或可检查宿主文本证明零次commit，并同时核对远端pending不变。

| 编号 | 执行动作与明确期望 | 已有覆盖 | 缺失/当前判断 |
| --- | --- | --- | --- |
| F01 | 普通文本/数字字段打开IME→默认语音；点语音→partial→final，仅PREVIEW，无自动上屏 | shouldDefaultToVoice():1090返回true；S状态；LocalInputDeviceTest识别链 | 缺默认语音Service参数化测试和final宿主零写入断言；PENDING |
| F02 | LISTENING有partial→分别切26/T9/符号/数字，音频停、草稿保留、键盘可见 | switchMode→interruptToDraft；S.modeSwitchInvalidatesLateCallbacks；A真实视图重建 | A未注入LISTENING与speech假依赖，缺四布局全部执行；部分覆盖 |
| F03 | RECOGNIZING切布局→编辑→旧partial/final/error顺序到达，草稿不变 | generation递增；onResult/onError检查busy；S旧generation断言 | 缺Service main.post实际回调组合测试；部分覆盖 |
| F04 | 忙碌无文字→切键盘，IDLE、无幽灵稿，能普通输入 | interruptToDraft空值走IDLE | 缺空partial的Service切换用例；PENDING |
| F05 | 草稿→26/T9→拼音/音节约束→选候选/空格，候选插草稿；宿主零composing | syncComposition PREVIEW提前return；A.testRealPackagedRimeSyllableConstraint；K与RimeChineseInputEngineTest | 引擎测试不等于Service草稿集成；缺宿主setComposing计数+候选插草稿组合；部分覆盖 |
| F06 | 草稿选区分别插英文/SHIFT/数字/符号/空格；render后保留编辑 | A.testSymbolsEditDraftWithoutWritingHost、testKeyboardDraftSurvivesRealViewRecreation；type空格已接space | 数字/全部符号/原生EditText粘贴的Service断言不全；部分覆盖 |
| F07 | 反向选区删除；emoji前后移动/删除；长按删除后松手/CANCEL | A.testEmptyDraftAndReverseSelectionStayEdited、testCursorMovesByCodePointAndFailedCommitRetainsDraft | 长按未实现；这是当前明确缺口 |
| F08 | 草稿删空→重复删除→render/重建/切模式→继续语音，空值不复活、不穿透 | A空草稿与重建；visibleVoiceDraft稳定PREVIEW不fallback旧副本 | 缺空稿续说与远端/宿主零删除断言；部分覆盖 |
| F09 | 草稿带未决拼音→删除/上屏/切模式：删除先拼音，上屏/切换明确阻止 | backspace PREVIEW优先rawInput；confirmVoice与switchMode检查rawInput | 缺真实rawInput假引擎Service用例；普通回车已可选首候选，PREVIEW上屏仍要求先确认拼音；PENDING |
| F10 | 改草稿→继续说→final/空结果/error；继续时保留prefix；长按语音取消清稿且旧回调失效 | S.previewCanResumeWithoutCommitting、failedContinuationRestoresPriorDraft；VoiceDraftTest；cancelVoice入口在语音长按 | 缺Service续说与取消全链测试；取消按钮voiceActions整体GONE，普通可见取消入口实际是长按语音；部分覆盖 |
| F11 | 带真实本机InputConnection上屏成功→草稿视图为空→再点上屏，只有一次写入 | C成功/相同文本独立事件；A.testRealEditorToSqliteAndReadback验证Coordinator+EditText+SQLite | A没有成功的Service.confirmVoice路径；清视图代码已补，但须新增可达测试；PENDING |
| F12 | 无连接/false/throw→Service上屏，草稿与选区保留→恢复后显式重试一次 | A无连接失败保稿；C拒绝/抛异常/历史失败不重放 | 缺Service注入false/throw/恢复连接用例；部分覆盖 |
| F13 | 远端已有pending→语音草稿→左右/选区删除/候选：pending不变；上屏一次合入 | moveCursor与backspace PREVIEW优先；O远端光标/保护前缀；A仅本地fixture | 缺Service远端PREVIEW集成断言；PENDING |
| F14 | 远端上屏拒绝，语音稿保留，零网络调用 | routeStable返回false并不commit；O控制器基础 | 当前commitStable只在非remote时返回false，且routeStable先isRemote判定，正常同线程不易抵达拒绝分支；需说明这是防御路径或可注入目的地模拟，不能声称已测实际拒绝；PENDING |
| F15 | 远端上屏只入pending→显式发送→拒绝/未知/成功；未知重用同ID | O请求ID/保护前缀/回执；LiveRelayTest可做已授权真实链 | 缺Service从语音稿到待发到回执的组合测试；实际电脑插入仍需单独真实证据；部分覆盖 |
| F16 | 同字段隐藏/重建/restart/功能页往返，恢复稿/布局/选区且零提交 | A真实onCreateInputView重建；P.primarySecondaryReturnPreservesInputSnapshot/staleEditorNeverRestores；onStartInput同字段PREVIEW保留 | 运行语音隐藏丢稿，见阻塞2；功能页纯控制器不覆盖Service，缺端到端返回；部分覆盖 |
| F17 | 换字段/应用/密码框/真实finish/destroy→旧回调无效；敏感不语音/历史/跨端/明文恢复 | S跨字段；C敏感不记历史；A.testSensitiveRejectedTextNeverBecomesVisibleDraft；beginEditor清旧preview | 缺Service实际生命周期、隐私flags和所有敏感类型参数化；秘密不应进入测试日志；部分覆盖 |
| F18 | 无语音稿普通26/T9/英文/数字；Enter选首词/执行宿主action/换行；Termux删字；远端普通编辑 | K、EditorCompatibility断言、RimeChineseInputEngineTest；rawEditor KEYCODE_DEL；type空格接space | 缺Service Enter各分支、终端真实PTY、长按删除、实体窗口确认；不可用键事件发送成功代替终端文字实际改变；PENDING |

## 六秒静音的独立必测

1. 先有可识别说话，再静音5.5秒应继续；重新说话应重置静音计数；连续静音达到6秒才结束。E已有纯PCM能量测试，SafeAudioRecorder:90-98调用detector并回调onEndpoint，OfflineSpeechRecognizer:86调用finish。
2. 从未说话8秒触发NO_SPEECH_TIMEOUT，与“说完后6秒”分开解释。
3. 连续有声超过120秒目前会被Recognizer提前结束，必须处理前述限制矛盾。
4. 手动“停止”与自动endpoint均须生成可编辑PREVIEW，不能自动上屏。真实安静/噪声环境至少各一次，计时从最后有效语音起；能量单测不是实机VAD效果验证。

## 一次完成的验收依赖与交付判据

先补明确缺口和Service可达测试，再执行现有JVM+Android全集，再统一构建候选包；同一最终构建上进行F01-F18相关真机验收与终端/跨端验证。无需为了每个小修复分别安装。保留每项的构建标识、实际测试结果与证据位置；未完成项写PENDING，失败写FAILED，不把测试文件存在或编译通过记为VERIFIED。

本文件写入时，没有执行新增测试、安装APK或运行App，也没有修改业务文件。此矩阵是当前版本的验收清单，不是已完成报告。

## 合并实现后的验证更新

上表为最终实现前的差距清单，不再代表0.8.2仍有这些缺口。长按删除、临时隐藏保留partial、120秒统一、成功/失败上屏、远端草稿隔离、功能返回、真实Rime草稿混输均已补实现与用例。最终实际结果：主机26项PASS，Android24项PASS。具体测试范围与仍需人工/外设验证边界见CONSOLIDATED_ACCEPTANCE.md及CONSOLIDATED_EVIDENCE.json；不得把表中先前PENDING全部直接换成VERIFIED。
