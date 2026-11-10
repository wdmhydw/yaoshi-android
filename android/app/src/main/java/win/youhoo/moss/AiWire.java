package win.youhoo.moss;

import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** Native HTTP transport. No WebView requests, credential logging, retries or provider fallback. */
final class AiWire {
    static final Set<String> APIS=new HashSet<>(Arrays.asList("openai-completions","openai-responses","anthropic-messages","google-generative-ai"));
    static final class Call {
        volatile boolean cancelled;
        volatile HttpURLConnection connection;
        void cancel(){cancelled=true;HttpURLConnection c=connection;if(c!=null)c.disconnect();}
        void check()throws IOException{if(cancelled||Thread.currentThread().isInterrupted())throw new IOException("已取消");}
    }
    static final class Reply {
        final String text;final JSONObject usage;final long millis;final boolean truncated;
        Reply(String t,JSONObject u,long m,boolean cut){text=t;usage=u==null?new JSONObject():u;millis=m;truncated=cut;}
    }
    private static final class HttpFailure extends IOException{final int status;HttpFailure(int s,String m){super(m);status=s;}}
    private static final ScheduledExecutorService DEADLINES=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"ai-deadlines");t.setDaemon(true);return t;});
    static String base(String raw)throws IOException{
        try{
            URI u=new URI(raw.trim());String scheme=u.getScheme(),host=u.getHost();
            if(host==null||!("https".equals(scheme)||"http".equals(scheme))||u.getUserInfo()!=null||u.getQuery()!=null||u.getFragment()!=null)throw new Exception();
            if("http".equals(scheme)&&!localHost(host))throw new IOException("远程接口请使用 HTTPS；本机或局域网可使用 HTTP");
            String result=u.toString().replaceAll("/+$","");
            for(String suffix:new String[]{"/chat/completions","/responses","/messages"})if(result.endsWith(suffix)){result=result.substring(0,result.length()-suffix.length());break;}
            return result;
        }catch(IOException e){throw e;}catch(Exception e){throw new IOException("请填写完整的 API 地址");}
    }
    private static boolean localHost(String host){
        String h=host.toLowerCase(Locale.ROOT);
        if(h.equals("localhost")||h.equals("::1")||h.equals("[::1]"))return true;
        if(!h.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}"))return false;
        String[] parts=h.split("\\.");int[] ip=new int[4];
        for(int i=0;i<4;i++){ip[i]=Integer.parseInt(parts[i]);if(ip[i]>255)return false;}
        return ip[0]==127||ip[0]==10||(ip[0]==192&&ip[1]==168)||(ip[0]==172&&ip[1]>=16&&ip[1]<=31);
    }
    static String endpoint(JSONObject config,String path)throws IOException{
        String root=base(config.optString("baseUrl")),p=path.replaceAll("^/+","");
        if(path.startsWith("//")||!p.matches("[A-Za-z0-9_./-]+")||p.contains(".."))throw new IOException("接口路径请填写相对路径");
        if(path.startsWith("/")){URI uri=URI.create(root);return uri.getScheme()+"://"+uri.getRawAuthority()+"/"+p;}
        String tail=root.substring(root.lastIndexOf('/')+1);
        if(tail.matches("v[0-9]+(beta[0-9]*)?")&&p.startsWith(tail+"/"))p=p.substring(tail.length()+1);
        return root+"/"+p;
    }
    static String encode(String s){try{return URLEncoder.encode(s,"UTF-8").replace("+","%20");}catch(Exception e){throw new IllegalStateException(e);}}
    static String googleEndpoint(JSONObject cfg,String path)throws IOException{
        if(path.startsWith("//")||!path.matches("/?[A-Za-z0-9_./:-]+")||path.contains("..")||path.contains("://"))throw new IOException("模型 ID 或接口路径有误");
        String root=base(cfg.optString("baseUrl"));if(path.startsWith("/")){URI uri=URI.create(root);return uri.getScheme()+"://"+uri.getRawAuthority()+path;}
        String tail=root.substring(root.lastIndexOf('/')+1);if(tail.matches("v[0-9]+(beta[0-9]*)?")&&path.startsWith(tail+"/"))path=path.substring(tail.length()+1);
        return root+"/"+path;
    }
    static String error(Throwable e,String key){
        String text=e.getMessage();if(text==null||text.isEmpty())text=e.getClass().getSimpleName();
        if(key!=null&&!key.isEmpty())text=text.replace(key,"[密钥]");
        text=text.replaceAll("(?i)(bearer\\s+|api[-_]?key[=:]\\s*)[^\\s,;]+","$1[密钥]").replaceAll("sk-[A-Za-z0-9_-]{6,}","[密钥]");
        return text.length()>240?text.substring(0,240):text;
    }
    private static JSONObject request(JSONObject cfg,String key,String url,JSONObject body,Call call,int timeout)throws Exception{
        call.check();HttpURLConnection connection=(HttpURLConnection)new URL(url).openConnection();
        call.connection=connection;connection.setInstanceFollowRedirects(false);connection.setConnectTimeout(Math.min(timeout,15000));connection.setReadTimeout(timeout);
        connection.setRequestProperty("Accept","application/json");connection.setRequestProperty("Content-Type","application/json; charset=utf-8");
        String auth=cfg.optString("authMode","bearer");
        if(!auth.equals("none")&&key.isEmpty())throw new IOException("请填写 API 密钥");
        if(auth.equals("bearer"))connection.setRequestProperty("Authorization","Bearer "+key);
        else if(Arrays.asList("x-api-key","api-key","x-goog-api-key").contains(auth))connection.setRequestProperty(auth,key);
        else if(!auth.equals("none"))throw new IOException("鉴权方式不受支持");
        if(cfg.optString("api").equals("anthropic-messages"))connection.setRequestProperty("anthropic-version",cfg.optString("apiVersion","2023-06-01"));
        JSONObject headers=cfg.optJSONObject("extraHeaders");
        if(headers!=null)for(Iterator<String> it=headers.keys();it.hasNext();){String name=it.next(),value=headers.optString(name);
            if(name.matches("[A-Za-z0-9-]{1,80}")&&!name.matches("(?i)(authorization|x-api-key|api-key|x-goog-api-key|host|content-length|cookie)")&&!value.contains("\n")&&!value.contains("\r"))connection.setRequestProperty(name,value);
        }
        ScheduledFuture<?> deadline=DEADLINES.schedule(connection::disconnect,timeout,TimeUnit.MILLISECONDS);
        try{
            if(body!=null){connection.setRequestMethod("POST");connection.setDoOutput(true);byte[] bytes=body.toString().getBytes(StandardCharsets.UTF_8);connection.setFixedLengthStreamingMode(bytes.length);try(OutputStream out=connection.getOutputStream()){call.check();out.write(bytes);}}
            int status=connection.getResponseCode();
            InputStream stream=status>=200&&status<300?connection.getInputStream():connection.getErrorStream();String text="";
            if(stream!=null)try(InputStream in=stream;ByteArrayOutputStream out=new ByteArrayOutputStream()){
                byte[] buffer=new byte[8192];int n;while((n=in.read(buffer))!=-1){call.check();if(out.size()+n>4*1024*1024)throw new IOException("接口响应过大");out.write(buffer,0,n);}text=out.toString("UTF-8");
            }
            call.check();
            if(status<200||status>=300){
                String reason=status==401||status==403?"密钥或访问权限有误":status==429?"请求额度或频率受限":status>=300&&status<400?"接口返回重定向，请填写最终 API 地址":"请求失败";
                String detail="";try{Object e=new JSONObject(text).opt("error");if(e instanceof JSONObject)detail=((JSONObject)e).optString("message");}catch(Exception ignored){}
                throw new HttpFailure(status,reason+"（HTTP "+status+"）"+(detail.isEmpty()?"":"："+error(new Exception(detail),key)));
            }
            try{return new JSONObject(text);}catch(JSONException e){throw new IOException("接口未返回有效 JSON，请检查地址和协议");}
        }catch(Exception e){call.check();if(e instanceof HttpFailure)throw e;throw new IOException(error(e,key),e);}finally{deadline.cancel(false);connection.disconnect();if(call.connection==connection)call.connection=null;}
    }
    // CC Switch's known compatibility paths, MIT; inference routing is never changed.
    private static List<String> catalogs(JSONObject cfg)throws IOException{
        String root=base(cfg.optString("baseUrl"));LinkedHashSet<String> urls=new LinkedHashSet<>();
        if(!cfg.optString("discovery").equals("cc-switch")||!cfg.optString("modelsPath","models").equals("models"))return Collections.singletonList(endpoint(cfg,cfg.optString("modelsPath","models")));
        if(root.matches(".*/v[0-9]+$")){urls.add(root+"/models");if(!root.endsWith("/v1"))urls.add(root+"/v1/models");}else urls.add(root+"/v1/models");
        for(String suffix:new String[]{"/api/claudecode","/api/anthropic","/apps/anthropic","/api/coding","/claudecode","/anthropic","/step_plan","/coding","/claude"})if(root.endsWith(suffix)){String parent=root.substring(0,root.length()-suffix.length());urls.add(parent+"/v1/models");urls.add(parent+"/models");if(suffix.equals("/apps/anthropic"))urls.add(parent+"/compatible-mode/v1/models");break;}
        urls.add(root+"/models");return new ArrayList<>(urls);
    }
    static JSONArray models(JSONObject cfg,String key,Call call)throws Exception{
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);String api=cfg.optString("api"),discovery=cfg.optString("discovery");
        List<String> candidates=catalogs(cfg);int candidate=0,seenRows=0;
        String cursor="";Set<String> seen=new HashSet<>(),cursors=new HashSet<>();JSONArray result=new JSONArray();
        for(int page=0;page<20;page++){
            int left=(int)TimeUnit.NANOSECONDS.toMillis(deadline-System.nanoTime());if(left<1)throw new IOException("获取模型超时，请重试");
            String query=discovery.equals("bailian")?"?page_no="+(page+1)+"&page_size=100":cursor.isEmpty()?"":(api.equals("google-generative-ai")?"?pageToken=":api.equals("anthropic-messages")?"?after_id=":"?after=")+encode(cursor);
            JSONObject response;
            while(true){try{left=(int)TimeUnit.NANOSECONDS.toMillis(deadline-System.nanoTime());if(left<1)throw new IOException("获取模型超时，请重试");response=request(cfg,key,candidates.get(candidate)+query,null,call,left);break;}catch(HttpFailure e){if(page!=0||(e.status!=404&&e.status!=405)||candidate+1>=candidates.size())throw e;candidate++;}}
            JSONObject output=response.optJSONObject("output");
            JSONArray rows=discovery.equals("bailian")&&output!=null?output.optJSONArray("models"):response.optJSONArray(api.equals("google-generative-ai")?"models":"data");
            if(rows==null)rows=response.optJSONArray("models");if(rows==null)throw new IOException("接口未返回模型目录，可手动填写模型 ID");
            for(int i=0;i<rows.length();i++){
                Object value=rows.get(i);JSONObject row=value instanceof JSONObject?(JSONObject)value:new JSONObject().put("id",String.valueOf(value));
                String id=discovery.equals("bailian")?row.optString("model"):row.optString("id",row.optString("slug",row.optString("name")));if(id.isEmpty()||!seen.add(id))continue;
                JSONArray methods=row.optJSONArray("supportedGenerationMethods");
                if(api.equals("google-generative-ai")&&methods!=null&&!methods.toString().contains("generateContent"))continue;
                String type=row.optString("type");if(Arrays.asList("embedding","rerank","image","audio").contains(type))continue;
                result.put(new JSONObject().put("id",id).put("name",row.optString("display_name",row.optString("displayName",id))).put("source","remote").put("capabilities",JSONObject.NULL));
                if(result.length()>=5000)throw new IOException("模型目录超过 5000 项，请缩小目录或手动添加");
            }
            seenRows+=rows.length();cursor=discovery.equals("bailian")&&output!=null?(seenRows<output.optInt("total")?String.valueOf(page+2):""):api.equals("google-generative-ai")?response.optString("nextPageToken"):response.optBoolean("has_more")?response.optString("last_id"):"";
            if(response.optBoolean("has_more")&&cursor.isEmpty())throw new IOException("模型目录缺少下一页游标");
            if(cursor.isEmpty())return result;if(!cursors.add(cursor))throw new IOException("模型目录分页重复");
        }
        throw new IOException("模型目录分页超过上限");
    }
    static void setDeepSeekFlashTextMode(JSONObject body,String api,String endpoint)throws JSONException{
        URI uri=URI.create(endpoint);
        if(api.equals("openai-completions") && "deepseek-flash".equals(body.optString("model"))
                && "https".equalsIgnoreCase(uri.getScheme()) && "api.deepseek.com".equalsIgnoreCase(uri.getHost()))
            body.put("thinking",new JSONObject().put("type","disabled"));
    }
    static Reply complete(JSONObject cfg,String key,String system,String input,int outputTokens,Call call)throws Exception{
        String api=cfg.optString("api"),model=cfg.optString("selectedModelId"),path=cfg.optString("inferencePath");
        if(model.isEmpty())throw new IOException("请先选择默认模型");if(!APIS.contains(api))throw new IOException("请选择支持的 API 协议");
        JSONObject body=new JSONObject().put("model",model);String text="";JSONObject usage=null;boolean cut=false;long start=System.nanoTime();
        if(api.equals("openai-completions")){
            if(path.isEmpty())path="chat/completions";
            body.put("messages",new JSONArray().put(new JSONObject().put("role","system").put("content",system)).put(new JSONObject().put("role","user").put("content",input))).put("max_tokens",outputTokens).put("stream",false);
        }else if(api.equals("openai-responses")){
            if(path.isEmpty())path="responses";body.put("instructions",system).put("input",input).put("max_output_tokens",outputTokens).put("store",false).put("stream",false);
        }else if(api.equals("anthropic-messages")){
            if(path.isEmpty())path="messages";body.put("system",system).put("messages",new JSONArray().put(new JSONObject().put("role","user").put("content",input))).put("max_tokens",outputTokens).put("stream",false);
        }else{
            if(path.isEmpty())path=(model.startsWith("models/")?model:"models/"+model)+":generateContent";
            body.remove("model");body.put("systemInstruction",new JSONObject().put("parts",new JSONArray().put(new JSONObject().put("text",system))))
                .put("contents",new JSONArray().put(new JSONObject().put("role","user").put("parts",new JSONArray().put(new JSONObject().put("text",input)))))
                .put("generationConfig",new JSONObject().put("maxOutputTokens",outputTokens));
        }
        String url=api.equals("google-generative-ai")?googleEndpoint(cfg,path):endpoint(cfg,path);
        setDeepSeekFlashTextMode(body,api,url);
        JSONObject response=request(cfg,key,url,body,call,120000);
        if(api.equals("openai-completions")){
            JSONObject choice=response.getJSONArray("choices").getJSONObject(0),message=choice.getJSONObject("message");text=message.optString("content","");
            String stop=choice.optString("finish_reason");cut=stop.equals("length");if(stop.equals("tool_calls")||stop.equals("content_filter"))throw new IOException("模型没有完成文本生成");usage=response.optJSONObject("usage");
            if(text.trim().isEmpty()||text.equals("null")){
                if(cut)throw new IOException("模型用完输出额度后仍未返回正文，请缩短材料或更换模型");
                if(!message.isNull("reasoning_content")&&!message.optString("reasoning_content").isEmpty())
                    throw new IOException("模型只返回了推理过程，请使用直接输出正文的模式或更换模型");
            }
        }else if(api.equals("openai-responses")){
            JSONArray out=response.optJSONArray("output");StringBuilder b=new StringBuilder();if(out!=null)for(int i=0;i<out.length();i++){JSONArray parts=out.getJSONObject(i).optJSONArray("content");if(parts!=null)for(int j=0;j<parts.length();j++){JSONObject p=parts.getJSONObject(j);if(p.optString("type").equals("output_text"))b.append(p.optString("text"));}}
            text=b.toString();cut=response.optString("status").equals("incomplete");if(response.optString("status").equals("failed"))throw new IOException("模型生成失败");usage=response.optJSONObject("usage");
        }else if(api.equals("anthropic-messages")){
            StringBuilder b=new StringBuilder();JSONArray parts=response.optJSONArray("content");if(parts!=null)for(int i=0;i<parts.length();i++){JSONObject part=parts.getJSONObject(i);if(part.optString("type").equals("text"))b.append(part.optString("text"));}
            text=b.toString();String stop=response.optString("stop_reason");cut=stop.equals("max_tokens");if(stop.equals("tool_use"))throw new IOException("模型请求了工具，请使用文本生成模型");usage=response.optJSONObject("usage");
        }else{
            JSONArray candidates=response.optJSONArray("candidates");if(candidates==null||candidates.length()==0)throw new IOException("模型未返回文本");JSONObject candidate=candidates.getJSONObject(0);
            JSONArray parts=candidate.getJSONObject("content").getJSONArray("parts");StringBuilder b=new StringBuilder();for(int i=0;i<parts.length();i++){JSONObject part=parts.getJSONObject(i);if(!part.optBoolean("thought"))b.append(part.optString("text"));}text=b.toString();cut=candidate.optString("finishReason").equals("MAX_TOKENS");usage=response.optJSONObject("usageMetadata");
        }
        if(text.trim().isEmpty()||text.equals("null"))throw new IOException("模型未返回正文，请检查模型或输出额度");
        return new Reply(text,usage,TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start),cut);
    }
}
