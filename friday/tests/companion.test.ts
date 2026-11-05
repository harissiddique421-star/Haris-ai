import assert from 'node:assert/strict';
import test from 'node:test';
import fs from 'node:fs';
import vm from 'node:vm';
import { EventEmitter } from 'node:events';
import { createRequire } from 'node:module';
import { normalizeSettings, patchSettings } from '../settings/appSettings';
import { parseDesktopCharacterCommand } from '../shared/character/desktopCommands';

const require = createRequire(import.meta.url);
const source = fs.readFileSync(process.env.MYRAA_COMPANION_QA_SOURCE || new URL('../electron/companion.cjs', import.meta.url), 'utf8');

/** Shell contract fixture. Native transparency/focus require the separate Electron smoke. */
function fixture() {
  const timers = new Map<number, () => void>(); let nextTimer = 0;
  const ipcMain = new EventEmitter();
  class Window extends EventEmitter {
    options: any; visible = false; destroyed = false; ignoresMouse = true; focusCalls = 0;
    bounds = { x: 1300, y: 480, width: 300, height: 560 };
    messages: any[] = [];
    webContents = Object.assign(new EventEmitter(), {
      send: (channel: string, payload: any) => this.messages.push({ channel, payload }), reload: () => {},
    });
    constructor(options: any) { super(); this.options = options; this.bounds = { ...this.bounds, ...options }; }
    loadURL() {}
    isDestroyed() { return this.destroyed; }
    isVisible() { return this.visible; }
    showInactive() { this.visible = true; }
    focus() { this.focusCalls++; }
    hide() { this.visible = false; }
    setAlwaysOnTop() {}
    setIgnoreMouseEvents(value: boolean) { this.ignoresMouse = value; }
    getBounds() { return this.bounds; }
    getPosition() { return [this.bounds.x, this.bounds.y]; }
    getSize() { return [this.bounds.width, this.bounds.height]; }
    setPosition(x: number, y: number) { Object.assign(this.bounds, { x, y }); }
    setBounds(bounds: object) { Object.assign(this.bounds, bounds); }
    destroy() { this.destroyed = true; this.emit('closed'); }
  }
  const display = { id: 1, workArea: { x: 0, y: 0, width: 1920, height: 1040 }, bounds: { x: 0, y: 0, width: 1920, height: 1080 } };
  const second = { id: 2, scaleFactor: 1.5, workArea: { x: -1280, y: 0, width: 1280, height: 984 }, bounds: { x: -1280, y: 0, width: 1280, height: 1024 } };
  let displays = [display, second];
  const screen = Object.assign(new EventEmitter(), {
    getPrimaryDisplay: () => display, getDisplayNearestPoint: (point: { x: number }) => point.x < 0 && displays.length > 1 ? second : display,
    getCursorScreenPoint: () => ({ x: 1400, y: 700 }), getAllDisplays: () => displays,
  });
  const shell = { BrowserWindow: Window, ipcMain, Menu: {}, screen };
  const module = { exports: {} as any };
  vm.runInNewContext(source, {
    module, console, require: (id: string) => id === 'electron' ? shell : id === './win32.cjs' ? { available: () => false } : require(id),
    setInterval: (fn: () => void) => { timers.set(++nextTimer, fn); return nextTimer; },
    setTimeout: (fn: () => void) => { timers.set(++nextTimer, fn); return nextTimer; },
    clearInterval: (id: number) => timers.delete(id), clearTimeout: (id: number) => timers.delete(id),
  });
  const sent: any[] = [], saved: any[] = [];
  const manager = new module.exports.CompanionManager({ origin: 'http://127.0.0.1:5189', preload: '',
    dataDir: 'fixture-unused', ownPids: () => [], saveSettings: (patch: any) => saved.push(patch), sendToBackend: (message: any) => sent.push(message) });
  let settings = normalizeSettings({ companion: { enabled: true } });
  const apply = (patch: any) => { settings = patchSettings(settings, patch); manager.applySettings(settings); };
  apply({});
  return { manager, timers, ipcMain, sent, saved, apply, screen, removeSecond: () => { displays = [display]; screen.emit('display-removed'); }, window: manager.win as Window };
}

test('old click-through settings migrate, and both old and explicit mode updates remain usable', () => {
  const passive = normalizeSettings({ companion: { clickThrough: true } });
  assert.equal(passive.companion.mode, 'passive');
  const focus = patchSettings(passive, { companion: { mode: 'focus' } });
  assert.equal(focus.companion.mode, 'focus'); assert.equal(focus.companion.clickThrough, false);
  assert.equal(patchSettings(focus, { companion: { clickThrough: true } }).companion.mode, 'passive');
});

test('passive mode rejects stale hit-tests, drag and poke IPC', () => {
  const f = fixture(); f.apply({ companion: { mode: 'passive' } });
  f.manager.setInteractive(true); f.manager.beginDrag();
  f.ipcMain.emit('companion:poke', { sender: f.window.webContents }, 'poke');
  assert.equal(f.window.ignoresMouse, true); assert.equal(f.manager.drag, null); assert.equal(f.sent.length, 0);
  f.apply({ companion: { mode: 'interactive' } }); f.manager.setInteractive(true);
  assert.equal(f.window.ignoresMouse, false);
});

test('hidden and gaming stop shell timers; fullscreen exit preserves manual suspension', () => {
  const f = fixture(); assert.ok(f.timers.size > 0);
  f.apply({ companion: { mode: 'hidden' } });
  assert.equal(f.window.visible, false); assert.equal(f.timers.size, 0);
  f.manager.onForeground({ fullscreen: true }); f.manager.onForeground({ fullscreen: false });
  assert.equal(f.window.visible, false); assert.equal(f.timers.size, 0);
  f.apply({ companion: { mode: 'interactive' } });
  assert.equal(f.window.visible, true); assert.equal(f.window.focusCalls, 0); assert.equal(f.timers.size, 3);
  f.apply({ companion: { mode: 'gaming' } }); assert.equal(f.timers.size, 0);
  assert.equal(f.window.messages.at(-1).payload.suspended, true);
});

test('fullscreen policy changes apply immediately and focus suppresses autonomous decisions', () => {
  const f = fixture(); f.manager.onForeground({ fullscreen: true });
  assert.equal(f.window.visible, false);
  f.apply({ companion: { fullscreenBehavior: 'always', mode: 'focus' } });
  assert.equal(f.window.visible, true); f.manager.nextThought = 0;
  const count = f.window.messages.length; f.manager.think(); assert.equal(f.window.messages.length, count);
  f.manager.handleCommand('wave'); assert.equal(f.window.messages.at(-1).payload.name, 'wave');
  f.apply({ companion: { fullscreenBehavior: 'notifications_only' } }); assert.equal(f.window.visible, false);
});

test('switching modes releases an in-progress drag and teardown clears timers', () => {
  const f = fixture(); f.manager.beginDrag(); assert.ok(f.manager.drag);
  f.apply({ companion: { mode: 'passive' } });
  assert.equal(f.manager.drag, null); assert.equal(f.window.ignoresMouse, true);
  f.manager.destroy(); assert.equal(f.timers.size, 0); assert.equal(f.window.destroyed, true);
});

test('home is an explicit saved DIP position and survives movement to a negative-coordinate monitor', () => {
  const f = fixture(); const home = f.window.getPosition();
  assert.equal(f.manager.saveHome(), true); assert.equal(f.saved.at(-1).companion.home.monitorId, '1');
  assert.equal(f.manager.moveToMonitor(2), true); assert.ok(f.window.bounds.x < 0);
  assert.equal(f.saved.at(-1).companion.monitorId, '2');
  assert.equal(f.manager.returnHome(), true); assert.deepEqual(f.window.getPosition(), home);
  assert.equal(f.manager.moveToMonitor('unknown'), false);
});

test('monitor removal restores an accessible position and retires display listeners on shutdown', () => {
  const f = fixture(); f.manager.moveToMonitor(2); f.removeSecond();
  assert.ok(f.window.bounds.x >= -f.manager.sideMargin());
  assert.equal(f.window.messages.at(-1).payload.kind, 'teleport');
  assert.equal(f.screen.listenerCount('display-removed'), 1);
  f.manager.destroy(); assert.equal(f.screen.listenerCount('display-removed'), 0);
});

test('live renderer settings are forwarded once per change and passive mode is explicit', () => {
  const f = fixture(); const initial = f.window.messages.filter(item => item.channel === 'companion:settings').length;
  f.apply({});
  assert.equal(f.window.messages.filter(item => item.channel === 'companion:settings').length, initial);
  f.apply({ physics: { enabled: false } });
  assert.equal(f.window.messages.at(-1).channel, 'companion:settings');
  assert.equal(f.window.messages.at(-1).payload.physics.enabled, false);
  f.apply({ companion: { mode: 'passive' } });
  assert.equal(f.window.messages.filter(item => item.channel === 'companion:visibility').at(-1).payload.mode, 'passive');
});

test('in-place settings changes still publish passive mode to the renderer', () => {
  const f = fixture();
  f.manager.settings.companion.mode = 'passive';
  f.manager.applySettings(f.manager.settings);
  assert.equal(f.window.messages.filter(item => item.channel === 'companion:visibility').at(-1).payload.mode, 'passive');
});

for (const cancel of ['interrupt', 'hidden', 'gaming', 'destroy']) {
  test(`${cancel} resolves the pending walk action`, async () => {
    const f = fixture(); f.manager.waitFor = async () => true;
    const pending = f.manager.walkTo(f.window.bounds.x - 100);
    for (let i = 0; i < 10 && !f.manager.moveTimer; i++) await Promise.resolve();
    assert.ok(f.manager.moveTimer, cancel + ' starts an active movement before cancellation');
    if (cancel === 'interrupt') f.manager.interrupt();
    else if (cancel === 'destroy') f.manager.destroy();
    else f.apply({ companion: { mode: cancel } });
    const result = await Promise.race([pending, new Promise(resolve => setTimeout(() => resolve('unresolved'), 50))]);
    assert.equal(result, false, cancel + ' must settle the old movement promise');
    f.manager.destroy();
  });
}

test('a replacement walk settles the preceding movement instead of orphaning it', async () => {
  const f = fixture(); f.manager.waitFor = async () => true;
  const first = f.manager.walkTo(f.window.bounds.x - 100);
  for (let i = 0; i < 10 && !f.manager.moveTimer; i++) await Promise.resolve();
  assert.ok(f.manager.moveTimer);
  const next = f.manager.walkTo(f.window.bounds.x - 180);
  for (let i = 0; i < 10 && !f.manager.moveTimer; i++) await Promise.resolve();
  const result = await Promise.race([first, new Promise(resolve => setTimeout(() => resolve('unresolved'), 50))]);
  f.manager.interrupt();
  assert.equal(result, false); assert.equal(await next, false); f.manager.destroy();
});

test('interrupting a peek slide releases its pending movement', async () => {
  const f = fixture();
  const pending = f.manager.slideTo(f.window.bounds.x - 100, 1000);
  f.manager.interrupt();
  const result = await Promise.race([pending, new Promise(resolve => setTimeout(() => resolve('unresolved'), 50))]);
  assert.equal(result, false); f.manager.destroy();
});

test('manual character controls are local, explicit and reject unknown actions', async () => {
  const f = fixture(); f.manager.actorReady = true;
  const before = f.window.messages.length;
  assert.equal((await f.manager.manualControl('deleteFile')).ok, false);
  assert.equal(f.window.messages.length, before);
  assert.equal((await f.manager.manualControl('wave')).ok, true);
  assert.equal(f.window.messages.at(-1).payload.name, 'wave');
  f.apply({ companion: { mode: 'passive' } });
  let requested = '';
  f.manager.sitDown = async () => { requested = 'sit'; f.manager.mode = 'sitting'; };
  f.manager.standUp = async () => { requested = 'stand'; f.manager.mode = 'standing'; };
  assert.equal((await f.manager.manualControl('sit')).ok, true); assert.equal(requested, 'sit');
  assert.equal((await f.manager.manualControl('stand')).ok, true); assert.equal(requested, 'stand');
  f.manager.destroy();
});

test('manual movement targets the companion and can be interrupted by Stop', async () => {
  const f = fixture(); f.manager.actorReady = true; f.manager.waitFor = async () => true;
  const pending = f.manager.manualControl('move_left');
  for (let i = 0; i < 10 && !f.manager.moveTimer; i++) await Promise.resolve();
  assert.ok(f.manager.moveTimer);
  assert.equal((await f.manager.manualControl('stop')).ok, true);
  assert.equal((await pending).ok, false); assert.equal(f.manager.moveTimer, null);
  f.manager.destroy();
});

test('manual actions explain loading or suspension instead of silently succeeding', async () => {
  const f = fixture();
  assert.equal((await f.manager.manualControl('sit')).ok, false);
  f.manager.actorReady = true; f.apply({ companion: { mode: 'gaming' } });
  assert.equal((await f.manager.manualControl('sit')).ok, false);
  assert.equal(f.manager.moveTimer, null); f.manager.destroy();
});

test('desktop text controls recognize exact local intents without consuming other requests', () => {
  assert.deepEqual(parseDesktopCharacterCommand('MYRAA, please move left.'), { action: 'move_left' });
  assert.deepEqual(parseDesktopCharacterCommand('walk right'), { action: 'move_right' });
  assert.deepEqual(parseDesktopCharacterCommand('stop moving'), { action: 'stop' });
  assert.deepEqual(parseDesktopCharacterCommand('मायरा, बैठ जाओ'), { action: 'sit' });
  assert.equal(parseDesktopCharacterCommand('Explain how to move left in Blender'), null);
  assert.equal(parseDesktopCharacterCommand('Open Notepad and sit'), null);
});
