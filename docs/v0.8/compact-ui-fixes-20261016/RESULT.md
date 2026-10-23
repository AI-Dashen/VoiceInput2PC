# 0.8.6 数字角标、更多与图标可见性修复
2026-10-16；versionCode16 / 0.8.6-compact-ui；自身App与AndroidTest更新安装成功。

## 实际修改
Claude真实编写ImeKeycapView（60.29秒success），主Codex集成首排q-p数字角标1-0，点击字母/长按数字保持原逻辑，未新增数字行。
麦克风保留48dp触摸区、36dp实心状态图标，增加白色底托、深色细描边和软底影，以改善暗/亮背景边缘可见性。尚未测量所有背景的色彩对比度。
修复More在LISTENING/RECOGNIZING期间被toggleFeaturePanel静默拒绝、提示又隐藏的问题：先取消producer并保留已可见文字、更新generation，然后打开真实菜单；不会自动发送。请求重新布局和Insets。
既有四个功能入口保留并验证显示：目标、本机/电脑选择，历史/统计，设置，其他输入法。静态检查及测试未证明此busy路径是用户原始所有“更多无反应”的唯一原因。

## 分工
Claude：实际数字角标控件代码；Codex界面子任务：只读定位More边界与确认实现事实；测试子任务：新增菜单、忙碌保留文字、数字角标、明暗预览测试；主Codex：代码整合、图标、系统绑定测试升级、构建安装与验收。

## 实测
- Gradle debug+AndroidTest BUILD SUCCESSFUL 17秒。
- 手机21项Instrumentation PASS，3.384秒，见device.log；包括真实Rime全拼、语音事务和Android控件。
- 当前系统已选择自有输入法，未执行ime set或改系统设置。真实系统绑定输入法测试1项PASS，4.176秒，见bound-device.log。实际长按触点→点击更多→菜单三项及其他输入法可见→收起→展开键盘→宿主可用高度缩小，均通过。未录音、未点击系统输入法选择器。
- real-window/more.png、dock.png、keyboard.png是本轮真实系统窗口截图；window-evidence.json记录宿主可见区域。
- previews/keyboard.png、dark.png为生产View离屏预览，背景及文字是测试数据，明确不是系统截图。
- previews/more.png出现文字未绘制，已拒绝作为显示成功证据，不交付；使用real-window/more.png替代。不得拿离屏空白菜单证明真实窗口没有字。

## 仍待验证
用户日常宿主中的复测、触摸外部收起的OEM行为、真人麦克风完整流程、实际电脑接收回执；旧完整App端设置页面的美化不在本轮范围。
