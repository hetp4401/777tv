#!/usr/bin/env node
import fs from 'node:fs/promises';
import path from 'node:path';
import os from 'node:os';
import crypto from 'node:crypto';
import {fileURLToPath} from 'node:url';
import {spawnSync} from 'node:child_process';
import {Readable} from 'node:stream';
import {pipeline} from 'node:stream/promises';
import {createWriteStream} from 'node:fs';

const root=path.dirname(path.dirname(fileURLToPath(import.meta.url)));
const state=path.join(root,'.tools/shellbox-remote');
const config=JSON.parse(await fs.readFile(process.env.FLEET_REMOTE_CONFIG||path.join(state,'worker-access.json'),'utf8'));
const args=process.argv.slice(2), command=args[0]||'doctor';
const defaultUiClasses=['TvFlowTest','EngineLayoutTest','CategoryVisibilityTest','OptimizationRegressionTest',
    'SettingsNavigationTest','LiveNavigationTest','BrowseNavigationTest','SearchCatalogTest','NavigationRobustnessTest','VodControlsTest','YouTubeNavigationTest','YouTubeWatchUiTest','ArtworkMemoryCacheTest','FluidityRegressionTest']
    .map(name=>'xyz.billybishop4.fleet.tv.'+name).join(',');
const headers={Authorization:'Bearer '+config.token};
const sleep=ms=>new Promise(resolve=>setTimeout(resolve,ms));
async function request(worker,pathname,options={}) {
    const r=await fetch(worker+pathname,{...options,headers:{...headers,...options.headers},signal:AbortSignal.timeout(30000)});
    if(!r.ok)throw new Error(`${worker}${pathname}: HTTP ${r.status} ${(await r.text()).slice(0,300)}`);
    return r.json();
}
async function health(worker){try{return{worker,...await request(worker,'/health')};}catch(e){return{worker,ok:false,error:e.message};}}
async function snapshot(){
    const temporary=await fs.mkdtemp(path.join(state,'snapshot-'));
    const archive=path.join(temporary,'source.tgz');
    const code=String.raw`
import sys,pathlib,tarfile,gzip
root=pathlib.Path(sys.argv[1]);target=pathlib.Path(sys.argv[2])
files=[root/p for p in ['.gitignore','AGENTS.md','README.md','REVIEW.md','THIRD_PARTY_NOTICES.md','build.gradle.kts','settings.gradle.kts','gradle.properties','gradlew','gradlew.bat','app/build.gradle.kts']]
for name in ['app/src','scripts','gradle','remote','docs','releases']:
 files.extend(p for p in (root/name).rglob('*') if p.is_file() and '__pycache__' not in p.parts and 'jniLibs' not in p.parts and p.suffix not in ['.keystore','.jks'] and p.name!='.env')
with target.open('wb') as out,gzip.GzipFile(filename='',mode='wb',fileobj=out,mtime=0) as zipped,tarfile.open(fileobj=zipped,mode='w') as tar:
 for p in sorted(files):
  info=tar.gettarinfo(str(p),arcname=p.relative_to(root).as_posix());info.mtime=0;info.uid=info.gid=0;info.uname=info.gname=''
  with p.open('rb') as data:tar.addfile(info,data)
`;
    try{
        const r=spawnSync('python3',['-c',code,root,archive],{stdio:['ignore','inherit','inherit']});if(r.status!==0)throw Error('Source snapshot failed');
        const data=await fs.readFile(archive), digest=crypto.createHash('sha256').update(data).digest('hex');
        await fs.mkdir(path.join(state,'sources'),{recursive:true});
        await fs.writeFile(path.join(state,'sources',digest+'.tgz'),data,{mode:0o600});
        return{data,digest};
    }finally{await fs.rm(temporary,{recursive:true,force:true});}
}
async function sync(worker,source){return request(worker,'/sources',{method:'POST',headers:{'X-Source-SHA256':source.digest,'content-type':'application/gzip'},body:source.data});}
async function saveReceipt(receipt){
    await fs.mkdir(path.join(state,'jobs'),{recursive:true});
    await fs.writeFile(path.join(state,'jobs',receipt.id+'.json'),JSON.stringify(receipt,null,2),{mode:0o600});
}
async function collect(worker,result){
    const dest=path.join(root,'artifacts/remote',result.id);await fs.mkdir(dest,{recursive:true});
    await fs.writeFile(path.join(dest,'result.json'),JSON.stringify(result,null,2));
    const bundledFrames=(result.artifacts||[]).some(a=>a.path==='fixture-frames.json');
    for(const a of result.artifacts||[]){
        if(bundledFrames && a.path.startsWith('screenshots/') && a.path.endsWith('.png'))continue;
        // UI APKs stay remote; only the ARM release is needed on the Mac.
        if(a.path.startsWith('apk/') && result.task!=='build')continue;
        const output=path.resolve(dest,a.path);if(!output.startsWith(dest+path.sep))throw Error('Unsafe artifact path');
        await fs.mkdir(path.dirname(output),{recursive:true});
        const r=await fetch(worker+'/artifacts/'+result.id+'/'+a.path,{headers,signal:AbortSignal.timeout(180000)});if(!r.ok)throw Error('Artifact download failed '+r.status);
        await pipeline(Readable.fromWeb(r.body),createWriteStream(output));
        const digest=crypto.createHash('sha256').update(await fs.readFile(output)).digest('hex');if(digest!==a.sha256)throw Error('Artifact digest mismatch: '+a.path);
        if(a.path.endsWith('app-release-unsigned.apk')&&args.includes('--sign'))sign(output,dest);
    }
    if(bundledFrames) {
        const frames=JSON.parse(await fs.readFile(path.join(dest,'fixture-frames.json'),'utf8'));
        for(const [name,value] of Object.entries(frames)) {
            const expected=(result.artifacts||[]).find(a=>a.path===name);
            const output=path.resolve(dest,name);
            if(!expected || !name.startsWith('screenshots/') || !name.endsWith('.png') || !output.startsWith(dest+path.sep) || typeof value!=='string')throw Error('Unsafe fixture frame');
            const data=Buffer.from(value,'base64');
            if(crypto.createHash('sha256').update(data).digest('hex')!==expected.sha256)throw Error('Fixture frame digest mismatch: '+name);
            await fs.mkdir(path.dirname(output),{recursive:true});await fs.writeFile(output,data);
        }
    }
    return dest;
}
function sign(unsigned,dest){
    if(process.platform!=='darwin')throw Error('Signing is local to the Mac; unsigned artifact retained');
    const sdk=process.env.ANDROID_HOME||path.join(os.homedir(),'Library/Android/sdk');
    const signer=path.join(sdk,'build-tools/36.0.0/apksigner');const output=path.join(dest,'Fleet-TV-remote-preview.apk');
    const env={...process.env,JAVA_HOME:path.join(root,'.tools/jdk/Contents/Home')};
    const a=spawnSync(signer,['sign','--ks',path.join(os.homedir(),'.android/debug.keystore'),'--ks-key-alias','androiddebugkey','--ks-pass','pass:android','--key-pass','pass:android','--out',output,unsigned],{env,stdio:'inherit'});
    if(a.status!==0)throw Error('Local signing failed');const v=spawnSync(signer,['verify','--verbose',output],{env,stdio:'inherit'});if(v.status!==0)throw Error('Signature verification failed');console.log('Locally signed: '+output);
}
async function execute(task,preferred,source,classes){
    let candidates=[preferred,...config.workers.filter(w=>w!==preferred)];let last;
    for(let attempt=0;attempt<2;attempt++){
        const worker=candidates[attempt];const h=await health(worker);if(!h.ok||task==='ui'&&!h.kvm){last=Error('Worker unavailable or missing KVM: '+worker);continue;}
        await sync(worker,source);
        const receipt=await request(worker,'/jobs',{method:'POST',headers:{'content-type':'application/json'},body:JSON.stringify({source:source.digest,task,...(classes?{classes}:{})})});
        receipt.worker=worker;await saveReceipt(receipt);console.log(`${task}: ${worker} job ${receipt.id}`);
        let errors=0,lastLine='';
        for(;;){
            let result;
            try{result=await request(worker,'/jobs/'+receipt.id);errors=0;}catch(e){last=e;if(++errors<4){await sleep(5000);continue;}break;}
            if(result.state==='interrupted'){last=Error('Worker was replaced during the job');break;}
            const line=(result.logTail||'').trim().split('\n').at(-1)||'queued';if(line!==lastLine){console.log(`${task}: ${line.slice(0,220)}`);lastLine=line;}
            if(result.state==='passed'||result.state==='failed'){
                const dest=await collect(worker,result);console.log(`${task}: ${result.state}; ${dest}`);
                if(result.state!=='passed')throw Error(`${task} failed: ${result.error}; see ${dest}/job.log`);
                return{...result,worker,directory:dest};
            }
            await sleep(5000);
        }
        console.log(`${task}: worker interrupted; retrying on another replica`);
    }
    throw last||Error('No available remote worker');
}
await fs.mkdir(state,{recursive:true});
if(command==='doctor'){
    console.log(JSON.stringify(await Promise.all(config.workers.map(health)),null,2));
}else if(command==='status'||command==='collect'){
    const job=JSON.parse(await fs.readFile(path.join(state,'jobs',args[1]+'.json'),'utf8'));console.log(JSON.stringify(await request(job.worker,'/jobs/'+job.id),null,2));
    if(command==='collect'){
        const result=await request(job.worker,'/jobs/'+job.id);
        if(!['passed','failed'].includes(result.state))throw Error('Job has not completed; use status to check it');
        console.log('Collected: '+await collect(job.worker,result));
        if(result.state==='failed')process.exitCode=1;
    }
}else if(command==='sync'){
    const source=await snapshot();for(const worker of config.workers)console.log(worker,await sync(worker,source));
}else if(command==='pipeline'||['build','unit','ui'].includes(command)){
    const source=await snapshot();let results;
    if(command==='pipeline')results=await Promise.allSettled(['build','unit','ui'].map((task,i)=>execute(task,config.workers[i],source,task==='ui'?defaultUiClasses:undefined)));
    else{const wi=args.indexOf('--worker');const workerIndex=wi>=0?Number(args[wi+1])-1:0;if(!Number.isInteger(workerIndex)||!config.workers[workerIndex])throw Error('Unknown worker number');const at=args.indexOf('--classes');results=await Promise.allSettled([execute(command,config.workers[workerIndex],source,at>=0?args[at+1]:command==='ui'?defaultUiClasses:undefined)]);}
    await fs.writeFile(path.join(state,'latest-results.json'),JSON.stringify(results.map(r=>r.status==='rejected'?{status:r.status,error:r.reason.message}:r),null,2));
    if(results.some(r=>r.status==='rejected')){for(const r of results)if(r.status==='rejected')console.error(r.reason.message);process.exitCode=1;}
}else throw Error('Usage: remote.sh doctor | sync | build|unit|ui [--worker N] [--classes CLASS_LIST] [--sign] | pipeline [--sign] | status|collect JOB_ID [--sign]');
