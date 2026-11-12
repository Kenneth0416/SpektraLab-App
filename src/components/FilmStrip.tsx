import { useLanguage } from '../i18n/LanguageContext';

const frames = [
  { src: '/assets/scenes/thumbs/snow-pines.jpg', scene: 1, code: 'PINES 18:42', stock: 'GOLD 200' },
  { src: '/assets/scenes/thumbs/woods-cow.jpg', scene: 2, code: 'CATTLE 09:16', stock: 'PORTRA 400' },
  { src: '/assets/scenes/thumbs/guangzhou-night.jpg', scene: 5, code: 'NIGHT 21:40', stock: 'VISION3 500T' },
  { src: '/assets/scenes/thumbs/alaska-falls.jpg', scene: 7, code: 'FALLS 14:22', stock: 'EKTAR 100' },
  { src: '/assets/scenes/thumbs/alaska-otter.jpg', scene: 8, code: 'COAST 16:08', stock: 'PORTRA 400' },
  { src: '/assets/scenes/thumbs/guangzhou-bus.jpg', scene: 4, code: 'CITY 20:12', stock: 'ULTRAMAX 400' },
  { src: '/assets/scenes/thumbs/alaska-peak.jpg', scene: 6, code: 'PEAK 11:05', stock: 'DOUBLE-X 250', mono: true },
  { src: '/assets/scenes/thumbs/spiral-atrium.jpg', scene: 3, code: 'ATRIUM 12:08', stock: 'GOLD 200' },
];

export function FilmStrip() {
  const { messages } = useLanguage();
  return (
    <div className="filmstrip" aria-label={messages.home.filmstripLabel}>
      <div className="filmstrip-track">
        {frames.map((frame, index) => (
          <figure className="film-frame" key={`${frame.code}-${index}`}>
            <div className="frame-edge"><span>{frame.stock}</span><span>{String(index + 8).padStart(2, '0')}A</span></div>
            <img src={frame.src} alt={`${messages.lab.sceneNames[frame.scene]} · ${frame.stock}${frame.mono ? ' · B&W' : ''}`} width="480" height="270" decoding="async" loading="lazy" style={frame.mono ? { filter: 'grayscale(1) contrast(1.15)' } : undefined} />
            <figcaption><span>{frame.code}</span><span>▸ {String(index + 8).padStart(2, '0')}</span></figcaption>
          </figure>
        ))}
      </div>
    </div>
  );
}
