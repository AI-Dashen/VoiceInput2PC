package io.github.amxooo.voiceinput2pc;
import java.io.*;
import java.nio.channels.*;
import java.util.function.BooleanSupplier;
/** Whole-job lease, separate from short state locks so UI cancellation remains available. */
final class RecordingExecutionLock implements AutoCloseable {
 private final RandomAccessFile file;
 private final FileLock lock;
 private RecordingExecutionLock(RandomAccessFile file,FileLock lock){this.file=file;this.lock=lock;}
 static RecordingExecutionLock tryAcquire(File path)throws IOException{
  RandomAccessFile file=new RandomAccessFile(path,"rw");try{FileLock lock;try{lock=file.getChannel().tryLock();}catch(OverlappingFileLockException busy){file.close();return null;}if(lock==null){file.close();return null;}return new RecordingExecutionLock(file,lock);}catch(IOException|RuntimeException e){file.close();throw e;}
 }
 static RecordingExecutionLock acquire(File path,BooleanSupplier stopped)throws IOException,InterruptedException{
  while(!stopped.getAsBoolean()){RecordingExecutionLock lease=tryAcquire(path);if(lease!=null)return lease;Thread.sleep(200);}throw new InterruptedException("Job stopped while waiting for execution lease");
 }
 @Override public void close()throws IOException{try{lock.release();}finally{file.close();}}
}
