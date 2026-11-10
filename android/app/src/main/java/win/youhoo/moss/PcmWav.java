package win.youhoo.moss;

import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;

/** PCM16 WAV with recoverable headers and constant-size write buffers. */
final class PcmWav implements Closeable {
    private final RandomAccessFile out;
    final int rate;
    private long samples;
    PcmWav(File file,int sampleRate)throws IOException{
        rate=sampleRate;out=new RandomAccessFile(file,"rw");out.setLength(0);out.write(new byte[44]);checkpoint();
    }
    void write(byte[] bytes,int offset,int length)throws IOException{
        if((length&1)!=0)throw new IOException("PCM 数据未对齐");
        out.write(bytes,offset,length);samples+=length/2;
    }
    long samples(){return samples;}
    double seconds(){return samples/(double)rate;}
    void checkpoint()throws IOException{long end=out.getFilePointer();out.seek(0);out.write(header(rate,samples));out.seek(Math.max(44,end));}
    static byte[] header(int rate,long samples)throws IOException{
        long length=samples*2;if(length>0xfffffff0L-36)throw new IOException("录音文件过大");
        return ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            .put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt((int)(36+length))
            .put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16).putShort((short)1).putShort((short)1)
            .putInt(rate).putInt(rate*2).putShort((short)2).putShort((short)16)
            .put("data".getBytes(StandardCharsets.US_ASCII)).putInt((int)length).array();
    }
    public void close()throws IOException{try{checkpoint();out.getFD().sync();}finally{out.close();}}
    static double slice(File source,File dest,int rate,long first,long count)throws IOException{
        long energy=0,readSamples=0;
        try(RandomAccessFile in=new RandomAccessFile(source,"r");PcmWav out=new PcmWav(dest,rate)){
            in.seek(44+first*2);byte[] b=new byte[32768];long left=count*2;
            while(left>0){int n=(int)Math.min(left,b.length);in.readFully(b,0,n);
                for(int i=0;i<n;i+=2){int s=(short)((b[i]&255)|(b[i+1]<<8));energy+=(long)s*s;}readSamples+=n/2;
                out.write(b,0,n);left-=n;}
        }
        return readSamples==0?0:Math.sqrt(energy/(double)readSamples);
    }
}
