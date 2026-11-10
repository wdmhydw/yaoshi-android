package win.youhoo.moss;
import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;

/** Verify durable audio bytes, exact slice offsets and header lengths. */
public final class PcmWavTest {
    private static int checks;
    static void check(boolean value){if(!value)throw new AssertionError("check "+checks);checks++;}
    public static void main(String[]args)throws Exception{
        Path dir=Files.createTempDirectory("yaoshi-pcm-test");File full=dir.resolve("full.wav").toFile(),part=dir.resolve("part.wav").toFile();
        byte[] pcm=new byte[120002];new Random(2048).nextBytes(pcm);
        try{
            try(PcmWav out=new PcmWav(full,16000)){
                out.write(pcm,0,64000);out.checkpoint();byte[] during=Files.readAllBytes(full.toPath());
                check(ByteBuffer.wrap(during).order(ByteOrder.LITTLE_ENDIAN).getInt(40)==64000);
                check(Arrays.equals(Arrays.copyOfRange(during,44,during.length),Arrays.copyOf(pcm,64000)));
                out.write(pcm,64000,pcm.length-64000);check(out.samples()==60001);
            }
            byte[] bytes=Files.readAllBytes(full.toPath());ByteBuffer header=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            check(bytes.length==pcm.length+44);check(header.getInt(24)==16000);check(header.getInt(40)==pcm.length);
            check(Arrays.equals(pcm,Arrays.copyOfRange(bytes,44,bytes.length)));
            double rms=PcmWav.slice(full,part,16000,127,33001);
            byte[] slice=Files.readAllBytes(part.toPath());check(slice.length==66002+44);
            check(Arrays.equals(Arrays.copyOfRange(pcm,254,254+66002),Arrays.copyOfRange(slice,44,slice.length)));
            check(rms>1000&&rms<32768);
            try(PcmWav out=new PcmWav(part,48000)){}check(part.length()==44);
            boolean eof=false;try{PcmWav.slice(full,part,16000,60000,2);}catch(EOFException expected){eof=true;}check(eof);
            System.out.println(checks+" PCM persistence checks passed");
        }finally{Files.deleteIfExists(full.toPath());Files.deleteIfExists(part.toPath());Files.deleteIfExists(dir);}
    }
}
