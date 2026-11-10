package win.youhoo.moss;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.*;
import org.json.*;
import java.io.*;
import java.nio.file.Files;

/** Revisit an existing recording without rerunning speech recognition. */
public final class SpeakerMatchService extends Service {
    static volatile boolean busy;
    static volatile String record="",status="";
    static volatile String pending="";
    static volatile int progress;
    static volatile long revision;
    private volatile boolean cancelled;private Thread worker;private PowerManager.WakeLock wake;
    private long notified;
    public IBinder onBind(Intent i){return null;}
    public void onCreate(){super.onCreate();getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel("speakers","说话人整理",NotificationManager.IMPORTANCE_LOW));}
    private Notification notification(){
        PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop=PendingIntent.getService(this,3,new Intent(this,SpeakerMatchService.class).setAction("stop"),PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this,"speakers").setSmallIcon(R.drawable.ic_moss).setContentTitle("燿识 · 说话人整理").setContentText(status).setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).setProgress(100,progress,false)
            .addAction(new Notification.Action.Builder(null,"停止整理",stop).build()).build();
    }
    public int onStartCommand(Intent i,int flags,int startId){
        if(i==null){stopSelf();return START_NOT_STICKY;}
        if("stop".equals(i.getAction())){cancelled=true;if(worker!=null)worker.interrupt();return START_NOT_STICKY;}
        pending="";
        if(busy)return START_NOT_STICKY;
        if(TranscribeService.busy){stopSelf();return START_NOT_STICKY;}
        record=i.getStringExtra("record");if(record==null||!record.matches("[0-9]+(-partial)?")){stopSelf();return START_NOT_STICKY;}
        busy=true;cancelled=false;progress=0;status="正在匹配说话人";
        try{startForeground(2,notification(),ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING);
            wake=getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"moss:speakers");wake.acquire(2*60*60*1000L);
        }catch(Exception e){busy=false;status=e.getMessage();stopSelf();return START_NOT_STICKY;}
        worker=new Thread(this::run,"YaoshiSpeakerMatching");worker.start();return START_NOT_STICKY;
    }
    private void gate()throws Exception{
        if(cancelled||Thread.currentThread().isInterrupted())throw new InterruptedException();
        status="正在匹配说话人";notifyProgress();
    }
    private void notifyProgress(){if(SystemClock.elapsedRealtime()-notified>1000){getSystemService(NotificationManager.class).notify(2,notification());notified=SystemClock.elapsedRealtime();}}
    private static final class Finished extends Exception {}
    private void run(){
        File dir=new File(getCacheDir(),"speakers-"+record);dir.mkdirs();String end="complete",error="";long began=SystemClock.elapsedRealtime();
        try(SpeakerMatcher matcher=new SpeakerMatcher(this,record)){
            JSONObject raw=new JSONObject(Files.readString(new File(TranscribeService.history(this),record+".json").toPath()));JSONArray rows=raw.getJSONArray("segments");
            String uri=raw.optString("sourceUri");if(uri.isEmpty())throw new IOException("请关联原音频后重试");
            double last=0;for(int n=0;n<rows.length();n++)last=Math.max(last,rows.getJSONObject(n).optDouble("end"));final double until=last;
            TranscriptStore.matchStatus(this,record,"running","");revision++;
            try{AudioDecoder.decode(this,Uri.parse(uri),dir,new AudioDecoder.Progress(){
                public void check()throws Exception{gate();}
                public void update(double s){progress=(int)Math.min(99,until>0?s/until*100:0);}
            },chunk->{try{gate();matcher.accept(chunk,rows,this::gate);revision++;}finally{chunk.file.delete();}if(chunk.start+chunk.duration>=until)throw new Finished();});}
            catch(Finished done){/* Every transcript timestamp has been visited. */}
            gate();progress=100;status="说话人已整理";
        }catch(InterruptedException e){end="paused";status="整理已停止";}
        catch(Exception e){end="error";error=e.getMessage()==null?"请关联原音频后重试":e.getMessage();status=error;android.util.Log.e("YaoshiSpeaker","matching",e);}
        finally{
            try{TranscriptStore.matchStatus(this,record,end,error);revision++;}catch(Exception e){android.util.Log.e("YaoshiSpeaker","save",e);}
            android.util.Log.i("YaoshiSpeaker","record="+record+" status="+end+" elapsedMs="+(SystemClock.elapsedRealtime()-began));
            if(wake!=null&&wake.isHeld())wake.release();busy=false;stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();
            File[] files=dir.listFiles();if(files!=null)for(File f:files)f.delete();dir.delete();
        }
    }
    public void onTimeout(int id,int type){cancelled=true;if(worker!=null)worker.interrupt();}
    public void onDestroy(){cancelled=true;if(worker!=null)worker.interrupt();if(wake!=null&&wake.isHeld())wake.release();super.onDestroy();}
}
