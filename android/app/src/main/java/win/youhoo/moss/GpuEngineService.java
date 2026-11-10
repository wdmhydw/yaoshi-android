package win.youhoo.moss;

import android.app.Service;
import android.content.Intent;
import android.os.*;
import java.io.*;
import java.nio.file.Files;

/** A real application process gives vendor GPU libraries the app's linker namespace. */
public final class GpuEngineService extends Service {
    static final int START=IBinder.FIRST_CALL_TRANSACTION;
    private boolean started;
    private static native int runSession(String backend,String model,String job,int input,int threads);
    private final Binder binder=new Binder(){
        @Override protected boolean onTransact(int code,Parcel data,Parcel reply,int flags)throws RemoteException{
            if(code!=START)return super.onTransact(code,data,reply,flags);
            if(Binder.getCallingUid()!=android.os.Process.myUid())throw new SecurityException();
            if(started)throw new IllegalStateException("GPU task already started");
            String backend=data.readString(),model=data.readString(),directory=data.readString();
            int threads=data.readInt();ParcelFileDescriptor input=data.readFileDescriptor();
            try{
                if(!"opencl".equals(backend))throw new IOException("Unknown GPU backend");
                File job=new File(directory).getCanonicalFile();
                if(!job.getParentFile().equals(getCacheDir().getCanonicalFile())||!job.getName().startsWith("job-"))throw new IOException("Invalid job directory");
                if(!new File(model).getCanonicalFile().equals(TranscribeService.model(GpuEngineService.this).getCanonicalFile()))throw new IOException("Invalid model path");
                if(threads<2||threads>4||input==null)throw new IOException("Invalid engine input");
                started=true;
                Files.writeString(new File(job,"engine.pid").toPath(),String.valueOf(android.os.Process.myPid()));
                new Thread(()->{
                    int result=1;
                    try{
                        System.loadLibrary("moss_opencl");
                        result=runSession(backend,model,job.getAbsolutePath(),input.getFd(),threads);
                    }catch(Throwable e){
                        try(PrintWriter log=new PrintWriter(new FileOutputStream(new File(job,"engine.log"),true))){e.printStackTrace(log);}catch(Exception ignored){}
                    }finally{
                        try{input.close();}catch(Exception ignored){}
                        try{Files.writeString(new File(job,"engine.exit").toPath(),String.valueOf(result));}catch(Exception ignored){}
                        // A fresh process per task releases vendor contexts and native global state.
                        android.os.Process.killProcess(android.os.Process.myPid());
                    }
                },"MossGpuInference").start();
                reply.writeNoException();reply.writeInt(android.os.Process.myPid());return true;
            }catch(IOException e){if(input!=null)try{input.close();}catch(IOException ignored){}throw new IllegalArgumentException(e);}
        }
    };
    @Override public IBinder onBind(Intent intent){return binder;}
    @Override public void onDestroy(){super.onDestroy();android.os.Process.killProcess(android.os.Process.myPid());}
}
