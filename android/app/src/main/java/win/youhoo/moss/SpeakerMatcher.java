package win.youhoo.moss;

import android.content.Context;
import org.json.*;
import java.util.*;

/** Matches local MOSS speaker groups using non-overlapping source audio only. */
final class SpeakerMatcher implements AutoCloseable {
    interface Gate { void check()throws Exception; }
    private final Context context;private final String record;private SpeakerEmbedding embedding;
    SpeakerMatcher(Context c,String id){context=c;record=id;}
    void accept(AudioDecoder.Chunk chunk,JSONArray rows,Gate gate)throws Exception{
        JSONObject notes=TranscriptStore.annotations(context,record),map=SpeakerIdentity.object(notes,"autoSpeakers");
        LinkedHashMap<String,List<double[]>> spans=new LinkedHashMap<>();Map<String,Set<String>> groups=new LinkedHashMap<>();
        for(int i=0;i<rows.length();i++){
            JSONObject row=rows.getJSONObject(i);String key=SpeakerIdentity.raw(row),group=key.contains("-")?key.substring(0,key.lastIndexOf('-')):"all";
            groups.computeIfAbsent(group,k->new LinkedHashSet<>()).add(key);
            if(map.has(key))continue;double start=Math.max(chunk.start,row.optDouble("start")),end=Math.min(chunk.start+chunk.duration,row.optDouble("end"));
            if(!Double.isFinite(start)||!Double.isFinite(end)||end-start<.35)continue;
            List<double[]> clean=new ArrayList<>();clean.add(new double[]{start,end});
            for(int j=0;j<rows.length();j++){JSONObject other=rows.getJSONObject(j);if(key.equals(SpeakerIdentity.raw(other)))continue;
                double a=other.optDouble("start")-.08,b=other.optDouble("end")+.08;if(b<=start||a>=end)continue;
                List<double[]> next=new ArrayList<>();for(double[] s:clean){if(a>=s[1]||b<=s[0])next.add(s);else{if(a>s[0])next.add(new double[]{s[0],a});if(b<s[1])next.add(new double[]{b,s[1]});}}clean=next;
            }
            List<double[]> list=spans.computeIfAbsent(key,k->new ArrayList<>());
            for(double[] s:clean)if(s[1]-s[0]>=.35)list.add(new double[]{s[0]-chunk.start,s[1]-chunk.start});
        }
        LinkedHashMap<String,float[]> vectors=new LinkedHashMap<>();
        for(Map.Entry<String,List<double[]>> e:spans.entrySet()){
            gate.check();List<double[]> intervals=e.getValue();intervals.sort((a,b)->Double.compare(b[1]-b[0],a[1]-a[0]));
            double seconds=0;for(double[] s:intervals)seconds+=s[1]-s[0];if(seconds<1.2)continue;
            if(embedding==null)embedding=new SpeakerEmbedding(context);
            float[] v=embedding.excerpt(chunk.file,intervals);gate.check();if(v.length>0)vectors.put(e.getKey(),v);
        }
        for(Set<String> keys:groups.values()){
            LinkedHashMap<String,float[]> part=new LinkedHashMap<>();for(String key:keys)if(vectors.containsKey(key))part.put(key,vectors.get(key));
            if(!part.isEmpty())TranscriptStore.matchSpeakers(context,record,part,keys);
        }
    }
    public void close(){if(embedding!=null)embedding.close();}
}
