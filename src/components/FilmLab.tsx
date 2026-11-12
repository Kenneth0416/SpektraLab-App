import { useEffect, useMemo, useState } from 'react';
import type { CSSProperties, KeyboardEvent, PointerEvent } from 'react';
import { Aperture, RotateCcw } from 'lucide-react';
import { useLanguage } from '../i18n/LanguageContext';

const films = [
  { id: 'portra', name: 'Portra', iso: '400', color: '#edc58e', filter: 'saturate(.9) contrast(.96) sepia(.1)' },
  { id: 'ektar', name: 'Ektar', iso: '100', color: '#ff684f', filter: 'saturate(1.35) contrast(1.12)' },
  { id: 'gold', name: 'Gold', iso: '200', color: '#f2ba42', filter: 'saturate(1.15) contrast(1.03) sepia(.24)' },
  { id: 'ultramax', name: 'UltraMax', iso: '400', color: '#6bb9ed', filter: 'saturate(1.25) contrast(1.08) sepia(.12)' },
  { id: 'vision3', name: 'Vision3', iso: '500T', color: '#88aef2', filter: 'saturate(.9) contrast(.98) hue-rotate(-10deg)' },
  { id: 'doublex', name: 'Double-X', iso: '250', color: '#d9d5ca', filter: 'grayscale(1) contrast(1.2)' },
];

const scenes = [
  { src: '/assets/scenes/snow-tree.jpg', thumb: '/assets/scenes/thumbs/snow-tree.jpg', code: 'SNOW / WIDE' },
  { src: '/assets/scenes/snow-pines.jpg', thumb: '/assets/scenes/thumbs/snow-pines.jpg', code: 'PINES / 18:42' },
  { src: '/assets/scenes/woods-cow.jpg', thumb: '/assets/scenes/thumbs/woods-cow.jpg', code: 'CATTLE / 09:16' },
  { src: '/assets/scenes/spiral-atrium.jpg', thumb: '/assets/scenes/thumbs/spiral-atrium.jpg', code: 'ATRIUM / 12:08' },
  { src: '/assets/scenes/guangzhou-bus.jpg', thumb: '/assets/scenes/thumbs/guangzhou-bus.jpg', code: 'CITY / 20:12' },
  { src: '/assets/scenes/guangzhou-night.jpg', thumb: '/assets/scenes/thumbs/guangzhou-night.jpg', code: 'NIGHT / 21:40' },
  { src: '/assets/scenes/alaska-peak.jpg', thumb: '/assets/scenes/thumbs/alaska-peak.jpg', code: 'PEAK / 11:05' },
  { src: '/assets/scenes/alaska-falls.jpg', thumb: '/assets/scenes/thumbs/alaska-falls.jpg', code: 'FALLS / 14:22' },
  { src: '/assets/scenes/alaska-otter.jpg', thumb: '/assets/scenes/thumbs/alaska-otter.jpg', code: 'COAST / 16:08' },
];

export function FilmLab() {
  const { messages } = useLanguage();
  const [filmId, setFilmId] = useState('vision3');
  const [sceneIndex, setSceneIndex] = useState(0);
  const [exposure, setExposure] = useState(0.3);
  const [grain, setGrain] = useState(0.65);
  const [halation, setHalation] = useState(0.7);
  const [compare, setCompare] = useState(false);
  const [lite, setLite] = useState(() => window.matchMedia('(max-width: 800px)').matches);
  useEffect(() => {
    const query = window.matchMedia('(max-width: 800px)');
    const update = () => setLite(query.matches);
    query.addEventListener('change', update);
    return () => query.removeEventListener('change', update);
  }, []);
  const filmIndex = films.findIndex((item) => item.id === filmId);
  const film = films[filmIndex];
  const scene = scenes[sceneIndex];
  const sceneName = messages.lab.sceneNames[sceneIndex];

  const filter = useMemo(() => (
    compare ? 'none' : `${film.filter} brightness(${(1 + exposure * 0.12).toFixed(2)})`
  ), [compare, film.filter, exposure]);

  const reset = () => {
    setFilmId('vision3');
    setExposure(0.3);
    setGrain(0.65);
    setHalation(0.7);
    setCompare(false);
  };

  const holdStart = (event: PointerEvent<HTMLButtonElement>) => {
    event.currentTarget.setPointerCapture(event.pointerId);
    setCompare(true);
  };
  const holdByKey = (event: KeyboardEvent<HTMLButtonElement>, value: boolean) => {
    if (event.key === ' ' || event.key === 'Enter') {
      event.preventDefault();
      setCompare(value);
    }
  };

  return (
    <section
      className="film-lab"
      aria-label={messages.lab.sectionLabel}
      style={{ '--film-accent': film.color } as CSSProperties}
    >
      <div className="lab-topbar">
        <div className="lab-title"><Aperture size={17} /><span>DARKROOM</span></div>
        <div className="lab-live"><i />{messages.lab.live}</div>
        <button type="button" onClick={reset} aria-label={messages.lab.reset} title={messages.lab.reset}><RotateCcw size={16} /></button>
      </div>

      <div className="lab-stage">
        <div className="stage-meta"><span>{scene.code}</span><span>135 — 12A</span></div>
        <button
          type="button"
          className={`lab-preview ${compare ? 'is-comparing' : ''}`}
          aria-label={`${sceneName} · ${messages.lab.compareHint}`}
          onPointerDown={holdStart}
          onPointerUp={() => setCompare(false)}
          onPointerCancel={() => setCompare(false)}
          onKeyDown={(event) => holdByKey(event, true)}
          onKeyUp={(event) => holdByKey(event, false)}
          onBlur={() => setCompare(false)}
        >
          <img className="lab-image" src={scene.src} alt={sceneName} width="1800" height="831" draggable="false" decoding="async" fetchPriority="high" style={{ filter }} />
          {!compare && (
            <>
              {!lite && halation > 0.05 && (
                <img className="lab-halation" src={scene.src} alt="" aria-hidden="true" draggable="false" decoding="async" style={{ opacity: halation * 0.34, filter: `${film.filter} blur(13px) brightness(1.25)` }} />
              )}
              <span className="lab-grain" aria-hidden="true" style={{ opacity: grain * (lite ? 0.22 : 0.38), backgroundSize: `${170 - grain * 45}px` }} />
            </>
          )}
          <span className="crop-corner top-left" /><span className="crop-corner top-right" />
          <span className="crop-corner bottom-left" /><span className="crop-corner bottom-right" />
          <span className="compare-label">{compare ? messages.lab.original : messages.lab.compare}</span>
          {!compare && <span className="date-back">'26 11 11</span>}
        </button>
        <div className="stage-caption">
          <span><i />{film.name.toUpperCase()} {film.iso}</span>
          <span>{exposure >= 0 ? '+' : '−'}{Math.abs(exposure).toFixed(1)} EV</span>
        </div>
      </div>

      <div className="lab-films" role="group" aria-label={messages.lab.chooseFilm}>
        {films.map((item, index) => (
          <button
            type="button"
            key={item.id}
            className={item.id === filmId ? 'selected' : ''}
            style={{ '--swatch': item.color } as CSSProperties}
            aria-label={`${item.name} ${item.iso} · ${messages.lab.filmTypes[index]}`}
            aria-pressed={item.id === filmId}
            onClick={() => setFilmId(item.id)}
          >
            <i />
            <strong>{item.iso}</strong>
            <span>{item.name}</span>
          </button>
        ))}
      </div>

      <div className="lab-sliders">
        <label>
          <span>{messages.lab.exposure} <small>EXPOSURE</small></span>
          <input type="range" min="-2" max="2" step="0.1" value={exposure} onChange={(event) => setExposure(Number(event.target.value))} />
          <output>{exposure >= 0 ? '+' : '−'}{Math.abs(exposure).toFixed(1)}</output>
        </label>
        <label>
          <span>{messages.lab.grain} <small>GRAIN</small></span>
          <input type="range" min="0" max="1.5" step="0.05" value={grain} onChange={(event) => setGrain(Number(event.target.value))} />
          <output>{Math.round(grain / 1.5 * 100)}%</output>
        </label>
        <label>
          <span>{messages.lab.halation} <small>HALATION</small></span>
          <input type="range" min="0" max="1.5" step="0.05" value={halation} onChange={(event) => setHalation(Number(event.target.value))} />
          <output>{Math.round(halation / 1.5 * 100)}%</output>
        </label>
      </div>

      <div className="lab-scenes">
        <div className="scene-copy"><strong>{film.name} {film.iso}</strong><span>{messages.lab.filmNotes[filmIndex]}</span><a href="https://www.ysjf.com/material" target="_blank" rel="noreferrer">{messages.lab.photoCredit}</a></div>
        <div className="scene-options" role="group" aria-label={messages.lab.chooseScene}>
          {scenes.map((item, index) => (
            <button key={item.src} type="button" className={index === sceneIndex ? 'selected' : ''} onClick={() => setSceneIndex(index)} aria-label={`${messages.lab.previewScene}: ${messages.lab.sceneNames[index]}`} aria-pressed={index === sceneIndex}>
              <img src={item.thumb} alt="" width="480" height="270" decoding="async" />
            </button>
          ))}
        </div>
      </div>
    </section>
  );
}
