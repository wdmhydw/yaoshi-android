package win.youhoo.moss;

import android.content.Context;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;

/** Coordinates settings and per-record minutes; network work never runs on the UI thread. */
final class AiController {
    interface Events{void send(JSONObject message);}
    private final Context context;private final Events events;private final ModelConnections connections;
    private final ExecutorService worker=Executors.newFixedThreadPool(2);
    private final Map<String,JSONObject> discoveries=new ConcurrentHashMap<>(),tests=new ConcurrentHashMap<>();
    private final Map<String,AiWire.Call> calls=new ConcurrentHashMap<>();
    private JSONArray templates;private volatile boolean closed;private volatile String generating="";
    synchronized boolean isGenerating(String id){return id.equals(generating);}
    AiController(Context c,Events e)throws Exception{context=c.getApplicationContext();events=e;connections=new ModelConnections(context);File f=new File(context.getFilesDir(),"minutes-templates.json");templates=f.isFile()?new JSONArray(Files.readString(f.toPath(),StandardCharsets.UTF_8)):new JSONArray();}
    private JSONObject asset(String name)throws Exception{try(InputStream in=context.getAssets().open("ai/"+name)){return new JSONObject(new String(in.readAllBytes(),StandardCharsets.UTF_8));}}
    private void emit(JSONObject value){if(!closed)events.send(value);}
    private void notice(String message){try{emit(new JSONObject().put("kind","notice").put("message",message));}catch(Exception ignored){}}
    synchronized JSONArray allTemplates()throws Exception{JSONArray result=asset("templates.json").getJSONArray("templates");for(int i=0;i<templates.length();i++)result.put(templates.getJSONObject(i));return result;}
    void ready()throws Exception{emit(new JSONObject().put("kind","config").put("settings",connections.publicState()).put("presets",asset("providers.json").getJSONArray("presets")).put("templates",allTemplates()));}
    synchronized void handle(JSONObject args,JSONObject doc)throws Exception{
        String action=args.getString("action");
        switch(action){
            case "aiReady":ready();break;
            case "aiSaveConnection":{JSONObject draft=args.getJSONObject("connection");String id=draft.getString("id");connections.save(draft,discoveries.get(id),tests.get(id));ready();emit(new JSONObject().put("kind","saved").put("id",id));break;}
            case "aiDefault":connections.use(args.getString("id"));ready();break;
            case "aiDeleteConnection":connections.remove(args.getString("id"),args.optBoolean("clearDefault"));ready();break;
            case "aiDiscover":case "aiTest":operation(args,action.equals("aiDiscover"));break;
            case "aiCancelOperation":{AiWire.Call call=calls.remove("settings");if(call!=null)call.cancel();break;}
            case "aiOpenMinutes":if(doc!=null)open(doc);break;
            case "aiGenerate":if(doc!=null)generate(doc,args.getString("templateId"));break;
            case "aiCancelMinutes":{AiWire.Call call=calls.get("minutes");if(call!=null)call.cancel();break;}
            case "aiSaveMinutes":saveEdit(args);break;
            case "aiPasteMinutes":pasteMinutes(args,doc);break;
            case "aiDeleteMinutes":deleteVersion(args);break;
            case "aiSaveTemplate":saveTemplate(args.getJSONObject("template"));ready();emit(new JSONObject().put("kind","templateSaved").put("id",args.getJSONObject("template").getString("id")));break;
            case "aiDeleteTemplate":deleteTemplate(args.getString("id"));ready();break;
        }
    }
    private void operation(JSONObject args,boolean discover)throws Exception{
        JSONObject raw=args.getJSONObject("connection"),cfg=ModelConnections.normalize(raw);String key=connections.draftKey(raw),identity=ModelConnections.fingerprint(cfg,key),opId=args.getString("opId"),id=cfg.getString("id");
        AiWire.Call previous=calls.remove("settings");if(previous!=null)previous.cancel();AiWire.Call call=new AiWire.Call();calls.put("settings",call);
        worker.execute(()->{long start=System.nanoTime();JSONObject result=new JSONObject();
            try{
                result.put("kind","operation").put("opId",opId).put("id",id).put("operation",discover?"discover":"test");
                if(discover){JSONArray rows=AiWire.models(cfg,key,call);result.put("models",rows).put("status",rows.length()==0?"empty":"ready");discoveries.put(id,new JSONObject().put("identity",identity).put("models",rows).put("status",result.getString("status")).put("at",System.currentTimeMillis()));}
                else{AiWire.Reply reply=AiWire.complete(cfg,key,"这是文本接口连通性测试。请仅输出 OK。","OK",512,call);result.put("status",reply.truncated?"incomplete":"passed").put("elapsedMs",reply.millis);tests.put(id,new JSONObject().put("identity",identity).put("modelId",cfg.getString("selectedModelId")).put("status",result.getString("status")).put("elapsedMs",reply.millis).put("at",System.currentTimeMillis()));}
            }catch(Exception e){try{result.put("status",call.cancelled?"cancelled":"error").put("message",AiWire.error(e,key));if(!call.cancelled){if(discover){JSONObject old=discoveries.get(id);if(old==null||!identity.equals(old.optString("identity"))){JSONObject saved=connections.find(id);old=saved!=null&&identity.equals(saved.optString("identity"))?new JSONObject().put("at",saved.optLong("directoryFetchedAt")).put("models",saved.optJSONArray("models")):new JSONObject();}discoveries.put(id,new JSONObject(old.toString()).put("identity",identity).put("status","stale"));}else tests.put(id,new JSONObject().put("identity",identity).put("modelId",cfg.optString("selectedModelId")).put("status","failed").put("at",System.currentTimeMillis()));}}catch(Exception ignored){}}
            finally{try{result.put("elapsedMs",TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start));}catch(Exception ignored){}if(calls.get("settings")==call){calls.remove("settings");emit(result);}}
        });
    }
    private static String recordId(JSONObject doc)throws Exception{String id=doc.getString("recordId");if(!id.matches("[0-9]+(-partial)?"))throw new IOException("请先保存转写记录");return id;}
    private File minutesFile(String id)throws IOException{if(!id.matches("[0-9]+(-partial)?"))throw new IOException("记录标识有误");File dir=new File(context.getFilesDir(),"minutes");dir.mkdirs();return new File(dir,id+".json");}
    synchronized JSONObject minutes(String id)throws Exception{File f=minutesFile(id);return f.isFile()?new JSONObject(Files.readString(f.toPath(),StandardCharsets.UTF_8)):new JSONObject().put("recordId",id).put("versions",new JSONArray());}
    private void open(JSONObject doc)throws Exception{String id=recordId(doc);emit(new JSONObject().put("kind","minutes").put("recordId",id).put("title",doc.optString("title")).put("sources",MinutesFormat.sources(doc)).put("data",minutes(id)).put("busy",id.equals(generating)));if(minutes(id).getJSONArray("versions").length()>0)FileLibrary.ensureMinutes(context,id);}
    private synchronized JSONObject template(String id)throws Exception{JSONArray all=allTemplates();for(int i=0;i<all.length();i++)if(id.equals(all.getJSONObject(i).getString("id")))return all.getJSONObject(i);throw new IOException("请选择整理模板");}
    private synchronized void saveTemplate(JSONObject value)throws Exception{
        String id=value.getString("id"),name=value.optString("name").trim(),prompt=value.optString("prompt").trim();
        if(!id.matches("custom-[A-Za-z0-9_-]{1,80}")||name.isEmpty()||name.length()>50||prompt.isEmpty()||prompt.length()>16000)throw new IOException("请填写模板名称和提示词（最多 16000 字）");
        JSONArray next=new JSONArray();for(int i=0;i<templates.length();i++)if(!id.equals(templates.getJSONObject(i).optString("id")))next.put(templates.getJSONObject(i));
        if(next.length()>=100)throw new IOException("最多保存 100 个自定义模板");next.put(new JSONObject().put("id",id).put("name",name).put("prompt",prompt).put("custom",true));persistTemplates(next);
    }
    private synchronized void deleteTemplate(String id)throws Exception{JSONArray next=new JSONArray();for(int i=0;i<templates.length();i++)if(!id.equals(templates.getJSONObject(i).optString("id")))next.put(templates.getJSONObject(i));persistTemplates(next);}
    private void persistTemplates(JSONArray next)throws Exception{File f=new File(context.getFilesDir(),"minutes-templates.json"),tmp=new File(f.getPath()+".part");Files.writeString(tmp.toPath(),next.toString(),StandardCharsets.UTF_8);Files.move(tmp.toPath(),f.toPath(),java.nio.file.StandardCopyOption.ATOMIC_MOVE,java.nio.file.StandardCopyOption.REPLACE_EXISTING);templates=next;}
    private synchronized void append(String id,JSONObject version)throws Exception{
        JSONObject data=minutes(id);JSONArray old=data.getJSONArray("versions"),next=new JSONArray();next.put(version);for(int i=0;i<old.length();i++)next.put(old.getJSONObject(i));data.put("versions",next);TranscriptStore.writeMinutes(context,id,minutesFile(id),data);FileLibrary.enqueue(context,id,null);
    }
    private synchronized void deleteVersion(JSONObject args)throws Exception{
        String id=args.getString("recordId"),versionId=args.getString("versionId");JSONObject data=minutes(id);JSONArray old=data.getJSONArray("versions"),next=new JSONArray();boolean found=false;
        for(int i=0;i<old.length();i++){JSONObject row=old.getJSONObject(i);if(versionId.equals(row.optString("id")))found=true;else next.put(row);}
        if(!found)throw new IOException("这版纪要已删除");data.put("versions",next);TranscriptStore.writeMinutes(context,id,minutesFile(id),data);
        emit(new JSONObject().put("kind","minutesSaved").put("recordId",id).put("data",data));
        FileLibrary.prune(context,id,error->notice(error.isEmpty()?"纪要已删除":error));
    }
    static int count(Context context,String id){try{File f=new File(context.getFilesDir(),"minutes/"+id+".json");return f.isFile()?new JSONObject(Files.readString(f.toPath(),StandardCharsets.UTF_8)).getJSONArray("versions").length():0;}catch(Exception e){return 0;}}
    private synchronized void saveEdit(JSONObject args)throws Exception{
        String id=args.getString("recordId"),markdown=args.optString("markdown");if(markdown.trim().isEmpty()||markdown.length()>100000)throw new IOException("纪要内容请控制在 1 到 100000 字");
        JSONArray rows=minutes(id).getJSONArray("versions");JSONObject source=null;for(int i=0;i<rows.length();i++)if(rows.getJSONObject(i).optString("id").equals(args.optString("versionId")))source=rows.getJSONObject(i);
        if(source==null)throw new IOException("请重新打开这份纪要");JSONObject changed=new JSONObject(source.toString()).put("id",UUID.randomUUID().toString()).put("markdown",markdown).put("created",System.currentTimeMillis()).put("edited",true);
        append(id,changed);emit(new JSONObject().put("kind","minutesSaved").put("recordId",id).put("data",minutes(id)));notice("纪要已保存");
    }
    private void progress(String id,String text)throws Exception{emit(new JSONObject().put("kind","minutesProgress").put("recordId",id).put("status",text).put("busy",true));}
    private void pasteMinutes(JSONObject args,JSONObject doc)throws Exception{
        if(doc==null || !recordId(doc).equals(args.getString("recordId")))throw new IOException("请重新打开这条转写记录");
        String id=recordId(doc),markdown=args.optString("markdown");
        if(markdown.trim().isEmpty() || markdown.length()>100000)throw new IOException("纪要内容请控制在 1 到 100000 字");
        if(isGenerating(id) || minutes(id).getJSONArray("versions").length()!=0)throw new IOException("纪要状态已变化，请重新打开");
        JSONObject version=new JSONObject().put("id",UUID.randomUUID().toString()).put("created",System.currentTimeMillis())
                .put("title",doc.optString("title")).put("markdown",markdown).put("templateName","粘贴的纪要").put("sources",MinutesFormat.sources(doc));
        append(id,version);
        emit(new JSONObject().put("kind","minutesSaved").put("recordId",id).put("data",minutes(id)));
        notice("纪要已保存");
    }
    private void generate(JSONObject document,String templateId)throws Exception{
        if(!generating.isEmpty())throw new IOException("已有纪要正在生成");
        JSONObject doc=new JSONObject(document.toString()),tpl=new JSONObject(template(templateId).toString()),cfg=connections.selection();String key=connections.key(cfg.getString("id")),id=recordId(doc);
        if(cfg.optString("selectedModelId").isEmpty())throw new IOException("请先选择默认模型");
        List<String> chunks=MinutesFormat.chunks(doc,16000);AiWire.Call call=new AiWire.Call();calls.put("minutes",call);generating=id;progress(id,"正在整理…");
        worker.execute(()->{
            JSONArray usages=new JSONArray();long began=System.nanoTime();
            try{
                String material;int requests=0;
                if(chunks.size()==1)material=chunks.get(0);else{
                    List<String> notes=new ArrayList<>();
                    for(int i=0;i<chunks.size();i++){call.check();progress(id,"整理片段 "+(i+1)+" / "+chunks.size());AiWire.Reply reply=AiWire.complete(cfg,key,MinutesFormat.SYSTEM,"提取这一部分中的关键事实、决策、待办、角色线索，尽量精简，并保留原文引用。不要加入材料之外的内容。\n\n"+chunks.get(i),3000,call);if(reply.truncated)throw new IOException("片段整理达到模型输出上限，请换用更适合长文的模型");notes.add(reply.text);usages.put(reply.usage);requests++;}
                    for(int round=0;String.join("\n\n",notes).length()>28000&&round<4;round++){
                        List<String> reduced=new ArrayList<>();for(int at=0;at<notes.size();at+=3){call.check();String group=String.join("\n\n",notes.subList(at,Math.min(at+3,notes.size())));progress(id,"合并讨论要点…");AiWire.Reply reply=AiWire.complete(cfg,key,MinutesFormat.SYSTEM,"合并以下分段记录，压缩重复内容，保留不同决策、待办、说话人线索及原文引用。\n\n"+group,3000,call);if(reply.truncated)throw new IOException("合并结果达到输出上限，请调整模型后重试");reduced.add(reply.text);usages.put(reply.usage);requests++;}notes=reduced;
                    }
                    material=String.join("\n\n",notes);if(material.length()>60000)throw new IOException("纪要材料较长，请分段整理");
                }
                progress(id,"生成纪要…");String input="录音标题："+doc.optString("title")+"\n整理要求：\n"+tpl.getString("prompt")+"\n\n<转写材料>\n"+material+"\n</转写材料>";
                AiWire.Reply reply=AiWire.complete(cfg,key,MinutesFormat.SYSTEM,input,7000,call);call.check();usages.put(reply.usage);
                JSONObject version=new JSONObject().put("id",UUID.randomUUID().toString()).put("title",doc.optString("title")).put("created",System.currentTimeMillis()).put("markdown",reply.text).put("templateId",tpl.getString("id")).put("templateName",tpl.getString("name")).put("modelId",cfg.getString("selectedModelId")).put("connectionName",cfg.getString("name")).put("sources",MinutesFormat.sources(doc)).put("sourceHash",ModelConnections.hash(doc.toString())).put("usage",usages).put("requests",requests+1).put("elapsedMs",TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-began)).put("incomplete",reply.truncated);
                append(id,version);emit(new JSONObject().put("kind","minutesSaved").put("recordId",id).put("data",minutes(id)));
            }catch(Exception e){try{emit(new JSONObject().put("kind","minutesError").put("recordId",id).put("message",AiWire.error(e,key)));}catch(Exception ignored){}}
            finally{generating="";calls.remove("minutes",call);try{emit(new JSONObject().put("kind","minutesProgress").put("recordId",id).put("status","").put("busy",false));}catch(Exception ignored){}}
        });
    }
    synchronized JSONObject savedVersion(String id,String versionId)throws Exception{
        JSONArray rows=minutes(id).getJSONArray("versions");
        for(int i=0;i<rows.length();i++){
            JSONObject row=rows.getJSONObject(i);
            if(versionId.equals(row.optString("id")))return row;
        }
        throw new IOException("请先保存纪要");
    }
    void close(){closed=true;for(AiWire.Call call:calls.values())call.cancel();worker.shutdownNow();}
}
