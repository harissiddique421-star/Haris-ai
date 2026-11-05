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
