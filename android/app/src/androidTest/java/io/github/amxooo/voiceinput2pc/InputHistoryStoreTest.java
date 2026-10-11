package io.github.amxooo.voiceinput2pc;
import android.database.Cursor;
import android.test.AndroidTestCase;
import android.test.RenamingDelegatingContext;
import java.io.StringWriter;
import java.util.UUID;
import org.json.JSONArray;
/** Isolated DB: never clears real history or preferences. */
public final class InputHistoryStoreTest extends AndroidTestCase {
 public void testDedupReopenAndFullExport() throws Exception {
  RenamingDelegatingContext c=new RenamingDelegatingContext(getContext(),"history-test-"+UUID.randomUUID()+"-");
  InputHistoryStore s=new InputHistoryStore(c);
  try {
   for(int i=0;i<250;i++) s.record("session","phone_ime","device","ACTIVE","",0,"中文 "+i);
   s.record("session","phone_ime","device","ACTIVE","",0,"中文 249");
   try(Cursor page=s.search("中文",0,Long.MAX_VALUE,Long.MAX_VALUE,200)){assertEquals(200,page.getCount());}
   s.close();s=new InputHistoryStore(c);
   for(String f:new String[]{"txt","md","json","csv"}){
    StringWriter out=new StringWriter();assertEquals(250,s.export(out,f,"中文",0,Long.MAX_VALUE));
    assertTrue(out.toString().contains("中文 249"));
    if(f.equals("json"))assertEquals(250,new JSONArray(out.toString()).length());
   }
   try(Cursor page=s.search("' OR 1=1",0,Long.MAX_VALUE,Long.MAX_VALUE,200)){assertEquals(0,page.getCount());}
   s.record("session","phone_ime","device","QUEUED","request",1,"中文 249");
   assertEquals(251,s.export(new StringWriter(),"json","",0,Long.MAX_VALUE));
  } finally {s.close();}
 }
}
