package io.github.amxooo.voiceinput2pc;

import android.app.job.*;
import android.content.*;
import android.os.Build;
import org.json.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Offline audio jobs: JobScheduler supplies the execution lifecycle, JSON supplies durable progress. */
public final class RecordingTranscriptionService extends JobService {
    private static final int JOB_ID=3501;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private volatile AtomicBoolean stopped=new AtomicBoolean();
    private static JobInfo info(Context c,boolean expedited){
        JobInfo.Builder b=new JobInfo.Builder(JOB_ID,new ComponentName(c,RecordingTranscriptionService.class));
        if(Build.VERSION.SDK_INT>=31&&expedited)b.setExpedited(true);
        return b.build();
    }
    public static void enqueue(Context c,File file){
        try{
            RecordingTranscriptStore store=new RecordingTranscriptStore(c);File audio=store.audio(file.getName());
            store.update(audio,j->{String old=j.optString("state","");if("COMPLETED".equals(old))return;if("FAILED".equals(old)||"CANCELLED".equals(old))j.put("native_attempts",0);j.put("state","QUEUED");j.put("error","");j.put("cancel_requested",false);j.put("updated_at",System.currentTimeMillis());});
            if("COMPLETED".equals(store.load(audio).optString("state")))return;
            JobScheduler scheduler=c.getSystemService(JobScheduler.class);Intent work=new Intent().putExtra("audio",audio.getName());int result;
            try{result=scheduler.enqueue(info(c,true),new JobWorkItem(work));}catch(RuntimeException quota){result=JobScheduler.RESULT_FAILURE;}
            if(result!=JobScheduler.RESULT_SUCCESS)result=scheduler.enqueue(info(c,false),new JobWorkItem(work));
            if(result!=JobScheduler.RESULT_SUCCESS)store.update(audio,j->{j.put("state","FAILED");j.put("error","系统暂未接受后台转写任务，请稍后重试");});
        }catch(Exception e){throw new IllegalStateException("无法安排录音转写："+e.getMessage(),e);}
    }
    public static void cancel(Context c,File audio){
        try{new RecordingTranscriptStore(c).update(audio,j->{if("COMPLETED".equals(j.optString("state")))return;j.put("cancel_requested",true);boolean running=Arrays.asList("DECODING","DIARIZING","TRANSCRIBING","WAITING_INPUT","CANCELLING").contains(j.optString("state"));j.put("state",running?"CANCELLING":"CANCELLED");j.put("error",running?"正在停止，当前本地计算结束后退出；原录音保留":"已停止转写，录音和已完成文字保留");});}
        catch(IOException e){throw new IllegalStateException(e.getMessage(),e);}
    }
    /** Called on a background executor when Home resumes; never queues untouched recordings without user opt-in. */
    static void recover(Context c){
        try{if(c.getSystemService(JobScheduler.class).getPendingJob(JOB_ID)!=null)return;RecordingTranscriptStore s=new RecordingTranscriptStore(c);for(RecordingRepository.Entry entry:new RecordingRepository(c).list()){
            if(entry.interrupted)continue;JSONObject j=s.load(entry.file);String state=j.optString("state","");
            if("CANCELLING".equals(state)&&j.optBoolean("cancel_requested",false)){
                try(RecordingExecutionLock lease=RecordingExecutionLock.tryAcquire(new File(entry.file+".work.lock"))){if(lease!=null)s.update(entry.file,data->{data.put("state","CANCELLED");data.put("error","转写已停止，已完成文字保留");});}continue;
            }
            if(RecordingWorkPolicy.resumable(state))enqueue(c,entry.file);
        }}catch(Exception ignored){/* Explicit per-file retry still reports storage errors. */}
    }
    @Override public boolean onStartJob(JobParameters params){
        final AtomicBoolean stop=new AtomicBoolean();stopped=stop;
        worker.execute(()->{boolean retry=false;try{JobWorkItem item;while(!stop.get()&&(item=params.dequeueWork())!=null){
            String name=item.getIntent().getStringExtra("audio");try{run(name,stop);}catch(Exception failure){if(stop.get())break;try{RecordingTranscriptStore s=new RecordingTranscriptStore(this);File a=s.audio(name);state(s,a,"FAILED",failure.getMessage()==null?failure.getClass().getSimpleName():failure.getMessage());}catch(Exception unavailable){android.util.Log.w("RecordingJob","Unable to persist failure state: "+unavailable.getClass().getSimpleName());}}
            if(stop.get()){retry=true;break;}params.completeWork(item);
        }}finally{if(!stop.get())jobFinished(params,retry);}});return true;
    }
    @Override public boolean onStopJob(JobParameters params){stopped.set(true);return true;}
    @Override public void onDestroy(){stopped.set(true);worker.shutdown();super.onDestroy();}
    private void run(String name,AtomicBoolean stop)throws Exception{
        File audio=new RecordingTranscriptStore(this).audio(name);
        try(RecordingExecutionLock lease=RecordingExecutionLock.acquire(new File(audio+".work.lock"),stop::get)){
            if(!stop.get())runExclusive(name,stop);
        }
    }
    private void runExclusive(String name,AtomicBoolean stop)throws Exception{
        RecordingTranscriptStore store=new RecordingTranscriptStore(this);File audio=store.audio(name);JSONObject job=store.load(audio);
        if("COMPLETED".equals(job.optString("state"))||"CANCELLED".equals(job.optString("state")))return;
        if(job.optBoolean("cancel_requested",false)){store.update(audio,j->{j.put("state","CANCELLED");j.put("error","转写已停止，录音和已完成文字保留");});return;}
        java.util.function.BooleanSupplier cancelled=()->{
            if(stop.get())return true;try{return store.load(audio).optBoolean("cancel_requested",false);}catch(IOException e){return true;}
        };
        File work=new File(getFilesDir(),"recording-work");if(!work.isDirectory()&&!work.mkdirs())throw new IOException("无法创建转写工作目录");
        File pcm=new File(work,name+".pcm");
        try{
            long sourceSize=audio.length(),sourceTime=audio.lastModified();
            if(job.optLong("source_bytes",-1)!=sourceSize||job.optLong("source_modified",-1)!=sourceTime){
                store.update(audio,j->{j.remove("segments");j.remove("speakers");j.put("source_bytes",sourceSize);j.put("source_modified",sourceTime);j.put("completed_segments",0);j.put("total_segments",0);j.remove("pcm_ready");});
            }
            job=store.load(audio);
            if(!job.optBoolean("pcm_ready",false)||!pcm.isFile()){
                state(store,audio,"DECODING","");long samples=RecordingAudioDecoder.decodeToPcm(audio,pcm,cancelled);
                if(samples<=0)throw new IOException("录音没有可解码音频");if(samples>16000L*60*60)throw new IOException("本版单次转写支持60分钟以内录音，原音频保留");
                store.update(audio,j->{j.put("pcm_ready",true);j.put("samples",samples);});
            }
            check(cancelled);job=store.load(audio);
            if(!job.has("segments")){
                if(job.optInt("native_attempts",0)>=2)throw new IOException("说话人分析已连续中断两次，请手动重试；原录音保留");
                store.update(audio,j->j.put("native_attempts",j.optInt("native_attempts",0)+1));
                state(store,audio,"DIARIZING","");File model=SpeakerDiarizationNative.prepareAssets(this);
                String result=SpeakerDiarizationNative.diarize(model.getAbsolutePath(),pcm.getAbsolutePath(),0,0.75f);
                check(cancelled);JSONArray raw=new JSONArray(result),segments=new JSONArray();JSONObject speakers=new JSONObject();
                long duration=pcm.length()/2*1000/16000;
                for(int i=0;i<raw.length();i++){
                    JSONObject entry=raw.getJSONObject(i);long start=Math.max(0,entry.getLong("start_ms")),end=Math.min(duration,entry.getLong("end_ms"));
                    int speaker=entry.optInt("speaker",-1);if(start>=end)continue;if(speaker>=0&&!speakers.has(String.valueOf(speaker)))speakers.put(String.valueOf(speaker),"说话人"+(speakers.length()+1));
                    for(long[] range:RecordingWorkPolicy.split(start,end,duration)){JSONObject segment=new JSONObject();segment.put("start_ms",range[0]);segment.put("end_ms",range[1]);segment.put("speaker",speaker);segment.put("uncertain",entry.optBoolean("uncertain",speaker<0));segment.put("text","");segment.put("status","PENDING");segments.put(segment);}
                }
                if(segments.length()==0)throw new IOException("未检测到可分辨的人声，录音保留");
                store.update(audio,j->{j.put("segments",segments);j.put("speakers",speakers);j.put("total_segments",segments.length());j.put("completed_segments",0);j.put("speaker_method","sherpa-onnx-1.13.8-pyannote-int8-campplus");});
            }
            JSONArray segments=store.load(audio).getJSONArray("segments");
            try(RecordingAsrClient client=new RecordingAsrClient(this)){for(int index=0;index<segments.length();index++){
                check(cancelled);JSONObject segment=store.load(audio).getJSONArray("segments").getJSONObject(index);
                if("COMPLETED".equals(segment.optString("status")))continue;
                state(store,audio,"TRANSCRIBING","");String text=null;int reconnects=0;
                while(text==null){
                    check(cancelled);
                    try{text=client.transcribe(pcm,segment.getLong("start_ms")*16,segment.getLong("end_ms")*16,cancelled);}
                    catch(Exception e){
                        String detail=e.getMessage()==null?"":e.getMessage();
                        if(detail.contains("LIVE_ASR_BUSY")){state(store,audio,"WAITING_INPUT","正在使用语音输入，转写稍后继续");Thread.sleep(1500);continue;}
                        if(reconnects++==0&&(detail.contains("ASR connection")||detail.contains("Local ASR"))){Thread.sleep(250);continue;}
                        throw e;
                    }
                }
                if(text.trim().isEmpty())throw new IOException("片段未识别出文字，已完成部分保留");check(cancelled);final int current=index;final String recognized=text;
                store.update(audio,j->{if(j.optBoolean("cancel_requested",false))throw new InterruptedException("转写已停止");JSONObject s=j.getJSONArray("segments").getJSONObject(current);s.put("text",recognized);s.put("status","COMPLETED");s.remove("error");int count=0;JSONArray a=j.getJSONArray("segments");for(int k=0;k<a.length();k++)if("COMPLETED".equals(a.getJSONObject(k).optString("status")))count++;j.put("completed_segments",count);j.put("state","TRANSCRIBING");j.put("updated_at",System.currentTimeMillis());});
            }}
            check(cancelled);store.update(audio,j->{if(!RecordingWorkPolicy.completed(j.optBoolean("cancel_requested",false),j.optInt("completed_segments"),j.optInt("total_segments")))throw new InterruptedException("转写尚未全部完成或已停止");JSONArray a=j.getJSONArray("segments");for(int i=0;i<a.length();i++)if(!"COMPLETED".equals(a.getJSONObject(i).optString("status")))throw new IOException("存在未完成片段");j.put("state","COMPLETED");j.put("error","");j.put("updated_at",System.currentTimeMillis());});
            // Derived PCM is expendable; original M4A and completed transcript remain durable.
            pcm.delete();
        }catch(LinkageError failure){state(store,audio,"FAILED","本地运行库无法加载："+failure.getMessage());
        }catch(Exception e){
            if(stop.get()){state(store,audio,"QUEUED","系统暂停任务，将从已完成片段继续");}
            else if(cancelled.getAsBoolean()) {store.update(audio,j->{j.put("state","CANCELLED");j.put("error","转写已停止，录音和已完成文字保留");});}
            else state(store,audio,"FAILED",e.getMessage()==null?e.getClass().getSimpleName():e.getMessage());
        }
    }
    private static void check(java.util.function.BooleanSupplier cancelled)throws InterruptedException{if(cancelled.getAsBoolean())throw new InterruptedException("转写已停止");}
    private static void state(RecordingTranscriptStore s,File audio,String state,String error)throws IOException{s.update(audio,j->{if(j.optBoolean("cancel_requested",false))return;j.put("state",state);j.put("error",error);j.put("updated_at",System.currentTimeMillis());});}
}
