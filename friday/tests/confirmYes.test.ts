import test from 'node:test';
import assert from 'node:assert/strict';
import { isYes, isNo, ConfirmMemory } from '../src/mobile/confirmYes.ts';

test('short yes answers are yes, anything new is not', () => {
  for (const t of ['Hn kro', 'Hn kro msg', 'haan', 'ji bhej do', 'ok send kar do', 'Haan ji', 'theek hai']) assert.equal(isYes(t), true, t);
  for (const t of ['Ni yr phala jo bt hui thi hamari wo kro', 'Sm anas ko reply jo kr rahi thi', 'haan lekin 5 baje likho', '']) assert.equal(isYes(t), false, t);
  assert.equal(isNo('Ni yr phala jo bt hui thi'), true);
  assert.equal(isNo('Hn kro'), false);
});

test('the phone carries out a yes to the last send question once', () => {
  const m = new ConfirmMemory();
  m.saw({ action: 'reply_message', recipient: 'S.M.Anas', message: 'Maine nahi banaya' }, { status: 'needs_confirmation', confirm_token: 't1' }, 0);
  assert.equal(m.answer('kya haal hai', 1000), null);
  const p = m.answer('Hn kro', 2000);
  assert.deepEqual(p, { action: 'reply_message', args: { action: 'reply_message', recipient: 'S.M.Anas', message: 'Maine nahi banaya' }, token: 't1', at: 0 });
  assert.equal(m.answer('Hn kro', 3000), null, 'only once');
  m.ran('t1', { status: 'sent' });
  assert.deepEqual(m.already({ action: 'reply_message', confirm_token: 't1' }), { status: 'sent' });
});

test('a no, a timeout or a non-send action forgets nothing it should not run', () => {
  const m = new ConfirmMemory();
  m.saw({ action: 'whatsapp_message', recipient: 'Ali', message: 'x' }, { status: 'needs_confirmation', confirm_token: 't2' }, 0);
  assert.equal(m.answer('nahi', 1000), null);
  assert.equal(m.answer('haan', 2000), null);
  m.saw({ action: 'whatsapp_message', recipient: 'Ali', message: 'x' }, { status: 'needs_confirmation', confirm_token: 't3' }, 0);
  assert.equal(m.answer('haan', 200_000), null, 'expired');
  m.saw({ action: 'torch', on: true }, { status: 'needs_confirmation', confirm_token: 't4' }, 0);
  assert.equal(m.answer('haan', 1000), null, 'torch is not a send');
});
