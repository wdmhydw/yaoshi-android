(() => {
  'use strict';
  const $=id=>document.getElementById(id), all=s=>[...document.querySelectorAll(s)];
  const svg=name=>`<svg aria-hidden="true"><use href="#i-${name}"/></svg>`;
  const setText=(id,value)=>{const el=$(id),text=String(value??'');if(el.textContent!==text)el.textContent=text;};
  const invoke=(action,args={})=>window.MossNative?.invoke(JSON.stringify({action,...args}));
  let state={segments:[],history:[],backend:'cpu',phase:'idle'}, page='home',scope='active',previous='home';
  let sheet=null,restoreFocus=null,visible=true,filter='',noticeTimer=0;
  let sheetParent=null,confirmCommit=null;
  let previewSignature='',detailSignature='',historySignature='',filterSignature='';
  let lastPhase='idle',lastTask='',lastCount=0;
  let speakerTarget='',speakerScope='active',draggingAudio=false,highlighted='';
  let speakerRow=null,recordTarget=null;
  const speakerNumbers={active:new Map(),history:new Map()};
  let activeSpeakerNames={};
  let onlyBookmarks=false,searchTimer=0,editTarget=null,editScope='active';
  let insetsSignature='';
  let historyScroll=0;
  const appbar=document.querySelector('.appbar');
  window.addEventListener('scroll',()=>appbar.classList.toggle('scrolled',scrollY>8),{passive:true});
  function renderInsets(){
    const insets=state.windowInsets||{},scale=window.devicePixelRatio||1;
    const values=['top','right','bottom','left'].map(side=>Math.max(0,Number(insets[side])||0)/scale);
    const signature=values.join('|');if(signature===insetsSignature)return;insetsSignature=signature;
    ['top','right','bottom','left'].forEach((side,index)=>document.documentElement.style.setProperty('--safe-'+side,values[index]+'px'));
  }
  window.addEventListener('resize',renderInsets);
  const activeRows=()=>YaoshiTranscript.apply(state.segments,state.annotations);
  const previewRows=()=>YaoshiTranscript.preview(state.segments,state.busy?state.draft:null,state.annotations);
  function markText(element,text,query){element.replaceChildren(...YaoshiTranscript.parts(text,query).map(part=>{if(!part.hit)return document.createTextNode(part.text);const mark=document.createElement('mark');mark.textContent=part.text;return mark;}));}
  const fallbackSpeakers=new Map();
  const clock=value=>{const n=Math.max(0,Math.floor(Number(value)||0));return n>=3600?`${Math.floor(n/3600)}:${String(Math.floor(n/60)%60).padStart(2,'0')}:${String(n%60).padStart(2,'0')}`:`${String(Math.floor(n/60)).padStart(2,'0')}:${String(n%60).padStart(2,'0')}`;};
  const seconds=value=>`${(Number(value)||0).toFixed(1)} 秒`;
  function speaker(key,whichScope='active'){
    key=String(key||'S01');
    if(!fallbackSpeakers.has(key))fallbackSpeakers.set(key,fallbackSpeakers.size+1);
    const number=speakerNumbers[whichScope]?.get(key)||fallbackSpeakers.get(key),short=String(number).padStart(2,'0');
    const names=whichScope==='history'?state.historyDocument?.speakerNames:activeSpeakerNames;
    const label=names?.[key]||`说话人 ${short}`;
    return {number,short,label,right:number%2===0};
  }
  function bubbleMeta(row,whichScope='active'){
    const info=speaker(row.speaker,whichScope),meta=document.createElement('div');meta.className='bubble-meta';
    const dot=document.createElement('span');dot.className='speaker-dot';dot.textContent=info.short;
    const name=document.createElement('button');name.className='speaker-name';name.textContent=info.label;
    if(!row.provisional){name.onclick=e=>{e.stopPropagation();editSpeaker(row.speaker,whichScope,row);};name.setAttribute('aria-label','标注 '+info.label);
      YaoshiGestures.bind(name,{hold:()=>editSpeaker(row.speaker,whichScope,row)});}
    else{name.disabled=true;name.setAttribute('aria-label',info.label);}
    const time=document.createElement('time');time.textContent=clock(row.start);
    meta.append(dot,name,time);return meta;
  }
  function orbState(){window.MossOrb?.setState({visible:visible&&page==='home',busy:state.busy,reducedMotion:state.reducedMotion,suspended:!!sheet,gentleMotion:false});}
  function go(next){
    if(!['home','history','detail','minutes'].includes(next))return;
    if(page==='history')historyScroll=scrollY;
    YaoshiGestures.close();closeSheet(true);page=next;all('.page').forEach(p=>p.hidden=p.id!==page);
    all('.nav-tabs [data-go]').forEach(b=>{const active=b.dataset.go===page||(['detail','minutes'].includes(page)&&b.dataset.go===previous);b.classList.toggle('active',active);b.setAttribute('aria-current',active?'page':'false');});
    window.scrollTo(0,0);if(page==='history')renderHistory();if(page==='detail')renderDetail(true);renderPlayer();orbState();
    if(page==='history')requestAnimationFrame(()=>{if(page==='history')window.scrollTo(0,historyScroll);});
  }
  function openDetail(nextScope='active',query=''){
    scope=nextScope==='history'?'history':'active';previous=scope==='history'?'history':'home';filter='';filterSignature='';detailSignature='';lastCount=0;
    onlyBookmarks=false;$('transcript-search').value=query;
    $('conversation').replaceChildren();highlighted='';go('detail');window.MossAI?.availability();
  }
  function back(){if(!sheet&&page==='minutes'){go('detail');return true;}if(sheet){closeSheet();return true;}if(page==='detail'){go(previous);return true;}if(page==='history'){go('home');return true;}return false;}
  function showSheet(id,nested=false){
    sheetParent=nested&&sheet?{node:sheet,focus:document.activeElement,scroll:sheet.scrollTop}:null;
    if(!sheet)restoreFocus=document.activeElement;all('.sheet').forEach(s=>{s.hidden=true;s.inert=false;});
    if(sheetParent){sheetParent.node.hidden=false;sheetParent.node.inert=true;}
    sheet=$(id);sheet.hidden=false;$('sheet-backdrop').hidden=false;
    $('sheet-backdrop').classList.toggle('nested',!!sheetParent);
    document.querySelector('main').inert=true;document.querySelector('.appbar').inert=true;document.body.style.overflow='hidden';
    sheet.querySelector('button')?.focus({preventScroll:true});orbState();
  }
  function closeSheet(force=false){
    if(sheetParent&&force!==true){const parent=sheetParent;sheet.hidden=true;sheet=parent.node;sheet.hidden=false;sheet.inert=false;sheet.scrollTop=parent.scroll;sheetParent=null;confirmCommit=null;$('sheet-backdrop').classList.remove('nested');parent.focus?.focus({preventScroll:true});orbState();return;}
    sheetParent=null;confirmCommit=null;
    if(!sheet)return;all('.sheet').forEach(s=>{s.hidden=true;s.inert=false;});sheet=null;$('sheet-backdrop').hidden=true;$('sheet-backdrop').classList.remove('nested');
    document.querySelector('main').inert=false;document.querySelector('.appbar').inert=false;document.body.style.overflow='';
    document.activeElement?.blur();restoreFocus?.focus({preventScroll:true});restoreFocus=null;orbState();
  }
  function notice(text){setText('snackbar',text);$('snackbar').hidden=false;clearTimeout(noticeTimer);noticeTimer=setTimeout(()=>{$('snackbar').hidden=true;},4200);}
  function confirmAction(title,message,commit,label='删除'){setText('confirm-title',title);setText('confirm-message',message);setText('confirm-accept',label);showSheet('confirm-sheet',true);confirmCommit=commit;}
  $('confirm-cancel').onclick=()=>closeSheet();$('confirm-accept').onclick=()=>{const commit=confirmCommit;closeSheet();commit?.();};
  function showReport(title,text){setText('report-title',title);setText('report-body',text);showSheet('report-sheet');}
  function renderHome(){
    const identities=previewRows();speakerNumbers.active=new Map(identities.map(row=>[row.speaker,row.speakerNumber]));activeSpeakerNames=YaoshiTranscript.names(state.busy&&state.draft?[...state.segments,state.draft]:state.segments,state.annotations);
    const running=state.busy,complete=state.phase==='complete',stopped=state.phase==='cancelled',failed=state.phase==='error';
    const hasRows=state.segments.length>0,active=running||hasRows||complete||stopped||failed;
    document.body.classList.toggle('busy',running);document.body.classList.toggle('reduced-motion',!!state.reducedMotion);
    setText('progress-caption',state.importing?state.modelPhase:state.recording?'录音中':running?(state.phase==='decoding'?'读取音频':state.liveMode?'补齐文字':'总进度'):complete?'已完成':stopped?'已停止':failed?'转写失败':'');
    setText('progress-value',state.recording?clock(state.recordedSeconds):state.importing?`${state.importPercent}%`:active?`${state.progress||0}%`:'');
    $('progress-label').setAttribute('role',running&&!state.recording?'progressbar':'status');$('progress-label').setAttribute('aria-label',state.recording?'录音时长':'转写总进度');
    if(running&&!state.recording){$('progress-label').setAttribute('aria-valuemin','0');$('progress-label').setAttribute('aria-valuemax','100');$('progress-label').setAttribute('aria-valuenow',state.progress||0);}else ['aria-valuemin','aria-valuemax','aria-valuenow'].forEach(a=>$('progress-label').removeAttribute(a));
    $('orb-open').disabled=!active;$('open-detail').hidden=!active;
    setText('audio-position',state.recording?`已转写 ${clock(state.processedSeconds)} · 待处理 ${clock(Math.max(0,state.recordedSeconds-state.processedSeconds))}`:active&&state.audioSeconds?`${clock(state.processedSeconds)} / ${clock(state.audioSeconds)}`:'');
    setText('selected-file',running?state.taskTitle:(state.fileName||'添加音频 / 视频'));
    const backend=running?state.runningBackend:state.backend;
    $('backend-open').firstChild.textContent=backend==='vulkan'?'Vulkan GPU':backend==='opencl'?'OpenCL GPU':'CPU';
    $('backend-open').querySelector('span').textContent=backend!=='cpu'?'实验模式':'';
    const main=$('primary-action'),caption=state.recording?'结束录音':running?'停止转写':state.importing?state.modelPhase:!state.modelReady?'获取模型':state.hasAudio?'开始转写':'选择文件';
    main.querySelector('span').textContent=caption;main.querySelector('use').setAttribute('href',running?'#i-stop':'#i-arrow');main.classList.toggle('running',running);main.disabled=!!state.importing;
    for(const id of ['pick-file','demo-button','backend-open'])$(id).disabled=running||state.importing;
    $('demo-button').disabled=running||state.importing||!state.modelReady;
    $('record-start').hidden=running;$('record-start').disabled=state.importing||!state.modelReady;
    const heat=state.thermal||{},battery=Number.isFinite(heat.batteryC)?`电池 ${heat.batteryC.toFixed(1)}°C`:'';
    const heatLabel=heat.systemStatus>=1?'系统正在调节性能':'';
    const heatText=running?[heatLabel,battery].filter(Boolean).join(' · '):'';
    setText('footer-status',failed?'查看错误':heatText||(stopped&&hasRows?'部分结果已保存':!state.modelReady?'需先获取本地模型':''));
    $('footer-status').classList.toggle('thermal-status',!!heatText&&!failed);
    $('footer-status').classList.toggle('status-error',failed);$('footer-status').setAttribute('tabindex',failed?'0':'-1');$('footer-status').setAttribute('role',failed?'button':'status');
    setText('elapsed-label',active&&state.elapsedSeconds?`用时 ${clock(state.elapsedSeconds)}`:'');
    renderPreview();
  }
  function renderPreview(){
    let rows=activeRows();const context=playbackContext('active'),p=state.player||{},synced=p.key===context.key&&p.ready;
    const ended=state.phase==='complete'&&!p.playing&&p.duration>0&&p.position>=p.duration-.1;
    const location=synced&&!ended?YaoshiTimeline.locate(rows,p.position,state.processedSeconds,state.busy,state.phase==='complete'):null;
    const message=location&&location.kind!=='segment'?timelineMessage(location.kind):'';
    $('preview-wait').hidden=!message;$('preview-wait').textContent=message;
    if(location)rows=location.kind==='segment'?[location.row]:[];
    else rows=previewRows();
    const selected=[[],[]];
    for(const row of rows)selected[speaker(row.speaker).right?1:0].push(row);
    const preview=selected.map(list=>list.slice(-2)),signature=JSON.stringify([preview,state.annotations?.speakerNames]);
    if(signature===previewSignature)return;previewSignature=signature;
    preview.forEach((list,index)=>{
      const lane=$(index?'preview-right':'preview-left'),keys=new Set();
      list.forEach(row=>{
        const key=String(row.id??row.start)+'|'+row.speaker;keys.add(key);
        let card=[...lane.children].find(el=>el.dataset.key===key);
        if(!card){card=document.createElement('article');card.className='preview-bubble glass';card.dataset.key=key;
          card.append(bubbleMeta(row));const text=document.createElement('button');text.className='bubble-text';card.append(text);
          card.addEventListener('click',()=>openDetail());lane.append(card);}
        card.querySelector('.bubble-text').textContent=row.text||'';
        card.classList.toggle('is-generating',!!row.provisional);
        card.querySelector('.speaker-name').textContent=speaker(row.speaker).label;
        card.querySelector('.speaker-dot').textContent=speaker(row.speaker).short;
        card.querySelector('time').textContent=clock(row.start);
        card.setAttribute('aria-label',`${speaker(row.speaker).label}：${row.text}，查看完整转写`);
      });
      [...lane.children].forEach(el=>{if(!keys.has(el.dataset.key))el.remove();});
    });
  }
  function currentDocument(){return scope==='history'?(state.historyDocument||{segments:[]}):{title:state.taskTitle,segments:activeRows(),sourceUri:state.sourceUri,speakerNames:activeSpeakerNames,speakerMatch:state.annotations?.speakerMatch,backend:state.runningBackend==='vulkan'?'Vulkan GPU':state.runningBackend==='opencl'?'OpenCL GPU':'CPU',audioSeconds:state.audioSeconds,elapsedSeconds:state.elapsedSeconds,partial:['cancelled','error'].includes(state.phase)};}
  function renderDetail(force=false){
    const doc=currentDocument(),rows=doc.segments||[],live=scope==='active'&&state.busy;
    const nearDraftEnd=document.documentElement.scrollHeight-innerHeight-scrollY<180;
    const draft=live?previewRows().find(row=>row.provisional):null,stream=$('draft-preview');
    stream.hidden=!draft||!!onlyBookmarks||!!$('transcript-search').value.trim()||!!filter&&draft.speaker!==filter;
    if(!stream.hidden){
      const key=draft.id+'|'+draft.speaker+'|'+draft.start;
      if(stream.dataset.key!==key){stream.dataset.key=key;const text=document.createElement('p');text.className='conversation-bubble';stream.replaceChildren(bubbleMeta(draft),text);}
      const changed=stream.querySelector('p').textContent!==draft.text;
      stream.classList.toggle('right',speaker(draft.speaker).right);stream.querySelector('p').textContent=draft.text;
      if(changed&&nearDraftEnd&&!state.player?.ready&&$('follow-live').checked)requestAnimationFrame(()=>stream.scrollIntoView({block:'nearest',behavior:'instant'}));
    }
    speakerNumbers[scope]=new Map((live?previewRows():rows).map(row=>[row.speaker,row.speakerNumber]));
    setText('detail-back',scope==='history'?'返回转写记录':'返回转写空间');
    if(!$('detail-back').querySelector('svg'))$('detail-back').insertAdjacentHTML('afterbegin',svg('back'));
    setText('detail-state',live?'转写中':doc.partial?'部分结果':'');
    if(live&&state.recording)setText('detail-state',`录音中 · ${clock(state.recordedSeconds)}`);
    $('detail-stop').hidden=!live;setText('detail-stop',state.recording?'结束录音':'停止转写');
    $('retry-transcribe').hidden=state.busy||!doc.partial||!doc.sourceUri;
    setText('detail-title',doc.title||'正在准备录音');
    const keys=[...new Set(rows.map(row=>row.speaker||'S01'))];
    const fields=[clock(doc.audioSeconds),`${rows.length} 句`,doc.backend||'CPU'];
    fields.push(`${keys.length} 位说话人`);
    const record=scope==='history'?doc.recordId:state.recordId,matching=state.speakerJob?.busy&&state.speakerJob.record===record;
    const matchState=doc.speakerMatch?.status||'';
    $('speaker-match-status').hidden=!matching&&!['error','paused','queued','running'].includes(matchState);
    setText('speaker-match-status',matching?`${state.speakerJob.status} ${state.speakerJob.progress}% · 停止`:matchState==='error'?'说话人整理中断 · 重试':['queued','running'].includes(matchState)?'继续整理说话人':'继续整理说话人');
    $('speaker-match-status').onclick=()=>invoke(matching?'stopSpeakerMatch':'matchSpeakers',{id:record});
    const meta=$('detail-meta');if(meta.dataset.value!==fields.join('|')){meta.dataset.value=fields.join('|');meta.replaceChildren(...fields.map(text=>{const span=document.createElement('span');span.textContent=text;return span;}));}
    $('detail-progress').hidden=!live;$('detail-progress').firstElementChild.style.width=`${state.progress||0}%`;
    $('copy-button').disabled=$('export-open').disabled=!rows.length;
    document.querySelector('.follow-control').hidden=!live;
    const filters=JSON.stringify([keys,filter,doc.speakerNames]);
    if(filter&&!keys.includes(filter))filter='';
    if(filters!==filterSignature){filterSignature=filters;const frag=document.createDocumentFragment();
      for(const key of ['',...keys]){const b=document.createElement('button');b.textContent=key?speaker(key,scope).label:'全部';b.classList.toggle('active',filter===key);b.setAttribute('aria-pressed',String(filter===key));b.onclick=()=>{filter=key;renderDetail(true);};if(key){b.setAttribute('aria-label',speaker(key,scope).label+'，长按标注');YaoshiGestures.bind(b,{hold:()=>editSpeaker(key,scope)});}frag.append(b);}
      $('speaker-filters').replaceChildren(frag);}
    const query=$('transcript-search').value.trim();
    $('bookmarks-only').classList.toggle('selected',onlyBookmarks);$('bookmarks-only').setAttribute('aria-pressed',String(onlyBookmarks));
    const signature=JSON.stringify([rows,filter,live,!!draft,doc.speakerNames,query,onlyBookmarks]);
    if(force||signature!==detailSignature){
      detailSignature=signature;const box=$('conversation'),shown=rows.filter(row=>(!filter||row.speaker===filter)&&(!onlyBookmarks||row.marked)&&YaoshiTranscript.matches(row,query,speaker(row.speaker,scope).label));
      setText('transcript-match-count',query||onlyBookmarks?`${shown.length} 句`:'');
      const nearEnd=document.documentElement.scrollHeight-innerHeight-scrollY<180;
      if(!shown.length){const empty=document.createElement('p');empty.className='conversation-empty';empty.textContent=query?'未找到匹配文字':onlyBookmarks?'暂无重点':live?'正在识别…':'暂无文字';if(draft&&!query&&!onlyBookmarks)box.replaceChildren();else box.replaceChildren(empty);}
      else{
        box.querySelector('.conversation-empty')?.remove();const keep=new Set();
        for(const row of shown){const key=String(row.id??row.start)+'|'+row.speaker;keep.add(key);
          let el=[...box.children].find(c=>c.dataset.key===key);
          if(!el){el=document.createElement('article');el.className='conversation-row'+(speaker(row.speaker,scope).right?' right':'');el.dataset.key=key;el.append(bubbleMeta(row,scope));const p=document.createElement('p');p.className='conversation-bubble';el.append(p);box.append(el);
            const time=el.querySelector('time'),seek=document.createElement('button');seek.className='segment-time';time.replaceWith(seek);seek.append(time);seek.setAttribute('aria-label','从 '+clock(row.start)+' 播放');
            const actions=document.createElement('div');actions.className='segment-actions';actions.innerHTML=`<button class="segment-mark">${svg('bookmark')}<span>重点</span></button><button class="segment-edit">${svg('edit')}<span>纠错</span></button><span class="edited-label"></span>`;el.append(actions);}
          markText(el.querySelector('.conversation-bubble'),row.text||'',query);
          el.querySelector('.speaker-name').textContent=speaker(row.speaker,scope).label;
          el.querySelector('.speaker-dot').textContent=speaker(row.speaker,scope).short;
          el.classList.toggle('right',speaker(row.speaker,scope).right);
          el.querySelector('time').textContent=`${clock(row.start)} — ${clock(row.end)}`;
          el.querySelector('.segment-time').onclick=()=>invoke('seek',{scope,position:Number(row.start),autoplay:true});
          const mark=el.querySelector('.segment-mark');mark.setAttribute('aria-pressed',String(!!row.marked));mark.setAttribute('aria-label',(row.marked?'取消':'标记')+'重点：'+clock(row.start));mark.onclick=()=>invoke('bookmark',{scope,segment:row.id,marked:!row.marked});
          el.classList.toggle('is-marked',!!row.marked);el.querySelector('.edited-label').textContent=row.edited?'已校对':'';
          el.querySelector('.segment-edit').onclick=()=>editText(row);
          box.append(el);
        }
        [...box.children].forEach(el=>{if(!keep.has(el.dataset.key))el.remove();});
      }
      if(live&&!state.player?.ready&&rows.length>lastCount&&lastCount>0&&$('follow-live').checked&&nearEnd)requestAnimationFrame(()=>$('reading-end').scrollIntoView({block:'end',behavior:state.reducedMotion?'instant':'smooth'}));
      lastCount=rows.length;
    }
    setText('reading-end',live?`总进度 ${state.progress||0}%`:'');
  }
  function renderHistory(){
    const query=$('history-search').value.trim().toLocaleLowerCase(),waiting=!!query&&(state.searchQuery!==query||state.searching),rows=query?(waiting?[]:state.searchResults||[]):state.history;
    const signature=JSON.stringify([rows,query,waiting,state.searchError]);if(signature===historySignature)return;historySignature=signature;
    const frag=document.createDocumentFragment();
    if(!rows.length){const empty=document.createElement('div');empty.className='history-empty';empty.textContent=waiting?'正在搜索…':state.searchError|| (query?'未找到记录':'暂无转写记录');frag.append(empty);}
    rows.forEach(row=>{
      const shell=document.createElement('div');shell.className='record-shell';shell.dataset.id=row.id;
      const remove=document.createElement('button');remove.className='record-delete';remove.innerHTML=svg('trash')+'<span>删除</span>';remove.inert=true;remove.setAttribute('aria-label','删除 '+row.title);remove.setAttribute('aria-hidden','true');remove.onclick=()=>deleteRecord(row);
      const button=document.createElement('div');button.className='history-row';button.setAttribute('role','group');
      const open=document.createElement('button');open.className='record-open';open.setAttribute('aria-label','打开 '+row.title);
      const date=document.createElement('span');date.className='history-date';
      const d=new Date(row.created),day=document.createElement('strong'),time=document.createElement('span');
      day.textContent=`${String(d.getMonth()+1).padStart(2,'0')}.${String(d.getDate()).padStart(2,'0')}`;time.textContent=`${String(d.getHours()).padStart(2,'0')}:${String(d.getMinutes()).padStart(2,'0')}`;date.append(day,time);
      const main=document.createElement('span');main.className='history-row-main';const title=document.createElement('strong');markText(title,row.title,query);
      const meta=document.createElement('small');meta.className='history-meta';
      for(const value of [clock(row.audioSeconds),`${row.count} 句`,row.backend||'CPU']){const item=document.createElement('span');item.textContent=value;meta.append(item);}
      if(row.partial){const badge=document.createElement('span');badge.className='record-state';badge.textContent='部分结果';meta.append(badge);}
      if(row.minutesCount){const badge=document.createElement('span');badge.className='record-minutes-count';badge.textContent=`纪要 ${row.minutesCount} 版`;meta.append(badge);}
      main.append(title,meta);if(row.snippet&&query){const snippet=document.createElement('span');snippet.className='search-snippet';markText(snippet,row.snippet,query);main.append(snippet);}open.append(date,main);
      open.onclick=()=>invoke('openHistory',{id:row.id,query:row.snippet?query:''});
      const menu=document.createElement('button');menu.className='record-menu icon-button';menu.innerHTML=svg('more');menu.setAttribute('aria-label',row.title+'的操作菜单');menu.onclick=e=>{e.stopPropagation();recordMenu(row);};
      button.append(open,menu);shell.append(remove,button);frag.append(shell);
      YaoshiGestures.bind(button,{hold:()=>recordMenu(row),swipe:true,change:shown=>{shell.classList.toggle('revealed',shown);remove.inert=!shown;remove.setAttribute('aria-hidden',String(!shown));}});
    });
    $('history-list').replaceChildren(frag);
  }
  function deleteRecord(row){
    YaoshiGestures.close();confirmAction('删除录音及关联文件',`删除“${row.title}”的录音副本、转写、标注及全部纪要？手动导出的文件保留。`,()=>{closeSheet(true);invoke('deleteHistory',{id:row.id});});
  }
  function recordMenu(row){
    YaoshiGestures.close();recordTarget=row;setText('record-menu-title',row.title);showSheet('record-sheet');
    $('record-minutes').hidden=!row.minutesCount;
    const matching=state.speakerJob?.busy&&state.speakerJob.record===row.id.replace(/\.json$/,'');setText('record-match-label',matching?'停止整理说话人':'整理说话人');
  }
  $('record-view').onclick=()=>{closeSheet(true);invoke('openHistory',{id:recordTarget.id});};
  $('record-minutes').onclick=()=>{closeSheet(true);invoke('openRecordMinutes',{id:recordTarget.id});};
  $('record-remove').onclick=()=>deleteRecord(recordTarget);
  $('record-match').onclick=()=>{const id=recordTarget.id.replace(/\.json$/,''),matching=state.speakerJob?.busy&&state.speakerJob.record===id;closeSheet(true);invoke(matching?'stopSpeakerMatch':'matchSpeakers',{id});};
  function renderSettings(){
    all('[data-backend]').forEach(b=>{const selected=b.dataset.backend===state.backend;b.classList.toggle('selected',selected);b.setAttribute('aria-pressed',String(selected));b.disabled=state.busy||state.importing;});
    $('motion-toggle').setAttribute('aria-checked',String(!!state.reducedMotion));
    const heat=state.thermal||{};
    setText('thermal-state',state.busy?(heat.label||'由系统调节'):'由系统调节');
    setText('thermal-copy',state.busy&&Number.isFinite(heat.batteryC)?`电池 ${heat.batteryC.toFixed(1)}°C · 温度管理交给设备系统`:'温度管理交给设备系统，应用只显示温度状态。');
    setText('background-state',state.backgroundAllowed?'已允许持续运行':'受系统省电管理');
    $('background-settings').firstChild.textContent=state.backgroundAllowed?'系统设置':'允许持续运行';
    const storage=state.storage||{};setText('save-folder-path',storage.label||'下载/燿识');setText('export-folder-path',storage.label||'下载/燿识');
    setText('file-save-status',storage.error||(storage.pending?'正在保存文件…':''));$('file-save-status').hidden=!storage.error&&!storage.pending;$('retry-file-save').hidden=!storage.error;$('reset-save-folder').hidden=!storage.custom;
    const remaining=state.partialModelBytes||0;
    setText('model-state',state.importing?state.modelPhase+' '+state.importPercent+'%':state.modelReady?'已启用':remaining?'可续传':'未安装');
    setText('model-copy',state.importing?'传输完成后自动校验并启用。':state.modelMessage||(remaining&&!state.modelReady?'已下载 '+Math.floor(remaining/1048576)+' MiB。':''));
    $('download-model').hidden=state.modelReady||state.importing;
    $('download-model').textContent=remaining?'继续下载并启用':'下载并启用模型';
    $('download-model').disabled=state.busy;
    $('pause-model').hidden=!state.importing||!state.modelDownloading;
    $('import-model').disabled=state.busy||state.importing;
    $('diagnostics-button').disabled=state.busy;
  }
  function receive(next){
    state={...state,...next};state.segments=Array.isArray(state.segments)?state.segments:[];state.history=Array.isArray(state.history)?state.history:[];
    renderInsets();
    if(state.busy&&(!['recording','decoding','transcribing','cooling','saving'].includes(lastPhase)||state.taskTitle!==lastTask)){
      fallbackSpeakers.clear();previewSignature='';detailSignature='';filterSignature='';filter='';lastCount=0;$('preview-left').replaceChildren();$('preview-right').replaceChildren();
    }
    if(['recording','decoding','transcribing','cooling','saving'].includes(lastPhase)&&['complete','cancelled','error'].includes(state.phase))notice(state.phase==='complete'?`转写完成 · ${state.segments.length} 句已保存`:state.phase==='cancelled'?'转写已停止，识别内容已保留':'转写遇到问题，请查看详情');
    lastPhase=state.phase;lastTask=state.taskTitle;renderHome();renderSettings();setText('history-count',state.history.length||'');
    if(page==='detail')renderDetail();if(page==='history')renderHistory();renderPlayer();orbState();window.MossAI?.availability();
  }
  function playbackContext(whichScope=page==='detail'?scope:'active'){
    if(whichScope==='history'){const doc=state.historyDocument||{};return {key:'record:'+(doc.recordId||String(state.historyId||'').replace(/\.json$/,'')),source:doc.sourceUri||'',duration:doc.audioSeconds||0,scope:'history'};}
    if(state.recordId)return {key:'record:'+state.recordId,source:state.sourceUri||'',duration:state.audioSeconds||0,scope:'active'};
    return {key:'selected:'+(state.selectedUri||''),source:state.selectedUri||'',duration:0,scope:'active'};
  }
  function timelineMessage(kind){return kind==='pending'?'请稍后，正在转写中':kind==='untranscribed'?'此处尚未转写':'此处暂无文字';}
  function renderPlayer(forceFollow=false){
    const context=playbackContext(),p=state.player||{},matching=p.key===context.key;
    const shown=!state.recording&&page!=='history'&&(context.source||page==='detail');
    const panel=$('player-panel'),mount=$(page==='detail'?'detail-player-mount':'home-player-mount');
    if(panel.parentElement!==mount)mount.append(panel);panel.hidden=!shown;if(!shown)return;
    const prepared=matching&&p.ready,playing=prepared&&p.playing,duration=prepared?p.duration:context.duration,position=matching?p.position:0;
    $('audio-play').disabled=!context.source||(matching&&p.loading);$('audio-play').querySelector('use').setAttribute('href',playing?'#i-pause':'#i-play');$('audio-play').setAttribute('aria-label',playing?'暂停原音频':'播放原音频');
    $('audio-rewind').disabled=!prepared;$('audio-speed').textContent=`${p.speed||1}×`;
    setText('audio-current',clock(position));setText('audio-duration',clock(duration));
    const seek=$('audio-seek');seek.max=duration||1;seek.disabled=!context.source||!duration||(matching&&p.loading);if(!draggingAudio)seek.value=position;
    const processed=context.scope==='history'?(state.historyDocument?.processedSeconds??duration):state.phase==='complete'?duration:state.processedSeconds||0;
    seek.style.setProperty('--buffered',`${duration?Math.min(100,processed/duration*100):0}%`);
    const doc=context.scope==='history'?state.historyDocument||{}:{segments:activeRows()},rows=doc.segments||[];
    let location=prepared?YaoshiTimeline.locate(rows,position,processed,context.scope==='active'&&state.busy,context.scope==='history'?!doc.partial:state.phase==='complete'):null;
    let message=!context.source?'关联原音频后可回听':matching&&p.error?p.error:matching&&p.loading?'正在读取原音频…':page==='detail'&&location&&location.kind!=='segment'?timelineMessage(location.kind):'';
    if(page==='detail'&&location?.kind==='segment'&&((filter&&filter!==location.row.speaker)||(onlyBookmarks&&!location.row.marked)||!YaoshiTranscript.matches(location.row,$('transcript-search').value,speaker(location.row.speaker,scope).label)))message=speaker(location.row.speaker,scope).label+'：'+location.row.text;
    $('playback-message').hidden=!message;setText('playback-message',message);
    if(page==='detail'){
      const key=location?.kind==='segment'?String(location.row.id??location.row.start)+'|'+location.row.speaker:'';
      all('.conversation-row').forEach(row=>{const current=row.dataset.key===key;row.classList.toggle('is-current',current);if(current)row.setAttribute('aria-current','true');else row.removeAttribute('aria-current');});
      if(key&&(forceFollow||(key!==highlighted&&playing))&&$('follow-live').checked&&!sheet){const row=all('.conversation-row').find(r=>r.dataset.key===key);row?.scrollIntoView({block:'center',behavior:state.reducedMotion?'instant':'smooth'});}
      highlighted=key;document.querySelector('.follow-control').hidden=!(state.busy||prepared);
    }
  }
  function editSpeaker(key,whichScope,row=null){
    speakerTarget=key;speakerScope=whichScope;speakerRow=row;const names=whichScope==='history'?state.historyDocument?.speakerNames:activeSpeakerNames;
    $('speaker-name-input').value=names?.[key]||'';setText('speaker-original',speaker(key,whichScope).label);const suggestions=[...new Set([...Object.values(names||{}),'我','主持人','采访者','受访者'])].slice(0,8);
    $('speaker-suggestions').replaceChildren(...suggestions.map(name=>{const b=document.createElement('button');b.textContent=name;b.onclick=()=>{$('speaker-name-input').value=name;};return b;}));
    const rows=whichScope==='history'?state.historyDocument?.segments||[]:activeRows(),keys=[...new Set(rows.map(r=>r.speaker))];
    const option=(key,label)=>{const o=document.createElement('option');o.value=key;o.textContent=label;return o;};
    $('speaker-assign-wrap').hidden=!row;$('speaker-corrections').open=false;
    $('speaker-assign-to').replaceChildren(...keys.map(k=>option(k,speaker(k,whichScope).label)),option('new','新建说话人'),option('reset','恢复自动归属'));$('speaker-assign-to').value=key;
    $('speaker-merge-to').replaceChildren(option('','选择合并到…'),...keys.filter(k=>k!==key).map(k=>option(k,speaker(k,whichScope).label)));
    $('speaker-merge').disabled=true;$('speaker-merge-wrap').hidden=keys.length<2;
    showSheet('speaker-sheet');$('speaker-name-input').focus({preventScroll:true});
  }
  $('speaker-merge-to').onchange=e=>$('speaker-merge').disabled=!e.target.value;
  $('speaker-merge').onclick=()=>{const target=$('speaker-merge-to').value;if(!target)return;confirmAction('合并说话人',`将“${speaker(speakerTarget,speakerScope).label}”的所有发言归到“${speaker(target,speakerScope).label}”。`,()=>{invoke('speakerMerge',{scope:speakerScope,speaker:speakerTarget,target});closeSheet(true);},'合并');};
  $('speaker-assign').onclick=()=>{if(speakerRow){invoke('speakerAssign',{scope:speakerScope,segment:speakerRow.id,target:$('speaker-assign-to').value});closeSheet(true);}};
  const saveSpeaker=name=>{invoke('speakerName',{scope:speakerScope,speaker:speakerTarget,name});closeSheet();};
  function editText(row){
    editTarget=row;editScope=scope;setText('edit-speaker',speaker(row.speaker,scope).label+' · '+clock(row.start));
    $('edit-text').value=row.text||'';setText('edit-original',row.originalText??row.text??'');$('edit-reset').disabled=!row.edited;
    $('edit-save').disabled=!$('edit-text').value.trim();document.querySelector('.original-copy').open=false;showSheet('edit-sheet');$('edit-text').focus({preventScroll:true});
  }
  const saveText=reset=>{if(!editTarget)return;invoke('editText',{scope:editScope,segment:editTarget.id,text:$('edit-text').value,reset});closeSheet();};
  $('edit-save').onclick=()=>saveText(false);$('edit-reset').onclick=()=>saveText(true);
  $('edit-listen').onclick=()=>{if(editTarget)invoke('seek',{scope:editScope,position:Number(editTarget.start),autoplay:true});};
  $('edit-text').addEventListener('input',e=>{$('edit-save').disabled=!e.target.value.trim();});
  $('transcript-search').addEventListener('input',()=>{renderDetail(true);renderPlayer();});
  $('bookmarks-only').onclick=()=>{onlyBookmarks=!onlyBookmarks;renderDetail(true);renderPlayer();};
  $('speaker-save').onclick=()=>saveSpeaker($('speaker-name-input').value);$('speaker-reset').onclick=()=>saveSpeaker('');
  $('speaker-name-input').addEventListener('keydown',e=>{if(e.key==='Enter'){e.preventDefault();saveSpeaker(e.target.value);}});
  $('audio-play').onclick=()=>invoke('play',{scope:playbackContext().scope});
  $('audio-rewind').onclick=()=>invoke('seek',{scope:playbackContext().scope,position:Math.max(0,(state.player?.position||0)-10)});
  $('audio-relink').onclick=()=>invoke('relink',{scope:playbackContext().scope});
  $('audio-speed').onclick=()=>{const speeds=[.75,1,1.25,1.5,2],index=speeds.indexOf(state.player?.speed||1);invoke('speed',{value:speeds[(index+1)%speeds.length]});};
  $('audio-seek').addEventListener('input',e=>{draggingAudio=true;setText('audio-current',clock(e.target.value));});
  $('audio-seek').addEventListener('change',e=>{invoke('seek',{scope:playbackContext().scope,position:Number(e.target.value)});draggingAudio=false;});
  all('[data-go]').forEach(button=>button.onclick=()=>go(button.dataset.go));
  $('settings-open').onclick=$('backend-open').onclick=()=>showSheet('settings-sheet');
  $('sheet-backdrop').onclick=closeSheet;all('.close-sheet').forEach(b=>b.onclick=closeSheet);
  $('pick-file').onclick=()=>invoke('pickAudio');$('demo-button').onclick=()=>invoke('demo');
  $('primary-action').onclick=()=>{
    if(!state.modelReady&&!state.busy){showSheet('settings-sheet');$('speech-model').scrollIntoView({block:'center'});return;}
    invoke(state.recording?'finishRecording':state.busy?'cancel':state.hasAudio?'start':'pickAudio');
  };
  $('record-start').onclick=()=>invoke('record');$('detail-stop').onclick=()=>invoke(state.recording?'finishRecording':'cancel');
  $('retry-transcribe').onclick=()=>invoke('retranscribe',{scope});
  $('orb-open').onclick=$('open-detail').onclick=()=>openDetail();$('detail-back').onclick=()=>go(previous);
  $('copy-button').onclick=()=>invoke('copy',{scope});$('export-open').onclick=()=>showSheet('export-sheet');
  all('[data-export]').forEach(b=>b.onclick=()=>{closeSheet();invoke('export',{format:b.dataset.export,scope});});
  all('[data-backend]').forEach(b=>b.onclick=()=>invoke('backend',{value:b.dataset.backend}));
  $('motion-toggle').onclick=()=>invoke('motion',{value:!state.reducedMotion});
  $('background-settings').onclick=()=>invoke('backgroundSettings');
  $('choose-save-folder').onclick=$('export-choose-folder').onclick=()=>invoke('chooseSaveFolder');
  $('open-save-folder').onclick=()=>invoke('openSaveFolder');$('reset-save-folder').onclick=()=>invoke('resetSaveFolder');$('retry-file-save').onclick=()=>invoke('retryFileSave');
  $('download-model').onclick=()=>invoke('downloadModel');
  $('pause-model').onclick=()=>invoke('pauseModel');
  $('import-model').onclick=()=>{closeSheet();invoke('importModel');};
  $('diagnostics-button').onclick=()=>invoke('diagnostics');$('about-button').onclick=()=>invoke('about');
  $('history-search').addEventListener('input',()=>{clearTimeout(searchTimer);renderHistory();searchTimer=setTimeout(()=>invoke('searchHistory',{query:$('history-search').value}),200);});
  $('follow-live').addEventListener('change',()=>{
    if(!$('follow-live').checked)return;
    const context=playbackContext(),player=state.player||{};
    if(player.ready&&player.key===context.key)renderPlayer(true);
    else if(scope==='active'&&state.busy)$('reading-end').scrollIntoView({block:'end',behavior:state.reducedMotion?'instant':'smooth'});
  });
  const errorDetails=()=>{if(state.phase==='error')showReport('转写问题',state.status||'请检查录音和模型后重试。');};
  $('footer-status').onclick=errorDetails;$('footer-status').onkeydown=e=>{if(e.key==='Enter')errorDetails();};
  document.addEventListener('keydown',e=>{
    if(e.key==='Escape'){if(back())e.preventDefault();return;}
    if(e.key==='Tab'&&sheet){const list=[...sheet.querySelectorAll('button:not(:disabled),input:not(:disabled),textarea:not(:disabled),select:not(:disabled),summary,[tabindex="0"]')].filter(el=>el.getClientRects().length&&!el.closest('[hidden]'));const first=list[0],last=list.at(-1);
      if(e.shiftKey&&document.activeElement===first){e.preventDefault();last?.focus();}else if(!e.shiftKey&&document.activeElement===last){e.preventDefault();first?.focus();}}
  });
  window.Moss={receive,go,openDetail,back,notice,showReport,showSheet,closeSheet,confirmAction,aiContext(){const d=currentDocument();return {scope,recordId:scope==='history'?(state.historyDocument?.recordId||String(state.historyId||'').replace(/\.json$/,'')):state.recordId,title:d.title,hasText:!!d.segments?.length,busy:scope!=='history'&&state.busy};},setVisible(value){visible=value;orbState();}};
  invoke('ready');
})();
