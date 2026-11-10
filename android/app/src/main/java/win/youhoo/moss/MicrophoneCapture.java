package win.youhoo.moss;

import android.content.Context;
import android.media.*;
import android.os.*;
import java.io.*;

/** The capture thread only saves PCM; inference work never blocks it. */
final class MicrophoneCapture {
    final File file;
    volatile long samples;
    volatile int rate=16000;
    volatile boolean done,stopRequested;
    volatile Exception error;
    volatile double level;
    private volatile AudioRecord recorder;
    private Thread thread;
    MicrophoneCapture(File f){file=f;}
    void start(){thread=new Thread(this::capture,"MossMicrophone");thread.start();}
    private void capture(){
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO);
        try{
            for(int r:new int[]{16000,48000}){
                int min=AudioRecord.getMinBufferSize(r,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT);
                if(min<=0)continue;
                AudioRecord next=new AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                    .setAudioFormat(new AudioFormat.Builder().setSampleRate(r).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build())
                    .setBufferSizeInBytes(Math.max(min*4,r*2)).build();
                if(next.getState()==AudioRecord.STATE_INITIALIZED){recorder=next;rate=r;break;}next.release();
            }
            if(recorder==null)throw new IOException("麦克风初始化失败，请检查是否被其他应用占用");
            try(PcmWav out=new PcmWav(file,rate)){
                byte[] b=new byte[rate/5*2];long checkpoint=0,quietSince=SystemClock.elapsedRealtime();
                recorder.startRecording();
                if(recorder.getRecordingState()!=AudioRecord.RECORDSTATE_RECORDING)throw new IOException("麦克风未开始录音");
                while(!stopRequested&&samples<rate*5400L){
                    int n=recorder.read(b,0,b.length,AudioRecord.READ_BLOCKING);
                    if(n<0){if(stopRequested)break;throw new IOException("麦克风读取中断（"+n+"）");}
                    if(n==0){if(SystemClock.elapsedRealtime()-quietSince>5000)throw new IOException("麦克风长时间没有数据");continue;}quietSince=SystemClock.elapsedRealtime();
                    AudioRecordingConfiguration config=recorder.getActiveRecordingConfiguration();
                    if(config!=null&&config.isClientSilenced())throw new IOException("系统暂停了麦克风，请检查麦克风开关或其他通话应用");
                    out.write(b,0,n);samples=out.samples();long energy=0;
                    for(int i=0;i<n;i+=2){int s=(short)((b[i]&255)|(b[i+1]<<8));energy+=(long)s*s;}level=Math.sqrt(energy/(double)(n/2))/32768;
                    if(samples-checkpoint>=rate){out.checkpoint();checkpoint=samples;if(file.getUsableSpace()<32L*1024*1024)throw new IOException("存储空间不足，录音已保存");}
                }
            }
        }catch(Exception e){error=e;}
        finally{if(recorder!=null){try{recorder.stop();}catch(Exception ignored){}recorder.release();recorder=null;}done=true;level=0;}
    }
    void stop(){stopRequested=true;}
    void await()throws InterruptedException{
        if(thread==null)return;thread.join(2000);
        if(thread.isAlive()){AudioRecord r=recorder;if(r!=null)try{r.stop();}catch(Exception ignored){}thread.join(3000);}
    }
    double seconds(){return samples/(double)rate;}
}
