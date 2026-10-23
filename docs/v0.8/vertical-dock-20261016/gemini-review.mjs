import fs from 'node:fs/promises';
import {connect} from '../../../../tools/termux-browser-probe/cdp-client.mjs';
const base='voiceinput2pc-v08/docs/v0.8/vertical-dock-20261016/';
const tab=await(await fetch('http://127.0.0.1:9333/json/new?https://gemini.google.com/app',{method:'PUT'})).json();const c=await connect(tab.id);
try {
 await new Promise(r=>setTimeout(r,5500));
 const account=await c.evaluate(`([...document.querySelectorAll('[aria-label],[title]')].map(e=>(e.getAttribute('aria-label')||'')+' '+(e.getAttribute('title')||'')).filter(s=>/cooocvc@gmail.com/i.test(s)))`);
 if(!account.length)throw Error('REQUIRED_ACCOUNT_NOT_CONFIRMED');
 const prompt='请作为Android输入法工程与视觉顾问，聚焦评审已有IME内小麦克风入口改版。用户要求不固定位置、缩小图标、上方弹删除下方弹确认，长按更多。当前IME窗口只有底部128dp高、没有系统overlay权限；禁止Root/系统设置。1提出可拖动范围及触摸区域/窗口边界的真实限制；2给上删除中麦克风下发送三圆按钮推荐dp尺寸，视觉小但48dp触摸；3如何避免拖动/长按/单击冲突和自动发送；4给一个简洁白底青灰色竖排效果图SVG代码，必须标注此为交互预览，左示收起右示展开，不需要整屏手机。不超过1000字。确认按钮依宿主输入框动作发送/搜索/回车；收音中禁用。不得擅自改用常驻悬浮窗。';
 await c.evaluate(`(()=>{const e=document.querySelector('[contenteditable=true][role=textbox]');if(!e)throw Error('NO_EDITOR');e.focus()})()`);await c.call('Input.insertText',{text:prompt});
 await c.evaluate(`(()=>{const b=[...document.querySelectorAll('button')].find(e=>/发送|Send/i.test(e.getAttribute('aria-label')||'')&&!e.disabled);if(!b)throw Error('NO_SEND');b.click()})()`);
 let result,last='',stable=0;
 for(let i=0;i<85;i++){await new Promise(r=>setTimeout(r,2000));result=await c.evaluate(`({text:[...document.querySelectorAll('model-response')].map(e=>e.innerText).join('\n'),busy:[...document.querySelectorAll('button')].some(e=>/停止生成|Stop response|Stop generating/i.test(e.getAttribute('aria-label')||'')),model:[...document.querySelectorAll('button')].map(e=>e.innerText).filter(t=>t&&/Pro|Flash|思考|快速/.test(t)).slice(0,5)})`);stable=last===result.text?stable+1:0;last=result.text;if(result.text.length>150&&!result.busy&&stable>=2)break;}
 result.complete=Boolean(result.text.length>150&&!result.busy&&stable>=2);result.channel='Gemini website';result.required_account_confirmed=true;
 await fs.writeFile(base+'gemini-review.json',JSON.stringify(result,null,2));console.log(JSON.stringify(result));
}finally{c.close()}
