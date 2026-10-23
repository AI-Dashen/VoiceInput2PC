package io.github.amxooo.voiceinput2pc;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;

/** One inline recorder surface; no microphone access until the user presses start. */
final class RecordingPanel {
    static final int EXPORT_REQUEST=3410, MIC_PERMISSION=3411, NOTIFICATION_PERMISSION=3412;
    private final Activity activity;
    private RecordingRepository repository;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private static final java.util.concurrent.atomic.AtomicBoolean exportBusy=new java.util.concurrent.atomic.AtomicBoolean();
    private LinearLayout root,list;
    private TextView status,notice;
    private Button start,pause,stop,toggle;
    private boolean resumed,destroyed,loading,expanded,lastExportBusy;
    private int limit=5,generation;
    private String lastState="",pendingExport="",playing="";
    private MediaPlayer player;
    private String transcriptName="";
    private RecordingTranscriptPanel transcript;
    private long playbackEndMs=Long.MAX_VALUE;
    private final Runnable playbackLimit=new Runnable(){public void run(){if(player==null)return;try{if(player.isPlaying()&&player.getCurrentPosition()>=playbackEndMs){releasePlayer();notice.setText("片段回放结束。");return;}}catch(RuntimeException e){releasePlayer();return;}main.postDelayed(this,100);}};
    private List<RecordingRepository.Entry> entries=Collections.emptyList();
    private final Runnable tick=new Runnable(){public void run(){if(!resumed||destroyed)return;refresh();if(transcript!=null)transcript.refresh();main.postDelayed(this,500);}};

    RecordingPanel(Activity activity){this.activity=activity;}
    View create(){
        root=VoiceUiTheme.card(activity);root.setId(3400);
        LinearLayout heading=row();TextView title=VoiceUiTheme.title(activity,"录音",18);heading.addView(title,new LinearLayout.LayoutParams(0,-2,1));
        TextView local=text("本机保存",12);heading.addView(local);root.addView(heading,params(36));
        status=text("未录音",15);status.setId(3404);status.setTextColor(VoiceUiTheme.INK);root.addView(status,params(32));
        LinearLayout controls=row();
        start=button("开始录音",true);start.setId(3401);start.setOnClickListener(v->start());controls.addView(start,weighted());
        pause=button("暂停",false);pause.setId(3402);pause.setOnClickListener(v->command("PAUSED".equals(VoiceRecordingService.getState())?VoiceRecordingService.ACTION_RESUME:VoiceRecordingService.ACTION_PAUSE));controls.addView(pause,weighted());
        stop=button("结束保存",false);stop.setId(3403);stop.setOnClickListener(v->command(VoiceRecordingService.ACTION_STOP));controls.addView(stop,weighted());root.addView(controls);
        notice=text("主动开始才录音；切换应用后可从录音通知暂停或结束。",12);notice.setPadding(0,dp(8),0,dp(4));root.addView(notice);
        toggle=button("录音文件",false);toggle.setId(3406);toggle.setOnClickListener(v->{expanded=!expanded;list.setVisibility(expanded?View.VISIBLE:View.GONE);if(expanded)reload();});root.addView(toggle,params(42));
        list=new LinearLayout(activity);list.setOrientation(LinearLayout.VERTICAL);list.setId(3405);list.setVisibility(View.GONE);root.addView(list);
        refresh();reload();return root;
    }
    private void start(){
        if(activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){
            notice.setText("请授权麦克风后，再点击开始录音。");activity.requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},MIC_PERMISSION);return;
        }
        if(Build.VERSION.SDK_INT>=33 && !activity.getPreferences(Activity.MODE_PRIVATE).getBoolean("recording.notificationChoice",false) && activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED){
            notice.setText("请先选择是否允许录音通知，再点击开始；不允许通知也能在此页结束录音。");activity.requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},NOTIFICATION_PERMISSION);return;
        }
        startAfterPermissionChoice();
    }
    void permissionResult(int request){
        if(request==NOTIFICATION_PERMISSION){activity.getPreferences(Activity.MODE_PRIVATE).edit().putBoolean("recording.notificationChoice",true).apply();notice.setText("通知选择已保存。点击开始录音即可。");}
        else if(request==MIC_PERMISSION)notice.setText(activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED?"麦克风已授权，请点击开始录音。":"未授权麦克风，未开始录音。");
    }
    private void startAfterPermissionChoice(){
        releasePlayer();command(VoiceRecordingService.ACTION_START);
    }
    private void command(String action){
        try{VoiceRecordingService.command(activity,action);notice.setText(VoiceRecordingService.ACTION_START.equals(action)?"正在启动，请以录音计时状态为准。":"操作已提交，请以当前状态为准。");}
        catch(RuntimeException e){notice.setText("操作未成功："+e.getClass().getSimpleName()+"。请回到此页重试。");}
        refresh();
    }
    private void refresh(){
        String state=VoiceRecordingService.getState();boolean active=VoiceRecordingService.isActive();
        if(lastExportBusy!=exportBusy.get()){lastExportBusy=exportBusy.get();if(expanded)renderEntries();}
        String label="RECORDING".equals(state)?"● 录音中":"PAUSED".equals(state)?"已暂停":"STARTING".equals(state)?"正在启动":"SAVING".equals(state)?"正在保存":"ERROR".equals(state)?"录音未完成":"未录音";
        status.setText(label+(active?"  "+duration(VoiceRecordingService.getElapsed()):""));
        status.setTextColor("RECORDING".equals(state)?0xffcc3b39:VoiceUiTheme.INK);
        if(active&&!VoiceRecordingService.getError().isEmpty())notice.setText(VoiceRecordingService.getError());
        start.setEnabled(!active&&!exportBusy.get());pause.setEnabled("RECORDING".equals(state)||"PAUSED".equals(state));pause.setText("PAUSED".equals(state)?"继续":"暂停");stop.setEnabled("RECORDING".equals(state)||"PAUSED".equals(state));
        if(!state.equals(lastState)){lastState=state;if("ERROR".equals(state))notice.setText(VoiceRecordingService.getError());if(!active)reload();if(!VoiceRecordingService.getError().isEmpty())notice.setText(VoiceRecordingService.getError());if(expanded)renderEntries();}
    }
    private void reload(){
        if(loading||destroyed)return;loading=true;final int revision=++generation;
        io.execute(()->{List<RecordingRepository.Entry> result;String error=null;try{if(repository==null)repository=new RecordingRepository(activity);result=repository.list();}catch(Exception e){result=Collections.emptyList();error="无法读取录音文件："+e.getClass().getSimpleName();}final List<RecordingRepository.Entry> found=result;final String failure=error;main.post(()->{loading=false;if(destroyed||revision!=generation)return;entries=found;toggle.setText((expanded?"收起录音":"录音文件")+" · "+entries.size());if(failure!=null)notice.setText(failure);renderEntries();});});
    }
    private void renderEntries(){
        if(list==null)return;if(transcript!=null){transcript.destroy();transcript=null;}list.removeAllViews();if(entries.isEmpty()){list.addView(text("还没有录音。",13));return;}
        int count=0;for(RecordingRepository.Entry entry:entries){if(VoiceRecordingService.isActive()&&entry.file.getName().endsWith(".part.m4a"))continue;if(count++>=limit)break;
            LinearLayout item=new LinearLayout(activity);item.setOrientation(LinearLayout.VERTICAL);item.setPadding(0,dp(8),0,dp(8));
            String date=new SimpleDateFormat("MM-dd HH:mm",Locale.CHINA).format(new Date(entry.startedAt));
            item.addView(text(date+" · "+duration(entry.durationMs)+" · "+String.format(Locale.ROOT,"%.1f MB",entry.file.length()/1000000.0),14));
            if(entry.interrupted)item.addView(text("异常中断残留 · 可能无法回放，已保留原文件",12));
            LinearLayout actions=row();Button play=button(entry.file.getName().equals(playing)?"停止回放":"播放",false);play.setEnabled(!VoiceRecordingService.isActive());play.setOnClickListener(v->play(entry));actions.addView(play,weighted());
            Button export=button("导出",false);export.setEnabled(!exportBusy.get());export.setOnClickListener(v->chooseExport(entry));actions.addView(export,weighted());item.addView(actions);
            if(!entry.interrupted){Button words=button(entry.file.getName().equals(transcriptName)?"收起文字":"文字与说话人",false);words.setOnClickListener(v->{transcriptName=entry.file.getName().equals(transcriptName)?"":entry.file.getName();renderEntries();});item.addView(words,params(40));
                if(entry.file.getName().equals(transcriptName)){transcript=new RecordingTranscriptPanel(activity,entry.file,this::playRange);item.addView(transcript.create());}}
            list.addView(item);
        }
        if(entries.size()>limit){Button more=button("显示更多录音",false);more.setOnClickListener(v->{limit+=10;renderEntries();});list.addView(more,params(42));}
    }
    private void play(RecordingRepository.Entry entry){
        if(VoiceRecordingService.isActive()){notice.setText("请先结束录音，再回放。");return;}
        boolean same=entry.file.getName().equals(playing);releasePlayer();if(same){renderEntries();return;}
        try{
            MediaPlayer created=new MediaPlayer();player=created;playing=entry.file.getName();created.setDataSource(entry.file.getAbsolutePath());
            created.setOnPreparedListener(p->{if(player==p&&!VoiceRecordingService.isActive()){p.start();notice.setText("正在回放 "+entry.file.getName());}else if(player==p)releasePlayer();});
            created.setOnCompletionListener(p->{if(player==p){releasePlayer();renderEntries();notice.setText("回放结束。");}});
            created.setOnErrorListener((p,what,extra)->{if(player==p){releasePlayer();renderEntries();notice.setText("无法回放此文件，原录音保留。");}return true;});created.prepareAsync();renderEntries();
        }catch(Exception e){releasePlayer();renderEntries();notice.setText("回放失败："+e.getClass().getSimpleName()+"，文件未删除。");}
    }
    private void playRange(File audio,long from,long until){
        if(VoiceRecordingService.isActive()){notice.setText("请先结束录音，再回听片段。");return;}
        releasePlayer();try{MediaPlayer created=new MediaPlayer();player=created;playing=audio.getName();playbackEndMs=until;
            created.setDataSource(audio.getAbsolutePath());
            created.setOnPreparedListener(p->{if(player!=p)return;if(VoiceRecordingService.isActive()){releasePlayer();return;}p.seekTo((int)Math.max(0,from));});
            created.setOnSeekCompleteListener(p->{if(player==p&&!VoiceRecordingService.isActive()){p.start();main.post(playbackLimit);notice.setText("正在回听所选发言。");}});
            created.setOnCompletionListener(p->{if(player==p)releasePlayer();});created.setOnErrorListener((p,w,e)->{if(player==p)releasePlayer();notice.setText("片段回听失败，录音仍保留。");return true;});created.prepareAsync();
        }catch(Exception e){releasePlayer();notice.setText("无法回听："+e.getClass().getSimpleName());}
    }
    private void releasePlayer(){main.removeCallbacks(playbackLimit);playbackEndMs=Long.MAX_VALUE;if(player!=null){try{player.release();}catch(RuntimeException ignored){}player=null;}playing="";}
    private void chooseExport(RecordingRepository.Entry entry){
        if(exportBusy.get()||!pendingExport.isEmpty())return;pendingExport=entry.file.getName();
        Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("audio/mp4");intent.putExtra(Intent.EXTRA_TITLE,pendingExport.endsWith(".part.m4a")?pendingExport+".bin":pendingExport);
        try{activity.startActivityForResult(intent,EXPORT_REQUEST);}catch(RuntimeException e){pendingExport="";notice.setText("无法打开导出位置："+e.getClass().getSimpleName());}
    }
    boolean handleResult(int request,int result,Intent data){
        if(request!=EXPORT_REQUEST)return false;String name=pendingExport;pendingExport="";
        if(result!=Activity.RESULT_OK||data==null||data.getData()==null||name.isEmpty())return true;
        final android.net.Uri destination=data.getData();if(!exportBusy.compareAndSet(false,true)){notice.setText("已有录音正在导出。");return true;}
        notice.setText("正在导出录音…");io.execute(()->{String message;try{
            File base=new File(activity.getFilesDir(),"recordings").getCanonicalFile();File source=new File(base,name).getCanonicalFile();
            if(!source.getParentFile().equals(base)||!source.isFile())throw new IOException("录音文件不存在");
            try(InputStream in=new FileInputStream(source);OutputStream out=activity.getContentResolver().openOutputStream(destination,"wt")){if(out==null)throw new IOException("无法打开目标");byte[] buffer=new byte[65536];int n;while((n=in.read(buffer))!=-1)out.write(buffer,0,n);out.flush();}message="录音已导出到所选位置。";
        }catch(Exception e){message="导出失败："+e.getClass().getSimpleName()+"，原录音仍保留。";}finally{exportBusy.set(false);}final String text=message;main.post(()->{if(!destroyed){notice.setText(text);renderEntries();}});});return true;
    }
    void saveState(Bundle b){b.putString("recording.transcript",transcriptName);b.putBoolean("recording.expanded",expanded);b.putString("recording.export",pendingExport);}
    void restoreState(Bundle b){if(b!=null){transcriptName=b.getString("recording.transcript","");expanded=b.getBoolean("recording.expanded");pendingExport=b.getString("recording.export","");if(list!=null)list.setVisibility(expanded?View.VISIBLE:View.GONE);}}
    void resume(){io.execute(()->RecordingTranscriptionService.recover(activity.getApplicationContext()));resumed=true;main.removeCallbacks(tick);main.post(tick);reload();}
    void pause(){resumed=false;main.removeCallbacks(tick);releasePlayer();}
    void destroy(){if(transcript!=null){transcript.destroy();transcript=null;}destroyed=true;generation++;pause();io.shutdown();main.removeCallbacksAndMessages(null);}
    private static String duration(long ms){long s=Math.max(0,ms/1000);return String.format(Locale.ROOT,"%02d:%02d:%02d",s/3600,(s/60)%60,s%60);}
    private int dp(int n){return VoiceUiTheme.dp(activity,n);}
    private TextView text(String s,int size){return VoiceUiTheme.text(activity,s,size,VoiceUiTheme.MUTED);}
    private Button button(String s,boolean primary){return VoiceUiTheme.button(activity,s,primary);}
    private LinearLayout row(){LinearLayout r=new LinearLayout(activity);r.setGravity(Gravity.CENTER_VERTICAL);return r;}
    private LinearLayout.LayoutParams params(int h){return new LinearLayout.LayoutParams(-1,dp(h));}
    private LinearLayout.LayoutParams weighted(){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(44),1);p.setMargins(dp(2),0,dp(2),0);return p;}
}
