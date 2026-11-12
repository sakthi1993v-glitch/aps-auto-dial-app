'use strict';
const assert=require('assert/strict'),fs=require('fs'),path=require('path'),vm=require('vm');
const code=fs.readFileSync(path.join(__dirname,'../app/src/main/assets/live-call-bridge.js'),'utf8');
async function main(){
 const sent=[],events=[],timers=new Map();let timerId=0;
 const transport={postMessage:text=>sent.push(JSON.parse(text))};
 const ctx={APSNativeLiveMessages:transport,CustomEvent:class{constructor(type){this.type=type}},setTimeout:fn=>{timers.set(++timerId,fn);return timerId},clearTimeout:id=>timers.delete(id),Promise,JSON,Date};
 ctx.window=ctx;ctx.dispatchEvent=e=>events.push(e.type);vm.createContext(ctx);vm.runInContext(code,ctx);
 assert.equal(ctx.APSLiveCalls.bridgeVersion,2);assert.deepEqual(events,['aps-live-bridge-ready']);
 const statusPromise=ctx.APSLiveCalls.getSetupStatus(),logoutPromise=ctx.APSLiveCalls.prepareLogout(),openPromise=ctx.APSLiveCalls.openSetup();
 assert.deepEqual(sent.map(r=>r.action),['getSetupStatus','prepareLogout','openSetup']);assert.equal(new Set(sent.map(r=>r.id)).size,3);
 const reply=(index,value)=>transport.onmessage({data:JSON.stringify({id:sent[index].id,value})});
 reply(2,true);reply(0,{supported:true,officeSim:{slot:2,carrier:'Carrier'}});reply(1,true);
 assert.equal(await openPromise,true);assert.equal(await logoutPromise,true);assert.equal((await statusPromise).officeSim.slot,2);assert.equal(timers.size,0);
 const original=ctx.APSLiveCalls;const pending=ctx.APSLiveCalls.getSetupStatus();vm.runInContext(code,ctx);assert.equal(ctx.APSLiveCalls,original);assert.equal(events.length,2);reply(3,{supported:true});assert.equal((await pending).supported,true);
 const timeout=ctx.APSLiveCalls.openSetup();for(const fn of [...timers.values()])fn();assert.equal(await timeout,false);
 transport.postMessage=()=>{throw Error('bridge unavailable')};assert.equal(await ctx.APSLiveCalls.openSetup(),false);
 console.log('PASS: native bridge independent request IDs, concurrent status/logout/setup replies, reinjection, timeout and unavailable fallback');
}
main().catch(e=>{console.error(e);process.exitCode=1});
