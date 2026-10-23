# 0.8.7 图标菜单交付记录
日期2026-10-16。版本0.8.7-icon-menu，versionCode17。

## 按已确认效果图完成
- 默认麦克风改墨蓝，保留红色收音与琥珀识别，细白边及外轮廓。
- 展开条4个图标：键盘、删除、真实宿主动作、更多。动作按EditorInfo显示发送/搜索/完成/回车对应图形，不假定所有输入框可发送。
- 更多176×120dp、两行六图标：设备目标、历史、统计、设置、切换输入法、本机；选中本机有勾。视觉不铺汉字，contentDescription和tooltip保留中文。
- 设备选择保留真实名称，远端有待发送/未知结果时不允许切回本机丢弃草稿。
- 保留26键、首排长按数字与角标、全拼回调修复及语音事务。

## 分工与真实结果
- Codex界面子代理：IMS布局/点击链与远端目标保护；测试子代理：3个Android测试类及图标/无障碍/菜单验收。
- Claude：本轮图标类代码请求180秒超时，限一次重试后再次180秒超时，无有效代码返回（claude-code.json）。不能称新图标由Claude完成。此前实际由Claude编写的CompactImeUiPolicy和ImeKeycapView继续使用。
- 主Codex：补齐ImeIconButton Canvas矢量实现、统一墨蓝与圆形边框、构建安装验收。Jev真实分类已记录。

## 实测
- 主机键盘4/4 PASS，2.305秒；CompactImeUiPolicyTest all passed。
- Gradle debug+AndroidTest BUILD SUCCESSFUL16秒；自身App和AndroidTest更新安装成功，未清数据。
- 手机Android输入/布局/真实Rime22项PASS，2.761秒（device.log）。
- 真实系统绑定输入法窗口1项PASS，5.812秒（bound-device.log）。真实长按→展开4图标→更多6图标可见→本机回调收起→展开键盘成功，宿主可见高度符合键盘收放。当前系统已经选择自有IME，未执行ime set或修改系统设置；测试未录音，未触发切换输入法系统选择器。
- real-window/{default,expanded,more,keyboard}.png是本轮实际系统窗口截图，并已由Codex直接查看。背景为自身测试Activity，不是概念图。expanded截图宿主action是完成勾，非强行发送，符合实际EditorInfo。

## 限制
本轮未验证所有第三方App、OEM外点收起、真人麦克风长时使用及真实电脑ACK。旧连接电脑页面的米黄配色与卡片布局仍待单独设计，未把本图标菜单当成全App视觉重构完成。无新系统权限或悬浮权限。未提交Git或推送。
