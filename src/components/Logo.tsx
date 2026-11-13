import { Link } from 'react-router-dom';
import { useLanguage } from '../i18n/LanguageContext';

type LogoProps = { compact?: boolean };

export function Logo({ compact = false }: LogoProps) {
  const { messages, locale } = useLanguage();
  return (
    <Link className="brand" to="/" aria-label={messages.nav.home}>
      <svg className="brand-mark" viewBox="0 0 40 40" aria-hidden="true">
        <rect x="1" y="1" width="38" height="38" rx="8" fill="#131315" />
        <polygon points="11.04,8.12 16.62,8.12 8.04,22.99 5.25,18.16" fill="#f0c542" />
        <polygon points="17.73,8.12 28.95,8.12 34.75,18.17 31.94,23.03 26.22,13.13 14.84,13.13" fill="#cb3272" />
        <polygon points="8.16,23.21 25.11,23.21 22.09,28.44 11.18,28.44" fill="#29a1c3" />
        <polygon points="28.58,19.02 31.4,23.9 26.22,32.88 20.57,32.88" fill="#29a1c3" />
        <rect x="14.29" y="14.04" width="11.44" height="8.28" fill="#ecf1f4" />
      </svg>
      {!compact && (
        <span className="brand-type">
          <strong>SILVER<span>CAM</span></strong>
          <small>{locale === 'zh-Hans' ? '银盐工坊 · 数字暗房' : locale === 'zh-Hant' ? '銀鹽工坊 · 數位暗房' : 'DIGITAL DARKROOM'}</small>
        </span>
      )}
    </Link>
  );
}
