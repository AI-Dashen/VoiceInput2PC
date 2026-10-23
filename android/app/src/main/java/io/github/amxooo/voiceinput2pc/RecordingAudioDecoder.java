package io.github.amxooo.voiceinput2pc;

import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.function.BooleanSupplier;

/** Android codec streaming decode to headerless PCM16LE, mono, 16 kHz. No whole-recording heap buffer. */
final class RecordingAudioDecoder {
    private RecordingAudioDecoder(){}
    static long decodeToPcm(File source,File destination,BooleanSupplier cancelled)throws IOException{
        if(android.os.Looper.myLooper()==android.os.Looper.getMainLooper())throw new IllegalStateException("Decode must run in background");
        File parent=destination.getAbsoluteFile().getParentFile();if(parent==null||(!parent.isDirectory()&&!parent.mkdirs()))throw new IOException("Cannot create PCM directory");
        if(source.getCanonicalFile().equals(destination.getCanonicalFile()))throw new IOException("Audio source equals destination");
        File temporary=File.createTempFile("recording-", ".pcm.part",parent);MediaExtractor extractor=new MediaExtractor();MediaCodec codec=null;boolean success=false,started=false;
        try(FileOutputStream fileOut=new FileOutputStream(temporary);BufferedOutputStream out=new BufferedOutputStream(fileOut,65536)){
            extractor.setDataSource(source.getAbsolutePath());int track=-1;MediaFormat format=null;
            for(int i=0;i<extractor.getTrackCount();i++){MediaFormat candidate=extractor.getTrackFormat(i);String mime=candidate.getString(MediaFormat.KEY_MIME);if(mime!=null&&mime.startsWith("audio/")){track=i;format=candidate;break;}}
            if(track<0||format==null)throw new IOException("No audio track");
            if(format.containsKey(MediaFormat.KEY_DURATION)){long us=format.getLong(MediaFormat.KEY_DURATION);if(us>3_600_000_000L)throw new IOException("本版单次转写支持60分钟以内录音");if(us>0 && parent.getUsableSpace()<us/1000000*32000+64L*1024*1024)throw new IOException("存储空间不足，原录音保留");}
            extractor.selectTrack(track);
            codec=MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME));codec.configure(format,null,null,0);codec.start();started=true;
            Resampler resampler=new Resampler(out);resampler.configure(format);boolean inputDone=false,outputDone=false;MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();long lastProgress=System.nanoTime();
            while(!outputDone){
                if(cancelled.getAsBoolean()||Thread.currentThread().isInterrupted())throw new IOException("CANCELLED");
                boolean progress=false;
                if(!inputDone){int index=codec.dequeueInputBuffer(10000);if(index>=0){ByteBuffer input=codec.getInputBuffer(index);if(input==null)throw new IOException("Missing decoder input buffer");input.clear();int size=extractor.readSampleData(input,0);if(size<0){codec.queueInputBuffer(index,0,0,0,MediaCodec.BUFFER_FLAG_END_OF_STREAM);inputDone=true;}else{codec.queueInputBuffer(index,0,size,extractor.getSampleTime(),0);extractor.advance();}progress=true;}}
                int index=codec.dequeueOutputBuffer(info,10000);
                if(index==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED){resampler.configure(codec.getOutputFormat());progress=true;}
                else if(index>=0){try{if(info.size>0&&(info.flags&MediaCodec.BUFFER_FLAG_CODEC_CONFIG)==0){ByteBuffer buffer=codec.getOutputBuffer(index);if(buffer==null)throw new IOException("Missing decoded PCM");buffer.position(info.offset);buffer.limit(info.offset+info.size);resampler.accept(buffer);}outputDone=(info.flags&MediaCodec.BUFFER_FLAG_END_OF_STREAM)!=0;}finally{codec.releaseOutputBuffer(index,false);}progress=true;}
                if(progress)lastProgress=System.nanoTime();else if(System.nanoTime()-lastProgress>30_000_000_000L)throw new IOException("Audio decoder stalled");
            }
            if(resampler.partial!=0||resampler.outputSamples==0)throw new IOException("Incomplete decoded PCM");
            out.flush();fileOut.getFD().sync();if(cancelled.getAsBoolean())throw new IOException("CANCELLED");
            Files.move(temporary.toPath(),destination.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);success=true;return resampler.outputSamples;
        }catch(RuntimeException failure){throw new IOException("Android audio decode failed: "+failure.getClass().getSimpleName(),failure);}
        finally{if(codec!=null){if(started)try{codec.stop();}catch(RuntimeException ignored){}codec.release();}extractor.release();if(!success)temporary.delete();}
    }
    /** Linear interpolation with continuity across codec buffers. */
    private static final class Resampler{
        final BufferedOutputStream out;int rate,channels,encoding,frameBytes,partial;byte[] frame;long frames,outputSamples;double previous,nextPosition;
        Resampler(BufferedOutputStream out){this.out=out;}
        void configure(MediaFormat format)throws IOException{
            int newRate=format.getInteger(MediaFormat.KEY_SAMPLE_RATE),newChannels=format.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
            int newEncoding=format.containsKey(MediaFormat.KEY_PCM_ENCODING)?format.getInteger(MediaFormat.KEY_PCM_ENCODING):AudioFormat.ENCODING_PCM_16BIT;
            if(newRate<8000||newRate>192000||newChannels<1||newChannels>8||(newEncoding!=AudioFormat.ENCODING_PCM_16BIT&&newEncoding!=AudioFormat.ENCODING_PCM_FLOAT))throw new IOException("Unsupported decoded audio format");
            if(frames>0&&(rate!=newRate||channels!=newChannels||encoding!=newEncoding))throw new IOException("Audio format changed mid recording");
            rate=newRate;channels=newChannels;encoding=newEncoding;frameBytes=channels*(encoding==AudioFormat.ENCODING_PCM_FLOAT?4:2);frame=new byte[frameBytes];partial=0;
        }
        void accept(ByteBuffer buffer)throws IOException{
            while(buffer.hasRemaining()){frame[partial++]=buffer.get();if(partial!=frameBytes)continue;partial=0;double mono=0;int offset=0;
                for(int c=0;c<channels;c++){double sample;if(encoding==AudioFormat.ENCODING_PCM_FLOAT){int bits=(frame[offset]&255)|((frame[offset+1]&255)<<8)|((frame[offset+2]&255)<<16)|(frame[offset+3]<<24);sample=Float.intBitsToFloat(bits)*32768.0;offset+=4;}else{sample=(short)((frame[offset]&255)|(frame[offset+1]<<8));offset+=2;}if(!Double.isNaN(sample)&&!Double.isInfinite(sample))mono+=sample;}mono/=channels;
                if(frames==0){write(mono);nextPosition=(double)rate/16000;}else while(nextPosition<=frames){double fraction=nextPosition-(frames-1);write(previous+(mono-previous)*fraction);nextPosition+=(double)rate/16000;}
                previous=mono;frames++;
            }
        }
        void write(double value)throws IOException{if(outputSamples>=16000L*60*60)throw new IOException("录音超过60分钟转写限制");int sample=(int)Math.round(Math.max(-32768,Math.min(32767,value)));out.write(sample&255);out.write((sample>>>8)&255);outputSamples++;}
    }
}
