package win.youhoo.moss;

import org.json.*;
import java.util.*;

/** Record-local identity graph. Raw diarizer keys and segment IDs remain immutable. */
final class SpeakerIdentity {
    static JSONObject object(JSONObject doc,String key){JSONObject o=doc.optJSONObject(key);return o==null?new JSONObject():o;}
    static String raw(JSONObject row){return row.optString("originalSpeaker",row.optString("speaker","S01"));}
    static String root(JSONObject notes,String key){
        JSONObject links=object(notes,"speakerLinks");Set<String> seen=new HashSet<>();
        while(links.has(key)&&seen.add(key)){String next=links.optString(key);if(next.isEmpty())break;key=next;}
        return key;
    }
    static String resolve(JSONObject notes,JSONObject row){
        String key=raw(row),id=row.optString("id");
        key=object(notes,"autoSpeakers").optString(key,key);
        key=object(notes,"segmentSpeakers").optString(id,key);
        return root(notes,key);
    }
    static JSONObject names(JSONObject notes,JSONArray rows)throws JSONException{
        JSONObject result=new JSONObject(),names=object(notes,"speakerNames");
        // Explicit canonical names win over imported legacy aliases.
        for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);String key=resolve(notes,row);if(names.has(key))result.put(key,names.getString(key));}
        for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);String key=resolve(notes,row),raw=raw(row);
            if(!result.has(key)&&names.has(raw))result.put(key,names.getString(raw));}
        return result;
    }
    static void decorate(JSONObject doc,JSONObject notes)throws JSONException{
        JSONArray rows=doc.optJSONArray("segments");if(rows==null)rows=new JSONArray();
        JSONObject names=names(notes,rows);LinkedHashMap<String,Integer> ordinals=new LinkedHashMap<>();
        for(int i=0;i<rows.length();i++){
            JSONObject row=rows.getJSONObject(i);if(!row.has("id"))row.put("id","row_"+i);
            String raw=raw(row),key=resolve(notes,row);ordinals.putIfAbsent(key,ordinals.size()+1);
            row.put("originalSpeaker",raw).put("speaker",key).put("speakerNumber",ordinals.get(key));
        }
        doc.put("speakerNames",names).put("speakerMatch",object(notes,"speakerMatch"));
    }
    static boolean contains(JSONArray rows,String key){for(int i=0;i<rows.length();i++)if(key.equals(rows.optJSONObject(i).optString("speaker")))return true;return false;}
    static void merge(JSONObject notes,JSONArray decorated,String from,String to)throws JSONException{
        if(from.equals(to)||!contains(decorated,from)||!contains(decorated,to))throw new IllegalArgumentException("请选择两位不同的说话人");
        // Freeze every currently visible member as well as future rows of this identity.
        JSONObject links=object(notes,"speakerLinks"),overrides=object(notes,"segmentSpeakers");
        to=root(notes,to);links.put(from,to);notes.put("speakerLinks",links);
        for(int i=0;i<decorated.length();i++){JSONObject row=decorated.getJSONObject(i);if(from.equals(row.optString("speaker")))overrides.put(row.getString("id"),to);}
        notes.put("segmentSpeakers",overrides);
    }
    static void assign(JSONObject notes,JSONArray decorated,String segment,String target)throws JSONException{
        boolean exists=false;for(int i=0;i<decorated.length();i++)if(segment.equals(decorated.getJSONObject(i).optString("id")))exists=true;
        if(!exists)throw new IllegalArgumentException("这句文字已更新，请重新打开");
        JSONObject values=object(notes,"segmentSpeakers");
        if(target.equals("reset"))values.remove(segment);
        else {if(target.equals("new"))target="manual_"+UUID.randomUUID().toString();else if(!contains(decorated,target))throw new IllegalArgumentException("请选择说话人");values.put(segment,root(notes,target));}
        notes.put("segmentSpeakers",values);
    }

    static double similarity(float[] a,float[] b){if(a.length!=b.length||a.length==0)return -1;double dot=0,aa=0,bb=0;
        for(int i=0;i<a.length;i++){if(!Float.isFinite(a[i])||!Float.isFinite(b[i]))return -1;dot+=a[i]*b[i];aa+=a[i]*a[i];bb+=b[i]*b[i];}
        return aa>0&&bb>0?dot/Math.sqrt(aa*bb):-1;
    }
    static float[] vector(JSONArray a){if(a==null)return new float[0];float[] v=new float[a.length()];for(int i=0;i<v.length;i++)v[i]=(float)a.optDouble(i);return v;}
    static JSONArray vector(float[] a)throws JSONException{JSONArray out=new JSONArray();for(float v:a)out.put((double)v);return out;}
    static float[] normalized(float[] a){double norm=0;for(float v:a){if(!Float.isFinite(v))return new float[0];norm+=v*v;}if(norm<1e-12)return new float[0];float[] v=a.clone();for(int i=0;i<v.length;i++)v[i]/=Math.sqrt(norm);return v;}
    private static final class Pair {String raw,key;double score;Pair(String r,String k,double s){raw=r;key=k;score=s;}}
    /** Greedy one-to-one assignment within one diarizer chunk, with ambiguity margin. */
    static void match(JSONObject notes,LinkedHashMap<String,float[]> samples,Set<String> chunkKeys)throws JSONException{
        JSONObject map=object(notes,"autoSpeakers"),profiles=object(notes,"speakerProfiles"),names=object(notes,"speakerNames");
        Set<String> used=new HashSet<>();for(String raw:chunkKeys)if(map.has(raw))used.add(map.getString(raw));
        List<Pair> pairs=new ArrayList<>();
        for(Map.Entry<String,float[]> entry:samples.entrySet()){
            if(map.has(entry.getKey()))continue;String best="";double top=-1,second=-1;
            for(Iterator<String> it=profiles.keys();it.hasNext();){String id=it.next();if(used.contains(id))continue;JSONObject profile=profiles.getJSONObject(id);
                String label=names.optString(entry.getKey()),known=names.optString(id,profile.optString("legacyName"));
                if(!label.isEmpty()&&!known.isEmpty()&&!label.equals(known))continue;
                double score=similarity(entry.getValue(),vector(profile.optJSONArray("vector")));
                if(score>top){second=top;top=score;best=id;}else second=Math.max(second,score);
            }
            if(top>=.60&&top-second>=.06)pairs.add(new Pair(entry.getKey(),best,top));
        }
        pairs.sort((a,b)->Double.compare(b.score,a.score));Map<String,String> matched=new HashMap<>();
        for(Pair p:pairs)if(used.add(p.key))matched.put(p.raw,p.key);
        for(Map.Entry<String,float[]> entry:samples.entrySet()){
            String raw=entry.getKey();if(map.has(raw))continue;float[] v=normalized(entry.getValue());if(v.length==0)continue;
            String id=matched.get(raw);if(id==null){int n=profiles.length()+1;do{id="voice_"+n++;}while(profiles.has(id));}
            JSONObject profile=profiles.optJSONObject(id);int count=0;
            if(profile!=null){count=Math.min(8,profile.optInt("count",1));float[] old=vector(profile.optJSONArray("vector"));if(old.length==v.length){for(int i=0;i<v.length;i++)v[i]=(old[i]*count+v[i])/(count+1);v=normalized(v);}}
            else profile=new JSONObject();
            profile.put("vector",vector(v)).put("count",count+1);String label=names.optString(raw);
            if(!label.isEmpty()){profile.put("legacyName",label);if(!names.has(id))names.put(id,label);}
            profiles.put(id,profile);map.put(raw,id);
        }
        notes.put("autoSpeakers",map).put("speakerProfiles",profiles).put("speakerNames",names);
    }
}
