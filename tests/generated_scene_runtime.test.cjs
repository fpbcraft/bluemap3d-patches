const {test} = require('node:test');
const assert = require('node:assert/strict');
const {readFileSync} = require('node:fs');
const vm = require('node:vm');
const generated = process.env.BLUEMAP3D_GENERATED_CORE;
class Vector {
    constructor(x=0,y=0,z=0,w=1) { Object.assign(this,{x,y,z,w}); }
    set(x,y,z,w=this.w) { Object.assign(this,{x,y,z,w}); return this; }
    slerp(b,alpha) { for(const key of ['x','y','z','w'])this[key]+=(b[key]-this[key])*alpha; return this; }
    setFromAxisAngle(axis,angle) { this.angle=angle; return this; }
}
class Matrix { compose(p,q,s) { this.values=[p.x,p.y,p.z,q.x,q.y,q.z,q.w,s.x,s.y,s.z]; return this; } }
function fixture() {
    let now=0;
    const context=vm.createContext({THREE:{Vector3:Vector,Quaternion:Vector,Matrix4:Matrix},
        performance:{now:()=>now},shipMarkers:null,_scratch:new Vector(),
        instancePosition:null,instanceScale:null,instanceFromQ:null,instanceToQ:null,instanceMatrix:null,
        KIND_SPIN:0,KIND_OSCILLATE:1,KIND_ORBIT:2,KIND_RATE:3,KIND_LOOP:4,KIND_UV_SCROLL:5,MAX_RATE_DT_SECONDS:1});
    const source=readFileSync(generated,'utf8');
    const extract=(name,end)=>source.slice(source.indexOf(`    function ${name}(`),source.indexOf(end,source.indexOf(`    function ${name}(`)));
    vm.runInContext(readFileSync(require('node:path').join(__dirname,'../scripts/transforms/dirty_transforms.js'),'utf8')+
        extract('groupsById','    function writeInstanceTransforms')+
        extract('writeInstanceTransforms','    function createReplayInstanceGroup')+
        extract('writeTransform','    var _scratch = null;'),context);
    return {context,tick:value=>now=value};
}
test('actual generated root transform skips stationary work while rate animation continues', {skip:!generated}, () => {
    const {context,tick}=fixture(); let writes=0;
    const pose={pos:[1,2,3],rot:[0,0,0,1],scale:[1,1,1]};
    const mesh={position:new Vector(),quaternion:new Vector(),scale:new Vector(),updateMatrix:()=>writes++};
    const group={position:new Vector(),quaternion:new Vector()};
    const entry={mesh,from:pose,to:pose,nodeGroups:[group],nodes:[{kind:3,rate:2,axis:{}}],
        nodeSigns:[1],odometer:0,segmentTravel:0,rateAngles:[0],rateLastTime:[null]};
    for(let frame=0;frame<60;frame++){tick(frame*16);context.writeTransform(entry,.5);}
    assert.equal(writes,1);
    assert.equal(mesh.position.x,1);
    assert(group.quaternion.angle>1.8,'continuous animation still advances on a stationary root');
    assert.equal(context.transformWork.rootSkips,59);
});
test('actual generated instance path skips static matrices and uploads only the moving range', {skip:!generated}, () => {
    const {context}=fixture();const calls=[];let uploads=0;
    const attribute={updateRange:{offset:0,count:-1}};
    Object.defineProperty(attribute,'needsUpdate',{set:()=>uploads++});
    const state={capacity:4,mesh:{count:0,instanceMatrix:attribute,setMatrixAt:(i,m)=>calls.push({i,pose:[...m.values]})}};
    const pose=x=>[x,0,0,0,0,0,1,1,1,1];
    const a=[pose(1),pose(2)];
    const entry={isInstanced:true,instanceGroups:{g:state},from:{groups:[{id:'g',instances:a}]},to:{groups:[{id:'g',instances:a}]}};
    context.writeInstanceTransforms(entry,.5);
    for(let i=0;i<60;i++)context.writeInstanceTransforms(entry,i/60);
    entry.from={groups:[{id:'g',instances:a.map(p=>[...p])}]};entry.to={groups:[{id:'g',instances:a.map(p=>[...p])}]};
    context.writeInstanceTransforms(entry,.5);
    assert.equal(calls.length,2);assert.equal(uploads,1);
    attribute.updateRange.count=-1;calls.length=0;
    entry.to={groups:[{id:'g',instances:[pose(1),pose(4)]}]};
    context.writeInstanceTransforms(entry,.5);
    assert.equal(calls.length,1);assert.equal(calls[0].i,1);assert.equal(calls[0].pose[0],3);
    assert.deepEqual(attribute.updateRange,{offset:16,count:16});
});
