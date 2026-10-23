package io.github.amxooo.voiceinput2pc;
import android.content.ContextWrapper;
import android.test.AndroidTestCase;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.json.*;
/** Private temporary fixtures only; these are store tests, not claimed speech samples. */
public final class RecordingTranscriptStoreTest extends AndroidTestCase {
 private File root,audio;
 private RecordingTranscriptStore store;
 @Override protected void setUp()throws Exception{super.setUp();root=new File(getContext().getCacheDir(),"transcript-test-"+UUID.randomUUID());File recordings=new File(root,"recordings");assertTrue(recordings.mkdirs());audio=new File(recordings,"recording-1000-"+UUID.randomUUID()+".m4a");assertTrue(audio.createNewFile());store=new RecordingTranscriptStore(new ContextWrapper(getContext()){@Override public File getFilesDir(){return root;}});}
 private void baseline()throws Exception{store.update(audio,j->{j.put("state","TRANSCRIBING");j.put("total_segments",1);j.put("completed_segments",1);j.put("speakers",new JSONObject().put("5","说话人1"));j.put("segments",new JSONArray().put(new JSONObject().put("speaker",5).put("text","测试文字").put("status","COMPLETED").put("start_ms",10).put("end_ms",1000)));});}
 public void testCheckpointReopenAndNameSurvivesProgressWrite()throws Exception{baseline();store.renameSpeaker(audio,5,"同事甲");store.update(audio,j->j.put("updated_at",123));JSONObject saved=store.load(audio);assertEquals("同事甲",saved.getJSONObject("speakers").getString("5"));assertEquals("测试文字",saved.getJSONArray("segments").getJSONObject(0).getString("text"));int extra=store.addSpeaker(audio,"同事乙");store.correctSpeaker(audio,0,extra);saved=store.load(audio);assertEquals(extra,saved.getJSONArray("segments").getJSONObject(0).getInt("speaker"));assertTrue(saved.getJSONArray("segments").getJSONObject(0).getBoolean("user_corrected"));}
 public void testCancelCannotBecomeCompleted()throws Exception{baseline();store.update(audio,j->{j.put("cancel_requested",true);j.put("state","CANCELLING");});try{store.update(audio,j->{if(!RecordingWorkPolicy.completed(j.optBoolean("cancel_requested"),j.optInt("completed_segments"),j.optInt("total_segments")))throw new IOException("not complete");j.put("state","COMPLETED");});fail("cancelled task became completed");}catch(IOException expected){}assertEquals("CANCELLING",store.load(audio).getString("state"));}
 public void testOutsideFileSameNameRejected()throws Exception{File outside=new File(root,audio.getName());assertTrue(outside.createNewFile());try{store.load(outside);fail("outside allowed");}catch(IOException expected){}assertTrue(outside.exists());}
 public void testCorruptJsonRefusedWithoutOverwrite()throws Exception{File json=new File(audio+".transcript.json");try(FileOutputStream out=new FileOutputStream(json)){out.write("{broken".getBytes(StandardCharsets.UTF_8));}try{store.load(audio);fail("broken parsed");}catch(IOException expected){}assertEquals(7,json.length());}
 public void testAtomicBackupRecovered()throws Exception{baseline();File json=new File(audio+".transcript.json");assertTrue(json.renameTo(new File(json+".bak")));assertEquals("TRANSCRIBING",store.load(audio).getString("state"));}
 @Override protected void tearDown()throws Exception{clear(root);super.tearDown();}
 private static void clear(File f){File[] all=f.listFiles();if(all!=null)for(File c:all)clear(c);f.delete();}
}
