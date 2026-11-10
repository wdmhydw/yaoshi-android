package win.youhoo.moss;
import org.json.*;
import java.util.*;

public final class SpeakerIdentityTest {
    static int count;
    static void check(boolean b,String why){count++;if(!b)throw new AssertionError(why);}
    static LinkedHashMap<String,float[]> samples(Object... pairs){LinkedHashMap<String,float[]> out=new LinkedHashMap<>();for(int i=0;i<pairs.length;i+=2)out.put((String)pairs[i],(float[])pairs[i+1]);return out;}
    static JSONObject row(String id,String key)throws Exception{return new JSONObject().put("id",id).put("speaker",key);}
    static String mapped(JSONObject n,String key){return SpeakerIdentity.object(n,"autoSpeakers").optString(key);}
    public static void main(String[] args)throws Exception{
        JSONObject n=new JSONObject();float[] a={1,0,0},b={0,1,0};
        SpeakerIdentity.match(n,samples("片段1-S01",a,"片段1-S02",b),Set.of("片段1-S01","片段1-S02"));String aa=mapped(n,"片段1-S01"),bb=mapped(n,"片段1-S02");
        check(!aa.equals(bb),"distinct voices get distinct identities");
        SpeakerIdentity.match(n,samples("片段2-S02",a,"片段2-S01",b),Set.of("片段2-S02","片段2-S01"));
        check(mapped(n,"片段2-S02").equals(aa),"swapped local IDs match voice, not number");
        check(mapped(n,"片段2-S01").equals(bb),"second speaker remains independent");
        String persisted=n.toString();SpeakerIdentity.match(n,samples("片段2-S02",b),Set.of("片段2-S02"));check(n.toString().equals(persisted),"resume does not overwrite an existing assignment");
        JSONObject one=new JSONObject();SpeakerIdentity.match(one,samples("c1",a),Set.of("c1"));
        SpeakerIdentity.match(one,samples("x",a,"y",a),Set.of("x","y"));check(!mapped(one,"x").equals(mapped(one,"y")),"one-to-one assignment within a chunk");
        JSONObject invalid=new JSONObject();SpeakerIdentity.match(invalid,samples("z",new float[]{Float.NaN,0}),Set.of("z"));check(mapped(invalid,"z").isEmpty(),"invalid embeddings do not create profiles");
        check(SpeakerIdentity.similarity(new float[]{0,0},new float[]{1,1})<0,"silence cannot match");
        check(SpeakerIdentity.similarity(a,new float[]{1,0})<0,"dimension mismatch cannot match");
        JSONObject human=new JSONObject().put("speakerNames",new JSONObject().put("a","张三").put("b","李四"));SpeakerIdentity.match(human,samples("a",a),Set.of("a"));SpeakerIdentity.match(human,samples("b",a),Set.of("b"));check(!mapped(human,"a").equals(mapped(human,"b")),"conflicting human names prevent automatic merge");
        JSONArray raw=new JSONArray().put(row("1","片段1-S01")).put(row("2","片段2-S02")).put(row("3","片段1-S02"));JSONObject d=new JSONObject().put("segments",new JSONArray(raw.toString()));SpeakerIdentity.decorate(d,n);JSONArray rows=d.getJSONArray("segments");
        check(rows.getJSONObject(0).getString("speaker").equals(rows.getJSONObject(1).getString("speaker")),"same speaker shares canonical key");
        check(rows.getJSONObject(0).getInt("speakerNumber")==rows.getJSONObject(1).getInt("speakerNumber"),"same speaker shares display number");
        n.put("speakerNames",new JSONObject().put(aa,"主持人"));SpeakerIdentity.decorate(d,n);check(d.getJSONObject("speakerNames").getString(aa).equals("主持人"),"canonical name available to all consumers");
        SpeakerIdentity.assign(n,rows,"2","new");SpeakerIdentity.decorate(d,n);String manual=rows.getJSONObject(1).getString("speaker");check(!manual.equals(aa),"single sentence can split automatic grouping");check(rows.getJSONObject(0).getString("speaker").equals(aa),"split leaves other sentences intact");
        SpeakerIdentity.assign(n,rows,"2",bb);SpeakerIdentity.decorate(d,n);check(rows.getJSONObject(1).getString("speaker").equals(bb),"single sentence can move to existing identity");
        SpeakerIdentity.assign(n,rows,"2","reset");SpeakerIdentity.decorate(d,n);check(rows.getJSONObject(1).getString("speaker").equals(aa),"reset restores automatic result");
        SpeakerIdentity.merge(n,rows,aa,bb);SpeakerIdentity.decorate(d,n);for(int i=0;i<rows.length();i++)check(rows.getJSONObject(i).getString("speaker").equals(bb),"manual merge visible throughout document");
        JSONObject again=new JSONObject().put("segments",new JSONArray(raw.toString()));SpeakerIdentity.decorate(again,new JSONObject(n.toString()));check(again.getJSONArray("segments").getJSONObject(0).getString("speaker").equals(bb),"manual merge survives reload from raw engine result");
        check(raw.getJSONObject(0).getString("speaker").equals("片段1-S01"),"raw ASR evidence preserved");
        SpeakerIdentity.assign(n,rows,"1","new");SpeakerIdentity.decorate(d,n);check(!rows.getJSONObject(0).getString("speaker").equals(bb),"manual correction can split after merge");
        try{SpeakerIdentity.assign(n,rows,"missing",bb);throw new AssertionError("missing ID accepted");}catch(IllegalArgumentException expected){count++;}
        try{SpeakerIdentity.merge(n,rows,bb,bb);throw new AssertionError("self merge accepted");}catch(IllegalArgumentException expected){count++;}
        JSONObject cycle=new JSONObject().put("speakerLinks",new JSONObject().put("a","b").put("b","a"));check(!SpeakerIdentity.root(cycle,"a").isEmpty(),"corrupt cycles are bounded");
        System.out.println(count+" speaker identity checks passed");
    }
}
