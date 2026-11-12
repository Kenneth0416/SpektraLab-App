import { ArrowUpRight, Github } from 'lucide-react';
import { Link } from 'react-router-dom';
import { Logo } from './Logo';
import { useLanguage } from '../i18n/LanguageContext';

export function SiteFooter() {
  const { messages } = useLanguage();
  return (
    <footer className="site-footer">
      <div className="footer-main">
        <div className="footer-brand">
          <Logo />
          <p>{messages.footer.tagline}</p>
          <p style={{ marginTop: '14px', fontSize: '11px', color: '#7a7469', lineHeight: '1.6', maxWidth: '340px' }}>
            {messages.footer.disclaimer}
          </p>
        </div>
        <div className="footer-links">
          <div>
            <span>{messages.footer.explore}</span>
            <Link to="/#darkroom">{messages.footer.lab}</Link>
            <Link to="/#craft">{messages.footer.craft}</Link>
            <Link to="/download">{messages.footer.download}</Link>
            <Link to="/credits">{messages.footer.credits}</Link>
          </div>
          <div>
            <span>{messages.footer.source}</span>
            <a href="https://github.com/Kenneth0416/SpektraLab-App" target="_blank" rel="noreferrer">
              <Github size={16} />GitHub<ArrowUpRight size={14} />
            </a>
            <a href="https://github.com/andreavolpato/spektrafilm" target="_blank" rel="noreferrer">
              spektrafilm<ArrowUpRight size={14} />
            </a>
            <a href="https://github.com/JamesQiu2005/SpektraLab" target="_blank" rel="noreferrer">
              SpektraLab<ArrowUpRight size={14} />
            </a>
            <Link to="/credits">{messages.footer.licenses}</Link>
          </div>
        </div>
      </div>
      <div className="footer-bottom">
        <span>{messages.footer.status}</span>
        <span>{messages.footer.note}</span>
      </div>
    </footer>
  );
}
