package io.github.amxooo.voiceinput2pc;
import android.test.InstrumentationTestCase;
import android.content.*;
import android.os.*;
import java.io.*;
import java.util.concurrent.*;
import org.json.*;
/** Tests target app UID/process/loader. Uses public fixture, never microphone or personal text. */
public final class LocalInputDeviceTest extends InstrumentationTestCase {
 public void testIndependentAppOfflineSpeech()throws Exception{
  Context context=getInstrumentation().getTargetContext();android.app.Activity foreground=getInstrumentation().startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));CountDownLatch ready=new CountDownLatch(1),listening=new CountDownLatch(1),done=new CountDownLatch(1);IBinder[] remote={null};String[] text={null},error={null};final long generation=9001;
  Binder callback=new Binder(){protected boolean onTransact(int code,Parcel data,Parcel reply,int flags)throws RemoteException{if(code!=LocalAsrService.EVENT)return false;data.enforceInterface(LocalAsrService.TOKEN);long g=data.readLong();String kind=data.readString(),value=data.readString();if(g!=generation)return true;if("listening".equals(kind))listening.countDown();if("final".equals(kind)){text[0]=value;done.countDown();}if("error".equals(kind)){error[0]=value;listening.countDown();done.countDown();}return true;}};
  ServiceConnection conn=new ServiceConnection(){public void onServiceConnected(ComponentName n,IBinder b){remote[0]=b;ready.countDown();}public void onServiceDisconnected(ComponentName n){error[0]="Service disconnected";done.countDown();}};
  assertTrue(context.bindService(new Intent(context,LocalAsrService.class),conn,Context.BIND_AUTO_CREATE | Context.BIND_IMPORTANT));
  try{
   assertTrue("service bind timeout",ready.await(20,TimeUnit.SECONDS));send(remote[0],LocalAsrService.START,generation,null,callback);assertTrue("model/handle timeout",listening.await(90,TimeUnit.SECONDS));assertNull(error[0]);
   ByteArrayOutputStream bytes=new ByteArrayOutputStream();try(InputStream in=getInstrumentation().getContext().getAssets().open("asr-test.wav")){byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)bytes.write(b,0,n);}byte[] wav=bytes.toByteArray();int pos=12,size=0;while(pos+8<wav.length){int n=(wav[pos+4]&255)|((wav[pos+5]&255)<<8)|((wav[pos+6]&255)<<16)|((wav[pos+7]&255)<<24);if(wav[pos]=='d'&&wav[pos+1]=='a'&&wav[pos+2]=='t'&&wav[pos+3]=='a'){size=n;pos+=8;break;}pos+=8+n+(n&1);}assertTrue(size>0);
   for(int q=0;q<size;q+=1280){int length=Math.min(1280,size-q);byte[] pcm=new byte[length];System.arraycopy(wav,pos+q,pcm,0,length);send(remote[0],LocalAsrService.FEED,generation,pcm,null);}send(remote[0],LocalAsrService.FINISH,generation,null,null);
   assertTrue("final timeout",done.await(45,TimeUnit.SECONDS));assertNull(error[0]);assertNotNull(text[0]);assertEquals("广州市房地产中介协会分析",text[0].replaceAll("[\\p{Punct}。！？，、\\s]",""));
  }finally{if(remote[0]!=null)send(remote[0],LocalAsrService.CANCEL,generation,null,null);context.unbindService(conn);foreground.finish();}
 }
 public void testRealPinyinAndT9()throws Exception{
  ChineseInputEngine engine=new RimeChineseInputEngine(getInstrumentation().getTargetContext());
  try{assertTrue(engine.snapshot().error,engine.snapshot().available);engine.setLayout(ChineseInputEngine.Layout.FULL_PINYIN);for(char c:"nihao".toCharArray())engine.input(c);assertTrue("No Chinese candidates",engine.snapshot().candidates.size()>0);String picked=engine.select(0);assertTrue("Non-Chinese result",picked!=null&&picked.matches(".*[\\u4e00-\\u9fff].*"));engine.reset();engine.setLayout(ChineseInputEngine.Layout.T9);for(char c:"64426".toCharArray())engine.input(c);assertTrue("T9 has no real candidates",engine.snapshot().candidates.size()>0);assertTrue(engine.snapshot().candidates.get(0).matches(".*[\\u4e00-\\u9fff].*"));}finally{engine.close();}
 }
 private static void send(IBinder b,int code,long g,byte[] pcm,IBinder callback)throws RemoteException{Parcel p=Parcel.obtain();try{p.writeInterfaceToken(LocalAsrService.TOKEN);p.writeLong(g);if(code==LocalAsrService.START)p.writeStrongBinder(callback);if(code==LocalAsrService.FEED)p.writeByteArray(pcm);b.transact(code,p,null,IBinder.FLAG_ONEWAY);}finally{p.recycle();}}
}
