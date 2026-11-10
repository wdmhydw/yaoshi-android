package win.youhoo.moss;

import android.content.Context;
import android.media.*;
import android.net.Uri;
import android.os.*;
import java.io.*;
import java.nio.*;
import java.util.*;
import java.util.concurrent.*;

/** Async Android decode with multi-frame batching; consumers control prefetch. */
final class AudioDecoder {
    interface Progress { void update(double seconds); void check()throws Exception; default void format(double seconds,String mode){} }
    interface Chunks { void ready(Chunk chunk)throws Exception; }
    static final class Chunk {
        final File file;final double start,duration,rms;
        Chunk(File f,double s,double d,double level){file=f;start=s;duration=d;rms=level;}
    }
    static void decode(Context ctx,Uri uri,File dir,Progress progress,Chunks chunks)throws Exception{
        MediaExtractor ex=new MediaExtractor();MediaCodec codec=null;HandlerThread callbacks=null;Sink sink=null;
        try{
            if("file".equals(uri.getScheme()))ex.setDataSource(uri.getPath());else ex.setDataSource(ctx,uri,null);
            MediaFormat fmt=null;
            for(int i=0;i<ex.getTrackCount();i++){MediaFormat f=ex.getTrackFormat(i);if(f.getString(MediaFormat.KEY_MIME).startsWith("audio/")){fmt=f;ex.selectTrack(i);break;}}
            if(fmt==null)throw new IOException("这个文件没有音轨，请选择音频或含声音的视频。");
            double duration=fmt.containsKey(MediaFormat.KEY_DURATION)?fmt.getLong(MediaFormat.KEY_DURATION)/1e6:0;
            if(duration>5400)throw new IOException("请先将录音裁剪到 90 分钟以内。");
            int rate=fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE),channels=fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
            sink=new Sink(dir,rate,channels,fmt.containsKey(MediaFormat.KEY_PCM_ENCODING)?fmt.getInteger(MediaFormat.KEY_PCM_ENCODING):AudioFormat.ENCODING_PCM_16BIT,progress,chunks);
            String mime=fmt.getString(MediaFormat.KEY_MIME);boolean mu="audio/g711-mlaw".equals(mime),a="audio/g711-alaw".equals(mime);
            if("audio/raw".equals(mime)||mu||a){
                progress.format(duration,"pcm");ByteBuffer b=ByteBuffer.allocate(2*1024*1024);
                if(mu||a)sink.configure(rate,channels,AudioFormat.ENCODING_PCM_16BIT);
                while(true){progress.check();b.clear();int n=ex.readSampleData(b,0);if(n<0)break;b.position(0);b.limit(n);
                    if(mu||a){ByteBuffer pcm=ByteBuffer.allocate(n*2).order(ByteOrder.LITTLE_ENDIAN);
                        while(b.hasRemaining()){int u=b.get()&255,s;
                            if(mu){u=(~u)&255;s=(((u&15)<<3)+132)<<((u&112)>>4);s=(u&128)!=0?132-s:s-132;}
                            else{u^=85;s=(u&15)<<4;int e=(u&112)>>4;s+=e==0?8:264;if(e>1)s<<=e-1;s=(u&128)!=0?s:-s;}pcm.putShort((short)s);}
                        pcm.flip();sink.accept(pcm);
                    }else sink.accept(b);ex.advance();}
            }else{
                callbacks=new HandlerThread("MossAudioCodec");callbacks.start();
                // Each event owns one codec buffer until consumed. Codec buffer counts
                // bound this queue; callbacks never block on inference or disk writes.
                BlockingQueue<Event> events=new LinkedBlockingQueue<>();
                MediaCodec.Callback cb=new MediaCodec.Callback(){
                    public void onInputBufferAvailable(MediaCodec c,int index){events.add(new Event(0,index,null,null,null));}
                    public void onOutputBufferAvailable(MediaCodec c,int index,MediaCodec.BufferInfo info){ArrayDeque<MediaCodec.BufferInfo> infos=new ArrayDeque<>();infos.add(copy(info));events.add(new Event(1,index,infos,null,null));}
                    public void onOutputBuffersAvailable(MediaCodec c,int index,ArrayDeque<MediaCodec.BufferInfo> infos){ArrayDeque<MediaCodec.BufferInfo> clone=new ArrayDeque<>();for(MediaCodec.BufferInfo i:infos)clone.add(copy(i));events.add(new Event(1,index,clone,null,null));}
                    public void onOutputFormatChanged(MediaCodec c,MediaFormat f){events.add(new Event(2,0,null,f,null));}
                    public void onError(MediaCodec c,MediaCodec.CodecException error){events.add(new Event(3,0,null,null,error));}
                };
                codec=MediaCodec.createDecoderByType(mime);
                boolean batch=codec.getCodecInfo().getCapabilitiesForType(mime).isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_MultipleFrames);
                fmt.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE,2*1024*1024);
                if(batch){fmt.setInteger(MediaFormat.KEY_BUFFER_BATCH_MAX_OUTPUT_SIZE,256*1024);fmt.setInteger(MediaFormat.KEY_BUFFER_BATCH_THRESHOLD_OUTPUT_SIZE,64*1024);}
                codec.setCallback(cb,new Handler(callbacks.getLooper()));
                try{codec.configure(fmt,null,null,0);}catch(Exception first){
                    if(!batch)throw first;codec.release();events.clear();batch=false;
                    fmt.removeKey(MediaFormat.KEY_BUFFER_BATCH_MAX_OUTPUT_SIZE);fmt.removeKey(MediaFormat.KEY_BUFFER_BATCH_THRESHOLD_OUTPUT_SIZE);
                    codec=MediaCodec.createDecoderByType(mime);codec.setCallback(cb,new Handler(callbacks.getLooper()));codec.configure(fmt,null,null,0);
                }
                progress.format(duration,batch?"batch":"async");codec.start();
                boolean inputDone=false,outputDone=false;long lastOutput=System.nanoTime();
                while(!outputDone){
                    long at=System.nanoTime();progress.check();lastOutput+=System.nanoTime()-at;
                    Event e=events.poll(100,TimeUnit.MILLISECONDS);
                    if(e!=null){
                        if(e.error!=null)throw e.error;
                        if(e.kind==0&&!inputDone){
                            ByteBuffer in=codec.getInputBuffer(e.index);ArrayDeque<MediaCodec.BufferInfo> infos=new ArrayDeque<>();int offset=0;
                            for(int p=0;p<(batch?32:1);p++){
                                long size=ex.getSampleSize(),time=ex.getSampleTime();MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();
                                if(size<0){info.set(offset,0,0,MediaCodec.BUFFER_FLAG_END_OF_STREAM);infos.add(info);inputDone=true;break;}
                                if(size>in.capacity()-offset){if(offset==0)throw new IOException("音频数据块超过解码器容量");break;}
                                in.clear();int n=ex.readSampleData(in,offset);if(n<0)throw new EOFException("音频读取提前结束");
                                info.set(offset,n,time,0);infos.add(info);offset+=n;ex.advance();
                            }
                            if(batch)codec.queueInputBuffers(e.index,infos);else{MediaCodec.BufferInfo i=infos.getFirst();codec.queueInputBuffer(e.index,i.offset,i.size,i.presentationTimeUs,i.flags);}
                        }else if(e.kind==1){
                            try{ByteBuffer data=codec.getOutputBuffer(e.index);for(MediaCodec.BufferInfo i:e.infos){
                                if(i.size>0){ByteBuffer part=data.duplicate();part.position(i.offset);part.limit(i.offset+i.size);sink.accept(part);lastOutput=System.nanoTime();}
                                if((i.flags&MediaCodec.BUFFER_FLAG_END_OF_STREAM)!=0)outputDone=true;
                            }}finally{codec.releaseOutputBuffer(e.index,false);}
                        }else if(e.kind==2){MediaFormat f=e.format;sink.configure(f.getInteger(MediaFormat.KEY_SAMPLE_RATE),f.getInteger(MediaFormat.KEY_CHANNEL_COUNT),f.containsKey(MediaFormat.KEY_PCM_ENCODING)?f.getInteger(MediaFormat.KEY_PCM_ENCODING):AudioFormat.ENCODING_PCM_16BIT);}
                    }
                    if(System.nanoTime()-lastOutput>60_000_000_000L)throw new IOException("音频解码长时间没有输出，请换用 WAV 或 M4A 重试。");
                }
            }
            sink.finish();progress.update(sink.elapsed);
            if(sink.elapsed==0)throw new IOException("录音为空，没有可转写的声音。");
        }finally{
            if(codec!=null){try{codec.stop();}catch(Exception ignored){}codec.release();}
            boolean interrupted=Thread.interrupted();
            try{
                if(callbacks!=null){callbacks.quitSafely();try{callbacks.join(2000);}catch(InterruptedException e){interrupted=true;}}
                try{if(sink!=null)sink.close();}finally{ex.release();}
            }finally{if(interrupted)Thread.currentThread().interrupt();}
        }
    }
    private static MediaCodec.BufferInfo copy(MediaCodec.BufferInfo i){MediaCodec.BufferInfo o=new MediaCodec.BufferInfo();o.set(i.offset,i.size,i.presentationTimeUs,i.flags);return o;}
    private static final class Event{
        final int kind,index;final ArrayDeque<MediaCodec.BufferInfo> infos;final MediaFormat format;final Exception error;
        Event(int k,int i,ArrayDeque<MediaCodec.BufferInfo>b,MediaFormat f,Exception e){kind=k;index=i;infos=b;format=f;error=e;}
    }
    static final class Sink implements Closeable{
        final File dir;final Progress progress;final Chunks chunks;final byte[] bytes=new byte[65536];
        byte[] inputBytes=new byte[262144];
        PcmWav wav;File current;int rate,channels,encoding,used,index;long count,energy,lastReport;double elapsed;
        Sink(File d,int r,int c,int e,Progress p,Chunks out)throws Exception{dir=d;progress=p;chunks=out;configure(r,c,e);}
        void configure(int r,int c,int e)throws Exception{
            if(r<8000||r>192000||c<1||c>32)throw new IOException("不支持的音频采样率或声道数");
            if(e!=AudioFormat.ENCODING_PCM_16BIT&&e!=AudioFormat.ENCODING_PCM_FLOAT)throw new IOException("不支持的 PCM 编码："+e);
            if(wav!=null&&(r!=rate||c!=channels||e!=encoding))finish();rate=r;channels=c;encoding=e;
        }
        void accept(ByteBuffer b)throws Exception{
            b.order(ByteOrder.LITTLE_ENDIAN);int stride=channels*(encoding==AudioFormat.ENCODING_PCM_FLOAT?4:2);
            if(b.remaining()%stride!=0)throw new IOException("PCM 音频帧未对齐");
            if(encoding==AudioFormat.ENCODING_PCM_16BIT){
                // Copy a codec buffer once. DirectByteBuffer.getShort per channel
                // otherwise crosses Android's memory-access boundary millions of times.
                int length=b.remaining();if(inputBytes.length<length)inputBytes=new byte[length];b.get(inputBytes,0,length);
                for(int i=0;i<length;){int sum=0;for(int ch=0;ch<channels;ch++,i+=2)sum+=(short)((inputBytes[i]&255)|(inputBytes[i+1]<<8));writeSample(sum/channels);}
            }else{
                while(b.remaining()>=stride){double sum=0;for(int ch=0;ch<channels;ch++)sum+=b.getFloat()*32767;writeSample((int)Math.max(-32768,Math.min(32767,sum/channels)));}
            }
            if(SystemClock.elapsedRealtime()-lastReport>500){progress.check();progress.update(elapsed+(double)count/rate);lastReport=SystemClock.elapsedRealtime();}
            if(elapsed+(double)count/rate>5400)throw new IOException("录音超过 90 分钟，请分开处理。");
        }
        void writeSample(int s)throws Exception{
            if(wav==null){current=new File(dir,String.format(Locale.ROOT,"chunk-%04d.wav",index++));wav=new PcmWav(current,rate);}
            bytes[used++]=(byte)s;bytes[used++]=(byte)(s>>8);count++;energy+=(long)s*s;
            if(used==bytes.length)flush();if(count>=rate*30L)finish();
        }
        void flush()throws IOException{if(used>0){wav.write(bytes,0,used);used=0;}}
        void finish()throws Exception{
            if(wav==null)return;flush();wav.close();wav=null;double d=count/(double)rate,rms=Math.sqrt(energy/(double)count);
            Chunk c=new Chunk(current,elapsed,d,rms);elapsed+=d;count=0;energy=0;progress.update(elapsed);chunks.ready(c);
        }
        public void close()throws IOException{if(wav!=null){wav.close();wav=null;}}
    }
}
