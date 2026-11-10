package win.youhoo.moss;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.*;
import android.system.Os;
import android.system.OsConstants;
import org.json.*;
import java.io.*;
import java.lang.Process;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ArrayBlockingQueue;

public final class TranscribeService extends Service {
    static volatile boolean busy=false;
    static volatile String status="选择一段录音，开始本地转写。", result="", backend="cpu";
    static volatile JSONObject document=null;
    static volatile int progress=0;
    static volatile String phase="idle", taskTitle="", previewJson="[]";
    static volatile String draftJson="null";
    static volatile double totalSeconds=0, processedSeconds=0;
    static volatile long startedAt=0;
    static volatile int totalChunks=0, currentChunk=0;
    static volatile String recordId="", sourceUri="";
    static volatile JSONObject thermal=new JSONObject();
    static volatile boolean liveMode=false,recording=false;
    static volatile double recordedSeconds=0,captureLevel=0,decodedSeconds=0;
    static volatile String decoderMode="";
    private volatile boolean finishRequested,producerDone;
    private volatile Exception producerError;
    private MicrophoneCapture microphone;
    private Thread producer;
    private BufferedWriter commands;
    private long firstChunkMs,firstTextMs,lastCheckpoint,modelReadyMs;
    private String lastLivePayload="";
    private int engineStarts;
    private volatile boolean cancelled=false;
    private volatile Process process;
    private volatile int enginePid=0;
    private Thread worker;
    private PowerManager.WakeLock wake;
    private long began;
    private long notificationAt=0;
    private ThermalMonitor thermalMonitor;
    private static final int ENGINE_THREADS=4;
    static File model(Context c){return new File(c.getFilesDir(),"models/moss-q8.gguf");}
    static boolean modelReady(Context c){return model(c).length()==AsrModelFile.BYTES;}
    static String normalizedBackend(String value){return "vulkan".equals(value)?"vulkan":"opencl".equals(value)?"opencl":"cpu";}
    static String backendLabel(){return "vulkan".equals(backend)?"Vulkan GPU":"opencl".equals(backend)?"OpenCL GPU":"CPU";}
    static File history(Context c){File d=new File(c.getFilesDir(),"history");d.mkdirs();return d;}
    public IBinder onBind(Intent i){return null;}
    public void onCreate(){super.onCreate();NotificationChannel ch=new NotificationChannel("transcribe","本地转写",NotificationManager.IMPORTANCE_LOW);getSystemService(NotificationManager.class).createNotificationChannel(ch);}
    private Notification notification(String text){
        PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop=PendingIntent.getService(this,1,new Intent(this,TranscribeService.class).setAction(recording?"finishRecording":"cancel"),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this,"transcribe").setSmallIcon(R.drawable.ic_moss).setContentTitle("燿识·声音角色识别")
            .setContentText(text).setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE).setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            .setProgress(100,progress,recording||"decoding".equals(phase))
            .addAction(new Notification.Action.Builder(null,recording?"结束录音":"停止转写",stop).build()).build();
    }
    public int onStartCommand(Intent intent,int flags,int id){
        if(intent==null){stopSelf();return START_NOT_STICKY;}
        if("cancel".equals(intent.getAction())){cancel();return START_NOT_STICKY;}
        if("finishRecording".equals(intent.getAction())){finishRequested=true;if(microphone!=null)microphone.stop();return START_NOT_STICKY;}
        if(busy)return START_NOT_STICKY;
        if(SpeakerMatchService.busy){stopSelf();return START_NOT_STICKY;}
        busy=true;cancelled=false;progress=0;result="";document=null;began=SystemClock.elapsedRealtime();
        liveMode="record".equals(intent.getAction());recording=liveMode;finishRequested=false;
        recordedSeconds=0;captureLevel=0;decodedSeconds=0;decoderMode=liveMode?"microphone":"";
        producerDone=false;producerError=null;firstChunkMs=0;firstTextMs=0;lastCheckpoint=0;modelReadyMs=0;engineStarts=0;draftJson="null";lastLivePayload="";
        thermal=new JSONObject();thermalMonitor=new ThermalMonitor(this);
        phase=liveMode?"recording":"decoding";previewJson="[]";totalSeconds=0;processedSeconds=0;totalChunks=0;currentChunk=0;
        startedAt=began;taskTitle=intent.getStringExtra("title");if(taskTitle==null)taskTitle="录音";
        recordId=String.valueOf(System.currentTimeMillis());sourceUri=intent.getData()==null?"":intent.getData().toString();
        backend=normalizedBackend(intent.getStringExtra("backend"));
        status="正在准备录音…";
        try {
            if(Build.VERSION.SDK_INT>=35)startForeground(1,notification(status),ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING|(liveMode?ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE:0));
            else startForeground(1,notification(status));
            wake=((PowerManager)getSystemService(POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"moss:transcribe");wake.acquire(6*60*60*1000L);
        } catch(Exception e){busy=false;recording=false;phase="error";status="启动后台处理失败："+e.getMessage();stopSelf();return START_NOT_STICKY;}
        final Uri uri=intent.getData();final String title=intent.getStringExtra("title");
        worker=new Thread(()->runJob(uri,title),"MossTranscription");worker.start();return START_NOT_STICKY;
    }
    private void publish(String text){status=text;notificationAt=SystemClock.elapsedRealtime();getSystemService(NotificationManager.class).notify(1,notification(text));}
    private void check()throws InterruptedException{if(cancelled||Thread.currentThread().isInterrupted())throw new InterruptedException("用户停止");}
    private void pollThermal(){
        updateCapture();
        thermalMonitor.poll();thermal=thermalMonitor.snapshot(ENGINE_THREADS);
        if(SystemClock.elapsedRealtime()-notificationAt>=2000)publish(status);
    }
    private void checkAndSample()throws InterruptedException{
        check();pollThermal();
    }
    private int childPid(File pidFile,Process child)throws IOException,InterruptedException{
        for(int i=0;i<100;i++){
            check();
            if(pidFile.isFile())try{int pid=Integer.parseInt(Files.readString(pidFile.toPath()).trim());if(pid>1)return pid;}catch(NumberFormatException ignored){}
            if(!child.isAlive())break;Thread.sleep(10);
        }
        throw new IOException("推理进程未就绪，请重试。");
    }
    private void terminateEngine(){
        Process child=process;int pid=enginePid;
        if(child==null)return;
        // Preserve prompt cancellation even if the native child is unresponsive.
        if(pid>1&&child.isAlive())try{Os.kill(pid,OsConstants.SIGKILL);}catch(android.system.ErrnoException ignored){}
        child.destroyForcibly();
    }
    private void updateCapture(){
        if(microphone==null)return;
        recordedSeconds=microphone.seconds();totalSeconds=recordedSeconds;captureLevel=microphone.level;
        recording=!microphone.done;totalChunks=totalSeconds>0?1+(int)Math.ceil(Math.max(0,totalSeconds-8)/24):0;
        if(recording)status=String.format(Locale.CHINA,"录音中 · %s · 待转写 %s",stamp(recordedSeconds),stamp(Math.max(0,recordedSeconds-processedSeconds)));
    }
    private void producerCheck()throws Exception{
        check();
    }
    private JSONObject savedDocument(JSONArray segments,boolean partial)throws JSONException{
        JSONObject d=new JSONObject().put("title",taskTitle).put("created",Long.parseLong(recordId)).put("backend",backendLabel())
            .put("recordId",recordId).put("sourceUri",sourceUri).put("audioSeconds",totalSeconds).put("processedSeconds",processedSeconds)
            .put("elapsedSeconds",(SystemClock.elapsedRealtime()-began)/1000.0).put("segments",segments).put("thermal",thermal)
            .put("recorded",liveMode).put("partial",partial)
            .put("pipeline",new JSONObject().put("decoder",decoderMode).put("decodedSeconds",decodedSeconds).put("firstChunkSeconds",firstChunkMs/1000.0)
                .put("firstTextSeconds",firstTextMs/1000.0).put("modelReadySeconds",modelReadyMs/1000.0).put("engineStarts",engineStarts).put("prefetchChunks",2));
        if(microphone!=null&&microphone.error!=null)d.put("recordingError",microphone.error.getMessage());
        return d;
    }
    private void checkpoint(JSONArray segments)throws Exception{
        TranscriptStore.write(new File(history(this),recordId+".json"),savedDocument(segments,true));lastCheckpoint=SystemClock.elapsedRealtime();
    }
    private void startEngine(File job)throws Exception{
        File pidFile=new File(job,"engine.pid");pidFile.delete();new File(job,"ready").delete();
        enginePid=0;
        if(backend.equals("opencl")){
            process=GpuEngineProcess.start(this,backend,model(this),job,ENGINE_THREADS);
        }else{
        ProcessBuilder pb=new ProcessBuilder("/system/bin/sh","-c","printf '%s\\n' \"$$\" > \"$MTD_PID_FILE\"; exec \"$@\"","moss-runner",
            new File(getApplicationInfo().nativeLibraryDir,"libmoss_"+backend+".so").getAbsolutePath(),"serve",model(this).getAbsolutePath(),job.getAbsolutePath());
        pb.environment().put("MTD_DEVICE",backend);pb.environment().put("MTD_THREADS",String.valueOf(ENGINE_THREADS));
        pb.environment().put("MTD_PID_FILE",pidFile.getAbsolutePath());pb.environment().put("TMPDIR",job.getAbsolutePath());
        pb.environment().put("MTD_PROGRESS_FILE",new File(job,"live.json").getAbsolutePath());
        pb.redirectOutput(new File(job,"stdout.log"));pb.redirectError(new File(job,"engine.log"));
        process=pb.start();
        }
        engineStarts++;enginePid=childPid(pidFile,process);
        commands=new BufferedWriter(new OutputStreamWriter(process.getOutputStream(),StandardCharsets.UTF_8));
        waitForEngine(new File(job,"ready"),null,null,0);
        modelReadyMs=SystemClock.elapsedRealtime()-began;
    }
    private void waitForEngine(File expected,JSONArray completed,AudioDecoder.Chunk chunk,int index)throws Exception{
        long started=SystemClock.elapsedRealtime();
        while(!expected.isFile()){
            check();pollThermal();
            if(!process.isAlive())throw new IOException("推理退出（"+process.exitValue()+"），请查看错误详情或切换 CPU 重试。\n"+tail(Files.readString(new File(expected.getParentFile(),"engine.log").toPath()),1400));
            long now=SystemClock.elapsedRealtime();
            phase=recording?"recording":"transcribing";
            if(chunk!=null){readLive(new File(expected.getParentFile(),"live.json"),completed,chunk,index,2);if(firstTextMs==0&&(!previewJson.equals("[]")||!draftJson.equals("null")))firstTextMs=now-began;}
            if(now-started>15*60*1000L)throw new IOException("这一段处理超过 15 分钟，请缩短录音或切换 CPU 重试。");
            Thread.sleep(100);
        }
    }
    private void runJob(Uri uri,String title){
        File job=new File(getCacheDir(),"job-"+recordId);job.mkdirs();
        new File(getFilesDir(),"last-error.txt").delete();
        JSONArray segments=new JSONArray();boolean complete=false;ArrayBlockingQueue<AudioDecoder.Chunk> queue=new ArrayBlockingQueue<>(2);
        try(SpeakerMatcher matcher=new SpeakerMatcher(this,recordId)){
            if(!modelReady(this))throw new IOException("模型缺失或文件不完整，请在设置中下载或导入配套模型。");
            if(liveMode){
                File dir=new File(getFilesDir(),"recordings");dir.mkdirs();
                if(dir.getUsableSpace()<64L*1024*1024)throw new IOException("存储空间不足，请先释放空间");
                microphone=new MicrophoneCapture(new File(dir,recordId+".wav"));sourceUri=Uri.fromFile(microphone.file).toString();
                microphone.start();if(finishRequested)microphone.stop();checkpoint(segments);
                // AudioRecord runs independently while the model/tokenizer/cache load.
                // Cancellation and read-only telemetry also apply during preload.
                checkAndSample();startEngine(job);
            }else{
                producer=new Thread(()->{
                    try{AudioDecoder.decode(this,uri,job,new AudioDecoder.Progress(){
                        public void check()throws Exception{producerCheck();}
                        public void update(double s){decodedSeconds=s;}
                        public void format(double s,String mode){totalSeconds=s;totalChunks=(int)Math.ceil(s/30);decoderMode=mode;}
                    },chunk->{while(!queue.offer(chunk,250,TimeUnit.MILLISECONDS))producerCheck();});
                    totalSeconds=decodedSeconds;totalChunks=(int)Math.ceil(totalSeconds/30);
                    }catch(Exception e){producerError=e;}finally{producerDone=true;}
                },"MossFileDecoder");producer.start();
            }
            long microphoneCursor=0;int index=0;
            while(true){
                checkAndSample();updateCapture();AudioDecoder.Chunk chunk=null;
                if(liveMode){
                    long available=microphone.samples-microphoneCursor,window=microphone.rate*(index==0?8L:24L);
                    if(available>=window||(microphone.done&&available>0)){
                        long count=Math.min(available,window);File f=new File(job,"mic-"+index+".wav");
                        double level=PcmWav.slice(microphone.file,f,microphone.rate,microphoneCursor,count);
                        chunk=new AudioDecoder.Chunk(f,microphoneCursor/(double)microphone.rate,count/(double)microphone.rate,level);microphoneCursor+=count;
                    }else if(microphone.done){if(microphone.error!=null)throw microphone.error;break;}
                    else{if(SystemClock.elapsedRealtime()-lastCheckpoint>5000)checkpoint(segments);Thread.sleep(100);continue;}
                }else{
                    chunk=queue.poll(200,TimeUnit.MILLISECONDS);
                    if(chunk==null){if(producerDone){if(producerError!=null)throw producerError;break;}continue;}
                }
                if(firstChunkMs==0)firstChunkMs=SystemClock.elapsedRealtime()-began;
                currentChunk=index+1;phase=recording?"recording":"transcribing";
                if(!recording)publish(String.format(Locale.CHINA,"转写中 · %s / %s",stamp(processedSeconds),stamp(totalSeconds)));
                JSONArray part=new JSONArray();
                // Only near-digital silence is skipped; original audio/timeline remain intact.
                if(chunk.rms>=20&&chunk.duration>=.25){
                    if(process==null)startEngine(job);
                    File output=new File(job,"response.json");output.delete();new File(job,"live.json").delete();lastLivePayload="";draftJson="null";
                    commands.write(ENGINE_THREADS+" "+chunk.file.getAbsolutePath()+"\n");commands.flush();
                    waitForEngine(output,segments,chunk,index);
                    part=new JSONArray(Files.readString(output.toPath(),StandardCharsets.UTF_8));
                    if(backend.equals("vulkan")&&!Files.readString(new File(job,"engine.log").toPath()).contains("backend: Vulkan"))throw new IOException("未实际启用 Vulkan GPU，请切换 CPU 重试。");
                }
                JSONArray speakerRows=new JSONArray();
                for(int j=0;j<part.length();j++){
                    JSONObject s=part.getJSONObject(j);double start=s.optDouble("start"),end=s.optDouble("end");
                    if(!Double.isFinite(start)||!Double.isFinite(end)||start<0||end<start||start>chunk.duration)continue;
                    s.put("start",chunk.start+start).put("end",chunk.start+Math.min(end,chunk.duration)).put("id","seg_"+(segments.length()+1))
                        .put("speaker","片段"+(index+1)+"-"+s.optString("speaker","S01"));segments.put(s);speakerRows.put(s);
                }
                // The ASR child is idle here; speaker embeddings use one CPU thread.
                draftJson="null";previewJson=segments.toString();checkpoint(segments);
                try{matcher.accept(chunk,speakerRows,this::checkAndSample);}
                catch(InterruptedException e){throw e;}
                catch(Exception e){android.util.Log.e("YaoshiSpeaker","chunk matching",e);TranscriptStore.matchStatus(this,recordId,"error","说话人整理中断，可在记录中重试");}
                if(firstTextMs==0&&segments.length()>0)firstTextMs=SystemClock.elapsedRealtime()-began;
                result=render(segments);previewJson=segments.toString();processedSeconds=chunk.start+chunk.duration;
                progress=totalSeconds>0?Math.min(99,(int)Math.floor(processedSeconds/totalSeconds*100)):0;
                chunk.file.delete();checkpoint(segments);index++;
            }
            complete=true;progress=100;processedSeconds=totalSeconds;phase="complete";status="转写完成，已保存。";
            if(!SpeakerIdentity.object(TranscriptStore.annotations(this,recordId),"speakerMatch").optString("status").equals("error"))TranscriptStore.matchStatus(this,recordId,"complete","");
        }catch(InterruptedException e){phase="cancelled";status=liveMode?"录音和已识别内容已保存。":"已停止，已识别内容已保留。";
        }catch(Exception e){phase="error";status="转写失败："+(e.getMessage()==null?e.getClass().getSimpleName():e.getMessage());
            android.util.Log.e("MossASR","Transcription failed",e);
            try(PrintWriter trace=new PrintWriter(new File(getFilesDir(),"last-error.txt"))){e.printStackTrace(trace);}catch(Exception ignored){}
        }finally{
            boolean interrupted=Thread.interrupted();
            if(microphone!=null){microphone.stop();try{microphone.await();}catch(InterruptedException e){interrupted=true;}updateCapture();}
            recording=false;captureLevel=0;draftJson="null";
            if(producer!=null){producer.interrupt();try{producer.join(5000);}catch(InterruptedException e){interrupted=true;}}
            Process p=process;terminateEngine();
            if(p!=null)try{p.waitFor(5,TimeUnit.SECONDS);}catch(InterruptedException e){interrupted=true;}
            process=null;enginePid=0;
            if(commands!=null)try{commands.close();}catch(Exception ignored){}
            try{File log=new File(job,"engine.log");if(log.isFile())Files.copy(log.toPath(),new File(getFilesDir(),"last-engine.log").toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);}catch(Exception ignored){}
            if(!complete)try{JSONArray liveRows=new JSONArray(previewJson);if(liveRows.length()>segments.length())segments=liveRows;}catch(Exception ignored){}
            if(complete||segments.length()>0||(microphone!=null&&microphone.file.isFile()))try{
                document=savedDocument(segments,!complete);previewJson=segments.toString();result=render(segments);
                TranscriptStore.write(new File(history(this),recordId+".json"),document);
                FileLibrary.enqueue(this,recordId,null);
            }catch(Exception e){phase="error";status="结果保存失败，请立即导出。";}
            if(wake!=null&&wake.isHeld())wake.release();busy=false;stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();
            if(producer==null||!producer.isAlive()){File[] files=job.listFiles();if(files!=null)for(File f:files)f.delete();job.delete();}
            if(interrupted)Thread.currentThread().interrupt();
        }
    }

    private void readLive(File live,JSONArray completed,AudioDecoder.Chunk chunk,int index,int count){
        if(!live.isFile())return;
        try{
            String payload=Files.readString(live.toPath(),StandardCharsets.UTF_8);
            if(payload.equals(lastLivePayload))return;
            JSONArray partial;JSONObject draft=null;
            if(payload.startsWith("["))partial=new JSONArray(payload);
            else{
                JSONObject snapshot=new JSONObject(payload);partial=snapshot.getJSONArray("segments");
                draft=LivePreview.draft(snapshot.optString("transcript"),chunk.start,chunk.duration,index);
            }
            JSONArray rows=new JSONArray(completed.toString());double latest=chunk.start;
            for(int i=0;i<partial.length();i++){
                JSONObject row=partial.getJSONObject(i);
                double start=row.getDouble("start"),end=row.getDouble("end");
                if(!Double.isFinite(start)||!Double.isFinite(end)||start<0||end<start||start>chunk.duration)continue;
                end=Math.min(end,chunk.duration);row.put("start",chunk.start+start);row.put("end",chunk.start+end);
                row.put("id","seg_"+(rows.length()+1));
                if(count>1)row.put("speaker","片段"+(index+1)+"-"+row.optString("speaker","S01"));
                rows.put(row);latest=Math.max(latest,chunk.start+end);
            }
            draftJson=draft==null?"null":draft.toString();previewJson=rows.toString();result=render(rows);lastLivePayload=payload;
            processedSeconds=Math.max(processedSeconds,Math.min(totalSeconds,latest));
            if(totalSeconds>0)progress=Math.max(progress,Math.min(99,(int)Math.floor(processedSeconds/totalSeconds*100)));
        }catch(Exception ignored){/* A snapshot may not exist until the first token. */}
    }
    private static String tail(String s,int n){return s.length()>n?s.substring(s.length()-n):s;}
    static String stamp(double sec){long s=Math.max(0,(long)sec);return String.format(Locale.ROOT,"%02d:%02d:%02d",s/3600,s/60%60,s%60);}
    static String render(JSONArray rows)throws JSONException{StringBuilder b=new StringBuilder();for(int i=0;i<rows.length();i++){
        JSONObject s=rows.getJSONObject(i);b.append(stamp(s.optDouble("start"))).append("  ").append(s.optString("speaker")).append('\n').append(s.optString("text")).append("\n\n");}return b.toString();}
    private void cancel(){cancelled=true;if(microphone!=null)microphone.stop();if(producer!=null)producer.interrupt();terminateEngine();if(worker!=null)worker.interrupt();if(!busy)stopSelf();}
    public void onTimeout(int startId,int type){cancel();stopSelf();}
    public void onDestroy(){cancel();if(wake!=null&&wake.isHeld())wake.release();super.onDestroy();}
}
