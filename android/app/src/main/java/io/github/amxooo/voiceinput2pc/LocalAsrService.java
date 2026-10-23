package io.github.amxooo.voiceinput2pc;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Own-UID bound process. The APK-owned C++ engine has no shell, network or Termux dependency. */
public final class LocalAsrService extends Service {
    static final String TOKEN="io.github.amxooo.voiceinput2pc.LOCAL_ASR";
    static final int START=1, FEED=2, FINISH=3, CANCEL=4, EVENT=5, RESET_PROCESS=6, FILE_TRANSCRIBE=7;
    private static final int MAX_PCM_BYTES=16000*2*180;
    private static final String[] ASSET_NAMES={"decoder-runtime.bin","encoder.mnn.weight","encoder_stream.mnn","logit.mnn","logit.mnn.weight","vocab.txt"};
    private static final long[] ASSET_BYTES={13865684L,119098728L,248008L,1752L,25088022L,935789L};
    private static final String[] ASSET_SHA256={
        "5f4f60a5a4ca3cc39fcc30568a23ed1ae1a6ad8bb6851866f96f49bd943c7f23",
        "2077d45a281b404754151e7f0c0f2a1344dd78efbfa220119e7d960a8e898912",
        "94e09dabbb0493a5ce3dd3e68b3dc253c7d46e5f914d1f7013e86342a74c67c2",
        "4cc50520066190bab4276db5ee5e31ebe3babad6ec7c084f946ecf51ccc20f8c",
        "5ff111e9d03bcc1836d4cfa5caed7c114d29e2a6909d6aba704d9ae1b5c0ec4e",
        "d90571b868b4285bf22eafeac862f8b5e8cba7639c06192598e8c4b250d00027"};
    private static final String ASSET_SET_ID="independent-asr-v1-94e09dab-5f4f60a5";
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    // These fields are owned by worker. Binder threads only validate and enqueue
    // commands, so an old generation cannot change a newer session's state.
    private boolean cancelled;
    private final Object pcmLock=new Object();
    private final ByteArrayOutputStream pcm=new ByteArrayOutputStream(16000*2*15);
    private volatile long activeGeneration,requestedGeneration;
    // File requests never borrow the live microphone generation/callback.
    private long batchGeneration;
    private volatile long pendingInteractiveGeneration;
    private volatile IBinder callback;
    private boolean prepared;
    private final Binder binder=new Binder(){
        @Override protected boolean onTransact(int code,Parcel data,Parcel reply,int flags)throws RemoteException{
            if(code==INTERFACE_TRANSACTION){if(reply!=null)reply.writeString(TOKEN);return true;}
            data.enforceInterface(TOKEN);
            if(Binder.getCallingUid()!=getApplicationInfo().uid)throw new SecurityException("App-only local speech service");
            long generation=data.readLong();
            if(code==START){IBinder target=data.readStrongBinder();pendingInteractiveGeneration=generation;worker.execute(()->requestStart(generation,target));}
            else if(code==FEED){byte[] chunk=data.createByteArray();if(chunk==null||chunk.length>16384||(chunk.length&1)!=0)throw new IllegalArgumentException("Invalid PCM chunk");worker.execute(()->feed(generation,chunk));}
            else if(code==FINISH)worker.execute(()->finish(generation));
            else if(code==CANCEL){if(pendingInteractiveGeneration==generation)pendingInteractiveGeneration=0;worker.execute(()->cancel(generation));}
            else if(code==FILE_TRANSCRIBE){
                IBinder target=data.readStrongBinder();String path=data.readString();long start=data.readLong(),end=data.readLong();
                if(target==null||path==null||start<0||end<=start||end-start>16000L*30)throw new IllegalArgumentException("Invalid recording segment");
                worker.execute(()->transcribeFile(generation,target,path,start,end));
            }
            else if(code==RESET_PROCESS){
                // This service always runs in the dedicated :local_asr process.
                // RESET_PROCESS is the last-resort escape hatch when native
                // inference has stopped returning and the single worker can no
                // longer consume a queued CANCEL. It never kills the IME/UI.
                android.os.Process.killProcess(android.os.Process.myPid());
            }
            else return super.onTransact(code,data,reply,flags);
            if(reply!=null)reply.writeNoException();return true;
        }
    };
    @Override public IBinder onBind(Intent intent){return binder;}
    private void requestStart(long generation,IBinder target){if(pendingInteractiveGeneration==generation)pendingInteractiveGeneration=0;requestedGeneration=generation;start(generation,target);}
    private void start(long generation,IBinder target){
        close(activeGeneration);if(requestedGeneration!=generation)return;activeGeneration=generation;callback=target;cancelled=false;synchronized(pcmLock){pcm.reset();}
        try{
            emit(generation,"loading","正在加载 APK 内置本地语音模型");
            if(!prepared){File modelDir=prepareAssets();int rc=IndependentAsrNative.prepare(modelDir.getAbsolutePath());if(rc!=0)throw new IOException("原生本地模型加载失败（"+rc+"）："+IndependentAsrNative.lastError());prepared=true;}
            if(cancelled||requestedGeneration!=generation)return;
            int streamRc=beginStreamRecovering();
            if(streamRc!=0)throw new IOException("本地流式识别启动失败（"+streamRc+"）："+IndependentAsrNative.lastError());
            emit(generation,"listening","");
        }catch(Throwable e){
            String detail=e instanceof IOException?e.getMessage():"本地引擎不可用："+e.getClass().getSimpleName();
            emit(generation,"error",detail);close(generation);invalidateEngine();
        }
    }
    /** Recover stale native stream state once without discarding resident models. */
    private int beginStreamRecovering(){
        int rc=IndependentAsrNative.beginStream();
        if(rc==0)return 0;
        IndependentAsrNative.cancelStream();
        return IndependentAsrNative.beginStream();
    }
    private void feed(long generation,byte[] chunk){
        if(generation!=activeGeneration||cancelled)return;
        synchronized(pcmLock){if(pcm.size()+chunk.length>MAX_PCM_BYTES){emit(generation,"error","本地语音超过三分钟限制");cancelled=true;close(generation);return;}pcm.write(chunk,0,chunk.length);}
        int rc=IndependentAsrNative.acceptStream(chunk);if(rc!=0){
            String detail="本地流式识别失败（"+rc+"）："+IndependentAsrNative.lastError();
            emit(generation,"error",detail);cancelled=true;close(generation);invalidateEngine();
        }
    }
    private void finish(long generation){
        if(generation!=activeGeneration||cancelled)return;int capturedBytes;synchronized(pcmLock){capturedBytes=pcm.size();}
        if(capturedBytes<800){emit(generation,"error","没有收到有效语音，请重新说话");close(generation);return;}
        emit(generation,"recognizing","");String text=IndependentAsrNative.finishStream();
        if(cancelled||generation!=activeGeneration)return;
        if(text==null||text.trim().isEmpty()){
            String detail="本地识别失败："+IndependentAsrNative.lastError();
            emit(generation,"error",detail);close(generation);invalidateEngine();
        }else {emit(generation,"final",text);close(generation);}
    }
    private void cancel(long generation){if(generation==requestedGeneration)requestedGeneration=0;if(generation!=activeGeneration)return;cancelled=true;close(generation);}
    private void close(long generation){if(generation!=activeGeneration)return;if(prepared)IndependentAsrNative.cancelStream();activeGeneration=0;if(requestedGeneration==generation)requestedGeneration=0;callback=null;synchronized(pcmLock){pcm.reset();}}
    private void invalidateEngine(){
        if(prepared)IndependentAsrNative.release();
        prepared=false;
    }
    private void emit(long generation,String kind,String text){IBinder target=callback;if(target==null||generation!=activeGeneration||cancelled)return;Parcel p=Parcel.obtain();try{p.writeInterfaceToken(TOKEN);p.writeLong(generation);p.writeString(kind);p.writeString(text);target.transact(EVENT,p,null,IBinder.FLAG_ONEWAY);}catch(RemoteException ignored){cancelled=true;}finally{p.recycle();}}
    private void transcribeFile(long generation,IBinder target,String path,long first,long end){
        try{
            if(activeGeneration!=0||requestedGeneration!=0||pendingInteractiveGeneration!=0)throw new IOException("LIVE_ASR_BUSY");
            File file=new File(path).getCanonicalFile();
            File recordings=new File(getFilesDir(),"recordings").getCanonicalFile();
            File work=new File(getFilesDir(),"recording-work").getCanonicalFile();
            String actual=file.getPath();
            if(!actual.startsWith(recordings.getPath()+File.separator)&&!actual.startsWith(work.getPath()+File.separator))throw new IOException("Recording path outside private scope");
            if(!file.isFile()||(file.length()&1)!=0||end>file.length()/2)throw new IOException("Invalid PCM range");
            if(!target.isBinderAlive())return;
            batchGeneration=generation;
            byte[] bytes=new byte[(int)((end-first)*2)];
            try(java.io.RandomAccessFile input=new java.io.RandomAccessFile(file,"r")){input.seek(first*2);input.readFully(bytes);}
            if(!prepared){File modelDir=prepareAssets();int rc=IndependentAsrNative.prepare(modelDir.getAbsolutePath());if(rc!=0)throw new IOException("Local model prepare failed: "+rc);prepared=true;}
            String result=IndependentAsrNative.recognize(bytes);
            if(result==null)throw new IOException("Local recording recognition failed: "+IndependentAsrNative.lastError());
            emitBatch(target,generation,"batch_final",result);
        }catch(Throwable error){emitBatch(target,generation,"batch_error",error.getMessage()==null?error.getClass().getSimpleName():error.getMessage());}
        finally{if(batchGeneration==generation)batchGeneration=0;}
    }
    private void emitBatch(IBinder target,long generation,String kind,String text){
        Parcel packet=Parcel.obtain();try{packet.writeInterfaceToken(TOKEN);packet.writeLong(generation);packet.writeString(kind);packet.writeString(text);target.transact(EVENT,packet,null,IBinder.FLAG_ONEWAY);}catch(RemoteException ignored){}finally{packet.recycle();}
    }
    private File prepareAssets()throws Exception{
        File directory=new File(getFilesDir(),"independent-asr");if(!directory.exists()&&!directory.mkdirs())throw new IOException("无法创建本地模型目录");
        File marker=new File(directory,"asset-set.id");boolean current=marker.isFile()&&ASSET_SET_ID.equals(readSmall(marker));
        if(current){for(int i=0;i<ASSET_NAMES.length;i++)if(new File(directory,ASSET_NAMES[i]).length()!=ASSET_BYTES[i]){current=false;break;}}
        if(current)return directory;
        for(int i=0;i<ASSET_NAMES.length;i++)copyVerifiedAsset(directory,ASSET_NAMES[i],ASSET_BYTES[i],ASSET_SHA256[i]);
        File temp=new File(directory,"asset-set.id.part");try(FileOutputStream out=new FileOutputStream(temp)){out.write(ASSET_SET_ID.getBytes(StandardCharsets.UTF_8));out.getFD().sync();}if(!temp.renameTo(marker))throw new IOException("无法写入本地模型标记");return directory;
    }
    private void copyVerifiedAsset(File directory,String name,long expected,String expectedHash)throws Exception{
        File dst=new File(directory,name);if(dst.isFile()&&dst.length()==expected&&expectedHash.equals(sha256(dst)))return;
        File tmp=new File(directory,name+".part");MessageDigest digest=MessageDigest.getInstance("SHA-256");long total=0;
        try(InputStream in=getAssets().open("independent-asr/"+name);FileOutputStream out=new FileOutputStream(tmp)){byte[] b=new byte[262144];int n;while((n=in.read(b))!=-1){out.write(b,0,n);digest.update(b,0,n);total+=n;}out.getFD().sync();}
        if(total!=expected||!expectedHash.equals(hex(digest.digest())))throw new IOException("模型资源校验失败："+name);if(dst.exists()&&!dst.delete())throw new IOException("无法替换本地模型："+name);if(!tmp.renameTo(dst))throw new IOException("无法保存本地模型："+name);
    }
    private static String readSmall(File file)throws IOException{byte[] data=new byte[(int)Math.min(file.length(),256)];try(FileInputStream in=new FileInputStream(file)){int n=in.read(data);return n<0?"":new String(data,0,n,StandardCharsets.UTF_8);}}
    private static String sha256(File file)throws Exception{MessageDigest d=MessageDigest.getInstance("SHA-256");try(InputStream in=new FileInputStream(file)){byte[] b=new byte[262144];int n;while((n=in.read(b))!=-1)d.update(b,0,n);}return hex(d.digest());}
    private static String hex(byte[] value){StringBuilder out=new StringBuilder();for(byte b:value)out.append(String.format(Locale.ROOT,"%02x",b&255));return out.toString();}
    @Override public void onDestroy(){worker.execute(()->{cancelled=true;if(activeGeneration!=0)close(activeGeneration);if(prepared)IndependentAsrNative.release();});worker.shutdown();super.onDestroy();}
}
