package io.github.amxooo.voiceinput2pc;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Binder;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.RemoteException;
import java.io.File;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

/** Background-only, bounded file transcription through one reusable ASR binding. */
final class RecordingAsrClient implements AutoCloseable {
    private static final AtomicLong IDS=new AtomicLong(System.nanoTime());
    private final Context context;
    private final Object connectionLock=new Object();
    private volatile IBinder remote;
    private volatile String connectionError;
    private volatile CountDownLatch connectionReady=new CountDownLatch(1);
    private volatile CountDownLatch activeRequest;
    private boolean bound,closed;
    private final ServiceConnection connection=new ServiceConnection(){
        @Override public void onServiceConnected(ComponentName name,IBinder service){remote=service;connectionError=null;connectionReady.countDown();}
        @Override public void onServiceDisconnected(ComponentName name){disconnected("Local ASR process disconnected");}
        @Override public void onNullBinding(ComponentName name){disconnected("Local ASR binding unavailable");}
        @Override public void onBindingDied(ComponentName name){disconnected("Local ASR binding died");}
        private void disconnected(String error){remote=null;connectionError=error;connectionReady.countDown();CountDownLatch request=activeRequest;if(request!=null)request.countDown();}
    };
    RecordingAsrClient(Context context){this.context=context.getApplicationContext();}
    private IBinder requireService(BooleanSupplier cancelled)throws IOException,InterruptedException{
        synchronized(connectionLock){
            if(closed)throw new IOException("ASR client closed");
            if(remote!=null)return remote;
            // A dead binding never becomes usable by waiting on its completed latch.
            // Dispose it before creating the one bounded reconnect allowed by the caller.
            if(bound&&connectionError!=null){
                try{context.unbindService(connection);}catch(IllegalArgumentException ignored){}
                bound=false;
            }
            if(!bound){
                connectionError=null;connectionReady=new CountDownLatch(1);
                bound=context.bindService(new Intent(context,LocalAsrService.class),connection,Context.BIND_AUTO_CREATE);
                if(!bound)throw new IOException("Cannot bind local ASR");
            }
        }
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
        while(!connectionReady.await(200,TimeUnit.MILLISECONDS)){
            if(cancelled.getAsBoolean())throw new IOException("CANCELLED");
            if(System.nanoTime()>=deadline)throw new IOException("Local ASR connection timeout");
        }
        if(connectionError!=null)throw new IOException(connectionError);
        IBinder service=remote;if(service==null)throw new IOException("Local ASR connection unavailable");return service;
    }
    String transcribe(File pcm,long startSample,long endSample,BooleanSupplier cancelled)throws IOException,InterruptedException{
        if(Looper.myLooper()==Looper.getMainLooper())throw new IllegalStateException("Recording ASR must run in background");
        if(startSample<0||endSample<=startSample||endSample-startSample>480000)throw new IOException("Invalid ASR segment");
        final long generation=IDS.incrementAndGet();
        CountDownLatch done=new CountDownLatch(1);String[] result=new String[1],error=new String[1];activeRequest=done;
        Binder callback=new Binder(){@Override protected boolean onTransact(int code,Parcel data,Parcel reply,int flags)throws RemoteException{
            if(code!=LocalAsrService.EVENT)return super.onTransact(code,data,reply,flags);
            data.enforceInterface(LocalAsrService.TOKEN);if(Binder.getCallingUid()!=android.os.Process.myUid())throw new SecurityException("App-only callback");
            if(data.readLong()!=generation)return true;String kind=data.readString(),text=data.readString();
            if("batch_final".equals(kind)){result[0]=text;done.countDown();}else if("batch_error".equals(kind)){error[0]=text;done.countDown();}return true;
        }};
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(90);
        try{
            if(cancelled.getAsBoolean())throw new IOException("CANCELLED");
            IBinder service=requireService(cancelled);
            Parcel data=Parcel.obtain(),reply=Parcel.obtain();try{
                data.writeInterfaceToken(LocalAsrService.TOKEN);data.writeLong(generation);data.writeStrongBinder(callback);data.writeString(pcm.getAbsolutePath());data.writeLong(startSample);data.writeLong(endSample);
                if(!service.transact(LocalAsrService.FILE_TRANSCRIBE,data,reply,0))throw new IOException("Recording ASR operation unsupported");reply.readException();
            }catch(RemoteException failure){throw new IOException("ASR connection interrupted",failure);}finally{data.recycle();reply.recycle();}
            while(!done.await(200,TimeUnit.MILLISECONDS)){
                if(cancelled.getAsBoolean())throw new IOException("CANCELLED");
                if(System.nanoTime()>=deadline)throw new IOException("Local ASR segment timeout");
            }
            if(cancelled.getAsBoolean())throw new IOException("CANCELLED");
            if(error[0]!=null)throw new IOException(error[0]);
            if(result[0]==null)throw new IOException(connectionError==null?"Missing local ASR result":connectionError);return result[0];
        }finally{if(activeRequest==done)activeRequest=null;}
    }
    @Override public void close(){
        synchronized(connectionLock){
            closed=true;
            if(bound){try{context.unbindService(connection);}catch(IllegalArgumentException ignored){}bound=false;}
            remote=null;connectionReady.countDown();CountDownLatch request=activeRequest;if(request!=null)request.countDown();
        }
    }
}
