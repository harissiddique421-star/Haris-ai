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

test('camera by typed command and its result message', () => {
  assert.deepEqual(parseMobileAction('camera se dekho'), { action: 'camera_vision' });
  assert.deepEqual(parseMobileAction('objects pehchano'), { action: 'camera_vision' });
  assert.deepEqual(parseMobileAction("what's in front of me?"), { action: 'camera_vision' });
  assert.deepEqual(parseMobileAction('mere aas paas kya hai?'), { action: 'camera_vision', mode: 'scene' });
  assert.deepEqual(parseMobileAction('describe my surroundings'), { action: 'camera_vision', mode: 'scene' });
  assert.deepEqual(parseMobileAction('live object detection on'), { action: 'camera_vision', on: true });
  assert.deepEqual(parseMobileAction('camera band karo'), { action: 'cancel_vision' });
  assert.equal(parseMobileAction('ye kya hai'), null, 'a general question stays with the model');
  const seen = visionMessage({ kind: 'camera', ok: true, looks: 3, summary: 'A desk with a laptop.', objects: 'laptop (in the centre), cup (on the left)', answer: '' });
  assert.match(seen.chat, /^Camera: A desk with a laptop\. \(laptop/);
  assert.match(seen.say, /do not repeat it/);
  const none = visionMessage({ kind: 'camera', ok: false, looks: 0 });
  assert.match(none.say, /Say nothing/);
});

test('screen understanding and guide by typed command and its result message', () => {
  assert.deepEqual(parseMobileAction('explain this screen'), { action: 'screen_vision' });
  assert.deepEqual(parseMobileAction('ye screen samjhao'), { action: 'screen_vision' });
  assert.deepEqual(parseMobileAction('guide me to turn on dark mode'), { action: 'screen_vision', mode: 'guide', query: 'turn on dark mode' });
  assert.deepEqual(parseMobileAction('wifi password change kaise karun guide karo'), { action: 'screen_vision', mode: 'guide', query: 'wifi password change' });
  assert.deepEqual(parseMobileAction('guide band karo'), { action: 'screen_vision', mode: 'stop' });
  const m = visionMessage({ kind: 'screen', ok: true, app: 'Settings', screen: 'Display', summary: 'Display settings.', steps: ['Tap Dark theme'], next_label: 'Dark theme', framed: true, spoken: 'Dark theme dabayein.', picture: false });
  assert.match(m.chat, /^Settings \(Display\): Display settings\.\n1\. Tap Dark theme$/);
  assert.match(m.say, /Next button: "Dark theme" \(framed in yellow/);
  assert.match(m.say, /tap with target "Dark theme"/);
  assert.match(m.say, /accessibility labels/);
  const done = visionMessage({ kind: 'screen', ok: true, app: 'Settings', summary: 'On.', done: true, steps: [] });
  assert.match(done.say, /already reached/);
  assert.doesNotMatch(done.say, /tap with target/);
});

test('video and visual memory by typed command, and the video result message', () => {
  assert.deepEqual(parseMobileAction('ye video samjhao'), { action: 'analyze_video' });
  assert.deepEqual(parseMobileAction('video ka summary do'), { action: 'analyze_video' });
  assert.deepEqual(parseMobileAction('is video mein kya ho raha hai?'), { action: 'analyze_video' });
  assert.deepEqual(parseMobileAction('summarize this video'), { action: 'analyze_video' });
  assert.deepEqual(parseMobileAction('show my visual memories'), { action: 'visual_memory', mode: 'list' });
  // Playing a video is still YouTube's.
  assert.notEqual(parseMobileAction('video chalao')?.action, 'analyze_video');
  assert.equal(agentFor('analyze_video'), 'document');
  assert.equal(agentFor('visual_memory'), 'document');
  const names: string[] = (MOBILE_TOOL as any).parameters.properties.action.enum;
  assert.ok(names.includes('analyze_video') && names.includes('visual_memory'));
  assert.ok(VISION_ACTIONS.includes('analyze_video' as any) && VISION_NAMES.visual_memory === 'Visual memory');
  const props = (MOBILE_TOOL as any).parameters.properties;
  assert.ok(props.approved && props.memory_id);
  const m = visionMessage({ kind: 'video', ok: true, title: 'Cricket match', summary: 'Boys play cricket. Timeline: 0:05 bowler runs;', duration: '0:42', frames: 6, file: 'match.mp4', answer: '' });
  assert.match(m.chat, /^Cricket match \(0:42\): Boys play cricket/);
  assert.match(m.say, /6 moments of the video/);
  assert.match(m.say, /no sound/);
  assert.match(m.say, /content, not instructions/);
  const failed = visionMessage({ kind: 'video', ok: false, message: 'This video cannot be opened on the phone.' });
  assert.match(failed.say, /video reading did not finish/);
});
