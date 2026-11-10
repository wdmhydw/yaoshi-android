package win.youhoo.moss;

import org.json.*;
import com.sun.net.httpserver.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** Exercises the actual production HTTP/formatting classes against a loopback server. */
public final class AiWireTest {
    static int checks;static volatile String mode="chat",lastPath;static volatile JSONObject lastBody;
    static void check(boolean value,String name){if(!value)throw new AssertionError(name);checks++;}
    interface Task{void run()throws Exception;}
    static void fails(Task task,String contains)throws Exception{try{task.run();throw new AssertionError("expected: "+contains);}catch(java.io.IOException e){check(e.getMessage().contains(contains),e.getMessage());}}
    static JSONObject cfg(String root,String api)throws Exception{return new JSONObject().put("baseUrl",root).put("api",api).put("authMode","bearer").put("modelsPath","models").put("selectedModelId","Case/Model:v2");}
    static void respond(HttpExchange e)throws java.io.IOException{
        try{
            lastPath=e.getRequestURI().toString();String raw=new String(e.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);lastBody=raw.isEmpty()?new JSONObject():new JSONObject(raw);
            int code=200;String body="{}";
            if(mode.equals("slow")){Thread.sleep(2000);body="{}";}
            else if(mode.equals("auth")){code=401;body="{\"error\":{\"message\":\"bad secret-qa-123\"}}";}
            else if(mode.equals("limit")){code=429;body="{}";}
            else if(mode.equals("redirect")){code=302;e.getResponseHeaders().add("Location","https://example.com/");}
            else if(mode.equals("compat")&&lastPath.endsWith("/v1/models")){code=404;}
            else if(lastPath.contains("models")&&!lastPath.contains("generateContent")){
                boolean next=lastPath.contains("?");
                if(mode.equals("bailian"))body="{\"output\":{\"models\":[{\"model\":\""+(lastPath.contains("page_no=2")?"Qwen-2":"Qwen-1")+"\"}],\"total\":2}}";
                else if(mode.equals("missingCursor"))body="{\"data\":[{\"id\":\"one\"}],\"has_more\":true}";
                else if(mode.equals("google"))body=next?"{\"models\":[{\"name\":\"models/Gem:2\",\"supportedGenerationMethods\":[\"generateContent\"]}]}":"{\"models\":[{\"name\":\"models/embed\",\"supportedGenerationMethods\":[\"embedContent\"]}],\"nextPageToken\":\"page 2\"}";
                else if(mode.equals("empty"))body="{\"data\":[]}";
                else if(mode.equals("badjson"))body="html";
                else body=next?"{\"data\":[{\"id\":\"Case/Model:v2\"},{\"id\":\"B\"}],\"has_more\":false}":"{\"data\":[{\"id\":\"Case/Model:v2\"},{\"id\":\"embed\",\"type\":\"embedding\"}],\"has_more\":true,\"last_id\":\"cursor/A\"}";
            }else if(mode.equals("reasoning-only"))body="{\"choices\":[{\"message\":{\"content\":null,\"reasoning_content\":\"private\"},\"finish_reason\":\"length\"}]}";
            else if(mode.equals("responses"))body="{\"status\":\"completed\",\"output\":[{\"content\":[{\"type\":\"output_text\",\"text\":\"answer\"}]}]}";
            else if(mode.equals("anthropic"))body="{\"content\":[{\"type\":\"text\",\"text\":\"answer\"}],\"stop_reason\":\"end_turn\"}";
            else if(mode.equals("google"))body="{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"hidden\",\"thought\":true},{\"text\":\"answer\"}]},\"finishReason\":\"STOP\"}]}";
            else body="{\"choices\":[{\"message\":{\"content\":\"answer\"},\"finish_reason\":\""+(mode.equals("cut")?"length":"stop")+"\"}]}";
            byte[] bytes=body.getBytes(StandardCharsets.UTF_8);e.getResponseHeaders().add("Content-Type","application/json");e.sendResponseHeaders(code,bytes.length);e.getResponseBody().write(bytes);
        }catch(Exception ignored){}finally{e.close();}
    }
    public static void main(String[] args)throws Exception{
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);ExecutorService pool=Executors.newCachedThreadPool();server.setExecutor(pool);server.createContext("/",AiWireTest::respond);server.start();
        String root="http://127.0.0.1:"+server.getAddress().getPort()+"/proxy/v1";JSONObject c=cfg(root,"openai-completions");
        try{
            check(AiWire.base(root+"/chat/completions").equals(root),"full operation URL");
            check(AiWire.endpoint(c,"v1/models").equals(root+"/models"),"prefix and version preserved");
            check(AiWire.endpoint(c,"/api/v1/models").endsWith("/api/v1/models")&&!AiWire.endpoint(c,"/api/v1/models").contains("proxy"),"explicit origin-relative directory");
            fails(()->AiWire.base("http://10.attacker.example/v1"),"HTTPS");fails(()->AiWire.base("http://192.168.999.1/v1"),"完整");fails(()->AiWire.base("https://user:secret@example.com/v1"),"完整");fails(()->AiWire.endpoint(c,"../other"),"相对路径");
            JSONArray list=AiWire.models(c,"secret-qa-123",new AiWire.Call());check(list.length()==2,"dedup and non-text exclusion");check(list.getJSONObject(0).getString("id").equals("Case/Model:v2"),"verbatim ID");check(lastPath.contains("after=cursor%2FA"),"encoded page cursor");
            mode="empty";check(AiWire.models(c,"secret",new AiWire.Call()).length()==0,"empty is supported");
            mode="badjson";fails(()->AiWire.models(c,"secret",new AiWire.Call()),"JSON");
            mode="bailian";JSONObject bailian=cfg(root,"openai-completions").put("discovery","bailian").put("modelsPath","/api/v1/models");check(AiWire.models(bailian,"secret",new AiWire.Call()).getJSONObject(1).getString("id").equals("Qwen-2"),"Bailian schema and numbered pages");
            mode="missingCursor";fails(()->AiWire.models(c,"secret",new AiWire.Call()),"游标");
            mode="compat";JSONObject compat=cfg(root.replace("/proxy/v1","/compat"),"openai-completions").put("discovery","cc-switch");check(AiWire.models(compat,"secret",new AiWire.Call()).length()==2&&lastPath.startsWith("/compat/models"),"404 directory candidates preserve prefix");
            for(String p:new String[]{"chat","responses","anthropic","google"}){
                mode=p;String api=p.equals("chat")?"openai-completions":p.equals("responses")?"openai-responses":p.equals("anthropic")?"anthropic-messages":"google-generative-ai";
                JSONObject wire=cfg(root,api);AiWire.Reply reply=AiWire.complete(wire,"secret-qa-123","system","input",100,new AiWire.Call());check(reply.text.equals("answer")&&!reply.truncated,p+" body parsed");
                if(p.equals("chat"))check(lastBody.getJSONArray("messages").length()==2,"system/user separation");
                if(p.equals("responses"))check(!lastBody.getBoolean("store")&&!lastBody.getBoolean("stream"),"responses controls");
                if(p.equals("google")){check(lastPath.endsWith("/models/Case/Model:v2:generateContent"),"Gemini model path");check(AiWire.models(wire,"secret",new AiWire.Call()).length()==1,"Gemini paging and task filter");}
            }
            mode="cut";check(AiWire.complete(c,"secret","s","i",100,new AiWire.Call()).truncated,"truncation retained");
            mode="reasoning-only";fails(()->AiWire.complete(c,"secret","s","i",100,new AiWire.Call()),"用完输出额度");
            JSONObject direct=new JSONObject().put("model","deepseek-flash");
            AiWire.setDeepSeekFlashTextMode(direct,"openai-completions","https://api.deepseek.com/chat/completions");
            check("disabled".equals(direct.getJSONObject("thinking").getString("type")),"request a visible answer on official DeepSeek Flash");
            direct.remove("thinking");AiWire.setDeepSeekFlashTextMode(direct,"openai-completions","https://api.deepseek.com.example/proxy");
            check(!direct.has("thinking"),"no vendor parameter on other endpoints");
            mode="auth";try{AiWire.complete(c,"secret-qa-123","s","i",100,new AiWire.Call());throw new AssertionError();}catch(java.io.IOException e){check(e.getMessage().contains("401")&&!e.getMessage().contains("secret-qa-123"),"redacted 401");}
            mode="limit";fails(()->AiWire.models(c,"secret",new AiWire.Call()),"429");mode="redirect";fails(()->AiWire.models(c,"secret",new AiWire.Call()),"重定向");
            mode="slow";AiWire.Call call=new AiWire.Call();Future<?> pending=pool.submit(()->{try{AiWire.models(c,"secret",call);throw new AssertionError("cancel");}catch(Exception e){if(!e.getMessage().contains("取消"))throw new RuntimeException(e);}});Thread.sleep(120);call.cancel();pending.get(3,TimeUnit.SECONDS);checks++;
            JSONObject doc=new JSONObject().put("segments",new JSONArray().put(new JSONObject().put("id","seg_1").put("start",83).put("speaker","S01").put("marked",true).put("text","议题😀".repeat(1000)))).put("speakerNames",new JSONObject().put("S01","王经理"));
            List<String> chunks=MinutesFormat.chunks(doc,250);check(chunks.size()>1&&chunks.stream().allMatch(s->s.length()<=250),"bounded long segment");check(chunks.get(0).contains("王经理 【重点】"),"user annotations");check(chunks.stream().noneMatch(s->s.matches("(?s).*\\p{Cs}$")),"unicode boundary");
            check(MinutesFormat.clipboardText("决定 [伪时间](source:seg_1) [未知](source:invented)",MinutesFormat.sources(doc)).equals("决定 [00:01:23] 未知"),"clipboard source timestamps authoritative");
            System.out.println("PASS "+checks+" AI wire/format checks");
        }finally{server.stop(0);pool.shutdownNow();}
    }
}
