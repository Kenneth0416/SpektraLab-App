import { spawnSync } from 'node:child_process';
import { existsSync } from 'node:fs';
import { mkdir, readFile, readdir, rename, writeFile } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { root, outputDir, snapshot, archiveBytes, sha256 } from './download-archives.mjs';
import {
  nativeApkFilename, nativeSourceFilename, nativeProvenanceFilename, parseReleaseProperties,
  makeNativeFeed, verifiedNativePair, checkUpgrade,
} from './native-release.mjs';

const sdk = process.env.ANDROID_HOME || process.env.ANDROID_SDK_ROOT
  || (process.platform === 'darwin' ? path.join(os.homedir(), 'Library', 'Android', 'sdk') : undefined);
if (!sdk || !existsSync(sdk)) throw new Error('Set ANDROID_HOME or ANDROID_SDK_ROOT to the Android SDK');
const previous = await verifiedNativePair();
const releaseProperties = await readFile(path.join(root, 'release/android.properties'), 'utf8');
const config = parseReleaseProperties(releaseProperties);
const releaseNotes = JSON.parse(await readFile(path.join(root, 'release/android-notes.json'), 'utf8'));
const before = await snapshot();
const windows = process.platform === 'win32';
const args = [...(windows ? [] : ['gradlew']), ...(process.env.ANDROID_BUILD_OFFLINE === '1' ? ['--offline'] : []), ':app:assembleRelease', '--console=plain'];
const result = spawnSync(windows ? 'gradlew.bat' : 'sh', args, {
  cwd: root, stdio: 'inherit', shell: windows,
  env: { ...process.env, ANDROID_HOME: sdk, ANDROID_SDK_ROOT: sdk },
});
if (result.error) throw result.error;
if (result.status !== 0) process.exit(result.status ?? 1);
if (before.digest !== (await snapshot()).digest) throw new Error('Source changed during build; downloads were not replaced. Build again after edits finish.');

const apkPath = path.join(root, 'app/build/outputs/apk/release/app-release.apk');
const tools = (await readdir(path.join(sdk, 'build-tools'))).filter(name => /^\d+\.\d+\.\d+$/.test(name))
  .sort((a, b) => b.localeCompare(a, undefined, { numeric: true }));
if (!tools.length) throw new Error('Android SDK build-tools missing');
function tool(name, args) {
  const result = spawnSync(path.join(sdk, 'build-tools', tools[0], name), args, { encoding: 'utf8', shell: windows });
  if (result.error || result.status !== 0) throw new Error(`${name} failed: ${result.error?.message ?? result.stderr}`);
  return result.stdout;
}
// Read the actual binary, not just the Gradle config, before publishing metadata.
const badging = tool(windows ? 'aapt.exe' : 'aapt', ['dump', 'badging', apkPath]);
const apkInfo = {
  applicationId: badging.match(/package: name='([^']+)'/)[1],
  versionCode: Number(badging.match(/versionCode='(\d+)'/)[1]),
  versionName: badging.match(/versionName='([^']+)'/)[1],
  minSdk: Number(badging.match(/sdkVersion:'(\d+)'/)[1]),
};
const certificate = tool(windows ? 'apksigner.bat' : 'apksigner', ['verify', '--print-certs', apkPath]);
const signerSha256 = certificate.match(/Signer #1 certificate SHA-256 digest: ([a-f0-9]+)/i)?.[1].toLowerCase();
if (!signerSha256) throw new Error('No verified signing certificate');
const apk = await readFile(apkPath);
const source = archiveBytes(before);
const feed = makeNativeFeed(config, apkInfo, releaseNotes, sha256(apk));
const provenance = {
  schema: 1, builtAt: new Date().toISOString(), apk: apkInfo, signerSha256,
  releaseProperties, releaseNotes, apkSha256: feed.sha256, sourceSha256: sha256(source),
  sourceDigest: before.digest, files: before.manifest,
};
checkUpgrade(previous?.provenance, provenance);
await mkdir(outputDir, { recursive: true });
for (const [filename, bytes] of [
  [nativeSourceFilename, source], [nativeApkFilename, apk],
  [nativeProvenanceFilename, JSON.stringify(provenance, null, 2) + '\n'],
]) {
  const destination = path.join(outputDir, filename);
  const temporary = `${destination}.${process.pid}.tmp`;
  await writeFile(temporary, bytes);
  await rename(temporary, destination);
}
await import('./package-downloads.mjs');
console.log(`Native ${config.versionName} (${config.versionCode}) ready: public/downloads/${nativeApkFilename}`);
console.log(`Update feed: ${config.updateBaseUrl}/updates/android.json`);
console.log('Local files only. Publish the APK, corresponding source, and feed together.');
