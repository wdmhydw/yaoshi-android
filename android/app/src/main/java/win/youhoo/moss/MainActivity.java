package win.youhoo.moss;

import android.app.*;
import android.content.*;
import android.content.res.Configuration;
import android.graphics.Color;
import android.net.Uri;
import android.os.*;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.view.*;
import android.webkit.*;
import android.widget.FrameLayout;
import org.json.*;
import java.io.*;
import java.lang.Process;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Local presentation layer. ASR stays offline; optional AI calls run in native code. */
public final class MainActivity extends Activity {
    private static native String nativeProbeLibraries();
    private static final int AUDIO=10, MODEL=11, RELINK=13, SAVE_FOLDER=14;
    private static final String ORIGIN="https://moss.local/";
    private final Handler handler=new Handler(Looper.getMainLooper());
    private WebView web;
    private Uri audio;
    private String audioName="", selectedBackend="cpu";
    private String relinkScope="active",relinkRecord="";
    private SourcePlayer player;
    private AiController ai;
    private String aiInitError="";
    private boolean ready=false, resumed=false, reducedMotion=false, backgroundAllowed=false;
    private android.graphics.Insets contentInsets=android.graphics.Insets.NONE;
    private String historyId="", lastHistorySignature="";
    private long historyAnnotationVersion=-1;
    private final Set<String> speakerAutoChecked=new HashSet<>();
    private JSONObject historyDocument;
    private JSONArray historyRows=new JSONArray();
    private volatile byte[] orbMedia;
    private String sentPreview;
    private JSONArray sentHistory;
    private JSONObject sentHistoryDocument;
    private final java.util.concurrent.ExecutorService searchWorker=java.util.concurrent.Executors.newSingleThreadExecutor();
    private volatile int searchVersion=0;
    private String searchQuery="",searchError="";
    private boolean searching=false;
    private JSONArray searchRows=new JSONArray(),sentSearchRows;
    private final Runnable tick=new Runnable(){public void run(){sendState();if(resumed)handler.postDelayed(this,300);}};

    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);
        RecordingRecovery.recover(this);
        selectedBackend=TranscribeService.normalizedBackend(getPreferences(0).getString("backend","cpu"));
        reducedMotion=getPreferences(0).getBoolean("reducedMotion",false);
        player=new SourcePlayer(this,new SourcePlayer.Events(){public void changed(){sendState();}public void error(String message){notice(message);}});
        try{ai=new AiController(this,message->handler.post(()->{if(message.optString("kind").equals("minutesSaved"))lastHistorySignature="";js("window.MossAI&&MossAI.receive("+message.toString()+")");}));}catch(Exception e){aiInitError="AI 设置读取失败："+e.getMessage();}
        FileLibrary.resume(this);
        if(saved!=null){String uri=saved.getString("audio");if(uri!=null)audio=Uri.parse(uri);audioName=saved.getString("audioName","");}
        getWindow().setDecorFitsSystemWindows(false);
        getWindow().setStatusBarColor(Color.TRANSPARENT);getWindow().setNavigationBarColor(Color.TRANSPARENT);
        getWindow().setStatusBarContrastEnforced(false);getWindow().setNavigationBarContrastEnforced(false);
        WindowManager.LayoutParams windowLayout=getWindow().getAttributes();
        windowLayout.layoutInDisplayCutoutMode=WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        getWindow().setAttributes(windowLayout);
        FrameLayout host=new FrameLayout(this);host.setBackgroundColor(Color.rgb(3,3,4));
        web=new WebView(this);web.setBackgroundColor(Color.rgb(3,3,4));
        WebSettings settings=web.getSettings();settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(false);settings.setAllowFileAccess(false);settings.setAllowContentAccess(false);
        settings.setSupportZoom(false);settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setTextZoom(Math.round(getResources().getConfiguration().fontScale*100));
        WebView.setWebContentsDebuggingEnabled((getApplicationInfo().flags&android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE)!=0);
        web.addJavascriptInterface(new Bridge(),"MossNative");
        web.setWebChromeClient(new WebChromeClient(){
            @Override public android.graphics.Bitmap getDefaultVideoPoster(){
                // Decorative video has its own full-resolution still. Never
                // enlarge Android's grey play-button bitmap across the scene.
                return android.graphics.Bitmap.createBitmap(1,1,android.graphics.Bitmap.Config.ARGB_8888);
            }
        });
        web.setWebViewClient(new WebViewClient(){
            @Override public boolean shouldOverrideUrlLoading(WebView v,WebResourceRequest request){return true;}
            @Override public WebResourceResponse shouldInterceptRequest(WebView v,WebResourceRequest request){
                Uri uri=request.getUrl();
                if(!"https".equals(uri.getScheme())||!"moss.local".equals(uri.getHost()))return denied();
                String path=uri.getPath();if(path==null||path.equals("/"))path="/index.html";
                if(path.contains(".."))return denied();
                if(path.equals("/media/orb.mp4"))return media(request);
                String mime=path.endsWith(".js")?"text/javascript":path.endsWith(".css")?"text/css":path.endsWith(".ttf")?"font/ttf":path.endsWith(".woff2")?"font/woff2":path.endsWith(".webp")?"image/webp":"text/html";
                try{return new WebResourceResponse(mime,path.endsWith(".ttf")?null:"UTF-8",getAssets().open("ui"+path));}
                catch(IOException e){return denied();}
            }
            @Override public void onPageFinished(WebView v,String url){if(url.startsWith(ORIGIN)){ready=true;resetStateCache();sendState();}}
        });
        host.addView(web,new FrameLayout.LayoutParams(-1,-1));
        host.setOnApplyWindowInsetsListener((v,in)->{
            android.graphics.Insets bars=in.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());
            android.graphics.Insets ime=in.getInsets(WindowInsets.Type.ime());
            // The WebView paints behind system bars; only the keyboard reduces its viewport.
            int keyboard=Math.max(0,ime.bottom);
            if(v.getPaddingBottom()!=keyboard)v.setPadding(0,0,0,keyboard);
            android.graphics.Insets safe=android.graphics.Insets.of(bars.left,bars.top,bars.right,keyboard>0?0:bars.bottom);
            if(!safe.equals(contentInsets)){contentInsets=safe;sendState();}
            return WindowInsets.CONSUMED;
        });
        setContentView(host);host.requestApplyInsets();web.loadUrl(ORIGIN);
        getWindow().getInsetsController().setSystemBarsAppearance(0,WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS|WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
        getOnBackInvokedDispatcher().registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,this::handleBack);
        if(Build.VERSION.SDK_INT>=33&&checkSelfPermission("android.permission.POST_NOTIFICATIONS")!=0)
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"},20);
    }
    private void handleBack(){web.evaluateJavascript("window.Moss?Moss.back():false",value->{if(!"true".equals(value))moveTaskToBack(true);});}
    @SuppressWarnings("deprecation") @Override public void onBackPressed(){handleBack();}
    private WebResourceResponse denied(){return new WebResourceResponse("text/plain","UTF-8",404,"Not found",Collections.emptyMap(),new ByteArrayInputStream(new byte[0]));}
    private WebResourceResponse media(WebResourceRequest request){
        try{
            if(orbMedia==null)synchronized(this){if(orbMedia==null)try(InputStream in=getAssets().open("ui/media/orb.mp4")){orbMedia=in.readAllBytes();}}
            byte[] data=orbMedia;int start=0,end=data.length-1,status=200;String range=null;
            for(Map.Entry<String,String> h:request.getRequestHeaders().entrySet())if(h.getKey().equalsIgnoreCase("Range"))range=h.getValue();
            Map<String,String> headers=new HashMap<>();headers.put("Accept-Ranges","bytes");headers.put("Cache-Control","max-age=31536000");
            if(range!=null&&range.startsWith("bytes=")){
                String[] bounds=range.substring(6).split("-",-1);
                if(bounds.length!=2)return denied();
                if(bounds[0].isEmpty())start=Math.max(0,data.length-Integer.parseInt(bounds[1]));
                else{start=Integer.parseInt(bounds[0]);if(!bounds[1].isEmpty())end=Math.min(end,Integer.parseInt(bounds[1]));}
                if(start<0||start>end||start>=data.length)return denied();status=206;
                headers.put("Content-Range","bytes "+start+"-"+end+"/"+data.length);
            }
            headers.put("Content-Length",String.valueOf(end-start+1));
            return new WebResourceResponse("video/mp4",null,status,status==206?"Partial Content":"OK",headers,new ByteArrayInputStream(data,start,end-start+1));
        }catch(Exception e){return denied();}
    }
    @Override public void onConfigurationChanged(Configuration c){super.onConfigurationChanged(c);web.getSettings().setTextZoom(Math.round(c.fontScale*100));sendState();}
    @Override public void onSaveInstanceState(Bundle b){super.onSaveInstanceState(b);if(audio!=null)b.putString("audio",audio.toString());b.putString("audioName",audioName);}
    @Override protected void onResume(){super.onResume();resumed=true;web.onResume();
        backgroundAllowed=getSystemService(PowerManager.class).isIgnoringBatteryOptimizations(getPackageName());
        handler.removeCallbacks(tick);handler.post(tick);js("Moss.setVisible(true)");}
    @Override protected void onPause(){resumed=false;handler.removeCallbacks(tick);player.pause();js("Moss.setVisible(false)");web.onPause();super.onPause();}
    @Override protected void onDestroy(){searchVersion++;searchWorker.shutdownNow();if(ai!=null)ai.close();handler.removeCallbacks(tick);player.release();web.removeJavascriptInterface("MossNative");web.destroy();super.onDestroy();}

    private final class Bridge {
        @JavascriptInterface public void invoke(String payload){
            if(payload.length()>1048576)return;
            handler.post(()->{JSONObject args=null;try{args=new JSONObject(payload);action(args);}catch(Exception e){
                String message="操作失败："+AiWire.error(e,args==null?"":args.optJSONObject("connection")==null?"":args.optJSONObject("connection").optString("apiKey"));
                if(args!=null&&args.optString("action").startsWith("ai")){try{JSONObject error=new JSONObject().put("kind","notice").put("message",message);js("window.MossAI&&MossAI.receive("+error+")");}catch(Exception ignored){notice(message);}}else notice(message);
            }});
        }
    }
    private void action(JSONObject args)throws Exception{
        String kind=args.optString("action");
        if(kind.startsWith("ai")){
            if(ai==null){notice(aiInitError);return;}
            if(kind.equals("aiExportMinutes")||kind.equals("aiCopyMinutes")){
                JSONObject version=ai.savedVersion(args.getString("recordId"),args.getString("versionId"));
                String markdown=version.getString("markdown");
                if(kind.equals("aiCopyMinutes")){
                    String text=MinutesFormat.clipboardText(markdown,version.optJSONObject("sources"));
                    ((ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("会议纪要",text));notice("纪要已复制");
                }
                else{
                    String format=args.optString("format","MD");
                    if(!format.equals("MD")&&!format.equals("DOCX"))throw new java.io.IOException("请选择 Word 或 Markdown");
                    FileLibrary.exportMinutes(this,args.getString("recordId"),markdown,format,error->handler.post(()->notice(
                            error.isEmpty()?(format.equals("DOCX")?"Word":"Markdown")+" 纪要已导出":error)));
                }return;
            }
            if(kind.equals("aiSeekSource")){
                String id=args.getString("recordId");if(!id.matches("[0-9]+(-partial)?"))return;openHistory(id+".json");
                if(historyDocument!=null&&id.equals(TranscriptStore.id(historyId))){JSONArray rows=historyDocument.getJSONArray("segments");for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);if(args.optString("segmentId").equals(row.optString("id"))){play("history",row.optDouble("start"),true);break;}}}return;
            }
            JSONObject doc=null;if(kind.equals("aiOpenMinutes")||kind.equals("aiGenerate")||kind.equals("aiPasteMinutes")){
                if(kind.equals("aiGenerate")&&TranscribeService.busy&&!args.optString("scope").equals("history")){notice("转写完成后即可生成纪要");return;}
                doc=selectedDocument(args.optString("scope"));
            }
            ai.handle(args,doc);return;
        }
        switch(kind){
            case "ready":ready=true;resetStateCache();sendState();break;
            case "chooseSaveFolder":startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION|Intent.FLAG_GRANT_PREFIX_URI_PERMISSION).putExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI,FileLibrary.folder(this,"")),SAVE_FOLDER);break;
            case "resetSaveFolder":FileLibrary.reset(this);sendState();notice("默认位置已恢复为下载/燿识");break;
            case "retryFileSave":FileLibrary.resume(this);sendState();break;
            case "openSaveFolder":openSaveFolder(args.optString("recordId"));break;
            case "openRecordMinutes":openHistory(args.getString("id"));js("MossAI.openMinutes()");break;
            case "pickAudio":if(!TranscribeService.busy&&!ModelInstallService.busy)pick(AUDIO);break;
            case "downloadModel":ModelInstallService.request(this,null);sendState();break;
            case "pauseModel":ModelInstallService.pause(this);break;
            case "importModel":if(!TranscribeService.busy&&!ModelInstallService.busy)pick(MODEL);break;
            case "demo":if(!TranscribeService.busy&&!ModelInstallService.busy)demo();break;
            case "start":startJob();break;
            case "retranscribe":{
                if(TranscribeService.busy||ModelInstallService.busy)return;
                JSONObject doc=selectedDocument(args.optString("scope"));if(doc==null||doc.optString("sourceUri").isEmpty())return;
                audio=Uri.parse(doc.getString("sourceUri"));audioName=doc.optString("title","录音");startJob();break;
            }
            case "record":startRecording();break;
            case "finishRecording":if(TranscribeService.busy)startService(new Intent(this,TranscribeService.class).setAction("finishRecording"));break;
            case "cancel":startService(new Intent(this,TranscribeService.class).setAction("cancel"));break;
            case "backend":
                if(!TranscribeService.busy){selectedBackend=TranscribeService.normalizedBackend(args.optString("value"));getPreferences(0).edit().putString("backend",selectedBackend).apply();sendState();}break;
            case "motion":reducedMotion=args.optBoolean("value");getPreferences(0).edit().putBoolean("reducedMotion",reducedMotion).apply();sendState();break;
            case "play":play(args.optString("scope"),null,false);break;
            case "seek":play(args.optString("scope"),Math.max(0,args.optDouble("position",0)),args.optBoolean("autoplay"));break;
            case "speed":player.speed((float)args.optDouble("value",1));break;
            case "relink":relinkScope=args.optString("scope");relinkRecord="history".equals(relinkScope)?TranscriptStore.id(historyId):TranscribeService.recordId;pick(RELINK);break;
            case "speakerName":{
                String scope=args.optString("scope"),key="history".equals(scope)?TranscriptStore.id(historyId):TranscribeService.recordId;
                TranscriptStore.speaker(this,key,args.optString("speaker"),args.optString("name"));
                saveRecordFiles(key);
                if("history".equals(scope)&&historyDocument!=null)historyDocument=TranscriptStore.decorate(this,key,historyDocument);
                lastHistorySignature="";
                sendState();notice("标注已保存");break;
            }
            case "speakerMerge":case "speakerAssign":{
                String scope=args.optString("scope"),key="history".equals(scope)?TranscriptStore.id(historyId):TranscribeService.recordId;
                JSONObject doc=selectedDocument(scope);if(doc==null)return;
                TranscriptStore.changeSpeaker(this,key,doc,kind.equals("speakerMerge")?"merge":"assign",args.optString("speaker"),args.optString("target"),args.optString("segment"));
                saveRecordFiles(key);
                if("history".equals(scope))historyDocument=TranscriptStore.decorate(this,key,historyDocument);
                lastHistorySignature="";sendState();notice(kind.equals("speakerMerge")?"说话人已合并":"归属已更新");break;
            }
            case "matchSpeakers":startSpeakerMatch(TranscriptStore.id(args.optString("id",historyId)),true);break;
            case "stopSpeakerMatch":if(SpeakerMatchService.busy)startService(new Intent(this,SpeakerMatchService.class).setAction("stop"));break;
            case "deleteHistory":deleteHistory(args.optString("id"));break;
            case "editText":case "bookmark":{
                String scope=args.optString("scope"),key="history".equals(scope)?TranscriptStore.id(historyId):TranscribeService.recordId;
                JSONObject doc=selectedDocument(scope);if(doc==null)return;
                String segment=args.optString("segment");JSONArray rows=doc.getJSONArray("segments");boolean exists=false;
                for(int i=0;i<rows.length();i++)if(segment.equals(rows.getJSONObject(i).optString("id"))){exists=true;break;}
                if(!exists)throw new IOException("这段文字已更新，请重新打开");
                TranscriptStore.segment(this,key,segment,args.optString("text"),args.optBoolean("reset"),kind.equals("bookmark")?Boolean.valueOf(args.optBoolean("marked")):null);
                saveRecordFiles(key);
                if("history".equals(scope))historyDocument=TranscriptStore.decorate(this,key,historyDocument);
                lastHistorySignature="";sendState();if(kind.equals("editText"))notice(args.optBoolean("reset")?"已恢复原文":"纠错已保存");break;
            }
            case "searchHistory":searchHistory(args.optString("query"));break;
            case "openHistory":openHistory(args.optString("id"));if(!args.optString("query").isEmpty())js("Moss.openDetail('history',"+JSONObject.quote(args.optString("query"))+")");break;
            case "export":export(args.optString("format"),args.optString("scope"));break;
            case "copy":{
                JSONObject doc=selectedDocument(args.optString("scope"));if(doc==null)return;
                ((ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("转写文本",TranscriptStore.text(doc)));
                notice("已复制完整文本");break;
            }
            case "diagnostics":diagnostics();break;
            case "backgroundSettings":{
                Intent settings=new Intent(backgroundAllowed?Settings.ACTION_APPLICATION_DETAILS_SETTINGS:Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:"+getPackageName()));
                try{startActivity(settings);}catch(ActivityNotFoundException e){startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));}
                break;
            }
            case "about":try(InputStream in=getAssets().open("THIRD_PARTY_NOTICES.txt")){ByteArrayOutputStream bytes=new ByteArrayOutputStream();copy(in,bytes);panel("关于与开源说明",bytes.toString("UTF-8"));}break;
        }
    }
    private void js(String code){if(ready&&!isDestroyed())web.evaluateJavascript("window.Moss&&"+code,null);}
    private void notice(String text){js("Moss.notice("+JSONObject.quote(text)+")");}
    private void panel(String title,String text){js("Moss.showReport("+JSONObject.quote(title)+","+JSONObject.quote(text)+")");}
    private void resetStateCache(){sentPreview=null;sentHistory=null;sentHistoryDocument=null;sentSearchRows=null;}

    private void sendState(){
        if(!ready||isDestroyed())return;
        try{
            refreshHistory();
            if(!historyId.isEmpty()&&historyDocument!=null){
                long version=TranscriptStore.annotationVersion(this,TranscriptStore.id(historyId));
                if(version!=historyAnnotationVersion){historyDocument=TranscriptStore.decorate(this,TranscriptStore.id(historyId),historyDocument);historyAnnotationVersion=version;}
                if(!TranscribeService.busy&&!SpeakerMatchService.busy&&!speakerAutoChecked.contains(historyId)){startSpeakerMatch(TranscriptStore.id(historyId),false);speakerAutoChecked.add(historyId);}
            }
            JSONObject state=new JSONObject().put("busy",TranscribeService.busy).put("phase",TranscribeService.phase)
                .put("speakerJob",new JSONObject().put("busy",SpeakerMatchService.busy).put("record",SpeakerMatchService.record).put("progress",SpeakerMatchService.progress).put("status",SpeakerMatchService.status))
                .put("status",TranscribeService.status).put("taskTitle",TranscribeService.taskTitle)
                .put("fileName",audioName).put("hasAudio",audio!=null).put("modelReady",TranscribeService.modelReady(this))
                .put("backend",selectedBackend).put("runningBackend",TranscribeService.backend)
                .put("thermal",TranscribeService.thermal)
                .put("recording",TranscribeService.recording).put("liveMode",TranscribeService.liveMode)
                .put("recordedSeconds",TranscribeService.recordedSeconds).put("captureLevel",TranscribeService.captureLevel)
                .put("decodedSeconds",TranscribeService.decodedSeconds).put("decoderMode",TranscribeService.decoderMode)
                .put("backgroundAllowed",backgroundAllowed)
                .put("storage",FileLibrary.state(this))
                .put("importing",ModelInstallService.busy).put("importPercent",ModelInstallService.percent)
                .put("modelPhase",ModelInstallService.phase).put("modelDownloading",ModelInstallService.downloading)
                .put("modelMessage",ModelInstallService.message)
                .put("partialModelBytes",ModelInstallService.busy?0:AsrModelFile.partial(TranscribeService.model(this)).length())
                .put("progress",TranscribeService.progress).put("audioSeconds",TranscribeService.totalSeconds)
                .put("processedSeconds",TranscribeService.processedSeconds).put("totalChunks",TranscribeService.totalChunks)
                .put("currentChunk",TranscribeService.currentChunk).put("reducedMotion",getPreferences(0).contains("reducedMotion")?reducedMotion:Settings.Global.getFloat(getContentResolver(),Settings.Global.ANIMATOR_DURATION_SCALE,1)==0)
                .put("recordId",TranscribeService.recordId).put("sourceUri",TranscribeService.sourceUri)
                .put("windowInsets",new JSONObject().put("left",contentInsets.left).put("top",contentInsets.top).put("right",contentInsets.right).put("bottom",contentInsets.bottom))
                .put("selectedUri",audio==null?"":audio.toString()).put("annotations",TranscriptStore.publicAnnotations(this,TranscribeService.recordId)).put("player",player.state())
                .put("elapsedSeconds",TranscribeService.busy?(SystemClock.elapsedRealtime()-TranscribeService.startedAt)/1000.0:TranscribeService.document==null?0:TranscribeService.document.optDouble("elapsedSeconds"))
                .put("historyId",historyId).put("searchQuery",searchQuery).put("searching",searching).put("searchError",searchError);
            String preview=TranscribeService.previewJson;
            state.put("draft",TranscribeService.busy&&!TranscribeService.draftJson.equals("null")?new JSONObject(TranscribeService.draftJson):JSONObject.NULL);
            if(!preview.equals(sentPreview)){state.put("segments",new JSONArray(preview));sentPreview=preview;}
            if(sentHistory!=historyRows){state.put("history",historyRows);sentHistory=historyRows;}
            if(sentHistoryDocument!=historyDocument){state.put("historyDocument",historyDocument==null?JSONObject.NULL:historyDocument);sentHistoryDocument=historyDocument;}
            if(sentSearchRows!=searchRows){state.put("searchResults",searchRows);sentSearchRows=searchRows;}
            js("Moss.receive("+state.toString()+")");
        }catch(Exception e){android.util.Log.e("MossUI","state",e);}
    }
    private void refreshHistory(){
        File[] files=TranscribeService.history(this).listFiles((d,n)->n.endsWith(".json"));if(files==null)return;
        Arrays.sort(files,(a,b)->b.getName().compareTo(a.getName()));
        String signature=files.length+":"+(files.length==0?"":files[0].getName()+":"+files[0].lastModified());
        if(signature.equals(lastHistorySignature))return;
        JSONArray rows=new JSONArray();
        for(int i=0;i<Math.min(200,files.length);i++)try{
            JSONObject d=new JSONObject(Files.readString(files[i].toPath(),StandardCharsets.UTF_8));
            rows.put(new JSONObject().put("id",files[i].getName()).put("title",d.optString("title","录音"))
                .put("created",d.optLong("created")).put("audioSeconds",d.optDouble("audioSeconds",0))
                .put("backend",d.optString("backend")).put("partial",d.optBoolean("partial"))
                .put("count",d.getJSONArray("segments").length()).put("minutesCount",AiController.count(this,TranscriptStore.id(files[i].getName()))));
        }catch(Exception ignored){}
        historyRows=rows;lastHistorySignature=signature;
        if(!searchQuery.isEmpty())searchHistory(searchQuery);
    }
    private void searchHistory(String raw){
        final String query=raw.trim().toLowerCase(Locale.ROOT);final int version=++searchVersion;
        searchQuery=query;searchError="";searching=!query.isEmpty();searchRows=new JSONArray();
        if(query.isEmpty()){sendState();return;}
        searchWorker.execute(()->{
            JSONArray matches=new JSONArray();String failure="";
            try{
                File[] files=TranscribeService.history(this).listFiles((dir,name)->name.endsWith(".json"));
                if(files!=null){Arrays.sort(files,(a,b)->b.getName().compareTo(a.getName()));
                    for(File file:files){if(version!=searchVersion)return;
                        try{
                            JSONObject doc=TranscriptStore.decorate(this,TranscriptStore.id(file.getName()),new JSONObject(Files.readString(file.toPath(),StandardCharsets.UTF_8)));
                            JSONArray segments=doc.getJSONArray("segments");String title=doc.optString("title","录音"),snippet="";
                            boolean hit=title.toLowerCase(Locale.ROOT).contains(query);
                            for(int i=0;i<segments.length();i++){
                                JSONObject row=segments.getJSONObject(i);String body=row.optString("text"),name=TranscriptStore.speakerName(doc,row);
                                int at=body.toLowerCase(Locale.ROOT).indexOf(query);
                                if(at>=0||name.toLowerCase(Locale.ROOT).contains(query)){
                                    int begin=Math.max(0,at-24),end=Math.min(body.length(),Math.max(0,at)+100);
                                    snippet=(begin>0?"…":"")+body.substring(begin,end)+(end<body.length()?"…":"");hit=true;break;
                                }
                            }
                            if(hit)matches.put(new JSONObject().put("id",file.getName()).put("title",title).put("created",doc.optLong("created"))
                                .put("audioSeconds",doc.optDouble("audioSeconds")).put("backend",doc.optString("backend"))
                                .put("partial",doc.optBoolean("partial")).put("count",segments.length()).put("snippet",snippet).put("minutesCount",AiController.count(this,TranscriptStore.id(file.getName()))));
                        }catch(Exception ignored){/* One damaged record must not hide other searchable results. */}
                    }
                }
            }catch(Exception e){failure="搜索失败，请重试";}
            final String error=failure;handler.post(()->{if(version!=searchVersion||isDestroyed())return;searchRows=matches;searching=false;searchError=error;sendState();});
        });
    }
    private void openHistory(String id)throws Exception{
        if(!id.matches("[0-9]+(-partial)?\\.json"))throw new IOException("记录不存在");
        historyDocument=TranscriptStore.decorate(this,TranscriptStore.id(id),new JSONObject(Files.readString(new File(TranscribeService.history(this),id).toPath(),StandardCharsets.UTF_8)));
        player.pause();
        historyId=id;historyAnnotationVersion=TranscriptStore.annotationVersion(this,TranscriptStore.id(id));sendState();js("Moss.openDetail('history')");
    }
    private void startSpeakerMatch(String key,boolean explicit)throws Exception{
        if(TranscribeService.busy||SpeakerMatchService.busy){if(explicit)notice("当前任务结束后再整理");return;}
        if(!key.matches("[0-9]+(-partial)?"))return;
        JSONObject notes=TranscriptStore.annotations(this,key);String status=SpeakerIdentity.object(notes,"speakerMatch").optString("status");
        if(!explicit&&!status.isEmpty())return;
        File file=new File(TranscribeService.history(this),key+".json");if(!file.isFile())return;
        JSONObject doc=new JSONObject(Files.readString(file.toPath()));if(doc.optJSONArray("segments")==null||doc.getJSONArray("segments").length()==0)return;
        if(doc.optString("sourceUri").isEmpty()){if(explicit)notice("请先关联原音频");return;}
        // Persist before posting the service intent, so UI ticks cannot enqueue duplicates.
        TranscriptStore.matchStatus(this,key,"queued","");
        SpeakerMatchService.pending=key;
        try{startForegroundService(new Intent(this,SpeakerMatchService.class).putExtra("record",key));}
        catch(Exception e){SpeakerMatchService.pending="";TranscriptStore.matchStatus(this,key,"error",e.getMessage());throw e;}
    }
    private void deleteHistory(String filename)throws Exception{
        if(!filename.matches("[0-9]+(-partial)?\\.json"))throw new IOException("记录不存在");String key=TranscriptStore.id(filename);
        if((TranscribeService.busy&&key.equals(TranscribeService.recordId))||(SpeakerMatchService.busy&&key.equals(SpeakerMatchService.record))||key.equals(SpeakerMatchService.pending))throw new IOException("请先停止这条记录的处理任务");
        if(ai!=null&&ai.isGenerating(key))throw new IOException("请先停止这条记录的纪要生成");
        FileLibrary.deleteRecord(this,key);if(player.matches("record:"+key))player.release();
        if(filename.equals(historyId)){historyId="";historyDocument=null;historyAnnotationVersion=-1;js("Moss.go('history')");}
        if(key.equals(TranscribeService.recordId)){TranscribeService.document=null;TranscribeService.previewJson="[]";TranscribeService.recordId="";TranscribeService.sourceUri="";TranscribeService.phase="idle";TranscribeService.progress=0;TranscribeService.taskTitle="";TranscribeService.totalSeconds=0;TranscribeService.processedSeconds=0;}
        lastHistorySignature="";++searchVersion;sendState();notice("记录已删除");
    }
    private void saveRecordFiles(String id){if(!(TranscribeService.busy&&id.equals(TranscribeService.recordId)))FileLibrary.enqueue(this,id,null);}
    private JSONObject selectedDocument(String scope)throws Exception{
        if("history".equals(scope))return historyDocument==null?null:TranscriptStore.decorate(this,TranscriptStore.id(historyId),historyDocument);
        if(TranscribeService.document!=null)return TranscriptStore.decorate(this,TranscribeService.recordId,TranscribeService.document);
        JSONArray rows=new JSONArray(TranscribeService.previewJson);if(rows.length()==0){notice("识别出文字后即可使用");return null;}
        return TranscriptStore.decorate(this,TranscribeService.recordId,new JSONObject().put("title",TranscribeService.taskTitle).put("created",System.currentTimeMillis())
            .put("sourceUri",TranscribeService.sourceUri).put("partial",true).put("backend",TranscribeService.backend).put("audioSeconds",TranscribeService.totalSeconds).put("segments",rows));
    }
    private void play(String scope,Double position,boolean autoplay){
        if(TranscribeService.recording){notice("结束录音后即可回听");return;}
        String key,source;
        if("history".equals(scope)&&historyDocument!=null){key="record:"+TranscriptStore.id(historyId);source=historyDocument.optString("sourceUri");}
        else if(!TranscribeService.recordId.isEmpty()&&!TranscribeService.sourceUri.isEmpty()){
            key="record:"+TranscribeService.recordId;source=TranscribeService.sourceUri;
        }else{key="selected:"+(audio==null?"":audio.toString());source=audio==null?"":audio.toString();}
        if(source.isEmpty()){notice("请先关联原音频");return;}
        if(!player.matches(key))player.open(key,Uri.parse(source),position==null?0:position,position==null||autoplay);
        else if(position==null)player.toggle();else player.seek(position,autoplay);
        sendState();
    }
    private void pick(int request){
        Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        if(request==AUDIO||request==RELINK)i.putExtra(Intent.EXTRA_MIME_TYPES,new String[]{"audio/*","video/*"});startActivityForResult(i,request);
    }
    private void demo()throws IOException{
        File dir=new File(getFilesDir(),"audio");dir.mkdirs();File file=new File(dir,"中文示例.wav");
        try(InputStream in=getAssets().open("demo-zh.wav");OutputStream out=new FileOutputStream(file)){copy(in,out);}
        audio=Uri.fromFile(file);audioName="中文示例 · 7.77 秒";sendState();startJob();
    }
    private void startRecording(){
        if(SpeakerMatchService.busy){notice("说话人整理中，可先停止整理再开始录音");return;}
        if(TranscribeService.busy||ModelInstallService.busy)return;
        if(!TranscribeService.modelReady(this)){notice("请先下载或导入本地转写模型");return;}
        if(checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)!=android.content.pm.PackageManager.PERMISSION_GRANTED){
            requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO},51);return;
        }
        if(!resumed)return;
        player.release();String title=new java.text.SimpleDateFormat("yyyy年MM月dd日 HH点mm分",Locale.CHINA).format(new Date())+" · 录音";
        startForegroundService(new Intent(this,TranscribeService.class).setAction("record").putExtra("title",title).putExtra("backend",selectedBackend));
        js("Moss.go('home')");
    }
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] results){
        super.onRequestPermissionsResult(request,permissions,results);
        if(request==51){if(results.length>0&&results[0]==android.content.pm.PackageManager.PERMISSION_GRANTED)handler.post(this::startRecording);
            else notice("录音需要麦克风权限，可在系统应用权限中开启");}
    }
    private void startJob(){
        if(SpeakerMatchService.busy){notice("说话人整理中，可先停止整理再开始转写");return;}
        if(TranscribeService.busy||ModelInstallService.busy)return;
        if(!TranscribeService.modelReady(this)){notice("请先下载或导入本地转写模型");return;}
        if(audio==null){pick(AUDIO);return;}
        player.release();
        startForegroundService(new Intent(this,TranscribeService.class).setData(audio).putExtra("title",audioName).putExtra("backend",selectedBackend));
        js("Moss.go('home')");
    }
    private String name(Uri uri){
        try(android.database.Cursor c=getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)){if(c!=null&&c.moveToFirst())return c.getString(0);}catch(Exception ignored){}return "录音";
    }
    @Override protected void onActivityResult(int request,int code,Intent data){
        super.onActivityResult(request,code,data);if(code!=RESULT_OK||data==null||data.getData()==null)return;Uri uri=data.getData();
        if(request==AUDIO){
            player.release();audio=uri;audioName=name(uri);
            if(!TranscribeService.busy){TranscribeService.document=null;TranscribeService.previewJson="[]";TranscribeService.phase="idle";TranscribeService.recordId="";TranscribeService.sourceUri="";TranscribeService.taskTitle="";TranscribeService.totalSeconds=0;TranscribeService.processedSeconds=0;TranscribeService.progress=0;}
            try{getContentResolver().takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION);}catch(Exception ignored){}sendState();
        }
        else if(request==RELINK){
            try{getContentResolver().takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION);}catch(Exception ignored){}
            try{
                if("history".equals(relinkScope)&&historyDocument!=null&&TranscriptStore.id(historyId).equals(relinkRecord)){
                    historyDocument=new JSONObject(historyDocument.toString()).put("sourceUri",uri.toString());TranscriptStore.write(new File(TranscribeService.history(this),historyId),historyDocument);
                }else if(relinkRecord.equals(TranscribeService.recordId)){
                    TranscribeService.sourceUri=uri.toString();
                    if(TranscribeService.document!=null){TranscribeService.document=new JSONObject(TranscribeService.document.toString()).put("sourceUri",uri.toString());TranscriptStore.write(new File(TranscribeService.history(this),relinkRecord+".json"),TranscribeService.document);}
                }
                saveRecordFiles(relinkRecord);player.release();sendState();notice("原音频已关联");
            }catch(Exception e){notice("关联失败："+e.getMessage());}
        }
        else if(request==MODEL)ModelInstallService.request(this,uri);
        else if(request==SAVE_FOLDER){
            try{getContentResolver().takePersistableUriPermission(uri,data.getFlags()&(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION));}catch(Exception e){notice("文件夹授权未完成，请重新选择");return;}
            new Thread(()->{try{FileLibrary.choose(this,uri);handler.post(()->{sendState();notice("默认保存位置已更新");});}catch(Exception e){handler.post(()->notice("保存位置未更换："+e.getMessage()));}},"ChooseSaveFolder").start();
        }
    }
    private void export(String format,String scope)throws Exception{
        if(!Arrays.asList("MD","SRT","JSON").contains(format))return;
        JSONObject doc=selectedDocument(scope);if(doc==null)return;
        FileLibrary.export(this,new JSONObject(doc.toString()),format,error->handler.post(()->notice(error.isEmpty()?"已导出到默认文件夹":error)));
    }
    private void openSaveFolder(String id)throws Exception{
        Uri folder=FileLibrary.folder(this,id);Intent intent=new Intent(Intent.ACTION_VIEW).setDataAndType(folder,android.provider.DocumentsContract.Document.MIME_TYPE_DIR);
        try{startActivity(intent);}catch(ActivityNotFoundException e){notice("保存位置："+FileLibrary.state(this).getString("label"));}
    }
    private void diagnostics(){
        if(TranscribeService.busy){notice("请在转写结束后运行设备检测");return;}
        notice("正在检测设备接口…");
        new Thread(()->{try{
            File log=new File(getCacheDir(),"probe.txt");Process p=new ProcessBuilder(new File(getApplicationInfo().nativeLibraryDir,"libmoss_probe.so").getAbsolutePath()).redirectErrorStream(true).redirectOutput(log).start();
            if(!p.waitFor(20,TimeUnit.SECONDS)){p.destroyForcibly();throw new IOException("检测超时");}
            String jni;try{System.loadLibrary("moss_accel");jni=nativeProbeLibraries();}catch(LinkageError e){jni="JNI: "+e.getMessage();}
            String report="NPU 尚未接入。nnapi-reference 为 CPU；加载运行库不代表模型已使用 NPU。\n\n"+Files.readString(log.toPath())+"\n"+jni;
            Files.writeString(new File(getFilesDir(),"device-probe.txt").toPath(),report);
            handler.post(()->panel("设备能力",report));
        }catch(Exception e){handler.post(()->notice(e.getMessage()));}},"DeviceProbe").start();
    }
    private static void copy(InputStream in,OutputStream out)throws IOException{byte[] buffer=new byte[65536];int n;while((n=in.read(buffer))!=-1)out.write(buffer,0,n);}
}
