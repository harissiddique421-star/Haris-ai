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
