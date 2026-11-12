import { spawnSync } from 'node:child_process';
import { existsSync } from 'node:fs';
import { mkdir, readFile, rename, writeFile } from 'node:fs/promises';
import { snapshot, archiveBytes, sha256, sourceFilename, apkFilename, provenanceFilename } from './download-archives.mjs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const androidDir = path.join(root, 'android');
const defaultMacSdk = path.join(os.homedir(), 'Library', 'Android', 'sdk');
const sdk = process.env.ANDROID_HOME || process.env.ANDROID_SDK_ROOT || (process.platform === 'darwin' ? defaultMacSdk : undefined);

// Keep the WebView prototype's embedded license texts in sync with the
// canonical .txt files before the APK is assembled.
await import('./build-licenses-data.mjs');

if (!sdk || !existsSync(sdk)) {
  console.error('Android SDK not found. Set ANDROID_HOME or ANDROID_SDK_ROOT before building.');
  process.exit(1);
}

// Capture after license generation, before Gradle reads any inputs.
const before = await snapshot();
const isWindows = process.platform === 'win32';
const gradleCommand = isWindows ? 'gradlew.bat' : 'sh';
const buildArgs = [...(process.env.ANDROID_BUILD_OFFLINE === '1' ? ['--offline'] : []), 'assembleDebug'];
const gradleArgs = isWindows ? buildArgs : ['gradlew', ...buildArgs];
const result = spawnSync(gradleCommand, gradleArgs, {
  cwd: androidDir,
  stdio: 'inherit',
  env: { ...process.env, ANDROID_HOME: sdk, ANDROID_SDK_ROOT: sdk },
  shell: process.platform === 'win32',
});
if (result.error) console.error(result.error.message);
if (result.status !== 0) process.exit(result.status ?? 1);

const input = path.join(androidDir, 'app', 'build', 'outputs', 'apk', 'debug', 'app-debug.apk');
const outputDir = path.join(root, 'public', 'downloads');
const after = await snapshot();
if (before.digest !== after.digest) {
  throw new Error('Source changed during the Android build. Downloads were not replaced; run the build again after edits finish.');
}
const apk = await readFile(input);
const source = archiveBytes(before);
const gradleConfig = await readFile(path.join(androidDir, 'app', 'build.gradle'), 'utf8');
const version = gradleConfig.match(/versionName\s+['"]([^'"]+)['"]/)[1];
const provenance = {
  schema: 1, version, builtAt: new Date().toISOString(),
  apkSha256: sha256(apk), sourceSha256: sha256(source),
  sourceDigest: before.digest, files: before.manifest,
};
await mkdir(outputDir, { recursive: true });
// Publish provenance last. An interrupted replacement is rejected by the
// packaging verifier rather than silently pairing files from different builds.
for (const [filename, bytes] of [
  [sourceFilename, source], [apkFilename, apk],
  [provenanceFilename, JSON.stringify(provenance, null, 2) + '\n'],
]) {
  const destination = path.join(outputDir, filename);
  const temporary = `${destination}.${process.pid}.tmp`;
  await writeFile(temporary, bytes);
  await rename(temporary, destination);
}
console.log(`APK ready: public/downloads/${apkFilename} (${(apk.length / 1024).toFixed(1)} KB)`);

await import('./package-downloads.mjs');
