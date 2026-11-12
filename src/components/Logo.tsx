import { Link } from 'react-router-dom';
import { useLanguage } from '../i18n/LanguageContext';

type LogoProps = { compact?: boolean };

export function Logo({ compact = false }: LogoProps) {
  const { messages, locale } = useLanguage();
  return (
    <Link className="brand" to="/" aria-label={messages.nav.home}>
      <svg className="brand-mark" viewBox="0 0 40 40" aria-hidden="true">
        <rect x="1" y="1" width="38" height="38" rx="8" fill="currentColor" />
        <path d="M10 29V11h4v18z" fill="#f05a40" />
        <path d="M17 29V11h4v18z" fill="#e8b34e" />
        <path d="M24 29V11h4v18z" fill="#90ad96" />
        <path d="M8 7h22M8 33h22" stroke="#f3efe5" strokeWidth="1.5" />
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
