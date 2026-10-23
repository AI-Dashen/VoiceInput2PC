# v0.8.8 半透明竖排入口实际交付

日期2026-10-16，versionCode18，versionName0.8.8-vertical-glass。

## 已实施
- 中间麦克风30dp、上删除28dp、下方宿主动作28dp；所有触摸slot48dp，三键竖排144dp，不再横向按钮条。
- 浅青底60%不透明（40%透明）、细白边、扁平墨蓝线条；移除厚白圆和阴影，收音红、识别琥珀保留。
- 图标拖动记住归一化位置；超过touchSlop取消点击/长按，抬手不录音/不发送；长按直接更多。
- 编辑快捷键忙时禁用，删除滑出/CANCEL停止连删，展开收起麦克风中心不跳；更多保留旧六个功能并加入键盘/收起。
- 菜单/远端预览跟随入口避让，不申请系统overlay权限、不修改系统设置、不修改语音模型/键盘引擎。

## 已知设计缺口（用户已指出）
**当前只在输入法底部窗口内移动，本机240dp高、远端272dp，不是全屏自由移动。**
这是本实现窗口/布局的限制，不能称手机能力不支持。上一条“全屏必须新悬浮权限”的解释过于绝对，已向用户纠正。下一步应先研究扩大现有透明输入法窗口+限定touchableRegion；是否能在Android16/本机稳定工作尚未真机验证，不能声称通过。独立系统overlay只是备选路线，涉及新权限时遵守系统确认要求。

官方依据（2026-10-16核查）：
- https://developer.android.com/reference/android/inputmethodservice/InputMethodService.Insets ：TOUCHABLE_INSETS_REGION与contentTopInsets。
- https://developer.android.com/reference/android/inputmethodservice/InputMethodService ：新版目标SDK不能盲目override onConfigureWindow；不能套旧Android代码。

## 验证
- host keyboard回归4/4 PASS，1.948s。
- 几何JVM8组PASS（含尺寸边界sweep）；CompactImeUiPolicyTest通过。
- Gradle debug+AndroidTest最终BUILD SUCCESSFUL15秒（build-acceptance.log）。
- 更新安装应用及测试APK成功，未清数据（install-final.log/install-test-final.log）。
- 最终手机Android fixture25/25 PASS，4.397s：10项竖排手势/动作/几何，10项既有语音编辑事务，5项布局/图标菜单。使用真实View/EditText/InputConnection，但ASR为替身，不冒称真人麦克风验收。
- 首轮真实系统绑定窗口1/1 PASS，5.733s：默认小图标、长按更多、键盘切换，截图real-window/default.png、more.png。这是此轮第一个成功安装版本；随后新增标准无障碍展开/收起和生命周期清理，通过最终fixture；未再次进行前台bound测试，因为截图显示用户导航正在运行。
- 增补的真实三键展开bound用例已编译，尚未执行；手机25项fixture实际验证竖排/动作。

## 失败与修复
初版Java声明前向引用编译失败，已修正。初轮Android fixture21/23PASS，两项使用未attach视图getLocationInWindow导致0坐标，改为真实父链left/top/translation/scroll累加，并用嵌套偏移测试验证辅助函数，不降低原断言；最终25/25PASS。历史失败日志保留，不计成功。

## 分工
Codex负责生产Service/外观/接入/构建/安装/真机验收；几何子任务编写纯Java几何策略与边界测试；测试子任务编写/修订Android fixture和bound测试。Claude与指定账号Gemini官网实际评审记录同目录；Gemini模型实际为Flash-Lite，未冒称其他模型。

## 共享成果
/storage/emulated/0/Pictures/CodexCamera/MobileAI-Exports/万能输入法-v0.8.8-半透明竖排.apk

未commit/push。没有把当前底部可移动版本说成全屏自由移动版本。
