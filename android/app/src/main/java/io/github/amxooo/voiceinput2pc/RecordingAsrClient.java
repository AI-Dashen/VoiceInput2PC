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

/** Background-only, bounded file transcription through the existing isolated ASR process. */
final class RecordingAsrClient {
    private static final AtomicLong IDS=new AtomicLong(System.nanoTime());
    private final Context context;
    RecordingAsrClient(Context context){this.context=context.getApplicationContext();}
    String transcribe(File pcm,long startSample,long endSample,BooleanSupplier cancelled)throws IOException,InterruptedException{
        if(Looper.myLooper()==Looper.getMainLooper())throw new IllegalStateException("Recording ASR must run in background");
        if(startSample<0||endSample<=startSample||endSample-startSample>480000)throw new IOException("Invalid ASR segment");
        final long generation=IDS.incrementAndGet();
        CountDownLatch done=new CountDownLatch(1);String[] result=new String[1],error=new String[1];
        Binder callback=new Binder(){@Override protected boolean onTransact(int code,Parcel data,Parcel reply,int flags)throws RemoteException{
            if(code!=LocalAsrService.EVENT)return super.onTransact(code,data,reply,flags);
            data.enforceInterface(LocalAsrService.TOKEN);if(Binder.getCallingUid()!=android.os.Process.myUid())throw new SecurityException("App-only callback");
            if(data.readLong()!=generation)return true;String kind=data.readString(),text=data.readString();
            if("batch_final".equals(kind)){result[0]=text;done.countDown();}else if("batch_error".equals(kind)){error[0]=text;done.countDown();}return true;
        }};
        ServiceConnection connection=new ServiceConnection(){
            @Override public void onServiceConnected(ComponentName name,IBinder service){
                Parcel data=Parcel.obtain(),reply=Parcel.obtain();try{
                    data.writeInterfaceToken(LocalAsrService.TOKEN);data.writeLong(generation);data.writeStrongBinder(callback);data.writeString(pcm.getAbsolutePath());data.writeLong(startSample);data.writeLong(endSample);
                    if(!service.transact(LocalAsrService.FILE_TRANSCRIBE,data,reply,0))throw new IOException("Recording ASR operation unsupported");reply.readException();
                }catch(Exception failure){error[0]="ASR connection: "+failure.getClass().getSimpleName();done.countDown();}finally{data.recycle();reply.recycle();}
            }
            @Override public void onServiceDisconnected(ComponentName name){error[0]="Local ASR process disconnected";done.countDown();}
            @Override public void onNullBinding(ComponentName name){error[0]="Local ASR binding unavailable";done.countDown();}
            @Override public void onBindingDied(ComponentName name){error[0]="Local ASR binding died";done.countDown();}
        };
        boolean bound=false;long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(90);
        try{
            if(cancelled.getAsBoolean())throw new IOException("CANCELLED");
            bound=context.bindService(new Intent(context,LocalAsrService.class),connection,Context.BIND_AUTO_CREATE);
            if(!bound)throw new IOException("Cannot bind local ASR");
            while(!done.await(200,TimeUnit.MILLISECONDS)){
                if(cancelled.getAsBoolean())throw new IOException("CANCELLED");
                if(System.nanoTime()>=deadline)throw new IOException("Local ASR segment timeout");
            }
            if(cancelled.getAsBoolean())throw new IOException("CANCELLED");
            if(error[0]!=null)throw new IOException(error[0]);
            if(result[0]==null)throw new IOException("Missing local ASR result");return result[0];
        }finally{if(bound)context.unbindService(connection);}
    }
}
