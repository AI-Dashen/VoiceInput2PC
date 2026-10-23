package io.github.amxooo.voiceinput2pc;

import android.content.Context;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** CPU-only, fully local speaker separation. Labels are per recording, not identities. */
public final class SpeakerDiarizationNative {
    private SpeakerDiarizationNative() {}
    static { System.loadLibrary("speakerdiarization"); }
    /** Returns a sorted JSON array [{start_ms,end_ms,speaker,uncertain,confidence}].
     * PCM must be signed little-endian 16-bit mono 16kHz, no WAV header, <=60 minutes.
     * numSpeakers=0 uses clustering threshold; positive values constrain number of speakers.
     * Call off the UI thread. Native inference cannot be interrupted mid-call; cancellation
     * must discard its returned result. No microphone, networking or authentication access.
     */
    public static native String diarize(String modelDirectory,String rawPcm16Path,int numSpeakers,float threshold);

    /** Copy only packaged verified models into app-private files using atomic replacement. */
    public static synchronized File prepareAssets(Context context) throws IOException {
        File directory = new File(context.getFilesDir(),"speaker-diarization-v1");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Cannot create speaker model directory");
        copy(context,directory,"model.int8.onnx",1540506L,"d582f4b4c6b48205de7e0643c57df0df5615a3c176189be3fc461e9d18827b5d");
        copy(context,directory,"embedding.onnx",28281164L,"aa3cfc16963a10586a9393f5035d6d6b57e98d358b347f80c2a30bf4f00ceba2");
        return directory;
    }
    private static void copy(Context context,File dir,String name,long bytes,String hash) throws IOException {
        File target = new File(dir,name);
        if (valid(target,bytes,hash)) return;
        File temp = new File(dir,name+".tmp");
        try {
            try(InputStream in=context.getAssets().open("speaker-diarization/"+name);FileOutputStream out=new FileOutputStream(temp)) {
                byte[] buffer=new byte[65536];int n;long total=0;
                while((n=in.read(buffer))!=-1){total+=n;if(total>bytes)throw new IOException("Speaker model size invalid");out.write(buffer,0,n);}
                out.getFD().sync();
            }
            if(!valid(temp,bytes,hash))throw new IOException("Speaker model integrity check failed");
            if(!temp.renameTo(target))throw new IOException("Speaker model atomic install failed");
        } finally { if(temp.exists())temp.delete(); }
    }
    private static boolean valid(File file,long size,String expected) throws IOException {
        if(!file.isFile()||file.length()!=size)return false;
        try {
            MessageDigest digest=MessageDigest.getInstance("SHA-256");
            try(FileInputStream in=new FileInputStream(file)){byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1)digest.update(b,0,n);}
            StringBuilder hash=new StringBuilder();for(byte b:digest.digest())hash.append(String.format(java.util.Locale.ROOT,"%02x",b&255));
            return expected.contentEquals(hash);
        } catch(NoSuchAlgorithmException e){throw new IOException(e);}
    }
}
