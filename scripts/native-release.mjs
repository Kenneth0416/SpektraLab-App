import { readFile } from 'node:fs/promises';
import path from 'node:path';
import { isDeepStrictEqual } from 'node:util';
import { root, outputDir, sha256, snapshot, metadata, apkFilename, sourceFilename, provenanceFilename } from './download-archives.mjs';

// One release serves the website and the installed app. Keep existing public URLs.
export const nativeApkFilename = apkFilename;
export const nativeSourceFilename = sourceFilename;
export const nativeProvenanceFilename = provenanceFilename;

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
  if (!Number.isInteger(apk.minSdk) || apk.minSdk !== 29) throw new Error('Update the website Android requirement before changing native minSdk');
  if (!/^[a-f0-9]{64}$/.test(apkSha256)) throw new Error('Invalid APK checksum');
  for (const locale of ['en', 'zh-Hans', 'zh-Hant']) {
    if (typeof notes[locale] !== 'string' || !notes[locale].trim() || notes[locale].length > 8000) throw new Error(`Missing or invalid ${locale} release notes`);
  }
  const feed = {
    schemaVersion: 1, applicationId: apk.applicationId,
    versionCode: apk.versionCode, versionName: apk.versionName, minSdk: apk.minSdk,
    downloadUrl: `${config.updateBaseUrl}/downloads/${nativeApkFilename}`,
    sha256: apkSha256, releaseNotes: notes,
  };
  if (Buffer.byteLength(JSON.stringify(feed, null, 2) + '\n') > 64 * 1024) throw new Error('Release notes exceed the update client response limit');
  return feed;
}

// Also accepts legacy provenance, solely so the builder can verify the old pair
// and inspect its actual package/version/certificate before the one-time migration.
export function verifyArtifactHashes(provenance, apk, source) {
  if (provenance.schema !== 1 || provenance.apkSha256 !== sha256(apk)
      || provenance.sourceSha256 !== sha256(source) || !Array.isArray(provenance.files)
      || provenance.sourceDigest !== sha256(JSON.stringify(provenance.files))) {
    throw new Error('APK/source pair does not match its build provenance');
  }
}

// Website copy and promo files are not native inputs. Hash paths, content and
// modes from the same source snapshot used to create the corresponding archive.
export function nativeInputDigest(manifest) {
  const roots = ['app/', 'engine/', 'tools/', 'release/', 'gradle/'];
  const files = new Set(['build.gradle.kts', 'settings.gradle.kts', 'gradle.properties',
    'gradlew', 'gradlew.bat', 'scripts/build-android.mjs', 'scripts/build-native-android.mjs',
    'scripts/native-release.mjs', 'scripts/download-archives.mjs']);
  return sha256(JSON.stringify(manifest.filter(entry => files.has(entry.name)
    || roots.some(prefix => entry.name.startsWith(prefix))).sort((a, b) => a.name < b.name ? -1 : a.name > b.name ? 1 : 0)));
}

export function verifyNativeProvenance(provenance, apk, source) {
  verifyArtifactHashes(provenance, apk, source);
  if (!/^[a-f0-9]{64}$/.test(provenance.signerSha256)) throw new Error('Missing native signing certificate');
  if (provenance.nativeInputDigest !== nativeInputDigest(provenance.files)) throw new Error('Native input provenance is missing or invalid; rebuild the APK');
  const config = parseReleaseProperties(provenance.releaseProperties);
  const feed = makeNativeFeed(config, provenance.apk, provenance.releaseNotes, provenance.apkSha256);
  return {
    feed, provenance,
    android: { ...metadata(nativeApkFilename, apk), version: feed.versionName, minAndroid: '10+' },
    source: metadata(nativeSourceFilename, source, provenance.files.length),
  };
}

export async function readArtifactPair(directory = outputDir) {
  const readOptional = async filename => readFile(path.join(directory, filename)).catch(error => {
    if (error.code === 'ENOENT') return null;
    throw error;
  });
  const [apk, source, provenanceBytes] = await Promise.all([
    readOptional(nativeApkFilename), readOptional(nativeSourceFilename), readOptional(nativeProvenanceFilename),
  ]);
  if (!apk && !source && !provenanceBytes) return null;
  if (!apk || !source || !provenanceBytes) throw new Error('Incomplete APK/source/provenance release');
  const provenance = JSON.parse(provenanceBytes);
  verifyArtifactHashes(provenance, apk, source);
  return { apk, source, provenance };
}

export async function verifiedNativePair(directory = outputDir) {
  const pair = await readArtifactPair(directory);
  return pair && verifyNativeProvenance(pair.provenance, pair.apk, pair.source);
}

export function verifyPublishedRelease(pair, current, feed) {
  if (!pair) throw new Error('Native APK missing. Run npm run android:build.');
  if (pair.provenance.nativeInputDigest !== nativeInputDigest(current.manifest)) {
    throw new Error('Native APK is stale relative to the current native inputs. Bump the release version and run npm run android:build.');
  }
  if (!isDeepStrictEqual(feed, pair.feed)) throw new Error('Update feed does not match the website APK. Run npm run android:build.');
  return pair;
}

export async function verifiedPublishedRelease({ directory = outputDir,
  feedPath = path.join(root, 'public/updates/android.json'), current = snapshot() } = {}) {
  try {
    const [pair, source, feed] = await Promise.all([
      verifiedNativePair(directory), current, readFile(feedPath, 'utf8').then(JSON.parse),
    ]);
    return verifyPublishedRelease(pair, source, feed);
  } catch (error) {
    throw new Error(`Cannot publish the native release: ${error.message}. Run npm run android:build after integrating source and updating the release version.`, { cause: error });
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
