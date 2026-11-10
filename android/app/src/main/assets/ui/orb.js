/* Pre-rendered 60 fps reference film, with a tail for invisible decoder handoff. */
(() => {
  'use strict';
  const stage=document.getElementById('visual-background'),poster=document.getElementById('orb-poster');
  const videos=['orb-video-a','orb-video-b'].map(id=>document.getElementById(id));
  let visible=true,reducedMotion=false,suspended=false,busy=false,sourceReady=false,gentleMotion=false;
  let active=0,cycles=0,period=14,overlap=1,switching=null,finishTimer=0,epoch=0;
  const retries=[0,0],presented=[false,false],handoffs=[];
  const running=()=>sourceReady&&visible&&!reducedMotion&&!suspended&&!document.hidden;
  const paint=()=>{
    // A paused Android WebView video may replace its decoded frame with the
    // platform's default play poster after returning from another activity.
    const ready=running()&&presented[active]&&!videos[active].paused&&!videos[active].seeking&&videos[active].readyState>=2&&!videos[active].error;
    poster.style.opacity=ready?'0':'1';
    videos.forEach((v,i)=>{v.style.transition='none';v.style.zIndex=i===active?'1':'2';v.style.opacity=ready&&i===active?'1':'0';});
  };
  function settle(){
    clearTimeout(finishTimer);epoch++;
    if(switching?.fading)active=switching.to;
    switching=null;
    videos.forEach((v,i)=>{if(i!==active){v.pause();presented[i]=false;if(v.readyState>=1&&!v.error)v.currentTime=0;}});
    paint();
  }
  function update(){
    stage.hidden=!visible;
    if(!running()){settle();videos.forEach(v=>v.pause());presented.fill(false);paint();return;}
    paint();
    const current=videos[active],token=epoch;current.muted=true;
    current.play().then(()=>{if(token===epoch&&!running())current.pause();}).catch(()=>{
      if(token===epoch&&current===videos[active]&&running()){presented[active]=false;paint();}
    });
  }
  function handoff(){
    if(switching||!running())return;
    const from=active,to=1-active,next=videos[to],current=videos[from];
    if(next.error||next.readyState<2)return;
    const token=++epoch;
    switching={from,to,token,started:performance.now(),fading:false};
    // The outgoing file contains the same first second after its period. It
    // keeps MOVING while this hidden decoder seeks/starts, including on Mali.
    next.currentTime=Math.min(overlap*.5,Math.max(0,current.currentTime-period));
    next.style.transition='none';next.style.zIndex='2';next.style.opacity='.001';
    current.style.zIndex='1';current.style.opacity='1';next.muted=true;
    next.play().then(()=>{if(token===epoch&&!running())next.pause();}).catch(()=>{if(token===epoch)settle();});
  }
  function incomingFrame(index){
    const swap=switching;
    if(!swap||swap.to!==index||swap.fading||!running()||videos[index].paused||videos[index].seeking)return;
    presented[index]=true;
    swap.fading=true;
    const delta=(videos[swap.from].currentTime-period)-videos[index].currentTime;
    // Only matching phases blend; there is no fade through a poster or a still.
    videos[index].style.transition='opacity 160ms linear';videos[index].style.opacity='1';
    finishTimer=setTimeout(()=>{
      if(swap.token!==epoch||!running())return;
      active=swap.to;cycles++;
      const event={cycle:cycles,decodeStartMs:performance.now()-swap.started,phaseDriftMs:Math.round(delta*1000)};
      handoffs.push(event);if(handoffs.length>12)handoffs.shift();
      settle();window.dispatchEvent(new CustomEvent('moss-orb-handoff',{detail:event}));
    },180);
  }
  function onFrame(index){
    if(index===active&&running()&&!videos[index].paused&&!videos[index].seeking&&!presented[index]){
      presented[index]=true;paint();
    }
    if(index===active&&running()&&videos[index].currentTime>=period)handoff();
    incomingFrame(index);
  }
  videos.forEach((video,index)=>{
    video.muted=true;video.controls=false;video.disablePictureInPicture=true;
    if(video.requestVideoFrameCallback){
      const frame=()=>{onFrame(index);video.requestVideoFrameCallback(frame);};video.requestVideoFrameCallback(frame);
    }else video.addEventListener('timeupdate',()=>onFrame(index));
    video.addEventListener('loadeddata',()=>{if(index===active)update();});
    video.addEventListener('pause',()=>{presented[index]=false;if(index===active)paint();});
    video.addEventListener('emptied',()=>{presented[index]=false;if(index===active)paint();});
    video.addEventListener('ended',()=>{if(index===active)handoff();});
    video.addEventListener('error',()=>{
      presented[index]=false;
      settle();if(index===active){poster.style.opacity='1';video.style.opacity='0';}
      if(!sourceReady||retries[index]++>=2)return;video.load();
    });
  });
  // One shared local Blob survives a native picker pause without range reads.
  Promise.all([
    fetch('/media/orb.mp4',{cache:'no-store'}).then(r=>{if(!r.ok)throw Error('media');return r.blob();}),
    fetch('/media/orb-loop.json').then(r=>r.json())
  ]).then(([blob,meta])=>{
    period=Number(meta.period)||14;overlap=Number(meta.overlap)||1;
    const url=URL.createObjectURL(blob);sourceReady=true;
    videos.forEach(v=>{v.src=url;v.load();});update();
  }).catch(()=>{poster.style.opacity='1';videos.forEach(v=>v.style.opacity='0');});
  document.addEventListener('visibilitychange',update);
  window.MossOrb={setState(next){const changed=visible!==!!next.visible||reducedMotion!==!!next.reducedMotion||suspended!==!!next.suspended;visible=!!next.visible;reducedMotion=!!next.reducedMotion;suspended=!!next.suspended;busy=!!next.busy;gentleMotion=!!next.gentleMotion;
      // Thermal control pauses inference, never the visible scene. Slowing both
      // decoders keeps their loop phases aligned without resetting either one.
      const rate=gentleMotion?.5:1;videos.forEach(v=>{if(v.playbackRate!==rate)v.playbackRate=rate;});if(changed)update();},
    stats(){const video=videos[active];return {renderer:'double-buffer-reference-video',visible,busy,reducedMotion,suspended,gentleMotion,rate:video.playbackRate,posterVisible:poster.style.opacity!=='0',presented:[...presented],time:video.currentTime,paused:video.paused,ready:video.readyState,error:video.error?.code||0,cycles,active,period,handoffs:[...handoffs],painted:videos.reduce((sum,v)=>sum+(v.getVideoPlaybackQuality?.().totalVideoFrames||0),0),decoders:videos.map(v=>({time:v.currentTime,paused:v.paused,ready:v.readyState,error:v.error?.code||0,dropped:v.getVideoPlaybackQuality?.().droppedVideoFrames||0}))};}};
  update();
})();
