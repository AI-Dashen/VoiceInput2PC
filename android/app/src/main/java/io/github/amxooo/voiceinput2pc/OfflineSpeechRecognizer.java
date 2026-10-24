package io.github.amxooo.voiceinput2pc;

import android.content.*;
import android.os.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** PCM-only app-local Binder client. No network, shell, Termux or cloud fallback. */
public final class OfflineSpeechRecognizer implements SafeAudioRecorder.Listener, SpeechInput {
    public interface Listener {
        void onListening(long generation);
        void onRecognizing(long generation);
        void onResult(long generation,String text);
        void onError(long generation,String message);
        default void onPartial(long generation,String text) {}
        default void onSpeechDetected(long generation) {}
        default void onAudioProgress(long generation,long samples) {}
    }
    private final Context context;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final SafeAudioRecorder recorder;
    private final ExecutorService audioTransport=Executors.newSingleThreadExecutor();
    private final StartupPcmBuffer startupPcm=new StartupPcmBuffer(16000*2*120);
    private final AtomicLong queuedPcmBytes=new AtomicLong();
    private final AtomicBoolean transportBacklogSignalled=new AtomicBoolean();
    private volatile IBinder remote;
    private volatile long generation;
    private volatile boolean valid,finishing;
    private boolean bound,closed,requestSent,finishSent,engineReady,captureComplete,recognizingNotified;
    // The fields below are owned only by audioTransport.
    private long transportGeneration;
    private boolean transportReady,transportCaptureComplete,transportFinishSent;
    private Listener listener;
    private Runnable startTimeout,audioStartTimeout,finishDispatchTimeout,resultTimeout;
    // A START that reached the service is acknowledged immediately with
    // "loading". Waiting two minutes for that first acknowledgement made a
    // dropped Binder request look like a frozen microphone.
    private static final long START_ACK_TIMEOUT_MS=10000;
    private static final long MODEL_START_TIMEOUT_MS=120000;
    private static final long AUDIO_START_TIMEOUT_MS=4000;
    private static final long FINISH_DISPATCH_TIMEOUT_MS=15000;
    private static final long MAX_QUEUED_PCM_BYTES=16000L*2*30;
    private static final long RESULT_TIMEOUT_MS=60000;
    /** Keep the large local model warm across short dictation pauses, then really evict it. */
    private static final long IDLE_MODEL_KEEPALIVE_MS=60000;
    private final Runnable idleRelease=()->{
        if(valid)return;
        transact(LocalAsrService.RELEASE_IDLE,generation,null);
        unbind();
    };
    private final Binder callback=new Binder(){
        @Override protected boolean onTransact(int code,Parcel data,Parcel reply,int flags)throws RemoteException{
            if(code!=LocalAsrService.EVENT)return super.onTransact(code,data,reply,flags);
            data.enforceInterface(LocalAsrService.TOKEN);
            long g=data.readLong();String kind=data.readString(),text=data.readString();
            main.post(()->event(g,kind,text));return true;
        }
    };
    private final ServiceConnection connection=new ServiceConnection(){
        @Override public void onServiceConnected(ComponentName name,IBinder service){remote=service;if(valid)sendStart();}
        @Override public void onServiceDisconnected(ComponentName name){remote=null;requestSent=false;if(valid)fail(generation,"本地识别进程已中断，请重新开始");}
        @Override public void onBindingDied(ComponentName name){onServiceDisconnected(name);unbind();}
        @Override public void onNullBinding(ComponentName name){onServiceDisconnected(name);unbind();}
    };
    public OfflineSpeechRecognizer(Context context){this.context=context.getApplicationContext();recorder=new SafeAudioRecorder(this.context,this);}
    public void start(long requested,Listener listener){
        if(MicrophoneLease.isOwnedBy("long-recorder")){listener.onError(requested,"录音机正在使用麦克风，请先结束录音再使用语音输入");return;}
        if(closed){listener.onError(requested,"本地语音入口已关闭");return;}
        cancel();main.removeCallbacks(idleRelease);this.listener=listener;generation=requested;valid=true;finishing=false;requestSent=false;finishSent=false;
        engineReady=false;captureComplete=false;recognizingNotified=false;
        enqueueTransport(()->resetTransport(requested));
        armStartTimeout(requested,START_ACK_TIMEOUT_MS,"本地识别服务未响应，已恢复，可重新说");
        if(remote!=null)sendStart();
        else if(!bound){bound=context.bindService(new Intent(context,LocalAsrService.class),connection,Context.BIND_AUTO_CREATE);if(!bound)fail(requested,"无法启动本地识别服务");}
    }
    private void sendStart(){
        if(requestSent||!valid)return;
        requestSent=true;
        if(!transact(LocalAsrService.START,generation,null)){
            requestSent=false;
            main.post(()->fail(generation,"本地识别连接未接受启动请求"));
        } else startRecorderWhenIdle(generation,0);
    }
    private boolean transact(int command,long g,byte[] pcm){
        IBinder target=remote;if(target==null)return false;Parcel p=Parcel.obtain();Parcel reply=command==LocalAsrService.FEED?Parcel.obtain():null;
        try{p.writeInterfaceToken(LocalAsrService.TOKEN);p.writeLong(g);if(command==LocalAsrService.START)p.writeStrongBinder(callback);if(command==LocalAsrService.FEED)p.writeByteArray(pcm);boolean accepted=target.transact(command,p,reply,command==LocalAsrService.FEED?0:IBinder.FLAG_ONEWAY);if(reply!=null)reply.readException();return accepted;}
        catch(RemoteException|RuntimeException e){main.post(()->fail(g,"本地识别连接中断"));return false;}
        finally{p.recycle();if(reply!=null)reply.recycle();}
    }
    public void finish(){if(!valid||finishing)return;finishing=true;if(recorder.isRunning())recorder.stop();else finishCaptured(generation);}
    private void finishCaptured(long g){
        if(!valid||g!=generation||captureComplete)return;
        captureComplete=true;clearAudioStartTimeout();
        if(engineReady)armFinishDispatchTimeout(g);
        if(!recognizingNotified){recognizingNotified=true;Listener l=listener;if(l!=null)l.onRecognizing(g);}
        // This task is enqueued only after the recorder's final onPcm call.
        // The transport thread therefore sends FINISH after every accepted PCM block.
        enqueueTransport(()->requestTransportFinish(g));
    }
    public void cancel(){
        long g=generation;valid=false;finishing=false;finishSent=false;engineReady=false;captureComplete=false;recognizingNotified=false;
        clearTimeouts();recorder.stop();enqueueTransport(()->cancelTransport(g));
        if(requestSent)transact(LocalAsrService.CANCEL,g,null);requestSent=false;
        main.removeCallbacks(idleRelease);main.postDelayed(idleRelease,IDLE_MODEL_KEEPALIVE_MS);
    }
    public void releaseIdleNow(){if(valid)return;main.removeCallbacks(idleRelease);idleRelease.run();}
    public void close(){cancel();closed=true;main.removeCallbacks(idleRelease);idleRelease.run();listener=null;audioTransport.shutdown();}
    private void unbind(){if(bound){try{context.unbindService(connection);}catch(IllegalArgumentException ignored){}bound=false;}remote=null;requestSent=false;}
    private void event(long g,String kind,String text){
        if(!valid||g!=generation||listener==null)return;
        if("loading".equals(kind)){
            armStartTimeout(g,MODEL_START_TIMEOUT_MS,"本地模型启动超时，已恢复，可重新说");
        }
        else if("listening".equals(kind)){
            clearStartTimeout();engineReady=true;if(captureComplete)armFinishDispatchTimeout(g);
            enqueueTransport(()->markTransportReady(g));
        }
        else if("recognizing".equals(kind)){if(!recognizingNotified){recognizingNotified=true;listener.onRecognizing(g);}}
        else if("partial".equals(kind))listener.onPartial(g,text==null?"":text);
        else if("final".equals(kind)){
            clearTimeouts();valid=false;finishing=false;finishSent=false;engineReady=false;captureComplete=false;recognizingNotified=false;
            // The service has completed and closed this generation. Keeping
            // requestSent=true made the next tap send a stale CANCEL directly
            // before its new START, which could cancel the replacement session.
            requestSent=false;recorder.stop();enqueueTransport(()->cancelTransport(g));
            listener.onResult(g,text==null?"":text);main.postDelayed(idleRelease,IDLE_MODEL_KEEPALIVE_MS);
        }
        else if("error".equals(kind))fail(g,text==null?"本地识别失败":text);
    }
    private void resetTransport(long g){
        transportGeneration=g;transportReady=false;transportCaptureComplete=false;transportFinishSent=false;
        transportBacklogSignalled.set(false);startupPcm.clear();
    }
    private void cancelTransport(long g){
        if(transportGeneration!=g)return;
        transportReady=false;transportCaptureComplete=false;transportFinishSent=true;startupPcm.clear();
    }
    private void transportPcm(long g,byte[] pcm){
        if(!valid||g!=generation||g!=transportGeneration||transportCaptureComplete||transportFinishSent)return;
        if(!transportReady){
            if(!startupPcm.append(pcm)){
                transportFinishSent=true;
                main.post(()->fail(g,"本地模型启动过慢，已安全停止，请重新说"));
            }
            return;
        }
        if(!transact(LocalAsrService.FEED,g,pcm)){
            transportFinishSent=true;
            main.post(()->fail(g,"本地语音数据传输中断，请重新说"));
        }
    }
    private void markTransportReady(long g){
        if(!valid||g!=generation||g!=transportGeneration||transportFinishSent)return;
        transportReady=true;
        if(!flushStartupPcm(g))return;
        if(transportCaptureComplete)sendTransportFinish(g);
    }
    private boolean flushStartupPcm(long g){
        byte[] data=startupPcm.drain();
        for(int offset=0;offset<data.length;offset+=16384){
            if(!valid||g!=generation||g!=transportGeneration||transportFinishSent)return false;
            int length=Math.min(16384,data.length-offset);byte[] chunk=new byte[length];
            System.arraycopy(data,offset,chunk,0,length);
            if(!transact(LocalAsrService.FEED,g,chunk)){
                transportFinishSent=true;main.post(()->fail(g,"本地语音数据传输中断，请重新说"));return false;
            }
        }
        return true;
    }
    private void requestTransportFinish(long g){
        if(!valid||g!=generation||g!=transportGeneration||transportFinishSent)return;
        transportCaptureComplete=true;
        if(transportReady)sendTransportFinish(g);
    }
    private void sendTransportFinish(long g){
        if(transportFinishSent||!transportReady||!transportCaptureComplete)return;
        transportFinishSent=true;
        boolean accepted=transact(LocalAsrService.FINISH,g,null);
        main.post(()->{
            if(!valid||g!=generation)return;
            if(!accepted){fail(g,"本地识别连接未接受结束请求");return;}
            finishSent=true;clearStartTimeout();clearFinishDispatchTimeout();
            resultTimeout=()->{if(valid&&generation==g&&finishSent)hardReset(g,"本地识别超时，语音子进程已恢复，请重新说");};
            main.postDelayed(resultTimeout,RESULT_TIMEOUT_MS);
        });
    }
    private boolean enqueueTransport(Runnable task){
        if(closed)return false;
        try{audioTransport.execute(task);return true;}catch(RejectedExecutionException ignored){return false;}
    }
    private void startRecorderWhenIdle(long g,int attempt){
        if(!valid||g!=generation||finishing||captureComplete)return;
        if(!recorder.isIdle()){
            recorder.stop();
            if(attempt<40){main.postDelayed(()->startRecorderWhenIdle(g,attempt+1),50);return;}
            fail(g,"上一轮麦克风释放超时，已恢复，请重新说");return;
        }
        if(!valid||g!=generation||finishing||captureComplete)return;
        if(recorder.start(g)){
            clearAudioStartTimeout();
            audioStartTimeout=()->{if(valid&&generation==g&&!finishing)fail(g,"麦克风没有返回音频，已恢复，请重新说");};
            main.postDelayed(audioStartTimeout,AUDIO_START_TIMEOUT_MS);return;
        }
        // isIdle() and start() are deliberately separate because AudioRecord
        // releases asynchronously. A transient refusal must not leave the UI
        // in LISTENING forever.
        if(attempt<40){main.postDelayed(()->startRecorderWhenIdle(g,attempt+1),50);return;}
        fail(g,"麦克风启动未完成，已恢复，请重新说");
    }
    private void armStartTimeout(long g,long delay,String message){
        clearStartTimeout();
        startTimeout=()->{if(valid&&generation==g&&!engineReady)fail(g,message);};
        main.postDelayed(startTimeout,delay);
    }
    private void clearStartTimeout(){if(startTimeout!=null){main.removeCallbacks(startTimeout);startTimeout=null;}}
    private void clearAudioStartTimeout(){if(audioStartTimeout!=null){main.removeCallbacks(audioStartTimeout);audioStartTimeout=null;}}
    private void armFinishDispatchTimeout(long g){
        clearFinishDispatchTimeout();
        finishDispatchTimeout=()->{if(valid&&generation==g&&captureComplete&&!finishSent)hardReset(g,"本地语音传输超时，识别进程已恢复，请重新说");};
        main.postDelayed(finishDispatchTimeout,FINISH_DISPATCH_TIMEOUT_MS);
    }
    private void clearFinishDispatchTimeout(){if(finishDispatchTimeout!=null){main.removeCallbacks(finishDispatchTimeout);finishDispatchTimeout=null;}}
    private void clearTimeouts(){clearStartTimeout();clearAudioStartTimeout();clearFinishDispatchTimeout();if(resultTimeout!=null){main.removeCallbacks(resultTimeout);resultTimeout=null;}}
    private void fail(long g,String message){if(g!=generation||!valid)return;Listener l=listener;cancel();if(l!=null)l.onError(g,message);}
    private void hardReset(long g,String message){
        if(g!=generation||!valid)return;
        Listener l=listener;
        valid=false;finishing=false;finishSent=false;engineReady=false;captureComplete=false;recognizingNotified=false;
        clearTimeouts();recorder.stop();enqueueTransport(()->cancelTransport(g));
        // RESET_PROCESS is handled immediately on a Binder thread. Unlike a
        // queued CANCEL it can recover even when native inference has wedged
        // the service worker. The service is isolated in :local_asr.
        transact(LocalAsrService.RESET_PROCESS,g,null);
        requestSent=false;main.removeCallbacks(idleRelease);unbind();
        if(l!=null)l.onError(g,message);
    }
    @Override public void onStarted(long g){main.post(()->{if(valid&&g==generation&&!finishing){clearAudioStartTimeout();Listener l=listener;if(l!=null)l.onListening(g);}});}
    @Override public void onProgress(long g,long samples){
        main.post(()->{
            if(!valid || g!=generation || finishing)return;
            if(samples>=16000L*SpeechEndpointDetector.MAX_SESSION_MS/1000)finish();
            else {Listener l=listener;if(l!=null)l.onAudioProgress(g,samples);}
        });
    }
    @Override public void onPcm(long g,byte[] pcm){
        if(!valid||g!=generation||pcm==null||pcm.length==0)return;
        long queued=queuedPcmBytes.addAndGet(pcm.length);
        if(queued>MAX_QUEUED_PCM_BYTES){
            queuedPcmBytes.addAndGet(-pcm.length);
            if(transportBacklogSignalled.compareAndSet(false,true))
                main.post(()->hardReset(g,"本地语音传输积压，识别进程已恢复，请重新说"));
            return;
        }
        if(!enqueueTransport(()->{
            try{transportPcm(g,pcm);}
            finally{queuedPcmBytes.updateAndGet(value->Math.max(0,value-pcm.length));}
        }))queuedPcmBytes.updateAndGet(value->Math.max(0,value-pcm.length));
    }
    @Override public void onSpeechDetected(long g){main.post(()->{if(valid&&g==generation&&listener!=null)listener.onSpeechDetected(g);});}
    @Override public void onEndpoint(long g){main.post(()->{if(valid&&g==generation)finish();});}
    @Override public void onStopped(long g,long samples){main.post(()->{if(valid&&g==generation&&finishing)finishCaptured(g);});}
    @Override public void onError(long g,String message){main.post(()->fail(g,message));}
}
