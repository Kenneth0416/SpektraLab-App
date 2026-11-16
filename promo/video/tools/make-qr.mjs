// Writes assets/qr.js (inline SVG for the video) and ../site/qr.svg (for the download page).
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import QRCode from 'qrcode';

const root = path.join(path.dirname(fileURLToPath(import.meta.url)), '..');
const url = process.argv[2] || 'https://silvercam.convert-md.com';
const svg = await QRCode.toString(url, { type: 'svg', errorCorrectionLevel: 'M', margin: 0, color: { dark: '#1B1A17', light: '#ffffff' } });
fs.writeFileSync(path.join(root, 'assets/qr.js'), `// QR code for ${url} (tools/make-qr.mjs)\nexport default ${JSON.stringify(svg.replace('<svg ', '<svg style="display:block;width:100%;height:100%" '))};\n`);
fs.mkdirSync(path.join(root, '../site'), { recursive: true });
fs.writeFileSync(path.join(root, '../site/qr.svg'), svg);
console.log('qr for', url);
