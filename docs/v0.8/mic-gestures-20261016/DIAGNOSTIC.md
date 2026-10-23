# 真实窗口坐标验收纠正

2026-10-16，组件测试通过后，前三轮真实绑定测试在双击处失败，未把这些失败改写成PASS。原日志保留bound-device.log、bound-diagnostic.log、bound-trace.log、bound-trace2.log。

事实：GestureBoundDeviceTest在早期3次60ms节点采样后截图，仍复用截图前的Rect注入；初始动画后位置发生变化。first保存mic Rect中心y2385，而截图中麦克风墨色像素范围y2228..2300，中心约2264，不是同一时刻的位置。生产handleDockTouch没有接收这次注入，不能归因为双击时间窗口或识别逻辑。

修正测试：进入和回语音时先等600ms动画，截图前后重新取fresh稳定节点，严格要求bounds相等；注入前再刷新节点并核对own IME window。保持双击不进入录音、真实输入框回读等断言。双击间隔70ms，未放宽系统双击判断条件。

实际bound-settled.log：PASS，4.874秒；截图前后及注入时mic均1099 2100 1267 2268；own EditText真实回读Baseline。；无录音状态、起点删除隐藏、选区删除可用，七张截图。未因早期失败放开触摸区域、修改系统设置或改为任意全屏控制。

曾加入短期无正文的layout/insets/event诊断，完成定位后已从交付生产代码清除。查Android16 AOSP确认requestApplyInsets会安排traversal并重算InternalInsets，未采纳“这个API不更新内部insets”的无证据推断。
