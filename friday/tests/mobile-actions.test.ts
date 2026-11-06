import test from 'node:test';
import assert from 'node:assert/strict';
import { parseMobileAction } from '../src/mobile/actions';
test('opens named apps and WP aliases without a cloud call',()=>{
  for(const [text,app] of [['Open Chrome!','Chrome'],['myraa, open WhatsApp','WhatsApp'],['wp kholo','wp'],['please launch Google Maps','Google Maps']]) assert.deepEqual(parseMobileAction(text),{action:'open_app',app});
});
test('search intent preserves the requested query',()=>{
  assert.deepEqual(parseMobileAction('Open YouTube and search for Hindi songs'),{action:'search_youtube',query:'Hindi songs'});
  assert.deepEqual(parseMobileAction('search for why is the sky blue? on youtube'),{action:'search_youtube',query:'why is the sky blue?'});
  assert.deepEqual(parseMobileAction('Chrome search for cats & dogs?'),{action:'search_web',query:'cats & dogs?'});
});
test('WhatsApp recipient and message remain separate and exact',()=>{
  assert.deepEqual(parseMobileAction('Message Rahul Sharma on WhatsApp: Hello!\nI am late.'),{action:'whatsapp_message',recipient:'Rahul Sharma',message:'Hello!\nI am late.'});
  assert.deepEqual(parseMobileAction('WP msg to +919999000001: Meet at 5?'),{action:'whatsapp_message',recipient:'+919999000001',message:'Meet at 5?'});
});
test('negation, explanation, arbitrary URLs and compound requests are not executed locally',()=>{
  for(const text of ['do not open chrome','how do I open WhatsApp?','open javascript:alert(1)','open chrome and delete files','message Rahul on WhatsApp:','I like youtube search videos']) assert.equal(parseMobileAction(text),null,text);
});
test('calls, alarms, reminders and music parse locally',()=>{
  assert.deepEqual(parseMobileAction('Friday, call Rahul Sharma'),{action:'call',recipient:'Rahul Sharma'});
  assert.deepEqual(parseMobileAction('Ammi ko call karo'),{action:'call',recipient:'Ammi'});
  assert.deepEqual(parseMobileAction('set an alarm for 6:30 am'),{action:'set_alarm',hour:6,minute:30});
  assert.deepEqual(parseMobileAction('alarm at 7 pm'),{action:'set_alarm',hour:19,minute:0});
  assert.deepEqual(parseMobileAction('remind me in 10 minutes to drink water'),{action:'set_reminder',in_minutes:10,text:'drink water'});
  assert.deepEqual(parseMobileAction('play Arijit Singh songs'),{action:'play_music',query:'Arijit Singh'});
  assert.deepEqual(parseMobileAction('Kesariya gaana chalao'),{action:'play_music',query:'Kesariya'});
  for(const text of ['call Rahul and delete contacts','alarm at 25:00','do not call mom']) assert.equal(parseMobileAction(text),null,text);
});
test('phone controls parse locally',()=>{
  assert.deepEqual(parseMobileAction('torch on'),{action:'torch',on:true});
  assert.deepEqual(parseMobileAction('turn off the flashlight'),{action:'torch',on:false});
  assert.deepEqual(parseMobileAction('volume up'),{action:'volume',direction:'up'});
  assert.deepEqual(parseMobileAction('set volume to 40%'),{action:'volume',percent:40});
  assert.deepEqual(parseMobileAction('put the phone on silent'),{action:'ringer_mode',mode:'silent'});
  assert.deepEqual(parseMobileAction('brightness 70'),{action:'brightness',percent:70});
  assert.deepEqual(parseMobileAction('battery kitni hai?'),{action:'battery'});
  assert.deepEqual(parseMobileAction('read my notifications'),{action:'read_notifications'});
  assert.deepEqual(parseMobileAction('navigate to Lahore airport'),{action:'navigate',place:'Lahore airport'});
  assert.deepEqual(parseMobileAction('SMS Ammi: ghar aa raha hun'),{action:'send_sms',recipient:'Ammi',message:'ghar aa raha hun'});
  for(const text of ['volume 400','brightness 250']) assert.equal(parseMobileAction(text),null,text);
});
test('phone control: settings pages, screenshot and lock parse locally; taps never do',()=>{
  assert.deepEqual(parseMobileAction('open wifi settings'),{action:'open_settings',page:'wifi'});
  assert.deepEqual(parseMobileAction('Wi-Fi settings'),{action:'open_settings',page:'wifi'});
  assert.deepEqual(parseMobileAction('bluetooth kholo'),{action:'open_settings',page:'bluetooth'});
  assert.deepEqual(parseMobileAction('airplane mode settings'),{action:'open_settings',page:'airplane_mode'});
  assert.deepEqual(parseMobileAction('take a screenshot'),{action:'phone_control',control:'screenshot'});
  assert.deepEqual(parseMobileAction('lock the phone'),{action:'phone_control',control:'lock_screen'});
  for(const text of ['tap send','click pay now','type my password']) assert.equal(parseMobileAction(text),null,text);
});
test('WhatsApp messages and voice/video calls parse from Hinglish',()=>{
  assert.deepEqual(parseMobileAction('WhatsApp par Ali ko message karo: kal milte hain'),{action:'whatsapp_message',recipient:'Ali',message:'kal milte hain'});
  assert.deepEqual(parseMobileAction('Ali ko WhatsApp message bhejo: on my way'),{action:'whatsapp_message',recipient:'Ali',message:'on my way'});
  assert.deepEqual(parseMobileAction('Ali ko WhatsApp call karo'),{action:'whatsapp_call',recipient:'Ali'});
  assert.deepEqual(parseMobileAction('Ali ko WhatsApp video call karo'),{action:'whatsapp_call',recipient:'Ali',video:true});
  assert.deepEqual(parseMobileAction('WhatsApp par Ali Khan ko video call karo'),{action:'whatsapp_call',recipient:'Ali Khan',video:true});
  assert.deepEqual(parseMobileAction('whatsapp call Ammi'),{action:'whatsapp_call',recipient:'Ammi'});
  assert.deepEqual(parseMobileAction('Ali ko call karo'),{action:'call',recipient:'Ali'});
  assert.deepEqual(parseMobileAction('go back'),{action:'phone_control',control:'back'});
  assert.deepEqual(parseMobileAction('home jao'),{action:'phone_control',control:'home'});
  assert.deepEqual(parseMobileAction('open contact Ali'),{action:'open_contact',recipient:'Ali'});
  for(const text of ['Ali ko WhatsApp call karo and delete chats','whatsapp video call']) assert.equal(parseMobileAction(text),null,text);
});
test('memory and persona commands parse locally',()=>{
  assert.deepEqual(parseMobileAction('remember that my sister is Sara'),{action:'remember',fact:'my sister is Sara'});
  assert.deepEqual(parseMobileAction('yaad rakho ki mujhe chai pasand hai'),{action:'remember',fact:'mujhe chai pasand hai'});
  assert.deepEqual(parseMobileAction('what do you remember?'),{action:'list_memories'});
  assert.deepEqual(parseMobileAction('forget Sara'),{action:'forget',query:'Sara'});
  assert.deepEqual(parseMobileAction('switch to venom mode'),{action:'set_persona',persona:'venom'});
  assert.deepEqual(parseMobileAction('professional mode on'),{action:'set_persona',persona:'professional'});
  assert.deepEqual(parseMobileAction('use girlfriend voice'),{action:'set_persona',persona:'gf'});
});
test('driving mode and call announcement commands parse locally',()=>{
  assert.deepEqual(parseMobileAction('driving mode on'),{action:'set_driving_mode',on:true});
  assert.deepEqual(parseMobileAction('Driving mode band karo'),{action:'set_driving_mode',on:false});
  assert.deepEqual(parseMobileAction('turn off driving mode'),{action:'set_driving_mode',on:false});
  assert.deepEqual(parseMobileAction("I'm driving"),{action:'set_driving_mode',on:true});
  assert.deepEqual(parseMobileAction('call announcement off'),{action:'set_call_announce',on:false});
  assert.deepEqual(parseMobileAction('caller name on karo'),{action:'set_call_announce',on:true});
  assert.equal(parseMobileAction('driving mode'),null);
});
test('wake word and email commands parse locally',()=>{
  assert.deepEqual(parseMobileAction('wake word on'),{action:'set_wake_word',on:true});
  assert.deepEqual(parseMobileAction('turn off the wake word'),{action:'set_wake_word',on:false});
  assert.deepEqual(parseMobileAction('email me: buy milk'),{action:'email',recipient:'me',message:'buy milk'});
  assert.deepEqual(parseMobileAction('mujhe email karo: kal meeting hai'),{action:'email',recipient:'mujhe',message:'kal meeting hai'});
  assert.deepEqual(parseMobileAction('Ali ko email bhejo: report ready hai'),{action:'email',recipient:'Ali',message:'report ready hai'});
  assert.equal(parseMobileAction('email Ali and delete everything: hi'),null);
});
test('routines, sleep and timers parse locally',()=>{
  assert.deepEqual(parseMobileAction('run my morning routine'),{action:'run_routine',name:'morning'});
  assert.deepEqual(parseMobileAction('morning routine chalao'),{action:'run_routine',name:'morning'});
  assert.deepEqual(parseMobileAction('save routine morning: battery; torch on; read my notifications'),{action:'save_routine',name:'morning',steps:['battery','torch on','read my notifications']});
  assert.deepEqual(parseMobileAction('delete morning routine'),{action:'delete_routine',name:'morning'});
  assert.deepEqual(parseMobileAction('my routines'),{action:'list_routines'});
  assert.deepEqual(parseMobileAction('Bye FRIDAY'),{action:'sleep'});
  assert.deepEqual(parseMobileAction('so jao'),{action:'sleep'});
  assert.deepEqual(parseMobileAction('good night friday'),{action:'sleep'});
  assert.deepEqual(parseMobileAction('set a timer for 5 minutes'),{action:'set_timer',in_minutes:5});
  assert.deepEqual(parseMobileAction('30 sec ka timer lagao'),{action:'set_timer',seconds:30});
  assert.equal(parseMobileAction('run my morning and delete everything routine'),null);
});
test('YouTube playback, links and media controls parse locally',()=>{
  assert.deepEqual(parseMobileAction('play Tum Hi Ho on YouTube'),{action:'play_youtube',query:'Tum Hi Ho'});
  assert.deepEqual(parseMobileAction('play Tum Hi Ho song on youtube'),{action:'play_youtube',query:'Tum Hi Ho',kind:'music'});
  assert.deepEqual(parseMobileAction('open YouTube and play lofi beats'),{action:'play_youtube',query:'lofi beats'});
  assert.deepEqual(parseMobileAction('play a youtube song Kesariya'),{action:'play_youtube',query:'Kesariya',kind:'music'});
  assert.deepEqual(parseMobileAction('YouTube par Pasoori chalao'),{action:'play_youtube',query:'Pasoori'});
  assert.deepEqual(parseMobileAction('https://youtu.be/dQw4w9WgXcQ'),{action:'play_youtube',url:'https://youtu.be/dQw4w9WgXcQ'});
  assert.deepEqual(parseMobileAction('play https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=10'),{action:'play_youtube',url:'https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=10'});
  assert.deepEqual(parseMobileAction('play Arijit Singh'),{action:'play_music',query:'Arijit Singh'});
  assert.deepEqual(parseMobileAction('pause'),{action:'media',command:'pause'});
  assert.deepEqual(parseMobileAction('Resume'),{action:'media',command:'resume'});
  assert.deepEqual(parseMobileAction('stop'),{action:'media',command:'stop'});
  assert.deepEqual(parseMobileAction('next song'),{action:'media',command:'next'});
  assert.deepEqual(parseMobileAction('previous song'),{action:'media',command:'previous'});
  assert.deepEqual(parseMobileAction('agla gaana'),{action:'media',command:'next'});
  assert.deepEqual(parseMobileAction('volume up'),{action:'volume',direction:'up'});
  assert.deepEqual(parseMobileAction('volume down'),{action:'volume',direction:'down'});
  assert.deepEqual(parseMobileAction('mute'),{action:'volume',direction:'mute'});
  assert.deepEqual(parseMobileAction('unmute'),{action:'volume',direction:'unmute'});
  assert.equal(parseMobileAction('play songs on youtube and delete my chats'),null);
});

test('briefing, reminders, meeting mode, modes and resume parse locally', () => {
  assert.deepEqual(parseMobileAction('good morning'),{action:'briefing'});
  assert.deepEqual(parseMobileAction('subah ki briefing'),{action:'briefing'});
  assert.deepEqual(parseMobileAction('kis ne call kiya tha?'),{action:'missed_calls'});
  assert.deepEqual(parseMobileAction('mere reminders'),{action:'list_reminders'});
  assert.deepEqual(parseMobileAction('dawai le li'),{action:'reminder_done'});
  assert.deepEqual(parseMobileAction('meeting mode on'),{action:'meeting_mode',on:true});
  assert.deepEqual(parseMobileAction('meeting mode band karo'),{action:'meeting_mode',on:false});
  assert.deepEqual(parseMobileAction('english practice'),{action:'set_mode',mode:'english_practice'});
  assert.deepEqual(parseMobileAction('translator mode for Arabic'),{action:'set_mode',mode:'translator',language:'Arabic'});
  assert.deepEqual(parseMobileAction('normal mode'),{action:'set_mode',mode:'normal'});
  assert.deepEqual(parseMobileAction('wahin se chalao'),{action:'resume_video'});
  assert.equal(parseMobileAction('done'),null);
});
