package io.github.amxooo.voiceinput2pc;
import java.io.File;
import java.util.concurrent.atomic.AtomicBoolean;
public final class RecordingExecutionLockTest {
 public static void main(String[] args)throws Exception{
  File f=File.createTempFile("recording-lease-",".lock");
  try{
   RecordingExecutionLock first=RecordingExecutionLock.tryAcquire(f);if(first==null)throw new AssertionError("initial lock");
   if(RecordingExecutionLock.tryAcquire(f)!=null)throw new AssertionError("duplicate executor entered");
   AtomicBoolean stopped=new AtomicBoolean(),cancelled=new AtomicBoolean();Thread waiter=new Thread(()->{try(RecordingExecutionLock ignored=RecordingExecutionLock.acquire(f,stopped::get)){throw new AssertionError("duplicate acquired");}catch(InterruptedException expected){cancelled.set(true);}catch(Exception e){throw new RuntimeException(e);}});waiter.start();Thread.sleep(100);stopped.set(true);waiter.join(1000);if(!cancelled.get())throw new AssertionError("stop did not interrupt wait");
   first.close();try(RecordingExecutionLock reopened=RecordingExecutionLock.tryAcquire(f)){if(reopened==null)throw new AssertionError("resume lease failed");}
   System.out.println("execution lease checks passed");
  }finally{f.delete();}
 }
}
