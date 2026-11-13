import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, readFile, writeFile, rm } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { sha256 } from './download-archives.mjs';
import {
  parseReleaseProperties, makeNativeFeed, verifyNativeProvenance, verifiedNativePair, checkUpgrade,
  nativeApkFilename, nativeSourceFilename, nativeProvenanceFilename,
  readArtifactPair, verifyPublishedRelease, verifiedPublishedRelease, nativeInputDigest,
} from './native-release.mjs';

const properties = 'updateBaseUrl=https://example.com\nversionCode=2\nversionName=0.1.1\n';
const config = parseReleaseProperties(properties);
const apkInfo = { applicationId: 'app.spektralab', versionCode: 2, versionName: '0.1.1', minSdk: 29 };
const notes = { en: 'Updates', 'zh-Hans': '更新', 'zh-Hant': '更新' };
const apk = Buffer.from('fixture binary');
const source = Buffer.from('fixture source');
function provenance() {
  return { schema: 1, apk: apkInfo, apkSha256: sha256(apk), sourceSha256: sha256(source),
    sourceDigest: sha256('[]'), nativeInputDigest: nativeInputDigest([]), files: [], signerSha256: 'a'.repeat(64), releaseProperties: properties, releaseNotes: notes };
}

test('feed is generated from actual native APK metadata and all three notes', () => {
  const feed = makeNativeFeed(config, apkInfo, notes, sha256(apk));
  assert.equal(feed.versionCode, 2);
  assert.equal(feed.downloadUrl, 'https://example.com/downloads/Silvercam-Android.apk');
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

test('website and updater publish one actual version, hash and source pair', () => {
  const pair = verifyNativeProvenance(provenance(), apk, source);
  assert.equal(pair.android.href, new URL(pair.feed.downloadUrl).pathname);
  assert.equal(pair.android.sha256, pair.feed.sha256);
  assert.equal(pair.android.version, pair.feed.versionName);
  assert.equal(pair.android.minAndroid, '10+');
  assert.equal(pair.source.filename, 'Silvercam-App-source.zip');
  assert.equal(pair.source.sha256, sha256(source));
  assert.equal(nativeProvenanceFilename, 'Silvercam-Android.provenance.json');
});

test('publishing rejects stale sources and stale or tampered update feeds', () => {
  const pair = verifyNativeProvenance(provenance(), apk, source);
  const current = { manifest: [] };
  assert.equal(verifyPublishedRelease(pair, current, pair.feed), pair);
  assert.throws(() => verifyPublishedRelease(null, current, pair.feed), /APK missing/);
  for (const name of ['app/src/main/Camera.kt', 'engine/src/main/Film.kt', 'app/build.gradle.kts',
    'release/android.properties', 'gradle/libs.versions.toml', 'scripts/build-native-android.mjs']) {
    assert.throws(() => verifyPublishedRelease(pair, { manifest: [{ name, mode: 420, sha256: sha256('edited') }] }, pair.feed), /stale/);
  }
  const websiteOnly = { manifest: [{ name: 'src/i18n/translations.ts', mode: 420, sha256: sha256('copy') },
    { name: 'promo-video/index.ts', mode: 420, sha256: sha256('promo') }] };
  assert.equal(verifyPublishedRelease(pair, websiteOnly, pair.feed), pair);
  for (const changed of [
    { ...pair.feed, versionCode: 1 }, { ...pair.feed, sha256: sha256('old apk') },
    { ...pair.feed, downloadUrl: 'https://example.com/downloads/Silvercam-Native.apk' },
    { ...pair.feed, releaseNotes: { ...notes, en: 'Unbuilt notes' } },
  ]) assert.throws(() => verifyPublishedRelease(pair, current, changed), /feed/);
});

test('full release verification refuses orphan files and missing feed', async () => {
  const dir = await mkdtemp(path.join(os.tmpdir(), 'silvercam-publish-'));
  const feedPath = path.join(dir, 'android.json');
  const current = { manifest: [] };
  try {
    await writeFile(path.join(dir, nativeProvenanceFilename), JSON.stringify(provenance()));
    await assert.rejects(readArtifactPair(dir), /Incomplete/);
    await writeFile(path.join(dir, nativeApkFilename), apk);
    await writeFile(path.join(dir, nativeSourceFilename), source);
    await assert.rejects(verifiedPublishedRelease({ directory: dir, feedPath, current }));
    const feed = makeNativeFeed(config, apkInfo, notes, sha256(apk));
    await writeFile(feedPath, JSON.stringify(feed));
    const pair = await verifiedPublishedRelease({ directory: dir, feedPath, current });
    assert.equal(pair.android.sha256, feed.sha256);
    await assert.rejects(verifiedPublishedRelease({ directory: dir, feedPath, current: { manifest: [{ name: 'app/new.kt', mode: 420, sha256: sha256('changed') }] } }), /stale/);
    await writeFile(feedPath, JSON.stringify({ ...feed, versionCode: 1 }));
    await assert.rejects(verifiedPublishedRelease({ directory: dir, feedPath, current }), /feed/);
  } finally { await rm(dir, { recursive: true, force: true }); }
});

test('both npm Android build commands select the shared release entry point', async () => {
  const pkg = JSON.parse(await readFile(new URL('../package.json', import.meta.url)));
  assert.equal(pkg.scripts['android:build'], pkg.scripts['android:build:native']);
});

test('an interrupted publication cannot pass until APK, source, provenance and feed agree', async () => {
  const dir = await mkdtemp(path.join(os.tmpdir(), 'silvercam-interrupted-'));
  const feedPath = path.join(dir, 'android.json');
  const current = { manifest: [] };
  const verify = () => verifiedPublishedRelease({ directory: dir, feedPath, current });
  const old = provenance();
  const nextApk = Buffer.from('new version binary');
  const nextSource = Buffer.from('new corresponding source');
  const next = { ...old, apk: { ...apkInfo, versionCode: 3, versionName: '0.1.2' },
    releaseProperties: properties.replace('versionCode=2', 'versionCode=3').replace('0.1.1', '0.1.2'),
    apkSha256: sha256(nextApk), sourceSha256: sha256(nextSource) };
  const nextFeed = verifyNativeProvenance(next, nextApk, nextSource).feed;
  try {
    await writeFile(path.join(dir, nativeApkFilename), apk);
    await writeFile(path.join(dir, nativeSourceFilename), source);
    await writeFile(path.join(dir, nativeProvenanceFilename), JSON.stringify(old));
    await writeFile(feedPath, JSON.stringify(verifyNativeProvenance(old, apk, source).feed));
    await verify();
    await writeFile(path.join(dir, nativeSourceFilename), nextSource);
    await assert.rejects(verify(), /provenance/);
    await writeFile(path.join(dir, nativeApkFilename), nextApk);
    await assert.rejects(verify(), /provenance/);
    await writeFile(path.join(dir, nativeProvenanceFilename), JSON.stringify(next));
    await assert.rejects(verify(), /feed/);
    await writeFile(feedPath, JSON.stringify(nextFeed));
    assert.equal((await verify()).android.sha256, nextFeed.sha256);
  } finally { await rm(dir, { recursive: true, force: true }); }
});

test('native input digest includes file content, paths and modes but ignores website edits', () => {
  const entry = { name: 'app/src/main/Camera.kt', sha256: sha256('source'), mode: 420 };
  const digest = nativeInputDigest([entry]);
  for (const changed of [{ ...entry, sha256: sha256('edited') }, { ...entry, name: 'app/src/main/New.kt' },
    { ...entry, mode: 493 }]) assert.notEqual(nativeInputDigest([changed]), digest);
  assert.notEqual(nativeInputDigest([]), digest);
  assert.equal(nativeInputDigest([entry, { name: 'src/styles.css', sha256: sha256('new style'), mode: 420 }]), digest);
});

test('legacy pairs can be inspected for migration but never published as native', async () => {
  const dir = await mkdtemp(path.join(os.tmpdir(), 'silvercam-migrate-'));
  const legacy = provenance();
  delete legacy.apk;
  delete legacy.signerSha256;
  delete legacy.releaseProperties;
  delete legacy.releaseNotes;
  legacy.version = '0.1.0';
  try {
    await writeFile(path.join(dir, nativeApkFilename), apk);
    await writeFile(path.join(dir, nativeSourceFilename), source);
    await writeFile(path.join(dir, nativeProvenanceFilename), JSON.stringify(legacy));
    assert.equal((await readArtifactPair(dir)).provenance.version, '0.1.0');
    await assert.rejects(verifiedNativePair(dir), /signing/);
  } finally { await rm(dir, { recursive: true, force: true }); }
});

test('release feed fits client byte limit and website minimum SDK stays accurate', () => {
  assert.throws(() => makeNativeFeed(config, { ...apkInfo, minSdk: 30 }, notes, sha256(apk)), /website Android requirement/);
  const oversizedNotes = Object.fromEntries(['en', 'zh-Hans', 'zh-Hant'].map(locale => [locale, '新'.repeat(8000)]));
  assert.throws(() => makeNativeFeed(config, apkInfo, oversizedNotes, sha256(apk)), /response limit/);
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
