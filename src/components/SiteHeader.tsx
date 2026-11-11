import { useEffect, useState } from 'react';
import { Download, Menu, X } from 'lucide-react';
import { Link, NavLink, useLocation } from 'react-router-dom';
import { useLanguage } from '../i18n/LanguageContext';
import { LanguageSwitcher } from './LanguageSwitcher';
import { Logo } from './Logo';

export function SiteHeader() {
  const [open, setOpen] = useState(false);
  const location = useLocation();
  const { messages } = useLanguage();
  const navigation = [
    { label: messages.nav.lab, to: '/#darkroom' },
    { label: messages.nav.craft, to: '/#craft' },
    { label: messages.nav.workflow, to: '/#workflow' },
    { label: messages.nav.credits, to: '/credits' },
  ];

  useEffect(() => setOpen(false), [location.pathname, location.search, location.hash]);
  useEffect(() => {
    document.body.classList.toggle('menu-open', open);
    const closeOnEscape = (event: KeyboardEvent) => {
      if (event.key === 'Escape') setOpen(false);
    };
    if (open) document.addEventListener('keydown', closeOnEscape);
    return () => {
      document.body.classList.remove('menu-open');
      document.removeEventListener('keydown', closeOnEscape);
    };
  }, [open]);

  return (
    <header className="site-header" onClick={(event) => {
      if (event.target instanceof Element && event.target.closest('a')) setOpen(false);
    }}>
      <div className="header-inner">
        <Logo />
        <nav className="desktop-nav" aria-label={messages.nav.mainNavigation}>
          {navigation.map((item) => <Link key={item.to} to={item.to}>{item.label}</Link>)}
        </nav>
        <div className="header-actions">
          <LanguageSwitcher />
          <NavLink className={({ isActive }) => `header-download ${isActive ? 'active' : ''}`} to="/download">
            <Download size={16} strokeWidth={1.8} />
            <span>{messages.nav.download}</span>
          </NavLink>
          <button
            className="menu-toggle"
            type="button"
            aria-label={open ? messages.nav.close : messages.nav.open}
            aria-expanded={open}
            aria-controls="mobile-navigation"
            onClick={() => setOpen((value) => !value)}
          >
            {open ? <X size={23} /> : <Menu size={23} />}
          </button>
        </div>
      </div>
      <nav id="mobile-navigation" className={`mobile-nav ${open ? 'is-open' : ''}`} aria-label={messages.nav.mobileNavigation}>
        {navigation.map((item, index) => (
          <Link key={item.to} to={item.to}>
            <span>0{index + 1}</span>{item.label}
          </Link>
        ))}
        <Link className="mobile-download" to="/download"><Download size={18} />{messages.nav.mobileDownload}</Link>
      </nav>
    </header>
  );
}
