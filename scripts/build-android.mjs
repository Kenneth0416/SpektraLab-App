import { spawnSync } from 'node:child_process';
import { existsSync } from 'node:fs';
import { copyFile, mkdir, stat } from 'node:fs/promises';
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

const isWindows = process.platform === 'win32';
const gradleCommand = isWindows ? 'gradlew.bat' : 'sh';
const gradleArgs = isWindows ? ['assembleDebug'] : ['gradlew', 'assembleDebug'];
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
const output = path.join(outputDir, 'Silvercam-Android.apk');
await mkdir(outputDir, { recursive: true });
await copyFile(input, output);
const info = await stat(output);
console.log(`APK ready: public/downloads/Silvercam-Android.apk (${(info.size / 1024).toFixed(1)} KB)`);

await import('./package-downloads.mjs');
