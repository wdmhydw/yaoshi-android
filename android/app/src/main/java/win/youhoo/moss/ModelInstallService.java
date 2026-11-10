package win.youhoo.moss;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.*;
import java.io.*;

/** A user-initiated transfer outlives its Activity; a partial download is resumable. */
public final class ModelInstallService extends Service {
    private static final int NOTIFICATION=73;
    private static final String CHANNEL="speech-model";
    static volatile boolean busy, downloading;
    static volatile String phase="", message="";
    static volatile int percent;
    private volatile boolean interrupted;
    private Thread worker;
    private long lastUpdate;

    static synchronized void request(Context context,Uri source){
        if(busy||TranscribeService.busy)return;
        if(source==null&&TranscribeService.modelReady(context))return;
        downloading=source==null;phase=downloading?"下载模型":"导入模型";message="";percent=0;busy=true;
        Intent job=new Intent(context,ModelInstallService.class).setAction(downloading?"download":"import");
        if(source!=null)job.setData(source).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try{context.startForegroundService(job);}
        catch(RuntimeException e){busy=false;message="未能启动模型传输，请重新打开应用后重试";throw e;}
    }
    static void pause(Context c){
        if(busy&&downloading)c.startService(new Intent(c,ModelInstallService.class).setAction("pause"));
    }
    @Override public void onCreate(){
        super.onCreate();
        getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel(CHANNEL,"本地模型",NotificationManager.IMPORTANCE_LOW));
    }
    private Notification notification(){
        PendingIntent app=PendingIntent.getActivity(this,NOTIFICATION,new Intent(this,MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b=new Notification.Builder(this,CHANNEL).setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("燿识 · "+phase).setContentText(percent+"%").setContentIntent(app)
                .setProgress(100,percent,false).setOnlyAlertOnce(true).setOngoing(true);
        if(downloading){
            PendingIntent stop=PendingIntent.getService(this,NOTIFICATION,new Intent(this,ModelInstallService.class).setAction("pause"),PendingIntent.FLAG_IMMUTABLE);
            b.addAction(new Notification.Action.Builder(null,"暂停",stop).build());
        }
        return b.build();
    }
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        if(intent==null||"pause".equals(intent.getAction())){
            interrupted=true;if(worker==null)stopSelf();return START_NOT_STICKY;
        }
        if(worker!=null)return START_NOT_STICKY;
        Uri source=intent.getData();downloading=source==null;busy=true;interrupted=false;
        phase=downloading?"下载模型":"导入模型";message="";
        try{startForeground(NOTIFICATION,notification(),ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);}
        catch(RuntimeException e){busy=false;message="系统未允许后台下载，请返回应用重试";stopSelf();return START_NOT_STICKY;}
        worker=new Thread(()->transfer(source),"YaoshiModelInstall");worker.start();
        return START_NOT_STICKY;
    }
    private void transfer(Uri source){
        File dest=TranscribeService.model(this);
        String result;
        try{
            if(source==null)AsrModelFile.download(dest,this::progress);
            else try(InputStream in=getContentResolver().openInputStream(source)){
                if(in==null)throw new IOException("无法读取模型文件");
                AsrModelFile.importFile(in,dest,this::progress);
            }
            result="模型校验通过，已启用";
        }catch(Exception e){
            if(interrupted)result="下载已暂停，可继续下载";
            else if(e instanceof java.net.SocketTimeoutException||e instanceof java.net.UnknownHostException||e instanceof java.net.ConnectException)
                result="无法连接模型发布站，请检查网络后继续下载";
            else result="模型未安装："+(e.getLocalizedMessage()==null?"请重试":e.getLocalizedMessage());
        }
        final String status=result;
        new Handler(Looper.getMainLooper()).post(()->{
            message=status;busy=false;worker=null;stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();
        });
    }
    private void progress(long bytes,boolean verifying)throws IOException{
        if(interrupted)throw new InterruptedIOException();
        phase=verifying?"校验模型":downloading?"下载模型":"导入模型";
        percent=(int)(100*bytes/AsrModelFile.BYTES);
        long now=SystemClock.elapsedRealtime();
        if(now-lastUpdate>=900){lastUpdate=now;getSystemService(NotificationManager.class).notify(NOTIFICATION,notification());}
    }
    @Override public void onTimeout(int id,int type){interrupted=true;stopSelf();}
    @Override public void onDestroy(){interrupted=true;super.onDestroy();}
    @Override public IBinder onBind(Intent intent){return null;}
}
