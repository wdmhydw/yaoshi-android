/* User changes are applied over immutable recognition output, including live updates. */
globalThis.YaoshiTranscript={
  preview(rows,draft,annotations={}){
    // A draft has no end timestamp and never enters the durable document.
    const valid=draft?.provisional&&typeof draft.text==='string'&&draft.text.trim()&&Number.isFinite(draft.start);
    return this.apply(valid?[...rows,draft]:rows,annotations);
  },
  resolve(row,annotations={}){
    const raw=row.originalSpeaker??row.speaker??'S01';
    let key=annotations.segmentSpeakers?.[String(row.id)]??annotations.autoSpeakers?.[raw]??raw;
    const seen=new Set();while(annotations.speakerLinks?.[key]&&!seen.has(key)){seen.add(key);key=annotations.speakerLinks[key];}return key;
  },
  names(rows,annotations={}){
    const out={},names=annotations.speakerNames||{};
    for(const row of rows){const key=this.resolve(row,annotations);if(names[key])out[key]=names[key];}
    for(const row of rows){const key=this.resolve(row,annotations),raw=row.originalSpeaker??row.speaker??'S01';if(!out[key]&&names[raw])out[key]=names[raw];}
    return out;
  },
  apply(rows,annotations={}){
    const numbers=new Map();
    return rows.map((row,index)=>{
      const id=String(row.id??'row_'+index),raw=row.originalText??row.text??'';
      const edited=Object.prototype.hasOwnProperty.call(annotations.textOverrides||{},id);
      const speaker=this.resolve({...row,id},annotations);if(!numbers.has(speaker))numbers.set(speaker,numbers.size+1);
      return {...row,id,originalSpeaker:row.originalSpeaker??row.speaker??'S01',speaker,speakerNumber:numbers.get(speaker),originalText:raw,text:edited?annotations.textOverrides[id]:raw,edited,marked:!!annotations.bookmarks?.[id]};
    });
  },
  matches(row,query,name=''){
    const needle=String(query).trim().toLocaleLowerCase();
    return !needle||String(row.text||'').toLocaleLowerCase().includes(needle)||String(name).toLocaleLowerCase().includes(needle);
  },
  parts(text,query){
    text=String(text);query=String(query).trim();if(!query)return [{text,hit:false}];
    const folded=text.toLocaleLowerCase(),needle=query.toLocaleLowerCase(),parts=[];let cursor=0,index;
    while((index=folded.indexOf(needle,cursor))>=0){
      if(index>cursor)parts.push({text:text.slice(cursor,index),hit:false});
      parts.push({text:text.slice(index,index+query.length),hit:true});cursor=index+query.length;
    }
    if(cursor<text.length)parts.push({text:text.slice(cursor),hit:false});return parts;
  }
};
