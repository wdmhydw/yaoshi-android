package win.youhoo.moss;

import android.content.Context;
import com.k2fsa.sherpa.onnx.*;
import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** One CPU thread and bounded excerpts; thermal management belongs to Android. */
final class SpeakerEmbedding implements AutoCloseable {
    static final String MODEL_SHA="1a331345f04805badbb495c775a6ddffcdd1a732567d5ec8b3d5749e3c7a5e4b";
    private final SpeakerEmbeddingExtractor extractor;
    SpeakerEmbedding(Context context)throws Exception{this(install(context));}
    SpeakerEmbedding(File model){extractor=new SpeakerEmbeddingExtractor(SpeakerEmbeddingExtractorConfig.builder().setModel(model.getAbsolutePath()).setNumThreads(1).setDebug(false).setProvider("cpu").build());}
    private static File install(Context c)throws Exception{
        File dir=new File(c.getFilesDir(),"models");dir.mkdirs();File model=new File(dir,"3dspeaker-v1.onnx");
        if(model.isFile()&&model.length()==39593761L&&hash(model).equals(MODEL_SHA))return model;
        File temp=new File(dir,"3dspeaker-v1.part");
        try(InputStream in=c.getAssets().open("speaker/3dspeaker.onnx");OutputStream out=new FileOutputStream(temp)){byte[] b=new byte[65536];int n;while((n=in.read(b))>0)out.write(b,0,n);}
        if(!hash(temp).equals(MODEL_SHA)){temp.delete();throw new IOException("声纹模型校验失败，请重新安装应用");}
        Files.move(temp.toPath(),model.toPath(),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);return model;
    }
    private static String hash(File f)throws Exception{MessageDigest d=MessageDigest.getInstance("SHA-256");try(InputStream in=new FileInputStream(f)){byte[] b=new byte[65536];int n;while((n=in.read(b))>0)d.update(b,0,n);}StringBuilder s=new StringBuilder();for(byte b:d.digest())s.append(String.format(Locale.ROOT,"%02x",b&255));return s.toString();}
    float[] compute(float[] pcm,int rate){
        if(pcm.length<rate*1.2)return new float[0];OnlineStream stream=extractor.createStream();
        try{stream.acceptWaveform(pcm,rate);stream.inputFinished();return extractor.isReady(stream)?SpeakerIdentity.normalized(extractor.compute(stream)):new float[0];}finally{stream.release();}
    }
    float[] excerpt(File wav,List<double[]> intervals)throws IOException{
        try(RandomAccessFile in=new RandomAccessFile(wav,"r")){
            byte[] header=new byte[44];in.readFully(header);ByteBuffer h=ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
            int rate=h.getInt(24);if(h.getShort(20)!=1||h.getShort(22)!=1||h.getShort(34)!=16||rate<8000||rate>192000)throw new IOException("声纹音频格式不匹配");
            long count=(in.length()-44)/2;int limit=rate*6;float[] pcm=new float[limit];int used=0;
            for(double[] span:intervals){
                long start=Math.max(0,Math.min(count,(long)(span[0]*rate))),end=Math.max(start,Math.min(count,(long)(span[1]*rate)));
                int n=(int)Math.min(end-start,limit-used);if(n<=0)continue;byte[] b=new byte[n*2];in.seek(44+start*2);in.readFully(b);
                // Drop near-silent 20 ms frames. Never include another speaker's overlap.
                int frame=Math.max(1,rate/50);
                for(int at=0;at<n;at+=frame){int len=Math.min(frame,n-at);double energy=0;for(int i=at;i<at+len;i++){int s=(short)((b[i*2]&255)|(b[i*2+1]<<8));energy+=(double)s*s;}
                    if(Math.sqrt(energy/len)<70)continue;
                    for(int i=at;i<at+len;i++)pcm[used++]=(short)((b[i*2]&255)|(b[i*2+1]<<8))/32768f;}
                if(used>=limit)break;
            }
            return compute(Arrays.copyOf(pcm,used),rate);
        }
    }
    public void close(){extractor.release();}
}
