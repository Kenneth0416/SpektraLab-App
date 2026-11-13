import { readFile } from 'node:fs/promises';
import path from 'node:path';
import { outputDir, sha256 } from './download-archives.mjs';

export const nativeApkFilename = 'Silvercam-Native.apk';
export const nativeSourceFilename = 'Silvercam-Native-source.zip';
export const nativeProvenanceFilename = 'Silvercam-Native.provenance.json';

export function parseReleaseProperties(text) {
  const entries = text.split(/\r?\n/).map(line => line.trim()).filter(line => line && !line.startsWith('#'));
  const config = Object.fromEntries(entries.map(line => {
    const separator = line.indexOf('=');
    if (separator < 1) throw new Error('Invalid release property');
    return [line.slice(0, separator).trim(), line.slice(separator + 1).trim()];
  }));
  const base = new URL(config.updateBaseUrl);
  if (base.protocol !== 'https:' || base.username || base.password || base.search || base.hash) {
    throw new Error('updateBaseUrl must be an HTTPS base URL');
  }
  if (!/^[1-9]\d*$/.test(config.versionCode) || Number(config.versionCode) > 2100000000) throw new Error('Invalid versionCode');
  if (!config.versionName?.trim() || config.versionName.length > 80) throw new Error('Invalid versionName');
  return { updateBaseUrl: base.href.replace(/\/$/, ''), versionCode: Number(config.versionCode), versionName: config.versionName };
}

export function makeNativeFeed(config, apk, notes, apkSha256) {
  if (apk.applicationId !== 'app.spektralab') throw new Error('Refusing to publish a prototype or unrelated APK as a native update');
  if (apk.versionCode !== config.versionCode || apk.versionName !== config.versionName) throw new Error('APK version does not match release configuration');
  if (!Number.isInteger(apk.minSdk) || apk.minSdk < 29) throw new Error('Invalid native minimum SDK');
  if (!/^[a-f0-9]{64}$/.test(apkSha256)) throw new Error('Invalid APK checksum');
  for (const locale of ['en', 'zh-Hans', 'zh-Hant']) {
    if (typeof notes[locale] !== 'string' || !notes[locale].trim() || notes[locale].length > 8000) throw new Error(`Missing or invalid ${locale} release notes`);
  }
  return {
    schemaVersion: 1, applicationId: apk.applicationId,
    versionCode: apk.versionCode, versionName: apk.versionName, minSdk: apk.minSdk,
    downloadUrl: `${config.updateBaseUrl}/downloads/${nativeApkFilename}`,
    sha256: apkSha256, releaseNotes: notes,
  };
}

export function verifyNativeProvenance(provenance, apk, source) {
  if (provenance.schema !== 1 || provenance.apkSha256 !== sha256(apk)
      || provenance.sourceSha256 !== sha256(source) || !Array.isArray(provenance.files)
      || provenance.sourceDigest !== sha256(JSON.stringify(provenance.files))
      || !/^[a-f0-9]{64}$/.test(provenance.signerSha256)) {
    throw new Error('Native APK/source pair does not match its build provenance');
  }
  const config = parseReleaseProperties(provenance.releaseProperties);
  const feed = makeNativeFeed(config, provenance.apk, provenance.releaseNotes, provenance.apkSha256);
  return { feed, provenance };
}

export async function verifiedNativePair(directory = outputDir) {
  let apk;
  try { apk = await readFile(path.join(directory, nativeApkFilename)); }
  catch (error) { if (error.code === 'ENOENT') return null; throw error; }
  try {
    const provenance = JSON.parse(await readFile(path.join(directory, nativeProvenanceFilename), 'utf8'));
    const source = await readFile(path.join(directory, nativeSourceFilename));
    return verifyNativeProvenance(provenance, apk, source);
  } catch (error) {
    throw new Error(`Cannot publish native updates. Run npm run android:build:native. ${error.message}`);
  }
}

export function checkUpgrade(previous, next) {
  if (!previous) return;
  if (next.signerSha256 !== previous.signerSha256) throw new Error('APK signing certificate changed; installed users could not upgrade');
  if (next.apk.versionCode < previous.apk.versionCode) throw new Error('Refusing to publish a lower versionCode');
  if (next.apkSha256 !== previous.apkSha256 && next.apk.versionCode === previous.apk.versionCode) {
    throw new Error('Changed APK requires a larger versionCode so installed users can discover it');
  }
}
