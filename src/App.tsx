import { lazy, Suspense, useEffect } from 'react';
import { Route, Routes, useLocation } from 'react-router-dom';
import { SiteHeader } from './components/SiteHeader';
import { SiteFooter } from './components/SiteFooter';
import { HomePage } from './pages/HomePage';
import { useLanguage } from './i18n/LanguageContext';

const DownloadPage = lazy(() => import('./pages/DownloadPage').then((module) => ({ default: module.DownloadPage })));
const CoffeePage = lazy(() => import('./pages/CoffeePage').then((module) => ({ default: module.CoffeePage })));
const ThanksPage = lazy(() => import('./pages/ThanksPage').then((module) => ({ default: module.ThanksPage })));
const CreditsPage = lazy(() => import('./pages/CreditsPage').then((module) => ({ default: module.CreditsPage })));

function RouteEffects() {
  const location = useLocation();
  const { messages, locale } = useLanguage();

  useEffect(() => {
    let page = messages.meta.homeTitle;
    if (location.pathname === '/download') {
      page = messages.meta.downloadTitle;
    } else if (location.pathname === '/coffee') {
      page = locale === 'en' ? 'Buy me a coffee' : locale === 'zh-Hans' ? '请我喝一杯' : '請我喝一杯';
    } else if (location.pathname === '/thanks') {
      page = locale === 'en' ? 'Thank you' : locale === 'zh-Hans' ? '感谢支持' : '感謝支持';
    } else if (location.pathname === '/credits' || location.pathname === '/licenses') {
      page = messages.meta.creditsTitle;
    }
    const brand = locale === 'zh-Hans' ? '银盐工坊' : locale === 'zh-Hant' ? '銀鹽工坊' : 'Silvercam';
    document.title = `${page} — ${brand}`;
    document.querySelector<HTMLMetaElement>('meta[property="og:title"]')?.setAttribute('content', `${page} — ${brand}`);
    if (location.hash) {
      const frame = requestAnimationFrame(() => {
        let id = location.hash.slice(1);
        try { id = decodeURIComponent(id); } catch { /* Keep malformed escapes as literal IDs. */ }
        document.getElementById(id)?.scrollIntoView({ behavior: 'smooth' });
      });
      return () => cancelAnimationFrame(frame);
    } else {
      window.scrollTo({ top: 0, behavior: 'instant' });
    }
  }, [location.pathname, location.hash, messages, locale]);

  return null;
}

function SkipLink() {
  const { messages } = useLanguage();
  return <a className="skip-link" href="#main-content">{messages.nav.skip}</a>;
}

export default function App() {
  return (
    <div className="site-shell">
      <SkipLink />
      <div className="scroll-progress" aria-hidden="true" />
      <RouteEffects />
      <SiteHeader />
      <Suspense fallback={<div className="route-fallback" />}>
        <Routes>
          <Route path="/" element={<HomePage />} />
          <Route path="/coffee" element={<CoffeePage />} />
          <Route path="/thanks" element={<ThanksPage />} />
          <Route path="/download" element={<DownloadPage />} />
          <Route path="/credits" element={<CreditsPage />} />
          <Route path="/licenses" element={<CreditsPage />} />
          <Route path="*" element={<HomePage />} />
        </Routes>
      </Suspense>
      <SiteFooter />
    </div>
  );
}
