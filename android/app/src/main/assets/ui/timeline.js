/* Pure timeline semantics shared by live preview and transcript playback. */
globalThis.YaoshiTimeline={
  locate(rows,position,processed,busy,complete){
    const time=Math.max(0,Number(position)||0);
    const index=rows.findIndex(row=>Number(row.start)<=time&&time<Number(row.end));
    if(index>=0)return {kind:'segment',index,row:rows[index]};
    if(!complete&&time>=Math.max(0,Number(processed)||0))return {kind:busy?'pending':'untranscribed',index:-1};
    return {kind:'silence',index:-1};
  }
};
