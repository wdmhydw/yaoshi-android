package win.youhoo.moss;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.security.*;
import java.util.regex.*;

/** Stage, verify and atomically activate the pinned MOSS speech model. */
final class AsrModelFile {
    static final long BYTES = 986881024L;
    static final String SHA256 = "ed6c35d0d527c5d03171c3eb448e2150a42c76a51e3e73aa821e351c3da8307c";
    static final String URL_Q8 = "https://huggingface.co/mudler/moss-transcribe.cpp-gguf/resolve/"
            + "54e4bbd17da3f84adf1c1bcf7791b9b9266f741e/moss-transcribe-q8_0.gguf";
    private static final Pattern RANGE = Pattern.compile("bytes ([0-9]+)-([0-9]+)/([0-9]+)");
    interface Progress { void update(long bytes, boolean verifying) throws IOException; }
    static File partial(File model){return new File(model.getParentFile(),model.getName()+".download");}

    private static void space(File dir,long bytes)throws IOException{
        long free=dir.getUsableSpace();
        if(free>0&&free<bytes)throw new IOException("存储空间不足，请释放空间后重试");
    }
    private static void commit(File tmp,File model,long size,String hash,Progress progress)throws IOException{
        progress.update(0,true);
        if(tmp.length()!=size)throw new EOFException("文件不完整，请重试");
        MessageDigest digest;
        try{digest=MessageDigest.getInstance("SHA-256");}catch(NoSuchAlgorithmException e){throw new AssertionError(e);}
        byte[] buf=new byte[256*1024];long done=0;
        try(InputStream input=new FileInputStream(tmp)){
            int len;while((len=input.read(buf))!=-1){digest.update(buf,0,len);done+=len;progress.update(done,true);}
        }
        StringBuilder actual=new StringBuilder(64);
        for(byte b:digest.digest()){actual.append(Character.forDigit((b>>4)&15,16));actual.append(Character.forDigit(b&15,16));}
        if(!hash.contentEquals(actual)){
            Files.delete(tmp.toPath());
            throw new IOException("模型校验失败，请重新下载或选择配套 Q8_0 文件");
        }
        Files.move(tmp.toPath(),model.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
    }
    private static long copy(InputStream input,File file,long offset,long limit,Progress progress)throws IOException{
        byte[] buf=new byte[256*1024];long done=offset;
        try(FileOutputStream output=new FileOutputStream(file,offset>0)){
            int n;while((n=input.read(buf))!=-1){
                if(n>limit-done)throw new IOException("模型数据超过预期大小");
                output.write(buf,0,n);done+=n;progress.update(done,false);
            }
        }
        return done;
    }
    static void importFile(InputStream input,File model,Progress progress)throws IOException{
        Files.createDirectories(model.toPath().getParent());
        File temp=new File(model.getParentFile(),"import.part");
        try{
            Files.deleteIfExists(temp.toPath());space(model.getParentFile(),BYTES);
            copy(input,temp,0,BYTES,progress);commit(temp,model,BYTES,SHA256,progress);
            // An activated local copy supersedes any paused download of this same file.
            partial(model).delete();
        }finally{Files.deleteIfExists(temp.toPath());}
    }
    static void download(File model,Progress progress)throws IOException{
        download(new URL(URL_Q8),model,BYTES,SHA256,progress);
    }
    static void download(URL origin,File model,long size,String hash,Progress progress)throws IOException{
        Files.createDirectories(model.toPath().getParent());
        File tmp=partial(model);
        if(tmp.length()>size)Files.delete(tmp.toPath());
        long offset=tmp.length();
        while(offset<size){
            progress.update(offset,false);space(model.getParentFile(),size-offset);
            HttpURLConnection con=open(origin,offset);
            try{
                int status=con.getResponseCode();long end=size;
                if(status==HttpURLConnection.HTTP_PARTIAL){
                    Matcher m=RANGE.matcher(String.valueOf(con.getHeaderField("Content-Range")));
                    if(!m.matches()||Long.parseLong(m.group(1))!=offset||Long.parseLong(m.group(3))!=size)
                        throw new IOException("下载续传位置不匹配，请重试");
                    long last=Long.parseLong(m.group(2));
                    if(last<offset||last>=size)throw new IOException("下载范围无效");
                    end=last+1;
                }else if(status==HttpURLConnection.HTTP_OK){
                    offset=0;
                }else throw new IOException("模型下载服务返回 HTTP "+status);
                long length=con.getContentLengthLong();
                if(length>=0&&length!=end-offset)throw new IOException("模型下载大小与预期不符");
                if(offset==0&&tmp.length()>0)Files.delete(tmp.toPath()); // server declined Range
                space(model.getParentFile(),end-offset);
                try(InputStream in=con.getInputStream()){
                    offset=copy(in,tmp,offset,end,progress);
                }
                if(offset!=end)throw new EOFException("下载中断，可继续下载");
            }catch(NumberFormatException e){throw new IOException("下载范围无效",e);}
            finally{con.disconnect();}
        }
        commit(tmp,model,size,hash,progress);
    }
    private static HttpURLConnection open(URL origin,long offset)throws IOException{
        URL url=origin;
        for(int hop=0;hop<9;hop++){
            if(!url.getProtocol().equals(origin.getProtocol())||url.getUserInfo()!=null)
                throw new IOException("模型下载重定向无效");
            HttpURLConnection con=(HttpURLConnection)url.openConnection();
            try{
                con.setInstanceFollowRedirects(false);con.setUseCaches(false);con.setConnectTimeout(18000);con.setReadTimeout(25000);
                con.setRequestProperty("Accept-Encoding","identity");
                if(offset>0)con.setRequestProperty("Range","bytes="+offset+"-");
                int status=con.getResponseCode();
                if(status!=301&&status!=302&&status!=303&&status!=307&&status!=308)return con;
                String next=con.getHeaderField("Location");
                if(next==null)throw new IOException("模型下载缺少重定向地址");
                url=new URL(url,next);
            }catch(IOException|RuntimeException e){con.disconnect();throw e;}
            con.disconnect();
        }
        throw new IOException("模型下载重定向次数过多");
    }
}
