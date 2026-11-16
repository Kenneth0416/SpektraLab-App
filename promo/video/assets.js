// Every image the app scenes use, in one place.
//   assets/app/shots/   the app's own Compose screens (see app/src/test/.../PromoScreenshotTest.kt)
//   assets/app/renders/ photos developed by the app's film engine (see tools/promo)
const P = 'assets/spektrafilm/banner.jpg';   // placeholder until the real one is wired in
export default {
  library: P, librarySelect: P,
  editorFilm: P, editorPaper: P, editorGrain: P, editorHalation: P, editorFrame: P,
  camera: P, cameraAdvance: P, exportSheet: P, tablet: P,
  hero: { original: P, films: [['Kodak Portra 400', 'Portra Endura', P], ['Kodak Gold 200', 'Portra Endura', P], ['Kodak Ektar 100', 'Portra Endura', P],
    ['Kodak Vision3 500T', 'Kodak 2383', P], ['Fujifilm Velvia 100', '反转片 · 直接扫描', P], ['Kodak Kodachrome 64', '反转片 · 直接扫描', P]] },
  prints: [P, P, P],
  halOff: P, halOn: P, grainOff: P, grainOn: P, borderOff: P, borderOn: P,
  stageOriginal: P, stageNegative: P, stagePrint: P,
  site: P,
};
