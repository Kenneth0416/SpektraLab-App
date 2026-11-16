// Promo render list: which photo goes through which look. Prints jobs.tsv (for
// PromoDumpTest) with --tsv, otherwise exports the job array for render.js.
const STOCKS = {
  portra400: 'film=kodak_portra_400', portra160: 'film=kodak_portra_160', portra800: 'film=kodak_portra_800',
  gold200: 'film=kodak_gold_200', ektar100: 'film=kodak_ektar_100', ultramax400: 'film=kodak_ultramax_400',
  c200: 'film=fujifilm_c200', pro400h: 'film=fujifilm_pro_400h',
  vision500t_2383: 'film=kodak_vision3_500t;paper=kodak_2383', vision250d_2383: 'film=kodak_vision3_250d;paper=kodak_2383',
  velvia100: 'film=fujifilm_velvia_100', kodachrome64: 'film=kodak_kodachrome_64', provia100f: 'film=fujifilm_provia_100f',
};
const HERO = ['portra400', 'gold200', 'ektar100', 'vision500t_2383', 'velvia100', 'kodachrome64', 'c200', 'pro400h'];
const PLAN = {
  portrait: HERO.concat(['portra160', 'portra800']), canyon: HERO, valley: HERO, bulbs: HERO, scooter: HERO,
  bridge: ['vision500t_2383', 'portra800'], pier: ['velvia100', 'ektar100'], lantern: ['vision500t_2383', 'portra400'],
  cafe: ['portra400', 'c200'], beach: ['pro400h', 'gold200'], hikers: ['portra400', 'kodachrome64'],
  sign: ['gold200', 'kodachrome64'], mandarins: ['ektar100', 'ultramax400'], waterfall: ['pro400h', 'provia100f'],
};
const jobs = [];
const add = (photo, tag, spec, extra = {}) => jobs.push({ photo, tag, spec, out: `${photo}__${tag}`, ...extra });
for (const [photo, stocks] of Object.entries(PLAN)) for (const s of stocks) add(photo, s, STOCKS[s]);
// halation off / on (same film, same seed)
add('bulbs', 'halation_off__portra800', 'film=kodak_portra_800;halation=0');
add('bulbs', 'halation_on__portra800', 'film=kodak_portra_800;halation=3;halationSize=1.6');
add('bridge', 'halation_off__vision500t_2383', 'film=kodak_vision3_500t;paper=kodak_2383;halation=0');
add('bridge', 'halation_on__vision500t_2383', 'film=kodak_vision3_500t;paper=kodak_2383;halation=3;halationSize=1.6');
// diffusion filters
add('bulbs', 'cinebloom__portra400', 'film=kodak_portra_400;diffusion=cinebloom;diffusionAmount=0.7');
add('bridge', 'black_pro_mist__vision500t_2383', 'film=kodak_vision3_500t;paper=kodak_2383;diffusion=black_pro_mist;diffusionAmount=0.7');
// grain off / on, 100% crops (half-frame format: the largest grain)
add('portrait', 'grain_off__portra800_crop', 'film=kodak_portra_800;grain=0;format=half', { crop: [300, 700, 1200, 800] });
add('portrait', 'grain_on__portra800_crop', 'film=kodak_portra_800;grain=1.6;grainSize=1.4;format=half', { crop: [300, 700, 1200, 800] });
// borders and date back
add('valley', 'gold200__whiteborder', 'film=kodak_gold_200;border=white');
add('scooter', 'portra400__filmedge_date', 'film=kodak_portra_400;border=edge;dateStamp=true', { frame: 12 });
add('cafe', 'c200__whiteborder_date', 'film=fujifilm_c200;border=white;dateStamp=true');
add('portrait', 'portra400__filmedge', 'film=kodak_portra_400;border=edge', { frame: 7 });
// formats
add('valley', 'ektar100__xpan', 'film=kodak_ektar_100;format=xpan');
add('canyon', 'velvia100__66', 'film=fujifilm_velvia_100;format=66');
// enlarger filtration, exposure
add('canyon', 'portra400__y+20', 'film=kodak_portra_400;yFilter=20');
add('canyon', 'portra400__m+20', 'film=kodak_portra_400;mFilter=20');
add('portrait', 'portra400__filmEv+2', 'film=kodak_portra_400;filmEv=2');
// pipeline stages of one frame: the developed negative scanned directly, and its print
add('portrait', 'stage_negative', 'film=kodak_portra_400;paper=none');
add('portrait', 'stage_print', 'film=kodak_portra_400');
add('canyon', 'stage_negative', 'film=kodak_portra_400;paper=none');
add('canyon', 'stage_print', 'film=kodak_portra_400');

// camera viewfinder: the whole 4:3 sensor frame through the look at the effect's processing
// size (FilmViewfinderEffect: long edge 1280 when the frame is advanced, 22 for the ground glass)
for (const [p, tag, spec] of [['vf_scooter', 'portra400', STOCKS.portra400], ['vf_cafe', 'c200', STOCKS.c200],
  ['vf_bulbs', 'vision500t_2383', STOCKS.vision500t_2383], ['vf_bridge', 'vision500t_2383', STOCKS.vision500t_2383]]) {
  add(p, tag, spec, { size: [960, 1280] });
  add(p, tag + '__groundglass', spec, { size: [17, 22] });
}

const FORMAT = { half: 4 / 3, 135: 1.5, xpan: 65 / 24, 66: 1, 67: 70 / 56 };
/** Framing.compute for a 3:2 source of long edge 2400 (the prepared photos). */
function framing(portrait, spec) {
  const f = Object.fromEntries(spec.split(';').map(s => s.split('=')));
  const sw = portrait ? 1600 : 2400, sh = portrait ? 2400 : 1600;
  const a = FORMAT[f.format || '135'], target = portrait ? 1 / a : a, sa = sw / sh;
  let cw = 1, ch = 1; if (sa > target) cw = target / sa; else ch = sa / target;
  const crop = [(1 - cw) / 2, (1 - ch) / 2, (1 + cw) / 2, (1 + ch) / 2].map(Math.fround);
  const iw = Math.max(2, Math.round(sw * cw)), ih = Math.max(2, Math.round(sh * ch));
  let ow = iw, oh = ih, rect = [0, 0, 1, 1];
  if (f.border === 'white') { const pad = Math.floor(Math.max(iw, ih) * 0.03); ow = iw + 2 * pad; oh = ih + 2 * pad;
    rect = [pad / ow, pad / oh, (pad + iw) / ow, (pad + ih) / oh]; }
  else if (f.border === 'edge') { const short = Math.min(iw, ih); let bx = Math.round(short * 0.035), by = Math.round(short * 0.09);
    if (portrait) [bx, by] = [by, bx]; ow = iw + 2 * bx; oh = ih + 2 * by; rect = [bx / ow, by / oh, (bx + iw) / ow, (by + ih) / oh]; }
  return { crop, iw, ih, ow, oh, rect, look: f };
}
const PORTRAIT = new Set(['portrait']);
for (const j of jobs) { j.framing = framing(PORTRAIT.has(j.photo), j.spec);
  if (j.size) j.framing = { crop: [0, 0, 1, 1], iw: j.size[0], ih: j.size[1], ow: j.size[0], oh: j.size[1], rect: [0, 0, 1, 1], look: j.framing.look }; j.edge = Math.max(j.framing.iw, j.framing.ih); }
if (process.argv.includes('--tsv')) for (const j of jobs) console.log(`${j.out}\t${j.edge}\t${j.spec}`);
module.exports = { jobs };
