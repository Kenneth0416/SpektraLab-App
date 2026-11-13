import { verifiedPublishedRelease } from './native-release.mjs';

const { android, source } = await verifiedPublishedRelease();
console.log(`Verified current native release ${android.version}: ${android.filename}, ${source.filename}, and updater feed share the build snapshot and APK SHA-256 ${android.sha256}`);
