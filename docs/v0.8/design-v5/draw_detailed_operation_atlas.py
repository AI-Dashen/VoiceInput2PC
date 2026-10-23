from PIL import Image, ImageDraw, ImageFont
from pathlib import Path
import textwrap

OUT = Path(__file__).resolve().parent
FONT = '/system/fonts/NotoSansCJK-Regular.ttc'
FONT_BOLD = '/system/fonts/NotoSansCJK-Bold.ttc'
if not Path(FONT_BOLD).exists(): FONT_BOLD = FONT
W = 1800
BG = '#F5F7F9'; NAVY = '#172B3A'; INK = '#1E2B35'; MUTED = '#64727D'
BLUE = '#246B90'; BLUE_BG = '#E8F3F8'; GREEN = '#17836F'; GREEN_BG = '#E8F6F1'
PURPLE = '#7255A5'; PURPLE_BG = '#F1ECFA'; AMBER = '#C27A16'; AMBER_BG = '#FFF5DF'
RED = '#C94F43'; RED_BG = '#FFF0ED'; LINE = '#CED8DE'; WHITE = '#FFFFFF'

def F(size, bold=False): return ImageFont.truetype(FONT_BOLD if bold else FONT, size)

def wrap_px(draw, text, font, max_w):
    lines=[]
    for para in str(text).split('\n'):
        if not para:
            lines.append(''); continue
        cur=''
        for ch in para:
            trial=cur+ch
            if draw.textbbox((0,0), trial, font=font)[2] <= max_w or not cur:
                cur=trial
            else:
                lines.append(cur); cur=ch
        if cur: lines.append(cur)
    return lines

def draw_wrapped(draw, xy, text, font, fill, max_w, spacing=8):
    x,y=xy; lines=wrap_px(draw,text,font,max_w)
    line_h=font.size+spacing
    for line in lines:
        draw.text((x,y),line,font=font,fill=fill)
        y += line_h
    return y

def rounded(draw, rect, fill, outline=None, width=2, radius=18):
    draw.rounded_rectangle(rect,radius=radius,fill=fill,outline=outline,width=width)

def arrow(draw, x, y1, y2, color=GREEN):
    draw.line((x,y1,x,y2-12),fill=color,width=5)
    draw.polygon([(x,y2),(x-10,y2-16),(x+10,y2-16)],fill=color)

def header(img, title, subtitle, page):
    d=ImageDraw.Draw(img)
    d.rectangle((0,0,W,210),fill=NAVY)
    d.text((70,43),title,font=F(52,True),fill=WHITE)
    d.text((70,120),subtitle,font=F(28),fill='#D0DFE7')
    d.text((W-220,74),page,font=F(28,True),fill='#A9C6D6')
    return d

def legend(d,y):
    items=[('蓝','用户动作',BLUE,BLUE_BG),('绿','屏幕/系统状态',GREEN,GREEN_BG),('紫','文字或数据位置',PURPLE,PURPLE_BG),('黄','判断/分支',AMBER,AMBER_BG),('红','异常/禁止',RED,RED_BG)]
    x=70
    for _,label,c,bg in items:
        rounded(d,(x,y,x+250,y+64),bg,c,2,16)
        d.ellipse((x+16,y+17,x+46,y+47),fill=c)
        d.text((x+58,y+13),label,font=F(24,True),fill=INK)
        x += 270

def draw_step_page(filename,title,subtitle,page,steps,footer):
    row_h=194
    H=340 + len(steps)*row_h + 150
    img=Image.new('RGB',(W,H),BG); d=header(img,title,subtitle,page); legend(d,230)
    cols=[70,230,600,1070,1440,1730]
    y=320
    labels=['步骤','你做什么','屏幕/系统发生什么','文字在哪里','下一步']
    for i,l in enumerate(labels):
        d.text((cols[i]+12,y),l,font=F(25,True),fill=NAVY)
    y += 48
    for idx,s in enumerate(steps):
        y1=y+idx*row_h; y2=y1+row_h-16
        fill=WHITE if idx%2==0 else '#FAFCFD'
        rounded(d,(70,y1,1730,y2),fill,LINE,2,16)
        # step badge
        badge_c = {'normal':BLUE,'decision':AMBER,'error':RED,'system':GREEN}.get(s.get('kind','normal'),BLUE)
        rounded(d,(88,y1+18,206,y1+74),badge_c,None,0,14)
        d.text((105,y1+27),s['id'],font=F(22,True),fill=WHITE)
        if idx < len(steps)-1: arrow(d,147,y2+3,y1+row_h-4,GREEN)
        cells=[s['action'],s['screen'],s['data'],s['next']]
        colors=[BLUE,GREEN,PURPLE,AMBER if s.get('kind')=='decision' else INK]
        for ci,text in enumerate(cells):
            x1=cols[ci+1]+18; x2=cols[ci+2]-18
            draw_wrapped(d,(x1,y1+18),text,F(24,ci==0),colors[ci],x2-x1,8)
        for x in cols[1:-1]: d.line((x,y1+12,x,y2-12),fill='#E4EAEE',width=2)
    fy=H-104
    rounded(d,(70,fy,1730,fy+64),NAVY,None,0,16)
    bb=d.textbbox((0,0),footer,font=F(25,True)); tw=bb[2]-bb[0]
    d.text(((W-tw)/2,fy+15),footer,font=F(25,True),fill=WHITE)
    img.save(OUT/filename,optimize=True)

# Page 00 overview diagram
H=2100
img=Image.new('RGB',(W,H),BG); d=header(img,'万能语音输入法 v0.8｜完整操作流程册','先看总览，再按 01—05 页逐步检查；每一步均有动作、屏幕、数据与出口','总览')
legend(d,230)
# central happy path
nodes=[
 ('01','首次启用 / 日常进入','启用输入法 → 选择输入法 → 点击普通输入框','01-entry.png'),
 ('02','语音完整闭环','点语音 → 说话 → 停止/静音6秒 → 草稿 → 修改/继续说 → 上屏','02-voice.png'),
 ('03','26键 / 九宫格 / 符号','选模式 → 组字 → 候选 → 选词/删除/空格/回车 → 草稿或输入框','03-keyboard.png'),
 ('04','本机与跨设备输出','本机上屏≠发送；远端草稿 → 显式发送 → ACK / 原编号重试','04-output.png'),
 ('05','跨App、二级功能与异常','切App隔离、敏感字段降级、超时/错误/重复停止/麦克风释放','05-safety.png'),
]
y=380
for i,(num,name,detail,file) in enumerate(nodes):
    rounded(d,(140,y,1660,y+230),WHITE,LINE,3,24)
    rounded(d,(180,y+42,310,y+172),BLUE if i<4 else RED,None,0,28)
    d.text((212,y+71),num,font=F(42,True),fill=WHITE)
    d.text((360,y+35),name,font=F(36,True),fill=NAVY)
    draw_wrapped(d,(360,y+95),detail,F(27),INK,1190,10)
    d.text((1370,y+176),file,font=F(20),fill=MUTED)
    if i<len(nodes)-1: arrow(d,900,y+230,y+288,GREEN)
    y += 300
rounded(d,(140,1870,1660,1990),NAVY,None,0,22)
d.text((220,1893),'四条不可混淆的边界：停止录音 ≠ 确认文字 ≠ 本机上屏 ≠ 远端发送',font=F(31,True),fill=WHITE)
img.save(OUT/'00-operation-atlas-index.png',optimize=True)

entry=[
 {'id':'E01','action':'首次安装后，打开“万能输入法”管理中心','screen':'显示本地语音、系统输入法、隐私状态；不直接弹出键盘','data':'尚未建立输入会话','next':'检查麦克风与输入法状态'},
 {'id':'E02','action':'点“授权麦克风”并在系统提示中允许','screen':'管理中心显示“本地语音已授权”','data':'只记录权限状态，不录音','next':'未允许→语音入口明确不可用；允许→E03','kind':'decision'},
 {'id':'E03','action':'点“打开系统输入法设置”，手动启用万能输入法','screen':'Android 系统输入法设置显示已启用','data':'系统登记 InputMethodService','next':'返回管理中心'},
 {'id':'E04','action':'点“选择当前输入法”，选择万能输入法','screen':'当前输入法切换成功','data':'系统默认/当前 IME 选择变化','next':'打开任意普通文本框'},
 {'id':'E05','action':'在微信、记事本等普通文本框点一下','screen':'键盘升起；左右功能栏和中间输入区出现','data':'创建 sessionId + package + fieldId + generation','next':'判断当前输入框类型','kind':'system'},
 {'id':'E06','action':'无需操作，系统检查字段是否敏感','screen':'普通框：默认显示语音待命；密码框：只显示安全键盘','data':'敏感框不载入语音、历史、远端目标','next':'普通框→E07；敏感框→安全键盘流程','kind':'decision'},
 {'id':'E07','action':'普通框内直接选择“语音 / 26键 / 九宫格 / 符号”','screen':'被选模式高亮；只切换输入面板，不产生文字','data':'当前输入会话保持不变','next':'语音→02页；键盘→03页'},
 {'id':'E08','action':'需要设备、统计或设置时点右侧“功能”','screen':'进入二级功能页；显示跨设备、输入记录、设置','data':'保存当前 generation、模式、草稿、组字和光标快照','next':'点具体功能或点“返回”'},
 {'id':'E09','action':'点“返回”或 Android 返回键','screen':'同一输入框：恢复原输入状态；输入框已变化：开启新会话','data':'只在 generation 相同的情况下恢复快照','next':'继续当前输入，不自动上屏','kind':'decision'},
]
draw_step_page('01-entry.png','01｜首次启用与日常进入','从安装到键盘出现；明确系统设置、敏感字段与二级页的边界','01 / 05',entry,'完成标准：进入普通文本框后，默认是“语音待命”，并且尚未写入任何文字')

voice=[
 {'id':'V01','action':'在普通文本框唤起输入法','screen':'中间显示“点击左侧语音开始说话”；语音键高亮','data':'当前输入会话为空','next':'点左侧“语音”'},
 {'id':'V02','action':'点左侧“语音”','screen':'按钮变“停止”；显示正在收音和已收音时长','data':'新建本轮音频流；generation固定','next':'开始说话；若麦克风不可用→V-E1'},
 {'id':'V03','action':'正常说话','screen':'持续显示录音时长；可显示临时识别文字','data':'音频进入本地识别；临时文字只在流缓存','next':'继续说、短暂停顿或手动停止'},
 {'id':'V04','action':'短暂停顿，但未达到连续静音6秒','screen':'仍显示录音中，不结束、不上屏','data':'已有音频与临时文字继续保留','next':'继续说话'},
 {'id':'V05','action':'点“停止”，或连续静音达到6秒','screen':'停止键锁定；显示“正在生成最终文字”','data':'停止动作只结算一次；立即进入麦克风释放流程','next':'等待本地引擎 final','kind':'decision'},
 {'id':'V06','action':'无需操作，系统释放麦克风','screen':'录音指示消失，不再占用音频输入','data':'音频流关闭；本轮 generation 仍有效','next':'识别成功→V07；失败→V-E2','kind':'system'},
 {'id':'V07','action':'等待最终识别完成','screen':'最终文字出现在中间可编辑草稿；右侧变“上屏”','data':'文字进入当前输入框专属语音草稿，尚未进入宿主输入框','next':'继续说 / 键盘修改 / 删除 / 取消 / 上屏'},
 {'id':'V08','action':'还没说完时，再点“语音”','screen':'原草稿保留；按钮重新变“停止”','data':'原草稿成为追加前缀，新音频属于同一会话的新片段','next':'说下一段→V05→V09'},
 {'id':'V09','action':'说完下一段并停止/静音6秒','screen':'新结果追加到草稿光标处，不覆盖原文','data':'合并后的文字仍只在可编辑草稿','next':'继续追加或编辑'},
 {'id':'V10','action':'点26键/九宫格/符号并修改文字','screen':'切换键盘；草稿仍可见、可删改；不会自动上屏','data':'键盘输入修改当前草稿','next':'修改完回到待确认草稿'},
 {'id':'V11','action':'点“取消”','screen':'提示本轮草稿已取消；回到语音待命','data':'仅清空当前未确认语音草稿','next':'重新说话或切换输入方式'},
 {'id':'V12','action':'确认文字无误，点右侧“上屏”','screen':'本机目标：文字进入当前输入框；远端目标：加入待发送草稿','data':'语音草稿清空；产生一条稳定文本事件','next':'本机流程结束；远端进入04页','kind':'decision'},
]
draw_step_page('02-voice.png','02｜语音输入完整闭环','重点：停录、识别、草稿、上屏是四个不同状态','02 / 05',voice,'主流程：点语音 → 说话 → 停止/静音6秒 → 可编辑草稿 → 上屏；任何模式切换都不得偷偷提交')

keyboard=[
 {'id':'K01','action':'点“26键”或“九宫格”','screen':'对应模式高亮；中间显示组字区、候选栏和键盘','data':'若已有语音草稿则保留；旧候选清空','next':'开始输入拼音/数字串'},
 {'id':'K02','action':'按字母，或按九宫格数字键','screen':'组字区显示拼音；候选栏实时更新','data':'内容仅在 composing 组字区，尚未确认','next':'继续输入、翻候选、删除或选词'},
 {'id':'K03','action':'候选不在当前页时点“‹ / ›”','screen':'只切换候选页，组字串不变','data':'候选页码变化；文字未确认','next':'点目标候选'},
 {'id':'K04','action':'点某个候选','screen':'候选文字进入目标；未完成的拼音继续保留候选','data':'有语音草稿→插入草稿；无语音草稿→本机输入框或远端草稿','next':'继续组字或结束本词','kind':'decision'},
 {'id':'K05','action':'有候选时点“空格”','screen':'确认第一候选；不额外插入空格','data':'与点击第一候选相同','next':'回到键盘就绪'},
 {'id':'K06','action':'没有组字时点“空格”','screen':'插入一个普通空格','data':'写入当前语音草稿 / 本机输入框 / 远端草稿','next':'继续输入'},
 {'id':'K07','action':'有组字时点“删除”','screen':'删除最后一个拼音字母/九宫格输入；候选刷新','data':'只修改 composing，不碰草稿和宿主文字','next':'组字为空后进入K08'},
 {'id':'K08','action':'组字为空时点“删除”','screen':'有草稿则删草稿光标前字符；否则删目标光标前字符','data':'删除作用域按 草稿→远端/宿主 的优先级选择','next':'继续编辑'},
 {'id':'K09','action':'点“中/EN”“123”或“符号”','screen':'键盘布局切换；已有草稿保留，旧组字明确取消','data':'只变输入模式；不得自动上屏','next':'输入英文、数字或符号'},
 {'id':'K10','action':'输入英文、数字或符号','screen':'字符立即显示在当前编辑位置','data':'有语音草稿→修改草稿；无草稿→稳定写入当前目标','next':'继续输入或确认'},
 {'id':'K11','action':'组字未完成时点“回车”','screen':'只确认当前第一候选，不同时触发宿主发送/换行','data':'先结束 composing，防止误发送','next':'需要发送/换行时再点一次回车','kind':'decision'},
 {'id':'K12','action':'组字为空时点“回车”','screen':'本机：执行宿主定义的换行/下一步/发送；远端：进入显式发送','data':'不重复提交候选','next':'本机由宿主接管；远端进入04页'},
 {'id':'K13','action':'有语音草稿时点“语音”','screen':'草稿保留，重新开始录音','data':'新语音结果追加到当前草稿光标处','next':'返回02页V03'},
 {'id':'K14','action':'切换26键↔九宫格','screen':'新键盘高亮；草稿保留；旧候选清空','data':'不会把模式切换当成确认动作','next':'在新布局继续输入'},
]
draw_step_page('03-keyboard.png','03｜26键、九宫格与符号逐步流程','每个按键都根据“是否有组字 / 是否有语音草稿”决定作用域','03 / 05',keyboard,'关键边界：选词可以确认文字；切键盘只能切模式；删除必须先判断组字、草稿和目标三层')

output=[
 {'id':'O01','action':'查看右侧目标键','screen':'显示“本机”或当前受信任设备简称','data':'输入方式与输出目标彼此独立','next':'保持本机，或点目标键切换'},
 {'id':'O02','action':'点目标键切换输出目标','screen':'无未决内容时切换；有组字/识别中时提示先完成当前输入','data':'不得静默搬运组字、未确认草稿或在途请求','next':'本机→O03；远端→O05','kind':'decision'},
 {'id':'O03','action':'本机目标下确认候选或点“上屏”','screen':'文字出现在当前App输入框','data':'写入宿主 InputConnection；输入法草稿清空','next':'O04'},
 {'id':'O04','action':'需要发送消息时，由用户点宿主App的发送键','screen':'宿主App自行发送；输入法不代替点击','data':'发送由微信/短信/记事本等宿主负责','next':'回到输入法待命'},
 {'id':'O05','action':'选择已配对的受信任设备','screen':'目标键显示设备名；中间出现远端待发送草稿','data':'新文字进入独立远端草稿，不污染手机输入框','next':'用语音或键盘生成文字'},
 {'id':'O06','action':'语音点“上屏”或键盘确认稳定文字','screen':'文字追加到远端草稿；右侧显示“发送”','data':'尚未离开手机；可继续删改','next':'继续编辑或点发送'},
 {'id':'O07','action':'点右侧“发送”','screen':'显示“正在发送到设备”；发送键变等待','data':'冻结本次发送前缀，生成 request_id；新输入进入尾部草稿','next':'等待回执'},
 {'id':'O08','action':'无需操作，收到匹配 ACK','screen':'显示已送达；已发送前缀清除，新输入尾部仍保留','data':'request_id 标记成功，不能重复发送','next':'继续输入下一段','kind':'system'},
 {'id':'O09','action':'超时后点“重试”','screen':'提示结果未知；草稿不丢；重试状态可见','data':'复用原 request_id，防止电脑重复输入','next':'收到ACK→O08；再次失败→O10','kind':'decision'},
 {'id':'O10','action':'发送失败后选择继续编辑、重试或取消在途','screen':'保持远端草稿，不永久卡在“发送中”','data':'未确认成功的内容不删除','next':'重试 / 编辑 / 安全返回本机'},
 {'id':'O11','action':'没有已配对设备时点目标键或“跨设备传输”','screen':'打开配对与设备管理；不伪装成可发送','data':'当前输入会话和草稿保持','next':'配对成功后返回并明确选择设备'},
 {'id':'O12','action':'在密码等敏感字段尝试切远端','screen':'目标键禁用并提示“敏感输入框禁止跨设备”','data':'强制本机；不读取或迁移旧远端草稿','next':'使用安全本机键盘','kind':'error'},
]
draw_step_page('04-output.png','04｜本机上屏与跨设备发送','先选目标，再确认文字；本机上屏和远端发送必须是两条独立路径','04 / 05',output,'本机：上屏后由宿主App决定发送　｜　远端：草稿 → 显式发送 → 匹配ACK；失败保留草稿并复用request_id')

safety=[
 {'id':'S01','action':'点新的App或新的输入框','screen':'新输入框获得焦点；输入法重新判断字段类型','data':'旧 generation 失效；新建 sessionId / generation','next':'执行S02','kind':'system'},
 {'id':'S02','action':'无需操作，系统清理旧会话','screen':'停止旧录音、清除旧候选；新框不会显示旧文字','data':'旧ASR回调即使迟到也因generation不匹配被丢弃','next':'普通框默认语音；敏感框安全键盘'},
 {'id':'S03','action':'收起后重新打开同一个输入框','screen':'同一字段可恢复未确认草稿；不会恢复到其他字段','data':'只有 package + fieldId + generation 匹配才恢复','next':'继续编辑或上屏','kind':'decision'},
 {'id':'S04','action':'录音8秒仍未检测到有效说话','screen':'提示“未检测到语音”，立即恢复语音待命','data':'释放麦克风；原有草稿不变','next':'重新点语音或切键盘','kind':'error'},
 {'id':'S05','action':'停止后15秒仍没有最终结果','screen':'有临时文字→进入可编辑草稿；无文字→恢复待命','data':'保留可用部分，释放识别会话','next':'编辑/重试；不得卡住','kind':'error'},
 {'id':'S06','action':'本地引擎报错或连接断开','screen':'显示具体错误；“语音”可重新点击，键盘仍可用','data':'保留已显示草稿；关闭音频与失败引擎会话','next':'重试或改用键盘','kind':'error'},
 {'id':'S07','action':'手动停止与静音停止几乎同时发生','screen':'只进入一次“最终识别中”','data':'停止动作幂等；不产生两份结果、不重复上屏','next':'按正常final流程继续','kind':'error'},
 {'id':'S08','action':'点“功能”进入二级页','screen':'显示跨设备、输入记录/统计、设置','data':'保存当前输入模式、草稿、组字、光标、generation','next':'点功能或返回'},
 {'id':'S09','action':'二级页中点“跨设备传输”','screen':'查看配对状态，选择本机/设备或管理设备','data':'不直接发送当前文字','next':'返回一级后目标状态清晰可见'},
 {'id':'S10','action':'二级页中点“输入记录/统计”','screen':'显示本机记录数量、字数和历史入口','data':'只读已确认稳定文字；临时草稿不计入','next':'查看后返回'},
 {'id':'S11','action':'二级页中点“设置”','screen':'查看麦克风权限、IME状态和隐私说明','data':'不改变当前草稿；系统设置动作另行明确触发','next':'返回一级'},
 {'id':'S12','action':'在二级页期间切换App或输入框','screen':'返回后不恢复旧草稿到新输入框','data':'旧快照因generation不匹配丢弃','next':'新会话从安全初始状态开始','kind':'error'},
 {'id':'S13','action':'在敏感字段打开输入法','screen':'语音、功能、历史、远端全部禁用；只显示安全键盘','data':'不记录、不学习、不跨设备、不读取旧草稿','next':'离开敏感框后重新建立普通会话','kind':'error'},
]
draw_step_page('05-safety.png','05｜跨App隔离、二级功能与异常恢复','任何失败都必须回到可继续输入的状态；任何旧回调都不能污染新输入框','05 / 05',safety,'验收底线：没有串字、没有永久“识别中”、没有麦克风残留、没有敏感字段越界、没有远端重复发送')

# Contact sheet for quick visual review.
files=['00-operation-atlas-index.png','01-entry.png','02-voice.png','03-keyboard.png','04-output.png','05-safety.png']
thumbs=[]
for f in files:
    im=Image.open(OUT/f).convert('RGB')
    ratio=760/im.width
    thumbs.append((f,im.resize((760,int(im.height*ratio)))))
maxh=max(im.height for _,im in thumbs)
contact=Image.new('RGB',(1600,3*(maxh+90)+40),BG); cd=ImageDraw.Draw(contact)
for i,(name,im) in enumerate(thumbs):
    col=i%2; row=i//2; x=30+col*790; y=30+row*(maxh+90)
    contact.paste(im,(x,y+50)); cd.text((x,y),name,font=F(24,True),fill=NAVY)
contact.save(OUT/'00-atlas-contact-sheet.png',optimize=True)
