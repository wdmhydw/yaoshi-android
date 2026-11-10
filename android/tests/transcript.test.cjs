const assert=require('node:assert/strict');
require('../app/src/main/assets/ui/transcript.js');
const T=globalThis.YaoshiTranscript;
const raw=[{id:'seg_1',text:'识别错字',speaker:'S01',start:0,end:2},{text:'原始第二句',speaker:'S02',start:2,end:4}];
const notes={textOverrides:{seg_1:'校对后的文字'},bookmarks:{seg_1:true}};
const edited=T.apply(raw,notes);
assert.equal(raw[0].text,'识别错字','never mutate engine output');
assert.deepEqual([edited[0].text,edited[0].originalText,edited[0].marked,edited[0].edited],['校对后的文字','识别错字',true,true]);
assert.equal(T.apply([...raw,{id:'seg_3',text:'新一句'}],notes)[0].text,'校对后的文字','live updates keep corrections');
assert.equal(T.apply(edited,{})[0].text,'识别错字','reset restores original, including decorated input');
assert.equal(edited[1].id,'row_1','old records have stable IDs');
assert.equal(T.apply(raw,{bookmarks:{row_1:true}})[1].marked,true);
assert(T.matches(edited[0],'校对'));
assert(!T.matches(edited[0],'错字'),'search displayed corrections');
assert(T.matches(edited[0],'主持','主持人'));
assert(T.matches({text:'Hello WORLD'},'world'));
assert.deepEqual(T.parts('<script>正文正文','正文'),[{text:'<script>',hit:false},{text:'正文',hit:true},{text:'正文',hit:true}]);
assert.deepEqual(T.parts('a+b a+b','a+b'),[{text:'a+b',hit:true},{text:' ',hit:false},{text:'a+b',hit:true}],'query is literal, not regex');
console.log('12 transcript annotation/search assertions passed');
const identityRows=[
  {id:'s1',speaker:'片段1-S01',text:'第一句'},
  {id:'s2',speaker:'片段2-S02',text:'第二句'},
  {id:'s3',speaker:'片段2-S01',text:'第三句'}
];
const identityNotes={autoSpeakers:{'片段1-S01':'voice_1','片段2-S02':'voice_1','片段2-S01':'voice_2'},speakerNames:{voice_1:'主持人'}};
let identities=T.apply(identityRows,identityNotes);
assert.equal(identities[0].speaker,identities[1].speaker,'swapped local numbers share the matched voice');
assert.notEqual(identities[0].speaker,identities[2].speaker,'same local number is not treated as same voice');
assert.deepEqual(identities.map(r=>r.speakerNumber),[1,1,2]);
assert.equal(T.names(identityRows,identityNotes).voice_1,'主持人');
identityNotes.segmentSpeakers={s2:'manual_1'};
identities=T.apply(identities,identityNotes);
assert.equal(identities[1].speaker,'manual_1','manual split overlays already decorated rows');
assert.equal(identities[0].speaker,'voice_1');
identityNotes.speakerLinks={voice_2:'voice_1'};
identities=T.apply(identities,identityNotes);
assert.equal(identities[2].speaker,'voice_1','merge applies to raw records and decorated snapshots');
assert.equal(identities[1].speaker,'manual_1','a manual sentence assignment survives automatic updates');
delete identityNotes.segmentSpeakers.s2;
assert.equal(T.apply(identities,identityNotes)[1].speaker,'voice_1','reset returns to raw identity map');
assert.equal(T.apply(identities,{})[1].speaker,'片段2-S02','original voice evidence preserved');
assert.equal(identityRows[0].speaker,'片段1-S01','source rows immutable');
assert.equal(T.names(identityRows,{...identityNotes,speakerNames:{'片段1-S01':'旧标注'}}).voice_1,'旧标注','legacy labels survive matching');
assert.equal(T.names(identityRows,{...identityNotes,speakerNames:{'片段1-S01':'旧标注',voice_1:'新标注'}}).voice_1,'新标注','explicit canonical label wins');
assert.doesNotThrow(()=>T.resolve({speaker:'a'},{speakerLinks:{a:'b',b:'a'}}),'cycles are bounded');
console.log('14 speaker grouping/annotation assertions passed');
