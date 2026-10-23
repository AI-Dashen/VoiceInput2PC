package io.github.amxooo.voiceinput2pc;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.view.inputmethod.*;
import android.widget.*;
import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentResult;
import org.json.JSONObject;
import java.util.concurrent.*;

/** One management surface: device selection, history/statistics and settings expand inline. */
public final class HomeActivity extends Activity {
    public static final String EXTRA_SECTION="management.section";
    private static final int MIC_PERMISSION=808;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private SharedPreferences prefs;
    private InputHistoryStore store;
    private HistoryScreen history;
    private RecordingPanel recording;
    private ScrollView scroll;
    private LinearLayout content,devices,settings,voiceDetails,keyboardDetails,recordDetails;
    private TextView status,target,deviceHint,micStatus,imeStatus,deviceNotice;
    private EditText pairingCode;
    private boolean destroyed,pairingBusy;
    private int probeRevision;
    private final java.util.Map<String,String> probeStates=new java.util.HashMap<>();

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        if(Build.VERSION.SDK_INT>=30)getWindow().setDecorFitsSystemWindows(false);
        prefs=getSharedPreferences("voiceinput2pc",MODE_PRIVATE);store=new InputHistoryStore(this);
        history=new HistoryScreen(this,store,()->{});if(state!=null)history.restoreState(state);
        build();refresh();
        if(state!=null){
            devices.setVisibility(state.getBoolean("management.devices",false)?View.VISIBLE:View.GONE);
            if(devices.getVisibility()==View.VISIBLE)renderDevices();
            voiceDetails.setVisibility(state.getBoolean("management.voice",false)?View.VISIBLE:View.GONE);
            keyboardDetails.setVisibility(state.getBoolean("management.keyboard",false)?View.VISIBLE:View.GONE);
            recordDetails.setVisibility(state.getBoolean("management.records",false)?View.VISIBLE:View.GONE);
            int y=state.getInt("management.scroll",0);scroll.post(()->scroll.scrollTo(0,y));
        }
        if(state!=null)recording.restoreState(state);
        openIntentSection(getIntent());
    }
    private void build() {
        scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setBackgroundColor(VoiceUiTheme.PAPER);
        content=column();content.setPadding(dp(16),dp(12),dp(16),dp(20));
        content.setOnApplyWindowInsetsListener((v,insets)->{
            if(Build.VERSION.SDK_INT>=30){android.graphics.Insets bars=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());android.graphics.Insets ime=insets.getInsets(WindowInsets.Type.ime());v.setPadding(dp(16)+bars.left,dp(10)+bars.top,dp(16)+bars.right,dp(20)+Math.max(bars.bottom,ime.bottom));}
            return insets;
        });
        LinearLayout heading=row();ImeIconButton mark=icon("mic","万能输入法",null);heading.addView(mark,new LinearLayout.LayoutParams(dp(38),dp(44)));
        LinearLayout names=column();names.addView(VoiceUiTheme.title(this,"万能输入法",22));status=text("输入管理",12);names.addView(status);heading.addView(names,new LinearLayout.LayoutParams(0,-2,1));
        TextView version=text(version(),12);heading.addView(version);content.addView(heading,lp(56));
        LinearLayout targetRow=row();targetRow.setId(3104);targetRow.setContentDescription("设备与跨端，展开连接电脑");targetRow.setBackground(VoiceUiTheme.surface(this,VoiceUiTheme.SOFT_GREEN,0,10));targetRow.setPadding(dp(8),0,dp(8),0);
        targetRow.addView(icon("device","连接电脑",v->toggleDevices()),new LinearLayout.LayoutParams(dp(40),dp(48)));
        LinearLayout labels=column();target=text("输入到本机",16);target.setTextColor(VoiceUiTheme.INK);deviceHint=text("",12);labels.addView(target);labels.addView(deviceHint);targetRow.addView(labels,new LinearLayout.LayoutParams(0,-2,1));
        targetRow.addView(icon("chevron","切换设备",v->toggleDevices()),new LinearLayout.LayoutParams(dp(44),dp(48)));targetRow.setOnClickListener(v->toggleDevices());content.addView(targetRow,lp(64));
        devices=column();devices.setVisibility(View.GONE);content.addView(devices);divider(content);
        recording=new RecordingPanel(this);content.addView(recording.create());divider(content);
        View recordView=history.createEmbedded();recordView.setId(3105);recordView.setContentDescription("输入记录与统计");content.addView(recordView);divider(content);
        LinearLayout settingHeading=row();TextView title=VoiceUiTheme.title(this,"偏好设置",18);settingHeading.addView(title,new LinearLayout.LayoutParams(0,-2,1));content.addView(settingHeading,lp(44));
        settings=column();content.addView(settings);
        voiceDetails=column();keyboardDetails=column();recordDetails=column();
        addExpandable(settings,"mic","本地语音",3103,voiceDetails,"本地输入与语音");
        addExpandable(settings,"keyboard","键盘与手势",3112,keyboardDetails,"键盘与手势");
        addExpandable(settings,"history","记录与导出",3113,recordDetails,"记录与导出");
        micStatus=text("",13);micStatus.setPadding(dp(12),dp(4),dp(4),dp(8));voiceDetails.addView(micStatus);
        voiceDetails.addView(text("识别在手机本地执行；仅主动点击麦克风时收音。",13));
        Button permission=button("授权麦克风",false);permission.setId(3201);permission.setOnClickListener(v->requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},MIC_PERMISSION));voiceDetails.addView(permission,lp(44));
        imeStatus=text("",13);keyboardDetails.addView(imeStatus);
        Button enable=button("启用输入法",false);enable.setId(3101);enable.setOnClickListener(v->startActivity(new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)));keyboardDetails.addView(enable,lp(44));
        Button choose=button("选择 / 切换输入法",false);choose.setId(3102);choose.setOnClickListener(v->((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).showInputMethodPicker());keyboardDetails.addView(choose,lp(44));
        TextView gesture=text("单击收音 · 双击键盘 · 上滑标点 · 长按拖动；26键右侧为数字、语音、删除、回车。",13);gesture.setPadding(0,dp(6),0,dp(10));keyboardDetails.addView(gesture);
        Button system=button("系统输入法设置",false);system.setId(3110);system.setOnClickListener(v->startActivity(new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)));keyboardDetails.addView(system,lp(44));
        recordDetails.addView(text("只显示本机已保存的输入片段与修订。密码框不记录；未实现的准确率、语音时长不显示。",13));
        Button export=button("导出当前筛选的记录",false);export.setOnClickListener(v->content.findViewById(2707).performClick());recordDetails.addView(export,lp(44));
        recordDetails.addView(text("可导出 TXT、Markdown、JSON、CSV；在系统文件选择器中指定位置。",12));
        TextView footer=text("本地识别 · 你的输入，由你掌握",12);footer.setGravity(Gravity.CENTER);footer.setPadding(0,dp(16),0,dp(6));content.addView(footer);
        scroll.addView(content);setContentView(scroll);
    }
    private void addExpandable(LinearLayout parent,String glyph,String label,int id,LinearLayout details,String description) {
        LinearLayout heading=row();heading.setId(id);heading.setContentDescription(description);heading.setMinimumHeight(dp(48));
        heading.addView(icon(glyph,label,null),new LinearLayout.LayoutParams(dp(38),dp(44)));
        TextView name=text(label,15);name.setTextColor(VoiceUiTheme.INK);heading.addView(name,new LinearLayout.LayoutParams(0,-2,1));
        ImeIconButton expand=icon("chevron","展开"+label,null);heading.addView(expand,new LinearLayout.LayoutParams(dp(36),dp(44)));
        View.OnClickListener toggle=v->{boolean opening=details.getVisibility()!=View.VISIBLE;details.setVisibility(opening?View.VISIBLE:View.GONE);expand.setIcon(opening?"collapse":"chevron");heading.setContentDescription((opening?"收起":"展开")+description);};heading.setOnClickListener(toggle);expand.setOnClickListener(toggle);
        parent.addView(heading);details.setPadding(dp(38),0,0,dp(8));details.setVisibility(View.GONE);parent.addView(details);divider(parent);
    }
    private void toggleDevices(){devices.setVisibility(devices.getVisibility()==View.VISIBLE?View.GONE:View.VISIBLE);if(devices.getVisibility()==View.VISIBLE)renderDevices();}
    private String deviceKey(PairingConfig p){return p.deviceId.isEmpty()?"legacy:"+p.host:p.deviceId;}
    private boolean hasPending(){return !prefs.getString("ime_remote_pending","").isEmpty() || !prefs.getString("ime_remote_retry_id","").isEmpty() || !prefs.getString("pending","").isEmpty() || !prefs.getString("legacyPendingRaw","").isEmpty();}
    private boolean canChangeDevice(PairingConfig next){PairingConfig active=PairingStore.load(prefs);if(hasPending() && (active==null || next==null || !active.equals(next))){notice("有待发或结果待确认文字，请先在原目标处理；配对和草稿保持不变。");return false;}return true;}
    private void renderDevices() {
        String retainedCode=pairingCode==null?"":pairingCode.getText().toString();
        boolean codeVisible=pairingCode!=null && pairingCode.getVisibility()==View.VISIBLE;
        devices.removeAllViews();PairingConfig active=PairingStore.load(prefs);
        Button local=button("本机输入",false);local.setOnClickListener(v->selectOutput(null));devices.addView(local,lp(44));
        for(PairingConfig device:PairingStore.list(prefs)) {
            LinearLayout block=column();block.setPadding(dp(6),dp(6),dp(6),dp(6));
            LinearLayout line=row();String name=PairingStore.label(prefs,device);
            TextView label=text(name,15);label.setSingleLine(true);label.setEllipsize(android.text.TextUtils.TruncateAt.END);label.setTextColor(VoiceUiTheme.INK);line.addView(label,new LinearLayout.LayoutParams(0,-2,1));
            ImeIconButton rename=icon("edit","重命名电脑",v->rename(device));line.addView(rename,new LinearLayout.LayoutParams(dp(44),dp(44)));block.addView(line);
            String probe=probeStates.get(deviceKey(device));TextView state=text(probe==null?"已配对 · 尚未检查连接":probe,12);block.addView(state);
            LinearLayout actions=row();Button use=button(active!=null&&device.equals(active)?"选为输出":"使用此电脑",false);use.setOnClickListener(v->selectOutput(device));actions.addView(use,new LinearLayout.LayoutParams(0,dp(44),1));
            Button check=button("检查连接",false);check.setOnClickListener(v->probe(device,check));actions.addView(check,new LinearLayout.LayoutParams(0,dp(44),1));
            Button mode=button("连接方式",false);mode.setOnClickListener(v->connectionMode(device));actions.addView(mode,new LinearLayout.LayoutParams(0,dp(44),1));block.addView(actions);devices.addView(block);divider(devices);
        }
        LinearLayout add=row();Button scan=button("扫码添加",true);scan.setId(1002);scan.setOnClickListener(v->new IntentIntegrator(this).setDesiredBarcodeFormats(IntentIntegrator.QR_CODE).setPrompt("扫描电脑接收端配对二维码").setBeepEnabled(false).setBarcodeImageEnabled(false).setOrientationLocked(false).initiateScan());add.addView(scan,new LinearLayout.LayoutParams(0,dp(44),1));
        Button manual=button("粘贴配对码",false);manual.setOnClickListener(v->pairingCode.setVisibility(pairingCode.getVisibility()==View.VISIBLE?View.GONE:View.VISIBLE));add.addView(manual,new LinearLayout.LayoutParams(0,dp(44),1));devices.addView(add);
        pairingCode=new EditText(this);pairingCode.setTextSize(14);pairingCode.setHint("粘贴完整配对码后点导入");pairingCode.setMinLines(1);pairingCode.setMaxLines(3);pairingCode.setText(retainedCode);pairingCode.setVisibility(codeVisible?View.VISIBLE:View.GONE);devices.addView(pairingCode);
        Button importButton=button("导入配对码",false);importButton.setOnClickListener(v->{if(pairingCode.getVisibility()!=View.VISIBLE){pairingCode.setVisibility(View.VISIBLE);return;}importPairing(pairingCode.getText().toString().trim());});devices.addView(importButton,lp(44));
        Button legacy=button("电脑传输工具：收取文字 / 发送文件",false);legacy.setOnClickListener(v->startActivity(new Intent(this,MainActivity.class)));devices.addView(legacy,lp(44));
        deviceNotice=text("选择输出不会自动发送。新输入框默认本机；此处的选择只用于下次非密码输入框。",12);deviceNotice.setPadding(0,dp(6),0,dp(8));devices.addView(deviceNotice);
    }
    private void selectOutput(PairingConfig device) {
        if(device!=null && !canChangeDevice(device))return;
        if(device!=null && !PairingStore.save(prefs,device)){notice("设备保存失败，未切换。");return;}
        boolean saved=prefs.edit().putBoolean("management_output_remote",device!=null).putString("management_output_device",device==null?"":deviceKey(device)).putBoolean("management_output_pending",true).commit();
        if(!saved){notice("输出选择未保存，请重试。");return;}
        refresh();notice(device==null?"下次输入使用本机，不会清除电脑草稿。":"已选择电脑；下次非密码输入框生效，不会自动发送。");
    }
    private void connectionMode(PairingConfig device) {
        boolean local=prefs.getBoolean("lanOnly_"+device.fingerprint,false);
        new AlertDialog.Builder(this).setTitle("连接方式").setSingleChoiceItems(new String[]{"自动：局域网优先，跨网络备用","仅局域网"},local?1:0,(dialog,which)->{if(!prefs.edit().putBoolean("lanOnly_"+device.fingerprint,which==1).commit())notice("保存失败");else notice("连接方式已保存，下次请求生效。");dialog.dismiss();}).setNegativeButton("取消",null).show();
    }
    private void rename(PairingConfig device){EditText input=new EditText(this);input.setSingleLine(true);input.setText(PairingStore.label(prefs,device));new AlertDialog.Builder(this).setTitle("电脑名称").setView(input).setNegativeButton("取消",null).setPositiveButton("保存",(d,w)->{if(PairingStore.rename(prefs,device,input.getText().toString())){refresh();renderDevices();}else notice("名称需为1到40个字符。");}).show();}
    private void probe(PairingConfig device,Button button) {
        button.setEnabled(false);button.setText("检查中");final int revision=probeRevision;
        io.execute(()->{String result;try{JSONObject r=RelayTransportFactory.create(this,prefs,device).request(device.host,null);if(!r.optBoolean("ok")||!"VoiceInput2PC".equals(r.optString("app"))||r.optInt("protocol")!=2)throw new IllegalStateException("Protocol");result="连接检查通过 · "+java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"));}catch(Exception failure){result="连接检查失败 · "+failure.getClass().getSimpleName();}final String message=result;main.post(()->{if(destroyed||revision!=probeRevision)return;probeStates.put(deviceKey(device),message);renderDevices();});});
    }
    private void importPairing(String raw) {
        if(pairingBusy)return;final PairingConfig candidate;try{candidate=PairingCodec.decode(raw);}catch(IllegalArgumentException e){notice("配对码无效，原连接未改变。");return;}
        if(!canChangeDevice(candidate))return;pairingBusy=true;notice("正在核对电脑身份…");
        io.execute(()->{boolean verified=false;try{JSONObject r=RelayTransportFactory.create(this,prefs,candidate).request(candidate.host,null);verified=r.optBoolean("ok")&&"VoiceInput2PC".equals(r.optString("app"))&&r.optInt("protocol")==2;}catch(Exception ignored){}final boolean ok=verified;main.post(()->{if(destroyed)return;pairingBusy=false;if(!ok){notice("配对验证失败；检查接收端与网络，原配置未改变。");return;}if(!canChangeDevice(candidate))return;if(!PairingStore.register(prefs,candidate)){notice("保存失败，原配置未改变。");return;}probeStates.put(deviceKey(candidate),"配对验证通过 · 尚未选为输出");refresh();renderDevices();notice("配对成功，点选为输出后才切换。");});});
    }
    private void refresh() {
        if(status==null)return;
        InputMethodManager manager=(InputMethodManager)getSystemService(INPUT_METHOD_SERVICE);boolean enabled=false;
        for(InputMethodInfo info:manager.getEnabledInputMethodList())if(info.getPackageName().equals(getPackageName())&&info.getServiceName().equals(UniversalInputMethodService.class.getName()))enabled=true;
        boolean selected=new ComponentName(this,UniversalInputMethodService.class).flattenToString().equals(Settings.Secure.getString(getContentResolver(),Settings.Secure.DEFAULT_INPUT_METHOD));
        status.setText(selected?"当前输入法 · 本地识别":"输入管理 · "+(enabled?"已启用，待切换":"尚未启用"));
        imeStatus.setText(selected?"当前正在使用":enabled?"已启用，尚未选为当前":"未启用");findViewById(3101).setVisibility(enabled?View.GONE:View.VISIBLE);
        ((Button)findViewById(3102)).setText(selected?"切换其他输入法":"设为当前输入法");
        boolean mic=checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED;micStatus.setText(mic?"麦克风：已授权":"麦克风：未授权");findViewById(3201).setVisibility(mic?View.GONE:View.VISIBLE);
        PairingConfig active=PairingStore.load(prefs);boolean pending=prefs.getBoolean("management_output_pending",false),remote=prefs.getBoolean("management_output_remote",false);
        target.setText(pending?(remote&&active!=null?"下次输入："+PairingStore.label(prefs,active):"下次输入：本机"):"输入到本机 / 已选电脑");
        if(!pending)target.setText("输入目标与电脑");
        target.setSingleLine(true);target.setEllipsize(android.text.TextUtils.TruncateAt.END);
        deviceHint.setText("已配对 "+PairingStore.list(prefs).size()+" 台电脑"+(hasPending()?" · 有待处理文字":""));
    }
    private String version(){try{return getPackageManager().getPackageInfo(getPackageName(),0).versionName.split("-")[0];}catch(Exception ignored){return "";}}
    private void notice(String s){if(deviceNotice!=null)deviceNotice.setText(s);Toast.makeText(this,s,Toast.LENGTH_SHORT).show();}
    private void openIntentSection(Intent intent){if(intent==null)return;String section=intent.getStringExtra(EXTRA_SECTION);if(section==null)return;intent.removeExtra(EXTRA_SECTION);if("recordings".equals(section)){scroll.post(()->scroll.smoothScrollTo(0,Math.max(0,content.findViewById(3400).getTop()-dp(16))));return;}if("devices".equals(section)){devices.setVisibility(View.VISIBLE);renderDevices();}else if("settings".equals(section)){keyboardDetails.setVisibility(View.VISIBLE);}final View destination="history".equals(section)?content.findViewById(3105):"settings".equals(section)?settings:devices;scroll.post(()->scroll.smoothScrollTo(0,Math.max(0,destination.getTop()-dp(16))));}
    @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);setIntent(intent);openIntentSection(intent);}
    @Override protected void onResume(){super.onResume();refresh();if(recording!=null)recording.resume();if(devices!=null && devices.getVisibility()==View.VISIBLE)renderDevices();if(history!=null)history.refreshEmbedded();}
    @Override protected void onPause(){if(recording!=null)recording.pause();probeRevision++;probeStates.clear();super.onPause();}
    @Override protected void onSaveInstanceState(Bundle state){recording.saveState(state);history.saveState(state);state.putBoolean("management.devices",devices.getVisibility()==View.VISIBLE);state.putBoolean("management.voice",voiceDetails.getVisibility()==View.VISIBLE);state.putBoolean("management.keyboard",keyboardDetails.getVisibility()==View.VISIBLE);state.putBoolean("management.records",recordDetails.getVisibility()==View.VISIBLE);state.putInt("management.scroll",scroll.getScrollY());super.onSaveInstanceState(state);}
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(recording!=null&&recording.handleResult(request,result,data))return;if(history.handleActivityResult(request,result,data))return;IntentResult scan=IntentIntegrator.parseActivityResult(request,result,data);if(scan!=null&&scan.getContents()!=null)importPairing(scan.getContents());}
    @Override public void onRequestPermissionsResult(int code,String[] p,int[] result){super.onRequestPermissionsResult(code,p,result);if(recording!=null)recording.permissionResult(code);refresh();}
    @Override protected void onDestroy(){if(recording!=null)recording.destroy();destroyed=true;probeRevision++;io.shutdown();main.removeCallbacksAndMessages(null);if(history!=null)history.closeStoreWhenIdle();super.onDestroy();}
    private LinearLayout column(){LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.VERTICAL);return v;}
    private LinearLayout row(){LinearLayout v=new LinearLayout(this);v.setGravity(Gravity.CENTER_VERTICAL);return v;}
    private TextView text(String value,int size){return VoiceUiTheme.text(this,value,size,VoiceUiTheme.MUTED);}
    private Button button(String label,boolean primary){return VoiceUiTheme.button(this,label,primary);}
    private ImeIconButton icon(String glyph,String desc,View.OnClickListener action){ImeIconButton icon=new ImeIconButton(this,glyph,desc);icon.setOnClickListener(action);if(action==null){icon.setClickable(false);icon.setFocusable(false);}return icon;}
    private LinearLayout.LayoutParams lp(int height){return new LinearLayout.LayoutParams(-1,height<0?height:dp(height));}
    private void divider(LinearLayout parent){View line=new View(this);line.setBackgroundColor(VoiceUiTheme.BORDER);LinearLayout.LayoutParams lp=lp(1);lp.topMargin=dp(10);lp.bottomMargin=dp(10);parent.addView(line,lp);}
    private int dp(int v){return VoiceUiTheme.dp(this,v);}
}
