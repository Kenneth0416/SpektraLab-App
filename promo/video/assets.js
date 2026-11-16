// Every image the app scenes use, in one place (filled by tools/collect-assets.py).
//   shot_*   the app's own Compose screens (app/src/test/.../PromoScreenshotTest.kt)
//   others   photos developed by the app's film shaders (tools/promo/render.js)
const a = (n) => `assets/app/${n}.jpg`;
export default {
  library: a('shot_library'), librarySelect: a('shot_library_select'),
  editorFilm: a('shot_editor_film_portra400'), editorFilm2: a('shot_editor_film_gold200'), editorPaper: a('shot_editor_paper_2383'),
  editorVision: a('shot_editor_film_vision500t'), editorVelvia: a('shot_editor_film_velvia100'),
  editorHalation: a('shot_editor_halation'), editorFrame: a('shot_editor_frame_filmedge_date'),
  camera: a('shot_camera_c200_ready'), cameraAdvance: a('shot_camera_wind'), exportSheet: a('shot_editor_export_sheet'), tablet: a('shot_unfolded_editor_film'),
  hero: {
    original: a('hero_original'),
    films: [
      ['Kodak Portra 400', 'Portra Endura', a('hero_portra400')], ['Fujifilm C200', 'Crystal Archive', a('hero_c200')],
      ['Kodak Vision3 500T', 'Kodak 2383', a('hero_vision500t_2383')], ['Fujifilm Velvia 100', '反转片 · 直接扫描', a('hero_velvia100')],
      ['Fujifilm Pro 400H', 'Crystal Archive', a('hero_pro400h')], ['Kodak Kodachrome 64', '反转片 · 直接扫描', a('hero_kodachrome64')],
    ],
  },
  prints: [a('r_valley__gold200'), a('r_bridge__vision500t_2383'), a('r_pier__ektar100')],
  halOff: a('hal_off'), halOn: a('hal_on'), grainOff: a('grain_off'), grainOn: a('grain_on'), borderOff: a('border_off'), borderOn: a('border_on'),
  stageOriginal: a('stage_original'), stageNegative: a('stage_negative'), stagePrint: a('stage_print'),
  site: 'assets/site.jpg',
};
