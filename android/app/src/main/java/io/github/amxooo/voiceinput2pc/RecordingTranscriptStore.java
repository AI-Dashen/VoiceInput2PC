package io.github.amxooo.voiceinput2pc;

import android.content.Context;
import android.util.AtomicFile;
import org.json.*;
import java.io.*;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;

/** Per-recording job checkpoints, protected against concurrent UI/worker-process updates. */
final class RecordingTranscriptStore {
    private static final Object PROCESS_LOCK=new Object();
    private final File directory;
    interface Change { void apply(JSONObject value)throws Exception; }
    RecordingTranscriptStore(Context context){directory=new File(context.getFilesDir(),"recordings");}
    File audio(String name)throws IOException{
        if(name==null||!name.matches("recording-[0-9]+-[0-9a-f-]+\\.m4a"))throw new IOException("非法录音文件名");
        File file=new File(directory,name),base=directory.getCanonicalFile();
        if(!file.getCanonicalFile().equals(new File(base,name))||!file.isFile())throw new IOException("录音文件不存在或路径越界");return file;
    }
    private File checked(File input)throws IOException{File valid=audio(input.getName()).getCanonicalFile();if(!input.getCanonicalFile().equals(valid))throw new IOException("录音路径越界");return valid;}
    JSONObject load(File audio)throws IOException{
        final JSONObject[] value=new JSONObject[1];locked(audio,false,j->value[0]=new JSONObject(j.toString()));return value[0];
    }
    void update(File audio,Change change)throws IOException{locked(audio,true,change);}
    private void locked(File input,boolean write,Change change)throws IOException{
        File audio=checked(input);File state=new File(audio.getParentFile(),audio.getName()+".transcript.json");
        synchronized(PROCESS_LOCK){try(RandomAccessFile lockFile=new RandomAccessFile(state+".lock","rw");FileLock lock=lockFile.getChannel().lock()){
            JSONObject data=new JSONObject();AtomicFile atomic=new AtomicFile(state);
            if(state.exists()||new File(state+".bak").exists()){
                byte[] bytes=atomic.readFully();if(bytes.length>16*1024*1024)throw new IOException("转写记录过大");
                try{data=new JSONObject(new String(bytes,StandardCharsets.UTF_8));}catch(JSONException e){throw new IOException("转写进度损坏，原录音未改变",e);}
            }
            try{change.apply(data);}catch(Exception e){if(e instanceof IOException)throw (IOException)e;throw new IOException("无法更新转写记录",e);}
            if(write){FileOutputStream out=null;try{out=atomic.startWrite();out.write(data.toString().getBytes(StandardCharsets.UTF_8));atomic.finishWrite(out);}catch(IOException e){if(out!=null)atomic.failWrite(out);throw e;}}
        }}
    }
    void renameSpeaker(File audio,int speaker,String name)throws IOException{
        final String label=name==null?"":name.trim();if(speaker<0||label.isEmpty()||label.length()>40||label.contains("\n"))throw new IOException("称呼须为1至40个字符");
        update(audio,j->{JSONObject people=j.optJSONObject("speakers");if(people==null||!people.has(String.valueOf(speaker)))throw new IOException("此说话人不存在");people.put(String.valueOf(speaker),label);});
    }
    int addSpeaker(File audio,String name)throws IOException{
        final String label=name==null?"":name.trim();if(label.isEmpty()||label.length()>40||label.contains("\n"))throw new IOException("称呼须为1至40个字符");
        final int[] result={0};update(audio,j->{JSONObject people=j.optJSONObject("speakers");if(people==null)throw new IOException("尚无说话人片段");java.util.Iterator<String> keys=people.keys();int next=0;while(keys.hasNext()){try{next=Math.max(next,Integer.parseInt(keys.next())+1);}catch(NumberFormatException ignored){}}if(next>1000)throw new IOException("说话人标签过多");people.put(String.valueOf(next),label);result[0]=next;});return result[0];
    }
    void correctSpeaker(File audio,int index,int speaker)throws IOException{
        update(audio,j->{JSONArray segments=j.optJSONArray("segments");JSONObject people=j.optJSONObject("speakers");
            if(segments==null||index<0||index>=segments.length()||speaker< -1||(speaker>=0&&(people==null||!people.has(String.valueOf(speaker)))))throw new IOException("说话人或片段无效");
            JSONObject segment=segments.getJSONObject(index);if(!segment.has("original_speaker"))segment.put("original_speaker",segment.optInt("speaker",-1));segment.put("speaker",speaker);segment.put("user_corrected",true);segment.put("uncertain",speaker<0);
        });
    }
}
