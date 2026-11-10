/* Direction-locked gestures leave vertical scrolling native; deletion is always a button. */
globalThis.YaoshiGestures=(()=>{
  let opened=null;
  const close=()=>{opened?.(false);opened=null;};
  function bind(surface,{hold,swipe,change}={}){
    let start=null,timer=0,drag=false,offset=0,isOpen=false,suppressUntil=0;
    const cancelTimer=()=>{clearTimeout(timer);timer=0;};
    const show=value=>{isOpen=value;offset=value?-88:0;surface.style.removeProperty('transition');if(swipe)surface.style.transform=`translateX(${offset}px)`;change?.(value);};
    const reset=()=>{cancelTimer();start=null;drag=false;surface.classList.remove('dragging');show(isOpen);};
    const longPress=()=>{cancelTimer();if(!surface.isConnected||!surface.getClientRects().length)return;suppressUntil=performance.now()+800;start=null;close();hold?.();};
    surface.addEventListener('pointerdown',e=>{
      if(e.button!==0||!e.isPrimary){reset();return;}
      if(e.target.closest('.record-menu'))return;
      if(opened&&opened!==show)close();start={x:e.clientX,y:e.clientY,id:e.pointerId,offset};drag=false;
      timer=setTimeout(longPress,520);
    });
    surface.addEventListener('pointermove',e=>{
      if(!start||e.pointerId!==start.id)return;const dx=e.clientX-start.x,dy=e.clientY-start.y;
      if(Math.abs(dx)>8||Math.abs(dy)>8)cancelTimer();
      if(!drag&&Math.abs(dy)>10&&Math.abs(dy)>=Math.abs(dx)){start=null;return;}
      if(swipe&&!drag&&Math.abs(dx)>12&&Math.abs(dx)>Math.abs(dy)*1.3){drag=true;surface.setPointerCapture(e.pointerId);surface.classList.add('dragging');}
      if(drag){e.preventDefault();offset=Math.min(0,Math.max(-100,start.offset+dx));surface.style.transform=`translateX(${offset}px)`;}
    });
    surface.addEventListener('pointerup',e=>{
      cancelTimer();if(start&&drag){suppressUntil=performance.now()+500;const dx=e.clientX-start.x;show(dx>20?false:offset< -36);opened=isOpen?show:null;}
      start=null;drag=false;surface.classList.remove('dragging');
    });
    surface.addEventListener('pointercancel',reset);
    // Touch starts with implicit capture on the child button. Moving capture to the
    // sliding row emits a bubbling lost event on that child; it is not cancellation.
    surface.addEventListener('lostpointercapture',e=>{if(e.target===surface)reset();});
    surface.addEventListener('contextmenu',e=>{e.preventDefault();if(performance.now()>=suppressUntil)longPress();});
    surface.addEventListener('click',e=>{if(performance.now()<suppressUntil||isOpen){e.preventDefault();e.stopImmediatePropagation();if(isOpen)close();}},true);
    surface.addEventListener('keydown',e=>{if(e.key==='ContextMenu'||(e.shiftKey&&e.key==='F10')){e.preventDefault();longPress();}});
    // Abort an unfinished hold when the app loses focus, without leaking global listeners.
    const abort=()=>{if(start)reset();};
    surface.addEventListener('blur',abort,true);
    return show;
  }
  document.addEventListener('pointerdown',e=>{if(!e.target.closest('.record-shell'))close();},{passive:true});
  window.addEventListener('blur',close);window.addEventListener('scroll',close,{passive:true});
  return {bind,close};
})();
