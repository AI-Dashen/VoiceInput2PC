from PIL import Image, ImageDraw, ImageFont
from pathlib import Path
OUT=Path(__file__).parent
REG='/data/data/com.termux/files/home/.local/share/fonts/NotoSansCJK-Regular.ttc'
BOLD='/data/data/com.termux/files/home/.local/share/fonts/NotoSansCJK-Bold.ttc'
if not Path(BOLD).exists(): BOLD=REG
def font(n,b=False): return ImageFont.truetype(BOLD if b else REG,n)
def rr(d,box,r,fill,outline=None,w=1): d.rounded_rectangle(box,r,fill,outline,width=w)
def txt(d,xy,s,n=28,fill='#17212b',b=False,anchor=None): d.text(xy,s,font=font(n,b),fill=fill,anchor=anchor)
def line(d,pts,fill='#718096',w=4):
 d.line(pts,fill=fill,width=w)
 x,y=pts[-1]; x0,y0=pts[-2]
 if abs(x-x0)>abs(y-y0):
  q=-1 if x<x0 else 1; d.polygon([(x,y),(x-14*q,y-8),(x-14*q,y+8)],fill=fill)
 else:
  q=-1 if y<y0 else 1; d.polygon([(x,y),(x-8,y-14*q),(x+8,y-14*q)],fill=fill)
def save(img,name): img.save(OUT/name,quality=95)

# Function map
im=Image.new('RGB',(1600,1000),'#f6f8fa'); d=ImageDraw.Draw(im)
txt(d,(80,55),'万能语音输入法 v0.8｜功能结构',42,b=True)
txt(d,(80,110),'产品主体是系统输入法；电脑与其他终端只是已确认文字的输出目标',20,'#5f6b76')
rr(d,(70,155,1010,850),30,'#ffffff','#9fb9d1',3); rr(d,(70,155,1010,245),30,'#eaf4ff')
txt(d,(110,185),'系统输入法 · InputMethodService',26,b=True); txt(d,(795,192),'日常高频入口',17,'#2166a5',True)
txt(d,(110,275),'输入能力',25,b=True)
items=[(110,320,360,384,'26 键中文拼音','#f4f7fa'),(380,320,630,384,'中文九宫格','#f4f7fa'),(650,320,950,384,'英文 · 数字 · 符号','#f4f7fa'),(110,404,360,468,'本地离线语音','#e9f8f2'),(380,404,630,468,'候选与组字','#f4f7fa'),(650,404,950,468,'光标 · 选区 · 删除','#f4f7fa')]
for x1,y1,x2,y2,s,c in items: rr(d,(x1,y1,x2,y2),16,c); txt(d,((x1+x2)//2,(y1+y2)//2),s,19,b=False,anchor='mm')
line(d,[(530,492),(530,545)])
rr(d,(255,550,805,642),22,'#eef2ff','#8095d6',2); txt(d,(530,582),'StableTextEvent',25,b=True,anchor='mm'); txt(d,(530,617),'只有候选确认或语音 final 才能进入输出路由',17,'#52606d',anchor='mm')
line(d,[(530,642),(530,685)])
rr(d,(365,690,695,764),20,'#e9f3ff','#4e86bc',2); txt(d,(530,727),'TargetRouter',25,b=True,anchor='mm')
line(d,[(365,727),(245,727),(245,787)]); line(d,[(695,727),(815,727),(815,787)])
rr(d,(110,790,380,885),20,'#eaf8f1','#5a9d7e',2); txt(d,(245,823),'本机输入框',23,b=True,anchor='mm'); txt(d,(245,856),'InputConnection.commitText',16,'#52606d',anchor='mm')
rr(d,(680,790,950,885),20,'#edf3fb','#6e91b6',2); txt(d,(815,823),'受信任终端',23,b=True,anchor='mm'); txt(d,(815,856),'电脑 · 后续其他设备',16,'#52606d',anchor='mm')
rr(d,(1060,155,1530,850),30,'#ffffff','#cbd3da',3); rr(d,(1060,155,1530,245),30,'#f0f2f4')
txt(d,(1100,185),'设置与管理中心',26,b=True); txt(d,(1400,192),'低频入口',17,'#66717c',True)
settings=['启用与切换输入法','麦克风权限','本地模型状态与管理','终端配对 · 命名 · 移除','历史 · 导出 · 隐私','连接诊断','试用输入框']
for i,s in enumerate(settings):
 y=292+i*67; txt(d,(1110,y),s,20)
 if i<6: d.line((1110,y+34,1480,y+34),fill='#e1e5e8',width=2)
rr(d,(1100,752,1490,810),14,'#eef1f4'); txt(d,(1295,781),'不承担日常输入，不与电脑功能并列',16,'#52606d',anchor='mm')
rr(d,(70,905,1530,972),18,'#172331'); txt(d,(800,938),'密码字段强制本机｜临时结果不外发｜一个事件只有一个目标｜本地输入不依赖网络和电脑',19,'#ffffff',anchor='mm')
save(im,'01-function-map.png')

# Interaction flow
im=Image.new('RGB',(1600,1080),'#f7f9fb'); d=ImageDraw.Draw(im)
txt(d,(75,55),'万能语音输入法｜核心交互与安全状态',42,b=True); txt(d,(75,110),'同一套键盘与输入会话；确认后再选择唯一输出目标',20,'#64717c')
boxes=[((75,160,345,252),'IDLE','等待键盘或语音输入','#ffffff','#b7c5d1'),((445,145,745,267),'COMPOSING','拼音 / 九宫格 / 英文\n组字与候选，不输出','#edf5ff','#729bc3'),((840,145,1140,267),'CONFIRMED','用户选词或确认文字\n生成 StableTextEvent','#eaf8f2','#70a98e'),((1245,160,1515,252),'TARGET ROUTER','每个事件只有一个目标','#eef2ff','#788dcc')]
for box,h,s,c,o in boxes:
 rr(d,box,24,c,o,2); x=(box[0]+box[2])//2; txt(d,(x,box[1]+38),h,23,b=True,anchor='mm'); txt(d,(x,box[1]+78),s,16,'#5f6b76',anchor='mm',)
line(d,[(345,206),(440,206)]); line(d,[(745,206),(835,206)]); line(d,[(1140,206),(1240,206)])
voice=[((75,360,345,482),'LISTENING','点击麦克风后录音\n取消/焦点变化立即停录'),((445,360,745,482),'RECOGNIZING','本地模型生成结果\n禁止云端静默回退'),((840,360,1140,482),'PREVIEW','可编辑识别文字\n完成后才进入 CONFIRMED')]
for box,h,s in voice:
 rr(d,box,24,'#eaf8f2' if h!='PREVIEW' else '#fff8eb','#70a98e' if h!='PREVIEW' else '#caa36a',2); x=(box[0]+box[2])//2; txt(d,(x,box[1]+40),h,23,b=True,anchor='mm'); txt(d,(x,box[1]+86),s,17,'#53616e',anchor='mm')
line(d,[(205,252),(205,355)]); line(d,[(345,421),(440,421)]); line(d,[(745,421),(835,421)]); line(d,[(990,360),(990,275)])
line(d,[(1380,252),(1380,355)])
rr(d,(1215,360,1545,482),24,'#eaf8f2','#70a98e',2); txt(d,(1380,401),'本机当前输入框',23,b=True,anchor='mm'); txt(d,(1380,443),'InputConnection.commitText',17,'#53616e',anchor='mm')
line(d,[(1380,252),(1380,520),(1025,520),(1025,590)])
rr(d,(845,595,1205,717),24,'#edf3fb','#7293b5',2); txt(d,(1025,636),'受信任终端',23,b=True,anchor='mm'); txt(d,(1025,680),'在线才发送，回执决定完成状态',17,'#53616e',anchor='mm')
rr(d,(75,565,745,765),28,'#ffffff','#d4a163',3); txt(d,(115,603),'敏感字段覆盖规则',24,b=True); txt(d,(115,650),'密码 / 数字密码 / Web 密码',18); txt(d,(115,690),'→ 强制目标为本机',18); txt(d,(115,730),'→ 隐藏语音、终端、历史和待接收文字',18)
rr(d,(75,805,785,1010),28,'#ffffff','#b9c4ce',2); txt(d,(115,843),'终端离线 / 超时',24,b=True); txt(d,(115,892),'1. 不把“已发送”写成完成',18); txt(d,(115,932),'2. 保存草稿、事件编号和真实失败状态',18); txt(d,(115,972),'3. 用户选择重试、切回本机或取消',18)
rr(d,(835,805,1545,1010),28,'#172331'); txt(d,(875,843),'验收核心',24,'#ffffff',True); txt(d,(875,892),'本机：只进入当前 App，电脑收到 0 条',18,'#ffffff'); txt(d,(875,932),'电脑：宿主输入框不变，电脑只收到 1 次',18,'#ffffff'); txt(d,(875,972),'密码：语音、历史、电脑路由全部不可用',18,'#ffffff')
save(im,'02-interaction-flow.png')
print(OUT/'01-function-map.png')
print(OUT/'02-interaction-flow.png')
