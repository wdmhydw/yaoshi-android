package win.youhoo.moss;

import org.json.JSONObject;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Unfinished generation stays separate from durable, timestamped segments. */
final class LivePreview {
    private static final Pattern HEADER=Pattern.compile("\\[([0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)\\]\\s*\\[(S[0-9]+)\\]");
    private static final Pattern END=Pattern.compile("[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+");
    static JSONObject draft(String transcript,double offset,double duration,int chunk)throws Exception{
        Matcher headers=HEADER.matcher(transcript);int tail=-1;String speaker="";double start=0;
        while(headers.find()){tail=headers.end();speaker=headers.group(2);start=Double.parseDouble(headers.group(1));}
        if(tail<0||!Double.isFinite(start)||start<0||start>duration)return null;
        String text=transcript.substring(tail);int bracket=text.indexOf('[');
        if(bracket>=0){
            int close=text.indexOf(']',bracket+1);
            // A closed end marker belongs to the complete-segment channel.
            if(close>bracket&&END.matcher(text.substring(bracket+1,close)).matches())return null;
            text=text.substring(0,bracket);
        }
        text=text.trim();if(text.isEmpty())return null;
        return new JSONObject().put("id","draft_"+chunk).put("start",offset+start)
            .put("speaker","片段"+(chunk+1)+"-"+speaker).put("text",text).put("provisional",true);
    }
}
