import { spawnSync } from 'node:child_process';
import { existsSync } from 'node:fs';
import { mkdir, readFile, readdir, rename, writeFile } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { root, outputDir, snapshot, archiveBytes, sha256 } from './download-archives.mjs';
import {
  nativeApkFilename, nativeSourceFilename, nativeProvenanceFilename, parseReleaseProperties,
  makeNativeFeed, readArtifactPair, checkUpgrade, nativeInputDigest,
} from './native-release.mjs';

const sdk = process.env.ANDROID_HOME || process.env.ANDROID_SDK_ROOT
  || (process.platform === 'darwin' ? path.join(os.homedir(), 'Library', 'Android', 'sdk') : undefined);
if (!sdk || !existsSync(sdk)) throw new Error('Set ANDROID_HOME or ANDROID_SDK_ROOT to the Android SDK');
const windows = process.platform === 'win32';
const tools = (await readdir(path.join(sdk, 'build-tools'))).filter(name => /^\d+\.\d+\.\d+$/.test(name))
  .sort((a, b) => b.localeCompare(a, undefined, { numeric: true }));
if (!tools.length) throw new Error('Android SDK build-tools missing');
function tool(name, args) {
  const result = spawnSync(path.join(sdk, 'build-tools', tools[0], name), args, { encoding: 'utf8', shell: windows });
  if (result.error || result.status !== 0) throw new Error(`${name} failed: ${result.error?.message ?? result.stderr}`);
  return result.stdout;
}
function inspectApk(apkPath) {
  const badging = tool(windows ? 'aapt.exe' : 'aapt', ['dump', 'badging', apkPath]);
  const apk = {
    applicationId: badging.match(/package: name='([^']+)'/)[1],
    versionCode: Number(badging.match(/versionCode='(\d+)'/)[1]),
    versionName: badging.match(/versionName='([^']+)'/)[1],
    minSdk: Number(badging.match(/sdkVersion:'(\d+)'/)[1]),
  };
  const certificate = tool(windows ? 'apksigner.bat' : 'apksigner', ['verify', '--print-certs', apkPath]);
  const signerSha256 = certificate.match(/Signer #1 certificate SHA-256 digest: ([a-f0-9]+)/i)?.[1].toLowerCase();
  if (!signerSha256) throw new Error('No verified signing certificate');
  return { apk, signerSha256 };
}

// The first unified release can replace the old prototype, or upgrade the native
// APK already published under this filename. Inspect the binary even if its old
// provenance predates native metadata; never assume a legacy filename is WebView.
const previousPair = await readArtifactPair();
let previous;
if (previousPair) {
  const inspected = inspectApk(path.join(outputDir, nativeApkFilename));
  if (inspected.apk.applicationId === 'app.spektralab') {
    previous = { ...inspected, apkSha256: sha256(previousPair.apk) };
  } else if (inspected.apk.applicationId !== 'app.spektralab.prototype') {
    throw new Error('Existing download belongs to an unexpected Android application');
  }
}
const before = await snapshot();
const releaseProperties = before.entries.find(entry => entry.name === 'release/android.properties').bytes.toString('utf8');
const config = parseReleaseProperties(releaseProperties);
const releaseNotes = JSON.parse(before.entries.find(entry => entry.name === 'release/android-notes.json').bytes);
const args = [...(windows ? [] : ['gradlew']), ...(process.env.ANDROID_BUILD_OFFLINE === '1' ? ['--offline'] : []), ':app:assembleRelease', '--console=plain'];
const result = spawnSync(windows ? 'gradlew.bat' : 'sh', args, {
  cwd: root, stdio: 'inherit', shell: windows,
  env: { ...process.env, ANDROID_HOME: sdk, ANDROID_SDK_ROOT: sdk },
});
if (result.error) throw result.error;
if (result.status !== 0) process.exit(result.status ?? 1);
if (before.digest !== (await snapshot()).digest) throw new Error('Source changed during build; downloads were not replaced. Build again after edits finish.');

const apkPath = path.join(root, 'app/build/outputs/apk/release/app-release.apk');
const { apk: apkInfo, signerSha256 } = inspectApk(apkPath);
const apk = await readFile(apkPath);
const source = archiveBytes(before);
const feed = makeNativeFeed(config, apkInfo, releaseNotes, sha256(apk));
const provenance = {
  schema: 1, builtAt: new Date().toISOString(), apk: apkInfo, signerSha256,
  releaseProperties, releaseNotes, apkSha256: feed.sha256, sourceSha256: sha256(source),
  sourceDigest: before.digest, nativeInputDigest: nativeInputDigest(before.manifest), files: before.manifest,
};
checkUpgrade(previous, provenance);
const updatesDir = path.join(root, 'public', 'updates');
await mkdir(outputDir, { recursive: true });
await mkdir(updatesDir, { recursive: true });
// Feed is written last. Website packaging validates all four files, so an
// interrupted replacement cannot be deployed as a mismatched release.
for (const [destination, bytes] of [
  [path.join(outputDir, nativeSourceFilename), source],
  [path.join(outputDir, nativeApkFilename), apk],
  [path.join(outputDir, nativeProvenanceFilename), JSON.stringify(provenance, null, 2) + '\n'],
  [path.join(updatesDir, 'android.json'), JSON.stringify(feed, null, 2) + '\n'],
]) {
  const temporary = `${destination}.${process.pid}.tmp`;
  await writeFile(temporary, bytes);
  await rename(temporary, destination);
}
await import('./package-downloads.mjs');
console.log(`Native ${config.versionName} (${config.versionCode}) ready: public/downloads/${nativeApkFilename}`);
console.log(`Update feed: ${config.updateBaseUrl}/updates/android.json`);
console.log('Local files only. Publish the APK, corresponding source, provenance, and feed together.');
