import assert from 'node:assert/strict';
import test from 'node:test';
import { armDownload, canDownload, consumeAutostart, readSupport, resolveApk, writeSupport } from './gate.ts';

class MemoryStorage {
  private readonly values = new Map<string, string>();
  getItem(key: string) { return this.values.get(key) ?? null; }
  setItem(key: string, value: string) { this.values.set(key, value); }
  removeItem(key: string) { this.values.delete(key); }
}

class BlockedStorage {
  getItem(): string | null { throw new Error('blocked'); }
  setItem(): void { throw new Error('blocked'); }
  removeItem(): void { throw new Error('blocked'); }
}

const href = '/downloads/Silvercam-Android.apk';

test('a missing APK stays unavailable instead of looping to coffee', () => {
  const store = new MemoryStorage();
  assert.deepEqual(resolveApk(null, store), { href: null, available: false, remembered: false });
  assert.deepEqual(resolveApk(undefined, store).href, null);
  assert.equal(canDownload(store), false);
});

test('an available APK is directly reachable before any support choice', () => {
  const store = new MemoryStorage();
  const choice = resolveApk(href, store);
  assert.equal(choice.available, true);
  assert.equal(choice.href, href);
  assert.equal(choice.remembered, false);
});

test('blocked storage cannot hide an available APK or trap navigation', () => {
  const store = new BlockedStorage();
  assert.equal(writeSupport('skipped', store), false);
  assert.equal(readSupport(store), null);
  assert.equal(canDownload(store), false);
  assert.equal(armDownload('skipped', store), false);
  assert.equal(consumeAutostart(store), false);
  assert.deepEqual(resolveApk(href, store), { href, available: true, remembered: false });
  assert.deepEqual(resolveApk(null, store), { href: null, available: false, remembered: false });
});

test('a saved skip or return remembers the choice and autostarts once', () => {
  const store = new MemoryStorage();
  assert.equal(armDownload('skipped', store), true);
  assert.equal(canDownload(store), true);
  assert.equal(consumeAutostart(store), true);
  assert.equal(consumeAutostart(store), false);
  assert.equal(resolveApk(href, store).remembered, true);

  const returned = new MemoryStorage();
  writeSupport('returned', returned);
  assert.equal(canDownload(returned), true);
  writeSupport('awaiting', returned);
  assert.equal(canDownload(returned), false);
});
