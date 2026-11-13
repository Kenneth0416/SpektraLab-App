import { createHash } from 'node:crypto';
import { existsSync } from 'node:fs';
import { mkdir, readFile, readdir, stat, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { zipSync } from 'fflate';

export const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
export const outputDir = path.join(root, 'public', 'downloads');
export const sourceFilename = 'Silvercam-App-source.zip';
export const apkFilename = 'Silvercam-Android.apk';
export const provenanceFilename = 'Silvercam-Android.provenance.json';
export const sha256 = (bytes) => createHash('sha256').update(bytes).digest('hex');

const sourcePaths = [
  'README.md', 'LICENSE', 'NOTICE.md', 'docs', 'package.json', 'package-lock.json',
  'tsconfig.json', 'vite.config.ts', 'index.html', '.gitignore', 'src', 'scripts',
  'prototype', 'android', 'app', 'engine', 'tools', 'build.gradle.kts',
  'settings.gradle.kts', 'gradle.properties', 'gradlew', 'gradlew.bat', 'gradle',
  'public/assets', 'public/favicon.svg', 'public/logo-dichroic-prism.svg',
  'public/logo-dichroic-prism-mark.svg', 'public/_redirects', 'public/_headers',
];
export const prototypePaths = ['README.md', 'LICENSE', 'NOTICE.md', 'docs/YSJF_SAMPLES.md', 'prototype'];
const excluded = new Set([
  'src/generated/download-meta.ts',
  'android/local.properties',
  'local.properties',
  'docs/icon-concepts',
  'docs/logo-concepts',
]);

async function collect(relative, files) {
  if (excluded.has(relative)) return;
  const absolute = path.join(root, relative);
  if (!existsSync(absolute)) return;
  const info = await stat(absolute);
  if (info.isDirectory()) {
    for (const entry of await readdir(absolute, { withFileTypes: true })) {
      if (['build', '.gradle', '.git', '.vite', 'node_modules', 'dist', '__pycache__'].includes(entry.name)) continue;
      if (entry.name === '.DS_Store' || entry.name.endsWith('.pyc')) continue;
      if (entry.isSymbolicLink()) throw new Error(`Source snapshot refuses symlink: ${relative}/${entry.name}`);
      await collect(`${relative}/${entry.name}`, files);
    }
  } else if (info.isFile()) files.push(relative);
}

// Hash contents AND Unix modes. No reliance on Git HEAD or modification times:
// uncommitted edits and executable-bit changes are part of the build source.
export async function snapshot(paths = sourcePaths) {
  const files = [];
  for (const item of paths) await collect(item, files);
  const entries = [];
  for (const name of [...new Set(files)].sort()) {
    const bytes = await readFile(path.join(root, name));
    const mode = (await stat(path.join(root, name))).mode & 0o777;
    entries.push({ name, mode, bytes, sha256: sha256(bytes) });
  }
  const manifest = entries.map(({ name, mode, sha256 }) => ({ name, mode, sha256 }));
  return { entries, manifest, digest: sha256(JSON.stringify(manifest)) };
}

export function archiveBytes(snapshot) {
  const archive = {};
  for (const { name, mode, bytes } of snapshot.entries) {
    archive[name] = [new Uint8Array(bytes), {
      os: 3, attrs: ((0o100000 | mode) << 16) >>> 0,
      mtime: new Date(2020, 0, 1),
    }];
  }
  return zipSync(archive, { level: 9 });
}

export function metadata(filename, bytes, fileCount) {
  return {
    filename, href: `/downloads/${filename}`, bytes: bytes.byteLength,
    size: bytes.byteLength > 1024 * 1024
      ? `${(bytes.byteLength / 1024 / 1024).toFixed(1)} MB`
      : `${(bytes.byteLength / 1024).toFixed(1)} KB`,
    sha256: sha256(bytes), ...(fileCount === undefined ? {} : { fileCount }),
  };
}

export async function cachedArchive(filename, paths) {
  const current = await snapshot(paths);
  const cacheDir = path.join(root, 'android', 'build', 'download-cache');
  const cachePath = path.join(cacheDir, `${filename}.json`);
  const outputPath = path.join(outputDir, filename);
  try {
    const cache = JSON.parse(await readFile(cachePath, 'utf8'));
    if (cache.digest === current.digest) {
      const bytes = await readFile(outputPath);
      if (sha256(bytes) === cache.sha256) return metadata(filename, bytes, current.entries.length);
    }
  } catch (error) { if (error.code !== 'ENOENT' && !(error instanceof SyntaxError)) throw error; }
  const bytes = archiveBytes(current);
  await mkdir(cacheDir, { recursive: true });
  await writeFile(outputPath, bytes);
  await writeFile(cachePath, JSON.stringify({ digest: current.digest, sha256: sha256(bytes) }));
  return metadata(filename, bytes, current.entries.length);
}

export async function verifiedAndroidPair() {
  const apkPath = path.join(outputDir, apkFilename);
  if (!existsSync(apkPath)) return null;
  try {
    const provenance = JSON.parse(await readFile(path.join(outputDir, provenanceFilename), 'utf8'));
    const apk = await readFile(apkPath);
    const source = await readFile(path.join(outputDir, sourceFilename));
    if (provenance.schema !== 1 || provenance.apkSha256 !== sha256(apk)
      || provenance.sourceSha256 !== sha256(source)
      || !Array.isArray(provenance.files)
      || provenance.sourceDigest !== sha256(JSON.stringify(provenance.files))) {
      throw new Error('APK/source hashes do not match the build provenance');
    }
    return {
      android: { ...metadata(apkFilename, apk), version: provenance.version, minAndroid: '8.0+' },
      source: metadata(sourceFilename, source, provenance.files.length),
    };
  } catch (error) {
    throw new Error(`Cannot publish an unverified APK/source pair. Run npm run android:build. ${error.message}`);
  }
}
