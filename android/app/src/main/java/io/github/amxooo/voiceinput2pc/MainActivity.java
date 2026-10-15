package io.github.amxooo.voiceinput2pc;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Build;
import android.view.WindowInsets;
import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentResult;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import org.json.JSONObject;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    public static final String EXTRA_OPEN_HISTORY = "io.github.amxooo.voiceinput2pc.OPEN_HISTORY";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private CommitEditText editor;
    private TextView status, destination, counter;
    private Button start, fresh, retry, receive, sendFile;
    private Button pairingButton;
    private EditText pairingInput;
    private SharedPreferences prefs;
    private InputHistoryStore history;
    private String historySession;
    private String historySource = "phone_ime";
    private HistoryScreen historyScreen;
    private View inputRoot;
    private boolean reconnecting, resumeAfterReconnect;
    private int reconnectAttempt, reconnectEpoch;
    private final Runnable reconnect = this::retryConnection;
    private void retryConnection() {
        if (!destroyed && resumed && reconnecting && reconnectEpoch == activationEpoch
                && tracker != null && tracker.pending() != null && !busy)
            dispatch(tracker.pending());
    }
    private RelayTransport client;
    private PairingConfig currentPairing;
    private boolean scannerReturnToTyping;
    private RelayTransport receiveQueueClient;
    private CommitTracker tracker;
    private String host, receiveQueueHost, legacyPendingRaw = "";
    private boolean busy, loading, destroyed, resumed, storageBlocked, sendingText, receiveQueued;
    private boolean fileTransferSupported;
    private int activationEpoch, receiveQueueEpoch = -1;
    private final int green = VoiceUiTheme.GREEN;
    private final Runnable flush = this::transmitCommitted;
    private static final int PICK_FILE_REQUEST = 2606;

    private final int paper = VoiceUiTheme.PAPER;
    private final int brown = VoiceUiTheme.BROWN;
    private GradientDrawable surface(int color, int border, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color); d.setCornerRadius(dp(radius));
        if (border != 0) d.setStroke(dp(1), border);
        return d;
    }
    private void styleAction(Button b, boolean primary) {
        b.setAllCaps(false); b.setTextSize(14); b.setMinWidth(0); b.setMinimumWidth(0);
        b.setMinHeight(0); b.setMinimumHeight(0); b.setPadding(dp(8),0,dp(8),0);
        b.setTextColor(primary ? Color.WHITE : brown);
        b.setBackgroundTintList(null);
        b.setBackground(surface(primary ? brown : Color.rgb(255,249,242),
                primary ? 0 : Color.rgb(218,200,179),22));
    }
    private LinearLayout sectionCard() { return VoiceUiTheme.card(this); }

    private LinearLayout.LayoutParams sectionParams() {
        LinearLayout.LayoutParams value = row(-2);
        value.bottomMargin = dp(12);
        return value;
    }

    private TextView sectionTitle(String value) {
        return VoiceUiTheme.title(this, value, 17);
    }

    private TextView sectionNote(String value) {
        TextView note = VoiceUiTheme.text(this, value, 13, VoiceUiTheme.MUTED);
        note.setPadding(0, dp(5), 0, 0);
        return note;
    }

    private void showHistory() {
        if (busy) { say("请等待当前请求完成后查看历史。", true); return; }
        if (tracker != null) { pauseLocal(); if (!save()) return; }
        final View retained = inputRoot;
        if (historyScreen != null) historyScreen.destroy();
        historyScreen = new HistoryScreen(this, history, () -> {
            setContentView(retained);
            if (tracker != null) {
                updateControls(); say("已暂停 · 草稿已保留，点开始继续", false);
            }
        });
        ((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(
                getWindow().getDecorView().getWindowToken(),0);
        historyScreen.show();
    }
    private void showDevices() {
        if (!canChangeConnection()) return;
        java.util.List<PairingConfig> devices=PairingStore.list(prefs);
        LinearLayout content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(18),dp(16),dp(18),dp(10));content.setBackgroundColor(VoiceUiTheme.PAPER);
        content.addView(VoiceUiTheme.title(this,"选择电脑",24));
        TextView note=VoiceUiTheme.text(this,"切换目标不会删除草稿。新电脑只需重新扫码配对一次。",13,VoiceUiTheme.MUTED);
        note.setPadding(0,dp(4),0,dp(12));content.addView(note);
        android.widget.ScrollView listScroll=new android.widget.ScrollView(this);
        LinearLayout list=new LinearLayout(this);list.setOrientation(LinearLayout.VERTICAL);
        listScroll.addView(list);
        if (devices.isEmpty()) {
            TextView empty=VoiceUiTheme.text(this,"还没有已配对电脑",14,VoiceUiTheme.MUTED);
            empty.setPadding(dp(4),dp(12),dp(4),dp(12));list.addView(empty);
        }
        final AlertDialog dialog=new AlertDialog.Builder(this).create();
        for (PairingConfig device:devices) {
            boolean active=device.equals(currentPairing);
            String deviceLabel=PairingStore.label(prefs,device)+(active ? " · 当前" : "");
            Button deviceRow=VoiceUiTheme.button(this,deviceLabel,false);
            deviceRow.setGravity(android.view.Gravity.START | android.view.Gravity.CENTER_VERTICAL);
            deviceRow.setContentDescription((active ? "当前电脑 " : "切换到电脑 ")+PairingStore.label(prefs,device));
            LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,dp(50));params.bottomMargin=dp(8);
            list.addView(deviceRow,params);
            deviceRow.setOnClickListener(v-> {
                if (!PairingStore.save(prefs,device)) { say("设备选择保存失败。",true);return; }
                dialog.dismiss();showTypingScreen(device);
            });
        }
        LinearLayout.LayoutParams scrollParams=new LinearLayout.LayoutParams(-1,0,1);
        content.addView(listScroll,scrollParams);
        Button add=VoiceUiTheme.button(this,"连接新电脑",true);
        LinearLayout.LayoutParams addParams=new LinearLayout.LayoutParams(-1,dp(48));addParams.topMargin=dp(10);
        content.addView(add,addParams);
        add.setOnClickListener(v-> {
            dialog.dismiss();scannerReturnToTyping=currentPairing!=null;
            showPairingScreen("扫描新电脑的配对码，草稿已保留。");
        });
        Button close=VoiceUiTheme.button(this,"关闭",false);
        LinearLayout.LayoutParams closeParams=new LinearLayout.LayoutParams(-1,dp(44));closeParams.topMargin=dp(8);
        content.addView(close,closeParams);close.setOnClickListener(v->dialog.dismiss());
        dialog.setView(content);dialog.setOnShowListener(ignored-> {
            android.view.Window window=dialog.getWindow();
            if (window!=null) window.setLayout(-1,Math.min(dp(560),getResources().getDisplayMetrics().heightPixels-dp(80)));
        });
        dialog.show();
    }

    private void renameDevice() {
        if(currentPairing==null || !canChangeConnection())return;
        EditText name=new EditText(this);
        name.setSingleLine(true);name.setText(PairingStore.label(prefs,currentPairing));
        new AlertDialog.Builder(this).setTitle("电脑名称").setView(name)
            .setNegativeButton("取消",null).setPositiveButton("保存",(d,w)-> {
                if(PairingStore.rename(prefs,currentPairing,name.getText().toString().trim()))
                    destination.setText(PairingStore.label(prefs,currentPairing)+"  ▾");
                else say("名称需为1到40个字符，且能保存。",true);
            }).show();
    }
    @Override public void onBackPressed() {
        if(historyScreen!=null && historyScreen.onBackPressed())return;
        if(scannerReturnToTyping && currentPairing!=null && !busy){
            scannerReturnToTyping=false;showTypingScreen(currentPairing);return;
        }
        super.onBackPressed();
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private TextView label(String text, int size, int color) {
        TextView view = new TextView(this);
        view.setText(text); view.setTextSize(size); view.setTextColor(color);
        return view;
    }
    private LinearLayout.LayoutParams row(int height) {
        return new LinearLayout.LayoutParams(-1, height < 0 ? height : dp(height));
    }
    private void say(String text, boolean error) {
        status.setText(text); status.setTextColor(error ? Color.rgb(168,70,35) : green);
    }

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        if (Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(false);
        prefs = getSharedPreferences("voiceinput2pc", MODE_PRIVATE);
        history = new InputHistoryStore(this);
        historySession = prefs.getString("historySession", "");
        if (historySession.isEmpty()) {
            historySession = UUID.randomUUID().toString();
            if (!prefs.edit().putString("historySession", historySession).commit()) storageBlocked = true;
        }
        scannerReturnToTyping = savedInstanceState != null && savedInstanceState.getBoolean("scannerReturnToTyping", false);
        PairingConfig pairing = PairingStore.load(prefs);
        String incoming = incomingPairing(getIntent());
        if (incoming != null) {
            showPairingScreen("正在读取电脑配对码…");
            pairingInput.setText(incoming);
            importPairing(incoming);
        } else if (pairing == null) {
            showPairingScreen("请先在电脑接收端打开“配对手机”。");
        } else {
            showTypingScreen(pairing);
        }
        if (savedInstanceState != null && savedInstanceState.getBoolean("history_visible",false)) {
            showHistory(); historyScreen.restoreState(savedInstanceState); historyScreen.show();
        } else if (getIntent().getBooleanExtra(EXTRA_OPEN_HISTORY,false)) {
            getIntent().removeExtra(EXTRA_OPEN_HISTORY);
            showHistory();
        }
    }

    @Override protected void onSaveInstanceState(Bundle outState) {
        outState.putBoolean("scannerReturnToTyping", scannerReturnToTyping);
        outState.putBoolean("history_visible",historyScreen!=null && historyScreen.isVisible());
        if(historyScreen!=null)historyScreen.saveState(outState);
        super.onSaveInstanceState(outState);
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent != null && intent.getBooleanExtra(EXTRA_OPEN_HISTORY,false)) {
            intent.removeExtra(EXTRA_OPEN_HISTORY);
            showHistory();
            return;
        }
        String incoming = incomingPairing(intent);
        if (incoming != null) {
            clearQueuedReceive();
            if (tracker != null) { pauseLocal(); save(); }
            showPairingScreen("正在读取电脑配对码…");
            pairingInput.setText(incoming);
            importPairing(incoming);
        }
    }

    private String incomingPairing(Intent intent) {
        if (intent == null || !Intent.ACTION_VIEW.equals(intent.getAction())
                || intent.getData() == null) return null;
        return intent.getData().toString();
    }

    private void showPairingScreen(String message) {
        clearQueuedReceive();
        setTitle("连接电脑");
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(paper);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        applyScreenInsets(layout);
        layout.setBackgroundColor(paper);

        TextView title = VoiceUiTheme.title(this,"连接电脑",28);
        layout.addView(title,row(-2));
        TextView guide = VoiceUiTheme.text(this,
                "把手机上的文字直接送到电脑光标。首次使用只需完成一次安全配对。",
                14,VoiceUiTheme.MUTED);
        guide.setPadding(0,dp(4),0,dp(16));
        layout.addView(guide,row(-2));

        LinearLayout quick = sectionCard();
        quick.addView(sectionTitle("扫码配对"));
        quick.addView(sectionNote("在电脑接收端打开“配对手机”，再扫描屏幕上的二维码。"));
        Button scan = VoiceUiTheme.button(this,"扫码连接电脑",true);
        scan.setId(1002);
        scan.setContentDescription("扫描电脑接收端显示的配对二维码");
        LinearLayout.LayoutParams scanParams=row(48);scanParams.topMargin=dp(12);
        quick.addView(scan,scanParams);
        scan.setOnClickListener(v -> launchScanner());
        layout.addView(quick,sectionParams());

        LinearLayout manual = sectionCard();
        manual.addView(sectionTitle("手动导入"));
        manual.addView(sectionNote("也可以粘贴电脑生成的完整配对码。原有设备不会被覆盖。"));
        pairingInput = new EditText(this);
        pairingInput.setHint("粘贴 voiceinput2pc:// 开头的配对码");
        pairingInput.setTextSize(14);
        pairingInput.setTextColor(VoiceUiTheme.INK);
        pairingInput.setHintTextColor(Color.rgb(139,132,123));
        pairingInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_VARIATION_URI);
        pairingInput.setMinLines(3);pairingInput.setMaxLines(6);
        pairingInput.setGravity(android.view.Gravity.TOP | android.view.Gravity.START);
        pairingInput.setPadding(dp(12),dp(10),dp(12),dp(10));
        pairingInput.setBackground(VoiceUiTheme.surface(this,Color.WHITE,VoiceUiTheme.BORDER,12));
        LinearLayout.LayoutParams codeParams=row(-2);codeParams.topMargin=dp(12);
        manual.addView(pairingInput,codeParams);
        pairingButton = VoiceUiTheme.button(this,"导入配对码",false);
        pairingButton.setContentDescription("核对并导入粘贴的电脑配对码");
        pairingButton.setOnClickListener(v -> importPairing(pairingInput.getText().toString().trim()));
        LinearLayout.LayoutParams importParams=row(46);importParams.topMargin=dp(10);
        manual.addView(pairingButton,importParams);
        layout.addView(manual,sectionParams());

        LinearLayout stateCard=sectionCard();
        LinearLayout stateHeading=new LinearLayout(this);stateHeading.setGravity(android.view.Gravity.CENTER_VERTICAL);
        stateHeading.addView(sectionTitle("连接状态"),new LinearLayout.LayoutParams(0,-2,1));
        stateHeading.addView(VoiceUiTheme.statusChip(this,"待配对",false),new LinearLayout.LayoutParams(-2,-2));
        stateCard.addView(stateHeading);
        status = VoiceUiTheme.text(this,message,14,green);
        status.setPadding(0,dp(9),0,0);status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        stateCard.addView(status);
        layout.addView(stateCard,sectionParams());

        LinearLayout shortcuts=sectionCard();
        shortcuts.addView(sectionTitle("设备与记录"));
        Button paired=VoiceUiTheme.button(this,"已配对电脑  ›",false);
        LinearLayout.LayoutParams pairedParams=row(46);pairedParams.topMargin=dp(8);
        shortcuts.addView(paired,pairedParams);paired.setOnClickListener(v->showDevices());
        Button records=VoiceUiTheme.button(this,"输入历史  ›",false);
        records.setId(1004);LinearLayout.LayoutParams historyParams=row(46);historyParams.topMargin=dp(8);
        shortcuts.addView(records,historyParams);records.setOnClickListener(v->showHistory());
        layout.addView(shortcuts,sectionParams());

        TextView safety=sectionNote("配对码相当于连接密码，只在自己的手机和电脑之间使用。扫码时才会申请相机权限。");
        safety.setPadding(dp(4),0,dp(4),dp(8));layout.addView(safety,row(-2));
        scroll.addView(layout);
        inputRoot=scroll;setContentView(scroll);
    }

    private void applyScreenInsets(LinearLayout layout) {
        layout.setPadding(dp(12), dp(8), dp(12), dp(6));
        if (Build.VERSION.SDK_INT >= 30) {
            layout.setOnApplyWindowInsetsListener((view, insets) -> {
                android.graphics.Insets bars = insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                android.graphics.Insets ime = insets.getInsets(WindowInsets.Type.ime());
                // Edge-to-edge content gets one inset; never add keyboard height twice.
                view.setPadding(bars.left + dp(12), bars.top + dp(8), bars.right + dp(12),
                        Math.max(bars.bottom, ime.bottom) + dp(6));
                return insets;
            });
        }
    }

    private void launchScanner() {
        IntentIntegrator scanner = new IntentIntegrator(this);
        scanner.setDesiredBarcodeFormats(IntentIntegrator.QR_CODE);
        scanner.setPrompt("将电脑配对二维码放入框内");
        scanner.setBeepEnabled(false);
        scanner.setBarcodeImageEnabled(false);
        scanner.setOrientationLocked(false);
        scanner.initiateScan();
    }

    private boolean canChangeConnection() {
        if (busy || (tracker != null && tracker.pending() != null)) {
            say("请先处理待确认文字，再调整连接。", true);
            return false;
        }
        if (tracker != null) { pauseLocal(); return save(); }
        return true;
    }

    private void showMore() {
        String[] items = {"输入历史", "选择电脑", "电脑名称", "发送文件到电脑",
                "连接方式", "连接诊断", "核对并恢复输入", "本地输入法设置", "使用说明"};
        new AlertDialog.Builder(this).setTitle("更多")
            .setItems(items,(dialog,which)-> {
                if(which==0)showHistory();
                else if(which==1)showDevices();
                else if(which==2)renameDevice();
                else if(which==3){
                    if(sendFile.isEnabled())sendFile.performClick();
                    else say("当前暂不能发送文件，请核对电脑连接和版本。",true);
                } else if(which==4)showConnectionModes();
                else if(which==5)showConnectionDiagnostics();
                else if(which==6)newDraft();
                else if(which==7)startActivity(new Intent(this, ImeSettingsActivity.class));
                else new AlertDialog.Builder(this).setTitle("持续输入")
                    .setMessage("在电脑选中输入框，点开始，然后一直写或说。使用你当前输入法的麦克风，无需新建段落。电脑窗口切换后，新文字进入当前光标。临时识别文字不会发送；已输入文字被改动时先核对，电脑原文不会自动删除。后台、历史页或不确定回执会暂停，草稿和历史保留。")
                    .setPositiveButton("知道了",null).show();
            }).show();
    }

    private void showConnectionModes() {
        boolean local = prefs.getBoolean("lanOnly_" + currentPairing.fingerprint, false);
        new AlertDialog.Builder(this).setTitle("连接方式")
                .setSingleChoiceItems(new String[]{"自动：局域网优先，跨网络备用", "仅局域网"}, local ? 1 : 0,
                        (dialog, which) -> {
                            if (!canChangeConnection()) return;
                            if (!prefs.edit().putBoolean("lanOnly_" + currentPairing.fingerprint, which == 1).commit()) {
                                say("连接设置保存失败。", true); return;
                            }
                            dialog.dismiss(); showTypingScreen(currentPairing);
                        }).setNegativeButton("取消", null).show();
    }

    private void showConnectionDiagnostics() {
        String detail = "电脑局域网地址：" + currentPairing.host + ":" + currentPairing.port;
        if (client instanceof ConnectionManager) {
            ConnectionManager manager = (ConnectionManager) client;
            detail += "\n当前路径：" + manager.activeMode()
                    + "\n探测地址：" + manager.currentLanHost()
                    + "\n局域网探测：" + manager.lastLanFailure();
        } else detail += "\n连接设置：" + (prefs.getBoolean("lanOnly_" + currentPairing.fingerprint, false)
                ? "仅局域网" : currentPairing.transport);
        detail += "\n\n旁路由修改网关不会必然阻止直连；若探测失败，请检查电脑地址、防火墙及设备间路由。";
        new AlertDialog.Builder(this).setTitle("连接诊断").setMessage(detail)
                .setPositiveButton("关闭", null).show();
    }

    private void importPairing(String raw) {
        final PairingConfig candidate;
        try {
            candidate = PairingCodec.decode(raw);
        } catch (IllegalArgumentException invalid) {
            say("配对码无效，请重新扫描或完整粘贴。原有连接没有改变。", true);
            return;
        }
        pairingButton.setEnabled(false);
        say("正在核对电脑身份和连接凭据…", false);
        worker.execute(() -> {
            try {
                RelayTransport candidateClient = createTransport(candidate);
                JSONObject result = candidateClient.request(candidate.host, null);
                if (!Boolean.TRUE.equals(result.opt("ok"))
                        || !"VoiceInput2PC".equals(result.optString("app"))
                        || result.optInt("protocol") != 2) {
                    throw new Exception("接收端版本不兼容");
                }
                handler.post(() -> {
                    if (destroyed) return;
                    if (!PairingStore.save(prefs, candidate)) {
                        pairingButton.setEnabled(true);
                        say("手机无法保存连接配置，请检查存储空间后重试。", true);
                        return;
                    }
                    client = candidateClient;
                    showTypingScreen(candidate);
                    fileTransferSupported = hasFeature(result, "file-upload-v1");
                    updateControls();
                    String routeNote = routeNote(candidateClient);
                    say(result.optBoolean("paused")
                            ? "已连接" + routeNote + " · 请先在电脑启用输入，再选中输入框"
                            : "已连接" + routeNote + " · 先选中电脑输入框，再点开始", false);
                });
            } catch (Exception failure) {
                final String detail = pairingFailureText(failure);
                handler.post(() -> {
                    if (destroyed || pairingButton == null) return;
                    pairingButton.setEnabled(true);
                    say(detail, true);
                });
            }
        });
    }

    private RelayTransport createTransport(PairingConfig pairing) throws Exception {
        return RelayTransportFactory.create(this,prefs,pairing);
    }

    private String routeNote(RelayTransport transport) {
        if (!(transport instanceof ConnectionManager)) return currentPairing != null
                && !PairingConfig.TRANSPORT_TAILCAT.equals(currentPairing.transport) ? " · 本地直连" : "";
        String mode = ((ConnectionManager) transport).activeMode();
        if ("lan".equals(mode)) return " · 本地直连";
        if ("tailcat".equals(mode)) return " · 远程连接";
        return "";
    }

    private String pairingFailureText(Exception failure) {
        String message = failure == null ? "" : failure.getMessage();
        if (message == null || message.trim().isEmpty()) {
            message = failure == null ? "未知错误" : failure.getClass().getSimpleName();
        }
        Throwable cause=failure;
        while(cause!=null) {
            if(cause instanceof java.net.ConnectException)
                return "连接失败 · 没有找到电脑接收端，请确认电脑端已经打开";
            if(cause instanceof java.net.SocketTimeoutException)
                return "连接超时 · 请确认手机与电脑网络正常后重试";
            if(cause instanceof java.net.UnknownHostException)
                return "连接失败 · 找不到电脑地址，请重新配对或检查网络";
            cause=cause.getCause();
        }
        message = message.replaceAll("tc[A-Za-z0-9_-]{20,4094}", "tc<redacted>");
        if (message.length() > 120) message = message.substring(0, 120);
        return "连接失败 · " + message;
    }

    private void showTypingScreen(PairingConfig pairing) {
        currentPairing = pairing;
        clearQueuedReceive();
        fileTransferSupported = false;
        setTitle("万能输入法");
        try {
            host = prefs.getString("host", pairing.host);
            client = createTransport(pairing);
        } catch (Exception invalid) {
            showPairingScreen("保存的连接配置无法使用，请重新配对。");
            return;
        }
        String draft = prefs.getString("draft", ""), sent = prefs.getString("sent", "");
        String old = prefs.getString("pending", "");
        CommitTracker.Pending pending = null;
        legacyPendingRaw = prefs.getString("legacyPendingRaw", "");
        if (!old.isEmpty()) {
            String snapshot = prefs.getString("pendingSnapshot", draft);
            try {
                JSONObject value = new JSONObject(old);
                pending = new CommitTracker.Pending(value.getString("id"), snapshot,
                    value.getString("text"), value.optString("session", ""), value.optLong("seq", 0));
            } catch (Exception invalid) {
                legacyPendingRaw = old;
                pending = new CommitTracker.Pending("legacy-unconfirmed", snapshot, draft, "");
            }
        }
        tracker = new CommitTracker(sent, draft, pending, prefs.getBoolean("savedOnly", false));
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        applyScreenInsets(layout);
        layout.setBackgroundColor(paper);

        LinearLayout heading = new LinearLayout(this);
        heading.setGravity(android.view.Gravity.CENTER_VERTICAL);
        TextView title = VoiceUiTheme.title(this,"电脑输入",27);
        heading.addView(title,new LinearLayout.LayoutParams(0,-2,1));
        Button more=VoiceUiTheme.button(this,"历史",false);more.setId(1004);
        more.setContentDescription("打开输入历史");more.setOnClickListener(v->showHistory());
        heading.addView(more,new LinearLayout.LayoutParams(dp(72),dp(40)));
        layout.addView(heading,row(-2));
        LinearLayout deviceCard=sectionCard();
        LinearLayout deviceHeading=new LinearLayout(this);deviceHeading.setGravity(android.view.Gravity.CENTER_VERTICAL);
        deviceHeading.addView(sectionTitle("当前电脑"),new LinearLayout.LayoutParams(0,-2,1));
        TextView connected=VoiceUiTheme.statusChip(this,"已配对",true);
        deviceHeading.addView(connected,new LinearLayout.LayoutParams(-2,-2));
        deviceCard.addView(deviceHeading);
        destination=VoiceUiTheme.text(this,PairingStore.label(prefs,pairing)+"  ›",16,VoiceUiTheme.BROWN);
        destination.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        destination.setPadding(0,dp(10),0,dp(4));destination.setMinHeight(dp(44));
        destination.setGravity(android.view.Gravity.CENTER_VERTICAL);
        destination.setContentDescription("管理当前电脑 "+PairingStore.label(prefs,pairing));
        destination.setOnClickListener(v->showDevices());deviceCard.addView(destination,row(-2));
        status=VoiceUiTheme.text(this,"已暂停 · 文字只保存在本机",13,green);
        status.setPadding(0,dp(2),0,0);status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        deviceCard.addView(status,row(-2));
        layout.addView(deviceCard,sectionParams());

        LinearLayout editorCard=sectionCard();
        editorCard.addView(sectionTitle("要输入的文字"));
        editor = new CommitEditText(this);
        editor.setId(1001);editor.setSaveEnabled(false);
        editor.setTextSize(20);editor.setTextColor(VoiceUiTheme.INK);
        editor.setHintTextColor(Color.rgb(139,132,123));
        editor.setGravity(android.view.Gravity.TOP | android.view.Gravity.START);
        editor.setHint("在这里写字，或用输入法麦克风说话…\n\n点开始后，确认的文字会持续进入电脑当前光标。");
        editor.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        editor.setImeOptions(EditorInfo.IME_FLAG_NO_EXTRACT_UI | EditorInfo.IME_ACTION_NONE);
        editor.setPadding(dp(12),dp(12),dp(12),dp(12));editor.setMinLines(3);
        editor.setBackground(VoiceUiTheme.surface(this,Color.WHITE,VoiceUiTheme.BORDER,12));
        LinearLayout.LayoutParams inputParams=new LinearLayout.LayoutParams(-1,0,1);
        inputParams.topMargin=dp(10);editorCard.addView(editor,inputParams);
        counter=VoiceUiTheme.text(this,"",12,VoiceUiTheme.MUTED);
        counter.setGravity(android.view.Gravity.END);counter.setPadding(0,dp(5),0,0);
        editorCard.addView(counter,row(-2));
        LinearLayout.LayoutParams editorCardParams=new LinearLayout.LayoutParams(-1,0,1);
        editorCardParams.bottomMargin=dp(10);layout.addView(editorCard,editorCardParams);

        retry=VoiceUiTheme.button(this,"重试确认（同一条）",false);
        retry.setOnClickListener(v->retryPending());layout.addView(retry,row(44));
        fresh=VoiceUiTheme.button(this,"核对后恢复",false);
        layout.addView(fresh,row(44));

        LinearLayout actions=new LinearLayout(this);actions.setGravity(android.view.Gravity.CENTER_VERTICAL);
        start=VoiceUiTheme.button(this,"开始输入",true);
        receive=VoiceUiTheme.button(this,"接收",false);
        Button options=VoiceUiTheme.button(this,"更多",false);options.setId(1003);
        options.setContentDescription("更多电脑输入功能");options.setOnClickListener(v->showMore());
        actions.addView(start,new LinearLayout.LayoutParams(0,dp(48),2));
        LinearLayout.LayoutParams receiveParams=new LinearLayout.LayoutParams(0,dp(48),1);
        receiveParams.leftMargin=dp(8);actions.addView(receive,receiveParams);
        LinearLayout.LayoutParams optionParams=new LinearLayout.LayoutParams(0,dp(48),1);
        optionParams.leftMargin=dp(8);actions.addView(options,optionParams);
        layout.addView(actions,row(48));
        sendFile=new Button(this);sendFile.setText("发送文件到电脑");
        inputRoot=layout;setContentView(layout);
        loading=true; editor.setText(draft); editor.setSelection(editor.length()); loading=false;
        editor.setEditObserver(this::edited);
        start.setOnClickListener(v -> toggleStart()); fresh.setOnClickListener(v -> newDraft());
        receive.setOnClickListener(v -> receiveFromComputer());
        sendFile.setOnClickListener(v -> chooseFile());
        updateControls();
        if (pending != null) uncertainHint();
        else if (tracker.isSaved()) say("电脑只保存了文字，未自动输入。点“保留并重置”核对后恢复。",true);
        else if (tracker.hasConflict()) say("已输入部分发生修改。电脑原文不会改动，请保留并重置。",true);
        else if (!draft.isEmpty()) say("草稿已恢复，当前暂停。点开始前请核对电脑已有文字。",false);
        else health();
        probeCapabilities();
    }

    private void probeCapabilities() {
        final RelayTransport probeClient = client;
        final String address = host;
        worker.execute(() -> {
            try {
                JSONObject result = probeClient.request(address, null);
                final boolean supported = Boolean.TRUE.equals(result.opt("ok"))
                        && hasFeature(result, "file-upload-v1");
                handler.post(() -> {
                    if (destroyed || probeClient != client || !address.equals(host)) return;
                    fileTransferSupported = supported;
                    updateControls();
                });
            } catch (Exception ignored) {
                handler.post(() -> {
                    if (destroyed || probeClient != client || !address.equals(host)) return;
                    fileTransferSupported = false;
                    updateControls();
                });
            }
        });
    }

    private boolean hasFeature(JSONObject result, String feature) {
        org.json.JSONArray features = result.optJSONArray("features");
        if (features == null) return false;
        for (int i = 0; i < features.length(); i++) {
            if (feature.equals(features.optString(i))) return true;
        }
        return false;
    }

    private boolean save() {
        if (destroyed || tracker == null) return false;
        CommitTracker.Pending pending = tracker.pending();
        try {
            if (!editor.hasComposition() && !editor.hasOpenEdit()) {
                String state = pending != null ? "QUEUED" : tracker.hasConflict() ? "EDIT_CONFLICT"
                    : tracker.isSaved() ? "SAVED_NOT_INSERTED" : tracker.isActive() ? "ACTIVE" : "PAUSED";
                history.record(historySession, historySource, currentPairing.deviceId, state,
                    pending == null ? "" : pending.id, pending == null ? 0 : pending.seq, tracker.draft());
            }
            String message = pending == null ? "" : message(pending).toString();
            boolean stored = prefs.edit().putInt("schema",2).putString("host",host)
                .putString("draft",tracker.draft()).putString("sent",tracker.sent())
                .putString("pending",message).putString("pendingSnapshot",pending == null ? "" : pending.snapshot)
                .putBoolean("savedOnly",tracker.isSaved()).putString("legacyPendingRaw",legacyPendingRaw)
                .putBoolean("auto",false).putString("historySession",historySession).commit();
            storageBlocked = !stored;
            if (!stored) { tracker.pause(); say("无法保存草稿，已暂停。请检查手机存储空间后重试。",true); }
            return stored;
        } catch (Exception e) {
            storageBlocked = true; tracker.pause(); say("无法保存待确认文字，已暂停。",true); return false;
        }
    }
    private JSONObject message(CommitTracker.Pending pending) throws Exception {
        JSONObject value = new JSONObject().put("id",pending.id).put("text",pending.text).put("session",pending.session);
        if (pending.seq > 0) value.put("seq", pending.seq);
        return value;
    }
    private void updateControls() {
        if (destroyed || tracker == null) return;
        start.setText(reconnecting ? "停止重连" : tracker.isActive() ? "暂停输入" : "开始输入");
        start.setEnabled(reconnecting || tracker.isActive() || (!busy && !storageBlocked && tracker.pending() == null && !tracker.isSaved() && !tracker.hasConflict()));
        fresh.setEnabled(!busy);
        fresh.setText("核对后恢复");
        fresh.setVisibility(tracker.pending()!=null || tracker.isSaved() || tracker.hasConflict() ? View.VISIBLE : View.GONE);
        boolean cleanForReceive = !storageBlocked && !tracker.isSaved() && !tracker.hasConflict();
        receive.setEnabled((!busy && cleanForReceive && tracker.pending() == null)
            || (sendingText && !receiveQueued && cleanForReceive && tracker.pending() != null));
        retry.setVisibility(tracker.pending() != null && !tracker.pending().session.isEmpty() ? View.VISIBLE : View.GONE);
        retry.setEnabled(!busy);
        if (sendFile != null) sendFile.setEnabled(!busy && resumed && fileTransferSupported);
        counter.setText("电脑已接收输入 " + tracker.sent().codePointCount(0,tracker.sent().length())
            + " 字 · 本地草稿 " + tracker.draft().codePointCount(0,tracker.draft().length()) + " 字");
        if (tracker.isActive() && resumed) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }
    private void edited() {
        if (loading || destroyed) return;
        historySource = "phone_ime";
        tracker.observe(editor.getText().toString(),editor.hasComposition(),editor.hasOpenEdit());
        if (editor.wasConnectionInterrupted()) {
            pauseLocal(); say("输入法在确认文字前断开，已暂停。请核对草稿后重新开始。",true);
        }
        save(); updateControls(); handler.removeCallbacks(flush);
        if (tracker.hasConflict()) {
            say("已修改已输入或待确认的前缀，已暂停。电脑原文不会删除；请核对后重置。",true);
        } else if (resumed && tracker.isActive() && !busy) {
            // Coalesce within this UI turn; commit state comes from InputConnection, never elapsed time.
            handler.post(flush);
        }
    }
    private void pauseLocal() {
        activationEpoch++; tracker.pause(); handler.removeCallbacks(flush); handler.removeCallbacks(reconnect);
        reconnecting=false;resumeAfterReconnect=false;reconnectAttempt=0;updateControls();
    }
    private void toggleStart() {
        if (destroyed || !resumed) return;
        if (reconnecting) {
            pauseLocal(); save();
            say("已停止重连 · 本条文字仍保留，核对电脑后再处理",false);
            return;
        }
        if (tracker.isActive()) {
            pauseLocal(); save();
            say(busy ? "已暂停后续输入；已发出的这条请求仍可能完成，请等待确认。" : "已暂停 · 再次开始时会重新确认电脑窗口",false);
            return;
        }
        if (busy) return;
        if (tracker.pending() != null || tracker.isSaved() || tracker.hasConflict()) return;
        if (!tracker.unsentForReset().isEmpty()) {
            new AlertDialog.Builder(this).setTitle("开始自动输入")
                .setMessage("开始后，草稿中尚未输入的文字会自动进入电脑当前输入框。请先核对电脑内容并选中目标输入框。")
                .setNegativeButton("取消",null).setPositiveButton("开始",(d,w)->beginSession()).show();
        } else beginSession();
    }
    private void beginSession() {
        if (busy || destroyed || !resumed) return;
        pauseLocal(); final int attempt = activationEpoch;
        final String address = host;
        busy=true; updateControls(); say("正在确认电脑输入窗口…",false);
        worker.execute(() -> {
            try {
                JSONObject result = client.session(address);
                handler.post(() -> {
                    if (destroyed) return;
                    busy=false;
                    if (!resumed || attempt != activationEpoch) { updateControls(); return; }
                    Object session = result.opt("session");
                    if (!Boolean.TRUE.equals(result.opt("ok")) || !(session instanceof String) || ((String)session).isEmpty()) {
                        say(result.optString("note","电脑未建立输入会话，请检查接收端版本和目标输入框。"),true);
                    } else if (result.has("next_seq")
                            ? tracker.startOrdered((String)session, result.optLong("next_seq", -1))
                            : tracker.start((String)session)) {
                        say("正在输入 · 用手机输入法麦克风说话，确认文字后自动输入电脑",false);
                        editor.requestFocus(); editor.setSelection(editor.length());
                        ((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).showSoftInput(editor,InputMethodManager.SHOW_IMPLICIT);
                        edited();
                    }
                    updateControls();
                });
            } catch (Exception e) {
                handler.post(() -> {
                    if (destroyed) return;
                    busy=false; updateControls();
                    if (attempt == activationEpoch) say("未能开始。请确认电脑接收端已启动、处于启用状态，并选中输入框。",true);
                });
            }
        });
    }
    private void transmitCommitted() {
        if (destroyed || !resumed || busy || !tracker.isActive()) return;
        tracker.observe(editor.getText().toString(),editor.hasComposition(),editor.hasOpenEdit());
        if (tracker.hasConflict()) { pauseLocal(); save(); return; }
        String unsent = tracker.unsentForReset();
        if (unsent.codePointCount(0,unsent.length()) > 20000) {
            pauseLocal(); save(); say("未输入文字超过 20000 字，请先分段整理草稿。",true); return;
        }
        CommitTracker.Pending pending = tracker.prepare(UUID.randomUUID().toString());
        if (pending != null) dispatch(pending);
    }
    private void dispatch(CommitTracker.Pending pending) {
        if (destroyed || busy || !resumed) return;
        // Exact UUID, session, raw snapshot and transmitted text are durable BEFORE HTTPS.
        if (!save()) { updateControls(); return; }
        final JSONObject body;
        try { body = message(pending); }
        catch (Exception e) { pauseLocal(); uncertainHint(); return; }
        final String address = host;
        final int requestEpoch = activationEpoch;
        busy=true; sendingText=true; updateControls(); say("正在输入电脑…",false);
        worker.execute(() -> {
            try {
                JSONObject result = client.request(address,body);
                handler.post(() -> {
                    if (destroyed) return;
                    busy=false; sendingText=false;
                    Object id = result.opt("id"), resultStatus = result.opt("status");
                    CommitTracker.Pending awaiting = tracker.pending();
                    boolean sequenceMatches = awaiting != null && (awaiting.seq == 0
                        || result.opt("seq") instanceof Number && result.optLong("seq", -1) == awaiting.seq
                        && result.optDouble("seq", -1) == awaiting.seq);
                    boolean valid = sequenceMatches && id instanceof String && resultStatus instanceof String
                        && tracker.receipt((String)id,Boolean.TRUE.equals(result.opt("ok")),(String)resultStatus);
                    if (!valid) { clearQueuedReceive(); pauseLocal(); save(); uncertainHint(); return; }
                    try { history.record(historySession, historySource, currentPairing.deviceId,
                            "inserted".equals((String)resultStatus) ? "INSERTED" : "SAVED_NOT_INSERTED",
                            awaiting.id, awaiting.seq, awaiting.snapshot); }
                    catch (Exception storageFailure) {
                        pauseLocal(); save(); storageBlocked = true; updateControls();
                        say("电脑已返回结果，但历史保存失败。已暂停，请核对电脑，不要重复发送。", true); return;
                    }
                    boolean continueInput = reconnecting && resumeAfterReconnect && resumed
                        && reconnectEpoch == activationEpoch && "inserted".equals(resultStatus)
                        && !tracker.hasConflict() && !tracker.isSaved();
                    reconnecting=false;resumeAfterReconnect=false;reconnectAttempt=0;
                    handler.removeCallbacks(reconnect);
                    if(continueInput) {
                        if(awaiting.seq>0)tracker.startOrdered(awaiting.session,awaiting.seq+1);
                        else tracker.start(awaiting.session);
                    }
                    if (!save()) { clearQueuedReceive(); updateControls(); return; }
                    RelayTransport queuedClient = receiveQueueClient;
                    String queuedHost = receiveQueueHost;
                    boolean runQueuedReceive = queuedReceiveIsCurrent() && tracker.pending() == null
                        && !tracker.isSaved() && !tracker.hasConflict();
                    clearQueuedReceive();
                    if (runQueuedReceive) {
                        beginReceiveFromComputer(queuedClient,queuedHost,activationEpoch);
                        return;
                    }
                    updateControls();
                    if (tracker.isSaved()) {
                        say(result.optString("note","电脑只保存了文字，未自动输入。") + " 请核对后保留并重置。",true);
                    } else if (tracker.hasConflict()) {
                        say("电脑已接收待确认文字，但手机前缀被改过。当前暂停，请核对并重置。",true);
                    } else if (tracker.isActive() && resumed) {
                        say("已交给电脑输入 · 可继续说话",false); handler.post(flush);
                    } else say("待确认文字已交给电脑输入，当前仍暂停。",false);
                });
            } catch (Exception e) {
                handler.post(() -> {
                    if (destroyed) return;
                    busy=false;sendingText=false;clearQueuedReceive();
                    boolean mayResume = reconnecting ? resumeAfterReconnect : tracker.isActive();
                    int retries = reconnectAttempt;
                    pauseLocal();save();
                    if(resumed && requestEpoch == activationEpoch-1 && tracker.pending()!=null && !tracker.pending().session.isEmpty() && retries<2) {
                        reconnecting=true;resumeAfterReconnect=mayResume;
                        reconnectAttempt=retries+1;reconnectEpoch=activationEpoch;
                        updateControls();say("连接中断 · 已保留本条，正在重连 "+reconnectAttempt+"/2",true);
                        handler.postDelayed(reconnect,reconnectAttempt*1500L);
                    } else uncertainHint();
                });
            }
        });
    }
    private void uncertainHint() {
        updateControls();
        if (tracker.pending() != null && tracker.pending().session.isEmpty())
            say("保留了旧版待确认文字。请先核对电脑是否已有，再点“保留并重置”；不会自动重发。",true);
        else say("这条文字是否已输入尚未确认。点“重试确认”沿用原编号；不要重新输入同一段。",true);
    }
    private void retryPending() {
        if (busy || destroyed || !resumed || tracker.pending() == null || tracker.pending().session.isEmpty()) return;
        pauseLocal();
        new AlertDialog.Builder(this).setTitle("重试确认同一条文字")
            .setMessage("将沿用原编号和原输入会话。电脑若已处理，会返回原结果；若会话已失效，只保存文字。确认后仍保持暂停。")
            .setNegativeButton("取消",null).setPositiveButton("重试确认",(d,w)-> {
                if (tracker.pending() != null) dispatch(tracker.pending());
            }).show();
    }
    private void receiveFromComputer() {
        if (destroyed || !resumed || storageBlocked || tracker.isSaved() || tracker.hasConflict()) return;
        if (busy) {
            if (!sendingText || receiveQueued || tracker.pending() == null) return;
            pauseLocal();
            receiveQueued=true;
            receiveQueueEpoch=activationEpoch;
            receiveQueueHost=host;
            receiveQueueClient=client;
            if (!save()) { clearQueuedReceive(); updateControls(); return; }
            updateControls();
            say("已暂停后续输入 · 当前文字确认后自动接收",false);
            return;
        }
        if (tracker.pending() != null) return;
        pauseLocal();
        if (!save()) { updateControls(); return; }
        beginReceiveFromComputer(client,host,activationEpoch);
    }
    private void beginReceiveFromComputer(RelayTransport receiveClient, String address, int attempt) {
        busy=true; updateControls(); say("正在接收电脑文字…",false);
        worker.execute(() -> {
            try {
                JSONObject result=receiveClient.receive(address);
                handler.post(() -> {
                    if (destroyed) return;
                    busy=false;
                    if (!resumed || attempt != activationEpoch || receiveClient != client
                            || !address.equals(host)) { updateControls(); return; }
                    if (!Boolean.TRUE.equals(result.opt("ok"))) {
                        updateControls(); say("电脑没有返回可用文字，请稍后重试。",true); return;
                    }
                    if (!result.optBoolean("available",false)) {
                        updateControls(); say("电脑暂无待接收文字。先在电脑复制文字并点“发送剪贴板到手机”。",false); return;
                    }
                    Object itemId=result.opt("id"), itemText=result.opt("text");
                    if (!(itemId instanceof String) || !(itemText instanceof String)
                            || !validReceived((String)itemId,(String)itemText)) {
                        updateControls(); say("电脑返回的文字格式不正确，原草稿已保留。",true); return;
                    }
                    String id=(String)itemId, text=(String)itemText;
                    historySource="pc_clipboard";
                    historySession=UUID.randomUUID().toString();
                    loading=true; tracker.reset(text); legacyPendingRaw="";
                    editor.setText(text); editor.setSelection(editor.length()); loading=false;
                    if (!save()) { updateControls(); return; }
                    updateControls(); say("已接收电脑文字 · 当前保持暂停",false);
                    worker.execute(() -> acknowledgeReceived(receiveClient,address,id,attempt));
                });
            } catch(Exception e) {
                handler.post(() -> {
                    if (destroyed) return;
                    busy=false; updateControls();
                    if (attempt == activationEpoch)
                        say("接收失败。请确认电脑接收端已启动、两端网络可达，然后重试。",true);
                });
            }
        });
    }
    private void acknowledgeReceived(RelayTransport receiveClient, String address, String id, int attempt) {
        try {
            JSONObject result=receiveClient.acknowledge(address,id);
            Object resultId=result.opt("id");
            if (!Boolean.TRUE.equals(result.opt("ok")) || !(resultId instanceof String)
                    || !id.equals(resultId) || !"received".equals(result.optString("status")))
                throw new Exception("invalid acknowledgement");
        } catch(Exception e) {
            handler.post(() -> {
                if (!destroyed && attempt == activationEpoch && receiveClient == client
                        && address.equals(host))
                    say("文字已接收；电脑尚未确认，下次可能再次收到同一条。",true);
            });
        }
    }
    private boolean queuedReceiveIsCurrent() {
        return receiveQueued && receiveQueueEpoch == activationEpoch
            && receiveQueueClient == client && receiveQueueHost != null
            && receiveQueueHost.equals(host);
    }
    private void clearQueuedReceive() {
        receiveQueued=false;
        receiveQueueEpoch=-1;
        receiveQueueHost=null;
        receiveQueueClient=null;
    }
    private boolean validReceived(String id, String text) {
        if (!id.matches("[A-Za-z0-9_-]{1,80}") || text.isEmpty()
                || text.codePointCount(0,text.length())>20000) return false;
        for (int i=0;i<text.length();i++) {
            char c=text.charAt(i);
            if (c<32 && c!='\r' && c!='\n' && c!='\t') return false;
            if (Character.isHighSurrogate(c)) {
                if (++i>=text.length() || !Character.isLowSurrogate(text.charAt(i))) return false;
            } else if (Character.isLowSurrogate(c)) return false;
        }
        return true;
    }
    private void chooseFile() {
        if (busy || destroyed || !resumed) return;
        if (tracker != null && tracker.isActive()) {
            pauseLocal();
            save();
        }
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        startActivityForResult(intent, PICK_FILE_REQUEST);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if(historyScreen!=null && historyScreen.handleActivityResult(requestCode,resultCode,data))return;
        IntentResult scanned = IntentIntegrator.parseActivityResult(requestCode, resultCode, data);
        if (scanned != null) {
            if (scanned.getContents() != null) {
                // Camera UI may outlive this Activity after background process recreation.
                showPairingScreen("正在读取扫码结果…");
                pairingInput.setText(scanned.getContents());
                importPairing(scanned.getContents());
            } else if (scannerReturnToTyping && currentPairing != null) {
                showTypingScreen(currentPairing);
            } else say("已取消扫码，可重新扫描或粘贴配对码。", false);
            scannerReturnToTyping = false;
            return;
        }
        if (requestCode != PICK_FILE_REQUEST || resultCode != RESULT_OK
                || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        try {
            int flags = data.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION;
            getContentResolver().takePersistableUriPermission(uri, flags);
        } catch (Exception ignored) {
        }
        sendSelectedFile(uri);
    }

    private void sendSelectedFile(Uri uri) {
        if (busy || destroyed) return;
        busy = true;
        updateControls();
        say("正在读取文件并计算 SHA-256…", false);
        final String address = host;
        final RelayTransport transferClient = client;
        worker.execute(() -> {
            try {
                AndroidFileTransfer.Metadata metadata = AndroidFileTransfer.inspect(
                        getContentResolver(), uri, (done, total) -> {
                            if (done == 0) return;
                            final long mb = done / (1024 * 1024);
                            handler.post(() -> {
                                if (!destroyed) say("正在校验文件 · 已读取 " + mb + " MB", false);
                            });
                        });
                handler.post(() -> {
                    if (!destroyed) say("准备发送 " + metadata.name + " · "
                            + formatBytes(metadata.size), false);
                });
                JSONObject result = AndroidFileTransfer.upload(
                        getContentResolver(), uri, metadata, transferClient, address,
                        (sent, total) -> {
                            final int percent = total <= 0 ? 0 : (int)Math.min(100, sent * 100 / total);
                            handler.post(() -> {
                                if (!destroyed) say("正在发送 " + metadata.name + " · " + percent + "%", false);
                            });
                        });
                String savedName = result.optString("name", metadata.name);
                handler.post(() -> {
                    if (destroyed) return;
                    busy = false;
                    updateControls();
                    say("文件已发送到电脑 · " + savedName, false);
                });
            } catch (Exception error) {
                final String detail = safeFileError(error);
                handler.post(() -> {
                    if (destroyed) return;
                    busy = false;
                    updateControls();
                    say("文件发送失败 · " + detail + " · 可重新选择同一文件续传", true);
                });
            }
        });
    }

    private String safeFileError(Exception error) {
        String message = error == null ? "" : error.getMessage();
        if (message == null || message.trim().isEmpty()) {
            message = error == null ? "未知错误" : error.getClass().getSimpleName();
        }
        message = message.replaceAll("tc[A-Za-z0-9_-]{20,4094}", "tc<redacted>");
        return message.length() > 220 ? message.substring(0, 220) : message;
    }

    private String formatBytes(long bytes) {
        if (bytes >= 1024L * 1024L) {
            return String.format(java.util.Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
        }
        if (bytes >= 1024L) {
            return String.format(java.util.Locale.ROOT, "%.1f KB", bytes / 1024.0);
        }
        return bytes + " B";
    }

    private void newDraft() {
        if (busy || destroyed) return;
        pauseLocal();
        if (tracker.pending() != null) {
            new AlertDialog.Builder(this).setTitle("先核对电脑内容")
                .setMessage("这条文字可能已经输入。建议先重试确认。若无法确认，请在电脑逐字核对，再选择：已有则只保留后续文字；没有则保留全部未输入文字。")
                .setNegativeButton("取消",null)
                .setNeutralButton("电脑已有这条",(d,w)-> {
                    CommitTracker.Pending pending = tracker.pending();
                    String draft = tracker.draft();
                    if (!draft.startsWith(pending.snapshot)) {
                        say("手机前缀已改动，无法自动区分。请先把前缀恢复为待确认原文，再核对重置。",true); return;
                    }
                    resetDraft(draft.substring(pending.snapshot.length()));
                })
                .setPositiveButton("电脑没有，保留",(d,w)->resetDraft(tracker.unsentForReset())).show();
        } else if (tracker.isSaved() || tracker.hasConflict() || !tracker.unsentForReset().isEmpty()) {
            new AlertDialog.Builder(this).setTitle("核对后恢复输入")
                .setMessage(tracker.hasConflict()
                    ? "已输入部分被修改，电脑原文仍在。将保留整份修订稿；再次开始前请手动删去草稿中不需重复输入的部分。"
                    : "将保留尚未输入的文字并重置状态。核对电脑输入框后，需要再点开始才会输入。")
                .setNegativeButton("取消",null).setPositiveButton("保留并重置",(d,w)->resetDraft(tracker.unsentForReset())).show();
        } else resetDraft("");
    }
    private void resetDraft(String retained) {
        if (destroyed || busy) return;
        if(!save())return;
        historySession=UUID.randomUUID().toString();historySource="phone_ime";
        loading=true; tracker.reset(retained); legacyPendingRaw="";
        editor.setText(retained); editor.setSelection(editor.length()); loading=false;
        save(); updateControls(); say("修订已保留 · 核对电脑已有文字后，点开始继续。",false);
        editor.requestFocus();
    }
    private void health() {
        final String address=host;
        final RelayTransport probeClient = client;
        worker.execute(() -> {
            try {
                JSONObject result=probeClient.request(address,null);
                handler.post(() -> {
                    if (destroyed || probeClient != client || busy || tracker.isActive() || tracker.pending()!=null || tracker.isSaved() || tracker.hasConflict()) return;
                    if (!Boolean.TRUE.equals(result.opt("ok")) || !"VoiceInput2PC".equals(result.optString("app")) || result.optInt("protocol") != 2) {
                        say("电脑接收端需要更新到远程键盘版本。",true);
                    } else {
                        fileTransferSupported = hasFeature(result, "file-upload-v1");
                        updateControls();
                        String routeNote = routeNote(probeClient);
                        say(result.optBoolean("paused") ? "已连接" + routeNote + " · 请先在电脑启用输入，再选中输入框" : "已连接" + routeNote + " · 先选中电脑输入框，再点开始",false);
                    }
                });
            } catch(Exception e) {
                handler.post(() -> {
                    if (!destroyed && probeClient == client && !busy && !tracker.isActive() && tracker.pending()==null && !tracker.isSaved() && !tracker.hasConflict())
                        say(pairingFailureText(e),true);
                });
            }
        });
    }
    @Override public void onResume() { super.onResume(); resumed=true; if(tracker!=null) updateControls(); }
    @Override public void onPause() {
        resumed=false;
        clearQueuedReceive();
        if (tracker!=null) { pauseLocal(); save(); say("已暂停 · 回到前台后请重新点开始",false); }
        super.onPause();
    }
    @Override public void onDestroy() {
        if(historyScreen!=null)historyScreen.destroy();
        destroyed=true; clearQueuedReceive(); handler.removeCallbacksAndMessages(null); worker.shutdown();
        if (history != null) history.close();
        super.onDestroy();
    }
}
