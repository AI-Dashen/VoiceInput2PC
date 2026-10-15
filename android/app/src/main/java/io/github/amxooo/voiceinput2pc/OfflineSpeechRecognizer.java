package io.github.amxooo.voiceinput2pc;

import android.content.*;
import android.os.*;

/** PCM-only app-local Binder client. No network, shell, Termux or cloud fallback. */
public final class OfflineSpeechRecognizer implements SafeAudioRecorder.Listener {
    public interface Listener {
        void onListening(long generation);
        void onRecognizing(long generation);
        void onResult(long generation,String text);
        void onError(long generation,String message);
        default void onPartial(long generation,String text) {}
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
    private static final long START_TIMEOUT_MS=15000;
    private static final long RESULT_TIMEOUT_MS=15000;
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
        if(closed){listener.onError(requested,"本地语音入口已关闭");return;}
        cancel();main.removeCallbacks(idleRelease);this.listener=listener;generation=requested;valid=true;finishing=false;requestSent=false;finishSent=false;
        startTimeout=()->{if(valid&&generation==requested&&!recorder.isRunning())fail(requested,"本地识别启动超时，已恢复，可重新说");};
        main.postDelayed(startTimeout,START_TIMEOUT_MS);
        if(remote!=null)sendStart();
        else if(!bound){bound=context.bindService(new Intent(context,LocalAsrService.class),connection,Context.BIND_AUTO_CREATE | Context.BIND_IMPORTANT);if(!bound)fail(requested,"无法启动本地识别服务");}
    }
    private void sendStart(){if(requestSent||!valid)return;requestSent=true;transact(LocalAsrService.START,generation,null);}
    private void transact(int command,long g,byte[] pcm){
        IBinder target=remote;if(target==null)return;Parcel p=Parcel.obtain();Parcel reply=command==LocalAsrService.FEED?Parcel.obtain():null;
        try{p.writeInterfaceToken(LocalAsrService.TOKEN);p.writeLong(g);if(command==LocalAsrService.START)p.writeStrongBinder(callback);if(command==LocalAsrService.FEED)p.writeByteArray(pcm);target.transact(command,p,reply,command==LocalAsrService.FEED?0:IBinder.FLAG_ONEWAY);if(reply!=null)reply.readException();}
        catch(RemoteException|RuntimeException e){main.post(()->fail(g,"本地识别连接中断"));}
        finally{p.recycle();if(reply!=null)reply.recycle();}
    }
    public void finish(){if(!valid||finishing)return;finishing=true;if(recorder.isRunning())recorder.stop();else finishCaptured(generation);}
    private void finishCaptured(long g){
        if(!valid||g!=generation||finishSent)return;
        finishSent=true;clearStartTimeout();Listener l=listener;if(l!=null)l.onRecognizing(g);
        resultTimeout=()->{if(valid&&generation==g&&finishSent)fail(g,"本地识别超时，已恢复，可重新说");};
        main.postDelayed(resultTimeout,RESULT_TIMEOUT_MS);
        transact(LocalAsrService.FINISH,g,null);
    }
    public void cancel(){long g=generation;valid=false;finishing=false;finishSent=false;clearTimeouts();recorder.stop();if(requestSent)transact(LocalAsrService.CANCEL,g,null);requestSent=false;main.removeCallbacks(idleRelease);main.postDelayed(idleRelease,60000);}
    public void close(){closed=true;cancel();main.removeCallbacks(idleRelease);unbind();listener=null;}
    private void unbind(){if(bound){try{context.unbindService(connection);}catch(IllegalArgumentException ignored){}bound=false;}remote=null;requestSent=false;}
    private void event(long g,String kind,String text){
        if(!valid||g!=generation||listener==null)return;
        if("listening".equals(kind)){clearStartTimeout();if(finishing){finishCaptured(g);return;}if(!recorder.start(g))return;listener.onListening(g);}
        else if("recognizing".equals(kind))listener.onRecognizing(g);
        else if("partial".equals(kind))listener.onPartial(g,text==null?"":text);
        else if("final".equals(kind)){clearTimeouts();valid=false;finishing=false;finishSent=false;recorder.stop();listener.onResult(g,text==null?"":text);main.postDelayed(idleRelease,60000);}
        else if("error".equals(kind))fail(g,text==null?"本地识别失败":text);
    }
    private void clearStartTimeout(){if(startTimeout!=null){main.removeCallbacks(startTimeout);startTimeout=null;}}
    private void clearTimeouts(){clearStartTimeout();if(resultTimeout!=null){main.removeCallbacks(resultTimeout);resultTimeout=null;}}
    private void fail(long g,String message){if(g!=generation||!valid)return;Listener l=listener;cancel();if(l!=null)l.onError(g,message);}
    @Override public void onStarted(long g){}
    @Override public void onProgress(long g,long samples){
        if(samples>=16000L*120)main.post(this::finish);
        else main.post(()->{Listener l=listener;if(valid&&g==generation&&l!=null)l.onAudioProgress(g,samples);});
    }
    @Override public void onPcm(long g,byte[] pcm){if(valid&&g==generation)transact(LocalAsrService.FEED,g,pcm);}
    @Override public void onEndpoint(long g){main.post(()->{if(valid&&g==generation)finish();});}
    @Override public void onStopped(long g,long samples){main.post(()->{if(valid&&g==generation&&finishing)finishCaptured(g);});}
    @Override public void onError(long g,String message){main.post(()->fail(g,message));}
}
