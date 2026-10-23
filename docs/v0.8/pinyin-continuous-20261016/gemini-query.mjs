import fs from 'node:fs/promises';
const origin='http://127.0.0.1:9333';
const targets=await (await fetch(origin+'/json/list')).json();
const target=targets.find(t=>t.type==='page'&&t.url.startsWith('https://gemini.google.com/'));
if(!target)throw Error('NO_PAGE');
const ws=new WebSocket(target.webSocketDebuggerUrl);
await new Promise((r,j)=>{ws.addEventListener('open',r,{once:true});ws.addEventListener('error',j,{once:true});});
let id=0;const pending=new Map();
ws.addEventListener('message',e=>{const m=JSON.parse(e.data);const p=pending.get(m.id);if(p){clearTimeout(p.timer);pending.delete(m.id);m.error?p.reject(Error(JSON.stringify(m.error))):p.resolve(m.result);}});
function call(method,params={}){return new Promise((resolve,reject)=>{const i=++id;const timer=setTimeout(()=>{pending.delete(i);reject(Error('CDP_TIMEOUT '+method));},20000);pending.set(i,{resolve,reject,timer});ws.send(JSON.stringify({id:i,method,params}));});}

let prompt='';for await(const chunk of process.stdin)prompt+=chunk;
try{
 await call('Page.enable');await call('Page.navigate',{url:'https://gemini.google.com/app'});
 await new Promise(r=>setTimeout(r,5000));
 const ready=await call('Runtime.evaluate',{expression:`(()=>{if(location.pathname!=='/app')return false;const e=document.querySelector('[contenteditable=true][role=textbox]');if(!e)return false;e.focus();return true})()`,returnByValue:true});
 if(!ready.result.value)throw Error('NEW_CHAT_NOT_READY');
 await call('Input.insertText',{text:prompt});await new Promise(r=>setTimeout(r,400));
 const sent=await call('Runtime.evaluate',{expression:`(()=>{const b=[...document.querySelectorAll('button')].find(e=>/发送|Send/i.test(e.getAttribute('aria-label')||'')&&!e.disabled);if(!b)return false;b.click();return true})()`,returnByValue:true});
 if(!sent.result.value)throw Error('SEND_FAILED');console.log('GEMINI_APP_WEB_REQUEST_SUBMITTED');
 let result,stable=0,last='';
 for(let i=0;i<90;i++){
  await new Promise(r=>setTimeout(r,2000));
  const r=await call('Runtime.evaluate',{expression:`JSON.stringify({path:location.pathname,text:[...document.querySelectorAll('model-response')].map(e=>e.innerText).join(String.fromCharCode(10)),busy:[...document.querySelectorAll('button')].some(e=>/停止生成|Stop response|Stop generating/i.test(e.getAttribute('aria-label')||'')),models:[...document.querySelectorAll('button,[role=button]')].map(e=>e.innerText?.trim()).filter(t=>t&&/Argon|Pro|Flash/.test(t)).slice(0,5)})`,returnByValue:true});
  result=JSON.parse(r.result.value);stable=result.text===last?stable+1:0;last=result.text;
  if(result.text.length>100&&!result.busy&&stable>=2)break;
 }
 result.complete=Boolean(result.text.length>100&&!result.busy&&stable>=2);
 await fs.writeFile('voiceinput2pc-v08/docs/v0.8/pinyin-continuous-20261016/gemini-result.json',JSON.stringify(result,null,2));console.log(JSON.stringify(result));
 if(!result.complete)throw Error('EXPERT_RESPONSE_INCOMPLETE');
}finally{ws.close();}
