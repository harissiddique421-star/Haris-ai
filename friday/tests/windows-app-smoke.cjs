/** Launches the real Electron entry/backend with an isolated data profile. */
const { app, BrowserWindow } = require('electron');
const path = require('path'); const fs = require('fs'); const os = require('os');
const profile = fs.mkdtempSync(path.join(os.tmpdir(),'myraa-v2-app-'));
// Exercise offline startup; never borrow the owner's credentials for UI checks.
fs.writeFileSync(path.join(profile,'secrets.json'),JSON.stringify({ignoreEnvironmentApiKey:true}));
process.env.MYRAA_DATA_DIR = profile;
process.env.MYRAA_CHARACTERS_DIR = path.join(process.env.APPDATA,'MYRAA','characters');
process.env.MYRAA_PORT = '5188';
process.env.GEMINI_API_KEY = '';
process.env.GOOGLE_API_KEY = '';
fs.writeFileSync(path.join(profile,'app-settings.v3.json'),JSON.stringify({version:3,onboardingComplete:true,companion:{enabled:false},behavior:{proactivity:'quiet',awayCheckin:false,returnGreeting:false}}));
fs.mkdirSync(path.join(profile,'memory'),{recursive:true});
const preserved = [{role:'user',text:'MYRAA V2 preservation test',at:new Date().toISOString()},{role:'model',text:'Local test history',at:new Date().toISOString()}];
fs.writeFileSync(path.join(profile,'memory','text-chat.v2.json'),JSON.stringify(preserved));
const output = path.join(__dirname,'../artifacts/windows-app-smoke.json');
const record = { profile, checks: [], errors: [] };
const wait = ms => new Promise(r=>setTimeout(r,ms));
let done = false;
app.on('web-contents-created', (_event, contents) => {
  contents.on('did-finish-load', async () => {
    if (done || !contents.getURL().startsWith('http://127.0.0.1:5188')) return;
    done = true;
    try {
      await wait(2000);
      const offline = await contents.executeJavaScript(`(()=>{const b=[...document.querySelectorAll('button')].find(b=>b.textContent.includes('Use the character without AI'));if(b){b.click();return true}return false})()`);
      record.checks.push({name:'credential-free offline startup',passed:offline});
      await wait(13000);
      const window = BrowserWindow.fromWebContents(contents);
      record.checks.push({ name:'real main window loaded', passed:true, url:contents.getURL(), title:contents.getTitle() });
      record.api = await contents.executeJavaScript(`(async()=>{const paths=['/api/config','/api/app-settings','/api/permissions','/api/characters','/api/chat/history'];const values={};for(const p of paths){const r=await fetch(p);values[p]={status:r.status,body:await r.json()}}return values})()`);
      record.body = await contents.executeJavaScript('document.body.innerText.slice(0,2500)');
      record.checks.push({ name:'DOM populated', passed:record.body.length>100 });
      record.checks.push({ name:'API responses', passed:Object.values(record.api).every(r=>r.status===200) });
      record.checks.push({ name:'app launch requires consent', passed:record.api['/api/permissions'].body.policy.capabilities.LAUNCH_APP.decision==='ask' });
      const denial = await contents.executeJavaScript(`(async()=>{await fetch('/api/permissions/ACCESS_CAMERA',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({decision:'deny'})});return(await fetch('/api/permissions/request',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({capability:'ACCESS_CAMERA'})})).json()})()`);
      record.checks.push({ name:'denied camera policy enforced', passed:denial.allowed===false });
      const readable = await contents.executeJavaScript(`(()=>{const labels=['Conversation suggestions','Memories','Character Studio','Share screen','Settings','Choose AI model'];return labels.every(label=>{const b=document.querySelector('button[aria-label="'+label+'"]');return b&&Number(getComputedStyle(b).opacity)>=.8})})()`);
      record.checks.push({name:'primary utilities remain readable without hover',passed:readable});
      await contents.executeJavaScript(`document.querySelector('button[aria-label="Settings"]').focus();document.querySelector('button[aria-label="Settings"]').click()`);
      await wait(500);
      record.checks.push({name:'settings takes focus within its dialog',passed:await contents.executeJavaScript(`document.querySelector('[role="dialog"][aria-label="Settings"]').contains(document.activeElement)`)});
      await contents.executeJavaScript(`document.querySelector('button[role="switch"][aria-label="Animations"]').click()`);
      await wait(200);
      record.checks.push({name:'UI motion preference stops decorative particle rendering',passed:await contents.executeJavaScript(`(()=>{const c=document.querySelector('#myraa-hologram-living-canvas');const data=c.getContext('2d').getImageData(0,0,c.width,c.height).data;return document.querySelector('#myraa-holographic-desktop').dataset.uiMotion==='reduced'&&!data.some((value,index)=>index%4===3&&value>0)})()`)});
      await contents.executeJavaScript(`[...document.querySelectorAll('button')].find(b=>b.textContent.trim()==='Character & companion').click()`);
      await wait(200);
      const controls = await contents.executeJavaScript(`(()=>{const group=document.querySelector('[role="group"][aria-label="Graphics preset"]');const mode=document.querySelector('select[aria-label="Companion interaction mode"]');return{presets:[...group.querySelectorAll('button')].map(b=>b.textContent.trim()),modes:[...mode.options].map(o=>o.value)}})()`);
      record.checks.push({name:'three graphics presets and five companion modes',passed:JSON.stringify(controls.presets)===JSON.stringify(['Low','Medium','High'])&&controls.modes.length===5,detail:controls});
      await contents.executeJavaScript(`document.querySelector('input[aria-label="Message MYRAA"]').focus();`);
      // Retain a React-controlled draft while changing the avatar's graphics budget.
      await contents.executeJavaScript(`(()=>{const input=document.querySelector('input[aria-label="Message MYRAA"]');Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value').set.call(input,'unsent graphics retention check');input.dispatchEvent(new Event('input',{bubbles:true}));document.querySelector('button[role="switch"][aria-label="Choose graphics automatically"]').click()})()`);
      await wait(300);
      await contents.executeJavaScript(`[...document.querySelectorAll('[aria-label="Graphics preset"] button')].find(b=>b.textContent.trim()==='Low').click()`);
      await wait(2500);
      record.checks.push({name:'graphics changes preserve the unsent conversation draft',passed:await contents.executeJavaScript(`document.querySelector('input[aria-label="Message MYRAA"]').value==='unsent graphics retention check'`)});
      fs.writeFileSync(path.join(__dirname,'../artifacts/windows-settings-ui.png'),(await contents.capturePage()).toPNG());
      await contents.executeJavaScript(`(()=>{const d=document.querySelector('[role="dialog"][aria-label="Settings"]');d.querySelector('button').focus()})()`);
      contents.sendInputEvent({type:'keyDown',keyCode:'Tab',modifiers:['shift']}); contents.sendInputEvent({type:'keyUp',keyCode:'Tab',modifiers:['shift']});
      await wait(100);
      record.checks.push({name:'settings traps reverse keyboard navigation',passed:await contents.executeJavaScript(`(()=>{const d=document.querySelector('[role="dialog"][aria-label="Settings"]');const items=[...d.querySelectorAll('button:not([disabled]),a[href],input:not([disabled]),select:not([disabled]),textarea:not([disabled]),[tabindex="0"]')].filter(e=>e.getClientRects().length>0);return document.activeElement===items.at(-1)})()`)});
      contents.sendInputEvent({type:'keyDown',keyCode:'Escape'}); contents.sendInputEvent({type:'keyUp',keyCode:'Escape'});
      let focusReturn;
      for(let i=0;i<20;i++) {
        await wait(100);
        focusReturn=await contents.executeJavaScript(`({closed:!document.querySelector('[role="dialog"][aria-label="Settings"]'),activeLabel:document.activeElement.getAttribute('aria-label')})`);
        if(focusReturn.closed)break;
      }
      record.checks.push({name:'settings restores focus after Escape',passed:focusReturn.closed&&focusReturn.activeLabel==='Settings',detail:focusReturn});
      await contents.executeJavaScript(`(()=>{const input=document.querySelector('input[aria-label="Message MYRAA"]');Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value').set.call(input,'');input.dispatchEvent(new Event('input',{bubbles:true}))})()`);
      const reloaded = new Promise((resolve,reject)=>{const timeout=setTimeout(()=>reject(new Error('Renderer did not recover')),25000);contents.once('did-finish-load',()=>{clearTimeout(timeout);resolve()})});
      contents.forcefullyCrashRenderer(); await reloaded; await wait(5000);
      record.checks.push({name:'offline startup choice survives renderer recovery',passed:await contents.executeJavaScript(`Boolean(document.querySelector('input[aria-label="Message MYRAA"]'))`)});
      const restored=await contents.executeJavaScript(`fetch('/api/chat/history').then(r=>r.json())`);
      record.checks.push({name:'renderer crash recovered and text history survived',passed:JSON.stringify(restored)===JSON.stringify(preserved)});
      const lost = await contents.executeJavaScript(`(()=>{const canvas=document.querySelector('canvas');const gl=canvas?.getContext('webgl2');const extension=gl?.getExtension('WEBGL_lose_context');if(!extension)return false;window.__lostCanvas=canvas;window.addEventListener('myraa:character-diagnostics',e=>window.__gpuDiag=e.detail);extension.loseContext();return true})()`);
      if (lost) {
        let recovered = false;
        for (let i=0;i<40;i++) {
          await wait(500);
          recovered=await contents.executeJavaScript(`(()=>{window.dispatchEvent(new CustomEvent('myraa:request-character-diagnostics'));return document.querySelector('canvas')!==window.__lostCanvas&&window.__gpuDiag?.loaded===true&&window.__gpuDiag?.running===true})()`);
          if(recovered)break;
        }
        record.checks.push({name:'GPU context loss rebuilds a running avatar on a fresh canvas',passed:recovered});
      } else record.checks.push({name:'GPU context recovery',status:'not-run',reason:'No active WebGL context was available.'});
      if (process.env.MYRAA_SMOKE_LIVE === '1' && record.api['/api/config'].body.hasApiKey) {
        try {
          const reply = await contents.executeJavaScript(`fetch('/api/chat',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({text:'Reply exactly MYRAA_V2_TEXT_OK. Do not call any tools.'})}).then(async r=>({status:r.status,body:await r.json()}))`);
          record.liveTextTest={status:reply.status,model:reply.body.modelId,passed:reply.status===200 && reply.body.text?.includes('MYRAA_V2_TEXT_OK'),error:reply.body.error||null};
        } catch(error) {record.liveTextTest={passed:false,error:String(error)}}
      } else record.liveTextTest={status:'not-run',reason:'Live checks are waiting for the credential update.'};
      // No screen/microphone access or external PC actions are requested.
      await wait(3000); // Let the recovered canvas finish its one-second fade.
      record.avatarPresentation=await contents.executeJavaScript(`(()=>{const c=document.querySelector('canvas');return c?{opacity:getComputedStyle(c).opacity,width:c.width,height:c.height,diagnostics:window.__gpuDiag}:null})()`);
      fs.writeFileSync(path.join(__dirname,'../artifacts/windows-app.png'),(await window.webContents.capturePage()).toPNG());
      record.metrics=app.getAppMetrics();
      record.checks.push({ name:'main renderer remains responsive', passed:await contents.executeJavaScript('1+1')===2 });
    } catch(error) { record.errors.push(error.stack || String(error)); }
    finally { fs.writeFileSync(output,JSON.stringify(record,null,2)); app.quit(); }
  });
});
setTimeout(()=>{if(!done){record.errors.push('Main window did not load within 90 seconds.');fs.writeFileSync(output,JSON.stringify(record,null,2));app.quit()}},90000).unref();
require('../electron/main.cjs');
