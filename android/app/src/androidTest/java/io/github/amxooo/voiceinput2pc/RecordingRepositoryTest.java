package io.github.amxooo.voiceinput2pc;

import android.content.ContextWrapper;
import android.test.AndroidTestCase;
import java.io.File;
import java.io.FileOutputStream;
import java.util.UUID;

/** Storage contract only, test bytes do not claim to be playable audio. */
public final class RecordingRepositoryTest extends AndroidTestCase {
    private File base;
    private RecordingRepository repository;
    @Override protected void setUp()throws Exception{
        super.setUp();base=new File(getContext().getCacheDir(),"recording-test-"+UUID.randomUUID());assertTrue(base.mkdirs());
        repository=new RecordingRepository(new ContextWrapper(getContext()){@Override public File getFilesDir(){return base;}});
    }
    public void testFinishedRecordingKeepsBytesAndMetadata()throws Exception{
        File partial=repository.create(1000);try(FileOutputStream out=new FileOutputStream(partial)){out.write(new byte[]{1,2,3,4});}
        RecordingRepository.Entry entry=repository.finish(partial,1000,4200,false);
        assertTrue(entry.file.isFile());assertEquals(4,entry.file.length());assertFalse(entry.interrupted);
        assertEquals(1,repository.list().size());assertEquals(4200L,repository.list().get(0).durationMs);
        assertFalse(repository.list().get(0).interrupted);assertFalse(partial.exists());
    }
    public void testInterruptedBytesArePreservedWithoutClaimingSuccess()throws Exception{
        File partial=repository.create(2000);try(FileOutputStream out=new FileOutputStream(partial)){out.write(new byte[]{7,8,9});}
        repository.recoverInterrupted();assertEquals(1,repository.list().size());
        RecordingRepository.Entry entry=repository.list().get(0);assertTrue(entry.interrupted);assertEquals(3,entry.file.length());
    }
    public void testOutsidePathIsRejected()throws Exception{
        File outside=new File(base,"outside.m4a");try(FileOutputStream out=new FileOutputStream(outside)){out.write(1);}
        try{repository.finish(outside,0,0,false);fail("outside path allowed");}catch(java.io.IOException expected){}
        assertEquals(1,outside.length());assertTrue(repository.list().isEmpty());
    }
    @Override protected void tearDown()throws Exception{clear(base);super.tearDown();}
    private static void clear(File f){if(f==null)return;File[] children=f.listFiles();if(children!=null)for(File c:children)clear(c);f.delete();}
}
