package win.youhoo.moss;

import android.content.Context;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** User annotations live beside the engine result, so live inference never overwrites edits. */
final class TranscriptStore {
    private static final Map<String,JSONObject> publicCache=new LinkedHashMap<>();
    private static final Map<String,String> publicVersions=new HashMap<>();
    static String id(String filename){return filename.endsWith(".json")?filename.substring(0,filename.length()-5):filename;}
    private static File annotationsFile(Context c,String key)throws IOException{
        if(!key.matches("[0-9]+(-partial)?"))throw new IOException("记录尚未就绪");
        File dir=new File(c.getFilesDir(),"annotations");dir.mkdirs();return new File(dir,key+".json");
    }
    static synchronized JSONObject annotations(Context c,String key){
        try{File file=annotationsFile(c,key);return file.isFile()?new JSONObject(Files.readString(file.toPath(),StandardCharsets.UTF_8)):new JSONObject();}
        catch(Exception ignored){return new JSONObject();}
    }
    static synchronized JSONObject publicAnnotations(Context c,String key){
        String version="";try{File f=annotationsFile(c,key);version=f.lastModified()+":"+f.length();}catch(Exception ignored){}
        if(version.equals(publicVersions.get(key))&&publicCache.containsKey(key))return publicCache.get(key);
        JSONObject notes=annotations(c,key);notes.remove("speakerProfiles");
        if(publicCache.size()>24){publicCache.clear();publicVersions.clear();}publicCache.put(key,notes);publicVersions.put(key,version);return notes;
    }
    static long annotationVersion(Context c,String key){try{return annotationsFile(c,key).lastModified();}catch(Exception e){return 0;}}
    static synchronized void speaker(Context c,String key,String speaker,String name)throws Exception{
        if(speaker.isEmpty()||speaker.length()>100)throw new IOException("请选择说话人");
        name=name.trim();if(name.length()>40)throw new IOException("姓名或角色请控制在 40 字以内");
        JSONObject doc=annotations(c,key),names=doc.optJSONObject("speakerNames");if(names==null)names=new JSONObject();
        speaker=SpeakerIdentity.root(doc,SpeakerIdentity.object(doc,"autoSpeakers").optString(speaker,speaker));
        List<String> aliases=new ArrayList<>();for(Iterator<String> it=names.keys();it.hasNext();){String alias=it.next();if(SpeakerIdentity.root(doc,SpeakerIdentity.object(doc,"autoSpeakers").optString(alias,alias)).equals(speaker))aliases.add(alias);}
        for(String alias:aliases)names.remove(alias);
        if(name.isEmpty())names.remove(speaker);else names.put(speaker,name);doc.put("speakerNames",names);write(annotationsFile(c,key),doc);
    }
    static synchronized void matchSpeakers(Context c,String key,LinkedHashMap<String,float[]> vectors,Set<String> chunkKeys)throws Exception{
        JSONObject notes=annotations(c,key);SpeakerIdentity.match(notes,vectors,chunkKeys);write(annotationsFile(c,key),notes);
    }
    static synchronized void matchStatus(Context c,String key,String status,String error)throws Exception{
        JSONObject notes=annotations(c,key);notes.put("speakerMatch",new JSONObject().put("version",1).put("model","3D-Speaker ERes2Net").put("status",status).put("error",error).put("updated",System.currentTimeMillis()));write(annotationsFile(c,key),notes);
    }
    static synchronized void changeSpeaker(Context c,String key,JSONObject doc,String operation,String from,String to,String segment)throws Exception{
        JSONObject notes=annotations(c,key);JSONArray rows=doc.getJSONArray("segments");
        if(operation.equals("merge"))SpeakerIdentity.merge(notes,rows,from,to);else SpeakerIdentity.assign(notes,rows,segment,to);
        write(annotationsFile(c,key),notes);
    }
    /** Rename the record out of the visible history atomically before purging its sidecars. */
    static synchronized void delete(Context c,String key)throws Exception{
        File note=annotationsFile(c,key),file=new File(TranscribeService.history(c),key+".json");
        if(!file.isFile())throw new IOException("记录已删除");
        File tombstone=new File(file.getParentFile(),key+".deleted");
        Files.move(file.toPath(),tombstone.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        // Original recordings are retained. Imported URIs may be shared by other records.
        note.delete();new File(c.getFilesDir(),"minutes/"+key+".json").delete();tombstone.delete();
        publicCache.remove(key);publicVersions.remove(key);
    }
    static synchronized void writeMinutes(Context c,String key,File file,JSONObject doc)throws IOException{
        if(!new File(TranscribeService.history(c),key+".json").isFile()&&!(TranscribeService.busy&&key.equals(TranscribeService.recordId)))throw new IOException("这条转写记录已删除");
        write(file,doc);
    }
    static synchronized void segment(Context c,String key,String segment,String text,boolean reset,Boolean marked)throws Exception{
        if(segment.isEmpty()||segment.length()>100)throw new IOException("请选择一段文字");
        JSONObject doc=annotations(c,key);String field=marked==null?"textOverrides":"bookmarks";
        JSONObject values=doc.optJSONObject(field);if(values==null)values=new JSONObject();
        if(marked==null){
            text=text.trim();if(!reset&&(text.isEmpty()||text.length()>10000))throw new IOException("文字请控制在 1 到 10000 字以内");
            if(reset)values.remove(segment);else values.put(segment,text);
        }else if(marked)values.put(segment,true);else values.remove(segment);
        doc.put(field,values);write(annotationsFile(c,key),doc);
    }
    static synchronized void write(File file,JSONObject doc)throws IOException{
        File temp=new File(file.getParentFile(),file.getName()+".part");
        Files.writeString(temp.toPath(),doc.toString(),StandardCharsets.UTF_8);
        Files.move(temp.toPath(),file.toPath(),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
        if(file.getParentFile().getName().equals("annotations")){publicCache.remove(id(file.getName()));publicVersions.remove(id(file.getName()));}
    }
    static JSONObject decorate(Context c,String key,JSONObject original)throws JSONException{
        JSONObject doc=new JSONObject(original.toString());JSONObject annotations=annotations(c,key);
        doc.put("recordId",key).put("speakerNames",annotations.optJSONObject("speakerNames")!=null?annotations.getJSONObject("speakerNames"):new JSONObject());
        JSONObject edits=annotations.optJSONObject("textOverrides"),marks=annotations.optJSONObject("bookmarks");
        doc.put("textOverrides",edits==null?new JSONObject():edits).put("bookmarks",marks==null?new JSONObject():marks);
        JSONArray rows=doc.optJSONArray("segments");if(rows!=null)for(int i=0;i<rows.length();i++){
            JSONObject row=rows.getJSONObject(i);String id=row.optString("id","row_"+i),raw=row.optString("originalText",row.optString("text"));
            boolean edited=edits!=null&&edits.has(id);row.put("id",id).put("text",edited?edits.getString(id):raw).put("edited",edited).put("marked",marks!=null&&marks.optBoolean(id));
            if(edited)row.put("originalText",raw);else row.remove("originalText");
        }
        SpeakerIdentity.decorate(doc,annotations);
        return doc;
    }
    static String speakerName(JSONObject doc,JSONObject row){String speaker=row.optString("speaker","S01"),fallback=String.format(Locale.ROOT,"说话人 %02d",row.optInt("speakerNumber",1));JSONObject names=doc.optJSONObject("speakerNames");return names==null?fallback:names.optString(speaker,fallback);}
    static String text(JSONObject doc)throws JSONException{StringBuilder text=new StringBuilder();JSONArray rows=doc.getJSONArray("segments");
        for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);if(row.optBoolean("marked"))text.append("【重点】 ");text.append(TranscribeService.stamp(row.optDouble("start"))).append("  ").append(speakerName(doc,row)).append('\n').append(row.optString("text")).append("\n\n");}return text.toString();}
}
