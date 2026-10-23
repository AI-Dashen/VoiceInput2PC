package io.github.amxooo.voiceinput2pc;

import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.test.InstrumentationTestCase;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputConnectionWrapper;
import android.widget.EditText;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.UUID;

/** Real production views drawn offscreen, not a bound IME/window or microphone test. */
public final class CompactUiRenderTest extends InstrumentationTestCase {
    private static final class Engine implements ChineseInputEngine {
        String raw="";
        java.util.List<String> values=Arrays.asList("你好","您好","你们");
        public Snapshot snapshot(){return new Snapshot(raw,raw.isEmpty()?Collections.emptyList():values,0,false,true,"");}
        public void input(char c){raw+=c;}
        public void backspace(){if(!raw.isEmpty())raw=raw.substring(0,raw.length()-1);}
        public String select(int index){String value=snapshot().candidates.get(index);reset();return value;}
        public void nextPage(){} public void previousPage(){} public void setLayout(Layout l){}
        public void reset(){raw="";} public void close(){}
    }
    private static final class Ime extends UniversalInputMethodService {
        InputConnection connection; EditorInfo editor; EditText host; View root; int actions; int lastAction; int speechCancels; Engine engine;
        public InputConnection getCurrentInputConnection(){return connection;}
        public EditorInfo getCurrentInputEditorInfo(){return editor;}
    }
    private static Object get(Object o,String name)throws Exception {
        Field f=UniversalInputMethodService.class.getDeclaredField(name);f.setAccessible(true);return f.get(o);
    }
    private static void put(Object o,String name,Object value)throws Exception {
        Field f=UniversalInputMethodService.class.getDeclaredField(name);f.setAccessible(true);f.set(o,value);
    }
    private static void call(Object o,String name,Class<?>[] types,Object...args)throws Exception {
        Method m=UniversalInputMethodService.class.getDeclaredMethod(name,types);m.setAccessible(true);m.invoke(o,args);
    }
    private Ime fixture()throws Exception {
        Context c=getInstrumentation().getTargetContext();Ime s=new Ime();
        Method attach=ContextWrapper.class.getDeclaredMethod("attachBaseContext",Context.class);attach.setAccessible(true);attach.invoke(s,c);
        s.editor=new EditorInfo();s.editor.packageName="compact.fixture";s.editor.fieldId=31;
        s.editor.inputType=1;s.editor.imeOptions=EditorInfo.IME_ACTION_SEND;s.host=new EditText(c);
        s.connection=new InputConnectionWrapper(s.host.onCreateInputConnection(new EditorInfo()),false){
            public boolean performEditorAction(int action){s.actions++;s.lastAction=action;return true;}
        };
        put(s,"prefs",c.getSharedPreferences("compact-fixture-"+UUID.randomUUID(),0));
        s.engine=new Engine();put(s,"keyboard",new KeyboardController(s.engine));
        put(s,"speech",new SpeechInput(){
            public void start(long g,OfflineSpeechRecognizer.Listener l){throw new AssertionError("Render tests must never record");}
            public void finish(){}public void cancel(){s.speechCancels++;}public void close(){}
        });
        s.root=s.onCreateInputView();s.onStartInput(s.editor,false);return s;
    }
    private interface Checked {void run()throws Exception;}
    private void ui(Checked action)throws Throwable {runTestOnUiThread(()->{try{action.run();}catch(Exception e){throw new AssertionError(e);}});}
    private static View described(View v,String description) {
        if(v.getVisibility()!=View.VISIBLE)return null;
        if(description.contentEquals(v.getContentDescription()==null?"":v.getContentDescription()))return v;
        if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int i=0;i<g.getChildCount();i++){
            View found=described(g.getChildAt(i),description);if(found!=null)return found;
        }}
        return null;
    }
    private static TextView text(View v,String label){
        if(v.getVisibility()!=View.VISIBLE)return null;
        if(v instanceof TextView && label.contentEquals(((TextView)v).getText()))return (TextView)v;
        if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int i=0;i<g.getChildCount();i++){TextView found=text(g.getChildAt(i),label);if(found!=null)return found;}}
        return null;
    }
    private int px(float dp){return Math.round(dp*getInstrumentation().getTargetContext().getResources().getDisplayMetrics().density);}
    private void keyboard(Ime s)throws Exception {
        call(s,"openT9Keyboard",new Class[0]);
    }
    private void layout(Ime s){
        DisplayMetrics dm=s.getResources().getDisplayMetrics();
        s.root.measure(View.MeasureSpec.makeMeasureSpec(dm.widthPixels,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(dm.heightPixels,View.MeasureSpec.EXACTLY));
        s.root.layout(0,0,dm.widthPixels,dm.heightPixels);
    }
    private void capture(Ime s,String state)throws Exception {capture(s,state,false);}
    private void phase(String state,String phase) {
        android.util.Log.i("ImeRenderPhase",state+" "+phase+" uptime="+android.os.SystemClock.uptimeMillis());
    }
    private void capture(Ime s,String state,boolean dark)throws Exception {
        DisplayMetrics dm=s.getResources().getDisplayMetrics();
        phase(state,"measure.before");
        s.root.measure(View.MeasureSpec.makeMeasureSpec(dm.widthPixels,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(dm.heightPixels,View.MeasureSpec.EXACTLY));
        phase(state,"measure.after");phase(state,"layout.before");
        s.root.layout(0,0,dm.widthPixels,dm.heightPixels);
        phase(state,"layout.after");phase(state,"bitmap.before");
        Bitmap image=Bitmap.createBitmap(dm.widthPixels,dm.heightPixels,Bitmap.Config.ARGB_8888);
        phase(state,"bitmap.after");
        try {
            Canvas canvas=new Canvas(image);canvas.drawColor(dark?Color.rgb(22,26,33):Color.rgb(241,244,247));Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setColor(Color.WHITE);canvas.drawRect(0,0,dm.widthPixels,px(92),p);
            p.setColor(Color.rgb(37,49,61));p.setTextSize(px(19));canvas.drawText("聊天 · 输入布局",px(20),px(43),p);
            p.setTextSize(px(11));canvas.drawText("原生布局预览 · 非系统绑定截图",px(20),px(69),p);
            p.setTextSize(px(10));canvas.drawText("状态："+state+" · 编辑器/候选为测试数据",px(20),px(87),p);
            p.setColor(Color.WHITE);canvas.drawRoundRect(px(18),px(120),dm.widthPixels-px(54),px(179),px(12),px(12),p);
            p.setColor(Color.rgb(63,76,90));p.setTextSize(px(16));canvas.drawText("今天的安排已经确认。",px(30),px(155),p);
            p.setColor(Color.rgb(203,236,225));canvas.drawRoundRect(px(80),px(203),dm.widthPixels-px(18),px(260),px(12),px(12),p);
            p.setColor(Color.rgb(35,67,56));canvas.drawText("好的，稍后见。",px(94),px(238),p);
            int panel=((View)get(s,"normalRoot")).getVisibility()==View.VISIBLE?((View)get(s,"normalRoot")).getHeight():px(76);
            float inputTop=dm.heightPixels-panel-px(56);
            p.setColor(Color.WHITE);canvas.drawRoundRect(px(14),inputTop,dm.widthPixels-px(14),inputTop+px(44),px(12),px(12),p);
            p.setColor(Color.rgb(119,128,138));p.setTextSize(px(14));canvas.drawText("输入消息…",px(28),inputTop+px(28),p);
            phase(state,"draw.before");s.root.draw(canvas);phase(state,"draw.after");
            phase(state,"directory.before");
            File folder=new File(s.getExternalFilesDir(null),"evidence/compact-ui");assertTrue(folder.isDirectory()||folder.mkdirs());
            phase(state,"directory.after");phase(state,"open.before");
            try(FileOutputStream out=new FileOutputStream(new File(folder,state+".png"))){
                phase(state,"open.after");phase(state,"compress.before");
                assertTrue(image.compress(Bitmap.CompressFormat.PNG,100,out));
                phase(state,"compress.after");phase(state,"close.before");
            }
            phase(state,"close.after");
        } finally {phase(state,"recycle.before");image.recycle();phase(state,"recycle.after");}
    }
    public void testRenderFourProductionStates()throws Throwable {
        ui(()->{Ime s=fixture();capture(s,"default");capture(s,"dark",true);
            assertIcon(s.root,"删除，按住连续删除","backspace");assertIcon(s.root,"发送","send");
            assertTrue(((View)get(s,"dockDelete")).isEnabled());assertTrue(((View)get(s,"dockAction")).isEnabled());
            View mic=(View)get(s,"micIcon");assertEquals(px(48),mic.getWidth());assertEquals(px(48),mic.getHeight());
            assertTrue(mic.performLongClick());assertNotNull(described(s.root,"键盘"));
            assertEquals(View.GONE,((View)get(s,"dockDelete")).getVisibility());
            assertEquals(View.GONE,((View)get(s,"dockAction")).getVisibility());
            assertIcon(s.root,"键盘","keyboard");assertHub(s);capture(s,"menu");capture(s,"expanded");
            described(s.root,"键盘").performClick();capture(s,"keyboard");
            call(s,"switchMode",new Class[]{KeyboardController.Mode.class},KeyboardController.Mode.PINYIN);
            int idle=((View)get(s,"normalRoot")).getHeight();
            assertEquals(View.INVISIBLE,((View)get(s,"composition")).getVisibility());
            assertEquals(View.INVISIBLE,((View)get(s,"candidateScroller")).getVisibility());
            int base=s.getResources().getConfiguration().orientation==android.content.res.Configuration.ORIENTATION_LANDSCAPE?236:268;
            assertTrue("compact idle height: "+idle,Math.abs(idle-px(base))<=px(8));
            call(s,"type",new Class[]{String.class},"n");call(s,"type",new Class[]{String.class},"i");
            capture(s,"composing");assertEquals(View.VISIBLE,((View)get(s,"candidateScroller")).getVisibility());
            int composing=((View)get(s,"normalRoot")).getHeight();assertTrue("compact composing height: "+composing,Math.abs(composing-px(base))<=px(8));
            assertEquals("candidate appearance must not resize the keyboard",idle,composing);
            call(s,"selectCandidate",new Class[]{int.class},0);layout(s);
            assertEquals("candidate commit must not resize the keyboard",idle,((View)get(s,"normalRoot")).getHeight());
            assertEquals(View.INVISIBLE,((View)get(s,"composition")).getVisibility());
            assertEquals(View.INVISIBLE,((View)get(s,"candidateScroller")).getVisibility());
        });
    }
    public void testLongPinyinPreeditStaysInsideItsSingleLineViewport()throws Throwable {
        ui(()->{Ime s=fixture();
            call(s,"switchMode",new Class[]{KeyboardController.Mode.class},KeyboardController.Mode.PINYIN);
            String longPinyin="zhonghuarenmingongheguowansuinimenhaoshijie";
            for(int i=0;i<longPinyin.length();i++)
                call(s,"type",new Class[]{String.class},String.valueOf(longPinyin.charAt(i)));
            layout(s);
            TextView compose=(TextView)get(s,"composition");
            assertEquals(View.VISIBLE,compose.getVisibility());
            assertTrue(compose.isSingleLine());
            assertEquals(android.text.TextUtils.TruncateAt.START,compose.getEllipsize());
            assertEquals("preedit uses the declared compact height",px(ImeLayoutSpec.COMPOSITION_HEIGHT_DP),compose.getHeight());
            assertNotNull(compose.getLayout());
            assertEquals("preedit must never wrap",1,compose.getLayout().getLineCount());
            assertTrue("preedit ink remains inside content width",
                    compose.getLayout().getLineWidth(0)<=compose.getWidth()-compose.getCompoundPaddingLeft()-compose.getCompoundPaddingRight()+1);
        });
    }
    private void assertIcon(View root,String description,String icon) {
        View view=described(root,description);assertNotNull("accessible icon "+description,view);
        assertTrue(view instanceof ImeIconButton);
        assertEquals(icon,((ImeIconButton)view).getIcon());
        assertEquals("icon must not draw a text label","",((TextView)view).getText().toString());
    }
    public void testIconMenuTargetAndLocalCallbacks()throws Throwable {
        ui(()->{Ime s=fixture();openMore(s);assertHub(s);
            assertTrue(described(s.root,"目标设备").performClick());
            ImePanelController panels=(ImePanelController)get(s,"panels");
            assertEquals(ImePanelController.Feature.TRANSFER,panels.feature());
            View close=text(s.root,"收起 · 选择目标");assertNotNull(close);assertTrue(close.performClick());
            assertTrue(panels.isPrimary());openMore(s);
            assertTrue(described(s.root,"统计").performClick());
            assertEquals(ImePanelController.Feature.STATISTICS,panels.feature());
            assertTrue(described(s.root,"返回输入键盘，保留当前输入状态").performClick());
            openMore(s);assertTrue(described(s.root,"本机").performClick());
            assertTrue(panels.isPrimary());assertFalse(((OutputTargetController)get(s,"targets")).isRemote());
            assertEquals(0,s.actions);
        });
    }
    private void openMore(Ime s)throws Exception {
        assertTrue(((View)get(s,"micIcon")).performLongClick());
        assertEquals(ImePanelController.Feature.HUB,((ImePanelController)get(s,"panels")).feature());
    }
    private void assertHub(Ime s)throws Exception {
        ImePanelController panels=(ImePanelController)get(s,"panels");
        assertEquals(ImePanelController.Level.SECONDARY,panels.level());
        assertEquals(ImePanelController.Feature.HUB,panels.feature());
        String[] descriptions={"目标设备","历史","统计","设置","切换输入法","本机"};
        String[] icons={"device","history","stats","settings","switch","local"};
        for(int i=0;i<descriptions.length;i++)assertIcon(s.root,descriptions[i],icons[i]);
        assertTrue(described(s.root,"本机").isSelected());
        assertFalse(described(s.root,"目标设备").isSelected());
    }
    public void testMoreMenuWrapMeasurementAndSettingsReturn()throws Throwable {
        ui(()->{Ime s=fixture();openMore(s);assertHub(s);
            DisplayMetrics dm=s.getResources().getDisplayMetrics();
            s.root.measure(View.MeasureSpec.makeMeasureSpec(dm.widthPixels,View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(dm.heightPixels,View.MeasureSpec.AT_MOST));
            s.root.layout(0,0,s.root.getMeasuredWidth(),s.root.getMeasuredHeight());
            assertEquals("menu minimum height",px(280),s.root.getMinimumHeight());
            assertTrue("wrap root reserves menu space",s.root.getMeasuredHeight()>=Math.min(px(280),dm.heightPixels));
            View menu=(View)get(s,"secondaryPanel");assertEquals(View.VISIBLE,menu.getVisibility());
            assertTrue("menu top inside wrap root",menu.getTop()>=0);
            assertTrue("menu bottom inside wrap root",menu.getBottom()<=s.root.getHeight());
            assertEquals(px(174),menu.getHeight());assertEquals(px(176),menu.getWidth());
            ViewGroup grid=(ViewGroup)menu;assertEquals(3,grid.getChildCount());
            assertEquals(3,((ViewGroup)grid.getChildAt(0)).getChildCount());
            assertEquals(3,((ViewGroup)grid.getChildAt(1)).getChildCount());
            capture(s,"more");
            assertTrue(described(s.root,"设置").performClick());
            ImePanelController panels=(ImePanelController)get(s,"panels");
            assertEquals(ImePanelController.Feature.SETTINGS,panels.feature());
            assertNotNull(text(s.root,"输入法设置"));
            View back=described(s.root,"返回输入键盘，保留当前输入状态");assertNotNull(back);assertTrue(back.performClick());
            assertEquals(ImePanelController.Level.PRIMARY,panels.level());
            assertEquals(View.VISIBLE,((View)get(s,"floatingDock")).getVisibility());
            assertEquals(0,s.actions);
        });
    }
    private String preferredTextModeLabel(Ime service)throws Exception {
        return get(service,"preferredTextMode")==KeyboardController.Mode.T9?"九键":"26键";
    }
    public void testMoreWhileListeningPreservesTextWithoutMicrophone()throws Throwable {
        ui(()->{Ime s=fixture();InputSession session=(InputSession)get(s,"session");long old=session.generation();
            assertTrue(session.startListening(old));
            // State/partial fixture only: SpeechInput.start still throws if any code tries to record.
            assertTrue(s.connection.setComposingText("已经听到的文字",1));
            put(s,"activeComposing","已经听到的文字");put(s,"inlineVoiceText","已经听到的文字");
            call(s,"render",new Class[0]);
            int cancels=s.speechCancels;openMore(s);assertHub(s);
            assertTrue("producer cancelled",s.speechCancels>cancels);
            assertTrue("old generation invalidated",session.generation()!=old);
            assertEquals("已经听到的文字",s.host.getText().toString());
            assertFalse(session.state()==InputSession.State.LISTENING);
            assertEquals(0,s.actions);
        });
    }
    public void testKeyboardDigitsDeleteAndEditorAction()throws Throwable {
        ui(()->{Ime s=fixture();keyboard(s);ViewGroup rows=(ViewGroup)get(s,"keys");
            assertEquals("three T9 rows and one bottom action row",4,rows.getChildCount());
            for(int i=0;i<3;i++)assertEquals(3,((ViewGroup)rows.getChildAt(i)).getChildCount());
            View bottom=rows.getChildAt(3);
            for(String label:new String[]{"123","空格","26键","EN"})assertNotNull("bottom "+label,text(bottom,label));
            assertSame(bottom,((View)get(s,"symbolModeKey")).getParent());
            assertSame(get(s,"keyboardActionRail"),((View)get(s,"enterKey")).getParent());
            for(String label:new String[]{"分词","ABC","DEF","GHI","JKL","MNO","PQRS","TUV","WXYZ"})
                assertNotNull("T9 key "+label,text(rows,label));
            assertNull("obsolete retry key removed",text((View)get(s,"normalRoot"),"重输"));
            ViewGroup left=(ViewGroup)get(s,"syllables");
            for(String label:new String[]{"，","。","？","！","符号"})assertNotNull("left rail "+label,text(left,label));
            ViewGroup rail=(ViewGroup)get(s,"keyboardActionRail");
            assertEquals(3,rail.getChildCount());
            assertSame(get(s,"deleteKey"),rail.getChildAt(0));
            assertSame(get(s,"voice"),rail.getChildAt(1));
            assertSame(get(s,"enterKey"),rail.getChildAt(2));
            assertEquals("mic",((ImeIconButton)rail.getChildAt(1)).getIcon());
            View delete=(View)get(s,"deleteKey");delete.performClick();
            ((View)get(s,"enterKey")).performClick();assertEquals(1,s.actions);assertEquals(EditorInfo.IME_ACTION_SEND,s.lastAction);
            assertNull(text((View)get(s,"normalRoot"),"本机"));assertNull(text((View)get(s,"normalRoot"),"功能"));
        });
    }
    public void testRightRailOrderAndNarrowKeyboardFit()throws Throwable {
        ui(()->{Ime s=fixture();
            for(int widthDp:new int[]{320,360,412}) {
                for(KeyboardController.Mode mode:new KeyboardController.Mode[]{KeyboardController.Mode.T9,
                        KeyboardController.Mode.PINYIN,KeyboardController.Mode.ENGLISH,
                        KeyboardController.Mode.NUMBERS,KeyboardController.Mode.SYMBOLS}) {
                    call(s,"switchMode",new Class[]{KeyboardController.Mode.class},mode);
                    s.root.measure(View.MeasureSpec.makeMeasureSpec(px(widthDp),View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(px(844),View.MeasureSpec.AT_MOST));
                    s.root.layout(0,0,s.root.getMeasuredWidth(),s.root.getMeasuredHeight());
                    ViewGroup rail=(ViewGroup)get(s,"keyboardActionRail"),rows=(ViewGroup)get(s,"keys");
                    assertEquals(3,rail.getChildCount());
                    int expectedRows=mode==KeyboardController.Mode.NUMBERS?5:4;
                    assertEquals(expectedRows,rows.getChildCount());
                    assertEquals(px(ImeLayoutSpec.ACTION_RAIL_WIDTH_DP),rail.getWidth());
                    assertTrue("right column follows the keys",rail.getLeft()>=rows.getRight());
                    assertSame(get(s,"deleteKey"),rail.getChildAt(0));
                    assertSame(get(s,"voice"),rail.getChildAt(1));
                    assertSame(get(s,"enterKey"),rail.getChildAt(2));
                    assertEquals("backspace",((ImeIconButton)rail.getChildAt(0)).getIcon());
                    assertEquals("mic",((ImeIconButton)rail.getChildAt(1)).getIcon());
                    assertEquals("send",((ImeIconButton)rail.getChildAt(2)).getIcon());
                    for(int i=0;i<3;i++)assertTrue("rail key is a usable target",rail.getChildAt(i).getHeight()>=px(44));
                    if(mode==KeyboardController.Mode.T9) {
                        assertNotNull(text(rows,"ABC"));assertNotNull(text(rows,"WXYZ"));assertNotNull(text(rows,"123"));
                    } else if(mode==KeyboardController.Mode.PINYIN || mode==KeyboardController.Mode.ENGLISH) {
                        ViewGroup top=(ViewGroup)rows.getChildAt(0);assertEquals(10,top.getChildCount());
                        for(int i=0;i<10;i++) {
                            TextView letter=(TextView)top.getChildAt(i);
                            assertTrue("letter fits narrow keyboard: "+letter.getText(),letter.getPaint().measureText(letter.getText().toString())<=letter.getWidth()+1);
                        }
                    } else {
                        assertNotNull("numeric/symbol view has direct Chinese return",
                                text(rows,preferredTextModeLabel(s)));
                        if(mode==KeyboardController.Mode.NUMBERS) {
                            for(int row=0;row<4;row++)assertEquals(3,((ViewGroup)rows.getChildAt(row)).getChildCount());
                            for(String digit:new String[]{"1","2","3","4","5","6","7","8","9","0"})
                                assertNotNull("number pad digit "+digit,text(rows,digit));
                        }
                    }
                }
            }
        });
    }
    public void testTransparentMicHasNoCenterPlateOnEitherHostBackground()throws Throwable {
        ui(()->{Ime s=fixture();View mic=(View)get(s,"micIcon");
            mic.measure(View.MeasureSpec.makeMeasureSpec(px(48),View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(px(48),View.MeasureSpec.EXACTLY));
            mic.layout(0,0,px(48),px(48));
            for(InputSession.State state:new InputSession.State[]{InputSession.State.IDLE,InputSession.State.LISTENING,InputSession.State.RECOGNIZING}) {
                InputSession session=(InputSession)get(s,"session");
                if(state==InputSession.State.LISTENING)assertTrue(session.startListening(session.generation()));
                if(state==InputSession.State.RECOGNIZING)assertTrue(session.startRecognizing(session.generation()));
                Bitmap image=Bitmap.createBitmap(px(48),px(48),Bitmap.Config.ARGB_8888);
                try {
                    mic.draw(new Canvas(image));
                    assertEquals("center plate is transparent for "+state,0,Color.alpha(image.getPixel(px(35),px(24))));
                    int ringPixels=0;
                    for(int y=px(22);y<px(26);y++)for(int x=px(39);x<px(44);x++)if(Color.alpha(image.getPixel(x,y))>200)ringPixels++;
                    assertTrue("status ring stays visible for "+state,ringPixels>0);
                } finally {image.recycle();}
            }
        });
    }
    public void testAllFloatingActionsHaveTransparentCentersAndVisibleLeafRings()throws Throwable {
        ui(()->{
            Context context=getInstrumentation().getTargetContext();
            for(String icon:new String[]{"backspace","enter","send","device","history","settings","switch"}) {
                ImeIconButton button=new ImeIconButton(context,icon,icon);button.setLeafRing(true);
                button.measure(View.MeasureSpec.makeMeasureSpec(px(48),View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(px(48),View.MeasureSpec.EXACTLY));
                button.layout(0,0,px(48),px(48));
                Bitmap image=Bitmap.createBitmap(px(48),px(48),Bitmap.Config.ARGB_8888);
                try {
                    button.draw(new Canvas(image));
                    assertEquals("no opaque plate for "+icon,0,Color.alpha(image.getPixel(px(35),px(24))));
                    int ringPixels=0;
                    for(int y=px(22);y<px(26);y++)for(int x=px(39);x<px(44);x++)if(Color.alpha(image.getPixel(x,y))>200)ringPixels++;
                    assertTrue("leaf ring for "+icon,ringPixels>0);
                } finally {image.recycle();}
            }
        });
    }
    /** Verify real production updateCandidateViews reuses TextView instances when candidate count stays same. */
    public void testCandidateViewReuseOnTyping()throws Throwable {
        ui(()->{
            Ime s=fixture();keyboard(s);
            call(s,"switchMode",new Class[]{KeyboardController.Mode.class},KeyboardController.Mode.PINYIN);
            ViewGroup candidateContainer=(ViewGroup)get(s,"candidates");

            // Type "ni" to trigger first candidate render
            call(s,"type",new Class[]{String.class},"n");
            call(s,"type",new Class[]{String.class},"i");
            layout(s);

            // Fixture engine returns ["你好","您好","你们"] for non-empty input
            assertEquals("initial candidate count with nav",5,candidateContainer.getChildCount());
            TextView firstCandidate1=(TextView)candidateContainer.getChildAt(1);
            TextView firstCandidate2=(TextView)candidateContainer.getChildAt(2);
            TextView firstCandidate3=(TextView)candidateContainer.getChildAt(3);
            assertEquals("你好",firstCandidate1.getText().toString());
            assertEquals("您好",firstCandidate2.getText().toString());
            assertEquals("你们",firstCandidate3.getText().toString());

            // Return changed text with the same number of candidates.
            s.engine.values=Arrays.asList("泥","你","尼");
            call(s,"render",new Class[0]);
            layout(s);

            // Verify view instances were reused (same count)
            assertEquals("candidate count still 5",5,candidateContainer.getChildCount());
            TextView secondCandidate1=(TextView)candidateContainer.getChildAt(1);
            TextView secondCandidate2=(TextView)candidateContainer.getChildAt(2);
            TextView secondCandidate3=(TextView)candidateContainer.getChildAt(3);

            assertSame("first candidate TextView reused",firstCandidate1,secondCandidate1);
            assertSame("second candidate TextView reused",firstCandidate2,secondCandidate2);
            assertSame("third candidate TextView reused",firstCandidate3,secondCandidate3);

            assertEquals("泥",secondCandidate1.getText().toString());
            assertEquals("你",secondCandidate2.getText().toString());
            assertEquals("尼",secondCandidate3.getText().toString());

            // Verify the updated listener index selects the updated second candidate.
            assertTrue(secondCandidate2.performClick());
            assertEquals("你",s.host.getText().toString());

            // A smaller next lookup result changes child count and rebuilds as needed.
            s.engine.values=Collections.singletonList("新候选");
            call(s,"type",new Class[]{String.class},"x");
            layout(s);
            assertEquals("one candidate plus two navigation controls",3,candidateContainer.getChildCount());
            TextView thirdCandidate1=(TextView)candidateContainer.getChildAt(1);
            assertNotSame("new instance after candidate-count change",firstCandidate1,thirdCandidate1);
            assertEquals("新候选",thirdCandidate1.getText().toString());
        });
    }
}
