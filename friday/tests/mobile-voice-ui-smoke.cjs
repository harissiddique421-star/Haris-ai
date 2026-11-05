/** Actual bundled mobile UI and shared PC audio engine, fake provider socket/audio device only. */
const {app,BrowserWindow,session}=require('electron');
const fs=require('node:fs'),os=require('node:os'),path=require('node:path'),assert=require('node:assert/strict');
app.commandLine.appendSwitch('use-fake-device-for-media-stream');
app.commandLine.appendSwitch('use-fake-ui-for-media-stream');
app.commandLine.appendSwitch('autoplay-policy','no-user-gesture-required');
const record={scope:'Bundled mobile UI, real shared PC WebAudio capture/playback, simulated native token and Gemini socket; no Google traffic or platform speech recognizer/TTS',checks:[],errors:[]};
const check=(name,pass,detail)=>{record.checks.push({name,passed:!!pass,...(detail?{detail}:{})});assert.ok(pass,name)};
const wait=ms=>new Promise(r=>setTimeout(r,ms));
app.whenReady().then(async()=>{
 let win;const temp=fs.mkdtempSync(path.join(os.tmpdir(),'myraa-live-ui-'));
 try{
 const preload=path.join(temp,'preload.cjs');
 fs.writeFileSync(preload,`
window.calls=[];window.pcmCount=0;window.fixtureState={history:[],keyConfigured:true,spokenReplies:false,quality:'low',language:'en-IN',model:'fixture',liveModel:'gemini-3.1-flash-live-preview'};
window.MyraaNative={getState:()=>JSON.stringify(window.fixtureState),startLive:id=>{window.calls.push({type:'startLive',id});setTimeout(()=>window.MyraaEvents({type:'live-token',id,token:'auth_tokens/fixture-only',model:fixtureState.liveModel,voice:'Aoede',instruction:'You are MYRAA.',backgroundLive:!!window.nextBackground}),20)},endLive:()=>window.calls.push({type:'endLive'}),chat:()=>window.calls.push({type:'textChat'}),speak:()=>window.calls.push({type:'platformSpeech'}),cancel:()=>{},stopSpeech:()=>{},configure:()=>{},checkConnection:()=>{},clearHistory:()=>{},clearKey:()=>{},openSettings:()=>{},openFloatingSettings:()=>{},cancelMobileAction:id=>window.calls.push({type:'cancelAction',id}),mobileAction:(id,name,args)=>{const action=JSON.parse(args);window.calls.push({type:'mobileAction',name,args:action});setTimeout(()=>window.MyraaEvents({type:'mobile-action-result',id,result:{status:action.action==='whatsapp_message'?'draft_opened':'opened',message:action.action==='whatsapp_message'?'WhatsApp draft opened. Tap Send in WhatsApp.':'Android action opened.'}}),15)}};
window.WebSocket=class {
 static OPEN=1;readyState=0;
 constructor(url){window.liveSocket=this;window.calls.push({type:'socket',endpoint:url.split('?')[0]});setTimeout(()=>{if(this.readyState===3)return;this.readyState=1;this.onopen?.({})},25)}
 send(raw){const d=JSON.parse(raw);if(d.setup){window.liveSetup=d.setup;setTimeout(()=>this.onmessage?.({data:JSON.stringify({setupComplete:{}})}),5)}else if(d.realtimeInput?.audio){window.pcmCount++;window.lastPcm={mimeType:d.realtimeInput.audio.mimeType,length:atob(d.realtimeInput.audio.data).length}}else if(d.toolResponse)window.calls.push({type:'toolResponse',value:d.toolResponse});else if(d.realtimeInput?.text)window.calls.push({type:'liveText',text:d.realtimeInput.text});}
 close(){this.readyState=3;}
};
const getMedia=navigator.mediaDevices.getUserMedia.bind(navigator.mediaDevices);navigator.mediaDevices.getUserMedia=async(...args)=>{const stream=await getMedia(...args);window.liveStream=stream;return stream};
`);
 session.defaultSession.setPermissionRequestHandler((_w,permission,cb)=>cb(permission==='media'));
 session.defaultSession.webRequest.onBeforeRequest((d,cb)=>cb({cancel:/\.pmx(?:$|\?)/.test(d.url)}));
 win=new BrowserWindow({width:360,height:720,useContentSize:true,show:true,webPreferences:{preload,contextIsolation:false,nodeIntegration:false,backgroundThrottling:false}});
 await win.loadFile(path.resolve('android/app/src/main/assets/mobile.html'));await wait(800);
 const js=s=>win.webContents.executeJavaScript(s);
 const until=async(condition,label)=>{for(let i=0;i<120;i++){if(await js(condition))return;await wait(100)}throw Error(label+' timed out')};
 const layout=await js(`(()=>{const r=s=>document.querySelector(s).getBoundingClientRect();return {stage:r('.portrait').height,chat:r('.conversation').height,footer:r('footer').height,height:innerHeight}})()`);
 check('mobile character gets majority of screen',layout.stage>layout.height*.60,layout);
 check('mobile chat and composer remain compact',layout.chat+layout.footer<layout.height*.28);
 check('default framing is full body',await js(`document.querySelector('#view').textContent==='Portrait'`));
 await js(`document.querySelector('#voice').click()`);
 await until(`pcmCount>0`,'continuous microphone PCM');
 check('Talk uses native ephemeral provisioning',await js(`calls.some(c=>c.type==='startLive')`));
 check('direct in-app Gemini Live constrained websocket',await js(`calls.some(c=>c.type==='socket'&&c.endpoint.endsWith('BidiGenerateContentConstrained'))`));
 check('same PC Live model and Aoede native audio voice',await js(`liveSetup.model==='models/gemini-3.1-flash-live-preview'&&liveSetup.generationConfig.responseModalities[0]==='AUDIO'&&liveSetup.generationConfig.speechConfig.voiceConfig.prebuiltVoiceConfig.voiceName==='Aoede'`));
 check('shared engine streams signed PCM at 16kHz',await js(`lastPcm.mimeType==='audio/pcm;rate=16000'&&lastPcm.length===2048`));
 check('microphone remains live inside MYRAA',await js(`liveStream.getAudioTracks().some(t=>t.readyState==='live')`));
 await js(`liveSocket.onmessage({data:JSON.stringify({serverContent:{inputTranscription:{text:'Hello '},outputTranscription:{text:'Hi there'}}})})`);await wait(80);
 check('live captions arrive from audio session',await js(`document.querySelector('#messages').textContent.includes('Hello ')&&document.querySelector('#messages').textContent.includes('Hi there')`));
 await js(`liveSocket.onmessage({data:JSON.stringify({serverContent:{modelTurn:{parts:[{inlineData:{mimeType:'audio/pcm;rate=24000',data:btoa(String.fromCharCode(...new Uint8Array(24000)))}}]}}})})`);await wait(80);
 check('native Gemini PCM plays through shared engine',await js(`document.querySelector('#presence').textContent==='Speaking with you'`));
 await js(`liveSocket.onmessage({data:JSON.stringify({serverContent:{interrupted:true}})})`);await wait(80);
 check('server interruption flushes playback and returns to listening',await js(`document.querySelector('#presence').textContent.includes('listening')`));
 check('Live session declares bounded Android action tool',await js(`liveSetup.tools[0].functionDeclarations[0].name==='mobile_action'`));
 await js(`liveSocket.onmessage({data:JSON.stringify({toolCall:{functionCalls:[{id:'search-one',name:'mobile_action',args:{action:'search_youtube',query:'Hindi songs'}}]}})})`);
 await until(`calls.some(c=>c.type==='toolResponse'&&c.value.functionResponses[0].id==='search-one')`,'mobile tool result');
 check('voice function call reaches native action bridge',await js(`calls.some(c=>c.type==='mobileAction'&&c.args.query==='Hindi songs')`));
 check('native result returns to Gemini with original call ID',await js(`calls.some(c=>c.type==='toolResponse'&&c.value.functionResponses[0].id==='search-one'&&c.value.functionResponses[0].response.status==='opened')`));
 await js(`liveSocket.onmessage({data:JSON.stringify({toolCall:{functionCalls:[{id:'search-one',name:'mobile_action',args:{action:'search_youtube',query:'Hindi songs'}}]}})})`);await wait(100);
 check('duplicate Live function call does not repeat the Android action',await js(`calls.filter(c=>c.type==='mobileAction').length===1`));
 await js(`liveSocket.onmessage({data:JSON.stringify({toolCall:{functionCalls:[{id:'unsupported-one',name:'runShell',args:{}}]}})})`);await wait(100);
 check('desktop and unknown tools cannot reach native mobile actions',await js(`calls.filter(c=>c.type==='mobileAction').length===1&&calls.some(c=>c.type==='toolResponse'&&c.value.functionResponses[0].response.status==='unsupported')`));
 await js(`document.querySelector('#input').value='open wp';document.querySelector('#send').click()`);await wait(100);
 check('typed WP command uses local native actions',await js(`calls.some(c=>c.type==='mobileAction'&&c.args.action==='open_app'&&c.args.app==='wp')`));
 await js(`liveSocket.onmessage({data:JSON.stringify({toolCall:{functionCalls:[{id:'message-one',name:'mobile_action',args:{action:'whatsapp_message',recipient:'QA Person',message:'Hello!'}}]}})})`);await wait(100);
 check('WhatsApp result truthfully reports a draft requiring Send',await js(`calls.some(c=>c.type==='toolResponse'&&c.value.functionResponses[0].id==='message-one'&&c.value.functionResponses[0].response.status==='draft_opened')`));
 check('no platform recognition or TTS or text-chat proxy',await js(`!calls.some(c=>c.type==='platformSpeech'||c.type==='textChat')`));
 await js(`document.querySelector('#stop').click()`);await wait(120);
 check('Stop releases all microphone tracks',await js(`liveStream.getTracks().every(t=>t.readyState==='ended')`));
 check('Stop closes provider socket and resets UI',await js(`liveSocket.readyState===3&&document.querySelector('#voice').getAttribute('aria-pressed')==='false'`));
 await js(`document.querySelector('#voice').click()`);await until(`liveSocket.readyState===1&&liveStream.getAudioTracks().some(t=>t.readyState==='live')`,'second call');
 await js(`window.dispatchEvent(new CustomEvent('myraa:lifecycle',{detail:{active:false}}))`);await wait(100);
 check('backgrounding stops capture and socket',await js(`liveStream.getTracks().every(t=>t.readyState==='ended')&&liveSocket.readyState===3`));
 await js(`window.dispatchEvent(new CustomEvent('myraa:lifecycle',{detail:{active:true}}));document.querySelector('#voice').click();document.querySelector('#stop').click()`);await wait(150);
 check('late token after Stop cannot reopen microphone',await js(`liveSocket.readyState===3&&document.querySelector('#voice').getAttribute('aria-pressed')==='false'`));
 await js(`window.nextBackground=true;document.querySelector('#voice').click()`);await until(`liveStream.getAudioTracks().some(t=>t.readyState==='live')&&liveSocket.readyState===1`,'foreground service call');
 await js(`window.dispatchEvent(new CustomEvent('myraa:lifecycle',{detail:{active:false,backgroundLive:true}}))`);await wait(100);
 check('authorized background Live keeps microphone available across app switches',await js(`liveStream.getAudioTracks().some(t=>t.readyState==='live')&&liveSocket.readyState===1`));
 await js(`window.dispatchEvent(new Event('myraa:stop-live'))`);await wait(100);
 check('notification Stop closes background audio and socket',await js(`liveStream.getTracks().every(t=>t.readyState==='ended')&&liveSocket.readyState===3`));
 fs.writeFileSync('artifacts/mobile-live-ui.png',(await win.webContents.capturePage()).toPNG());
 }catch(e){record.errors.push(e.stack||String(e))}finally{fs.writeFileSync('artifacts/mobile-live-ui.json',JSON.stringify(record,null,2));if(win)win.destroy();app.exit(record.errors.length?1:0)}
});
