package io.github.amxooo.voiceinput2pc;

import android.content.*;
import android.os.*;

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
    private volatile IBinder remote;
    private volatile long generation;
    private volatile boolean valid,finishing;
    private boolean bound,closed,requestSent,finishSent;
    private Listener listener;
    private Runnable startTimeout,resultTimeout;
    // A START that reached the service is acknowledged immediately with
    // "loading". Waiting two minutes for that first acknowledgement made a
    // dropped Binder request look like a frozen microphone.
    private static final long START_ACK_TIMEOUT_MS=10000;
    private static final long MODEL_START_TIMEOUT_MS=120000;
    private static final long RESULT_TIMEOUT_MS=60000;
    private final Runnable idleRelease=()->{if(!valid)unbind();};
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
        armStartTimeout(requested,START_ACK_TIMEOUT_MS,"本地识别服务未响应，已恢复，可重新说");
        if(remote!=null)sendStart();
        else if(!bound){bound=context.bindService(new Intent(context,LocalAsrService.class),connection,Context.BIND_AUTO_CREATE | Context.BIND_IMPORTANT);if(!bound)fail(requested,"无法启动本地识别服务");}
    }
    private void sendStart(){
        if(requestSent||!valid)return;
        requestSent=true;
        if(!transact(LocalAsrService.START,generation,null)){
            requestSent=false;
            main.post(()->fail(generation,"本地识别连接未接受启动请求"));
        }
    }
    private boolean transact(int command,long g,byte[] pcm){
        IBinder target=remote;if(target==null)return false;Parcel p=Parcel.obtain();Parcel reply=command==LocalAsrService.FEED?Parcel.obtain():null;
        try{p.writeInterfaceToken(LocalAsrService.TOKEN);p.writeLong(g);if(command==LocalAsrService.START)p.writeStrongBinder(callback);if(command==LocalAsrService.FEED)p.writeByteArray(pcm);boolean accepted=target.transact(command,p,reply,command==LocalAsrService.FEED?0:IBinder.FLAG_ONEWAY);if(reply!=null)reply.readException();return accepted;}
        catch(RemoteException|RuntimeException e){main.post(()->fail(g,"本地识别连接中断"));return false;}
        finally{p.recycle();if(reply!=null)reply.recycle();}
    }
    public void finish(){if(!valid||finishing)return;finishing=true;if(recorder.isRunning())recorder.stop();else finishCaptured(generation);}
    private void finishCaptured(long g){
        if(!valid||g!=generation||finishSent)return;
        finishSent=true;clearStartTimeout();Listener l=listener;if(l!=null)l.onRecognizing(g);
        resultTimeout=()->{if(valid&&generation==g&&finishSent)hardReset(g,"本地识别超时，语音子进程已恢复，请重新说");};
        main.postDelayed(resultTimeout,RESULT_TIMEOUT_MS);
        transact(LocalAsrService.FINISH,g,null);
    }
    public void cancel(){long g=generation;valid=false;finishing=false;finishSent=false;clearTimeouts();recorder.stop();if(requestSent)transact(LocalAsrService.CANCEL,g,null);requestSent=false;main.removeCallbacks(idleRelease);main.postDelayed(idleRelease,60000);}
    public void close(){closed=true;cancel();main.removeCallbacks(idleRelease);unbind();listener=null;}
    private void unbind(){if(bound){try{context.unbindService(connection);}catch(IllegalArgumentException ignored){}bound=false;}remote=null;requestSent=false;}
    private void event(long g,String kind,String text){
        if(!valid||g!=generation||listener==null)return;
        if("loading".equals(kind)){
            armStartTimeout(g,MODEL_START_TIMEOUT_MS,"本地模型启动超时，已恢复，可重新说");
        }
        else if("listening".equals(kind)){clearStartTimeout();if(finishing){finishCaptured(g);return;}startRecorderWhenIdle(g,0);}
        else if("recognizing".equals(kind))listener.onRecognizing(g);
        else if("partial".equals(kind))listener.onPartial(g,text==null?"":text);
        else if("final".equals(kind)){
            clearTimeouts();valid=false;finishing=false;finishSent=false;
            // The service has completed and closed this generation. Keeping
            // requestSent=true made the next tap send a stale CANCEL directly
            // before its new START, which could cancel the replacement session.
            requestSent=false;
            recorder.stop();listener.onResult(g,text==null?"":text);main.postDelayed(idleRelease,60000);
        }
        else if("error".equals(kind))fail(g,text==null?"本地识别失败":text);
    }
    private void startRecorderWhenIdle(long g,int attempt){
        if(!valid||g!=generation||finishing)return;
        if(!recorder.isIdle()){
            recorder.stop();
            if(attempt<40){main.postDelayed(()->startRecorderWhenIdle(g,attempt+1),50);return;}
            fail(g,"上一轮麦克风释放超时，已恢复，请重新说");return;
        }
        if(recorder.start(g)){Listener l=listener;if(l!=null)l.onListening(g);return;}
        // isIdle() and start() are deliberately separate because AudioRecord
        // releases asynchronously. A transient refusal must not leave the UI
        // in LISTENING forever.
        if(attempt<40){main.postDelayed(()->startRecorderWhenIdle(g,attempt+1),50);return;}
        fail(g,"麦克风启动未完成，已恢复，请重新说");
    }
    private void armStartTimeout(long g,long delay,String message){
        clearStartTimeout();
        startTimeout=()->{if(valid&&generation==g&&!recorder.isRunning())fail(g,message);};
        main.postDelayed(startTimeout,delay);
    }
    private void clearStartTimeout(){if(startTimeout!=null){main.removeCallbacks(startTimeout);startTimeout=null;}}
    private void clearTimeouts(){clearStartTimeout();if(resultTimeout!=null){main.removeCallbacks(resultTimeout);resultTimeout=null;}}
    private void fail(long g,String message){if(g!=generation||!valid)return;Listener l=listener;cancel();if(l!=null)l.onError(g,message);}
    private void hardReset(long g,String message){
        if(g!=generation||!valid)return;
        Listener l=listener;
        valid=false;finishing=false;finishSent=false;clearTimeouts();recorder.stop();
        // RESET_PROCESS is handled immediately on a Binder thread. Unlike a
        // queued CANCEL it can recover even when native inference has wedged
        // the service worker. The service is isolated in :local_asr.
        transact(LocalAsrService.RESET_PROCESS,g,null);
        requestSent=false;main.removeCallbacks(idleRelease);unbind();
        if(l!=null)l.onError(g,message);
    }
    @Override public void onStarted(long g){}
    @Override public void onProgress(long g,long samples){
        main.post(()->{
            if(!valid || g!=generation || finishing)return;
            if(samples>=16000L*SpeechEndpointDetector.MAX_SESSION_MS/1000)finish();
            else {Listener l=listener;if(l!=null)l.onAudioProgress(g,samples);}
        });
    }
    @Override public void onPcm(long g,byte[] pcm){if(valid&&g==generation)transact(LocalAsrService.FEED,g,pcm);}
    @Override public void onSpeechDetected(long g){main.post(()->{if(valid&&g==generation&&listener!=null)listener.onSpeechDetected(g);});}
    @Override public void onEndpoint(long g){main.post(()->{if(valid&&g==generation)finish();});}
    @Override public void onStopped(long g,long samples){main.post(()->{if(valid&&g==generation&&finishing)finishCaptured(g);});}
    @Override public void onError(long g,String message){main.post(()->fail(g,message));}
}
