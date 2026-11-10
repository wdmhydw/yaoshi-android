package win.youhoo.moss;

import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** User-visible files, separate from the private database and model cache. */
final class FileLibrary {
    interface Done { void complete(String error); }
    private interface Input { InputStream open() throws Exception; }
    private static final ExecutorService worker=Executors.newSingleThreadExecutor();
    private static final Object ioLock=new Object();
    private static final String DEFAULT_PATH=Environment.DIRECTORY_DOWNLOADS+"/燿识/";
    private static final AtomicInteger pending=new AtomicInteger();
    private static final Map<String,String> errors=new ConcurrentHashMap<>();
    private static SharedPreferences preferences(Context c){return c.getSharedPreferences("file-library",0);}
    static JSONObject state(Context c)throws JSONException{return new JSONObject().put("label",preferences(c).getString("label","下载/燿识")).put("custom",!preferences(c).getString("tree","").isEmpty()).put("pending",pending.get()).put("error",errors.values().stream().findFirst().orElse(""));}
    static void choose(Context c,Uri tree)throws Exception{
        Uri root=DocumentsContract.buildDocumentUriUsingTree(tree,DocumentsContract.getTreeDocumentId(tree));String name="所选文件夹";
        try(Cursor rows=c.getContentResolver().query(root,new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME,DocumentsContract.Document.COLUMN_MIME_TYPE},null,null,null)){
            if(rows==null||!rows.moveToFirst()||!DocumentsContract.Document.MIME_TYPE_DIR.equals(rows.getString(1)))throw new IOException("请选择文件夹");name=rows.getString(0);
        }
        // Probe before replacing the working setting. An unavailable provider keeps the old folder.
        Uri probe=DocumentsContract.createDocument(c.getContentResolver(),root,"text/plain",".yaoshi-write-check-"+UUID.randomUUID());
        if(probe==null)throw new IOException("文件夹暂时不可写入");
        try(OutputStream out=c.getContentResolver().openOutputStream(probe,"wt")){if(out==null)throw new IOException("文件夹暂时不可写入");out.write(0);}
        finally{DocumentsContract.deleteDocument(c.getContentResolver(),probe);}
        String id=DocumentsContract.getTreeDocumentId(tree);if(id.startsWith("primary:"))name="内部存储/"+id.substring(8);
        if(!preferences(c).edit().putString("tree",tree.toString()).putString("label",name).commit())throw new IOException("保存位置写入失败");
    }
    static void reset(Context c){preferences(c).edit().remove("tree").remove("label").commit();}
    private static File directory(Context c,String name){File f=new File(c.getFilesDir(),name);f.mkdirs();return f;}
    private static File indexFile(Context c,String id)throws IOException{checkId(id);return new File(directory(c,"file-library"),id+".json");}
    private static void checkId(String id)throws IOException{if(!id.matches("[0-9]+(-partial)?"))throw new IOException("记录标识有误");}
    private static JSONObject read(File file)throws Exception{return file.isFile()?new JSONObject(Files.readString(file.toPath(),StandardCharsets.UTF_8)):new JSONObject();}
    private static JSONObject object(JSONObject parent,String key)throws JSONException{JSONObject value=parent.optJSONObject(key);if(value==null){value=new JSONObject();parent.put(key,value);}return value;}
    static void enqueue(Context context,String id,Done done){enqueue(context,id,false,done);}
    static void prune(Context context,String id,Done done){enqueue(context,id,true,done);}
    private static void enqueue(Context context,String id,boolean pruneOnly,Done done){
        Context c=context.getApplicationContext();String token=(pruneOnly?"prune:":"save:")+UUID.randomUUID();File marker=new File(directory(c,"file-library-pending"),id);
        try{checkId(id);Files.writeString(marker.toPath(),token);}catch(Exception e){if(done!=null)done.complete(e.getMessage());return;}
        pending.incrementAndGet();worker.execute(()->{String error="";try{sync(c,id,pruneOnly);if(marker.isFile()&&token.equals(Files.readString(marker.toPath())))marker.delete();errors.remove(id);}catch(Exception e){error=(pruneOnly?"文件删除未完成：":"文件保存未完成：")+message(e);errors.put(id,error);android.util.Log.w("YaoshiFiles",error,e);}finally{pending.decrementAndGet();if(done!=null)done.complete(error);}});
    }
    static void resume(Context context){
        Context c=context.getApplicationContext();worker.execute(()->{
            Set<String> ids=new LinkedHashSet<>();for(String dir:new String[]{"file-library-deleted","minutes","file-library","file-library-pending"}){File[] files=directory(c,dir).listFiles();if(files!=null)for(File f:files){String id=f.getName().replaceFirst("\\.json$","");if(id.matches("[0-9]+(-partial)?"))ids.add(id);}}
            for(String id:ids)try{File marker=new File(directory(c,"file-library-pending"),id);boolean pruneOnly=marker.isFile()?Files.readString(marker.toPath()).startsWith("prune:"):indexFile(c,id).isFile();enqueue(c,id,pruneOnly,null);}catch(Exception e){errors.put(id,"文件恢复未完成："+message(e));}
        });
    }
    static void ensureMinutes(Context context,String id){Context c=context.getApplicationContext();worker.execute(()->{try{
        checkId(id);JSONObject index=read(indexFile(c,id)),targets=index.optJSONObject("targets");JSONArray versions=read(new File(directory(c,"minutes"),id+".json")).optJSONArray("versions");if(versions==null)return;
        for(int i=0;i<versions.length();i++){String key="minutes/"+versions.getJSONObject(i).getString("id");boolean found=false;if(targets!=null)for(Iterator<String> it=targets.keys();it.hasNext();){JSONObject items=targets.getJSONObject(it.next()).optJSONObject("items"),item=items==null?null:items.optJSONObject(key);if(item!=null&&exists(c,Uri.parse(item.getString("uri")))){found=true;break;}}if(!found){enqueue(c,id,null);break;}}
    }catch(Exception e){errors.put(id,"纪要文件检查未完成："+message(e));}});}
    private static String message(Exception e){if(e instanceof SecurityException)return "文件夹访问已失效，请重新选择保存位置";return e.getMessage()==null?e.getClass().getSimpleName():e.getMessage();}
    static String clean(String name){String value=name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]","_").trim().replaceAll("[. ]+$","");if(value.isEmpty())value="录音";return value.codePointCount(0,value.length())>48?value.substring(0,value.offsetByCodePoints(0,48)):value;}
    static String minutesName(JSONObject version){return "纪要-"+new SimpleDateFormat("yyyyMMdd-HHmmss-SSS",Locale.ROOT).format(new Date(version.optLong("created")))+"-"+clean(version.optString("id"))+".md";}
    private static boolean exists(Context c,Uri uri){try(android.os.ParcelFileDescriptor fd=c.getContentResolver().openFileDescriptor(uri,"r")){return fd!=null;}catch(Exception e){return false;}}
    private static boolean documentExists(Context c,Uri uri){try(Cursor rows=c.getContentResolver().query(uri,new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID},null,null,null)){return rows!=null&&rows.moveToFirst();}catch(Exception e){return false;}}
    private static Uri child(Context c,Uri parent,String name)throws Exception{
        Uri children=DocumentsContract.buildChildDocumentsUriUsingTree(parent,DocumentsContract.getDocumentId(parent));
        try(Cursor rows=c.getContentResolver().query(children,new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME,DocumentsContract.Document.COLUMN_MIME_TYPE},null,null,null)){
            if(rows!=null)while(rows.moveToNext())if(name.equals(rows.getString(1))&&DocumentsContract.Document.MIME_TYPE_DIR.equals(rows.getString(2)))return DocumentsContract.buildDocumentUriUsingTree(parent,rows.getString(0));
        }
        Uri made=DocumentsContract.createDocument(c.getContentResolver(),parent,DocumentsContract.Document.MIME_TYPE_DIR,name);if(made==null)throw new IOException("创建录音文件夹失败");return made;
    }
    private static JSONObject target(Context c,JSONObject index,JSONObject doc)throws Exception{
        String tree=preferences(c).getString("tree",""),key=tree.isEmpty()?"downloads":ModelConnections.hash(tree),folder=index.optString("folder");
        if(folder.isEmpty()){folder=clean(doc.optString("title","录音"))+"-"+doc.getString("recordId");index.put("folder",folder);}
        JSONObject value=object(object(index,"targets"),key);value.put("tree",tree).put("label",preferences(c).getString("label","下载/燿识")+"/"+folder);
        if(tree.isEmpty())value.put("path",DEFAULT_PATH+folder+"/");else{
            Uri old=Uri.parse(value.optString("directory",""));if(value.optString("directory").isEmpty()||!documentExists(c,old)){
                Uri root=DocumentsContract.buildDocumentUriUsingTree(Uri.parse(tree),DocumentsContract.getTreeDocumentId(Uri.parse(tree)));value.put("directory",child(c,root,folder).toString());
            }
        }
        index.put("current",key);return value;
    }
    private static void deleteUri(Context c,Uri uri)throws Exception{
        if(DocumentsContract.isDocumentUri(c,uri)){if(!documentExists(c,uri))return;if(!DocumentsContract.deleteDocument(c.getContentResolver(),uri))throw new IOException("删除纪要文件失败");}
        else if(c.getContentResolver().delete(uri,null,null)==0&&exists(c,uri))throw new IOException("删除纪要文件失败");
    }
    private static void pruneMissingVersions(Context c,JSONObject index,Set<String> wanted)throws Exception{
        JSONObject targets=index.optJSONObject("targets");if(targets==null)return;
        for(Iterator<String> all=targets.keys();all.hasNext();){JSONObject items=object(targets.getJSONObject(all.next()),"items");List<String> removed=new ArrayList<>();
            for(Iterator<String> it=items.keys();it.hasNext();){String key=it.next();if(key.startsWith("minutes/")&&!wanted.contains(key.substring(8))){deleteUri(c,Uri.parse(items.getJSONObject(key).getString("uri")));removed.add(key);}}
            for(String key:removed)items.remove(key);
        }
    }
    private static Uri write(Context c,JSONObject index,JSONObject target,String key,String name,String mime,String hash,Input input)throws Exception{
        JSONObject items=object(target,"items"),old=items.optJSONObject(key);if(old!=null&&hash.equals(old.optString("hash"))&&exists(c,Uri.parse(old.getString("uri"))))return Uri.parse(old.getString("uri"));
        ContentResolver resolver=c.getContentResolver();Uri uri=null;boolean media=target.optString("tree").isEmpty(),committed=false;long bytes=0;
        try{
            if(media){ContentValues values=new ContentValues();values.put(MediaStore.MediaColumns.DISPLAY_NAME,name);values.put(MediaStore.MediaColumns.MIME_TYPE,mime);values.put(MediaStore.MediaColumns.RELATIVE_PATH,target.getString("path"));values.put(MediaStore.MediaColumns.IS_PENDING,1);uri=resolver.insert(MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),values);}
            else uri=DocumentsContract.createDocument(resolver,Uri.parse(target.getString("directory")),mime,".saving-"+UUID.randomUUID()+"-"+name);
            if(uri==null)throw new IOException("创建文件失败");
            try(InputStream in=input.open();OutputStream out=resolver.openOutputStream(uri,"wt")){if(in==null||out==null)throw new IOException("打开文件失败");byte[] buffer=new byte[131072];int n;while((n=in.read(buffer))!=-1){out.write(buffer,0,n);bytes+=n;}out.flush();}
            if(media){ContentValues values=new ContentValues();values.put(MediaStore.MediaColumns.IS_PENDING,0);if(resolver.update(uri,values,null,null)==0)throw new IOException("提交文件失败");}
            else {Uri renamed=DocumentsContract.renameDocument(resolver,uri,name);if(renamed==null)throw new IOException("提交文件失败");uri=renamed;}
            if(old!=null){JSONArray retired=index.optJSONArray("retired");if(retired==null){retired=new JSONArray();index.put("retired",retired);}retired.put(old.getString("uri"));}
            items.put(key,new JSONObject().put("uri",uri.toString()).put("name",name).put("mime",mime).put("hash",hash).put("bytes",bytes));
            TranscriptStore.write(indexFile(c,index.getString("recordId")),index);committed=true;cleanRetired(c,index);return uri;
        }catch(Exception e){if(uri!=null&&!committed)try{deleteUri(c,uri);}catch(Exception ignored){}throw e;}
    }
    private static void cleanRetired(Context c,JSONObject index)throws Exception{JSONArray retired=index.optJSONArray("retired");if(retired==null||retired.length()==0)return;for(int i=0;i<retired.length();i++)deleteUri(c,Uri.parse(retired.getString(i)));index.remove("retired");TranscriptStore.write(indexFile(c,index.getString("recordId")),index);}
    private static void text(Context c,JSONObject index,JSONObject target,String key,String name,String mime,String text)throws Exception{
        byte[] data=text.getBytes(StandardCharsets.UTF_8);write(c,index,target,key,name,mime,ModelConnections.hash(text),()->new ByteArrayInputStream(data));
    }
    private static JSONObject document(Context c,String id)throws Exception{
        File file=new File(TranscribeService.history(c),id+".json");if(!file.isFile())return null;return TranscriptStore.decorate(c,id,read(file));
    }
    private static String audioName(Context c,Uri source)throws Exception{
        String name=source.getLastPathSegment();if("content".equals(source.getScheme()))try(Cursor rows=c.getContentResolver().query(source,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)){if(rows!=null&&rows.moveToFirst())name=rows.getString(0);}
        String extension="";if(name!=null&&name.lastIndexOf('.')>=0){String suffix=name.substring(name.lastIndexOf('.')).toLowerCase(Locale.ROOT);if(suffix.matches("\\.[a-z0-9]{1,8}"))extension=suffix;}
        if(extension.isEmpty()){String mime=c.getContentResolver().getType(source);String ext=android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(mime==null?"":mime);if(ext!=null)extension="."+ext;}
        return "原音频"+(extension.isEmpty()?".audio":extension);
    }
    private static void copyAudio(Context c,JSONObject index,JSONObject target,JSONObject doc)throws Exception{
        if(doc.optString("sourceUri").isEmpty())throw new IOException("请关联原音频后重试保存");Uri source=Uri.parse(doc.getString("sourceUri"));String name=audioName(c,source);String identity=source.toString();
        if("file".equals(source.getScheme())){File file=new File(source.getPath());identity+="|"+file.length()+"|"+file.lastModified();}
        else try(android.content.res.AssetFileDescriptor fd=c.getContentResolver().openAssetFileDescriptor(source,"r")){if(fd!=null)identity+="|"+fd.getLength();}
        String mime=c.getContentResolver().getType(source);if(mime==null)mime=android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substring(name.lastIndexOf('.')+1));if(mime==null)mime="application/octet-stream";
        write(c,index,target,"audio",name,mime,ModelConnections.hash(identity),()->c.getContentResolver().openInputStream(source));
    }
    static void sync(Context c,String id,boolean pruneOnly)throws Exception{
        synchronized(ioLock){
            JSONObject index=read(indexFile(c,id)).put("recordId",id);
            File deleted=new File(directory(c,"file-library-deleted"),id+".json");if(deleted.isFile()){purgeRecord(c,id,index,read(deleted));deleted.delete();indexFile(c,id).delete();return;}
            JSONObject doc=document(c,id),minutes=read(new File(directory(c,"minutes"),id+".json"));JSONArray versions=minutes.optJSONArray("versions");if(versions==null)versions=new JSONArray();
            if(doc==null&&!index.has("targets"))return;cleanRetired(c,index);
            Set<String> wanted=new HashSet<>();if(doc!=null)for(int i=0;i<versions.length();i++)wanted.add(versions.getJSONObject(i).getString("id"));
            Exception deletionError=null;try{pruneMissingVersions(c,index,wanted);}catch(Exception e){deletionError=e;}
            TranscriptStore.write(indexFile(c,id),index);if(pruneOnly||doc==null){if(deletionError!=null)throw deletionError;return;}
            JSONObject dest=target(c,index,doc);TranscriptStore.write(indexFile(c,id),index);
            text(c,index,dest,"transcript","转写稿.md","text/markdown",transcript(doc,"MD"));
            for(int i=versions.length()-1;i>=0;i--){
                JSONObject row=versions.getJSONObject(i);
                // Editing creates a new version ID. Keep an existing archived version as written.
                String key="minutes/"+row.getString("id");
                JSONObject saved=object(dest,"items").optJSONObject(key);
                if(saved!=null&&exists(c,Uri.parse(saved.getString("uri"))))continue;
                text(c,index,dest,key,minutesName(row),"text/markdown",MinutesFormat.fileText(row.getString("markdown")));
            }
            // A missing original must not prevent already-generated minutes from being saved.
            copyAudio(c,index,dest,doc);
            if(deletionError!=null)throw deletionError;
        }
    }
    static void export(Context context,JSONObject document,String format,Done done){
        Context c=context.getApplicationContext();pending.incrementAndGet();worker.execute(()->{String error="";try{synchronized(ioLock){String id=document.getString("recordId");if(!new File(TranscribeService.history(c),id+".json").isFile())throw new IOException("这条记录已删除");JSONObject index=read(indexFile(c,id)).put("recordId",id),dest=target(c,index,document);String stamp=new SimpleDateFormat("yyyyMMdd-HHmmss-SSS",Locale.ROOT).format(new Date());String mime=format.equals("JSON")?"application/json":format.equals("SRT")?"application/x-subrip":"text/markdown";text(c,index,dest,"export/"+UUID.randomUUID(),"转写稿-"+stamp+"."+format.toLowerCase(Locale.ROOT),mime,transcript(document,format));}}catch(Exception e){error="导出未完成："+message(e);}finally{pending.decrementAndGet();done.complete(error);}});
    }
    static void exportMinutes(Context context,String id,String markdown,String format,Done done){
        Context c=context.getApplicationContext();
        pending.incrementAndGet();
        worker.execute(()->{
            String error="";
            try{synchronized(ioLock){
                JSONObject doc=document(c,id);
                if(doc==null)throw new IOException("这条记录已删除");
                JSONObject index=read(indexFile(c,id)).put("recordId",id),dest=target(c,index,doc);
                String token=UUID.randomUUID().toString(),stamp=new SimpleDateFormat("yyyyMMdd-HHmmss-SSS",Locale.ROOT).format(new Date());
                String name="导出纪要-"+stamp+"-"+token.substring(0,8),key="export/"+token;
                String fileText=MinutesFormat.fileText(markdown);
                if(format.equals("DOCX")){
                    String title=doc.optString("title").trim();
                    if(title.isEmpty())title="会议纪要";else if(!title.contains("纪要"))title+=" 会议纪要";
                    byte[] bytes=MinutesDocx.encode(title,fileText);
                    write(c,index,dest,key,name+".docx",MinutesDocx.MIME,ModelConnections.hash(bytes),()->new ByteArrayInputStream(bytes));
                }else if(format.equals("MD")){
                    text(c,index,dest,key,name+".md","text/markdown",fileText);
                }else throw new IOException("未知的纪要格式");
            }}catch(Exception e){error="导出未完成："+message(e);}
            finally{pending.decrementAndGet();done.complete(error);}
        });
    }
    static void deleteRecord(Context c,String id)throws Exception{
        checkId(id);JSONObject doc=document(c,id);if(doc==null)throw new IOException("这条记录已删除");File marker=new File(directory(c,"file-library-deleted"),id+".json");
        TranscriptStore.write(marker,new JSONObject().put("sourceUri",doc.optString("sourceUri")).put("recorded",doc.optBoolean("recorded")));
        try{TranscriptStore.delete(c,id);}catch(Exception e){marker.delete();throw e;}enqueue(c,id,true,null);
    }
    private static void purgeRecord(Context c,String id,JSONObject index,JSONObject deleted)throws Exception{
        cleanRetired(c,index);JSONObject targets=index.optJSONObject("targets");if(targets!=null)for(Iterator<String> it=targets.keys();it.hasNext();){JSONObject dest=targets.getJSONObject(it.next()),items=object(dest,"items");for(Iterator<String> keys=items.keys();keys.hasNext();){String key=keys.next();if(!key.startsWith("export/"))deleteUri(c,Uri.parse(items.getJSONObject(key).getString("uri")));}
            if(!dest.optString("directory").isEmpty()){Uri folder=Uri.parse(dest.getString("directory"));if(documentExists(c,folder)){Uri children=DocumentsContract.buildChildDocumentsUriUsingTree(folder,DocumentsContract.getDocumentId(folder));try(Cursor rows=c.getContentResolver().query(children,new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID},null,null,null)){if(rows!=null&&!rows.moveToFirst())DocumentsContract.deleteDocument(c.getContentResolver(),folder);}}}
        }
        // Only remove the app's own microphone source, and only after its last referencing record.
        Uri source=Uri.parse(deleted.optString("sourceUri"));if(deleted.optBoolean("recorded")&&"file".equals(source.getScheme())){File file=new File(source.getPath()).getCanonicalFile(),root=new File(c.getFilesDir(),"recordings").getCanonicalFile();boolean shared=false;File[] remaining=TranscribeService.history(c).listFiles((d,n)->n.endsWith(".json"));if(remaining!=null)for(File record:remaining)if(source.toString().equals(read(record).optString("sourceUri"))){shared=true;break;}
            if(!shared&&file.getParentFile().equals(root)&&file.getName().matches("[0-9]+\\.wav")&&file.exists()&&!file.delete())throw new IOException("录音文件删除未完成");
        }
    }
    static Uri folder(Context c,String id)throws Exception{
        if(id!=null&&!id.isEmpty()){JSONObject index=read(indexFile(c,id)),targets=index.optJSONObject("targets"),dest=targets==null?null:targets.optJSONObject(index.optString("current"));if(dest!=null){if(!dest.optString("directory").isEmpty())return Uri.parse(dest.getString("directory"));return DocumentsContract.buildDocumentUri("com.android.externalstorage.documents","primary:"+dest.getString("path").replaceFirst("/$",""));}}
        String tree=preferences(c).getString("tree","");return tree.isEmpty()?DocumentsContract.buildDocumentUri("com.android.externalstorage.documents","primary:"+DEFAULT_PATH.replaceFirst("/$","")):DocumentsContract.buildDocumentUriUsingTree(Uri.parse(tree),DocumentsContract.getTreeDocumentId(Uri.parse(tree)));
    }
    private static String srt(double sec){long ms=Math.max(0,Math.round(sec*1000));return String.format(Locale.ROOT,"%02d:%02d:%02d,%03d",ms/3600000,ms/60000%60,ms/1000%60,ms%1000);}
    static String transcript(JSONObject doc,String format)throws Exception{
        if(format.equals("JSON"))return doc.toString(2);StringBuilder text=new StringBuilder();if(format.equals("MD"))text.append("# ").append(doc.optString("title")).append("\n\n");JSONArray rows=doc.getJSONArray("segments");
        for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);if(format.equals("SRT"))text.append(i+1).append('\n').append(srt(row.optDouble("start"))).append(" --> ").append(srt(row.optDouble("end"))).append('\n').append('[').append(TranscriptStore.speakerName(doc,row)).append("] ");else text.append("**").append(row.optBoolean("marked")?"【重点】 ":"").append(TranscribeService.stamp(row.optDouble("start"))).append(" · ").append(TranscriptStore.speakerName(doc,row)).append("**\n\n");text.append(row.optString("text")).append("\n\n");}return text.toString();
    }
}
