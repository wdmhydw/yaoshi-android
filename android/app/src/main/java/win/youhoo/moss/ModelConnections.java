package win.youhoo.moss;

import android.content.Context;
import android.security.keystore.*;
import android.util.Base64;
import org.json.*;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.*;
import java.util.*;

/** Per-app connections; only credential references leave this class. */
final class ModelConnections {
    private final Context context;private final File file;
    private JSONObject document;
    ModelConnections(Context c)throws Exception{context=c.getApplicationContext();file=new File(context.getFilesDir(),"model-connections.json");document=file.isFile()?new JSONObject(Files.readString(file.toPath(),StandardCharsets.UTF_8)):new JSONObject().put("schemaVersion",1).put("connections",new JSONArray()).put("defaultConnectionId","");}
    private static String id(String value)throws IOException{if(!value.matches("[A-Za-z0-9_-]{1,80}"))throw new IOException("连接标识有误");return value;}
    synchronized JSONObject find(String id)throws Exception{JSONArray rows=document.getJSONArray("connections");for(int i=0;i<rows.length();i++)if(id.equals(rows.getJSONObject(i).optString("id")))return new JSONObject(rows.getJSONObject(i).toString());return null;}
    synchronized JSONObject selection()throws Exception{JSONObject c=find(document.optString("defaultConnectionId"));if(c==null)throw new IOException("请先在 AI 模型中设置默认连接");return c;}
    synchronized JSONObject publicState()throws Exception{
        JSONObject out=new JSONObject(document.toString());JSONArray rows=out.getJSONArray("connections");for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);row.remove("identity");row.put("configured",context.getSharedPreferences("model-credentials",0).contains(row.getString("id")));}return out;
    }
    private SecretKey secretKey()throws Exception{
        KeyStore store=KeyStore.getInstance("AndroidKeyStore");store.load(null);String alias="yaoshi.model-access.v1";
        if(store.containsAlias(alias))return (SecretKey)store.getKey(alias,null);
        KeyGenerator generator=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());return generator.generateKey();
    }
    synchronized String key(String connectionId)throws Exception{
        String encrypted=context.getSharedPreferences("model-credentials",0).getString(id(connectionId),"");if(encrypted.isEmpty())return "";
        String[] parts=encrypted.split(":",2);Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,secretKey(),new GCMParameterSpec(128,Base64.decode(parts[0],Base64.NO_WRAP)));
        return new String(cipher.doFinal(Base64.decode(parts[1],Base64.NO_WRAP)),StandardCharsets.UTF_8);
    }
    private void key(String connectionId,String value)throws Exception{
        android.content.SharedPreferences.Editor editor=context.getSharedPreferences("model-credentials",0).edit();
        if(value.isEmpty())editor.remove(connectionId);else{
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,secretKey());
            editor.putString(connectionId,Base64.encodeToString(cipher.getIV(),Base64.NO_WRAP)+":"+Base64.encodeToString(cipher.doFinal(value.getBytes(StandardCharsets.UTF_8)),Base64.NO_WRAP));
        }
        if(!editor.commit())throw new IOException("密钥保存失败");
    }
    synchronized String draftKey(JSONObject draft)throws Exception{
        String value=draft.optString("apiKey").trim();
        if(value.length()>8192||value.contains("\n")||value.contains("\r"))throw new IOException("密钥格式有误");return !value.isEmpty()?value:draft.optBoolean("clearKey")?"":key(draft.getString("id"));
    }
    static JSONObject normalize(JSONObject d)throws Exception{
        JSONObject c=new JSONObject();c.put("id",id(d.getString("id"))).put("name",d.optString("name","AI 连接").trim()).put("presetId",d.optString("presetId"));
        if(c.getString("name").isEmpty()||c.getString("name").length()>80)throw new IOException("连接名称请控制在 1 到 80 字");
        String api=d.optString("api"),auth=d.optString("authMode","bearer");if(!AiWire.APIS.contains(api))throw new IOException("请选择 API 协议");
        String discovery=d.optString("discovery","openai");if(!Arrays.asList("openai","anthropic","google","bailian","cc-switch").contains(discovery))discovery="openai";c.put("discovery",discovery);
        if(!Arrays.asList("none","bearer","x-api-key","api-key","x-goog-api-key").contains(auth))throw new IOException("请选择鉴权方式");
        c.put("api",api).put("authMode",auth).put("baseUrl",AiWire.base(d.optString("baseUrl"))).put("modelsPath",d.optString("modelsPath","models").trim()).put("inferencePath",d.optString("inferencePath").trim()).put("apiVersion",d.optString("apiVersion","2023-06-01"));
        AiWire.endpoint(c,c.getString("modelsPath"));if(!c.getString("inferencePath").isEmpty()){if(api.equals("google-generative-ai"))AiWire.googleEndpoint(c,c.getString("inferencePath"));else AiWire.endpoint(c,c.getString("inferencePath"));}
        JSONObject headers=d.optJSONObject("extraHeaders");c.put("extraHeaders",headers==null?new JSONObject():headers);
        if(c.getJSONObject("extraHeaders").toString().length()>4000)throw new IOException("附加请求头过长");
        for(Iterator<String> it=c.getJSONObject("extraHeaders").keys();it.hasNext();){String name=it.next(),value=c.getJSONObject("extraHeaders").optString(name);if(name.matches("(?i).*(authorization|api[-_]?key|token|cookie|secret).*"))throw new IOException("密钥请填写在专用密钥栏");if(!name.matches("[A-Za-z0-9-]{1,80}")||value.contains("\n")||value.contains("\r"))throw new IOException("附加请求头格式有误");}
        if(c.getString("apiVersion").contains("\n")||c.getString("apiVersion").contains("\r"))throw new IOException("API 版本格式有误");
        JSONArray enabled=d.optJSONArray("enabledModelIds"),models=d.optJSONArray("models");if(enabled==null)enabled=new JSONArray();if(models==null)models=new JSONArray();
        if(enabled.length()>5000||models.length()>5000)throw new IOException("模型数量超过上限");
        JSONArray cleanModels=new JSONArray(),cleanEnabled=new JSONArray();Set<String> modelIds=new HashSet<>(),enabledIds=new HashSet<>();
        for(int i=0;i<models.length();i++){
            JSONObject m=models.getJSONObject(i);String mid=modelId(m.optString("id"));if(modelIds.add(mid))cleanModels.put(new JSONObject().put("id",mid).put("name",m.optString("name",mid)).put("source",Arrays.asList("remote","cache","manual").contains(m.optString("source"))?m.optString("source"):"manual").put("capabilities",JSONObject.NULL));
        }
        for(int i=0;i<enabled.length();i++){String mid=modelId(enabled.getString(i));if(enabledIds.add(mid))cleanEnabled.put(mid);if(modelIds.add(mid))cleanModels.put(new JSONObject().put("id",mid).put("name",mid).put("source","manual").put("capabilities",JSONObject.NULL));}
        String selected=d.optString("selectedModelId").trim();if(!selected.isEmpty()&&!enabledIds.contains(selected))throw new IOException("请从启用的模型中选择默认模型");
        c.put("models",cleanModels).put("enabledModelIds",cleanEnabled).put("selectedModelId",selected).put("credentialRef",c.getString("id"));return c;
    }
    private static String modelId(String raw)throws IOException{String id=raw.trim();if(id.isEmpty()||id.length()>512||id.contains("\n")||id.contains("\r"))throw new IOException("模型 ID 有误");return id;}
    static String fingerprint(JSONObject c,String key)throws Exception{
        StringBuilder input=new StringBuilder();for(String field:new String[]{"id","baseUrl","api","authMode","discovery","modelsPath","inferencePath","apiVersion"})input.append(c.optString(field)).append('\n');
        JSONObject headers=c.optJSONObject("extraHeaders");if(headers!=null){List<String> keys=new ArrayList<>();headers.keys().forEachRemaining(keys::add);Collections.sort(keys);for(String h:keys)input.append(h).append(':').append(headers.optString(h)).append('\n');}
        input.append(key);return hash(input.toString());
    }
    static String hash(String s)throws Exception{return hash(s.getBytes(StandardCharsets.UTF_8));}
    static String hash(byte[] data)throws Exception{byte[] bytes=MessageDigest.getInstance("SHA-256").digest(data);StringBuilder out=new StringBuilder();for(byte b:bytes)out.append(String.format(Locale.ROOT,"%02x",b));return out.toString();}
    synchronized void save(JSONObject raw,JSONObject discovery,JSONObject test)throws Exception{
        JSONObject c=normalize(raw),previous=find(c.getString("id"));String secret=draftKey(raw),identity=fingerprint(c,secret);
        if(previous!=null&&raw.optLong("revision")!=previous.optLong("revision"))throw new IOException("连接已更新，请重新打开再保存");
        boolean same=previous!=null&&identity.equals(previous.optString("identity"));
        if(!same){JSONArray models=c.getJSONArray("models");for(int i=0;i<models.length();i++)models.getJSONObject(i).put("source","manual");}
        c.put("revision",System.currentTimeMillis()).put("identity",identity).put("credentialRevision",same?previous.optLong("credentialRevision"):System.currentTimeMillis());
        c.put("directoryFetchedAt",same?previous.optLong("directoryFetchedAt"):0).put("directoryStatus",same?previous.optString("directoryStatus","idle"):"idle");
        c.put("test",same&&c.optString("selectedModelId").equals(previous.optString("selectedModelId"))?previous.optJSONObject("test"):new JSONObject().put("status","untested"));
        if(discovery!=null&&identity.equals(discovery.optString("identity"))){c.put("directoryFetchedAt",discovery.optLong("at")).put("directoryStatus",discovery.optString("status"));Set<String> remoteIds=new HashSet<>();JSONArray rows=discovery.optJSONArray("models");if(rows!=null)for(int i=0;i<rows.length();i++)remoteIds.add(rows.getJSONObject(i).getString("id"));JSONArray models=c.getJSONArray("models");for(int i=0;i<models.length();i++)if(remoteIds.contains(models.getJSONObject(i).getString("id")))models.getJSONObject(i).put("source","remote");}
        if(test!=null&&identity.equals(test.optString("identity"))&&c.optString("selectedModelId").equals(test.optString("modelId"))){JSONObject safe=new JSONObject(test.toString());safe.remove("identity");c.put("test",safe);}
        JSONArray rows=document.getJSONArray("connections"),updated=new JSONArray();boolean found=false;
        for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);if(row.getString("id").equals(c.getString("id"))){updated.put(c);found=true;}else updated.put(row);}if(!found)updated.put(c);
        JSONObject next=new JSONObject(document.toString()).put("connections",updated);if(raw.optBoolean("makeDefault"))next.put("defaultConnectionId",c.getString("id"));
        String oldSecret=key(c.getString("id"));key(c.getString("id"),secret);
        try{write(next);}catch(Exception failure){try{key(c.getString("id"),oldSecret);}catch(Exception rollback){failure.addSuppressed(rollback);}throw failure;}
    }
    synchronized void use(String id)throws Exception{if(find(id)==null)throw new IOException("连接不存在");write(new JSONObject(document.toString()).put("defaultConnectionId",id));}
    synchronized void remove(String id,boolean clearDefault)throws Exception{
        if(id.equals(document.optString("defaultConnectionId"))&&!clearDefault)throw new IOException("请先切换默认连接或确认清空默认项");
        JSONObject next=new JSONObject(document.toString());JSONArray rows=next.getJSONArray("connections"),kept=new JSONArray();for(int i=0;i<rows.length();i++)if(!id.equals(rows.getJSONObject(i).optString("id")))kept.put(rows.getJSONObject(i));next.put("connections",kept);if(id.equals(next.optString("defaultConnectionId")))next.put("defaultConnectionId","");write(next);key(id,"");
    }
    private void write(JSONObject next)throws IOException{TranscriptStore.write(file,next);document=next;}
}
