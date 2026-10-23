package io.github.amxooo.voiceinput2pc;

import java.util.ArrayList;
import java.util.List;
/** Small contracts shared by persistent jobs and host-side regression tests. */
final class RecordingWorkPolicy {
    static final long MAX_DURATION_MS=3600000, SEGMENT_MS=20000;
    private RecordingWorkPolicy(){}
    static List<long[]> split(long start,long end,long audioDuration){
        if(start<0||end<=start||audioDuration<=0||audioDuration>MAX_DURATION_MS||end>audioDuration)throw new IllegalArgumentException("Invalid segment range");
        List<long[]> result=new ArrayList<>();for(long s=start;s<end;s+=SEGMENT_MS)result.add(new long[]{s,Math.min(end,s+SEGMENT_MS)});return result;
    }
    static boolean completed(boolean cancelRequested,int completed,int total){return !cancelRequested&&total>0&&completed==total;}
    static boolean resumable(String state){return "QUEUED".equals(state)||"DECODING".equals(state)||"DIARIZING".equals(state)||"TRANSCRIBING".equals(state)||"WAITING_INPUT".equals(state);}
}
