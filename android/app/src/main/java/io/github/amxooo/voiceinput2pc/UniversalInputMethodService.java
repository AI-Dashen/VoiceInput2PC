package io.github.amxooo.voiceinput2pc;

import android.animation.ValueAnimator;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import java.util.UUID;
import android.graphics.Color;
import android.widget.FrameLayout;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.inputmethodservice.InputMethodService;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Local IME. Every stable insertion is explicitly confirmed in the current editor generation. */
public class UniversalInputMethodService extends InputMethodService
        implements OfflineSpeechRecognizer.Listener {
    static final int ACCESSIBILITY_ACTION_OPEN_T9 = 0x01020001;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final InputSession session = new InputSession();
    private final ImePanelController panels = new ImePanelController();
    private final OutputTargetController targets = new OutputTargetController();
    private KeyboardController keyboard;
    private SpeechInput speech;
    private RemoteTextSender remoteSender;
    private SharedPreferences prefs;
    private PairingConfig activePairing;
    private InputHistoryStore history;
    private final String remoteHistorySession=UUID.randomUUID().toString();
    private TextView status, composition, remoteDraft, targetHeader;
    private EditText preview;
    private HorizontalScrollView candidateScroller;
    private android.widget.ScrollView syllableScroller;
    private LinearLayout syllables, keyboardBody, keyboardActionRail;
    private LinearLayout keys, candidates, voiceActions, inputPanel, secondaryPanel;
    private LinearLayout primaryPanel, keyboardUtilities;
    private TextView voice, confirm, function, targetKey, enterKey, pinyinModeKey, t9ModeKey, symbolModeKey;
    private int lastSelectionStart = -1, lastSelectionEnd = -1;
    private int keyboardAnchor = -1, keyboardExpectedCursor = -1;
    private boolean keyboardWriting;
    private String lastRenderedPreedit = "";
    private java.util.List<String> lastCandidateList = java.util.Collections.emptyList();
    private java.util.List<Integer> lastCandidateSourceIndices = java.util.Collections.emptyList();
    private int lastCandidatePage = -1;
    private boolean lastCandidateHasNext = false;
    private boolean secondaryBackDown;
    private boolean sensitiveField;
    private boolean voiceMode = true;
    private boolean updatingPreview;
    private int draftSelectionStart, draftSelectionEnd;
    private long voiceSamples;
    private boolean speechDetected;
    private boolean audioCaptureReady;
    private long previewCommitReadyAt;
    private String localHistorySession=UUID.randomUUID().toString();
    private KeyboardController.Mode preferredTextMode = KeyboardController.Mode.T9;
    private String message = "";
    private String activeComposing = "";
    private String suspendedPreview = "";
    private String voiceDraftPrefix = "";
    private String voiceDraftSuffix = "";
    private TextView deleteKey;
    private boolean deleteHeld, deleteRepeated;
    private final Runnable repeatDelete = new Runnable() {
        @Override public void run() {
            if (!deleteHeld || destroyed) return;
            deleteRepeated=true;backspace();main.postDelayed(this,80);
        }
    };
    private void stopDeleteRepeat() {
        deleteHeld=false;main.removeCallbacks(repeatDelete);
    }
    private volatile boolean destroyed;
    private FrameLayout dockRoot;
    private LinearLayout floatingDock, normalRoot;
    private TextView dockVoice,dockTarget,dockStatus,dockSend,remoteDockPreview;
    private ImeIconButton dockAction, dockDelete;
    private float dockXFraction=1f, dockYFraction=.65f;
    private float dragRawX, dragRawY, dragCenterX, dragCenterY;
    private boolean dockDragging, dockLongPressed, dockGestureBlocked, punctuationSelecting;
    private LinearLayout punctuationBar;
    private int punctuationIndex=-1;
    private final String[] punctuationValues={"，","。","？","！","⋯"};
    private long gestureBinding, gestureGeneration, tapBinding;
    private String gestureTarget="",tapTarget="";
    private long bindingRevision, editorContextRevision;
    private int beforeCursorLength=-1;
    private boolean contextSelectionFresh=true;
    private boolean contextProbeRunning;
    private java.util.concurrent.ExecutorService contextExecutor;
    private Runnable pendingSingleTap;
    private long lastTapAt;
    private float lastTapX,lastTapY;
    private boolean secondTap;
    private final Runnable contextProbe=() -> probeEditorContext();
    private MicView micIcon;
    private FrameLayout radialMenu;
    private final java.util.ArrayList<ImeIconButton> radialMenuItems=new java.util.ArrayList<>();
    private final Runnable dockLongPress = () -> {
        // Long-press only arms the gesture. Releasing while stationary opens the
        // menu; moving after arming preserves the established mic-drag behavior.
        if(micIcon!=null && micIcon.isPressed() && !dockDragging && !busy()) {
            dockLongPressed=true;micIcon.setPressed(false);
            micIcon.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
        }
    };
    private String inlineVoiceText="";
    private int voiceAnchor=-1,voiceExpected=-1;
    private boolean voiceWriting;
    private boolean unsafeRetry;
    private boolean editorBound;
    private boolean cursorUpdateAccepted, cursorAnchorReceived;
    private int cursorAnchorFlags;
    private float cursorAnchorTop=Float.NaN, cursorAnchorBottom=Float.NaN;
    private EditorReadinessPolicy.State editorReadiness() {
        EditorInfo info=getCurrentInputEditorInfo();
        return EditorReadinessPolicy.evaluate(editorBound,info!=null,
                info!=null && fieldKey(info).equals(session.identity().targetId),getCurrentInputConnection()!=null);
    }
    private void refreshEditorControls() {
        if(dockAction==null || dockDelete==null)return;
        boolean connected=editorReadiness()==EditorReadinessPolicy.State.READY;
        boolean targetReady=targets.isRemote() ? editorBound : connected;
        boolean deletable=hasDeletableContext();
        dockDelete.setEnabled(targetReady && !busy() && deletable);
        dockDelete.setAlpha(dockDelete.isEnabled()?1f:.4f);
        dockAction.setEnabled(targetReady && CompactImeUiPolicy.canRunEditorAction(session.state(),targets.isRemote(),targets.canSend()));
        dockAction.setAlpha(dockAction.isEnabled()?1f:.4f);
        if(showVoiceEditingActions()) {
            int visibility=deletable && !punctuationSelecting?View.VISIBLE:View.INVISIBLE;
            if(dockDelete.getVisibility()!=visibility) {
                dockDelete.setVisibility(visibility);if(dockRoot!=null)dockRoot.requestApplyInsets();
            }
        }
    }
    private boolean hasDeletableContext() {
        if(targets.isRemote())return !targets.pendingText().isEmpty();
        if(session.state()==InputSession.State.PREVIEW)return !session.preview().isEmpty();
        return EditorActionContextPolicy.evaluate(contextSelectionFresh?lastSelectionStart:-1,contextSelectionFresh?lastSelectionEnd:-1,-1,beforeCursorLength)
                !=EditorActionContextPolicy.DeleteContext.AT_START;
    }
    private void editorContentChanged() {
        contextSelectionFresh=false;scheduleEditorContextProbe();refreshEditorControls();
    }
    /** Debounced one-character query off the UI thread. No host text is retained. */
    private void scheduleEditorContextProbe() {
        beforeCursorLength=-1;editorContextRevision++;
        main.removeCallbacks(contextProbe);
        if(!destroyed && editorBound && !sensitiveField && !targets.isRemote())main.postDelayed(contextProbe,120);
    }
    private void probeEditorContext() {
        if(contextProbeRunning || destroyed || sensitiveField || targets.isRemote() || !editorBound)return;
        final InputConnection connection=getCurrentInputConnection();
        if(connection==null)return;
        final long revision=editorContextRevision,binding=bindingRevision;
        if(contextExecutor==null)contextExecutor=java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
            Thread t=new Thread(r,"ime-editor-context");t.setDaemon(true);return t;
        });
        contextProbeRunning=true;
        contextExecutor.execute(() -> {
            int length=-1;
            try {CharSequence before=connection.getTextBeforeCursor(1,0);length=before==null?-1:before.length();}
            catch(RuntimeException ignored) {}
            final int observed=length;
            main.post(() -> {
                contextProbeRunning=false;
                if(destroyed || !editorBound)return;
                if(binding!=bindingRevision || revision!=editorContextRevision){main.removeCallbacks(contextProbe);main.postDelayed(contextProbe,120);return;}
                beforeCursorLength=observed;refreshEditorControls();
            });
        });
    }
    private void requestEditorCursorUpdates() {
        cursorUpdateAccepted=false;cursorAnchorReceived=false;cursorAnchorFlags=0;
        cursorAnchorTop=cursorAnchorBottom=Float.NaN;
        InputConnection connection=getCurrentInputConnection();
        if(sensitiveField || !editorBound || connection==null)return;
        try {cursorUpdateAccepted=connection.requestCursorUpdates(InputConnection.CURSOR_UPDATE_IMMEDIATE|InputConnection.CURSOR_UPDATE_MONITOR);}
        catch(RuntimeException ignored) {cursorUpdateAccepted=false;}
    }
    private String editingStateSummary() {
        String cursor="UNKNOWN";
        if(cursorAnchorReceived) {
            if((cursorAnchorFlags & android.view.inputmethod.CursorAnchorInfo.FLAG_HAS_VISIBLE_REGION)!=0)cursor="HOST_REPORTED_VISIBLE";
            else if((cursorAnchorFlags & android.view.inputmethod.CursorAnchorInfo.FLAG_HAS_INVISIBLE_REGION)!=0)cursor="HOST_REPORTED_HIDDEN";
        }
        return "editor="+editorReadiness()+",selection="+(lastSelectionStart>=0 && lastSelectionEnd>=0?"REPORTED":"UNKNOWN")
                +",cursor="+cursor+",cursor_updates="+(cursorUpdateAccepted?"ACCEPTED":"UNAVAILABLE");
    }
    @Override public void onUpdateCursorAnchorInfo(android.view.inputmethod.CursorAnchorInfo info) {
        super.onUpdateCursorAnchorInfo(info);
        if(!editorBound || sensitiveField || info==null)return;
        cursorAnchorTop=info.getInsertionMarkerTop();cursorAnchorBottom=info.getInsertionMarkerBottom();
        cursorAnchorReceived=Float.isFinite(cursorAnchorTop) && Float.isFinite(cursorAnchorBottom);
        cursorAnchorFlags=cursorAnchorReceived?info.getInsertionMarkerFlags():0;
        refreshEditorControls();
    }
    @Override public void onUnbindInput() {
        if(speech!=null && busy())speech.cancel();
        session.invalidate();
        cancelDockGesture();bindingRevision++;editorContextRevision++;beforeCursorLength=-1;contextSelectionFresh=true;main.removeCallbacks(contextProbe);
        editorBound=false;cursorAnchorReceived=false;cursorUpdateAccepted=false;
        refreshEditorControls();super.onUnbindInput();
    }
    private String voiceTarget="";
    private String currentTargetKey(){return targets.isRemote() ? targets.deviceId() : "LOCAL";}


    @Override public void onCreate() {
        super.onCreate();
        prefs=getSharedPreferences("voiceinput2pc",MODE_PRIVATE);
        keyboard = new KeyboardController(new LoadingChineseEngine());
        speech = new OfflineSpeechRecognizer(this);
        remoteSender=new RemoteTextSender(this,prefs);
        history=new InputHistoryStore(this);
        restoreRemoteState();
        refreshPairing();
        Thread deploy = new Thread(() -> {
            ChineseInputEngine ready;
            try { ready = new RimeChineseInputEngine(getApplicationContext()); }
            catch (Throwable failure) { ready = new FailedChineseEngine(failure); }
            final ChineseInputEngine result=ready;
            main.post(() -> {
                installReadyKeyboard(result);
            });
        }, "ime-rime-deploy");
        deploy.start();
    }

    private void installReadyKeyboard(ChineseInputEngine engine) {
        if (destroyed) { engine.close(); return; }
        KeyboardController.Mode mode=keyboard.mode();
        keyboard.close();keyboard=new KeyboardController(engine);
        EditorInfo info=getCurrentInputEditorInfo();
        keyboard.setLearningEnabled(info != null && !sensitiveField
                && (info.imeOptions & EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) == 0);
        keyboard.setMode(mode);render();
    }

    @Override public View onCreateInputView() {
        syncVoiceDraft();
        // Android may ask one InputMethodService instance to recreate its view.
        // Never retain children owned by the previous hierarchy.
        if(micIcon!=null)micIcon.stopRingAnimation();
        radialMenuItems.clear();
        LinearLayout root = column();normalRoot=root;
        dockRoot=new FrameLayout(this);dockRoot.setBackgroundColor(Color.TRANSPARENT);
        root.setPadding(dp(2), dp(2), dp(2), dp(3));
        root.setBackgroundColor(Color.rgb(246,249,250));
        primaryPanel = column();
        primaryPanel.setGravity(Gravity.TOP);
        primaryPanel.setBackground(surface(Color.rgb(248,250,252),8));

        LinearLayout left = row();
        left.setPadding(dp(1),0,dp(1),0);
        voice = railButton(left,"语音",v -> selectVoiceMode());
        voice.setOnLongClickListener(v -> { cancelVoice(); return true; });
        t9ModeKey = railButton(left,"中/EN",v -> switchLanguage());
        pinyinModeKey=t9ModeKey;
        symbolModeKey = railButton(left,"符号",v -> switchMode(keyboard.mode()==KeyboardController.Mode.SYMBOLS
                ? preferredTextMode : KeyboardController.Mode.SYMBOLS));
        railButton(left,"其他输入法",v -> ((android.view.inputmethod.InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).showInputMethodPicker());
        symbolModeKey.setOnLongClickListener(v -> { switchMode(KeyboardController.Mode.NUMBERS); return true; });
        // Detached compatibility toolbar.

        inputPanel = column();
        inputPanel.setPadding(dp(3),0,dp(3),0);
        status = label(12); status.setGravity(Gravity.CENTER);
        status.setTextColor(Color.rgb(94,105,116));
        status.setPadding(dp(7),dp(2),dp(7),dp(2));
        inputPanel.addView(status,new LinearLayout.LayoutParams(-1,dp(ImeLayoutSpec.STATUS_HEIGHT_DP)));
        composition = label(ImeLayoutSpec.COMPOSITION_TEXT_MAX_SP);
        composition.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        composition.setGravity(Gravity.CENTER_VERTICAL);
        composition.setPadding(dp(7),0,dp(7),0);
        composition.setIncludeFontPadding(false);
        composition.setSingleLine(true);
        composition.setMaxLines(1);
        composition.setHorizontallyScrolling(false);
        composition.setEllipsize(android.text.TextUtils.TruncateAt.START);
        composition.setTextDirection(View.TEXT_DIRECTION_LTR);
        composition.setAutoSizeTextTypeUniformWithConfiguration(
                ImeLayoutSpec.COMPOSITION_TEXT_MIN_SP,
                ImeLayoutSpec.COMPOSITION_TEXT_MAX_SP,
                1,android.util.TypedValue.COMPLEX_UNIT_SP);
        inputPanel.addView(composition,new LinearLayout.LayoutParams(-1,dp(ImeLayoutSpec.COMPOSITION_HEIGHT_DP)));
        candidateScroller = new HorizontalScrollView(this);
        candidateScroller.setHorizontalScrollBarEnabled(false);
        candidates = row(); candidateScroller.addView(candidates);
        inputPanel.addView(candidateScroller,new LinearLayout.LayoutParams(-1,dp(28)));
        remoteDraft=label(16); remoteDraft.setBackground(fill(Color.WHITE,7));
        remoteDraft.setPadding(dp(8),dp(4),dp(8),dp(4)); remoteDraft.setMinLines(1); remoteDraft.setMaxLines(2);
        remoteDraft.setContentDescription("跨设备待发送文字"); inputPanel.addView(remoteDraft,new LinearLayout.LayoutParams(-1,dp(48)));
        preview = new EditText(this); preview.setTextSize(18);
        preview.setTextColor(Color.rgb(26,37,48)); preview.setBackground(fill(Color.WHITE,7));
        preview.setMinLines(3); preview.setMaxLines(5); preview.setVerticalScrollBarEnabled(true);
        preview.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS); preview.setShowSoftInputOnFocus(false);
        preview.setPadding(dp(8),dp(6),dp(8),dp(6));
        preview.setContentDescription("语音识别预览，可修改后确认输入"); inputPanel.addView(preview);
        preview.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence value,int start,int count,int after) {}
            public void onTextChanged(CharSequence value,int start,int before,int count) {
                if (!updatingPreview && session.state() == InputSession.State.PREVIEW) {
                    suspendedPreview=value.toString();
                    session.updatePreview(session.generation(),suspendedPreview);
                }
            }
            public void afterTextChanged(android.text.Editable value) {}
        });
        voiceActions = row();
        confirm = addButton(voiceActions,"确认输入",v -> confirmVoice(),2);
        addButton(voiceActions,"取消",v -> cancelVoice(),1);
        inputPanel.addView(voiceActions,new LinearLayout.LayoutParams(-1,dp(ImeLayoutSpec.ACTION_HEIGHT_DP)));
        keyboardBody=row();
        syllableScroller=new android.widget.ScrollView(this);
        syllableScroller.setVerticalScrollBarEnabled(false);
        syllableScroller.setFillViewport(true);
        syllables=column();syllableScroller.addView(syllables);
        keyboardBody.addView(syllableScroller,new LinearLayout.LayoutParams(dp(44),-1));
        keys=column();keyboardBody.addView(keys,new LinearLayout.LayoutParams(0,-1,1));
        keyboardActionRail=column();
        keyboardActionRail.setContentDescription("右侧单手操作栏");
        keyboardBody.addView(keyboardActionRail,new LinearLayout.LayoutParams(dp(ImeLayoutSpec.ACTION_RAIL_WIDTH_DP),-1));
        inputPanel.addView(keyboardBody,new LinearLayout.LayoutParams(-1,0,1));
        keyboardUtilities=row();
        addButton(keyboardUtilities,"←",v -> moveCursor(-1),1);
        addButton(keyboardUtilities,"空格",v -> space(),3);
        addButton(keyboardUtilities,"→",v -> moveCursor(1),1);
        // Space lives in the keyboard bottom row.
        primaryPanel.addView(inputPanel,new LinearLayout.LayoutParams(-1,0,1));

        LinearLayout right = row();
        right.setPadding(dp(1),0,dp(1),0);
        deleteKey=railButton(right,"删除",v -> backspace());
        bindDelete(deleteKey);
        targetKey=railButton(right,"本机",v -> toggleOutputTarget());
        targetKey.setOnLongClickListener(v -> { openTransferWorkspace(); return true; });
        function=railButton(right,"功能",v -> toggleFeaturePanel()); function.setId(4101);
        enterKey=railButton(right,"回车",v -> enter());
        rebuildKeys(); // Detached compatibility controls.
        int panelHeight=dp(CompactImeUiPolicy.keyboardHeightDp(getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE,false,false));
        root.addView(primaryPanel,new LinearLayout.LayoutParams(-1,panelHeight));

        secondaryPanel = column();
        secondaryPanel.setPadding(dp(6),dp(6),dp(6),dp(6));
        secondaryPanel.setBackgroundColor(Color.rgb(246,249,250));
        root.addView(secondaryPanel,new LinearLayout.LayoutParams(-1,panelHeight));
        dockRoot.addView(root,new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM));
        floatingDock=column();floatingDock.setGravity(Gravity.CENTER);
        dockDelete=compactControl("backspace","删除",v -> backspace());bindDelete(dockDelete);
        micIcon=new MicView();
        micIcon.setOnClickListener(v -> {if(compactMenu())panels.returnPrimary(session.generation());toggleVoice();});
        // Keep a semantic long-click path for accessibility services and tests;
        // touch users still use the gesture handler below.
        micIcon.setOnLongClickListener(v -> {
            if(sensitiveField)return false;
            openCompactMore();return true;
        });
        micIcon.setOnTouchListener((v,event) -> handleDockTouch(event));
        floatingDock.addView(micIcon,new LinearLayout.LayoutParams(dp(48),dp(48)));
        radialMenu=new FrameLayout(this);radialMenu.setClipChildren(false);radialMenu.setClipToPadding(false);
        radialMenu.setClickable(false);radialMenu.setVisibility(View.GONE);
        radialMenuItems.add(menuShortcut("device","目标设备",v -> {closeTransientControls();openManagement("devices");}));
        radialMenuItems.add(menuShortcut("history","输入记录与统计",v -> {closeTransientControls();openInputStatistics();}));
        radialMenuItems.add(menuShortcut("settings","输入法设置",v -> {closeTransientControls();openManagement("settings");}));
        radialMenuItems.add(menuShortcut("switch","切换系统输入法",v -> {closeTransientControls();((android.view.inputmethod.InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).showInputMethodPicker();}));
        for(ImeIconButton item:radialMenuItems)radialMenu.addView(item,new FrameLayout.LayoutParams(dp(48),dp(48)));
        dockRoot.addView(radialMenu,new FrameLayout.LayoutParams(-1,-1,Gravity.TOP|Gravity.LEFT));
        dockAction=compactControl("enter","回车",v -> enter());
        dockVoice=key("语音",12);dockVoice.setOnClickListener(v -> toggleVoice());
        dockTarget=key("本机",12);dockTarget.setOnClickListener(v -> toggleOutputTarget());
        dockSend=key("发送",12);dockSend.setOnClickListener(v -> sendRemoteDraft());dockStatus=label(10);
        dockXFraction=prefs.getFloat("ime_dock_x_fraction",1f);
        dockYFraction=prefs.getFloat("ime_dock_y_fraction",.65f);
        dockRoot.addView(floatingDock,new FrameLayout.LayoutParams(dp(48),dp(48),Gravity.TOP|Gravity.LEFT));
        punctuationBar=row();punctuationBar.setGravity(Gravity.CENTER);
        punctuationBar.setBackground(surface(0xe6f0f5f8,18));
        for(int i=0;i<punctuationValues.length;i++) {
            final int index=i;TextView mark=key(punctuationValues[i],20);
            mark.setContentDescription(i==4?"更多功能":"插入"+punctuationValues[i]);
            mark.setOnClickListener(v -> applyPunctuation(index));
            punctuationBar.addView(mark,new LinearLayout.LayoutParams(dp(40),dp(48)));
        }
        punctuationBar.setVisibility(View.GONE);dockRoot.addView(punctuationBar,new FrameLayout.LayoutParams(dp(200),dp(48)));
        dockRoot.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob) -> placeFloatingDock());
        if(getWindow()!=null && getWindow().getWindow()!=null) {
        getWindow().getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH | android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL);
        getWindow().getWindow().getDecorView().setOnTouchListener((v,event) -> {
            if(event.getActionMasked()==android.view.MotionEvent.ACTION_OUTSIDE)closeTransientControls();
            return false;
        });
        }
        remoteDockPreview=label(15);remoteDockPreview.setMinLines(2);remoteDockPreview.setMaxLines(5);
        remoteDockPreview.setPadding(dp(10),dp(8),dp(10),dp(8));
        remoteDockPreview.setBackground(surface(Color.rgb(248,250,252),12));
        remoteDockPreview.setMovementMethod(android.text.method.ScrollingMovementMethod.getInstance());
        remoteDockPreview.setVerticalScrollBarEnabled(true);
        remoteDockPreview.setContentDescription("电脑待发送草稿，仅显式点击发送后传出");
        FrameLayout.LayoutParams remoteParams=new FrameLayout.LayoutParams(dp(210),dp(132),Gravity.RIGHT|Gravity.BOTTOM);
        remoteParams.rightMargin=dp(12);remoteParams.bottomMargin=dp(136);dockRoot.addView(remoteDockPreview,remoteParams);
        render(); return dockRoot;
    }

    private ImeIconButton iconButton(String icon,String description,View.OnClickListener listener) {
        ImeIconButton view=new ImeIconButton(this,icon,description);view.setOnClickListener(listener);
        view.setBackgroundColor(Color.TRANSPARENT);
        if(android.os.Build.VERSION.SDK_INT>=26)view.setTooltipText(description);
        return view;
    }
    private ImeIconButton compactControl(String icon,String description,View.OnClickListener listener) {
        ImeIconButton view=iconButton(icon,description,listener);
        view.setLeafRing(true);view.setIconSizeDp(20f);
        floatingDock.addView(view,new LinearLayout.LayoutParams(dp(48),dp(48)));return view;
    }
    private ImeIconButton menuShortcut(String icon,String description,View.OnClickListener listener) {
        ImeIconButton view=iconButton(icon,description,listener);
        view.setLeafRing(true);view.setIconSizeDp(18f);return view;
    }
    private void bindDelete(TextView view) {
        view.setContentDescription("删除，按住连续删除");
        view.setOnTouchListener((v,event) -> {
            switch(event.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:stopDeleteRepeat();deleteHeld=true;deleteRepeated=false;main.postDelayed(repeatDelete,450);return true;
                case android.view.MotionEvent.ACTION_MOVE:
                    if(event.getX()<0 || event.getY()<0 || event.getX()>=v.getWidth() || event.getY()>=v.getHeight()) {
                        stopDeleteRepeat();deleteRepeated=true;
                    }
                    return true;
                case android.view.MotionEvent.ACTION_UP:boolean repeated=deleteRepeated;stopDeleteRepeat();if(!repeated)v.performClick();return true;
                case android.view.MotionEvent.ACTION_CANCEL:stopDeleteRepeat();return true;
                default:return true;
            }
        });
    }
    private void cancelDockGesture() {
        main.removeCallbacks(dockLongPress);
        if(pendingSingleTap!=null)main.removeCallbacks(pendingSingleTap);
        pendingSingleTap=null;lastTapAt=0;secondTap=false;
        dockDragging=false;dockLongPressed=false;dockGestureBlocked=true;
        if(micIcon!=null)micIcon.setPressed(false);
        hidePunctuation();
    }
    private boolean gestureStillBound() {
        return editorBound && gestureBinding==bindingRevision && gestureGeneration==session.generation()
                && gestureTarget.equals(currentTargetKey());
    }
    private void openT9Keyboard() {
        if(sensitiveField)return;
        if(!panels.isPrimary())panels.returnPrimary(session.generation());
        if(!voiceMode && keyboard.mode()==KeyboardController.Mode.T9)return;
        switchMode(KeyboardController.Mode.T9);
    }
    private void toggleKeyboardFromGesture() {
        if(voiceMode)openT9Keyboard();else selectVoiceMode();
    }
    private void queueMicTap(float x,float y) {
        long now=android.os.SystemClock.uptimeMillis();
        if(secondTap) {secondTap=false;lastTapAt=0;toggleKeyboardFromGesture();return;}
        lastTapAt=now;lastTapX=x;lastTapY=y;tapBinding=bindingRevision;tapTarget=currentTargetKey();
        final long generation=session.generation();
        pendingSingleTap=() -> {
            pendingSingleTap=null;lastTapAt=0;
            if(!destroyed && editorBound && tapBinding==bindingRevision && generation==session.generation()
                    && tapTarget.equals(currentTargetKey()))micIcon.performClick();
        };
        main.postDelayed(pendingSingleTap,android.view.ViewConfiguration.getDoubleTapTimeout());
    }
    private boolean handleDockTouch(android.view.MotionEvent event) {
        switch(event.getActionMasked()) {
            case android.view.MotionEvent.ACTION_DOWN:
                secondTap=pendingSingleTap!=null && android.os.SystemClock.uptimeMillis()-lastTapAt
                        <=android.view.ViewConfiguration.getDoubleTapTimeout()
                        && Math.hypot(event.getRawX()-lastTapX,event.getRawY()-lastTapY)
                            <=android.view.ViewConfiguration.get(this).getScaledDoubleTapSlop();
                if(pendingSingleTap!=null){main.removeCallbacks(pendingSingleTap);pendingSingleTap=null;}
                main.removeCallbacks(dockLongPress);dockDragging=false;dockLongPressed=false;dockGestureBlocked=false;
                gestureBinding=bindingRevision;gestureGeneration=session.generation();gestureTarget=currentTargetKey();
                dragRawX=event.getRawX();dragRawY=event.getRawY();
                dragCenterX=floatingDock.getLeft()+micIcon.getLeft()+micIcon.getWidth()/2f;
                dragCenterY=floatingDock.getTop()+micIcon.getTop()+micIcon.getHeight()/2f;
                micIcon.setPressed(true);main.postDelayed(dockLongPress,android.view.ViewConfiguration.getLongPressTimeout());return true;
            case android.view.MotionEvent.ACTION_POINTER_DOWN:cancelDockGesture();return true;
            case android.view.MotionEvent.ACTION_MOVE:
                if(!gestureStillBound()){cancelDockGesture();return true;}
                float dx=event.getRawX()-dragRawX,dy=event.getRawY()-dragRawY;
                int slop=android.view.ViewConfiguration.get(this).getScaledTouchSlop();
                if(punctuationSelecting){updatePunctuationSelection(event.getRawX(),event.getRawY());return true;}
                if(dockGestureBlocked)return true;
                if(dockLongPressed) {
                    dockDragging=true;
                    dockXFraction=CompactDockGeometry.fractionX(dragCenterX+dx,dockRoot.getWidth());
                    dockYFraction=CompactDockGeometry.fractionY(dragCenterY+dy,dockRoot.getHeight());placeFloatingDock();
                } else if(dx*dx+dy*dy>slop*slop) {
                    main.removeCallbacks(dockLongPress);micIcon.setPressed(false);secondTap=false;
                    if(pendingSingleTap!=null){main.removeCallbacks(pendingSingleTap);pendingSingleTap=null;lastTapAt=0;}
                    if(dy< -slop && -dy>Math.abs(dx)) {
                        showPunctuation();updatePunctuationSelection(event.getRawX(),event.getRawY());
                    } else dockGestureBlocked=true;
                }
                return true;
            case android.view.MotionEvent.ACTION_UP:
                main.removeCallbacks(dockLongPress);micIcon.setPressed(false);
                if(!gestureStillBound()){cancelDockGesture();return true;}
                if(punctuationSelecting) {
                    updatePunctuationSelection(event.getRawX(),event.getRawY());int selected=punctuationIndex;
                    hidePunctuation();if(selected>=0)applyPunctuation(selected);
                } else if(dockDragging)saveDockPosition();
                else if(dockLongPressed && !dockGestureBlocked && !busy())openCompactMore();
                else if(!dockLongPressed && !dockGestureBlocked)queueMicTap(event.getRawX(),event.getRawY());
                dockDragging=false;dockLongPressed=false;return true;
            case android.view.MotionEvent.ACTION_CANCEL:cancelDockGesture();return true;
            default:return true;
        }
    }
    private void showPunctuation() {
        if(punctuationBar==null || sensitiveField || !panels.isPrimary())return;
        punctuationSelecting=true;punctuationIndex=-1;
        int width=Math.min(dp(200),dockRoot.getWidth()),height=dp(48);
        int left=Math.max(0,Math.min(dockRoot.getWidth()-width,Math.round(dragCenterX)-width/2));
        int top=Math.max(0,Math.round(dragCenterY)-dp(80));
        placeInDock(punctuationBar,left,top,width,height);
        punctuationBar.setVisibility(View.VISIBLE);punctuationBar.bringToFront();refreshEditorControls();dockRoot.requestApplyInsets();
    }
    private void updatePunctuationSelection(float rawX,float rawY) {
        int[] origin=new int[2];dockRoot.getLocationOnScreen(origin);
        float x=rawX-origin[0],y=rawY-origin[1];
        punctuationIndex=-1;
        if(Math.hypot(x-dragCenterX,y-dragCenterY)>dp(24)
                && x>=punctuationBar.getLeft() && x<punctuationBar.getRight()
                && y>=punctuationBar.getTop() && y<punctuationBar.getBottom()) {
            punctuationIndex=Math.min(4,(int)((x-punctuationBar.getLeft())*5/punctuationBar.getWidth()));
        }
        for(int i=0;i<5;i++)punctuationBar.getChildAt(i).setBackgroundColor(i==punctuationIndex?0x553b819c:Color.TRANSPARENT);
    }
    private void hidePunctuation() {
        punctuationSelecting=false;punctuationIndex=-1;
        if(punctuationBar!=null){punctuationBar.setVisibility(View.GONE);refreshEditorControls();if(dockRoot!=null)dockRoot.requestApplyInsets();}
    }
    private void applyPunctuation(int index) {
        hidePunctuation();
        if(index==4){openCompactMore();return;}
        if(index<0 || index>=4 || sensitiveField || !editorBound || !panels.isPrimary())return;
        if(!targets.isRemote() && editorReadiness()!=EditorReadinessPolicy.State.READY)return;
        if(busy())interruptRecognitionForModeSwitch();
        commitLiteral(punctuationValues[index]);scheduleEditorContextProbe();refreshEditorControls();
    }
    private void saveDockPosition() {
        if(prefs!=null)prefs.edit().putFloat("ime_dock_x_fraction",dockXFraction)
                .putFloat("ime_dock_y_fraction",dockYFraction).apply();
    }
    private void placeFloatingDock() {
        if(dockRoot==null || floatingDock==null || dockRoot.getWidth()==0 || dockRoot.getHeight()==0)return;
        boolean expanded=showVoiceEditingActions();
        CompactDockGeometry.Placement p=CompactDockGeometry.layout(dockRoot.getWidth(),dockRoot.getHeight(),dp(48),dockXFraction,dockYFraction,expanded);
        dockDelete.setVisibility(p.expanded ? (hasDeletableContext() && !punctuationSelecting?View.VISIBLE:View.INVISIBLE) : View.GONE);
        dockAction.setVisibility(p.expanded ? View.VISIBLE : View.GONE);
        boolean geometryChanged=placeInDock(floatingDock,p.left,p.top,p.width,p.height);
        int gap=dp(8);
        boolean radialOpen=compactMenu() && panels.feature()==ImePanelController.Feature.HUB;
        if(compactMenu() && !radialOpen) {
            int w=dp(224),h=dp(264);
            int x=p.left>=w+gap ? p.left-w-gap : p.left+p.width+gap;
            geometryChanged=placeInDock(secondaryPanel,Math.max(0,Math.min(dockRoot.getWidth()-w,x)),
                Math.max(0,Math.min(dockRoot.getHeight()-h,Math.round(p.micCenterY)-h/2)),Math.min(w,dockRoot.getWidth()),Math.min(h,dockRoot.getHeight())) || geometryChanged;
        }
        if(radialMenu!=null) {
            radialMenu.setVisibility(radialOpen?View.VISIBLE:View.GONE);
            if(radialOpen) {
                float[][] locations=MicShortcutMenuGeometry.layout(dockRoot.getWidth(),dockRoot.getHeight(),
                    p.micCenterX,p.micCenterY,dp(64),dp(48),dp(8));
                for(int i=0;i<radialMenuItems.size();i++) {
                    ImeIconButton item=radialMenuItems.get(i);
                    int left=Math.round(locations[i][0]),top=Math.round(locations[i][1]),size=dp(48);
                    geometryChanged=placeInDock(item,left,top,size,size)||geometryChanged;
                }
                radialMenu.bringToFront();floatingDock.bringToFront();
            }
        }
        if(remoteDockPreview!=null && remoteDockPreview.getVisibility()==View.VISIBLE) {
            int w=Math.min(dp(210),Math.max(0,dockRoot.getWidth()-p.width-gap));int h=dp(132);
            int x=p.left>=w+gap ? p.left-w-gap : p.left+p.width+gap;
            geometryChanged=placeInDock(remoteDockPreview,Math.max(0,Math.min(dockRoot.getWidth()-w,x)),
                Math.max(0,Math.min(dockRoot.getHeight()-h,Math.round(p.micCenterY)-h/2)),w,Math.min(h,dockRoot.getHeight())) || geometryChanged;
        }
        refreshEditorControls();
        if(geometryChanged)dockRoot.requestApplyInsets();
    }
    private boolean placeInDock(View view,int left,int top,int width,int height) {
        FrameLayout.LayoutParams old=(FrameLayout.LayoutParams)view.getLayoutParams();
        if(old.width==width && old.height==height && old.leftMargin==left && old.topMargin==top && old.gravity==(Gravity.TOP|Gravity.LEFT))return false;
        FrameLayout.LayoutParams next=new FrameLayout.LayoutParams(width,height,Gravity.TOP|Gravity.LEFT);
        next.leftMargin=left;next.topMargin=top;view.setLayoutParams(next);
        // Sync measured slots and the touchable region in the same frame.
        if(view==floatingDock || view==punctuationBar) {
            view.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));
            view.layout(left,top,left+width,top+height);
        }
        return true;
    }
    private boolean compactMenu() {
        return !panels.isPrimary() && (panels.feature()==ImePanelController.Feature.HUB || panels.feature()==ImePanelController.Feature.TRANSFER);
    }
    private boolean showVoiceEditingActions() {return voiceMode && panels.isPrimary() && !sensitiveField;}
    private boolean dockVisible() {return voiceMode && (panels.isPrimary() || compactMenu()) && !sensitiveField;}
    private void closeTransientControls() {

        if(compactMenu())panels.returnPrimary(session.generation());render();
    }
    /** Compact ring icon stays inside its 48dp touch target. */
    static final class MicStyleSpec {
        static final float STATE_STROKE_WIDTH = 1.8f;
        private MicStyleSpec() {}
    }
    private final class MicView extends View {
        private final android.graphics.Paint paint=new android.graphics.Paint(3);
        private ValueAnimator ringAnimator;
        private MicStatusVisualPolicy.Ring animatedRing;
        private float ringPhase;
        MicView(){super(UniversalInputMethodService.this);setClickable(true);setFocusable(true);}
        @Override public void onInitializeAccessibilityNodeInfo(android.view.accessibility.AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(info);
            info.setClassName("android.widget.ImageButton");
            info.addAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_EXPAND);
            info.addAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_LONG_CLICK);
            info.addAction(new android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction(
                    ACCESSIBILITY_ACTION_OPEN_T9,"切换九宫格"));
            if(compactMenu())info.addAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_COLLAPSE);
        }
        @Override public boolean performAccessibilityAction(int action,android.os.Bundle args) {
            if((action==android.view.accessibility.AccessibilityNodeInfo.ACTION_EXPAND
                    || action==android.view.accessibility.AccessibilityNodeInfo.ACTION_LONG_CLICK)
                    && panels.isPrimary() && !sensitiveField) {
                openCompactMore();return true;
            }
            if(action==ACCESSIBILITY_ACTION_OPEN_T9 && !sensitiveField) {
                openT9Keyboard();return true;
            }
            if(action==android.view.accessibility.AccessibilityNodeInfo.ACTION_COLLAPSE && compactMenu()) {
                closeTransientControls();return true;
            }
            return super.performAccessibilityAction(action,args);
        }
        @Override protected void onAttachedToWindow(){super.onAttachedToWindow();syncRingAnimation();}
        @Override protected void onDetachedFromWindow(){stopRingAnimation();super.onDetachedFromWindow();}
        @Override protected void onVisibilityChanged(View changedView,int visibility){super.onVisibilityChanged(changedView,visibility);syncRingAnimation();}
        @Override protected void onWindowVisibilityChanged(int visibility){super.onWindowVisibilityChanged(visibility);syncRingAnimation();}
        void syncRingAnimation() {
            MicStatusVisualPolicy.Ring next=MicStatusVisualPolicy.ring(session.state());
            if(next==animatedRing && (ringAnimator!=null || next==MicStatusVisualPolicy.Ring.MUTED
                    || next==MicStatusVisualPolicy.Ring.READY))return;
            stopRingAnimation();animatedRing=next;ringPhase=0;
            if(!isShown() || destroyed || next==MicStatusVisualPolicy.Ring.MUTED
                    || next==MicStatusVisualPolicy.Ring.READY || next==MicStatusVisualPolicy.Ring.RECORDING
                    || !ValueAnimator.areAnimatorsEnabled()){invalidate();return;}
            long duration=next==MicStatusVisualPolicy.Ring.READY?2600:next==MicStatusVisualPolicy.Ring.RECORDING?760:1050;
            ringAnimator=ValueAnimator.ofFloat(0f,1f);ringAnimator.setDuration(duration);
            ringAnimator.setRepeatCount(ValueAnimator.INFINITE);
            ringAnimator.setRepeatMode(next==MicStatusVisualPolicy.Ring.RECOGNIZING?ValueAnimator.RESTART:ValueAnimator.REVERSE);
            ringAnimator.addUpdateListener(animation->{ringPhase=(Float)animation.getAnimatedValue();invalidate();});
            ringAnimator.start();invalidate();
        }
        void stopRingAnimation(){if(ringAnimator!=null){ringAnimator.cancel();ringAnimator=null;}}
        @Override protected void onDraw(android.graphics.Canvas canvas) {
            super.onDraw(canvas);canvas.save();canvas.translate(getWidth()/2f,getHeight()/2f);canvas.scale(getResources().getDisplayMetrics().density,getResources().getDisplayMetrics().density);
            MicStatusVisualPolicy.Ring ring=MicStatusVisualPolicy.ring(session.state());
            int color=MicStatusVisualPolicy.color(ring);
            LeafIconStyle.drawRing(canvas,paint,color,ring==MicStatusVisualPolicy.Ring.RECOGNIZING,ringPhase,isPressed());
            // Same 34dp open ring as delete and enter; no grey plate, fill, shadow bitmap or blur.
            drawMicSymbol(canvas,LeafIconStyle.INK,MicStyleSpec.STATE_STROKE_WIDTH+2.3f,true);
            drawMicSymbol(canvas,0xffedffdf,MicStyleSpec.STATE_STROKE_WIDTH+1.2f,true);
            drawMicSymbol(canvas,color,MicStyleSpec.STATE_STROKE_WIDTH,false);
            if(targets.isRemote()) {
                paint.setStyle(android.graphics.Paint.Style.STROKE);paint.setColor(LeafIconStyle.INK);paint.setStrokeWidth(3.2f);
                canvas.drawRoundRect(6f,7f,13f,12f,1,1,paint);
                paint.setColor(LeafIconStyle.HIGHLIGHT);paint.setStrokeWidth(1.5f);canvas.drawRoundRect(6f,7f,13f,12f,1,1,paint);
            }
            canvas.restore();
        }
        private void drawMicSymbol(android.graphics.Canvas canvas,int color,float width,boolean outline) {
            paint.setColor(color);paint.setAlpha(255);paint.setStrokeWidth(width);
            paint.setStrokeCap(android.graphics.Paint.Cap.ROUND);
            if(session.state()==InputSession.State.LISTENING) {
                paint.setStyle(outline?android.graphics.Paint.Style.FILL_AND_STROKE:android.graphics.Paint.Style.FILL);
                canvas.drawRoundRect(-4.5f,-4.5f,4.5f,4.5f,1.8f,1.8f,paint);
            } else {
                paint.setStyle(android.graphics.Paint.Style.STROKE);
                canvas.drawRoundRect(-2.8f,-7.5f,2.8f,2.8f,2.8f,2.8f,paint);
                canvas.drawArc(-6.5f,-5.5f,6.5f,6.5f,0,180,false,paint);
                canvas.drawLine(0,6.5f,0,10.5f,paint);canvas.drawLine(-3.5f,10.5f,3.5f,10.5f,paint);
            }
        }
    }
    @Override public void onComputeInsets(Insets out) {
        super.onComputeInsets(out);
        if(dockRoot==null || (!dockVisible() && !compactMenu()))return;
        View decor=getWindow().getWindow().getDecorView();
        out.contentTopInsets=decor.getHeight();out.visibleTopInsets=decor.getHeight();
        out.touchableInsets=Insets.TOUCHABLE_INSETS_REGION;
        int[] xy=new int[2];floatingDock.getLocationInWindow(xy);
        out.touchableRegion.setEmpty();
        if(dockVisible())for(int i=0;i<floatingDock.getChildCount();i++) {
            View child=floatingDock.getChildAt(i);if(child.getVisibility()!=View.VISIBLE)continue;
            child.getLocationInWindow(xy);out.touchableRegion.union(new Rect(xy[0],xy[1],xy[0]+child.getWidth(),xy[1]+child.getHeight()));
        }
        if(punctuationBar!=null && punctuationBar.getVisibility()==View.VISIBLE) {
            punctuationBar.getLocationInWindow(xy);out.touchableRegion.union(new Rect(xy[0],xy[1],xy[0]+punctuationBar.getWidth(),xy[1]+punctuationBar.getHeight()));
        }
        if(compactMenu() && panels.feature()!=ImePanelController.Feature.HUB && secondaryPanel.getVisibility()==View.VISIBLE) {
            secondaryPanel.getLocationInWindow(xy);out.touchableRegion.union(new Rect(xy[0],xy[1],xy[0]+secondaryPanel.getWidth(),xy[1]+secondaryPanel.getHeight()));
        }
        if(radialMenu!=null && radialMenu.getVisibility()==View.VISIBLE)for(ImeIconButton item:radialMenuItems) {
            if(item.getVisibility()!=View.VISIBLE)continue;
            item.getLocationInWindow(xy);out.touchableRegion.union(new Rect(xy[0],xy[1],xy[0]+item.getWidth(),xy[1]+item.getHeight()));
        }
        if(remoteDockPreview!=null && remoteDockPreview.getVisibility()==View.VISIBLE) {
            remoteDockPreview.getLocationInWindow(xy);
            out.touchableRegion.union(new Rect(xy[0],xy[1],xy[0]+remoteDockPreview.getWidth(),xy[1]+remoteDockPreview.getHeight()));
        }
    }

    @Override public void onStartInput(EditorInfo info, boolean restarting) {
        super.onStartInput(info,restarting);
        if (restarting && fieldKey(info).equals(session.identity().targetId)
                && session.state() == InputSession.State.PREVIEW) { render(); return; }
        // Android/terminal hosts may restart the same InputConnection when a
        // child process exits. Keep an explicitly selected text keyboard in
        // that same editor instead of unexpectedly replacing it with voice.
        if (restarting && fieldKey(info).equals(session.identity().targetId)
                && !voiceMode && !busy()) {
            editorBound=true;
            lastSelectionStart=info == null ? -1 : info.initialSelStart;
            lastSelectionEnd=info == null ? -1 : info.initialSelEnd;
            requestEditorCursorUpdates();render();scheduleEditorContextProbe();return;
        }
        beginEditor(info,restarting);
    }
    @Override public void onStartInputView(EditorInfo info, boolean restarting) {
        super.onStartInputView(info,restarting);
        // Some Android builds recreate an IME view for a new editor without a
        // matching onStartInput callback. Preserve a draft only for the exact
        // same field; a different app or field always starts clean.
        if (!fieldKey(info).equals(session.identity().targetId)) beginEditor(info,restarting);
        else render();
        applyManagementOutput(info);
        requestEditorCursorUpdates();
    }
    /** Apply an explicit one-shot choice only to an external non-sensitive editor. */
    private void applyManagementOutput(EditorInfo info) {
        if(prefs==null || info==null || getPackageName().equals(info.packageName) || sensitiveField
                || !prefs.getBoolean("management_output_pending",false))return;
        boolean remote=prefs.getBoolean("management_output_remote",false);
        String requested=prefs.getString("management_output_device","");
        refreshPairing();
        if(remote && (activePairing==null || !requested.equals(targets.deviceId()))) {
            message="选择的电脑已变化，请在管理页重新选择";render();return;
        }
        if(targets.sending()){message="文字正在发送，稍后再切换目标";render();return;}
        if(remote!=targets.isRemote() && targets.toggleTarget()!=OutputTargetController.ToggleResult.OK)return;
        prefs.edit().remove("management_output_pending").apply();
        message=remote?"本次输入到 "+targets.deviceLabel()+"；点击发送才传出":"本次输入到本机";
        persistRemoteState(true);render();
    }
    private void beginEditor(EditorInfo info,boolean restarting) {
        main.removeCallbacks(dockLongPress);dockDragging=false;dockLongPressed=false;
        stopDeleteRepeat();
        cancelDockGesture();bindingRevision++;editorContextRevision++;beforeCursorLength=-1;contextSelectionFresh=true;main.removeCallbacks(contextProbe);
        editorBound=true;cursorAnchorReceived=false;cursorUpdateAccepted=false;
        if (speech != null) speech.cancel();
        panels.reset();
        localHistorySession=UUID.randomUUID().toString();
        inlineVoiceText="";voiceAnchor=voiceExpected=-1;unsafeRetry=false;
        keyboard.reset(); activeComposing=""; suspendedPreview=""; voiceDraftPrefix=""; voiceDraftSuffix=""; voiceSamples=0;
        speechDetected=false;audioCaptureReady=false;previewCommitReadyAt=0;message="";
        lastRenderedPreedit="";lastCandidateList=java.util.Collections.emptyList();lastCandidateSourceIndices=java.util.Collections.emptyList();lastCandidatePage=-1;lastCandidateHasNext=false;
        sensitiveField=isSensitive(info == null ? 0 : info.inputType);
        keyboard.setLearningEnabled(info != null && !sensitiveField
                && (info.imeOptions & EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) == 0);
        voiceMode=shouldDefaultToVoice(info) && !sensitiveField;
        String editorKey=fieldKey(info);
        refreshPairing();
        targets.onStartInput(editorKey,sensitiveField,restarting);
        persistRemoteState(false);
        session.begin(editorKey,info == null ? "" : info.packageName,
                info == null ? 0 : info.fieldId,info == null ? 0 : info.inputType);
        draftSelectionStart=draftSelectionEnd=0;
        if (preview != null) preview.setText("");
        keyboardAnchor=-1; keyboardExpectedCursor=-1;
        lastSelectionStart=info == null ? -1 : info.initialSelStart;
        lastSelectionEnd=info == null ? -1 : info.initialSelEnd;
        if (sensitiveField) keyboard.setMode(KeyboardController.Mode.ENGLISH);
        else if (info != null && (info.inputType & InputType.TYPE_MASK_CLASS) == InputType.TYPE_CLASS_NUMBER)
            keyboard.setMode(KeyboardController.Mode.NUMBERS);
        else keyboard.setMode(preferredTextMode);
        if (keys != null) rebuildKeys();
        requestEditorCursorUpdates();render();scheduleEditorContextProbe();
    }
    private void abandonSpeechBinding() {
        if(speech!=null)speech.cancel();
        if(busy() && !targets.isRemote()) {
            InputConnection c=getCurrentInputConnection();if(c!=null)c.finishComposingText();
        }
        inlineVoiceText="";activeComposing="";voiceTarget="";audioCaptureReady=false;
        session.invalidate();
    }
    @Override public void onFinishInputView(boolean finishingInput) {
        cancelDockGesture();stopDeleteRepeat();panels.reset();
        if (finishingInput) {
            abandonSpeechBinding();
            if (speech != null) speech.cancel();
            suspendedPreview="";voiceDraftPrefix="";voiceDraftSuffix="";
            clearComposition();targets.onFinishInput();session.invalidate();
            if (preview != null) preview.setText("");
        } else if (busy()) {
            interruptRecognitionForModeSwitch();
        } else {
            if (speech != null) speech.cancel();
            if (session.state() == InputSession.State.PREVIEW && preview != null) syncVoiceDraft();
        }
        persistRemoteState(true);render();if(micIcon!=null)micIcon.stopRingAnimation();super.onFinishInputView(finishingInput);
    }
    @Override public void onFinishInput() {
        cancelDockGesture();bindingRevision++;editorContextRevision++;beforeCursorLength=-1;contextSelectionFresh=true;main.removeCallbacks(contextProbe);
        editorBound=false;cursorAnchorReceived=false;cursorUpdateAccepted=false;
        refreshEditorControls();
        main.removeCallbacks(dockLongPress);
        abandonSpeechBinding();
        stopDeleteRepeat();
        if (speech != null) speech.cancel();
        suspendedPreview=""; voiceDraftPrefix=""; voiceDraftSuffix="";
        if (preview != null) preview.setText("");
        panels.reset(); clearComposition(); targets.onFinishInput(); persistRemoteState(true);
        session.invalidate(); super.onFinishInput();
    }
    @Override public void onDestroy() {
        main.removeCallbacks(dockLongPress);
        cancelDockGesture();main.removeCallbacks(contextProbe);destroyed=true;if(contextExecutor!=null)contextExecutor.shutdownNow();stopDeleteRepeat(); persistRemoteState(true); session.invalidate();
        if (speech != null) speech.close(); if (remoteSender != null) remoteSender.close();
        if (history != null) history.close(); if (keyboard != null) keyboard.close(); super.onDestroy();
    }
    @Override public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if(speech!=null && level>=android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)speech.releaseIdleNow();
    }

    private void rebuildKeys() {
        stopDeleteRepeat();
        keys.removeAllViews();keyboardActionRail.removeAllViews();
        switch (keyboard.mode()) {
            case T9:
                addKeyRow(new String[]{"分词","ABC","DEF"},new String[]{"'","2","3"});
                addKeyRow(new String[]{"GHI","JKL","MNO"},new String[]{"4","5","6"});
                addKeyRow(new String[]{"PQRS","TUV","WXYZ"},new String[]{"7","8","9"});
                break;
            case NUMBERS:
                addKeyRow("123");addKeyRow("456");addKeyRow("789");
                addKeyRow(new String[]{".","0","#"},new String[]{".","0","#"});break;
            case SYMBOLS:
                addKeyRow(new String[]{"@","#","&","%","/"},new String[]{"@","#","&","%","/"});
                addKeyRow(new String[]{"\\","_","-","+","="},new String[]{"\\","_","-","+","="});
                addKeyRow(new String[]{"*","￥","$","？","！"},new String[]{"*","￥","$","？","！"});break;
            default:
                addKeyRow("qwertyuiop");addKeyRow("asdfghjkl");
                if(keyboard.mode()==KeyboardController.Mode.ENGLISH)
                    addKeyRow(new String[]{keyboard.uppercase()?"⇧开":"⇧","z","x","c","v","b","n","m"},
                            new String[]{"SHIFT","z","x","c","v","b","n","m"});
                else addKeyRow("zxcvbnm");
        }
        LinearLayout bottom=row();
        if(keyboard.mode()==KeyboardController.Mode.T9) {
            symbolModeKey=addButton(bottom,"123",v -> switchMode(KeyboardController.Mode.NUMBERS),1);
            symbolModeKey.setContentDescription("切换标准数字键盘");
            addButton(bottom,"空格",v -> space(),2.2f);
            TextView fullPinyin=addButton(bottom,"26键",v -> switchMode(KeyboardController.Mode.PINYIN),1);
            fullPinyin.setContentDescription("切换中文26键全拼");
            pinyinModeKey=addButton(bottom,"EN",v -> switchMode(KeyboardController.Mode.ENGLISH),.8f);
            t9ModeKey=fullPinyin;
        } else {
            if(keyboard.mode()==KeyboardController.Mode.NUMBERS || keyboard.mode()==KeyboardController.Mode.SYMBOLS) {
                TextView letters=addButton(bottom,preferredTextMode==KeyboardController.Mode.T9?"九键":"26键",
                        v -> switchMode(preferredTextMode),1);
                letters.setContentDescription("返回中文键盘");
            }
            addButton(bottom,"，",v -> type(keyboard.mode()==KeyboardController.Mode.ENGLISH?",":"，"),.7f);
            addButton(bottom,"空格",v -> space(),2.0f);
            addButton(bottom,"。",v -> type(keyboard.mode()==KeyboardController.Mode.ENGLISH?".":"。"),.7f);
            if(keyboard.mode()==KeyboardController.Mode.PINYIN) {
                t9ModeKey=addButton(bottom,"九键",v -> switchMode(KeyboardController.Mode.T9),.9f);
                pinyinModeKey=addButton(bottom,"EN",v -> switchMode(KeyboardController.Mode.ENGLISH),.8f);
            } else if(keyboard.mode()==KeyboardController.Mode.ENGLISH) {
                t9ModeKey=addButton(bottom,"九键",v -> switchMode(KeyboardController.Mode.T9),.9f);
                pinyinModeKey=addButton(bottom,"中文",v -> switchMode(preferredTextMode),.9f);
            } else {
                pinyinModeKey=addButton(bottom,"中/EN",v -> switchLanguage(),1);
                t9ModeKey=pinyinModeKey;
            }
            String modeLabel=keyboard.mode()==KeyboardController.Mode.NUMBERS?"符号":
                    keyboard.mode()==KeyboardController.Mode.SYMBOLS?"ABC":"123";
            symbolModeKey=addButton(bottom,modeLabel,v -> switchMode(
                    keyboard.mode()==KeyboardController.Mode.NUMBERS?KeyboardController.Mode.SYMBOLS:
                    keyboard.mode()==KeyboardController.Mode.SYMBOLS?preferredTextMode:KeyboardController.Mode.NUMBERS),1);
            symbolModeKey.setOnLongClickListener(v -> {switchMode(preferredTextMode);return true;});
        }
        keys.addView(bottom,new LinearLayout.LayoutParams(-1,0,1));
        deleteKey=addRailKey("删除","删除，按住连续删除",v -> backspace());bindDelete(deleteKey);
        voice=addRailKey("语音","单击语音，双击九宫格",v -> toggleVoice());
        bindKeyboardMicGesture(voice);
        enterKey=addRailKey("回车","回车",v -> enter());
        enterKey.setBackground(LeafIconStyle.keyBackground(this,true));
    }
    private TextView addRailKey(String text,String description,View.OnClickListener listener) {
        String icon="删除".equals(text)?"backspace":"语音".equals(text)?"mic":"回车".equals(text)?"enter":"numbers";
        ImeIconButton button=new ImeIconButton(this,icon,description);button.setIconSizeDp(21f);
        button.setBackground(LeafIconStyle.keyBackground(this,true));button.setOnClickListener(listener);
        LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,0,1);
        params.setMargins(dp(3),dp(1),dp(1),dp(1));keyboardActionRail.addView(button,params);
        return button;
    }
    private void bindKeyboardMicGesture(TextView button) {
        android.view.GestureDetector detector=new android.view.GestureDetector(this,
                new android.view.GestureDetector.SimpleOnGestureListener() {
             public boolean onDown(android.view.MotionEvent event) { return true; }
             public boolean onSingleTapConfirmed(android.view.MotionEvent event) {
                button.performClick();return true;
            }
             public boolean onDoubleTap(android.view.MotionEvent event) {
                openT9Keyboard();return true;
            }
             public void onLongPress(android.view.MotionEvent event) {
                button.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
                toggleFeaturePanel();
            }
        });
        button.setOnTouchListener((view,event) -> detector.onTouchEvent(event));
    }
    private void addKeyRow(String values) {
        String[] items = new String[values.length()];
        for (int i=0;i<values.length();i++) items[i]=String.valueOf(values.charAt(i));
        addKeyRow(items,items);
    }
    private void addKeyRow(String[] labels,String[] values) {
        LinearLayout row = row();
        for (int i=0;i<labels.length;i++) {
            final String value = values[i];
            String label = keyboard.mode() == KeyboardController.Mode.ENGLISH && keyboard.uppercase()
                    ? labels[i].toUpperCase(java.util.Locale.ROOT) : labels[i];
            TextView typingKey=addButton(row,label,v -> {if("DELETE".equals(value))backspace();else type(value);},1);
            if("DELETE".equals(value)){deleteKey=typingKey;bindDelete(typingKey);}
            if(values.length==10 && "q".equals(values[0])) {
                final String digit=String.valueOf((i+1)%10);
                ((ImeKeycapView)typingKey).setKeyHint(digit);
                typingKey.setOnLongClickListener(v -> {type(digit);return true;});
                typingKey.setContentDescription(label+"，长按输入"+digit);
            }
            boolean letterLayout=keyboard.mode() == KeyboardController.Mode.PINYIN
                    || keyboard.mode() == KeyboardController.Mode.ENGLISH;
            typingKey.setTextSize(letterLayout ? ImeLayoutSpec.KEY_TEXT_MAX_SP : 14);
            if(letterLayout) {
                typingKey.setSingleLine(true);
                typingKey.setMaxLines(1);
                typingKey.setAutoSizeTextTypeUniformWithConfiguration(
                        ImeLayoutSpec.KEY_TEXT_MIN_SP,ImeLayoutSpec.KEY_TEXT_MAX_SP,1,
                        android.util.TypedValue.COMPLEX_UNIT_SP);
            }
        }
        keys.addView(row,new LinearLayout.LayoutParams(-1,0,1));
    }
    private void switchMode(KeyboardController.Mode mode) {
        if (!panels.isPrimary()) return;
        if (sensitiveField && (mode == KeyboardController.Mode.PINYIN || mode == KeyboardController.Mode.T9)) return;
        if (busy()) interruptRecognitionForModeSwitch();
        if (session.state() == InputSession.State.PREVIEW) syncVoiceDraft();
        if (!settleKeyboardComposition()) return;
        voiceMode=false; keyboard.setMode(mode);
        if (mode == KeyboardController.Mode.PINYIN || mode == KeyboardController.Mode.T9) preferredTextMode=mode;
        message=""; rebuildKeys(); render();
    }
    /** Mode switching stops audio but never confirms or submits the draft. */
    private void interruptRecognitionForModeSwitch() {
        speech.cancel();finishInlineSpeech(inlineVoiceText);
    }
    private void syncVoiceDraft() {
        if (preview == null || session.state() != InputSession.State.PREVIEW) return;
        suspendedPreview=preview.getText().toString();
        session.updatePreview(session.generation(),suspendedPreview);
        draftSelectionStart=Math.max(0,preview.getSelectionStart());
        draftSelectionEnd=Math.max(0,preview.getSelectionEnd());
    }
    private void switchLanguage() {
        if (!panels.isPrimary() || busy() || sensitiveField) return;
        switchMode(keyboard.mode() == KeyboardController.Mode.ENGLISH
                ? preferredTextMode : KeyboardController.Mode.ENGLISH);
    }
    private void type(String value) {
        if (!panels.isPrimary() || busy()) return;
        if (" ".equals(value)) { space(); return; }
        if ("SHIFT".equals(value)) { keyboard.toggleCase(); rebuildKeys(); return; }
        ChineseInputEngine.Snapshot stateBeforeKey=keyboard.isT9ComposingKey(value)
                ? keyboard.snapshot() : null;
        String literal = keyboard.type(value,stateBeforeKey);
        if (literal != null) {commitLiteral(literal);return;}
        ChineseInputEngine.Snapshot stateAfterKey=keyboard.snapshot();
        if(keyboard.t9NoMatchRecoveryNeeded(stateAfterKey)) {
            if(!recoverT9NoMatch(stateAfterKey))return;
        }
        syncComposition();
    }
    /**
     * A T9 code with no Chinese candidate is never allowed to become a modal
     * dead end. Commit the longest valid prefix, then replay the untouched tail
     * as a fresh composition. If even a one-key segment is invalid, clear that
     * invalid lookup in the same event. T9 digits never leak into the editor.
     */
    private boolean recoverT9NoMatch(ChineseInputEngine.Snapshot initial) {
        if(initial==null || keyboard.mode()!=KeyboardController.Mode.T9
                || !keyboard.t9NoMatchRecoveryNeeded(initial))return true;
        StringBuilder tail=new StringBuilder();
        ChineseInputEngine.Snapshot prefix=initial;
        int candidate=-1;
        while(!prefix.numericInput.isEmpty()) {
            String code=prefix.numericInput;
            tail.insert(0,code.charAt(code.length()-1));
            keyboard.backspace();
            prefix=keyboard.snapshot();
            candidate=keyboard.firstCommittableCandidateIndex(prefix);
            if(candidate>=0)break;
        }
        if(candidate>=0) {
            if(!commitCandidate(candidate))return false;
        } else keyboard.reset();
        ChineseInputEngine.Snapshot replay=keyboard.snapshot();
        for(int i=0;i<tail.length();i++) {
            char key=tail.charAt(i);
            // A separator cannot start a new T9 segment.
            if(key=='\'' && replay.numericInput.isEmpty())continue;
            keyboard.type(String.valueOf(key),replay);
            replay=keyboard.snapshot();
            if(keyboard.firstCommittableCandidateIndex(replay)<0 && replay.numericInput.length()>1) {
                // Preserve forward progress even for an entirely arbitrary key
                // stream: discard the still-unmatched prefix and retain the
                // newest key as the next lookup segment.
                keyboard.reset();
                replay=keyboard.snapshot();
                if(key!='\'') {keyboard.type(String.valueOf(key),replay);replay=keyboard.snapshot();}
            }
        }
        // The invariant is strict: a composition that has no visible candidate
        // must never survive the key event and trap later actions.
        if(!replay.numericInput.isEmpty() && keyboard.firstCommittableCandidateIndex(replay)<0) {
            keyboard.reset();
            message="未匹配的九宫格输入已自动清除";
        } else message="九宫格已自动分段";
        return true;
    }
    private boolean rawEditor() {
        EditorInfo info=getCurrentInputEditorInfo();
        return info != null && EditorCompatibility.rawEditor(info.inputType,info.packageName);
    }
    private boolean inlineCompositionAllowed() {
        EditorInfo info=getCurrentInputEditorInfo();
        return info != null && EditorCompatibility.inlineComposition(info.inputType,info.packageName,
                keyboard.mode() == KeyboardController.Mode.T9);
    }
    private void syncComposition() {
        ChineseInputEngine.Snapshot state=keyboard.snapshot();
        String visible=keyboard.displayComposition(state);
        if (session.state() == InputSession.State.PREVIEW) { render(); return; }
        session.compose(session.generation(),visible);
        targets.setComposing(visible);
        if (!targets.isRemote() && inlineCompositionAllowed()) {
            InputConnection connection=getCurrentInputConnection();
            if (connection != null) {
                if (!visible.isEmpty()) {
                    if(activeComposing.isEmpty() || keyboardAnchor<0) {
                        android.view.inputmethod.ExtractedText current=keyboardEditorState(connection);
                        keyboardAnchor=current!=null && current.selectionStart>=0 && current.selectionEnd>=0
                                ? current.startOffset+Math.min(current.selectionStart,current.selectionEnd)
                                : Math.min(lastSelectionStart,lastSelectionEnd);
                    }
                    keyboardExpectedCursor=keyboardAnchor<0 ? -1 : keyboardAnchor+visible.length();
                    keyboardWriting=true;
                    boolean accepted;
                    try { accepted=connection.setComposingText(visible,1); }
                    finally { keyboardWriting=false;editorContentChanged(); }
                    if(accepted)activeComposing=visible;
                    else { keyboardAnchor=-1;keyboardExpectedCursor=-1; }
                } else if (!activeComposing.isEmpty()) {
                    connection.setComposingText("",1); connection.finishComposingText();
                    activeComposing="";keyboardAnchor=-1;keyboardExpectedCursor=-1;
                }
            }
        } else { activeComposing="";keyboardAnchor=-1;keyboardExpectedCursor=-1; }
        render(state);
    }
    private void selectSyllable(String value) {
        if (!panels.isPrimary() || busy()) return;
        if (!keyboard.chooseSyllable(value)) { message="当前音节不能选择，请继续选词或重输";render();return; }
        message="";syncComposition();
    }
    private void renderSyllables(ChineseInputEngine.Snapshot state,boolean visible) {
        if (syllables == null) return;
        boolean hasChoices=!state.syllables.isEmpty() || !state.syllableSegments.isEmpty();
        syllableScroller.setVisibility(visible ? View.VISIBLE : View.GONE);
        syllables.removeAllViews();
        if (!visible) return;
        if (!hasChoices) {
            String[] values={"，","。","？","！","符号"};
            for(String value:values) {
                TextView item=key(value,"符号".equals(value)?12:17);
                item.setContentDescription("符号".equals(value)?"打开符号键盘":"输入"+value);
                item.setOnClickListener(v -> {
                    if("符号".equals(value))switchMode(KeyboardController.Mode.SYMBOLS);
                    else type(value);
                });
                LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,0,1);
                params.setMargins(0,dp(1),dp(1),dp(1));
                syllables.addView(item,params);
            }
            return;
        }
        for (int i=0;i<state.syllableSegments.size();i++) {
            final int index=i;
            ChineseInputEngine.SyllableSegment segment=state.syllableSegments.get(i);
            TextView chosen=key(segment.syllable,12);
            chosen.setContentDescription((segment.locked ? "已选字音节 " : "重新选择音节 ")+segment.syllable);
            chosen.setEnabled(!segment.locked);
            styleModeKey(chosen,index == state.activeSyllableSegment);
            chosen.setOnClickListener(v -> {if(keyboard.activateSyllable(index))syncComposition();});
            syllables.addView(chosen,new LinearLayout.LayoutParams(-1,dp(32)));
        }
        if (state.canUndoSyllable) {
            TextView reset=key("取消",12);reset.setContentDescription("撤销音节选择");
            reset.setOnClickListener(v -> selectSyllable(""));
            syllables.addView(reset,new LinearLayout.LayoutParams(-1,dp(36)));
        }
        for (String value:state.syllables) {
            TextView item=key(value,14);
            item.setContentDescription("选择拼音音节 "+value);
            styleModeKey(item,value.equals(state.selectedSyllable));
            item.setOnClickListener(v -> selectSyllable(value));
            LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,dp(36));
            params.bottomMargin=dp(2);syllables.addView(item,params);
        }
    }
    /** Incrementally update candidate views: reuse existing TextViews when count matches, only rebuild on size change. */
    private void updateCandidateViews(ChineseInputEngine.Snapshot state) {
        java.util.List<String> current=new java.util.ArrayList<>();
        java.util.List<Integer> sourceIndices=new java.util.ArrayList<>();
        for(int i=0;i<state.candidates.size();i++)if(keyboard.displayableCandidate(state.candidates.get(i))) {
            current.add(state.candidates.get(i));sourceIndices.add(i);
        }
        int page=state.page;boolean hasNext=state.hasNext;
        if(current.isEmpty()) {
            candidates.removeAllViews();
            lastCandidateList=java.util.Collections.emptyList();
            lastCandidateSourceIndices=java.util.Collections.emptyList();
            lastCandidatePage=-1;lastCandidateHasNext=false;
            candidateScroller.setVisibility(View.INVISIBLE);
            return;
        }
        candidateScroller.setVisibility(View.VISIBLE);
        boolean pageChanged = page != lastCandidatePage;
        boolean navChanged = hasNext != lastCandidateHasNext;
        boolean sizeChanged = current.size() != lastCandidateList.size();
        boolean mappingChanged=!sourceIndices.equals(lastCandidateSourceIndices);
        boolean needRebuild = sizeChanged || mappingChanged || candidates.getChildCount() != (current.size() + 2);

        if (needRebuild) {
            candidates.removeAllViews();
            TextView previous=key("‹",18); previous.setEnabled(page > 0);
            previous.setOnClickListener(v -> { keyboard.previousPage(); render(); });
            candidates.addView(previous,new LinearLayout.LayoutParams(dp(36),-1));
            for (int i=0;i<current.size();i++) {
                final int sourceIndex=sourceIndices.get(i); TextView candidate=key(current.get(i),18);
                candidate.setPadding(dp(12),0,dp(12),0); candidate.setOnClickListener(v -> selectCandidate(sourceIndex));
                candidates.addView(candidate,new LinearLayout.LayoutParams(-2,-1));
            }
            TextView next=key("›",18); next.setEnabled(hasNext);
            next.setOnClickListener(v -> { keyboard.nextPage(); render(); });
            candidates.addView(next,new LinearLayout.LayoutParams(dp(36),-1));
        } else {
            // Reuse existing views: update only changed content, listeners, and enabled state
            if (pageChanged && candidates.getChildCount() > 0) {
                TextView previous = (TextView)candidates.getChildAt(0);
                previous.setEnabled(page > 0);
            }
            for (int i=0;i<current.size();i++) {
                if (i >= lastCandidateList.size() || !current.get(i).equals(lastCandidateList.get(i))) {
                    TextView candidate = (TextView)candidates.getChildAt(i + 1);
                    candidate.setText(current.get(i));
                    final int sourceIndex = sourceIndices.get(i);
                    candidate.setOnClickListener(v -> selectCandidate(sourceIndex));
                }
            }
            if (navChanged && candidates.getChildCount() > current.size() + 1) {
                TextView next = (TextView)candidates.getChildAt(current.size() + 1);
                next.setEnabled(hasNext);
            }
        }
        lastCandidateList = new java.util.ArrayList<>(current);
        lastCandidateSourceIndices = new java.util.ArrayList<>(sourceIndices);
        lastCandidatePage = page;
        lastCandidateHasNext = hasNext;
    }
    private void selectCandidate(int index) {
        if (!panels.isPrimary() || busy()) return;
        if(commitCandidate(index)) syncComposition();
    }
    private boolean commitCandidate(int index) {
        String result=keyboard.select(index);
        if(result==null || result.isEmpty()) {
            message="候选未能确认，原输入已保留";render();return false;
        }
        boolean accepted;
        if(session.state()==InputSession.State.PREVIEW) {editPreview(result,false);accepted=true;}
        else accepted=routeStable(result);
        return accepted;
    }
    /** Settle composition deterministically; mode switches must never deadlock. */
    private boolean settleKeyboardComposition() {
        ChineseInputEngine.Snapshot state=keyboard.snapshot();
        if(state.rawInput.isEmpty())return true;
        int candidate=keyboard.firstCommittableCandidateIndex(state);
        if(candidate>=0)return commitCandidate(candidate);
        clearComposition();
        message="未匹配的九宫格输入已清除";
        return true;
    }
    private void clearComposition() {
        if (keyboard != null) keyboard.reset();
        targets.clearComposing();
        InputConnection connection=getCurrentInputConnection();
        if (connection != null && !activeComposing.isEmpty()) {
            connection.setComposingText("",1); connection.finishComposingText();
        }
        activeComposing="";
    }
    private void commitLiteral(String value) {
        if (!panels.isPrimary() || busy()) return;
        ChineseInputEngine.Snapshot state=keyboard.snapshot();
        if(keyboard.chinese() && !state.rawInput.isEmpty()) {
            int candidate=keyboard.firstCommittableCandidateIndex(state);
            if(candidate>=0) {if(!commitCandidate(candidate))return;}
            else clearComposition();
        }
        if (session.state() == InputSession.State.PREVIEW) { editPreview(value,false); render(); return; }
        routeStable(value);
    }
    private void space() {
        if (!panels.isPrimary()) return;
        if (session.state() == InputSession.State.PREVIEW) {
            ChineseInputEngine.Snapshot state=keyboard.snapshot();
            int candidate=keyboard.firstCommittableCandidateIndex(state);
            if (candidate>=0) selectCandidate(candidate);
            else if (state.rawInput.isEmpty()) editPreview(" ",false);
            return;
        }
        if (busy()) return;
        ChineseInputEngine.Snapshot state=keyboard.snapshot();
        int candidate=keyboard.firstCommittableCandidateIndex(state);
        if (keyboard.chinese() && candidate>=0) selectCandidate(candidate);
        else {
            if(!state.rawInput.isEmpty())clearComposition();
            commitLiteral(" ");
        }
    }
    private void backspace() {
        if (!panels.isPrimary() || busy()) return;
        // Deleting an unfinished decoder composition is local to the IME and must
        // remain available even while the host editor readiness probe is stale.
        if (!keyboard.snapshot().rawInput.isEmpty()) {
            keyboard.backspace();
            if(session.state()==InputSession.State.PREVIEW)render(); else syncComposition();
            return;
        }
        if(!targets.isRemote() && editorReadiness()!=EditorReadinessPolicy.State.READY) {
            message="请先点击要编辑的输入框";render();return;
        }
        if (session.state() == InputSession.State.PREVIEW) {
            editPreview("",true);
            return;
        }
        if (targets.isRemote()) {
            targets.deleteBackward(); persistRemoteState(false); message=""; render(); return;
        }
        InputConnection connection=getCurrentInputConnection();
        if (connection != null) {
            try {
            // Terminal InputConnections can acknowledge surrounding deletion without
            // editing the PTY. A DEL key is the terminal's native deletion path.
            if (rawEditor()) {
                connection.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DEL));
                connection.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_UP,KeyEvent.KEYCODE_DEL));
                return;
            }
            CharSequence selected=connection.getSelectedText(0);
            boolean deleted;
            if (selected != null && selected.length() > 0) deleted=connection.commitText("",1);
            else {
                deleted=connection.deleteSurroundingTextInCodePoints(1,0);
                if (!deleted) deleted=connection.deleteSurroundingText(1,0);
            }
            if (!deleted) {
                connection.sendKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN,android.view.KeyEvent.KEYCODE_DEL));
                connection.sendKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_UP,android.view.KeyEvent.KEYCODE_DEL));
            }
            } finally {editorContentChanged();}
        }
    }
    private void enter() {
        if (!panels.isPrimary()) return;
        if(!targets.isRemote() && editorReadiness()!=EditorReadinessPolicy.State.READY) {
            message="请先点击要编辑的输入框";render();return;
        }
        if (session.state() == InputSession.State.PREVIEW) {
            if (android.os.SystemClock.uptimeMillis() < previewCommitReadyAt) {
                message="识别刚完成，请检查文字后再上屏";render();return;
            }
            confirmVoice();
            return;
        }
        if (session.state() == InputSession.State.LISTENING) { toggleVoice(); return; }
        if (session.state() == InputSession.State.RECOGNIZING) return;
        ChineseInputEngine.Snapshot keyboardState=keyboard.snapshot();
        if (!keyboardState.rawInput.isEmpty()) {
            if(keyboard.mode()==KeyboardController.Mode.T9) {
                int candidate=keyboard.firstCommittableCandidateIndex(keyboardState);
                if(candidate>=0) {selectCandidate(candidate);return;}
                clearComposition();
                // Fall through: Enter keeps its host action even after recovery.
            } else {
            // Conventional Chinese IME contract: Space chooses the first Chinese
            // candidate; Enter commits exactly the Latin letters the user typed.
            // This also gives an explicit escape hatch for names, commands and URLs.
                if(routeStable(keyboardState.rawInput,"ime_keyboard")) {
                    keyboard.reset();
                    syncComposition();
                }
                return;
            }
        }
        if (targets.isRemote()) { sendRemoteDraft(); return; }
        InputConnection connection=getCurrentInputConnection(); EditorInfo info=getCurrentInputEditorInfo();
        if (connection == null) return;
        int action=info == null ? EditorInfo.IME_ACTION_NONE : info.imeOptions & EditorInfo.IME_MASK_ACTION;
        if (info != null && (info.imeOptions & EditorInfo.IME_FLAG_NO_ENTER_ACTION) == 0
                && action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED)
            connection.performEditorAction(action);
        else routeStable("\n","ime_keyboard");
    }
    private void moveCursor(int direction) {
        if (!panels.isPrimary() || busy()) return;
        if (session.state() == InputSession.State.PREVIEW) {
            if (!keyboard.snapshot().rawInput.isEmpty()) { message="请先确认拼音再移动光标"; render(); return; }
            int current=Math.max(0,preview.getSelectionStart());
            String text=preview.getText().toString();
            if ((direction < 0 && current > 0) || (direction > 0 && current < text.length()))
                preview.setSelection(Character.offsetByCodePoints(text,current,direction));
            return;
        }
        if (targets.isRemote()) {
            targets.moveCursor(direction); persistRemoteState(false); message=""; render(); return;
        }
        if (!keyboard.snapshot().rawInput.isEmpty()) { message="请先确认拼音再移动光标"; render(); return; }
        InputConnection connection=getCurrentInputConnection();
        if (connection != null) {
            int code=direction < 0 ? android.view.KeyEvent.KEYCODE_DPAD_LEFT : android.view.KeyEvent.KEYCODE_DPAD_RIGHT;
            connection.sendKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN,code));
            connection.sendKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_UP,code));
        }
    }
    private void editPreview(String value,boolean delete) {
        int a=Math.max(0,preview.getSelectionStart()),b=Math.max(0,preview.getSelectionEnd());
        int start=Math.min(a,b),end=Math.max(a,b);
        if (delete && start == end && start > 0)
            start=Character.offsetByCodePoints(preview.getText(),start,-1);
        preview.getText().replace(start,end,delete ? "" : value);
        preview.setSelection(start+(delete ? 0 : value.length()));
        syncVoiceDraft();
        confirm.setEnabled(preview.length() > 0);
        render();
    }
    private boolean busy() {
        return session.state() == InputSession.State.LISTENING || session.state() == InputSession.State.RECOGNIZING;
    }
    private void selectVoiceMode() {
        stopDeleteRepeat();
        if(sensitiveField)return;
        if(busy())interruptRecognitionForModeSwitch();
        if(!settleKeyboardComposition())return;
        voiceMode=true;message="";render();
    }
    private void toggleVoice() {
        if(!panels.isPrimary() || sensitiveField)return;
        if(!targets.isRemote() && editorReadiness()!=EditorReadinessPolicy.State.READY) {
            message="请先点击要输入文字的位置";render();return;
        }
        if(session.state()==InputSession.State.PREVIEW && !session.preview().isEmpty()) {
            message="还有未写入文字（"+codePointCount(session.preview())+"字），请上屏或取消";render();return;
        }
        if(session.state()==InputSession.State.LISTENING) {speech.finish();message="识别中";render();return;}
        if(session.state()==InputSession.State.RECOGNIZING) {interruptRecognitionForModeSwitch();message="已停止";render();return;}
        if(!settleKeyboardComposition())return;
        voiceMode=true;
        if(session.state()==InputSession.State.PREVIEW){message="请先处理未写入文字";render();return;}
        InputSession.Identity current=session.identity();
        long generation=session.begin(current.targetId,current.packageName,current.fieldId,current.inputType).generation;
        inlineVoiceText="";suspendedPreview="";voiceSamples=0;speechDetected=false;audioCaptureReady=false;
        voiceAnchor=lastSelectionStart;voiceExpected=lastSelectionStart;voiceTarget=currentTargetKey();
        if(session.startListening(generation)) {message="准备收音";speech.start(generation,this);}
        render();
    }
    private void finishInlineSpeech(String text) {
        String finalText=text==null ? "" : text;
        voiceWriting=true;
        boolean accepted;
        try {
            accepted=finalText.isEmpty() || routeStable(finalText,"ime_voice");
            InputConnection connection=getCurrentInputConnection();
            if(!targets.isRemote() && connection!=null) {
                if(!accepted && !activeComposing.isEmpty()) {
                    // A refused final commit must not leave a partial that would duplicate on retry.
                    unsafeRetry=!connection.setComposingText("",1);
                }
                connection.finishComposingText();
            }
        } finally {voiceWriting=false;}
        activeComposing="";inlineVoiceText="";voiceSamples=0;speechDetected=false;audioCaptureReady=false;
        InputSession.Identity current=session.identity();
        session.begin(current.targetId,current.packageName,current.fieldId,current.inputType);
        if(!accepted) {session.retainRejectedCommit(session.generation(),finalText);suspendedPreview=finalText;
            preview.setText(finalText);message="输入失败，可重试";}
    }
    private void cancelVoice() {
        speech.cancel();finishInlineSpeech(inlineVoiceText);message="已停止";render();
    }
    private void confirmVoice() {
        if(unsafeRetry) {message="输入框未确认清理，请先检查宿主文字";render();return;}
        if (session.state() != InputSession.State.PREVIEW || sensitiveField) return;
        if (!keyboard.snapshot().rawInput.isEmpty()) { message="请先确认或删除拼音"; render(); return; }
        syncVoiceDraft();
        String text=session.preview();
        if (text.isEmpty() || !routeStable(text,"ime_voice")) { render(); return; }
        session.commit(session.generation());
        suspendedPreview=""; voiceDraftPrefix="";voiceDraftSuffix=""; voiceSamples=0;
        speechDetected=false;previewCommitReadyAt=0;
        if (preview != null) preview.setText("");
        draftSelectionStart=draftSelectionEnd=0;
        InputSession.Identity current=session.identity();
        session.begin(current.targetId,current.packageName,current.fieldId,current.inputType);
        if (message.isEmpty()) message=targets.isRemote() ? "已加入待发送文字" : "已输入";
        render();
    }
    @Override public void onListening(long generation) {
        main.post(() -> { if (session.isCurrent(generation) && session.state() == InputSession.State.LISTENING) {
            voiceMode=true;audioCaptureReady=true;speechDetected=false;message="等待说话 · 5秒内未说话将自动退出";render(); } });
    }
    @Override public void onRecognizing(long generation) {
        main.post(() -> { if (session.startRecognizing(generation)) {audioCaptureReady=false;message="本地识别中";render(); } });
    }
    @Override public void onPartial(long generation,String text) {
        main.post(() -> {
            if(!editorBound || !VoiceDockPolicy.acceptsResult(session.generation(),generation,voiceTarget,currentTargetKey())||!busy()||text==null||text.trim().isEmpty())return;
            inlineVoiceText=text;
            if(!targets.isRemote() && !rawEditor()) {
                InputConnection connection=getCurrentInputConnection();
                if(connection!=null) {
                    voiceExpected=voiceAnchor<0 ? -1 : voiceAnchor+text.length();voiceWriting=true;
                    try {if(connection.setComposingText(text,1))activeComposing=text;}finally{voiceWriting=false;}
                }
            }
            render();
        });
    }
    @Override public void onSpeechDetected(long generation) {
        main.post(() -> { if (session.isCurrent(generation) && session.state() == InputSession.State.LISTENING) {
            speechDetected=true;message="已听到语音 · 停顿6秒自动完成，也可点右侧停止";render();
        } });
    }
    @Override public void onAudioProgress(long generation,long samples) {
        main.post(() -> { if (session.isCurrent(generation) && session.state() == InputSession.State.LISTENING) {
            voiceSamples=samples; render();
        } });
    }
    @Override public void onResult(long generation,String text) {
        main.post(() -> {
            if(!editorBound || !VoiceDockPolicy.acceptsResult(session.generation(),generation,voiceTarget,currentTargetKey())||!busy())return;
            String result=text==null || text.trim().isEmpty() ? inlineVoiceText : text;
            finishInlineSpeech(result);
            if(session.state()!=InputSession.State.PREVIEW)message=result.isEmpty() ? "未听到语音" : targets.isRemote() ? "待发送" : "已输入";
            render();
        });
    }
    @Override public void onError(long generation,String error) {
        main.post(() -> {
            if(!editorBound || !VoiceDockPolicy.acceptsResult(session.generation(),generation,voiceTarget,currentTargetKey())||!busy())return;
            audioCaptureReady=false;finishInlineSpeech(inlineVoiceText);
            String detail=error==null?"":error.trim();
            message=detail.isEmpty()?"识别停止 · 点语音重试":detail;
            render();
        });
    }

    private String visibleVoiceDraft() {
        if (session.state() == InputSession.State.PREVIEW)
            return preview == null ? session.preview() : preview.getText().toString();
        String visible=preview == null ? "" : preview.getText().toString();
        if (visible == null || visible.trim().isEmpty()) visible=suspendedPreview;
        if (visible == null || visible.trim().isEmpty()) visible=voiceDraftPrefix;
        return visible == null ? "" : visible;
    }

    private boolean routeStable(String text) {
        String source=keyboard.chinese() ? (keyboard.mode() == KeyboardController.Mode.T9
                ? "ime_t9" : "ime_pinyin") : "ime_keyboard";
        return routeStable(text,source);
    }
    private boolean routeStable(String text,String source) {
        if (text == null || text.isEmpty()) return false;
        if (targets.isRemote()) {
            if (!targets.commitStable(text)) {
                message="跨设备草稿当前不可写，文字已保留";
                retainRejectedText(text);render();return false;
            }
            message=persistRemoteState(false) ? "" : "已加入远端草稿，但暂存失败，请勿退出";
            render();return true;
        }
        InputConnection connection=getCurrentInputConnection();
        EditorInfo info=getCurrentInputEditorInfo();
        boolean privateInput=sensitiveField || info == null || (info != null
                && (info.imeOptions & EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0);
        // History-search fields must not produce new history while searching history.
        if (info != null && getPackageName().equals(info.packageName)
                && info.fieldId >= 2701 && info.fieldId <= 2703) privateInput=true;
        String eventId=UUID.randomUUID().toString();
        InputCommitCoordinator.Result result=InputCommitCoordinator.commit(text,privateInput,
                connection == null ? null : value -> connection.commitText(value,1),
                history == null ? null : value -> history.record(localHistorySession,source,"本机",
                        "inserted",eventId,0,value));
        if (!result.accepted) {
            message="输入框未接受文字，草稿已保留，可重试上屏";
            retainRejectedText(text);render();return false;
        }
        activeComposing="";editorContentChanged();
        message=result.error == InputCommitCoordinator.Error.HISTORY_FAILED
                || result.error == InputCommitCoordinator.Error.HISTORY_UNAVAILABLE
                ? "文字已输入，但记录保存失败" : "";
        render();return true;
    }
    private void retainRejectedText(String text) {
        if (sensitiveField) return;
        if (session.state() == InputSession.State.PREVIEW) return;
        if (session.retainRejectedCommit(session.generation(),text)) {
            suspendedPreview=text;
            if (preview != null) { preview.setText(text);preview.setSelection(preview.length()); }
        }
    }

    private void toggleOutputTarget() {
        if(sensitiveField)return;
        if(busy())interruptRecognitionForModeSwitch();
        if(!settleKeyboardComposition())return;
        if(panels.isPrimary())panels.enterSecondary(session.generation(),keyboard.mode(),"",lastSelectionStart,lastSelectionEnd);
        panels.openFeature(ImePanelController.Feature.TRANSFER);render();
    }

    private void legacyToggleOutputTarget() {
        if (!panels.isPrimary() || busy() || session.state() == InputSession.State.PREVIEW) {
            message="请先完成当前输入"; render(); return;
        }
        if (!keyboard.snapshot().rawInput.isEmpty()) { message="请先确认或删除当前拼音"; render(); return; }
        refreshPairing();
        OutputTargetController.ToggleResult result=targets.toggleTarget();
        if (result == OutputTargetController.ToggleResult.NO_PAIRED_DEVICE) {
            message="尚未配对设备，正在打开设备管理"; render(); openTransferWorkspace(); return;
        }
        if (result == OutputTargetController.ToggleResult.SENSITIVE_FIELD) {
            message="敏感输入框禁止跨设备传输"; render(); return;
        }
        clearComposition(); persistRemoteState(false);
        message=targets.isRemote() ? "已切换到 "+targets.deviceLabel()+" · 输入后点发送" : "已切换到本机输入";
        render();
    }

    private void sendRemoteDraft() {
        if (targets.sending()) { message="正在等待电脑回执"; render(); return; }
        refreshPairing();
        if (activePairing == null || !targets.canSend()) {
            message=targets.pendingText().trim().isEmpty() ? "没有可发送的文字"
                    : targets.retryDeviceMismatch() ? "上一条结果未知，请切回原设备重试" : "请先确认拼音或检查设备连接";
            render(); return;
        }
        OutputTargetController.SendRequest request=targets.beginSend(UUID.randomUUID().toString());
        if (request == null) { message="当前文字还不能发送"; render(); return; }
        if (!persistRemoteState(true)) {
            targets.onSendFailed(request.requestId,OutputTargetController.SendFailure.UNKNOWN);
            message="无法保存待发送文字，已停止发送"; render(); return;
        }
        message="正在发送到 "+targets.deviceLabel(); render();
        final PairingConfig pairing=activePairing;
        remoteSender.send(pairing,request,new RemoteTextSender.Callback() {
            @Override public void onAcknowledged(String id,String state,String note,long seq) {
                if (destroyed || !targets.onSendSucceeded(id)) return;
                persistRemoteState(true);
                boolean saved=recordRemote(request,"inserted".equals(state) ? "INSERTED" : "SAVED_NOT_INSERTED",seq);
                message=("inserted".equals(state) ? "已发送到 "+targets.deviceLabel()
                        : "电脑已保存文字，但未输入到当前窗口")+(saved ? "" : "；历史保存失败");
                render();
            }
            @Override public void onRejected(String id,String note) {
                if (destroyed || !targets.onSendFailed(id,OutputTargetController.SendFailure.REJECTED)) return;
                persistRemoteState(true); boolean saved=recordRemote(request,"REJECTED",0);
                message=(note == null || note.isEmpty() ? "电脑拒绝了本次文字" : note)
                        +(saved ? "" : "；历史保存失败"); render();
            }
            @Override public void onUncertain(String id,String note) {
                if (destroyed || !targets.onSendFailed(id,OutputTargetController.SendFailure.UNKNOWN)) return;
                persistRemoteState(true); boolean saved=recordRemote(request,"DELIVERY_UNKNOWN",0);
                message="发送结果未知，文字已保留；再次发送会复用同一编号"
                        +(saved ? "" : "；历史保存失败"); render();
            }
        });
    }

    private boolean recordRemote(OutputTargetController.SendRequest request,String state,long seq) {
        try {
            history.record(remoteHistorySession,"ime_remote",request.deviceId,state,request.requestId,seq,request.text);
            return true;
        } catch (Exception ignored) { return false; }
    }

    private void refreshPairing() {
        activePairing=PairingStore.load(prefs);
        if (activePairing == null) { targets.setPairing("","电脑"); return; }
        String id=activePairing.deviceId.isEmpty() ? "legacy:"+activePairing.host : activePairing.deviceId;
        targets.setPairing(id,PairingStore.label(prefs,activePairing));
    }

    private boolean persistRemoteState(boolean durable) {
        if (prefs == null) return false;
        OutputTargetController.Snapshot state=targets.snapshot();
        SharedPreferences.Editor edit=prefs.edit().putString("ime_remote_pending",state.pending)
                .putString("ime_remote_retry_id",state.retryId)
                .putString("ime_remote_retry_text",state.retryText)
                .putInt("ime_remote_retry_attempt",state.retryAttempt)
                .putString("ime_remote_retry_device",state.retryDeviceId)
                .putInt("ime_remote_cursor",state.cursor);
        return durable ? edit.commit() : apply(edit);
    }

    private boolean apply(SharedPreferences.Editor edit) { edit.apply(); return true; }

    private void restoreRemoteState() {
        targets.restore(new OutputTargetController.Snapshot(
                prefs.getString("ime_remote_pending",""),prefs.getString("ime_remote_retry_id",""),
                prefs.getString("ime_remote_retry_text",""),prefs.getInt("ime_remote_retry_attempt",0),
                prefs.getString("ime_remote_retry_device",""),prefs.getInt("ime_remote_cursor",Integer.MAX_VALUE)));
    }

    private String fieldKey(EditorInfo info) {
        if (info == null) return "";
        return String.valueOf(info.packageName)+"|"+info.fieldId+"|"+String.valueOf(info.fieldName)+"|"+info.inputType;
    }

    private void openCompactMore() {
        if(sensitiveField)return;
        // A user can open settings while speech is active. Preserve what is visible,
        // invalidate the producer, and then open the real menu instead of dropping the tap.
        if(busy())interruptRecognitionForModeSwitch();

        toggleFeaturePanel();
        dockRoot.requestLayout();dockRoot.requestApplyInsets();
    }

    private void toggleFeaturePanel() {
        if (sensitiveField) return;
        if (panels.isPrimary()) {
            if (busy()) { message="请先结束当前语音识别"; render(); return; }
            ChineseInputEngine.Snapshot state=keyboard.snapshot();
            if (!panels.enterSecondary(session.generation(),keyboard.mode(),state.rawInput,
                    lastSelectionStart,lastSelectionEnd)) return;
        } else {
            ImePanelController.ReturnResult result=panels.returnPrimary(session.generation());
            if (!result.restored) message="输入框已变化，已安全返回输入界面";
            rebuildKeys();
        }
        render();
    }

    private void selectFeature(ImePanelController.Feature feature) {
        if (panels.openFeature(feature)) render();
    }

    private void openTransferWorkspace() {openManagement("devices");}
    private void openInputStatistics() {openManagement("history");}
    private void openManagement(String section) {
        if(busy())interruptRecognitionForModeSwitch();
        persistRemoteState(true);
        Intent intent=new Intent(this,HomeActivity.class).putExtra(HomeActivity.EXTRA_SECTION,section);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(intent);
    }

    private void renderSecondary() {
        if (secondaryPanel == null || panels.isPrimary()) return;
        secondaryPanel.removeAllViews();
        ImePanelController.Feature selected=panels.feature();
        addSecondaryHeader(selected);
        if(session.state()==InputSession.State.PREVIEW && !session.preview().isEmpty()) {
            TextView retry=secondaryAction("重试输入已保留的文字",true);
            retry.setOnClickListener(v -> {panels.returnPrimary(session.generation());confirmVoice();});
            retry.setEnabled(!unsafeRetry);
            secondaryPanel.addView(retry,new LinearLayout.LayoutParams(-1,dp(44)));
            TextView retained=secondaryDetail(session.preview());secondaryPanel.addView(retained);
            TextView dismiss=secondaryAction("放弃未写入文字",false);
            dismiss.setOnClickListener(v -> {InputSession.Identity id=session.identity();session.begin(id.targetId,id.packageName,id.fieldId,id.inputType);unsafeRetry=false;suspendedPreview="";preview.setText("");render();});
            secondaryPanel.addView(dismiss,new LinearLayout.LayoutParams(-1,dp(44)));
        }
        if (selected == ImePanelController.Feature.HUB) renderCompactHub();
        else if (selected == ImePanelController.Feature.TRANSFER) renderTransferFeature();
        else if (selected == ImePanelController.Feature.STATISTICS) renderStatisticsFeature();
        else renderSettingsFeature();
        if(compactMenu() && session.state()==InputSession.State.PREVIEW && !session.preview().isEmpty()) {
            LinearLayout recoveryContent=column();
            while(secondaryPanel.getChildCount()>0) {
                View child=secondaryPanel.getChildAt(0);secondaryPanel.removeViewAt(0);
                LinearLayout.LayoutParams params=(LinearLayout.LayoutParams)child.getLayoutParams();
                if(params.weight>0){params.weight=0;params.height=dp(176);}
                recoveryContent.addView(child,params);
            }
            android.widget.ScrollView recoveryScroll=new android.widget.ScrollView(this);
            recoveryScroll.addView(recoveryContent);secondaryPanel.addView(recoveryScroll,new LinearLayout.LayoutParams(-1,-1));
        }
    }

    private void renderCompactHub() {
        // The quick hub is drawn around the mic in radialMenu. The former tall
        // three-row panel duplicated history/statistics, local target and keyboard.
        secondaryPanel.removeAllViews();
    }
    private ImeIconButton hubIcon(LinearLayout row,String icon,String description,View.OnClickListener listener) {
        ImeIconButton view=iconButton(icon,description,listener);
        LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(dp(48),dp(48));params.setMargins(dp(3),dp(3),dp(3),dp(3));
        row.addView(view,params);return view;
    }
    private void selectLocalTarget() {
        if(sensitiveField)return;
        if(targets.isRemote()) {
            OutputTargetController.Snapshot state=targets.snapshot();
            if(!VoiceDockPolicy.canChangeDevice(false,targets.sending(),state.pending,state.retryId)) {
                message="电脑还有待发送或结果未知的文字，请先处理";
                android.widget.Toast.makeText(this,message,android.widget.Toast.LENGTH_SHORT).show();return;
            }
            if(targets.toggleTarget()!=OutputTargetController.ToggleResult.OK)return;
        }
        finishTargetSelection("已切换到本机");
    }

    private void addSecondaryHeader(ImePanelController.Feature selected) {
        if(compactMenu() && selected==ImePanelController.Feature.HUB)return;
        if(compactMenu()) {
            TextView close=secondaryAction("收起 · "+(panels.feature()==ImePanelController.Feature.TRANSFER?"选择目标":"更多"),false);
            close.setOnClickListener(v -> closeTransientControls());secondaryPanel.addView(close,new LinearLayout.LayoutParams(-1,dp(36)));return;
        }
        LinearLayout heading=row();
        TextView back=secondaryAction("‹ 键盘",false);back.setId(4200);
        back.setContentDescription("返回输入键盘，保留当前输入状态");
        back.setOnClickListener(v -> toggleFeaturePanel());
        heading.addView(back,new LinearLayout.LayoutParams(dp(72),dp(40)));
        String value=selected == ImePanelController.Feature.TRANSFER ? "跨设备传输"
                : selected == ImePanelController.Feature.STATISTICS ? "输入记录"
                : selected == ImePanelController.Feature.SETTINGS ? "输入法设置" : "常用功能";
        TextView title=label(18);title.setText(value);title.setTextColor(Color.rgb(28,39,50));
        title.setTypeface(Typeface.DEFAULT,Typeface.BOLD);title.setGravity(Gravity.CENTER_VERTICAL);
        title.setPadding(dp(10),0,dp(6),0);heading.addView(title,new LinearLayout.LayoutParams(0,dp(40),1));
        if (selected != ImePanelController.Feature.HUB) {
            TextView overview=secondaryAction("总览",false);overview.setContentDescription("返回功能总览");
            overview.setOnClickListener(v -> selectFeature(ImePanelController.Feature.HUB));
            heading.addView(overview,new LinearLayout.LayoutParams(dp(60),dp(40)));
        } else {
            TextView switchIme=secondaryAction("切换输入法",false);
            switchIme.setContentDescription("选择其他输入法");
            switchIme.setOnClickListener(v -> ((android.view.inputmethod.InputMethodManager)
                    getSystemService(INPUT_METHOD_SERVICE)).showInputMethodPicker());
            heading.addView(switchIme,new LinearLayout.LayoutParams(dp(88),dp(36)));
        }
        secondaryPanel.addView(heading,new LinearLayout.LayoutParams(-1,dp(42)));
    }

    private void renderFeatureHub() {
        LinearLayout editorActions=row();
        addButton(editorActions,"删除",v -> {panels.returnPrimary(session.generation());backspace();render();},1);
        addButton(editorActions,targets.isRemote() ? "发送到电脑" : editorActionLabel(),v -> {panels.returnPrimary(session.generation());enter();render();},1);
        addButton(editorActions,"其他输入法",v -> ((android.view.inputmethod.InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).showInputMethodPicker(),1);
        secondaryPanel.addView(editorActions,new LinearLayout.LayoutParams(-1,dp(44)));
        refreshPairing();
        LinearLayout transfer=secondaryCard();transfer.setId(4201);
        LinearLayout titleRow=row();
        TextView title=secondaryTitle("跨设备传输");titleRow.addView(title,new LinearLayout.LayoutParams(0,-2,1));
        titleRow.addView(secondaryChip(activePairing == null ? "未配对" : targets.isRemote() ? "电脑输入" : "可用",
                activePairing != null),new LinearLayout.LayoutParams(-2,dp(28)));
        transfer.addView(titleRow);
        String device=activePairing == null ? "添加电脑后，可把文字直接发送到电脑光标"
                : "当前设备："+PairingStore.label(prefs,activePairing)+" · 待发送 "+targets.pendingText().codePointCount(0,targets.pendingText().length())+" 字";
        TextView detail=secondaryDetail(device);transfer.addView(detail);
        LinearLayout actions=row();
        TextView target=secondaryAction(activePairing == null ? "配对电脑" : targets.isRemote() ? "返回本机" : "输入到电脑",true);
        target.setOnClickListener(v -> { if(activePairing==null)openTransferWorkspace();else toggleTargetFromSecondary(); });
        actions.addView(target,new LinearLayout.LayoutParams(0,dp(38),2));
        TextView manage=secondaryAction("设备",false);manage.setOnClickListener(v -> selectFeature(ImePanelController.Feature.TRANSFER));
        LinearLayout.LayoutParams manageParams=new LinearLayout.LayoutParams(0,dp(38),1);manageParams.leftMargin=dp(6);actions.addView(manage,manageParams);
        transfer.addView(actions,new LinearLayout.LayoutParams(-1,dp(38)));
        LinearLayout.LayoutParams transferParams=new LinearLayout.LayoutParams(-1,dp(112));transferParams.topMargin=dp(6);
        secondaryPanel.addView(transfer,transferParams);

        LinearLayout shortcuts=row();
        LinearLayout stats=secondaryShortcut("输入记录",secondaryHistorySummary());stats.setId(4202);
        stats.setOnClickListener(v -> selectFeature(ImePanelController.Feature.STATISTICS));
        LinearLayout settings=secondaryShortcut("设置","语音 · 隐私 · 状态");settings.setId(4203);
        settings.setOnClickListener(v -> selectFeature(ImePanelController.Feature.SETTINGS));
        LinearLayout.LayoutParams left=new LinearLayout.LayoutParams(0,dp(70),1);left.rightMargin=dp(4);
        LinearLayout.LayoutParams right=new LinearLayout.LayoutParams(0,dp(70),1);right.leftMargin=dp(4);
        shortcuts.addView(stats,left);shortcuts.addView(settings,right);
        LinearLayout.LayoutParams shortcutParams=new LinearLayout.LayoutParams(-1,dp(70));shortcutParams.topMargin=dp(6);
        secondaryPanel.addView(shortcuts,shortcutParams);
    }

    private void renderTransferFeature() {
        android.widget.ScrollView list=new android.widget.ScrollView(this);
        LinearLayout choices=column();list.addView(choices);
        TextView local=secondaryAction(targets.isRemote() ? "输入到本机" : "✓ 本机",!targets.isRemote());
        local.setOnClickListener(v -> selectLocalTarget());choices.addView(local,new LinearLayout.LayoutParams(-1,dp(44)));
        for(PairingConfig device:PairingStore.list(prefs)) {
            String id=device.deviceId.isEmpty() ? "legacy:"+device.host : device.deviceId;
            TextView item=secondaryAction((targets.isRemote() && id.equals(targets.deviceId()) ? "✓ " : "")
                    +PairingStore.label(prefs,device),targets.isRemote() && id.equals(targets.deviceId()));
            item.setContentDescription("输入到 "+PairingStore.label(prefs,device));
            item.setOnClickListener(v -> {
                boolean changed=!id.equals(targets.deviceId());
                OutputTargetController.Snapshot state=targets.snapshot();
                if(!VoiceDockPolicy.canChangeDevice(!changed,targets.sending(),state.pending,state.retryId)) {
                    message="原设备还有待发送或结果未知的文字，请先处理";
                    android.widget.Toast.makeText(this,message,android.widget.Toast.LENGTH_SHORT).show();return;
                }
                if(!PairingStore.save(prefs,device)) {message="设备选择保存失败";render();return;}
                refreshPairing();if(!targets.isRemote())targets.toggleTarget();
                finishTargetSelection("输入到 "+targets.deviceLabel());
            });
            LinearLayout.LayoutParams itemParams=new LinearLayout.LayoutParams(-1,dp(44));itemParams.topMargin=dp(6);
            choices.addView(item,itemParams);
        }
        TextView manage=secondaryAction("添加 / 管理电脑",false);manage.setOnClickListener(v -> openTransferWorkspace());
        choices.addView(manage,new LinearLayout.LayoutParams(-1,dp(44)));
        secondaryPanel.addView(list,new LinearLayout.LayoutParams(-1,0,1));
        secondaryPanel.addView(secondaryDetail("电脑文字保留为独立草稿，点击发送才传出。"));
    }
    private void finishTargetSelection(String notice) {
        clearComposition();persistRemoteState(false);panels.returnPrimary(session.generation());message=notice;render();
    }

    private void renderStatisticsFeature() {
        LinearLayout card=secondaryCard();
        card.addView(secondaryTitle("今天的输入记录"));
        TextView number=label(20);number.setText(secondaryHistorySummary());number.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        number.setTextColor(Color.rgb(38,74,92));number.setPadding(0,dp(5),0,dp(4));card.addView(number);
        card.addView(secondaryDetail("记录保存在手机本机，可按日期查询并导出。"));
        LinearLayout.LayoutParams cardParams=new LinearLayout.LayoutParams(-1,dp(102));cardParams.topMargin=dp(7);secondaryPanel.addView(card,cardParams);
        TextView open=secondaryAction("查看全部输入记录",true);open.setId(4220);open.setOnClickListener(v -> openInputStatistics());
        LinearLayout.LayoutParams openParams=new LinearLayout.LayoutParams(-1,dp(44));openParams.topMargin=dp(8);secondaryPanel.addView(open,openParams);
    }

    private void renderSettingsFeature() {
        boolean microphone=checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)==android.content.pm.PackageManager.PERMISSION_GRANTED;
        LinearLayout card=secondaryCard();
        card.addView(secondaryTitle("当前状态"));
        TextView voiceState=secondaryDetail("本地语音　"+(microphone ? "已授权" : "需要麦克风权限"));
        voiceState.setTextColor(microphone ? Color.rgb(38,74,92) : Color.rgb(176,72,45));card.addView(voiceState);
        EditorReadinessPolicy.State ready=editorReadiness();
        String readyLabel=ready==EditorReadinessPolicy.State.READY ? "已连接输入框"
                : ready==EditorReadinessPolicy.State.STALE_EDITOR ? "输入目标已切换"
                : ready==EditorReadinessPolicy.State.DISCONNECTED ? "输入连接已断开" : "请先点击输入框";
        card.addView(secondaryDetail("输入检测　"+readyLabel));
        String caretLabel=!cursorAnchorReceived ? "应用未报告光标位置"
                : (cursorAnchorFlags & android.view.inputmethod.CursorAnchorInfo.FLAG_HAS_VISIBLE_REGION)!=0 ? "应用报告光标可见"
                : (cursorAnchorFlags & android.view.inputmethod.CursorAnchorInfo.FLAG_HAS_INVISIBLE_REGION)!=0 ? "应用报告光标在不可见区" : "可见性未知";
        card.addView(secondaryDetail("光标检测　"+caretLabel));
        TextView privacy=secondaryDetail("密码框自动关闭语音、历史和跨设备输出");card.addView(privacy);
        LinearLayout.LayoutParams cardParams=new LinearLayout.LayoutParams(-1,dp(140));cardParams.topMargin=dp(7);secondaryPanel.addView(card,cardParams);
        TextView open=secondaryAction("打开详细设置",true);open.setId(4230);open.setOnClickListener(v -> openSettings());
        LinearLayout.LayoutParams openParams=new LinearLayout.LayoutParams(-1,dp(44));openParams.topMargin=dp(8);secondaryPanel.addView(open,openParams);
    }

    private void toggleTargetFromSecondary() {
        refreshPairing();
        if (activePairing == null) { openTransferWorkspace(); return; }
        OutputTargetController.ToggleResult result=targets.toggleTarget();
        if (result != OutputTargetController.ToggleResult.OK) { message="当前输入框不能切换输出目标"; render(); return; }
        clearComposition();persistRemoteState(false);
        panels.returnPrimary(session.generation());rebuildKeys();
        message=targets.isRemote() ? "已切换到 "+targets.deviceLabel()+" · 输入后点发送" : "已切换到本机输入";
        render();
    }

    private String secondaryHistorySummary() {
        if (history == null) return "暂无记录";
        long since=java.time.LocalDate.now().atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
        try {
            InputHistoryStore.Summary summary=history.summary("",since,System.currentTimeMillis());
            return "今日 "+summary.recordCount+" 条记录 · "+summary.codePointCount+" 字";
        } catch(RuntimeException ignored) { return "记录暂不可读"; }
    }

    private LinearLayout secondaryCard() {
        LinearLayout card=column();card.setPadding(dp(11),dp(9),dp(11),dp(9));
        card.setBackground(surface(Color.WHITE,10));return card;
    }
    private LinearLayout secondaryShortcut(String title,String detail) {
        LinearLayout card=secondaryCard();card.setGravity(Gravity.CENTER_VERTICAL);
        card.addView(secondaryTitle(title));card.addView(secondaryDetail(detail));
        card.setClickable(true);card.setFocusable(true);return card;
    }
    private TextView secondaryTitle(String value) {
        TextView title=label(15);title.setText(value);title.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        title.setTextColor(Color.rgb(28,39,50));title.setSingleLine(true);return title;
    }
    private TextView secondaryDetail(String value) {
        TextView detail=label(12);detail.setText(value);detail.setTextColor(Color.rgb(94,105,116));
        detail.setSingleLine(false);detail.setMaxLines(2);detail.setEllipsize(android.text.TextUtils.TruncateAt.END);
        detail.setPadding(0,dp(3),0,dp(3));return detail;
    }
    private TextView secondaryChip(String value,boolean active) {
        TextView chip=label(11);chip.setText(value);chip.setGravity(Gravity.CENTER);chip.setSingleLine(true);
        chip.setTextColor(active ? Color.rgb(0,112,88) : Color.rgb(94,105,116));
        chip.setBackground(fill(active ? Color.rgb(226,235,241) : Color.rgb(235,240,243),14));
        chip.setPadding(dp(8),0,dp(8),0);return chip;
    }
    private TextView secondaryAction(String value,boolean primary) {
        TextView action=label(13);action.setText(value);action.setGravity(Gravity.CENTER);action.setSingleLine(true);
        action.setTypeface(Typeface.DEFAULT,Typeface.BOLD);action.setClickable(true);action.setFocusable(true);
        action.setTextColor(primary ? Color.WHITE : Color.rgb(38,55,68));
        action.setBackground(primary ? fill(Color.rgb(38,74,92),9) : surface(Color.rgb(244,247,249),9));
        return action;
    }

    @Override public void onUpdateSelection(int oldSelStart,int oldSelEnd,int newSelStart,int newSelEnd,
                                             int candidatesStart,int candidatesEnd) {
        super.onUpdateSelection(oldSelStart,oldSelEnd,newSelStart,newSelEnd,candidatesStart,candidatesEnd);
        lastSelectionStart=newSelStart; lastSelectionEnd=newSelEnd;contextSelectionFresh=true;
        scheduleEditorContextProbe();refreshEditorControls();
        if(busy() && !targets.isRemote()) {
            if(VoiceDockPolicy.cursorDiverged(voiceWriting,oldSelStart,oldSelEnd,newSelStart,newSelEnd,candidatesEnd,voiceExpected)) {
                speech.cancel();
                InputConnection c=getCurrentInputConnection();if(c!=null)c.finishComposingText();
                activeComposing="";inlineVoiceText="";
                InputSession.Identity current=session.identity();session.begin(current.targetId,current.packageName,current.fieldId,current.inputType);
                message="光标已移动 · 点击语音继续";render();
            }
            return;
        }
        if (!targets.isRemote() && !activeComposing.isEmpty()) {
            if(keyboardWriting)return;
            InputConnection connection=getCurrentInputConnection();
            android.view.inputmethod.ExtractedText current=keyboardEditorState(connection);
            // A delayed callback describes an older cursor. It cannot cancel a newer write.
            if(current!=null && current.selectionStart>=0 && current.selectionEnd>=0
                    && (current.startOffset+current.selectionStart!=newSelStart
                        || current.startOffset+current.selectionEnd!=newSelEnd))return;
            if(newSelStart==newSelEnd && (newSelStart==candidatesEnd
                    || (keyboardExpectedCursor>=0 && newSelStart==keyboardExpectedCursor)))return;
            // Actual cursor movement ends composition, but never deletes visible text.
            keyboard.reset();targets.clearComposing();activeComposing="";
            keyboardAnchor=-1;keyboardExpectedCursor=-1;
            if(connection!=null)connection.finishComposingText();
            message="光标已移动，已保留输入文字";render();
        }
    }

    private android.view.inputmethod.ExtractedText keyboardEditorState(InputConnection connection) {
        if(connection==null)return null;
        android.view.inputmethod.ExtractedTextRequest request=new android.view.inputmethod.ExtractedTextRequest();
        request.hintMaxChars=256;request.hintMaxLines=4;
        return connection.getExtractedText(request,0);
    }

    @Override public boolean onEvaluateFullscreenMode() { return false; }

    @Override public boolean onKeyDown(int keyCode,KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && !panels.isPrimary() && event.getRepeatCount() == 0) {
            secondaryBackDown=true; toggleFeaturePanel(); return true;
        }
        return super.onKeyDown(keyCode,event);
    }

    @Override public boolean onKeyUp(int keyCode,KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && secondaryBackDown) {
            secondaryBackDown=false; return true;
        }
        return super.onKeyUp(keyCode,event);
    }

    private void render() {
        if (keyboard == null || status == null) return;
        render(keyboard.snapshot());
    }
    private void render(ChineseInputEngine.Snapshot state) {
        if (keyboard == null || status == null || state == null) return;
        boolean primary=panels.isPrimary(), remote=targets.isRemote();
        boolean compact=compactMenu();
        android.view.ViewGroup parent=(android.view.ViewGroup)secondaryPanel.getParent();
        if(compact && parent!=dockRoot){parent.removeView(secondaryPanel);dockRoot.addView(secondaryPanel);}
        else if(!compact && parent!=normalRoot){parent.removeView(secondaryPanel);normalRoot.addView(secondaryPanel,new LinearLayout.LayoutParams(-1,dp(280)));}
        if(compact && panels.feature()==ImePanelController.Feature.TRANSFER){FrameLayout.LayoutParams menuParams=new FrameLayout.LayoutParams(dp(224),dp(264),Gravity.RIGHT|Gravity.BOTTOM);menuParams.rightMargin=dp(12);menuParams.bottomMargin=dp(136);secondaryPanel.setLayoutParams(menuParams);secondaryPanel.setBackground(surface(Color.WHITE,18));}
        int height=CompactImeUiPolicy.keyboardHeightDp(getResources().getConfiguration().orientation==Configuration.ORIENTATION_LANDSCAPE,!state.rawInput.isEmpty(),remote);
        android.view.ViewGroup.LayoutParams keyPanel=primaryPanel.getLayoutParams();
        if(keyPanel.height!=dp(height)){keyPanel.height=dp(height);primaryPanel.setLayoutParams(keyPanel);}
        if(floatingDock!=null) {
            boolean dock=dockVisible();
            floatingDock.setVisibility(dock ? View.VISIBLE : View.GONE);normalRoot.setVisibility(dock || compact ? View.GONE : View.VISIBLE);
            boolean quick=showVoiceEditingActions();
            dockDelete.setVisibility(quick ? (hasDeletableContext() && !punctuationSelecting?View.VISIBLE:View.INVISIBLE) : View.GONE);
            dockAction.setVisibility(quick ? View.VISIBLE : View.GONE);

            String actionDescription=session.state()==InputSession.State.PREVIEW ? "上屏" : remote ? "发送" : editorActionLabel();
            dockAction.setIcon(actionIcon(actionDescription));dockAction.setContentDescription(actionDescription);
            if(android.os.Build.VERSION.SDK_INT>=26)dockAction.setTooltipText(actionDescription);refreshEditorControls();
            MicStatusVisualPolicy.Ring micRing=MicStatusVisualPolicy.ring(session.state());
            String micAction=busy()?session.state()==InputSession.State.LISTENING?"点击停止录音":"点击取消识别":"单击开始语音，双击切换键盘，长按打开快捷菜单，上滑选择标点";
            micIcon.setContentDescription(MicStatusVisualPolicy.label(micRing)+"；"+micAction+(remote?"；目标电脑 "+targets.deviceLabel():"；目标本机")+(message.isEmpty()?"":"；"+message));
            if(android.os.Build.VERSION.SDK_INT>=30)micIcon.setStateDescription(MicStatusVisualPolicy.label(micRing));
            micIcon.syncRingAnimation();micIcon.invalidate();
            if(remoteDockPreview!=null) {
                remoteDockPreview.setVisibility(dock && remote && !compact ? View.VISIBLE : View.GONE);
                String remoteText=targets.pendingText()+inlineVoiceText;
                remoteDockPreview.setText("输入到 "+targets.deviceLabel()+" · 识别后点下方发送\n"+(remoteText.isEmpty() ? "尚无待发送文字" : remoteText));
            }
            dockVoice.setText(session.state()==InputSession.State.LISTENING ? "停止" : session.state()==InputSession.State.RECOGNIZING ? "取消" : "语音");
            dockTarget.setText(remote ? shortDeviceLabel(targets.deviceLabel()) : "本机");
            dockSend.setVisibility(remote ? View.VISIBLE : View.GONE);dockSend.setEnabled(targets.canSend() && !busy());
            dockStatus.setText(busy() ? session.state()==InputSession.State.LISTENING ? (audioCaptureReady?"正在收音":"准备收音") : "识别中" : remote ? "草稿 "+codePointCount(targets.pendingText())+"字" : message.isEmpty() ? "点按说话" : message);
            // Do not derive a WRAP_CONTENT viewport from a child positioned as a
            // fraction of that same viewport. That creates a resize feedback loop.
            // Keep the voice viewport stable across editing, hub and device menus.
            int areaHeight=Math.min(dp(280),Math.max(dp(48),getResources().getDisplayMetrics().heightPixels-dp(48)));
            int rootHeight=dock || compact ? areaHeight : android.view.ViewGroup.LayoutParams.WRAP_CONTENT;
            android.view.ViewGroup.LayoutParams rootParams=dockRoot.getLayoutParams();
            if(rootParams==null)dockRoot.setLayoutParams(new FrameLayout.LayoutParams(-1,rootHeight));
            else if(rootParams.height!=rootHeight){rootParams.height=rootHeight;dockRoot.setLayoutParams(rootParams);}
            int min=dock || compact ? areaHeight : 0;
            if(dockRoot.getMinimumHeight()!=min){dockRoot.setMinimumHeight(min);dockRoot.requestLayout();}
            placeFloatingDock();
        }
        boolean isPreview=session.state() == InputSession.State.PREVIEW;
        boolean radialOpen=compact && panels.feature()==ImePanelController.Feature.HUB;
        if (primaryPanel != null) primaryPanel.setVisibility(primary ? View.VISIBLE : View.GONE);
        if (secondaryPanel != null) secondaryPanel.setVisibility(primary || radialOpen ? View.GONE : View.VISIBLE);
        if(radialMenu!=null)radialMenu.setVisibility(radialOpen?View.VISIBLE:View.GONE);
        if (function != null) { function.setText(primary ? "功能" : "返回"); function.setEnabled(!sensitiveField); }
        if (targetKey != null) {
            targetKey.setText(remote ? shortDeviceLabel(targets.deviceLabel()) : "本机");
            targetKey.setEnabled(primary && !sensitiveField);
        }
        if (targetHeader != null) targetHeader.setText(remote ? "输入到："+targets.deviceLabel() : "输入到：本机");
        if (enterKey != null) {
            InputSession.State speechState=session.state();
            String actionLabel=isPreview ? "上屏"
                    : speechState == InputSession.State.LISTENING ? "停止"
                    : speechState == InputSession.State.RECOGNIZING ? "识别中"
                    : remote ? (targets.sending() ? "等待" : "发送") : editorActionLabel();
            enterKey.setText(actionLabel);enterKey.setContentDescription(actionLabel);
            if(enterKey instanceof ImeIconButton) ((ImeIconButton)enterKey).setIcon(
                    "发送".equals(actionLabel)?"send":"搜索".equals(actionLabel)?"search":
                    "停止".equals(actionLabel)?"stop":"上屏".equals(actionLabel)?"check":"enter");
            boolean enabled=speechState != InputSession.State.RECOGNIZING
                    && (isPreview || speechState == InputSession.State.LISTENING || !remote || targets.canSend());
            enterKey.setEnabled(primary && enabled);
        }
        if (!primary) { renderSecondary(); return; }
        boolean voiceSurface=voiceMode || busy();
        if (isPreview && preview != null && !session.preview().contentEquals(preview.getText())) {
            updatingPreview=true;
            try {
                preview.setText(session.preview());
                int length=preview.length();
                preview.setSelection(Math.min(draftSelectionStart,length),Math.min(draftSelectionEnd,length));
            } finally { updatingPreview=false; }
        }
        preview.setVisibility(View.GONE);
        int draftLength=codePointCount(preview == null ? "" : preview.getText().toString());
        preview.setTextSize(ImeLayoutSpec.draftTextSizeSp(draftLength));
        preview.setMinLines(voiceSurface ? 3 : 1);
        preview.setMaxLines(voiceSurface ? 5 : 2);
        preview.setFocusable(isPreview); preview.setFocusableInTouchMode(isPreview); preview.setCursorVisible(isPreview);
        preview.setHint(session.state() == InputSession.State.LISTENING ? (audioCaptureReady?"正在收音…":"正在准备麦克风…")
                : session.state() == InputSession.State.RECOGNIZING ? "正在生成最终文字…"
                : isPreview ? "可修改；点左侧“续说”追加，点右侧“上屏”写入输入框" : "点击左侧“语音”开始说话");
        LinearLayout.LayoutParams previewParams=(LinearLayout.LayoutParams)preview.getLayoutParams();
        previewParams.height=voiceSurface ? 0 : dp(44);
        previewParams.weight=voiceSurface ? 1 : 0;
        preview.setLayoutParams(previewParams);
        remoteDraft.setVisibility(remote ? View.VISIBLE : View.GONE);
        if (remote) {
            String draft=targets.pendingText();
            if (draft.isEmpty()) remoteDraft.setText("输入到 "+targets.deviceLabel()+" · 待发送文字");
            else {
                int cursor=Math.max(0,Math.min(targets.cursor(),draft.length()));
                remoteDraft.setText("输入到 "+targets.deviceLabel()+"\n"+draft.substring(0,cursor)+"│"+draft.substring(cursor));
            }
        }
        voiceActions.setVisibility(View.GONE);
        confirm.setVisibility(isPreview ? View.VISIBLE : View.GONE);
        confirm.setEnabled(isPreview && preview.length() > 0);
        composition.setVisibility(voiceSurface ? View.GONE : state.rawInput.isEmpty() ? View.INVISIBLE : View.VISIBLE);
        candidateScroller.setVisibility(voiceSurface ? View.GONE : state.rawInput.isEmpty() ? View.INVISIBLE : View.VISIBLE);
        if (!busy() && !voiceSurface) {
            String preedit = keyboard.displayPreedit(state);
            if (!preedit.equals(lastRenderedPreedit)) {
                lastRenderedPreedit = preedit;
                composition.setText(preedit);
            }
        } else if (!lastRenderedPreedit.isEmpty()) {
            lastRenderedPreedit = "";
            composition.setText("");
        }
        renderSyllables(state,!voiceSurface && keyboard.mode() == KeyboardController.Mode.T9);
        if (!busy() && keyboard.chinese()) {
            updateCandidateViews(state);
        } else if (!lastCandidateList.isEmpty()) {
            lastCandidateList = java.util.Collections.emptyList();
            lastCandidateSourceIndices = java.util.Collections.emptyList();
            lastCandidatePage = -1;
            lastCandidateHasNext = false;
            candidates.removeAllViews();
        }
        if (sensitiveField) status.setText("敏感输入框 · 语音、历史和跨设备链路已关闭");
        else if (session.state() == InputSession.State.LISTENING && !speechDetected) {
            double remaining=Math.max(0,(SpeechEndpointDetector.NO_SPEECH_MS-voiceSamples*1000.0/16000.0)/1000.0);
            status.setText(String.format(java.util.Locale.ROOT,"等待说话 · %.1f 秒后自动退出",remaining));
        }
        else if (session.state() == InputSession.State.LISTENING)
            status.setText(String.format(java.util.Locale.ROOT,"持续听写 · %.1f 秒 · 停顿6秒自动完成",voiceSamples/16000.0));
        else if (!message.isEmpty()) status.setText(message);
        else if (keyboard.chinese() && !state.available) status.setText("中文引擎不可用："+state.error);
        else status.setText(modeLabel()+(remote ? " · 远端草稿 · 发送到 "+targets.deviceLabel() : " · 本机离线输入"));
        status.setVisibility(View.GONE);
        keys.setVisibility(voiceSurface ? View.GONE : View.VISIBLE);
        keyboardBody.setVisibility(voiceSurface ? View.GONE : View.VISIBLE);
        keyboardUtilities.setVisibility(View.GONE);
        voice.setText("语音");
        voice.setEnabled(!sensitiveField);
        styleModeKey(voice,voiceSurface);
        // Voice is a first-class input mode. The remembered text layout stays ready
        // underneath, but must not look selected while the voice surface is active.
        styleModeKey(pinyinModeKey,!voiceSurface && (keyboard.mode() == KeyboardController.Mode.PINYIN || keyboard.mode() == KeyboardController.Mode.ENGLISH));
        symbolModeKey.setText(keyboard.mode()==KeyboardController.Mode.SYMBOLS ? "ABC" : keyboard.mode()==KeyboardController.Mode.NUMBERS ? "符号" : "123");
        if(symbolModeKey instanceof ImeIconButton) ((ImeIconButton)symbolModeKey).setIcon(
                keyboard.mode()==KeyboardController.Mode.SYMBOLS?"keyboard":keyboard.mode()==KeyboardController.Mode.NUMBERS?"symbols":"numbers");
        if(keyboard.mode()==KeyboardController.Mode.T9 || keyboard.mode()==KeyboardController.Mode.PINYIN)
            pinyinModeKey.setText("EN");
        else if(keyboard.mode()==KeyboardController.Mode.ENGLISH)
            pinyinModeKey.setText("中文");
        else pinyinModeKey.setText("中/EN");
        if(t9ModeKey!=null && t9ModeKey!=pinyinModeKey)
            styleModeKey(t9ModeKey,!voiceSurface && keyboard.mode()==KeyboardController.Mode.T9);
        styleModeKey(symbolModeKey,!voiceSurface && (keyboard.mode() == KeyboardController.Mode.SYMBOLS || keyboard.mode() == KeyboardController.Mode.NUMBERS));
    }
    private String shortDeviceLabel(String value) {
        if (value == null || value.isEmpty()) return "设备";
        int count=value.codePointCount(0,value.length());
        return count <= 4 ? value : value.substring(0,value.offsetByCodePoints(0,4));
    }
    private static int codePointCount(String value) {
        return value == null ? 0 : value.codePointCount(0,value.length());
    }
    private String actionIcon(String label) {
        if("发送".equals(label)||"前往".equals(label))return "send";
        if("搜索".equals(label))return "search";
        if("完成".equals(label)||"上屏".equals(label))return "check";
        return "enter";
    }
    private String editorActionLabel() {
        EditorInfo info=getCurrentInputEditorInfo();
        int action=info == null || (info.imeOptions & EditorInfo.IME_FLAG_NO_ENTER_ACTION)!=0 ? EditorInfo.IME_ACTION_NONE : info.imeOptions & EditorInfo.IME_MASK_ACTION;
        switch(action) {
            case EditorInfo.IME_ACTION_GO:return "前往";
            case EditorInfo.IME_ACTION_SEARCH:return "搜索";
            case EditorInfo.IME_ACTION_SEND:return "发送";
            case EditorInfo.IME_ACTION_NEXT:return "下一步";
            case EditorInfo.IME_ACTION_DONE:return "完成";
            case EditorInfo.IME_ACTION_PREVIOUS:return "上一步";
            default:return "回车";
        }
    }
    private String modeLabel() {
        switch (keyboard.mode()) { case PINYIN:return "全拼"; case T9:return "九宫格";
            case ENGLISH:return "英文"; case NUMBERS:return "数字"; default:return "符号"; }
    }
    private void openSettings() {openManagement("settings");}
    private static boolean shouldDefaultToVoice(EditorInfo info) {
        int klass=info == null ? InputType.TYPE_CLASS_TEXT : info.inputType & InputType.TYPE_MASK_CLASS;
        return klass != InputType.TYPE_CLASS_NUMBER && klass != InputType.TYPE_CLASS_PHONE;
    }
    private static boolean isSensitive(int inputType) {
        int klass=inputType & InputType.TYPE_MASK_CLASS,variation=inputType & InputType.TYPE_MASK_VARIATION;
        if (klass == InputType.TYPE_CLASS_NUMBER) return variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD;
        return klass == InputType.TYPE_CLASS_TEXT && (variation == InputType.TYPE_TEXT_VARIATION_PASSWORD
                || variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD || variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD);
    }
    private LinearLayout column() { LinearLayout view=new LinearLayout(this); view.setOrientation(LinearLayout.VERTICAL); return view; }
    private LinearLayout row() { LinearLayout view=new LinearLayout(this); view.setGravity(Gravity.CENTER_VERTICAL); return view; }
    private TextView label(int size) { TextView view=new TextView(this); view.setTextSize(size); view.setTextColor(Color.rgb(45,52,48)); return view; }
    private TextView pill(String text,int background) { TextView view=label(13); view.setText(text); view.setGravity(Gravity.CENTER); view.setBackground(surface(background,15)); return view; }
    private TextView addButton(LinearLayout row,String text,View.OnClickListener listener,float weight) {
        TextView button=key(text,14); button.setOnClickListener(listener);
        LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(0,-1,(int)(weight*100));
        params.weight=weight; params.setMargins(dp(1),dp(1),dp(1),dp(1));
        row.addView(button,params); return button;
    }
    private TextView railButton(LinearLayout column,String text,View.OnClickListener listener) {
        TextView button=key(text,12); button.setOnClickListener(listener);
        button.setBackground(fill(Color.TRANSPARENT,6));
        LinearLayout.LayoutParams params=column.getOrientation()==LinearLayout.HORIZONTAL
                ? new LinearLayout.LayoutParams(0,-1,1) : new LinearLayout.LayoutParams(-1,0,1);
        params.setMargins(dp(2),dp(2),dp(2),dp(2)); column.addView(button,params); return button;
    }
    private void styleModeKey(TextView button,boolean selected) {
        if (button == null) return;
        button.setTextColor(LeafIconStyle.INK);
        if(button instanceof ImeIconButton) {
            button.setSelected(selected);button.setBackground(LeafIconStyle.keyBackground(this,true));
        } else button.setBackground(fill(selected ? 0xffe4f2dc : Color.TRANSPARENT,7));
    }
    private TextView key(String text,int size) {
        TextView button=new ImeKeycapView(this,text,""); button.setText(text); button.setTextSize(size);
        button.setMinWidth(0); button.setMinimumWidth(0); button.setMinHeight(0); button.setMinimumHeight(0);
        button.setPadding(0,0,0,0); button.setIncludeFontPadding(false); button.setGravity(Gravity.CENTER);
        button.setTextColor(Color.rgb(28,39,50)); button.setLetterSpacing(0);
        button.setClickable(true); button.setFocusable(true);
        button.setBackground(LeafIconStyle.keyBackground(this,false)); return button;
    }
    private LinearLayout.LayoutParams weighted() { LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(0,-1,1); params.setMargins(dp(1),dp(1),dp(1),dp(1)); return params; }
    private GradientDrawable surface(int color,int radius) { GradientDrawable shape=fill(color,radius); shape.setStroke(dp(1),Color.rgb(222,228,232)); return shape; }
    private GradientDrawable fill(int color,int radius) { GradientDrawable shape=new GradientDrawable(); shape.setColor(color); shape.setCornerRadius(dp(radius)); return shape; }
    private static final class FailedChineseEngine implements ChineseInputEngine {
        private final String reason;
        FailedChineseEngine(Throwable failure) {
            String message=failure == null ? "未知错误" : failure.getMessage();
            reason="本地中文引擎启动失败："+(message == null ? failure.getClass().getSimpleName() : message);
        }
        public Snapshot snapshot() { return new Snapshot("",java.util.Collections.emptyList(),0,false,false,reason); }
        public void input(char value) { } public void backspace() { }
        public String select(int index) { return null; }
        public void nextPage() { } public void previousPage() { }
        public void setLayout(Layout layout) { } public void reset() { } public void close() { }
    }
    /** Initialization is asynchronous and never supplies pretend Chinese candidates. */
    private static final class LoadingChineseEngine implements ChineseInputEngine {
        public Snapshot snapshot() { return new Snapshot("",java.util.Collections.emptyList(),0,false,false,"正在部署本地词库"); }
        public void input(char value) { } public void backspace() { }
        public String select(int index) { return null; }
        public void nextPage() { } public void previousPage() { }
        public void setLayout(Layout layout) { } public void reset() { } public void close() { }
    }
    private int dp(int value) { return Math.round(value*getResources().getDisplayMetrics().density); }
}
