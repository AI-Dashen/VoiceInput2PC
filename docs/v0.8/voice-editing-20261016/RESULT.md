# v0.8.10 语音编辑与输入检测结果

## 当前行为
- 语音主界面从开始就显示上删除、中麦克风、下方宿主动作；不再要求录音或识别结果才能出现。
- 两编辑键在录音/识别忙时置灰，空闲且连接就绪时可用；宿主动作依EditorInfo为发送/搜索/回车等。
- 编辑连接由bound、EditorInfo、session目标一致性、InputConnection共同判断；连接丢失或目标过期时两键保持可见但禁用，恢复后启用。
- 通过Android输入/光标事件更新状态，应用不支持位置报告时明确UNKNOWN；不凭selection=-1判断断连，不用截图轮询/模型调用判断每个按键。
- 更多→设置显示实际输入检测、宿主光标报告状态。位置报告不等于已核实光标像素。
- 生命周期解绑使旧识别回调失效；本机编辑动作执行前再次检查目标；远端保持原ACK/草稿边界。

## 布局与状态
语音、更多、设备菜单统一280dp窗口，普通键盘恢复WRAP_CONTENT，固定候选区保留。只在几何变化时请求insets更新，避免每次无变化布局重复请求。仍不是全屏自由拖动；该目标PENDING，未申请悬浮权限、未修改系统。

## 已完成验证
- EditorReadinessPolicy JVM 26 PASS：16组合、8动作状态、2null状态。
- host键盘测试4 PASS。
- 最终Gradle debug+AndroidTest BUILD SUCCESSFUL14秒，应用与测试APK更新安装成功，保留用户数据。
- 最终Android组件36/36 PASS，5.353秒（device-final.log）：真实父布局测量3项、编辑连接6项、真实Rime高度稳定1项、竖排/默认可编辑11项、语音事务10项、原生视觉与菜单5项。
- 自身Activity真实绑定的底部四行输入框焦点测试PASS；初始、更多期间、返回后8次采样：host/window焦点true、selection维持末尾、cursorVisible设置true、计算位置在host/window可见区（focus-evidence.json与bound相关日志）。没有录音、没有发送、没有设置系统输入法。
- 实际三键/窗口稳定性最终结果另见bound-complete.log与window-evidence.json，不能用历史失败代替通过。

## 不能宣称已经解决的内容
微信静态截图里没有看到光标，目前没有确定其像素绘制根因。自身页面焦点通过不等于微信通过；可能性不能写成事实。本版本可记录/显示宿主的光标报告，尚未在微信当前输入框读取到对应报告，因此微信光标问题仍PENDING。

## 失败记录与纠正
- 旧bound测试在等待动画后读取旧AccessibilityNodeInfo缓存bounds，after却获取新节点；已改为前后均刷新并连续3次稳定取值，保留完全相同的坐标断言，不将旧失败追溯为PASS。
- 默认编辑键不再可收起，旧bound的collapse成功预期与新契约冲突，改为必须拒绝隐藏必需操作且按钮仍可见。
- 一轮组件suite末尾截图测试未及时退出，已停止本次自有App测试。未获取足够证据确认卡点/根因；补阶段日志、收敛无变化insets请求后，完整36项5.353秒结束。保留失败/中断日志，不冒称已定位为内存或PNG压缩故障。

共享APK：/storage/emulated/0/Pictures/CodexCamera/MobileAI-Exports/万能输入法-v0.8.10-语音编辑与连接检测.apk

未commit/push；没有新的系统权限、系统设置修改、其他应用Hook或外部消息发送。安装/测试仅在已授权自有应用范围内使用现有Root。

## 最终真实绑定验收收口
VoiceDockBoundDeviceTest最终PASS，6.985秒（bound-complete.log）；fresh节点基线下三键48dp、上下顺序、麦克风锚点和IME窗口前后不变、长按更多、返回键盘均通过。CursorFocusBoundDeviceTest此前同一生产版本已PASS，自有页面焦点/选区/光标几何正常。微信光标根因仍PENDING。无须重复已通过验收。
