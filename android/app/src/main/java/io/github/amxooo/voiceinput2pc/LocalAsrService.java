package io.github.amxooo.voiceinput2pc;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.*;

/** Own-UID bound process: native ASR never executes in the networked relay/IME process. */
public final class LocalAsrService extends Service implements LocalAsrNative.Callback {
    static final String TOKEN="io.github.amxooo.voiceinput2pc.LOCAL_ASR";
    static final int START=1, FEED=2, FINISH=3, CANCEL=4, EVENT=5;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final AtomicBoolean cancelled=new AtomicBoolean();
    private volatile long activeGeneration, handle, requestedGeneration;
    private final Object handleLock=new Object();
    private volatile IBinder callback;
    private boolean prepared;
    private volatile boolean finalized;
    private String modelPath;
    private final Binder binder=new Binder(){
        @Override protected boolean onTransact(int code,Parcel data,Parcel reply,int flags)throws RemoteException{
            if(code==INTERFACE_TRANSACTION){if(reply!=null)reply.writeString(TOKEN);return true;}
            data.enforceInterface(TOKEN);
            if(Binder.getCallingUid()!=getApplicationInfo().uid)throw new SecurityException("App-only local speech service");
            long generation=data.readLong();
            if(code==START){requestedGeneration=generation;IBinder target=data.readStrongBinder();worker.execute(()->start(generation,target));}
            else if(code==FEED){byte[] pcm=data.createByteArray();if(pcm==null||pcm.length>16384||pcm.length%2!=0)throw new IllegalArgumentException("Invalid PCM chunk");feed(generation,pcm);}
            else if(code==FINISH)worker.execute(()->finish(generation));
            else if(code==CANCEL){if(generation==requestedGeneration)requestedGeneration=0;if(generation==activeGeneration){cancelled.set(true);synchronized(handleLock){long current=handle;if(current!=0)LocalAsrNative.cancel(current);}}worker.execute(()->close(generation));}
            else return super.onTransact(code,data,reply,flags);
            if(reply!=null)reply.writeNoException();return true;
        }
    };
    @Override public IBinder onBind(Intent intent){return binder;}
    private void start(long generation,IBinder target){
        if(requestedGeneration!=generation)return;close(activeGeneration);activeGeneration=generation;callback=target;cancelled.set(false);finalized=false;
        try{
            emit(generation,"loading","正在加载本地语音模型");
            if(!prepared){modelPath=prepareAsset().getAbsolutePath();int rc=LocalAsrNative.prepare(getApplicationInfo().nativeLibraryDir,modelPath);if(rc!=0)throw new IOException("本地模型加载失败（"+rc+"）："+LocalAsrNative.lastError());prepared=true;}
            if(cancelled.get()||requestedGeneration!=generation)return;
            handle=LocalAsrNative.begin(generation,modelPath,this);if(handle==0)throw new IOException("无法创建本地语音会话");
            emit(generation,"listening","");
        }catch(Throwable e){emit(generation,"error",e instanceof IOException?e.getMessage():"本地引擎不可用："+e.getClass().getSimpleName());close(generation);}
    }
    private void feed(long generation,byte[] pcm){if(generation!=activeGeneration||cancelled.get()||handle==0)return;int rc=LocalAsrNative.feed(handle,pcm);if(rc!=0){emit(generation,"error","本地语音处理失败（"+rc+"）");close(generation);}}
    private void finish(long generation){
        if(generation!=activeGeneration||cancelled.get()||handle==0)return;
        emit(generation,"recognizing","");int rc=LocalAsrNative.finish(handle);
        if(!cancelled.get()&&(!finalized||rc!=0))emit(generation,"error",rc==-20?"没有收到有效语音结果，请重新说话":"语音结束失败（"+rc+"）");
        close(generation);
    }
    private void close(long generation){if(generation!=activeGeneration)return;synchronized(handleLock){long current=handle;handle=0;if(current!=0)LocalAsrNative.close(current);}}
    public void onNativeEvent(long generation,String kind,byte[] bytes){
        if(generation!=activeGeneration||cancelled.get())return;
        if("error".equals(kind)){emit(generation,"error",new String(bytes,StandardCharsets.UTF_8));return;}
        if(!"result".equals(kind))return;
        try{
            JSONObject value=new JSONObject(new String(bytes,StandardCharsets.UTF_8));JSONArray results=value.optJSONArray("results");if(results==null||results.length()==0)return;
            JSONObject result=results.getJSONObject(0);String text=result.optString("text","");
            boolean isFinal=!result.optBoolean("is_interim",true)&&result.optBoolean("is_vad_finished",false)&&result.optInt("is_offline_result",0)==1;
            if(isFinal){if(finalized)return;finalized=true;emit(generation,"final",text);}else emit(generation,"partial",text);
        }catch(JSONException e){emit(generation,"error","本地语音结果格式错误");}
    }
    private void emit(long generation,String kind,String text){IBinder target=callback;if(target==null||generation!=activeGeneration||cancelled.get())return;Parcel p=Parcel.obtain();try{p.writeInterfaceToken(TOKEN);p.writeLong(generation);p.writeString(kind);p.writeString(text);target.transact(EVENT,p,null,IBinder.FLAG_ONEWAY);}catch(RemoteException ignored){cancelled.set(true);}finally{p.recycle();}}
    private File prepareAsset()throws Exception{
        File directory=new File(getFilesDir(),"local-asr");if(!directory.exists()&&!directory.mkdirs())throw new IOException("无法创建本地模型目录");
        File dst=new File(directory,"model.flute");if(dst.isFile()&&dst.length()==185377526L)return dst;
        File tmp=new File(directory,"model.part");MessageDigest digest=MessageDigest.getInstance("SHA-256");long total=0;
        try(InputStream in=getAssets().open("local-asr/model.flute");FileOutputStream out=new FileOutputStream(tmp)){byte[] b=new byte[262144];int n;while((n=in.read(b))!=-1){out.write(b,0,n);digest.update(b,0,n);total+=n;}out.getFD().sync();}
        StringBuilder hash=new StringBuilder();for(byte b:digest.digest())hash.append(String.format(java.util.Locale.ROOT,"%02x",b&255));
        if(total!=185377526L||!hash.toString().equals("3a46e175f322822f368d970580271888383cc571996776754a17af669b48867d"))throw new IOException("模型资源校验失败");
        if(!tmp.renameTo(dst))throw new IOException("无法保存本地模型");return dst;
    }
    @Override public void onDestroy(){cancelled.set(true);synchronized(handleLock){long current=handle;if(current!=0)LocalAsrNative.cancel(current);}worker.execute(()->{close(activeGeneration);if(prepared)LocalAsrNative.release();});worker.shutdown();super.onDestroy();}
}
