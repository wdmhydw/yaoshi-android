(() => {
  'use strict';
  const $=id=>document.getElementById(id), invoke=(action,args={})=>window.MossNative?.invoke(JSON.stringify({action,...args}));
  const clone=value=>JSON.parse(JSON.stringify(value)), uid=()=>crypto.randomUUID(), text=(id,value)=>{$(id).textContent=String(value??'');};
  let settings={connections:[],defaultConnectionId:''},presets=[],templates=[],draft=null,picking=new Set(),operation=null,serial=0;
  let minutesContext=null,minutesData={versions:[]},sources={},versionId='',minutesBusy=false,editing=false,templateId='';
  const selectedConnection=()=>settings.connections.find(c=>c.id===settings.defaultConnectionId);
  const version=()=>minutesData.versions?.find(v=>v.id===versionId);
  const option=(value,label)=>{const o=document.createElement('option');o.value=value;o.textContent=label;return o;};
  function setError(id,value){text(id,value);$(id).hidden=!value;}
  function invalidate(identityChanged=false){if(!draft)return;draft.test={status:'untested'};renderTest();if(identityChanged){draft.directoryStatus='stale';draft.models=(draft.models||[]).map(m=>({...m,source:m.source==='remote'?'cache':m.source}));text('ai-directory-status','连接已变更，可重新获取模型');}if(operation){invoke('aiCancelOperation');operation=null;serial++;renderWorking();}}
  function capture(){
    if(!draft)throw Error('请先选择供应商');
    return {...clone(draft),name:$('ai-connection-name').value,apiKey:$('ai-key').value,baseUrl:$('ai-base-url').value,api:$('ai-api').value,authMode:$('ai-auth').value,modelsPath:$('ai-models-path').value,inferencePath:$('ai-inference-path').value,selectedModelId:$('ai-default-model').value,makeDefault:$('ai-make-default').checked};
  }
  function remember(){if(draft){const d=capture();delete d.apiKey;draft=d;}}
  function renderConnections(){
    const list=$('ai-connections');list.replaceChildren();
    for(const connection of settings.connections){const button=document.createElement('button');button.className='ai-connection';button.classList.toggle('active',connection.id===draft?.id);button.setAttribute('aria-pressed',String(connection.id===draft?.id));
      const title=document.createElement('strong'),model=document.createElement('small');title.textContent=connection.name+(connection.id===settings.defaultConnectionId?' · 默认':'');model.textContent=connection.selectedModelId||'尚未选择模型';button.append(title,model);button.onclick=()=>openConnection(connection);list.append(button);
    }
    $('ai-settings-empty').hidden=!!draft;$('ai-connection-form').hidden=!draft;
  }
  function renderProviders(){
    const query=$('ai-provider-search').value.trim().toLowerCase(),select=$('ai-provider');select.replaceChildren();
    for(const p of presets){if(query&&!`${p.name} ${p.aliases||''} ${p.family||''} ${p.regionKey||''} ${p.planKey||''}`.toLowerCase().includes(query))continue;select.append(option(p.id,p.name));}
    select.value=draft?.presetId||'';
  }
  function chooseProvider(id){
    const p=presets.find(p=>p.id===id);if(!p)return;invalidate();
    draft={id:draft?.id||uid(),revision:draft?.revision||0,name:p.name,presetId:p.id,api:p.api,baseUrl:p.baseUrl||'',authMode:p.authMode,discovery:p.discovery||'openai',modelsPath:p.modelsPath||'models',inferencePath:p.inferencePath||'',apiVersion:p.apiVersion||'2023-06-01',extraHeaders:p.extraHeaders||{},models:(p.modelSuggestions||[]).map(m=>({...m,source:'manual'})),enabledModelIds:[],selectedModelId:'',clearKey:true,test:{status:'untested'}};
    fillForm();$('ai-provider-picker').open=!p.baseUrl;if(!p.baseUrl)document.querySelector('.ai-advanced').open=true;
  }
  function openConnection(connection){invalidate();draft=clone(connection);draft.clearKey=false;fillForm();$('ai-provider-picker').open=false;}
  function addConnection(){invalidate();draft={id:uid(),revision:0,name:'',presetId:'',api:'openai-completions',baseUrl:'',authMode:'bearer',modelsPath:'models',inferencePath:'',models:[],enabledModelIds:[],selectedModelId:'',test:{status:'untested'}};fillForm();$('ai-provider-picker').open=true;$('ai-provider-search').focus();}
  function fillForm(){
    if(!draft)return;renderConnections();renderProviders();
    $('ai-connection-name').value=draft.name||'';$('ai-key').value='';
    $('ai-key').placeholder=draft.configured&&!draft.clearKey?'已保存，留空保留':'粘贴 API 密钥';$('ai-key-clear').hidden=!draft.configured||draft.clearKey;
    $('ai-base-url').value=draft.baseUrl||'';$('ai-api').value=draft.api;$('ai-auth').value=draft.authMode||'bearer';$('ai-models-path').value=draft.modelsPath||'models';$('ai-inference-path').value=draft.inferencePath||'';
    $('ai-make-default').checked=draft.id===settings.defaultConnectionId||settings.connections.length===0;$('ai-delete-connection').hidden=!settings.connections.some(c=>c.id===draft.id);
    $('ai-model-picker').open=false;$('ai-model-search').value='';picking=new Set(draft.enabledModelIds||[]);
    text('ai-directory-status',draft.directoryFetchedAt?`${draft.directoryStatus==='stale'?'上次目录 · ':''}已缓存 ${draft.models?.filter(m=>m.source!=='manual').length||0} 个模型 · ${new Date(draft.directoryFetchedAt).toLocaleString()}`:'');
    setError('ai-form-error','');renderModels();renderTest();renderWorking();
  }
  function renderModels(){
    if(!draft)return;const list=$('ai-model-list'),query=$('ai-model-search').value.trim().toLowerCase();list.replaceChildren();
    for(const model of draft.models||[]){if(query&&!`${model.name} ${model.id}`.toLowerCase().includes(query))continue;
      const row=document.createElement('div'),checkLabel=document.createElement('label'),check=document.createElement('input'),use=document.createElement('button'),copy=document.createElement('span'),name=document.createElement('strong'),meta=document.createElement('small'),action=document.createElement('span');row.className='ai-model-row';checkLabel.className='ai-model-check';check.type='checkbox';check.checked=picking.has(model.id);check.setAttribute('aria-label','启用 '+model.id);check.onchange=()=>check.checked?picking.add(model.id):picking.delete(model.id);checkLabel.append(check);
      name.textContent=model.name||model.id;meta.textContent=(model.name&&model.name!==model.id?model.id+' · ':'')+(model.source==='remote'?'远端目录':model.source==='cache'?'缓存':'手动 / 预设');copy.append(name,meta);
      use.className='ai-model-use';use.type='button';use.dataset.modelId=model.id;use.setAttribute('aria-label','使用 '+model.id);use.setAttribute('aria-pressed',String(draft.selectedModelId===model.id));action.className='ai-model-action';action.textContent=draft.selectedModelId===model.id?'当前':'使用';use.append(copy,action);use.onclick=()=>selectModel(model.id);row.append(checkLabel,use);list.append(row);
    }
    if(!list.childElementCount){const empty=document.createElement('p');empty.className='ai-secondary';empty.textContent=(draft.models||[]).length?'没有匹配的模型':'获取模型目录，或在下方手动添加。';list.append(empty);}
    const select=$('ai-default-model');select.replaceChildren(option('','选择默认模型'));
    for(const id of draft.enabledModelIds||[]){const m=draft.models.find(m=>m.id===id);select.append(option(id,m?.name&&m.name!==id?`${m.name} · ${id}`:id));}select.value=draft.selectedModelId||'';
    text('ai-enabled-count',`（${draft.enabledModelIds?.length||0}）`);
  }
  function applyPicking(){
    const previous=draft.selectedModelId,initial=!previous;
    draft.enabledModelIds=[...picking];
    if(previous&&!picking.has(previous))draft.selectedModelId='';
    // Confirming the first single model also selects it for use. Removing an
    // existing default never silently switches the request to another model.
    if(initial&&picking.size===1)draft.selectedModelId=[...picking][0];
    if(previous!==draft.selectedModelId)invalidate();
    $('ai-model-picker').open=false;renderModels();
  }
  function selectModel(id){
    picking.add(id);draft.enabledModelIds=[...picking];draft.selectedModelId=id;
    invalidate();$('ai-model-picker').open=false;renderModels();
  }
  function renderTest(){
    const test=draft?.test||{},labels={untested:'未测试',passed:'测试通过',incomplete:'响应达到输出上限',failed:'测试失败'};text('ai-test-status',(labels[test.status]||'未测试')+(test.elapsedMs?` · ${(test.elapsedMs/1000).toFixed(2)} 秒`:''));text('ai-save-connection',test.status==='passed'?'保存配置':'保存未验证配置');
  }
  function renderWorking(){const busy=!!operation;for(const id of ['ai-fetch-models','ai-test','ai-save-connection'])$(id).disabled=busy;$('ai-operation-cancel').hidden=!busy;}
  function startOperation(kind){
    try{remember();setError('ai-form-error','');const opId=String(++serial);operation={opId,kind};renderWorking();text(kind==='aiDiscover'?'ai-directory-status':'ai-test-status',kind==='aiDiscover'?'正在获取模型…':'正在测试…');invoke(kind,{opId,connection:capture()});}catch(e){setError('ai-form-error',e.message);}
  }
  function openSettings(){window.Moss.showSheet('ai-settings-sheet');if(!draft&&settings.connections.length)openConnection(selectedConnection()||settings.connections[0]);else renderConnections();}
  function templateOptions(){
    const current=$('minutes-template').value||'meeting';$('minutes-template').replaceChildren(...templates.map(t=>option(t.id,t.name)));$('minutes-template').value=templates.some(t=>t.id===current)?current:'meeting';
    const select=$('ai-template-select');select.replaceChildren(...templates.map(t=>option(t.id,t.name)));select.value=templateId;
  }
  function chooseTemplate(id){const t=templates.find(t=>t.id===id);templateId=t?.id||'';$('ai-template-select').value=templateId;$('ai-template-name').value=t?.name||'';$('ai-template-prompt').value=t?.prompt||'';text('ai-template-save',t?.custom?'保存修改':t?'另存为模板':'保存模板');$('ai-template-delete').hidden=!t?.custom;}
  function openTemplates(){templateOptions();chooseTemplate($('minutes-template').value||'meeting');window.Moss.showSheet('ai-template-sheet');}
  function availability(){const context=window.Moss?.aiContext();$('minutes-open').disabled=!context?.hasText||!!context.busy;}
  function openMinutes(){minutesContext=window.Moss.aiContext();minutesData={versions:[]};sources={};versionId='';minutesBusy=false;editing=false;setError('minutes-error','');text('minutes-title',minutesContext.title||'会议纪要');window.Moss.go('minutes');renderMinutes();invoke('aiOpenMinutes',{scope:minutesContext.scope});}
  function inline(element,value){
    const pattern=/\[([^\]\n]{1,100})\]\(source:([A-Za-z0-9_.-]{1,100})\)|\*\*([^*\n]+)\*\*/g;let at=0,match;
    while((match=pattern.exec(value))){element.append(document.createTextNode(value.slice(at,match.index)));if(match[3]){const bold=document.createElement('strong');bold.textContent=match[3];element.append(bold);}else{
      const segmentId=match[2],row=sources[segmentId];if(row){const button=document.createElement('button');button.className='minutes-source';button.textContent=clock(row.start);button.setAttribute('aria-label','回听 '+clock(row.start));button.onclick=()=>invoke('aiSeekSource',{recordId:minutesContext.recordId,segmentId});element.append(button);}else element.append(document.createTextNode(match[1]));
    }at=pattern.lastIndex;}element.append(document.createTextNode(value.slice(at)));
  }
  function clock(value){const n=Math.max(0,Math.floor(Number(value)||0));return `${String(Math.floor(n/3600)).padStart(2,'0')}:${String(Math.floor(n/60)%60).padStart(2,'0')}:${String(n%60).padStart(2,'0')}`;}
  function renderMarkdown(container,markdown){
    container.replaceChildren();const lines=markdown.replace(/^```(?:markdown|md)?\s*\n/i,'').replace(/\n```\s*$/,'').split('\n');let list=null;
    for(let i=0;i<lines.length;i++){const line=lines[i].trim();if(!line){list=null;continue;}
      if(line.includes('|')&&i+1<lines.length&&/^\s*\|?\s*:?-{3,}/.test(lines[i+1])){
        const wrap=document.createElement('div'),table=document.createElement('table');wrap.className='minutes-table-scroll';
        const cells=s=>s.replace(/^\s*\||\|\s*$/g,'').split('|').map(v=>v.trim());const header=document.createElement('tr');for(const value of cells(line)){const th=document.createElement('th');inline(th,value);header.append(th);}table.append(header);i+=2;
        for(;i<lines.length&&lines[i].includes('|');i++){const tr=document.createElement('tr');for(const value of cells(lines[i])){const td=document.createElement('td');inline(td,value);tr.append(td);}table.append(tr);}i--;wrap.append(table);container.append(wrap);list=null;continue;
      }
      const heading=line.match(/^(#{1,6})\s+(.+)/);if(heading){const h=document.createElement(heading[1].length<=2?'h2':'h3');inline(h,heading[2]);container.append(h);list=null;continue;}
      const bullet=line.match(/^(?:[-*+]\s+|\d+[.)]\s+)(.*)/);if(bullet){if(!list){list=document.createElement('ul');container.append(list);}const li=document.createElement('li');inline(li,bullet[1]);list.append(li);continue;}
      list=null;const p=document.createElement('p');inline(p,line);container.append(p);
    }
  }
  function renderMinutes(){
    const v=version(),connection=selectedConnection();text('minutes-model',connection?.selectedModelId?`${connection.name} · ${connection.selectedModelId}`:'选择 AI 模型');
    $('minutes-generate').disabled=minutesBusy||editing||!minutesContext?.hasText;text('minutes-generate',minutesData.versions?.length?'重新生成':'生成纪要');$('minutes-template').disabled=minutesBusy;
    $('minutes-progress').hidden=!minutesBusy;$('minutes-empty').hidden=!!v||minutesBusy||editing;$('minutes-content').hidden=editing;$('minutes-editor-wrap').hidden=!editing;
    for(const id of ['minutes-edit','minutes-copy','minutes-export','minutes-delete'])$(id).disabled=!v||editing||minutesBusy;
    $('minutes-versions-label').hidden=!minutesData.versions?.length;text('minutes-versions-count',`历次纪要 · ${minutesData.versions?.length||0}`);
    $('minutes-version').disabled=editing||minutesBusy;const select=$('minutes-version');select.hidden=!minutesData.versions?.length;select.replaceChildren();for(const row of minutesData.versions||[])select.append(option(row.id,`${new Date(row.created).toLocaleString()} · ${row.edited?'已编辑':row.templateName}`));select.value=versionId;
    if(v){sources=v.sources||sources;renderMarkdown($('minutes-content'),v.markdown);if(v.incomplete)setError('minutes-error','已保存当前输出；达到模型输出上限，可重新生成。');}else $('minutes-content').replaceChildren();
  }
  function receive(message){
    if(message.kind==='notice'){window.Moss.notice(message.message);if(operation){setError('ai-form-error',message.message);text('ai-directory-status','');operation=null;renderWorking();renderTest();}return;}
    if(message.kind==='config'){settings=message.settings;presets=message.presets;templates=message.templates;renderConnections();templateOptions();renderMinutes();return;}
    if(message.kind==='saved'){const saved=settings.connections.find(c=>c.id===message.id);if(saved)openConnection(saved);window.Moss.notice('连接已保存');return;}
    if(message.kind==='templateSaved'){chooseTemplate(message.id);$('minutes-template').value=message.id;window.Moss.notice('模板已保存');return;}
    if(message.kind==='operation'){
      if(!operation||message.opId!==operation.opId||draft?.id!==message.id)return;operation=null;renderWorking();
      if(message.operation==='discover'){
        if(message.models){const ids=new Set(message.models.map(m=>m.id));draft.models=[...message.models,...draft.models.filter(m=>(draft.enabledModelIds||[]).includes(m.id)&&!ids.has(m.id)).map(m=>({...m,source:'manual'}))];draft.directoryFetchedAt=Date.now();picking=new Set(draft.enabledModelIds);renderModels();text('ai-directory-status',`已获取 ${message.models.length} 个模型 · ${(message.elapsedMs/1000).toFixed(2)} 秒`);$('ai-model-picker').open=true;}
        else {draft.directoryStatus='stale';draft.models=draft.models.map(m=>({...m,source:m.source==='remote'?'cache':m.source}));text('ai-directory-status',(draft.directoryFetchedAt?'保留上次目录 · ':'')+(message.message||'已取消'));}
      }else{draft.test={status:message.status==='error'?'failed':message.status,elapsedMs:message.elapsedMs};renderTest();if(message.message)setError('ai-form-error',message.message);}return;
    }
    if(message.recordId!==minutesContext?.recordId)return;
    if(message.kind==='minutes'){minutesData=message.data;sources=message.sources;minutesBusy=message.busy;versionId=minutesData.versions?.[0]?.id||'';text('minutes-title',message.title||'会议纪要');renderMinutes();}
    if(message.kind==='minutesProgress'){minutesBusy=message.busy;text('minutes-progress-text',message.status);renderMinutes();}
    if(message.kind==='minutesError'){setError('minutes-error',message.message);}
    if(message.kind==='minutesSaved'){minutesData=message.data;versionId=minutesData.versions?.[0]?.id||'';editing=false;setError('minutes-error','');renderMinutes();}
  }
  $('ai-settings-open').onclick=$('minutes-model').onclick=openSettings;$('ai-templates-open').onclick=$('minutes-templates').onclick=openTemplates;
  $('ai-add-connection').onclick=addConnection;$('ai-provider-search').oninput=renderProviders;$('ai-provider').onchange=e=>chooseProvider(e.target.value);
  $('ai-key-clear').onclick=()=>{draft.clearKey=true;$('ai-key').value='';$('ai-key').placeholder='填写新密钥';$('ai-key-clear').hidden=true;invalidate();};
  for(const id of ['ai-key','ai-base-url','ai-api','ai-auth','ai-models-path','ai-inference-path'])$(id).addEventListener('input',()=>{invalidate(true);if(id!=='ai-key')remember();});
  $('ai-api').addEventListener('change',()=>{$('ai-inference-path').value='';remember();});
  $('ai-default-model').onchange=()=>{draft.selectedModelId=$('ai-default-model').value;invalidate();};
  $('ai-fetch-models').onclick=()=>startOperation('aiDiscover');$('ai-test').onclick=()=>startOperation('aiTest');
  $('ai-operation-cancel').onclick=()=>{invoke('aiCancelOperation');operation=null;serial++;renderWorking();text('ai-directory-status','已取消');renderTest();};
  $('ai-model-picker').ontoggle=()=>{if($('ai-model-picker').open&&draft){picking=new Set(draft.enabledModelIds||[]);renderModels();}};
  $('ai-model-search').oninput=renderModels;$('ai-models-cancel').onclick=()=>{$('ai-model-picker').open=false;picking=new Set(draft.enabledModelIds);};
  $('ai-models-apply').onclick=applyPicking;
  $('ai-add-model').onclick=()=>{const id=$('ai-manual-model').value.trim();if(!id)return;if(!draft.models.some(m=>m.id===id))draft.models.push({id,name:id,source:'manual'});if(!draft.enabledModelIds.includes(id))draft.enabledModelIds.push(id);draft.selectedModelId=id;picking=new Set(draft.enabledModelIds);$('ai-manual-model').value='';invalidate();renderModels();};
  $('ai-save-connection').onclick=()=>{try{setError('ai-form-error','');if($('ai-model-picker').open)applyPicking();const connection=capture();if(connection.models?.length&&!connection.selectedModelId){setError('ai-form-error','请选择使用的模型');$('ai-model-picker').open=true;return;}invoke('aiSaveConnection',{connection});}catch(e){setError('ai-form-error',e.message);}};
  $('ai-delete-connection').onclick=()=>{const current=draft?.id;if(!current)return;const clear=current===settings.defaultConnectionId;window.Moss.confirmAction('删除连接',`删除“${draft.name}”${clear?'，同时清空默认连接':''}？`,()=>{draft=null;invoke('aiDeleteConnection',{id:current,clearDefault:clear});renderConnections();});};
  $('ai-template-select').onchange=e=>chooseTemplate(e.target.value);$('ai-template-new').onclick=()=>chooseTemplate('');
  $('ai-template-save').onclick=()=>{const current=templates.find(t=>t.id===templateId);invoke('aiSaveTemplate',{template:{id:current?.custom?current.id:'custom-'+uid(),name:$('ai-template-name').value,prompt:$('ai-template-prompt').value}});};
  $('ai-template-delete').onclick=()=>{const id=templateId,name=templates.find(t=>t.id===id)?.name||'这个模板';window.Moss.confirmAction('删除模板',`删除“${name}”？`,()=>{invoke('aiDeleteTemplate',{id});chooseTemplate('meeting');});};
  $('minutes-open').onclick=openMinutes;$('minutes-back').onclick=()=>window.Moss.go('detail');$('minutes-generate').onclick=()=>{if(!selectedConnection()?.selectedModelId){openSettings();return;}setError('minutes-error','');invoke('aiGenerate',{scope:minutesContext.scope,templateId:$('minutes-template').value});};
  $('minutes-cancel').onclick=()=>invoke('aiCancelMinutes');$('minutes-version').onchange=()=>{versionId=$('minutes-version').value;setError('minutes-error','');renderMinutes();};
  function editMinutes(){editing=true;setError('minutes-error','');$('minutes-editor').value=version()?.markdown||'';text('minutes-save',version()?'保存修改':'保存纪要');renderMinutes();$('minutes-editor').focus();}
  $('minutes-edit').onclick=$('minutes-paste').onclick=editMinutes;$('minutes-edit-cancel').onclick=()=>{editing=false;renderMinutes();};
  $('minutes-save').onclick=()=>invoke(versionId?'aiSaveMinutes':'aiPasteMinutes',{scope:minutesContext.scope,recordId:minutesContext.recordId,versionId,markdown:$('minutes-editor').value});
  $('minutes-delete').onclick=()=>{const id=versionId;window.Moss.confirmAction('删除这版纪要','删除当前版本及其 Markdown 文件？',()=>invoke('aiDeleteMinutes',{recordId:minutesContext.recordId,versionId:id}));};
  $('minutes-folder').onclick=()=>invoke('openSaveFolder',{recordId:minutesContext?.recordId||''});
  $('minutes-copy').onclick=()=>invoke('aiCopyMinutes',{recordId:minutesContext.recordId,versionId});
  let exportSelection=null;
  $('minutes-export').onclick=()=>{
    if(!version()||minutesBusy||editing)return;
    exportSelection={recordId:minutesContext.recordId,versionId};
    text('minutes-export-folder',$('export-folder-path').textContent);window.Moss.showSheet('minutes-export-sheet');
  };
  document.querySelectorAll('[data-minutes-export]').forEach(button=>button.onclick=()=>{
    const selection=exportSelection;exportSelection=null;window.Moss.closeSheet();
    if(selection&&selection.recordId===minutesContext?.recordId)invoke('aiExportMinutes',{...selection,format:button.dataset.minutesExport});
  });
  window.MossAI={receive,availability,openMinutes};availability();invoke('aiReady');
})();
