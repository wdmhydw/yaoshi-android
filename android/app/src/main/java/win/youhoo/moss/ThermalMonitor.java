package win.youhoo.moss;

import android.content.*;
import android.os.*;
import org.json.*;

/** Read-only Android telemetry. Workload scheduling and system thermal controls are never changed. */
final class ThermalMonitor {
    private final Context context;
    private final PowerManager power;
    private long sampledAt=-1,headroomAt=-1;
    private double battery=Double.NaN,headroom=Double.NaN,peakBattery=Double.NaN;
    private int thermalStatus=-1,peakStatus=-1;
    private boolean charging;

    ThermalMonitor(Context c){context=c;power=c.getSystemService(PowerManager.class);}
    void poll(){
        long now=SystemClock.elapsedRealtime();
        if(sampledAt>=0&&now-sampledAt<2000)return;
        sampledAt=now;
        try{thermalStatus=power.getCurrentThermalStatus();}catch(Exception e){thermalStatus=-1;}
        battery=Double.NaN;
        try{
            Intent value=context.registerReceiver(null,new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if(value!=null){
                if(value.hasExtra(BatteryManager.EXTRA_TEMPERATURE)){
                    double candidate=value.getIntExtra(BatteryManager.EXTRA_TEMPERATURE,Integer.MIN_VALUE)/10.0;
                    if(candidate>=-20&&candidate<=80)battery=candidate;
                }
                charging=value.getIntExtra(BatteryManager.EXTRA_PLUGGED,0)!=0;
            }
        }catch(Exception ignored){}
        if(headroomAt<0||now-headroomAt>=10_000){
            headroomAt=now;
            try{headroom=power.getThermalHeadroom(10);}catch(Exception e){headroom=Double.NaN;}
        }
        if(Double.isFinite(battery))peakBattery=Double.isFinite(peakBattery)?Math.max(peakBattery,battery):battery;
        peakStatus=Math.max(peakStatus,thermalStatus);
    }
    JSONObject snapshot(int threads){
        try{
            String label=thermalStatus>=1?"系统正在调节性能":"由系统调节";
            return new JSONObject().put("mode","system").put("label",label).put("appThrottling",false)
                .put("batteryC",number(battery)).put("peakBatteryC",number(peakBattery))
                .put("systemStatus",thermalStatus).put("peakSystemStatus",peakStatus)
                .put("headroom",number(headroom)).put("charging",charging).put("threads",threads)
                .put("targetThreads",threads).put("pausedSeconds",0)
                .put("cpuHotspotAvailable",false).put("sampledAt",sampledAt);
        }catch(JSONException e){return new JSONObject();}
    }
    private static Object number(double value){return Double.isFinite(value)?value:JSONObject.NULL;}
}
