import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, writeFile, rm } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { sha256 } from './download-archives.mjs';
import {
  parseReleaseProperties, makeNativeFeed, verifyNativeProvenance, verifiedNativePair, checkUpgrade,
  nativeApkFilename, nativeSourceFilename, nativeProvenanceFilename,
} from './native-release.mjs';

const properties = 'updateBaseUrl=https://example.com\nversionCode=2\nversionName=0.1.1\n';
const config = parseReleaseProperties(properties);
const apkInfo = { applicationId: 'app.spektralab', versionCode: 2, versionName: '0.1.1', minSdk: 29 };
const notes = { en: 'Updates', 'zh-Hans': '更新', 'zh-Hant': '更新' };
const apk = Buffer.from('fixture binary');
const source = Buffer.from('fixture source');
function provenance() {
  return { schema: 1, apk: apkInfo, apkSha256: sha256(apk), sourceSha256: sha256(source),
    sourceDigest: sha256('[]'), files: [], signerSha256: 'a'.repeat(64), releaseProperties: properties, releaseNotes: notes };
}

test('feed is generated from actual native APK metadata and all three notes', () => {
  const feed = makeNativeFeed(config, apkInfo, notes, sha256(apk));
  assert.equal(feed.versionCode, 2);
  assert.equal(feed.downloadUrl, 'https://example.com/downloads/Silvercam-Native.apk');
  assert.equal(feed.applicationId, 'app.spektralab');
  assert.deepEqual(feed.releaseNotes, notes);
  assert.throws(() => makeNativeFeed(config, { ...apkInfo, applicationId: 'app.spektralab.prototype' }, notes, sha256(apk)));
  assert.throws(() => makeNativeFeed(config, { ...apkInfo, versionCode: 3 }, notes, sha256(apk)));
  assert.throws(() => makeNativeFeed(config, apkInfo, { en: 'Incomplete' }, sha256(apk)));
});

test('configuration rejects unsafe URLs and invalid versions', () => {
  for (const url of ['http://example.com', 'https://user:secret@example.com', 'https://example.com?query', 'https://example.com#fragment']) {
    assert.throws(() => parseReleaseProperties(properties.replace('https://example.com', url)));
  }
  for (const code of ['-1', '2.5', '0', '2147483648']) assert.throws(() => parseReleaseProperties(properties.replace('versionCode=2', `versionCode=${code}`)));
});

test('build provenance detects altered APK, source and manifest', () => {
  assert.equal(verifyNativeProvenance(provenance(), apk, source).feed.versionName, '0.1.1');
  assert.throws(() => verifyNativeProvenance(provenance(), Buffer.from('altered'), source));
  assert.throws(() => verifyNativeProvenance(provenance(), apk, Buffer.from('altered')));
  assert.throws(() => verifyNativeProvenance({ ...provenance(), files: [{ name: 'altered' }] }, apk, source));
});

test('filesystem verification refuses missing provenance or tampered downloads', async () => {
  const dir = await mkdtemp(path.join(os.tmpdir(), 'silvercam-updates-'));
  try {
    assert.equal(await verifiedNativePair(dir), null);
    await writeFile(path.join(dir, nativeApkFilename), apk);
    await assert.rejects(verifiedNativePair(dir));
    await writeFile(path.join(dir, nativeSourceFilename), source);
    await writeFile(path.join(dir, nativeProvenanceFilename), JSON.stringify(provenance()));
    assert.equal((await verifiedNativePair(dir)).feed.versionCode, 2);
    await writeFile(path.join(dir, nativeApkFilename), 'tampered');
    await assert.rejects(verifiedNativePair(dir));
  } finally { await rm(dir, { recursive: true, force: true }); }
});

test('upgrades retain the signing certificate and increase versionCode for changed APKs', () => {
  const previous = provenance();
  assert.doesNotThrow(() => checkUpgrade(previous, previous));
  assert.throws(() => checkUpgrade(previous, { ...previous, signerSha256: 'b'.repeat(64) }));
  assert.throws(() => checkUpgrade(previous, { ...previous, apk: { ...apkInfo, versionCode: 1 } }));
  assert.throws(() => checkUpgrade(previous, { ...previous, apkSha256: sha256('new binary') }));
  assert.doesNotThrow(() => checkUpgrade(previous, {
    ...previous, apkSha256: sha256('new binary'), apk: { ...apkInfo, versionCode: 3 },
  }));
});
