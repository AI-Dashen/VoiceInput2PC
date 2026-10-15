package io.github.amxooo.voiceinput2pc;

import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import java.util.UUID;
import android.graphics.Color;
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
public final class UniversalInputMethodService extends InputMethodService
        implements OfflineSpeechRecognizer.Listener {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final InputSession session = new InputSession();
    private final ImePanelController panels = new ImePanelController();
    private final OutputTargetController targets = new OutputTargetController();
    private KeyboardController keyboard;
    private OfflineSpeechRecognizer speech;
    private RemoteTextSender remoteSender;
    private SharedPreferences prefs;
    private PairingConfig activePairing;
    private InputHistoryStore history;
    private final String remoteHistorySession=UUID.randomUUID().toString();
    private TextView status, composition, remoteDraft, targetHeader;
    private EditText preview;
    private HorizontalScrollView candidateScroller;
    private LinearLayout keys, candidates, voiceActions, inputPanel, secondaryPanel;
    private LinearLayout primaryPanel, keyboardUtilities;
    private TextView voice, confirm, function, targetKey, enterKey, pinyinModeKey, t9ModeKey, symbolModeKey;
    private int lastSelectionStart = -1, lastSelectionEnd = -1;
    private boolean secondaryBackDown;
    private boolean sensitiveField;
    private boolean voiceMode = true;
    private long voiceSamples;
    private KeyboardController.Mode preferredTextMode = KeyboardController.Mode.PINYIN;
    private String message = "";
    private String activeComposing = "";
    private String suspendedPreview = "";
    private String voiceDraftPrefix = "";
    private volatile boolean destroyed;

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
                if (destroyed) { result.close(); return; }
                KeyboardController.Mode mode = keyboard.mode();
                keyboard.close(); keyboard = new KeyboardController(result); keyboard.setMode(mode);
                render();
            });
        }, "ime-rime-deploy");
        deploy.start();
    }

    @Override public View onCreateInputView() {
        LinearLayout root = column();
        root.setPadding(dp(2), dp(2), dp(2), dp(3));
        root.setBackgroundColor(Color.rgb(246,249,250));
        primaryPanel = row();
        primaryPanel.setGravity(Gravity.TOP);
        primaryPanel.setBackground(surface(Color.rgb(248,250,252),8));

        LinearLayout left = column();
        left.setPadding(dp(1),0,dp(1),0);
        voice = railButton(left,"语音",v -> toggleVoice());
        voice.setOnLongClickListener(v -> { cancelVoice(); return true; });
        t9ModeKey = railButton(left,"九宫格",v -> switchMode(KeyboardController.Mode.T9));
        pinyinModeKey = railButton(left,"26键",v -> switchMode(KeyboardController.Mode.PINYIN));
        pinyinModeKey.setOnLongClickListener(v -> { switchMode(KeyboardController.Mode.ENGLISH); return true; });
        symbolModeKey = railButton(left,"符号",v -> switchMode(KeyboardController.Mode.SYMBOLS));
        symbolModeKey.setOnLongClickListener(v -> { switchMode(KeyboardController.Mode.NUMBERS); return true; });
        primaryPanel.addView(left,new LinearLayout.LayoutParams(dp(ImeLayoutSpec.RAIL_WIDTH_DP),-1));

        inputPanel = column();
        inputPanel.setPadding(dp(4),0,dp(4),0);
        status = label(12); status.setGravity(Gravity.CENTER);
        status.setTextColor(Color.rgb(94,105,116));
        status.setPadding(dp(7),dp(2),dp(7),dp(2));
        inputPanel.addView(status,new LinearLayout.LayoutParams(-1,dp(ImeLayoutSpec.STATUS_HEIGHT_DP)));
        composition = label(18); composition.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        composition.setGravity(Gravity.CENTER_VERTICAL);
        composition.setPadding(dp(7),0,dp(7),0);
        inputPanel.addView(composition,new LinearLayout.LayoutParams(-1,dp(ImeLayoutSpec.COMPOSITION_HEIGHT_DP)));
        candidateScroller = new HorizontalScrollView(this);
        candidateScroller.setHorizontalScrollBarEnabled(false);
        candidates = row(); candidateScroller.addView(candidates);
        inputPanel.addView(candidateScroller,new LinearLayout.LayoutParams(-1,dp(ImeLayoutSpec.CANDIDATE_HEIGHT_DP)));
        remoteDraft=label(16); remoteDraft.setBackground(fill(Color.WHITE,7));
        remoteDraft.setPadding(dp(8),dp(4),dp(8),dp(4)); remoteDraft.setMinLines(1); remoteDraft.setMaxLines(2);
        remoteDraft.setContentDescription("跨设备待发送文字"); inputPanel.addView(remoteDraft);
        preview = new EditText(this); preview.setTextSize(19);
        preview.setTextColor(Color.rgb(26,37,48)); preview.setBackground(fill(Color.WHITE,7));
        preview.setMinLines(3); preview.setMaxLines(10); preview.setShowSoftInputOnFocus(false);
        preview.setPadding(dp(8),dp(6),dp(8),dp(6));
        preview.setContentDescription("语音识别预览，可修改后确认输入"); inputPanel.addView(preview);
        voiceActions = row();
        confirm = addButton(voiceActions,"确认输入",v -> confirmVoice(),2);
        addButton(voiceActions,"取消",v -> cancelVoice(),1);
        inputPanel.addView(voiceActions,new LinearLayout.LayoutParams(-1,dp(ImeLayoutSpec.ACTION_HEIGHT_DP)));
        keys = column(); inputPanel.addView(keys,new LinearLayout.LayoutParams(-1,0,1)); rebuildKeys();
        keyboardUtilities=row();
        addButton(keyboardUtilities,"←",v -> moveCursor(-1),1);
        addButton(keyboardUtilities,"中/EN",v -> switchLanguage(),1);
        addButton(keyboardUtilities,"空格",v -> space(),3);
        addButton(keyboardUtilities,"123",v -> switchMode(KeyboardController.Mode.NUMBERS),1);
        addButton(keyboardUtilities,"→",v -> moveCursor(1),1);
        inputPanel.addView(keyboardUtilities,new LinearLayout.LayoutParams(-1,dp(ImeLayoutSpec.UTILITY_HEIGHT_DP)));
        primaryPanel.addView(inputPanel,new LinearLayout.LayoutParams(0,-1,1));

        LinearLayout right = column();
        right.setPadding(dp(1),0,dp(1),0);
        railButton(right,"删除",v -> backspace());
        targetKey=railButton(right,"本机",v -> toggleOutputTarget());
        targetKey.setOnLongClickListener(v -> { openTransferWorkspace(); return true; });
        function=railButton(right,"功能",v -> toggleFeaturePanel()); function.setId(4101);
        enterKey=railButton(right,"回车",v -> enter());
        primaryPanel.addView(right,new LinearLayout.LayoutParams(dp(ImeLayoutSpec.RAIL_WIDTH_DP),-1));
        int panelHeight=dp(ImeLayoutSpec.panelHeightDp(
                getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE));
        root.addView(primaryPanel,new LinearLayout.LayoutParams(-1,panelHeight));

        secondaryPanel = column();
        secondaryPanel.setPadding(dp(6),dp(6),dp(6),dp(6));
        secondaryPanel.setBackgroundColor(Color.rgb(246,249,250));
        root.addView(secondaryPanel,new LinearLayout.LayoutParams(-1,panelHeight));
        render(); return root;
    }

    @Override public void onStartInput(EditorInfo info, boolean restarting) {
        super.onStartInput(info,restarting);
        beginEditor(info,restarting);
    }
    @Override public void onStartInputView(EditorInfo info, boolean restarting) {
        super.onStartInputView(info,restarting);
        // Some Android builds recreate an IME view for a new editor without a
        // matching onStartInput callback. Preserve a draft only for the exact
        // same field; a different app or field always starts clean.
        if (!fieldKey(info).equals(session.identity().targetId)) beginEditor(info,restarting);
        else render();
    }
    private void beginEditor(EditorInfo info,boolean restarting) {
        if (speech != null) speech.cancel();
        panels.reset();
        keyboard.reset(); activeComposing=""; suspendedPreview=""; voiceDraftPrefix=""; voiceSamples=0; message="";
        sensitiveField=isSensitive(info == null ? 0 : info.inputType);
        voiceMode=shouldDefaultToVoice(info) && !sensitiveField;
        String editorKey=fieldKey(info);
        refreshPairing();
        targets.onStartInput(editorKey,sensitiveField,restarting);
        persistRemoteState(false);
        session.begin(editorKey,info == null ? "" : info.packageName,
                info == null ? 0 : info.fieldId,info == null ? 0 : info.inputType);
        lastSelectionStart=info == null ? -1 : info.initialSelStart;
        lastSelectionEnd=info == null ? -1 : info.initialSelEnd;
        if (sensitiveField) keyboard.setMode(KeyboardController.Mode.ENGLISH);
        else if (info != null && (info.inputType & InputType.TYPE_MASK_CLASS) == InputType.TYPE_CLASS_NUMBER)
            keyboard.setMode(KeyboardController.Mode.NUMBERS);
        else keyboard.setMode(preferredTextMode);
        if (keys != null) rebuildKeys();
        render();
    }
    @Override public void onFinishInputView(boolean finishingInput) {
        if (speech != null) speech.cancel();
        panels.reset();
        if (finishingInput) {
            suspendedPreview=""; voiceDraftPrefix=""; clearComposition(); targets.onFinishInput(); session.invalidate();
        } else if (session.state() == InputSession.State.PREVIEW && preview != null) {
            suspendedPreview=preview.getText().toString();
            session.updatePreview(session.generation(),suspendedPreview);
        } else session.cancel(session.generation());
        persistRemoteState(true); render();
        super.onFinishInputView(finishingInput);
    }
    @Override public void onFinishInput() {
        if (speech != null) speech.cancel();
        suspendedPreview=""; voiceDraftPrefix="";
        panels.reset(); clearComposition(); targets.onFinishInput(); persistRemoteState(true);
        session.invalidate(); super.onFinishInput();
    }
    @Override public void onDestroy() {
        destroyed=true; persistRemoteState(true); session.invalidate();
        if (speech != null) speech.close(); if (remoteSender != null) remoteSender.close();
        if (history != null) history.close(); if (keyboard != null) keyboard.close(); super.onDestroy();
    }

    private void rebuildKeys() {
        keys.removeAllViews();
        switch (keyboard.mode()) {
            case T9:
                addKeyRow(new String[]{"1\n分词","2\nABC","3\nDEF"},new String[]{"'","2","3"});
                addKeyRow(new String[]{"4\nGHI","5\nJKL","6\nMNO"},new String[]{"4","5","6"});
                addKeyRow(new String[]{"7\nPQRS","8\nTUV","9\nWXYZ"},new String[]{"7","8","9"});
                addKeyRow(new String[]{"，","0\n空格","。"},new String[]{"，"," ","。"}); break;
            case NUMBERS:
                addKeyRow("12345"); addKeyRow("67890"); addKeyRow(".,+-="); break;
            case SYMBOLS:
                addKeyRow("，。？！；"); addKeyRow("：、（）—"); addKeyRow(new String[]{"“","”","《","》","…"},new String[]{"“","”","《","》","…"}); break;
            default:
                addKeyRow("qwertyuiop"); addKeyRow("asdfghjkl");
                if (keyboard.mode() == KeyboardController.Mode.ENGLISH) {
                    String[] labels = {keyboard.uppercase() ? "⇧开" : "⇧","z","x","c","v","b","n","m"};
                    String[] values = {"SHIFT","z","x","c","v","b","n","m"}; addKeyRow(labels,values);
                } else addKeyRow("zxcvbnm");
        }
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
            TextView typingKey=addButton(row,label,v -> type(value),1);
            typingKey.setTextSize(keyboard.mode() == KeyboardController.Mode.PINYIN
                    || keyboard.mode() == KeyboardController.Mode.ENGLISH ? 16 : 14);
        }
        keys.addView(row,new LinearLayout.LayoutParams(-1,0,1));
    }
    private void switchMode(KeyboardController.Mode mode) {
        if (!panels.isPrimary()) return;
        if (sensitiveField && (mode == KeyboardController.Mode.PINYIN || mode == KeyboardController.Mode.T9)) return;
        if (busy()) interruptRecognitionForModeSwitch();
        if (session.state() == InputSession.State.PREVIEW) commitPreviewBeforeModeSwitch();
        voiceMode=false; suspendedPreview="";
        clearComposition(); keyboard.setMode(mode);
        if (mode == KeyboardController.Mode.PINYIN || mode == KeyboardController.Mode.T9
                || mode == KeyboardController.Mode.ENGLISH) preferredTextMode=mode;
        message=""; rebuildKeys(); render();
    }
    /**
     * A recognizer can already have rendered a useful partial result while its
     * final callback is delayed or lost. Mode keys are an explicit user action:
     * stop that recognizer, retain exactly the visible draft and let the normal
     * preview commit path finish before changing keyboards.
     */
    private void interruptRecognitionForModeSwitch() {
        long generation=session.generation();
        String visible=visibleVoiceDraft();
        speech.cancel();
        if (!visible.isEmpty() && session.restorePreview(generation,visible)) {
            suspendedPreview=visible;
            if (preview != null) { preview.setText(visible); preview.setSelection(preview.length()); }
            return;
        }
        InputSession.Identity current=session.identity();
        session.cancel(generation);
        session.begin(current.targetId,current.packageName,current.fieldId,current.inputType);
        suspendedPreview=""; voiceDraftPrefix=""; voiceSamples=0;
    }
    private void commitPreviewBeforeModeSwitch() {
        if (session.state() != InputSession.State.PREVIEW) return;
        String edited=preview == null ? session.preview() : preview.getText().toString();
        session.updatePreview(session.generation(),edited);
        String text=session.commit(session.generation());
        if (text != null && !text.isEmpty()) routeStable(text);
        suspendedPreview="";voiceDraftPrefix="";voiceSamples=0;
        InputSession.Identity current=session.identity();
        session.begin(current.targetId,current.packageName,current.fieldId,current.inputType);
    }
    private void switchLanguage() {
        if (!panels.isPrimary() || busy() || sensitiveField) return;
        switchMode(keyboard.mode() == KeyboardController.Mode.ENGLISH
                ? KeyboardController.Mode.PINYIN : KeyboardController.Mode.ENGLISH);
    }
    private void type(String value) {
        if (!panels.isPrimary()) return;
        if (busy()) return;
        if ("SHIFT".equals(value)) { keyboard.toggleCase(); rebuildKeys(); return; }
        if (session.state() == InputSession.State.PREVIEW && !keyboard.chinese()) { editPreview(value,false); return; }
        String literal = keyboard.type(value);
        if (literal != null) commitLiteral(literal); else syncComposition();
    }
    private void syncComposition() {
        ChineseInputEngine.Snapshot state=keyboard.snapshot();
        String visible=keyboard.displayComposition(state);
        if (session.state() == InputSession.State.PREVIEW) { render(); return; }
        session.compose(session.generation(),visible);
        targets.setComposing(visible);
        if (!targets.isRemote()) {
            InputConnection connection=getCurrentInputConnection();
            if (connection != null) {
                if (!visible.isEmpty()) connection.setComposingText(visible,1);
                else if (!activeComposing.isEmpty()) { connection.setComposingText("",1); connection.finishComposingText(); }
            }
            activeComposing=visible;
        } else activeComposing="";
        render();
    }
    private void selectCandidate(int index) {
        if (!panels.isPrimary() || busy()) return;
        String result=keyboard.select(index);
        if (result != null && !result.isEmpty()) {
            if (session.state() == InputSession.State.PREVIEW) editPreview(result,false);
            else routeStable(result);
        }
        syncComposition();
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
        if (keyboard.chinese() && !state.rawInput.isEmpty()) { message="请先选词或删除拼音"; render(); return; }
        routeStable(value);
    }
    private void space() {
        if (!panels.isPrimary()) return;
        if (session.state() == InputSession.State.PREVIEW) {
            if (!keyboard.snapshot().candidates.isEmpty()) selectCandidate(0);
            else if (keyboard.snapshot().rawInput.isEmpty()) editPreview(" ",false);
            return;
        }
        if (busy()) return;
        ChineseInputEngine.Snapshot state=keyboard.snapshot();
        if (keyboard.chinese() && !state.candidates.isEmpty()) selectCandidate(0);
        else if (state.rawInput.isEmpty()) commitLiteral(" ");
    }
    private void backspace() {
        if (!panels.isPrimary() || busy()) return;
        if (session.state() == InputSession.State.PREVIEW) {
            if (!keyboard.snapshot().rawInput.isEmpty()) { keyboard.backspace(); render(); }
            else editPreview("",true);
            return;
        }
        if (!keyboard.snapshot().rawInput.isEmpty()) { keyboard.backspace(); syncComposition(); return; }
        if (targets.isRemote()) {
            targets.deleteBackward(); persistRemoteState(false); message=""; render(); return;
        }
        InputConnection connection=getCurrentInputConnection();
        if (connection != null) {
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
        }
    }
    private void enter() {
        if (!panels.isPrimary()) return;
        if (session.state() == InputSession.State.PREVIEW) {
            confirmVoice();
            return;
        }
        if (busy()) return;
        if (!keyboard.snapshot().rawInput.isEmpty()) { message="请先选择候选词"; render(); return; }
        if (targets.isRemote()) { sendRemoteDraft(); return; }
        InputConnection connection=getCurrentInputConnection(); EditorInfo info=getCurrentInputEditorInfo();
        if (connection == null) return;
        int action=info == null ? EditorInfo.IME_ACTION_NONE : info.imeOptions & EditorInfo.IME_MASK_ACTION;
        if (info != null && (info.imeOptions & EditorInfo.IME_FLAG_NO_ENTER_ACTION) == 0
                && action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED)
            connection.performEditorAction(action);
        else connection.commitText("\n",1);
    }
    private void moveCursor(int direction) {
        if (!panels.isPrimary() || busy()) return;
        if (targets.isRemote()) {
            targets.moveCursor(direction); persistRemoteState(false); message=""; render(); return;
        }
        if (session.state() == InputSession.State.PREVIEW) {
            int current=Math.max(0,preview.getSelectionStart());
            preview.setSelection(Math.max(0,Math.min(preview.length(),current+direction))); return;
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
        int start=Math.max(0,preview.getSelectionStart()),end=Math.max(start,preview.getSelectionEnd());
        if (delete && start == end && start > 0)
            start=Character.offsetByCodePoints(preview.getText(),start,-1);
        preview.getText().replace(start,end,delete ? "" : value);
        preview.setSelection(start+(delete ? 0 : value.length()));
        confirm.setEnabled(preview.length() > 0);
    }
    private boolean busy() {
        return session.state() == InputSession.State.LISTENING || session.state() == InputSession.State.RECOGNIZING;
    }
    private void toggleVoice() {
        if (!panels.isPrimary()) return;
        if (sensitiveField) return;
        voiceMode=true;
        if (session.state() == InputSession.State.LISTENING) { speech.finish(); message="正在完成本地识别"; render(); return; }
        if (session.state() == InputSession.State.RECOGNIZING) {
            long generation=session.generation(); String visible=visibleVoiceDraft(); speech.cancel();
            if (!visible.isEmpty() && session.restorePreview(generation,visible)) {
                suspendedPreview=visible; message="识别已停止，当前文字已保留";
                if (preview != null) { preview.setText(visible); preview.setSelection(preview.length()); }
            } else {
                session.cancel(generation); message="识别已停止，未收到文字";
            }
            render(); return;
        }
        if (!keyboard.snapshot().rawInput.isEmpty()) { message="请先确认或删除当前拼音"; render(); return; }
        voiceSamples=0;
        long generation;
        if (session.state() == InputSession.State.PREVIEW) {
            String edited=preview == null ? session.preview() : preview.getText().toString();
            session.updatePreview(session.generation(),edited);
            voiceDraftPrefix=session.preview();
            if (voiceDraftPrefix.isEmpty()) {
                InputSession.Identity current=session.identity();
                generation=session.begin(current.targetId,current.packageName,current.fieldId,current.inputType).generation;
                if (!session.startListening(generation)) { message="语音会话启动失败，请重试"; render(); return; }
            } else {
                InputSession.Identity resumed=session.resumeListening(session.generation());
                if (resumed == null) { message="当前草稿无法继续录音，请重试"; render(); return; }
                generation=resumed.generation;
            }
            suspendedPreview=voiceDraftPrefix;
            if (preview != null) { preview.setText(voiceDraftPrefix); preview.setSelection(preview.length()); }
        } else {
            voiceDraftPrefix=""; suspendedPreview="";
            if (preview != null) preview.setText("");
            InputSession.Identity current=session.identity();
            generation=session.begin(current.targetId,current.packageName,current.fieldId,current.inputType).generation;
            if (!session.startListening(generation)) { message="语音会话启动失败，请重试"; render(); return; }
        }
        message=voiceDraftPrefix.isEmpty() ? "正在准备本地语音" : "继续录音 · 已识别文字会保留";
        speech.start(generation,this); render();
    }
    private void cancelVoice() {
        speech.cancel(); clearComposition(); voiceSamples=0; suspendedPreview=""; voiceDraftPrefix="";
        if (preview != null) preview.setText("");
        InputSession.Identity current=session.identity();
        session.begin(current.targetId,current.packageName,current.fieldId,current.inputType);
        message="已取消，本地音频已释放"; render();
    }
    private void confirmVoice() {
        if (session.state() != InputSession.State.PREVIEW || sensitiveField) return;
        if (!keyboard.snapshot().rawInput.isEmpty()) { message="请先确认或删除拼音"; render(); return; }
        session.updatePreview(session.generation(),preview.getText().toString());
        String text=session.commit(session.generation());
        if (text != null) routeStable(text);
        suspendedPreview=""; voiceDraftPrefix=""; voiceSamples=0;
        InputSession.Identity current=session.identity();
        session.begin(current.targetId,current.packageName,current.fieldId,current.inputType);
        message=targets.isRemote() ? "已加入待发送文字" : "已输入"; render();
    }
    @Override public void onListening(long generation) {
        main.post(() -> { if (session.isCurrent(generation) && session.state() == InputSession.State.LISTENING) {
            voiceMode=true; message="持续听写中 · 静音约6秒自动完成，也可点左侧停止"; render(); } });
    }
    @Override public void onRecognizing(long generation) {
        main.post(() -> { if (session.startRecognizing(generation)) { message="本地识别中"; render(); } });
    }
    @Override public void onPartial(long generation,String text) {
        main.post(() -> { if (session.isCurrent(generation) && busy() && preview != null) {
            suspendedPreview=VoiceDraft.append(voiceDraftPrefix,text);
            preview.setText(suspendedPreview); preview.setSelection(preview.length()); render();
        } });
    }
    @Override public void onAudioProgress(long generation,long samples) {
        main.post(() -> { if (session.isCurrent(generation) && session.state() == InputSession.State.LISTENING) {
            voiceSamples=samples; render();
        } });
    }
    @Override public void onResult(long generation,String text) {
        main.post(() -> {
            if (!session.isCurrent(generation)) return;
            if (session.state() == InputSession.State.LISTENING) session.startRecognizing(generation);
            String combined=VoiceDraft.append(voiceDraftPrefix,text);
            if (text == null || text.trim().isEmpty()) {
                String retained=visibleVoiceDraft();
                if (!retained.isEmpty() && session.restorePreview(generation,retained)) {
                    suspendedPreview=retained;
                    message="识别未正常结束，当前文字已保留";
                    if (preview != null) { preview.setText(retained); preview.setSelection(preview.length()); }
                    render(); return;
                }
                cancelVoice(); message="未识别到文字，请重试"; render(); return;
            }
            if (!session.showPreview(generation,combined)) return;
            voiceMode=true; suspendedPreview=combined; voiceDraftPrefix="";
            message="识别完成 · 可继续说，最后点右侧“上屏”";
            if (preview != null) { preview.setText(combined); preview.setSelection(preview.length()); }
            render();
        });
    }
    @Override public void onError(long generation,String error) {
        main.post(() -> { if (!session.isCurrent(generation)) return;
            String retained=visibleVoiceDraft();
            if (!retained.isEmpty() && session.restorePreview(generation,retained)) {
                suspendedPreview=retained;
                if (preview != null) { preview.setText(retained); preview.setSelection(preview.length()); }
                message="识别异常，当前文字已保留，可继续说或切换键盘";
            } else {
                session.cancel(generation); message=error == null ? "本地语音识别失败" : error;
            }
            voiceDraftPrefix=""; render(); });
    }

    private String visibleVoiceDraft() {
        String visible=preview == null ? "" : preview.getText().toString();
        if (visible == null || visible.trim().isEmpty()) visible=suspendedPreview;
        if (visible == null || visible.trim().isEmpty()) visible=voiceDraftPrefix;
        return visible == null ? "" : visible;
    }

    private void routeStable(String text) {
        if (text == null || text.isEmpty()) return;
        if (targets.isRemote()) {
            if (targets.commitStable(text)) { persistRemoteState(false); message=""; }
            else message="跨设备草稿当前不可写，请稍后重试";
            render(); return;
        }
        InputConnection connection=getCurrentInputConnection();
        if (connection != null) connection.commitText(text,1);
        message=""; render();
    }

    private void toggleOutputTarget() {
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
                recordRemote(request,"inserted".equals(state) ? "INSERTED" : "SAVED_NOT_INSERTED",seq);
                message="inserted".equals(state) ? "已发送到 "+targets.deviceLabel()
                        : "电脑已保存文字，但未输入到当前窗口";
                render();
            }
            @Override public void onRejected(String id,String note) {
                if (destroyed || !targets.onSendFailed(id,OutputTargetController.SendFailure.REJECTED)) return;
                persistRemoteState(true); recordRemote(request,"REJECTED",0);
                message=note == null || note.isEmpty() ? "电脑拒绝了本次文字" : note; render();
            }
            @Override public void onUncertain(String id,String note) {
                if (destroyed || !targets.onSendFailed(id,OutputTargetController.SendFailure.UNKNOWN)) return;
                persistRemoteState(true); recordRemote(request,"DELIVERY_UNKNOWN",0);
                message="发送结果未知，文字已保留；再次发送会复用同一编号"; render();
            }
        });
    }

    private void recordRemote(OutputTargetController.SendRequest request,String state,long seq) {
        try { history.record(remoteHistorySession,"ime_remote",request.deviceId,state,request.requestId,seq,request.text); }
        catch (Exception ignored) { message="传输已返回，但历史记录保存失败"; }
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

    private void openTransferWorkspace() {
        Intent intent=new Intent(this,MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(intent);
    }

    private void openInputStatistics() {
        Intent intent=new Intent(this,MainActivity.class);
        intent.putExtra(MainActivity.EXTRA_OPEN_HISTORY,true);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(intent);
    }

    private void renderSecondary() {
        if (secondaryPanel == null || panels.isPrimary()) return;
        secondaryPanel.removeAllViews();
        ImePanelController.Feature selected=panels.feature();
        addSecondaryHeader(selected);
        if (selected == ImePanelController.Feature.HUB) renderFeatureHub();
        else if (selected == ImePanelController.Feature.TRANSFER) renderTransferFeature();
        else if (selected == ImePanelController.Feature.STATISTICS) renderStatisticsFeature();
        else renderSettingsFeature();
    }

    private void addSecondaryHeader(ImePanelController.Feature selected) {
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
            TextView state=secondaryChip(activePairing == null ? "未配对" : targets.isRemote() ? "电脑" : "本机",
                    activePairing != null && targets.isRemote());
            heading.addView(state,new LinearLayout.LayoutParams(dp(60),dp(30)));
        }
        secondaryPanel.addView(heading,new LinearLayout.LayoutParams(-1,dp(42)));
    }

    private void renderFeatureHub() {
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
        refreshPairing();
        LinearLayout card=secondaryCard();
        card.addView(secondaryTitle(activePairing == null ? "尚未配对电脑" : PairingStore.label(prefs,activePairing)));
        String detail=activePairing == null ? "先在电脑接收端显示二维码，再用手机完成一次配对。"
                : (targets.isRemote() ? "当前文字将进入电脑草稿，点右侧发送后才会传出。"
                : "当前仍输入到手机。切换后，键盘会回到输入界面。");
        card.addView(secondaryDetail(detail));
        LinearLayout.LayoutParams cardParams=new LinearLayout.LayoutParams(-1,dp(86));cardParams.topMargin=dp(7);secondaryPanel.addView(card,cardParams);
        LinearLayout actions=row();
        TextView primary=secondaryAction(activePairing == null ? "配对电脑" : targets.isRemote() ? "返回本机输入" : "切换到电脑输入",true);
        primary.setId(4210);primary.setOnClickListener(v -> {if(activePairing==null)openTransferWorkspace();else toggleTargetFromSecondary();});
        TextView manage=secondaryAction("管理设备",false);manage.setOnClickListener(v -> openTransferWorkspace());
        actions.addView(primary,new LinearLayout.LayoutParams(0,dp(44),2));
        LinearLayout.LayoutParams manageParams=new LinearLayout.LayoutParams(0,dp(44),1);manageParams.leftMargin=dp(7);actions.addView(manage,manageParams);
        LinearLayout.LayoutParams actionsParams=new LinearLayout.LayoutParams(-1,dp(44));actionsParams.topMargin=dp(7);secondaryPanel.addView(actions,actionsParams);
        TextView safety=secondaryDetail("只发送明确确认的文字；密码输入框会自动关闭跨设备功能。");
        safety.setGravity(Gravity.CENTER_VERTICAL);secondaryPanel.addView(safety,new LinearLayout.LayoutParams(-1,dp(42)));
    }

    private void renderStatisticsFeature() {
        LinearLayout card=secondaryCard();
        card.addView(secondaryTitle("今天的输入"));
        TextView number=label(20);number.setText(secondaryHistorySummary());number.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        number.setTextColor(Color.rgb(0,137,105));number.setPadding(0,dp(5),0,dp(4));card.addView(number);
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
        voiceState.setTextColor(microphone ? Color.rgb(0,137,105) : Color.rgb(176,72,45));card.addView(voiceState);
        TextView imeState=secondaryDetail("系统输入法　正在使用 · 离线输入");card.addView(imeState);
        TextView privacy=secondaryDetail("密码框自动关闭语音、历史和跨设备输出");card.addView(privacy);
        LinearLayout.LayoutParams cardParams=new LinearLayout.LayoutParams(-1,dp(116));cardParams.topMargin=dp(7);secondaryPanel.addView(card,cardParams);
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
        try(android.database.Cursor cursor=history.getReadableDatabase().rawQuery(
                "SELECT COUNT(*),COALESCE(SUM(length(text)),0) FROM events WHERE created>=?",
                new String[]{Long.toString(since)})) {
            if(cursor.moveToFirst())return "今日 "+cursor.getLong(0)+" 条 · "+cursor.getLong(1)+" 字";
        } catch(RuntimeException ignored) { }
        return "记录暂不可读";
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
        chip.setBackground(fill(active ? Color.rgb(230,247,241) : Color.rgb(235,240,243),14));
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
        lastSelectionStart=newSelStart; lastSelectionEnd=newSelEnd;
        if (!targets.isRemote() && !activeComposing.isEmpty()
                && (newSelStart != candidatesEnd || newSelEnd != candidatesEnd)) {
            clearComposition(); message="光标已移动，原拼音组字已取消"; render();
        }
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
        ChineseInputEngine.Snapshot state=keyboard.snapshot();
        boolean primary=panels.isPrimary(), remote=targets.isRemote();
        boolean isPreview=session.state() == InputSession.State.PREVIEW;
        if (primaryPanel != null) primaryPanel.setVisibility(primary ? View.VISIBLE : View.GONE);
        if (secondaryPanel != null) secondaryPanel.setVisibility(primary ? View.GONE : View.VISIBLE);
        if (function != null) { function.setText(primary ? "功能" : "返回"); function.setEnabled(!sensitiveField); }
        if (targetKey != null) {
            targetKey.setText(remote ? shortDeviceLabel(targets.deviceLabel()) : "本机");
            targetKey.setEnabled(primary && !sensitiveField);
        }
        if (targetHeader != null) targetHeader.setText(remote ? "输入到："+targets.deviceLabel() : "输入到：本机");
        if (enterKey != null) {
            enterKey.setText(isPreview ? "上屏" : remote ? (targets.sending() ? "等待" : "发送") : "回车");
            enterKey.setEnabled(primary && (isPreview || !remote || targets.canSend()));
        }
        if (!primary) { renderSecondary(); return; }
        boolean voiceSurface=voiceMode || busy() || isPreview;
        if (voiceSurface && preview != null && !suspendedPreview.isEmpty()
                && !suspendedPreview.contentEquals(preview.getText())) {
            preview.setText(suspendedPreview); preview.setSelection(preview.length());
        }
        preview.setVisibility(voiceSurface ? View.VISIBLE : View.GONE);
        preview.setFocusable(isPreview); preview.setFocusableInTouchMode(isPreview); preview.setCursorVisible(isPreview);
        preview.setHint(session.state() == InputSession.State.LISTENING ? "正在收音…"
                : session.state() == InputSession.State.RECOGNIZING ? "正在生成最终文字…"
                : isPreview ? "可修改；点语音继续说，点上屏提交" : "点击左侧“语音”开始说话");
        LinearLayout.LayoutParams previewParams=(LinearLayout.LayoutParams)preview.getLayoutParams();
        previewParams.height=voiceSurface ? 0 : -2;
        previewParams.weight=voiceSurface ? 1 : 0;
        preview.setLayoutParams(previewParams);
        remoteDraft.setVisibility(remote ? View.VISIBLE : View.GONE);
        if (remote) {
            String draft=targets.pendingText();
            if (draft.isEmpty()) remoteDraft.setText("待发送文字会显示在这里");
            else {
                int cursor=Math.max(0,Math.min(targets.cursor(),draft.length()));
                remoteDraft.setText(draft.substring(0,cursor)+"│"+draft.substring(cursor));
            }
        }
        voiceActions.setVisibility(View.GONE);
        confirm.setVisibility(isPreview ? View.VISIBLE : View.GONE);
        confirm.setEnabled(isPreview && preview.length() > 0);
        composition.setVisibility(voiceSurface ? View.GONE : View.VISIBLE);
        candidateScroller.setVisibility(voiceSurface ? View.GONE : View.VISIBLE);
        if (!busy() && !voiceSurface) composition.setText(keyboard.displayComposition(state));
        candidates.removeAllViews();
        if (!busy() && keyboard.chinese()) {
            TextView previous=key("‹",18); previous.setEnabled(state.page > 0);
            previous.setOnClickListener(v -> { keyboard.previousPage(); render(); });
            candidates.addView(previous,new LinearLayout.LayoutParams(dp(36),-1));
            for (int i=0;i<state.candidates.size();i++) {
                final int index=i; TextView candidate=key(state.candidates.get(i),18);
                candidate.setPadding(dp(12),0,dp(12),0); candidate.setOnClickListener(v -> selectCandidate(index));
                candidates.addView(candidate,new LinearLayout.LayoutParams(-2,-1));
            }
            TextView next=key("›",18); next.setEnabled(state.hasNext);
            next.setOnClickListener(v -> { keyboard.nextPage(); render(); });
            candidates.addView(next,new LinearLayout.LayoutParams(dp(36),-1));
        }
        if (sensitiveField) status.setText("敏感输入框 · 语音、历史和跨设备链路已关闭");
        else if (session.state() == InputSession.State.LISTENING && voiceSamples > 0)
            status.setText(String.format(java.util.Locale.ROOT,"持续听写 · %.1f 秒 · 静音6秒自动完成",voiceSamples/16000.0));
        else if (!message.isEmpty()) status.setText(message);
        else if (keyboard.chinese() && !state.available) status.setText("中文引擎不可用："+state.error);
        else status.setText(modeLabel()+(remote ? " · 远端草稿 · 发送到 "+targets.deviceLabel() : " · 本机离线输入"));
        status.setVisibility(voiceSurface ? View.VISIBLE : View.GONE);
        keys.setVisibility(voiceSurface ? View.GONE : View.VISIBLE);
        keyboardUtilities.setVisibility(voiceSurface || keyboard.mode() == KeyboardController.Mode.T9
                || keyboard.mode() == KeyboardController.Mode.SYMBOLS ? View.GONE : View.VISIBLE);
        voice.setText(session.state() == InputSession.State.LISTENING ? "停止" : "语音");
        voice.setEnabled(!sensitiveField && session.state() != InputSession.State.RECOGNIZING);
        styleModeKey(voice,voiceSurface);
        // Voice is a first-class input mode. The remembered text layout stays ready
        // underneath, but must not look selected while the voice surface is active.
        styleModeKey(pinyinModeKey,!voiceSurface && (keyboard.mode() == KeyboardController.Mode.PINYIN || keyboard.mode() == KeyboardController.Mode.ENGLISH));
        styleModeKey(t9ModeKey,!voiceSurface && keyboard.mode() == KeyboardController.Mode.T9);
        styleModeKey(symbolModeKey,!voiceSurface && (keyboard.mode() == KeyboardController.Mode.SYMBOLS || keyboard.mode() == KeyboardController.Mode.NUMBERS));
    }
    private String shortDeviceLabel(String value) {
        if (value == null || value.isEmpty()) return "设备";
        int count=value.codePointCount(0,value.length());
        return count <= 4 ? value : value.substring(0,value.offsetByCodePoints(0,4));
    }
    private String modeLabel() {
        switch (keyboard.mode()) { case PINYIN:return "全拼"; case T9:return "九宫格";
            case ENGLISH:return "英文"; case NUMBERS:return "数字"; default:return "符号"; }
    }
    private void openSettings() {
        Intent intent=new Intent(this,ImeSettingsActivity.class); intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP); startActivity(intent);
    }
    private static boolean shouldDefaultToVoice(EditorInfo info) {
        // Voice is the product's primary input mode for every non-sensitive field.
        // The field type still chooses the keyboard that appears after a manual
        // switch (for example numbers), but it must not silently override voice.
        return true;
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
        LinearLayout.LayoutParams params=weighted(); params.weight=weight; row.addView(button,params); return button;
    }
    private TextView railButton(LinearLayout column,String text,View.OnClickListener listener) {
        TextView button=key(text,12); button.setOnClickListener(listener);
        button.setBackground(fill(Color.TRANSPARENT,6));
        LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,0,1);
        params.setMargins(dp(2),dp(2),dp(2),dp(2)); column.addView(button,params); return button;
    }
    private void styleModeKey(TextView button,boolean selected) {
        if (button == null) return;
        button.setTextColor(selected ? Color.rgb(0,137,105) : Color.rgb(30,42,54));
        button.setBackground(fill(selected ? Color.rgb(230,247,241) : Color.TRANSPARENT,6));
    }
    private TextView key(String text,int size) {
        TextView button=new TextView(this); button.setText(text); button.setTextSize(size);
        button.setMinWidth(0); button.setMinimumWidth(0); button.setMinHeight(0); button.setMinimumHeight(0);
        button.setPadding(0,0,0,0); button.setIncludeFontPadding(false); button.setGravity(Gravity.CENTER);
        button.setTextColor(Color.rgb(28,39,50)); button.setLetterSpacing(0);
        button.setClickable(true); button.setFocusable(true);
        button.setBackground(fill(Color.rgb(244,247,249),7)); return button;
    }
    private LinearLayout.LayoutParams weighted() { LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(0,-1,1); params.setMargins(dp(2),dp(2),dp(2),dp(2)); return params; }
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
