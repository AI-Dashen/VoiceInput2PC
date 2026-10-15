from PIL import Image, ImageDraw, ImageFont

W, H = 1600, 2000
BG = "#F4F7F9"
NAVY = "#16283A"
BLUE = "#2E6F95"
TEAL = "#1E8A7A"
CORAL = "#E46F55"
INK = "#1F2B36"
MUTED = "#637381"
WHITE = "#FFFFFF"
LINE = "#B9C8D2"
FONT = "/system/fonts/NotoSansCJK-Regular.ttc"

img = Image.new("RGB", (W, H), BG)
d = ImageDraw.Draw(img)

def font(size):
    return ImageFont.truetype(FONT, size)

def centered(text, box, size=34, color=INK):
    x1, y1, x2, y2 = box
    f = font(size)
    bb = d.multiline_textbbox((0, 0), text, font=f, spacing=8, align="center")
    tw, th = bb[2] - bb[0], bb[3] - bb[1]
    d.multiline_text(((x1+x2-tw)/2, (y1+y2-th)/2), text, font=f, fill=color, spacing=8, align="center")

def box(x, y, w, h, text, fill=WHITE, outline=LINE, color=INK, size=32, radius=24):
    rect=(x,y,x+w,y+h)
    d.rounded_rectangle(rect, radius=radius, fill=fill, outline=outline, width=3)
    centered(text, rect, size, color)
    return rect

def arrow(x1, y1, x2, y2, color=BLUE, width=6):
    d.line((x1,y1,x2,y2), fill=color, width=width)
    import math
    a=math.atan2(y2-y1,x2-x1)
    l=20
    pts=[(x2,y2),(x2-l*math.cos(a-.55),y2-l*math.sin(a-.55)),(x2-l*math.cos(a+.55),y2-l*math.sin(a+.55))]
    d.polygon(pts, fill=color)

d.rectangle((0,0,W,210), fill=NAVY)
d.text((90,55), "万能语音输入法 v0.8", font=font(55), fill=WHITE)
d.text((90,130), "端到端操作流程基线 · 每一步都有明确下一状态", font=font(30), fill="#CFE1EA")

d.text((90,265), "输入方式", font=font(38), fill=NAVY)
d.text((590,265), "确认与编辑", font=font(38), fill=NAVY)
d.text((1120,265), "输出目标", font=font(38), fill=NAVY)

box(80,340,390,130,"语音\n点击开始说话",fill="#EAF4F8",outline=BLUE,size=34)
box(80,550,390,130,"手动点停止\n或静音 6 秒",fill="#FFF1EC",outline=CORAL,size=32)
box(80,760,390,130,"引擎完成识别",fill="#EAF4F8",outline=BLUE,size=34)
arrow(275,470,275,550)
arrow(275,680,275,760)

box(575,340,440,130,"可编辑语音草稿",fill="#EAF7F3",outline=TEAL,size=36)
box(575,550,440,130,"继续说 / 删除 / 改字",fill=WHITE,outline=TEAL,size=31)
box(575,760,440,130,"点击“上屏”\n明确确认文字",fill=TEAL,outline=TEAL,color=WHITE,size=34)
arrow(795,470,795,550,color=TEAL)
arrow(795,680,795,760,color=TEAL)
arrow(470,825,575,825,color=TEAL)

box(1120,340,390,130,"本机输入框",fill="#EAF4F8",outline=BLUE,size=38)
box(1120,550,390,130,"远端待发送草稿",fill="#FFF5E8",outline="#D69C43",size=34)
box(1120,760,390,130,"宿主 App 自行发送\n或远端点击发送",fill=NAVY,outline=NAVY,color=WHITE,size=31)
arrow(1015,825,1120,405,color=BLUE)
arrow(1015,825,1120,615,color="#D69C43")
arrow(1315,680,1315,760,color="#D69C43")

d.text((90,980), "键盘输入流程", font=font(40), fill=NAVY)
box(80,1050,330,120,"26键 / 九宫格",fill="#EAF4F8",outline=BLUE,size=33)
box(500,1050,330,120,"输入拼音",fill=WHITE,outline=BLUE,size=36)
box(920,1050,330,120,"显示候选",fill=WHITE,outline=BLUE,size=36)
box(1290,1050,230,120,"点候选\n直接确认",fill=BLUE,outline=BLUE,color=WHITE,size=31)
arrow(410,1110,500,1110)
arrow(830,1110,920,1110)
arrow(1250,1110,1290,1110)

box(330,1260,940,145,"存在语音草稿时：键盘只修改草稿，不直接写入宿主；\n切换输入方式只换键盘，不偷偷上屏、不清空。",fill="#FFF1EC",outline=CORAL,size=31)

d.text((90,1515), "必须修正的现有冲突", font=font(40), fill=NAVY)
box(80,1590,440,190,"GAP-01\n切换键盘会自动提交\n语音草稿",fill="#FFF1EC",outline=CORAL,size=30)
box(580,1590,440,190,"GAP-02\n语音草稿无法自然进入\n键盘修改流程",fill="#FFF1EC",outline=CORAL,size=30)
box(1080,1590,440,190,"GAP-03\n候选是否还需再点模式键\n必须真机复现",fill="#FFF1EC",outline=CORAL,size=30)

d.rounded_rectangle((80,1860,1520,1940),radius=18,fill=NAVY)
centered("停止录音 ≠ 确认文字 ≠ 切换输入方式 ≠ 发送消息",(80,1860,1520,1940),34,WHITE)

img.save("docs/v0.8/design-v4/01-end-to-end-operation-flow.png", optimize=True)
