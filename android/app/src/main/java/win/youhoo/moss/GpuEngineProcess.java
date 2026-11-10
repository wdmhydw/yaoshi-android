package win.youhoo.moss;

import android.content.*;
import android.os.*;
import android.system.Os;
import android.system.OsConstants;
import java.io.*;
import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Process-compatible pipe/lifecycle adapter for the private GPU service. */
final class GpuEngineProcess extends java.lang.Process {
    private final Context context;
    private final File job;
    private final ParcelFileDescriptor readEnd;
    private final OutputStream input;
    private final CountDownLatch connected=new CountDownLatch(1),exited=new CountDownLatch(1);
    private final Handler main=new Handler(Looper.getMainLooper());
    private volatile int pid,exitCode=137;
    private volatile boolean bound,closed;
    private volatile Throwable error;
    private ServiceConnection connection;

    static GpuEngineProcess start(Context c,String backend,File model,File job,int threads)throws IOException,InterruptedException{
        GpuEngineProcess process=new GpuEngineProcess(c,job);
        try{process.bind(backend,model,threads);return process;}
        catch(IOException|InterruptedException|RuntimeException e){process.destroy();throw e;}
    }
    private GpuEngineProcess(Context c,File directory)throws IOException{
        context=c.getApplicationContext();job=directory;
        ParcelFileDescriptor[] pipe=ParcelFileDescriptor.createPipe();readEnd=pipe[0];input=new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]);
    }
    private void bind(String backend,File model,int threads)throws IOException,InterruptedException{
        connection=new ServiceConnection(){
            @Override public void onServiceConnected(ComponentName name,IBinder service){
                if(closed){releaseBinding();return;}
                Parcel data=Parcel.obtain(),reply=Parcel.obtain();
                try{
                    service.linkToDeath(()->finish(readExit()),0);
                    data.writeString(backend);data.writeString(model.getAbsolutePath());data.writeString(job.getAbsolutePath());data.writeInt(threads);data.writeFileDescriptor(readEnd.getFileDescriptor());
                    if(!service.transact(GpuEngineService.START,data,reply,0))throw new IOException("GPU service rejected start");
                    reply.readException();pid=reply.readInt();
                    if(closed)kill();
                }catch(Exception e){error=e;finish(1);}
                finally{try{readEnd.close();}catch(IOException ignored){}reply.recycle();data.recycle();connected.countDown();}
            }
            @Override public void onServiceDisconnected(ComponentName name){finish(readExit());}
            @Override public void onBindingDied(ComponentName name){finish(readExit());}
            @Override public void onNullBinding(ComponentName name){error=new IOException("GPU service returned no connection");finish(1);}
        };
        bound=context.bindService(new Intent(context,GpuEngineService.class),connection,Context.BIND_AUTO_CREATE|Context.BIND_IMPORTANT);
        if(!bound)throw new IOException("GPU service binding failed");
        if(!connected.await(10,TimeUnit.SECONDS))throw new IOException("GPU service startup timed out");
        if(error!=null)throw new IOException("GPU 服务启动失败："+error.getMessage(),error);
        if(pid<=1||pid==android.os.Process.myPid())throw new IOException("Invalid GPU process");
    }
    private int readExit(){try{return Integer.parseInt(Files.readString(new File(job,"engine.exit").toPath()).trim());}catch(Exception ignored){return 137;}}
    private synchronized void finish(int code){
        if(!closed){closed=true;exitCode=code;exited.countDown();connected.countDown();}
        releaseBinding();
    }
    private void releaseBinding(){main.post(()->{synchronized(this){if(bound){bound=false;try{context.unbindService(connection);}catch(IllegalArgumentException ignored){}}}});}
    private void kill(){if(pid>1&&pid!=android.os.Process.myPid())try{Os.kill(pid,OsConstants.SIGKILL);}catch(android.system.ErrnoException ignored){}}
    @Override public OutputStream getOutputStream(){return input;}
    @Override public InputStream getInputStream(){return new ByteArrayInputStream(new byte[0]);}
    @Override public InputStream getErrorStream(){return new ByteArrayInputStream(new byte[0]);}
    @Override public boolean isAlive(){return !closed;}
    @Override public int exitValue(){if(!closed)throw new IllegalThreadStateException();return exitCode;}
    @Override public int waitFor()throws InterruptedException{exited.await();return exitCode;}
    @Override public boolean waitFor(long timeout,TimeUnit unit)throws InterruptedException{return exited.await(timeout,unit);}
    @Override public void destroy(){kill();finish(137);try{readEnd.close();input.close();}catch(IOException ignored){}}
    @Override public java.lang.Process destroyForcibly(){destroy();return this;}
}
