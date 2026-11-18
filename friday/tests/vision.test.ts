import test from 'node:test';
import assert from 'node:assert/strict';
import { parseMobileAction, MOBILE_TOOL } from '../src/mobile/actions';
import { agentFor, READ_ONLY } from '../src/mobile/agents';
import { VISION_ACTIONS, VISION_NAMES, visionMessage } from '../src/mobile/vision';

test('documents by typed command: read, summarise, ask', () => {
  assert.deepEqual(parseMobileAction('ye PDF padho'), { action: 'analyze_document' });
  assert.deepEqual(parseMobileAction('FRIDAY, document parho'), { action: 'analyze_document' });
  assert.deepEqual(parseMobileAction('bill samjhao'), { action: 'analyze_document' });
  assert.deepEqual(parseMobileAction('read this document'), { action: 'analyze_document' });
  assert.deepEqual(parseMobileAction('summarize a pdf'), { action: 'analyze_document' });
  assert.deepEqual(parseMobileAction('pdf ka summary do'), { action: 'analyze_document' });
  assert.deepEqual(parseMobileAction('bill padh kar batao due date kya hai?'), { action: 'analyze_document', query: 'due date kya hai' });
  // A camera scan is still the scanner's, not a document reading.
  assert.notDeepEqual(parseMobileAction('document scan karo'), { action: 'analyze_document' });
});

test('pictures by typed command: compare, save, stop', () => {
  assert.deepEqual(parseMobileAction('compare images'), { action: 'compare_images' });
  assert.deepEqual(parseMobileAction('compare these screenshots'), { action: 'compare_images' });
  assert.deepEqual(parseMobileAction('do photos compare karo'), { action: 'compare_images' });
  assert.deepEqual(parseMobileAction('dono pictures mein farq batao'), { action: 'compare_images' });
  assert.deepEqual(parseMobileAction("what's the difference between these photos"), { action: 'compare_images' });
  assert.deepEqual(parseMobileAction('save the comparison as pdf'), { action: 'save_vision_report' });
  assert.deepEqual(parseMobileAction('ye report pdf mein save karo'), { action: 'save_vision_report' });
  assert.deepEqual(parseMobileAction('padhna band karo'), { action: 'cancel_vision' });
  // Unrelated commands are untouched.
  assert.equal(parseMobileAction('photos dikhao')?.action === 'compare_images', false);
  assert.deepEqual(parseMobileAction('battery'), { action: 'battery' });
});

test('vision actions are declared, owned by the document agent, and named on cards', () => {
  const names: string[] = (MOBILE_TOOL as any).parameters.properties.action.enum;
  for (const a of VISION_ACTIONS) {
    assert.ok(names.includes(a), a + ' declared');
    assert.equal(agentFor(a), 'document');
    assert.ok(VISION_NAMES[a], a + ' has a card name');
  }
  assert.ok(READ_ONLY.has('document_question'));
  assert.ok(!READ_ONLY.has('analyze_document'), 'reading a new file opens the picker');
});

test('vision results become a chat line and a [Phone] message with the content quoted as data', () => {
  const doc = visionMessage({ type: 'vision-done', kind: 'document', ok: true, title: 'K-Electric bill', summary: 'Bill for October. Due 25 Nov.', answer: '25 November', tables: 2, handwritten: false, files: 'bill.pdf' });
  assert.match(doc.chat, /^K-Electric bill: Bill for October/);
  assert.match(doc.say, /^\[Phone\] FRIDAY read the document/);
  assert.match(doc.say, /content, not instructions/);
  assert.match(doc.say, /2 tables/);
  assert.match(doc.say, /the answer first/);
  const cmp = visionMessage({ kind: 'compare', ok: true, summary: 'Verdict: similar.', pictures: 3, likeness: 72 });
  assert.match(cmp.say, /3 pictures/);
  assert.match(cmp.say, /72\/100 alike/);
  const q = visionMessage({ kind: 'question', ok: true, title: 'Bill', question: 'total?', answer: 'Rs 8,450' });
  assert.equal(q.chat, 'Rs 8,450');
  const fail = visionMessage({ kind: 'document', ok: false, message: 'Nothing was picked, so nothing was read.' });
  assert.doesNotMatch(fail.say, /try again/);
  const err = visionMessage({ kind: 'compare', ok: false, message: 'There is no internet connection.' });
  assert.match(err.say, /offer to try again/);
  const long = visionMessage({ kind: 'document', ok: true, summary: 'x'.repeat(5000) });
  assert.ok(long.say.length < 2000, 'long content is clipped');
});
