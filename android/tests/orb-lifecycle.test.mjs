import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import vm from 'node:vm';

class Video extends EventTarget {
  constructor(){super();this.style={};this.paused=true;this.readyState=0;this.currentTime=0;this.seeking=false;this.error=null;}
  load(){this.readyState=4;queueMicrotask(()=>this.dispatchEvent(new Event('loadeddata')));}
  play(){this.paused=false;return this.nextPlay?.()||Promise.resolve();}
  pause(){if(!this.paused){this.paused=true;this.dispatchEvent(new Event('pause'));}}
  requestVideoFrameCallback(callback){this.frame=callback;}
  present(time=1){this.currentTime=time;this.frame();}
}
const videos=[new Video(),new Video()],poster={style:{}},stage={hidden:false};
const document=new EventTarget();document.hidden=false;
document.getElementById=id=>({'visual-background':stage,'orb-poster':poster,'orb-video-a':videos[0],'orb-video-b':videos[1]})[id];
const window=new EventTarget();
const context={document,window,URL,Blob,CustomEvent,performance,setTimeout,clearTimeout,
  fetch:async url=>url.endsWith('.json')?{json:async()=>({period:14,overlap:1})}:{ok:true,blob:async()=>new Blob(['fixture'])}};
vm.runInNewContext(readFileSync(new URL('../app/src/main/assets/ui/orb.js',import.meta.url),'utf8'),context);
await new Promise(r=>setImmediate(r));
const orb=window.MossOrb;
let checks=0;
function check(condition,message){assert.ok(condition,message);checks++;}
const state=overrides=>orb.setState({visible:true,busy:false,reducedMotion:false,suspended:false,...overrides});
check(orb.stats().posterVisible,'readyState alone must not expose an unpresented video');
videos[0].present();check(!orb.stats().posterVisible,'a presented frame reveals the video');
state({busy:true,gentleMotion:true});
check(!orb.stats().posterVisible&&!videos[0].paused&&videos.every(v=>v.playbackRate===.5),'thermal pacing keeps the scene moving at half speed');
check(videos[0].currentTime===1,'thermal pacing changes no playback position');
state({});check(!videos[0].paused&&videos.every(v=>v.playbackRate===1),'thermal recovery restores speed without a pause');
state({suspended:true});check(orb.stats().posterVisible&&videos.every(v=>v.paused),'a dialog suspension shows the supplied still');
state({visible:false,suspended:true});state({suspended:true});
check(orb.stats().posterVisible&&videos.every(v=>v.paused),'returning while suspended must not expose Android default video poster');
state({});check(orb.stats().posterVisible,'resume waits for a real presented frame');
videos[0].present();check(!orb.stats().posterVisible,'resume paints after its first frame');
document.hidden=true;document.dispatchEvent(new Event('visibilitychange'));
check(orb.stats().posterVisible&&videos.every(v=>v.paused),'document backgrounding parks both decoders');
document.hidden=false;document.dispatchEvent(new Event('visibilitychange'));videos[0].present();
check(!orb.stats().posterVisible,'document foregrounding recovers the scene');
state({reducedMotion:true});check(orb.stats().posterVisible&&videos.every(v=>v.paused),'reduced motion retains the original still');
state({});videos[0].present();
let rejectOld;
videos[0].nextPlay=()=>new Promise((_,reject)=>{rejectOld=reject;});
state({visible:false});state({});
videos[0].nextPlay=null;state({visible:false});state({});videos[0].present();
rejectOld(new Error('old play was interrupted'));await new Promise(r=>setImmediate(r));
check(!orb.stats().posterVisible&&!videos[0].paused,'a stale play rejection must not blank a new foreground frame');
state({busy:true,gentleMotion:true});videos[0].present(14.04);videos[1].present(.04);
await new Promise(r=>setTimeout(r,220));
check(orb.stats().active===1&&orb.stats().cycles===1&&!orb.stats().posterVisible&&videos[1].playbackRate===.5,'decoder handoff remains animated during thermal pacing');
state({visible:false});
console.log(`${checks} orb lifecycle checks passed`);
