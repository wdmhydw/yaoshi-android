package win.youhoo.moss;

import android.content.Context;
import android.media.*;
import android.net.Uri;
import org.json.JSONObject;

/** Native source playback, independent of transcription and its decoder process. */
final class SourcePlayer {
    interface Events { void changed(); void error(String message); }
    private final Context context;
    private final Events events;
    private final AudioManager audio;
    private final AudioAttributes attributes=new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build();
    private final AudioFocusRequest focus;
    private MediaPlayer media;
    private String key="",error="";
    private boolean prepared=false,loading=false;
    private double pendingPosition=0;
    private float speed=1f;
    SourcePlayer(Context c,Events e){
        context=c;events=e;audio=(AudioManager)c.getSystemService(Context.AUDIO_SERVICE);
        focus=new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener(change->{if(change<0)pause();}).build();
    }
    void open(String sourceKey,Uri uri,double position,boolean autoplay){
        release();key=sourceKey;pendingPosition=Math.max(0,position);loading=true;error="";
        MediaPlayer p=new MediaPlayer();media=p;p.setAudioAttributes(attributes);
        p.setOnPreparedListener(ready->{
            if(media!=p)return;prepared=true;loading=false;
            if(pendingPosition>0)p.seekTo(Math.min((long)(pendingPosition*1000),Math.max(0,p.getDuration()-1)),MediaPlayer.SEEK_CLOSEST);
            if(autoplay)play();events.changed();
        });
        p.setOnCompletionListener(done->{audio.abandonAudioFocusRequest(focus);events.changed();});
        p.setOnErrorListener((failed,what,extra)->{if(media!=p)return true;fail();return true;});
        try{p.setDataSource(context,uri);p.prepareAsync();events.changed();}catch(Exception ex){fail();}
    }
    private void fail(){String old=key;release();key=old;error="原音频暂不可读，请重新关联文件";events.error(error);events.changed();}
    void toggle(){if(!prepared)return;if(media.isPlaying())pause();else{if(media.getCurrentPosition()>=media.getDuration()-80)media.seekTo(0);play();}}
    private void play(){
        if(!prepared)return;
        if(audio.requestAudioFocus(focus)!=AudioManager.AUDIOFOCUS_REQUEST_GRANTED){events.error("其他应用正在使用音频，请稍后重试");return;}
        try{media.start();media.setPlaybackParams(new PlaybackParams().setSpeed(speed).setPitch(1f));events.changed();}
        catch(Exception e){fail();}
    }
    void seek(double seconds,boolean autoplay){if(!prepared)return;media.seekTo(Math.max(0,Math.min((long)(seconds*1000),media.getDuration())),MediaPlayer.SEEK_CLOSEST);if(autoplay)play();events.changed();}
    void speed(float value){speed=Math.max(.5f,Math.min(2f,value));if(prepared&&media.isPlaying())media.setPlaybackParams(new PlaybackParams().setSpeed(speed).setPitch(1f));events.changed();}
    void pause(){if(prepared&&media!=null&&media.isPlaying())media.pause();audio.abandonAudioFocusRequest(focus);events.changed();}
    boolean matches(String sourceKey){return key.equals(sourceKey)&&media!=null;}
    JSONObject state(){JSONObject out=new JSONObject();try{
        out.put("key",key).put("ready",prepared).put("loading",loading).put("playing",prepared&&media.isPlaying())
            .put("position",prepared?media.getCurrentPosition()/1000.0:pendingPosition).put("duration",prepared?media.getDuration()/1000.0:0)
            .put("speed",speed).put("error",error);
    }catch(Exception ignored){}return out;}
    void release(){if(media!=null){media.setOnPreparedListener(null);media.setOnErrorListener(null);media.setOnCompletionListener(null);media.release();media=null;}
        audio.abandonAudioFocusRequest(focus);prepared=false;loading=false;key="";pendingPosition=0;error="";}
}
