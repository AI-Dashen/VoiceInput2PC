package io.github.amxooo.voiceinput2pc;
import java.util.List;
public final class RecordingWorkPolicyTest {
 static int checks;
 static void check(boolean b){checks++;if(!b)throw new AssertionError("check "+checks);}
 public static void main(String[] args){
  List<long[]> a=RecordingWorkPolicy.split(638,56861,56861);check(a.size()==3);check(a.get(0)[0]==638);check(a.get(2)[1]==56861);
  long next=638;for(long[] r:a){check(r[0]==next);check(r[1]-r[0]<=20000);next=r[1];}check(next==56861);
  for(long[] invalid:new long[][]{{-1,10,20},{1,1,20},{0,21,20},{0,10,3600001}}){boolean denied=false;try{RecordingWorkPolicy.split(invalid[0],invalid[1],invalid[2]);}catch(IllegalArgumentException expected){denied=true;}check(denied);}
  check(RecordingWorkPolicy.completed(false,10,10));check(!RecordingWorkPolicy.completed(true,10,10));check(!RecordingWorkPolicy.completed(false,0,0));check(!RecordingWorkPolicy.completed(false,9,10));
  for(String state:new String[]{"QUEUED","DECODING","DIARIZING","TRANSCRIBING","WAITING_INPUT"})check(RecordingWorkPolicy.resumable(state));
  for(String state:new String[]{"COMPLETED","CANCELLED","FAILED",""})check(!RecordingWorkPolicy.resumable(state));
  System.out.println(checks+" transcription policy checks passed");
 }
}
