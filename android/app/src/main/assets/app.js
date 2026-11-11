const films = [
  { name: 'Portra', iso: '400', color: '#edc58e', filter: 'saturate(.9) contrast(.96) sepia(.1)' },
  { name: 'Ektar', iso: '100', color: '#ff684f', filter: 'saturate(1.35) contrast(1.12)' },
  { name: 'Gold', iso: '200', color: '#f2ba42', filter: 'saturate(1.15) contrast(1.03) sepia(.24)' },
  { name: 'UltraMax', iso: '400', color: '#6bb9ed', filter: 'saturate(1.25) contrast(1.08) sepia(.12)' },
  { name: 'Vision3', iso: '500T', color: '#88aef2', filter: 'saturate(.9) contrast(.98) hue-rotate(-10deg)' },
  { name: 'Double-X', iso: '250', color: '#d9d5ca', filter: 'grayscale(1) contrast(1.2)' },
];
const scenes = [
  { src: 'assets/snow-tree.jpg', code: 'SNOW / WIDE' },
  { src: 'assets/snow-pines.jpg', code: 'PINES / 18:42' },
  { src: 'assets/woods-cow.jpg', code: 'CATTLE / 09:16' },
  { src: 'assets/spiral-atrium.jpg', code: 'ATRIUM / 12:08' },
  { src: 'assets/guangzhou-bus.jpg', code: 'CITY / 20:12' },
  { src: 'assets/guangzhou-night.jpg', code: 'NIGHT / 21:40' },
  { src: 'assets/alaska-peak.jpg', code: 'PEAK / 11:05' },
  { src: 'assets/alaska-falls.jpg', code: 'FALLS / 14:22' },
  { src: 'assets/alaska-otter.jpg', code: 'COAST / 16:08' },
];
const recipes = [
  { film: 0, exposure: .5, grain: .45, halation: .35, stock: 'PORTRA 400 × ENDURA' },
  { film: 4, exposure: .3, grain: .65, halation: 1.1, stock: 'VISION3 500T × 2383' },
  { film: 5, exposure: 0, grain: 1.2, halation: 0, stock: 'DOUBLE-X 250 × HALF' },
  { film: 2, exposure: .4, grain: .55, halation: .8, stock: 'GOLD 200 × XPAN' },
];

const licenseDocs = [
  { key: 'gpl3', path: 'licenses/GPL-3.0.txt' },
  { key: 'ccbysa4', path: 'licenses/SPEKTRAFILM_LICENSE.txt' },
  { key: 'changelog', path: 'licenses/CHANGELOG.txt' },
  { key: 'notice', path: 'licenses/NOTICE.txt' },
];

const messages = {
  'zh-Hant': {
    short: '繁', language: '切換語言', darkroom: '暗房', recipes: '配方', about: '關於', live: '即時預覽', reset: '重設',
    hold: 'HOLD TO COMPARE / 按住看原圖', original: 'ORIGINAL / 原圖', holdAria: '按住看原圖',
    exposure: '曝光', grain: '顆粒', halation: '光暈', chooseScene: '切換場景', chooseFilm: '選擇膠片', mainNav: '主要導航',
    recipesTitle: '留給下一個雨夜。', recipesIntro: '選一張配方卡，把它的光線與質感帶回暗房。', apply: '套用配方',
    aboutTitle: '一卷尚未沖完的實驗。',
    brand: '銀鹽工坊',
    aboutBody: '銀鹽工坊為可離線安裝的互動原型，以本地 WebView 呈現暗房操作與 CSS/SVG 近似質感，不包含正式膠片渲染引擎、相機或相冊存取。本應用為獨立專案，與 spektrafilm 或 SpektraLab 專案均無隸屬或背書關係。',
    brandSpec: '銀鹽工坊',
    offline: '無需網絡 · 無網絡權限',
    licensesHeading: '本地完整授權與聲明原文',
    closeLicense: '‹ 返回',
    sceneNames: ['雪坡孤樹', '霜林雪原', '林間牛隻', '螺旋中庭', '廣州公車', '夜城車流', '雪山鷹', '岩壁瀑布', '海獺'],
    filmNotes: ['柔和膚色 · 高寬容度', '濃郁色彩 · 細緻顆粒', '暖黃懷舊 · 日光伴侶', '日常快照 · 鮮明飽和', '霓虹夜景 · 冷調暗部', '經典黑白 · 硬朗反差'],
    recipeNames: ['日常人像', '霓虹夜景', '街頭黑白', '旅行快照'],
    legalCards: [
      {
        title: '獨立分支與非背書聲明',
        body: '銀鹽工坊為參考與衍生自 spektrafilm 的獨立下游專案，與原作者 Andrea Volpato、上游 spektrafilm 專案或 SpektraLab 專案均無隸屬、贊助或背書關係。\n膠片與相紙名稱（如 Portra、Ektar、Gold、Vision3 等）僅作原型示意，商標屬各自權利人所有。',
      },
      {
        title: '程式碼與數據授權區分（GPLv3 vs CC BY-SA 4.0）',
        body: '• 可執行程式碼（android/ 封裝與倉庫內 app/、engine/ 移植碼）：採用 GNU General Public License v3.0（GPLv3），含修改記錄（2026-11-10 – 2026-11-11），按 GPLv3「按原樣」提供且不附帶任何擔保（NO WARRANTY），授權人得依 GPLv3 再分發。\n• 上游膠片檔案與 LUT 條款：源自 Andrea Volpato 的 spektrafilm（https://github.com/andreavolpato/spektrafilm），採用 Creative Commons Attribution-ShareAlike 4.0 International（CC BY-SA 4.0）。註：本 WebView 互動原型僅使用本地 CSS 濾鏡與 SVG 插圖，未打包 .spk 二進位檔案；完整 CC BY-SA 4.0 原文與原檔變更說明仍一併本地收錄以維持透明。\n• 輸出成品：依上游前序與 GPLv3 第 2 條，成品照片或影片不具 copyleft 傳染性，亦不強制添加輸出浮水印。',
      },
      {
        title: '對應源碼義務（GPLv3 Section 6）',
        body: '源碼倉庫：https://github.com/Kenneth0416/SpektraLab-App\n注意：在發佈二進位 APK 時，僅放上通用倉庫首頁連結並不足以自動滿足 GPLv3 對應源碼（Corresponding Source）要求；正式或公開發佈必須提供或隨附與該 APK 完全相符的精確源碼修訂版本、構建腳本及建置素材。',
      },
      {
        title: '字體與第三方依賴核驗（本 APK）',
        body: '• 執行依賴：本 android/ WebView 原型僅使用 Android 系統平台之 Activity 與 WebView，android/app/build.gradle 未引入任何第三方外部程式庫。\n• 字體：僅使用系統內建 UI 與等寬字體，本 APK 未內嵌第三方字體檔。（註：倉庫原生 Compose 模組 app/ 另行內嵌 Long Cang 與 Martian Mono，均採 SIL OFL 1.1，並使用 Apache-2.0 之 Kotlin / Jetpack AndroidX 套件及 CameraX Core 標註的 BSD libyuv 元件。）',
      },
    ],
    licenseLabels: [
      { title: 'GNU GPL v3.0 完整許可原文', sub: '程式碼條款 · assets/licenses/GPL-3.0.txt' },
      { title: 'CC BY-SA 4.0 與 spektrafilm 前序原文', sub: '檔案與 LUT 條款 · assets/licenses/SPEKTRAFILM_LICENSE.txt' },
      { title: '檔案修改與原型範圍記錄（CHANGELOG）', sub: '2026-11-10 – 2026-11-11 · assets/licenses/CHANGELOG.txt' },
      { title: '修改聲明、源碼與依賴摘要（NOTICE）', sub: '本地聲明 · assets/licenses/NOTICE.txt' },
    ],
  },
  'zh-Hans': {
    short: '简', language: '切换语言', darkroom: '暗房', recipes: '配方', about: '关于', live: '实时预览', reset: '重置',
    hold: 'HOLD TO COMPARE / 按住看原图', original: 'ORIGINAL / 原图', holdAria: '按住看原图',
    exposure: '曝光', grain: '颗粒', halation: '光晕', chooseScene: '切换场景', chooseFilm: '选择胶片', mainNav: '主要导航',
    recipesTitle: '留给下一个雨夜。', recipesIntro: '选一张配方卡，把它的光线与质感带回暗房。', apply: '套用配方',
    aboutTitle: '一卷尚未冲完的实验。',
    brand: '银盐工坊',
    aboutBody: '银盐工坊为可离线安装的交互原型，以本地 WebView 呈现暗房操作与 CSS/SVG 近似质感，不包含正式胶片渲染引擎、相机或相册访问。本应用为独立项目，与 spektrafilm 或 SpektraLab 项目均无隶属或背书关系。',
    brandSpec: '银盐工坊',
    offline: '无需网络 · 无网络权限',
    licensesHeading: '本地完整许可与声明原文',
    closeLicense: '‹ 返回',
    sceneNames: ['雪坡孤树', '霜林雪原', '林间牛只', '螺旋中庭', '广州公车', '夜城车流', '雪山鹰', '岩壁瀑布', '海獭'],
    filmNotes: ['柔和肤色 · 高宽容度', '浓郁色彩 · 细致颗粒', '暖黄怀旧 · 日光伴侣', '日常快照 · 鲜明饱和', '霓虹夜景 · 冷调暗部', '经典黑白 · 硬朗反差'],
    recipeNames: ['日常人像', '霓虹夜景', '街头黑白', '旅行快照'],
    legalCards: [
      {
        title: '独立分支与非背书声明',
        body: '银盐工坊为参考并衍生自 spektrafilm 的独立下游项目，与原作者 Andrea Volpato、上游 spektrafilm 项目或 SpektraLab 项目均无隶属、赞助或背书关系。\n胶片与相纸名称（如 Portra、Ektar、Gold、Vision3 等）仅作原型示意，商标归各自权利人所有。',
      },
      {
        title: '代码与数据许可区分（GPLv3 vs CC BY-SA 4.0）',
        body: '• 可执行代码（android/ 封装与仓库内 app/、engine/ 移植代码）：采用 GNU General Public License v3.0（GPLv3），含修改记录（2026-11-10 – 2026-11-11），按 GPLv3“按原样”提供且不附带任何担保（NO WARRANTY），任何被许可方均可依 GPLv3 再分发。\n• 上游胶片档案与 LUT 条款：源自 Andrea Volpato 的 spektrafilm（https://github.com/andreavolpato/spektrafilm），采用 Creative Commons Attribution-ShareAlike 4.0 International（CC BY-SA 4.0）。注：本 WebView 交互原型仅使用本地 CSS 滤镜与 SVG 插图，未打包 .spk 二进制文件；完整 CC BY-SA 4.0 原文与变更说明仍一并本地收录以保持透明。\n• 输出成品：依上游前序与 GPLv3 第 2 条，成品照片或视频不具 copyleft 传染性，也不强制添加输出水印。',
      },
      {
        title: '对应源码义务（GPLv3 Section 6）',
        body: '源码仓库：https://github.com/Kenneth0416/SpektraLab-App\n注意：发布二进制 APK 时，仅提供通用仓库主页链接本身并不足以自动满足 GPLv3 对应源码（Corresponding Source）要求；公开或正式发布必须提供或随附与该 APK 完全匹配的精确源码修订版本、构建脚本及建置材料。',
      },
      {
        title: '字体与第三方依赖核验（本 APK）',
        body: '• 运行时依赖：本 android/ WebView 原型仅使用 Android 系统平台的 Activity 与 WebView，android/app/build.gradle 未引入任何第三方外部库。\n• 字体：仅使用系统自带 UI 与等宽字体，本 APK 未内置第三方字体文件。（注：仓库原生 Compose 模块 app/ 另行内置 Long Cang 与 Martian Mono，均采用 SIL OFL 1.1，并使用 Apache-2.0 的 Kotlin / Jetpack AndroidX 库及 CameraX Core 标注的 BSD libyuv 组件。）',
      },
    ],
    licenseLabels: [
      { title: 'GNU GPL v3.0 完整许可原文', sub: '代码条款 · assets/licenses/GPL-3.0.txt' },
      { title: 'CC BY-SA 4.0 与 spektrafilm 前序原文', sub: '档案与 LUT 条款 · assets/licenses/SPEKTRAFILM_LICENSE.txt' },
      { title: '档案修改与原型范围记录（CHANGELOG）', sub: '2026-11-10 – 2026-11-11 · assets/licenses/CHANGELOG.txt' },
      { title: '修改声明、源码与依赖摘要（NOTICE）', sub: '本地声明 · assets/licenses/NOTICE.txt' },
    ],
  },
  en: {
    short: 'EN', language: 'Change language', darkroom: 'Darkroom', recipes: 'Recipes', about: 'About', live: 'LIVE PREVIEW', reset: 'Reset',
    hold: 'HOLD TO COMPARE / ORIGINAL', original: 'ORIGINAL / UNPROCESSED', holdAria: 'Hold to see the original',
    exposure: 'Exposure', grain: 'Grain', halation: 'Halation', chooseScene: 'Change scene', chooseFilm: 'Choose film', mainNav: 'Main navigation',
    recipesTitle: 'Saved for the next rainy night.', recipesIntro: 'Choose an index card and bring its light and texture back to the darkroom.', apply: 'Apply recipe',
    aboutTitle: 'An experiment still in development.',
    brand: 'Silvercam',
    aboutBody: 'Silvercam is an installable, offline interaction prototype. A local WebView presents darkroom controls and approximate CSS/SVG treatments; it does not include a production film-rendering engine, camera, or photo-library access. Silvercam is an independent project and is not affiliated with or endorsed by the spektrafilm or SpektraLab projects.',
    brandSpec: 'Silvercam',
    offline: 'Offline · No network permission',
    licensesHeading: 'Locally Bundled Full Licenses & Notices',
    closeLicense: '‹ Back',
    sceneNames: ['Lone tree in snow', 'Frosted pines', 'A cow in the woods', 'Spiral atrium', 'Guangzhou buses', 'Night traffic', 'Peak and eagle', 'Cliff waterfall', 'Sea otter'],
    filmNotes: ['Soft skin tones · Wide latitude', 'Rich color · Fine grain', 'Warm nostalgia · Daylight companion', 'Everyday frames · Vivid color', 'Neon nights · Cool shadows', 'Classic monochrome · Firm contrast'],
    recipeNames: ['Everyday Portrait', 'Neon Night', 'Street Monochrome', 'Travel Snapshot'],
    legalCards: [
      {
        title: 'Independent Fork & Non-Endorsement',
        body: 'Silvercam is an independent downstream project derived from and referencing spektrafilm. It is NOT affiliated with, sponsored by, or endorsed by Andrea Volpato, the upstream spektrafilm project, or the SpektraLab project.\nFilm and paper stock names (Portra, Ektar, Gold, Vision3, etc.) are descriptive references and remain trademarks of their respective owners.',
      },
      {
        title: 'Code vs. Data Licensing (GPLv3 vs CC BY-SA 4.0)',
        body: '• Executable Code (android/ wrapper & repository app/ + engine/ ports): Licensed under GNU General Public License v3.0 (GPLv3), with dated modifications (2026-11-10 – 2026-11-11). Provided WITHOUT ANY WARRANTY under GPLv3; licensees may convey copies under GPLv3.\n• Upstream Profiles & LUT Terms: Derived from spektrafilm by Andrea Volpato (https://github.com/andreavolpato/spektrafilm) under CC BY-SA 4.0. Note: This WebView wrapper approximates film looks with CSS filters and SVGs and does NOT bundle binary .spk files; the full unmodified SPEKTRAFILM_LICENSE.txt and CHANGELOG.txt are bundled locally for completeness.\n• Outputs: Finished images/videos are not copyleft per the upstream preamble and GPLv3 Section 2; no output watermark is required.',
      },
      {
        title: 'Corresponding Source (GPLv3 Section 6)',
        body: 'Source Repository: https://github.com/Kenneth0416/SpektraLab-App\nHonesty Note: For a distributed binary APK release, a generic repository link alone does not automatically satisfy GPLv3 Section 6 Corresponding Source obligations—releases must provide or match the exact source revision, Gradle wrapper/build scripts, and build materials used for that specific binary.',
      },
      {
        title: 'Verifiable Fonts & Dependencies (This APK)',
        body: '• Runtime Dependencies: None outside the Android platform SDK (android.app.Activity and android.webkit.WebView). android/app/build.gradle declares zero external third-party dependencies.\n• Fonts: Uses Android system UI and monospace fonts only; no third-party font binaries are bundled in this wrapper APK. (The repository’s native Compose app in app/ separately bundles Long Cang and Martian Mono under SIL OFL 1.1 and AndroidX/Kotlin libraries under Apache-2.0, plus CameraX Core’s BSD libyuv reference.)',
      },
    ],
    licenseLabels: [
      { title: 'GNU GPL v3.0 (Full License Text)', sub: 'Code terms · assets/licenses/GPL-3.0.txt' },
      { title: 'CC BY-SA 4.0 & spektrafilm Preamble', sub: 'Profiles & LUT terms · assets/licenses/SPEKTRAFILM_LICENSE.txt' },
      { title: 'Profiles & Prototype Changelog', sub: '2026-11-10 – 2026-11-11 · assets/licenses/CHANGELOG.txt' },
      { title: 'Modified-Version, Source & Dependency Notice', sub: 'Local notice · assets/licenses/NOTICE.txt' },
    ],
  },
};

const localeOrder = ['zh-Hant', 'zh-Hans', 'en'];
let locale = 'zh-Hant';
try {
  const saved = localStorage.getItem('silvercam-language') || localStorage.getItem('spektralab-language');
  if (localeOrder.includes(saved)) locale = saved;
  else if ((navigator.language || '').toLowerCase().startsWith('en')) locale = 'en';
  else if (['zh-cn', 'zh-sg'].includes((navigator.language || '').toLowerCase())) locale = 'zh-Hans';
} catch { /* Use the default locale. */ }

const state = { film: 4, scene: 0, exposure: .3, grain: .65, halation: .7, compare: false, view: 'darkroom', licenseIndex: null };
const $ = (selector) => document.querySelector(selector);
const filmList = $('#filmList');
const sceneList = $('#sceneList');
const recipeList = $('#recipeList');
const legalNotices = $('#legalNotices');
const licenseButtons = $('#licenseButtons');

filmList.innerHTML = films.map((film, index) => `<button type="button" data-film="${index}" style="--color:${film.color}"><i></i><strong>${film.iso}</strong><span>${film.name}</span></button>`).join('');
sceneList.innerHTML = scenes.map((scene, index) => `<button type="button" data-scene="${index}"><img src="${scene.src}" alt=""><span></span></button>`).join('');
recipeList.innerHTML = recipes.map((recipe, index) => `<button type="button" class="recipe-card" data-recipe="${index}"><small data-recipe-brand="${index}"></small><strong data-recipe-name="${index}"></strong><span>${recipe.stock}</span><em></em></button>`).join('');

function render() {
  const m = messages[locale];
  const film = films[state.film];
  const scene = scenes[state.scene];
  document.documentElement.lang = locale;
  document.title = m.brand;
  document.documentElement.style.setProperty('--accent', film.color);
  $('#languageButton').textContent = m.short;
  $('#languageButton').setAttribute('aria-label', m.language);
  $('#filmList').setAttribute('aria-label', m.chooseFilm);
  $('.bottom-nav').setAttribute('aria-label', m.mainNav);
  document.querySelectorAll('[data-i18n]').forEach((node) => { node.textContent = m[node.dataset.i18n] || node.textContent; });
  ['darkroom', 'recipes', 'about'].forEach((view) => {
    $(`#${view}View`).hidden = state.view !== view;
  });
  document.querySelectorAll('[data-view]').forEach((button) => {
    const selected = button.dataset.view === state.view;
    button.classList.toggle('selected', selected);
    button.setAttribute('aria-current', selected ? 'page' : 'false');
  });
  $('#sceneImage').src = scene.src;
  $('#sceneImage').alt = m.sceneNames[state.scene];
  $('#halationImage').src = scene.src;
  $('#sceneCode').textContent = scene.code;
  $('#sceneImage').style.filter = state.compare ? 'none' : `${film.filter} brightness(${(1 + state.exposure * .12).toFixed(2)})`;
  $('#halationImage').style.display = state.compare ? 'none' : 'block';
  $('#halationImage').style.opacity = state.halation * .34;
  $('#halationImage').style.filter = `${film.filter} blur(13px) brightness(1.25)`;
  $('#grainLayer').style.display = state.compare ? 'none' : 'block';
  $('#grainLayer').style.opacity = state.grain * .38;
  $('#grainLayer').style.backgroundSize = `${170 - state.grain * 45}px`;
  $('.date-back').style.display = state.compare ? 'none' : 'block';
  $('#preview').classList.toggle('comparing', state.compare);
  $('#preview').setAttribute('aria-label', `${m.sceneNames[state.scene]} · ${m.holdAria}`);
  $('#compareLabel').textContent = state.compare ? m.original : m.hold;
  $('#filmReadout').textContent = `${film.name.toUpperCase()} ${film.iso}`;
  $('#currentFilm').textContent = `${film.name} ${film.iso}`;
  $('#filmNote').textContent = m.filmNotes[state.film];
  $('#exposureReadout').textContent = `${state.exposure >= 0 ? '+' : '−'}${Math.abs(state.exposure).toFixed(1)} EV`;
  ['exposure', 'grain', 'halation'].forEach((key) => { $(`#${key}`).value = state[key]; });
  $('#exposureOutput').textContent = `${state.exposure >= 0 ? '+' : '−'}${Math.abs(state.exposure).toFixed(1)}`;
  $('#grainOutput').textContent = `${Math.round(state.grain / 1.5 * 100)}%`;
  $('#halationOutput').textContent = `${Math.round(state.halation / 1.5 * 100)}%`;
  document.querySelectorAll('[data-film]').forEach((button) => {
    const selected = Number(button.dataset.film) === state.film;
    button.classList.toggle('selected', selected);
    button.setAttribute('aria-pressed', String(selected));
  });
  document.querySelectorAll('[data-scene]').forEach((button) => {
    const index = Number(button.dataset.scene);
    const selected = index === state.scene;
    button.classList.toggle('selected', selected);
    button.setAttribute('aria-pressed', String(selected));
    button.setAttribute('aria-label', m.sceneNames[index]);
    button.querySelector('span').textContent = m.sceneNames[index];
  });
  document.querySelectorAll('[data-recipe-name]').forEach((node) => { node.textContent = m.recipeNames[Number(node.dataset.recipeName)]; });
  document.querySelectorAll('[data-recipe-brand]').forEach((node) => { node.textContent = `${m.brand} / RECIPE 00${Number(node.dataset.recipeBrand) + 1}`; });
  document.querySelectorAll('.recipe-card em').forEach((node) => { node.textContent = `${m.apply} →`; });

  legalNotices.replaceChildren(...m.legalCards.map((card) => {
    const el = document.createElement('article');
    el.className = 'legal-card';
    const h2 = document.createElement('h2');
    h2.textContent = card.title;
    const p = document.createElement('p');
    p.textContent = card.body;
    el.append(h2, p);
    return el;
  }));

  licenseButtons.replaceChildren(...licenseDocs.map((doc, idx) => {
    const info = m.licenseLabels[idx];
    const btn = document.createElement('button');
    btn.type = 'button';
    btn.className = 'license-btn';
    btn.dataset.license = String(idx);
    const strong = document.createElement('strong');
    strong.textContent = info.title;
    const small = document.createElement('small');
    small.textContent = info.sub;
    btn.append(strong, small);
    return btn;
  }));

  const modal = $('#licenseModal');
  if (state.licenseIndex !== null && licenseDocs[state.licenseIndex]) {
    const doc = licenseDocs[state.licenseIndex];
    const info = m.licenseLabels[state.licenseIndex];
    const texts = window.SILVERCAM_LICENSES || {};
    $('#licenseModalTitle').textContent = info.title;
    $('#licenseModalPath').textContent = doc.path;
    $('#licenseModalText').textContent = texts[doc.key] || '';
    modal.hidden = false;
  } else {
    modal.hidden = true;
  }

  try { localStorage.setItem('silvercam-language', locale); } catch { /* Storage is optional. */ }
}

$('#languageButton').addEventListener('click', () => { locale = localeOrder[(localeOrder.indexOf(locale) + 1) % localeOrder.length]; render(); });
$('#resetButton').addEventListener('click', () => { Object.assign(state, { film: 4, exposure: .3, grain: .65, halation: .7, compare: false }); render(); });
filmList.addEventListener('click', (event) => { const button = event.target.closest('[data-film]'); if (button) { state.film = Number(button.dataset.film); state.compare = false; render(); } });
sceneList.addEventListener('click', (event) => { const button = event.target.closest('[data-scene]'); if (button) { state.scene = Number(button.dataset.scene); state.compare = false; render(); } });
['exposure', 'grain', 'halation'].forEach((key) => { $(`#${key}`).addEventListener('input', (event) => { state[key] = Number(event.target.value); state.compare = false; render(); }); });
document.querySelectorAll('[data-view]').forEach((button) => button.addEventListener('click', () => { state.view = button.dataset.view; state.compare = false; state.licenseIndex = null; $('#main').scrollTop = 0; render(); }));
recipeList.addEventListener('click', (event) => { const button = event.target.closest('[data-recipe]'); if (!button) return; const recipe = recipes[Number(button.dataset.recipe)]; Object.assign(state, { film: recipe.film, exposure: recipe.exposure, grain: recipe.grain, halation: recipe.halation, view: 'darkroom', licenseIndex: null }); render(); });
licenseButtons.addEventListener('click', (event) => {
  const button = event.target.closest('[data-license]');
  if (!button) return;
  state.licenseIndex = Number(button.dataset.license);
  render();
  $('#licenseModalText').scrollTop = 0;
});
$('#licenseCloseButton').addEventListener('click', () => { state.licenseIndex = null; render(); });
document.addEventListener('keydown', (event) => {
  if (event.key === 'Escape' && state.licenseIndex !== null) {
    state.licenseIndex = null;
    render();
  }
});
const preview = $('#preview');
preview.addEventListener('pointerdown', (event) => { preview.setPointerCapture(event.pointerId); state.compare = true; render(); });
['pointerup', 'pointercancel', 'lostpointercapture'].forEach((name) => preview.addEventListener(name, () => { state.compare = false; render(); }));
preview.addEventListener('keydown', (event) => { if (event.key === ' ' || event.key === 'Enter') { event.preventDefault(); state.compare = true; render(); } });
preview.addEventListener('keyup', (event) => { if (event.key === ' ' || event.key === 'Enter') { event.preventDefault(); state.compare = false; render(); } });
preview.addEventListener('contextmenu', (event) => event.preventDefault());
render();
