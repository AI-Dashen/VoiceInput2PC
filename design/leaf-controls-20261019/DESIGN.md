# 浮动三键：鲜叶绿/透明镂空设计讨论稿
2026-10-19。用户拒绝0.8.23深绿色与未同步修改的删除/回车。当前只做视觉研究和效果图，未修改产品。

目标：删除、麦克风、回车三键统一透明中心，不留灰色底盘；保留48dp触摸范围，视觉外径约34–36dp、图标20–22dp。鲜叶绿候选#51C832、边缘高光#9BE85C，使用窄暗色轮廓和细浅色高光适配深浅背景。不能仅靠绿色变亮保证白底可辨识。微光范围拟2–3dp，静态窄光晕，不做大面积模糊或大阴影；录音红、识别琥珀断环，保留形状状态反馈。

现存代码问题：MicView更改后compactControl仍保留0x55eff5f8底色、ImeIconButton仍固定#264a5c，无法统一适配黑底。后续需要共享视觉token与绘制管线，同时处理三个控件，不能只改MicView。

参考：
- Apple Materials https://developer.apple.com/design/human-interface-guidelines/materials — 透明材料需要保持可读性；本App不声称拥有Apple Liquid Glass或跨App采样背景能力。
- Fluent按钮 https://fluent2.microsoft.design/components/web/react/core/button/usage/ — 图标对比原则及状态信息。
- Material颜色 https://m3.material.io/styles/color/the-color-system — 正确配对背景/前景。

Claude实际咨询success 14.328秒。支持透明三键统一、鲜绿和暗轮廓；建议#7CFC4A/#8BE24A作为备选。其setShadowLayer/software layer仅作候选，不直接采用，必须真机核验绘制与性能，避免为了微光增加不必要模糊开销。

验收应在纯白、纯黑、中灰、绿色/复杂背景对照：三键均可辨识、中心透出宿主、禁用不误判、状态环形状可分辨、触摸区不变、录音之外不做持续强动画。图像生成概念只说明视觉，不证明Android实际渲染和对比已通过。待用户确认再实现。

生成图复核：图中麦克风比辅助两键略大、中心麦克风胶囊存在实心处理；不是尺寸/填充精确规范。实现仍应统一外径并保持图标内部镂空，不能直接从示意图抄不一致几何。上半图为真实大小关系讨论，下半为放大状态示意。
