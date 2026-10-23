# v0.8.9 键盘跳动修复结果

## 根因与修改
用户09:23:22、09:23:28截图显示Termux在候选栏出现/消失时上下重排。生产代码确实在rawInput非空时将键盘增加48dp，20dp拼音栏与28dp候选栏从GONE改VISIBLE；清空又收回，触发宿主resize。

修复：CompactImeUiPolicy始终预留48dp候选区，普通竖屏268dp、横屏236dp，远端另48不变；候选空时INVISIBLE而非GONE。保留现有组字按键高度，不屏蔽候选；语音小图标/模型未改。版本0.8.9-stable-keyboard，code19。

## 已执行证据
- host键盘回归4/4 PASS（host-tests.log）。
- policy JVM PASS，含4种orientation/remote组合各100轮idle/composing/idle高度相同（policy-test.log）。
- debug与AndroidTest BUILD SUCCESSFUL13秒（build.log）。
- 普通更新应用与测试APK安装成功（install.log/install-test.log），没有清除用户数据。
- Android26/26 PASS，3.535秒（device.log）。新增实际Rime、生产按键n/ni与候选点击的3轮循环，root/normalRoot/primaryPanel/keys顶部和高度全部恒定；空候选区域触摸不提交或发送。其他25项语音事务/竖排手势/图标布局回归通过。

## 验证边界
上述为手机Android上生产View+真实Rime/InputConnection测试，ASR相关旧fixture仍是替身；本次未重新操作Termux用户当前对话或启动前台测试Activity，不冒称已真实自动验证Termux整屏resize。用户截图与高度切换代码共同支持根因；其他App/OEM窗口行为仍需真实使用反馈。

共享安装包：/storage/emulated/0/Pictures/CodexCamera/MobileAI-Exports/万能输入法-v0.8.9-修复键盘跳动.apk

无系统配置、额外权限、模型下载或Git提交。
