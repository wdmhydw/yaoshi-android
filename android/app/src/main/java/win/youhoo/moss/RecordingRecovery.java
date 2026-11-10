package win.youhoo.moss;
import android.content.Context;
import android.net.Uri;
import java.io.*;
import java.nio.*;
import java.nio.file.Files;
import org.json.*;

/** Repair only this app's unfinished recordings after process death. Never auto-record. */
final class RecordingRecovery {
    static void recover(Context context){
        if(TranscribeService.busy)return;
        File root=new File(context.getFilesDir(),"recordings");
        File[] records=TranscribeService.history(context).listFiles((d,n)->n.endsWith(".json"));if(records==null)return;
        for(File record:records)try{
            JSONObject doc=new JSONObject(Files.readString(record.toPath()));
            if(!doc.optBoolean("recorded")||!doc.optBoolean("partial"))continue;
            Uri uri=Uri.parse(doc.optString("sourceUri"));if(!"file".equals(uri.getScheme()))continue;
            File file=new File(uri.getPath());if(!file.getCanonicalFile().getParentFile().equals(root.getCanonicalFile())||!file.getName().matches("[0-9]+\\.wav")||file.length()<44)continue;
            try(RandomAccessFile wav=new RandomAccessFile(file,"rw")){
                byte[] h=new byte[44];wav.readFully(h);ByteBuffer b=ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN);
                if(b.getInt(0)!=0x46464952||b.getInt(8)!=0x45564157||b.getShort(20)!=1||b.getShort(22)!=1||b.getShort(34)!=16)continue;
                int rate=b.getInt(24);if(rate!=16000&&rate!=48000)continue;long samples=(wav.length()-44)/2;
                wav.setLength(44+samples*2);wav.seek(0);wav.write(PcmWav.header(rate,samples));
                double seconds=samples/(double)rate;if(Math.abs(doc.optDouble("audioSeconds")-seconds)>.001){doc.put("audioSeconds",seconds);TranscriptStore.write(record,doc);}
            }
        }catch(Exception e){android.util.Log.w("MossRecording","Recording checkpoint recovery",e);}
    }
}
