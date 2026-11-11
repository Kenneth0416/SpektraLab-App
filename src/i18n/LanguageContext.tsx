import { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react';
import type { ReactNode } from 'react';
import { useLocation, useSearchParams } from 'react-router-dom';
import { localeOptions, translations } from './translations';
import type { Locale, Messages } from './translations';

const STORAGE_KEY = 'silvercam-language';
const LEGACY_STORAGE_KEY = 'spektralab-language';

function parseLocale(value: string | null | undefined): Locale | null {
  if (!value) return null;
  const normalized = value.toLowerCase();
  if (['zh-hant', 'zh-tw', 'zh-hk', 'zh-mo', 'tc'].includes(normalized)) return 'zh-Hant';
  if (['zh-hans', 'zh-cn', 'zh-sg', 'zh', 'sc'].includes(normalized)) return 'zh-Hans';
  if (normalized === 'en' || normalized.startsWith('en-')) return 'en';
  return null;
}

function getInitialLocale(): Locale {
  const fromUrl = parseLocale(new URLSearchParams(window.location.search).get('lang'));
  if (fromUrl) return fromUrl;
  try {
    const saved = parseLocale(
      window.localStorage.getItem(STORAGE_KEY) ||
      window.localStorage.getItem(LEGACY_STORAGE_KEY)
    );
    if (saved) return saved;
  } catch { /* Storage may be disabled. */ }
  for (const language of navigator.languages) {
    const detected = parseLocale(language);
    if (detected) return detected;
  }
  return 'zh-Hant';
}

type LanguageContextValue = {
  locale: Locale;
  messages: Messages;
  setLocale: (locale: Locale) => void;
};

const LanguageContext = createContext<LanguageContextValue | null>(null);

export function LanguageProvider({ children }: { children: ReactNode }) {
  const [searchParams, setSearchParams] = useSearchParams();
  const location = useLocation();
  const [locale, setLocaleState] = useState<Locale>(getInitialLocale);
  const queryLocale = parseLocale(searchParams.get('lang'));

  useEffect(() => {
    if (queryLocale) setLocaleState((current) => current === queryLocale ? current : queryLocale);
  }, [queryLocale]);

  useEffect(() => {
    if (!queryLocale) {
      const nextParams = new URLSearchParams(location.search);
      nextParams.set('lang', locale);
      setSearchParams(nextParams, { replace: true });
    }
  }, [location.pathname, location.search, queryLocale, locale, setSearchParams]);

  useEffect(() => {
    document.documentElement.lang = locale;
    document.documentElement.dataset.locale = locale;
    const description = document.querySelector<HTMLMetaElement>('meta[name="description"]');
    const ogDescription = document.querySelector<HTMLMetaElement>('meta[property="og:description"]');
    description?.setAttribute('content', translations[locale].meta.description);
    ogDescription?.setAttribute('content', translations[locale].meta.description);
    try { window.localStorage.setItem(STORAGE_KEY, locale); } catch { /* Storage may be disabled. */ }
  }, [locale]);

  const setLocale = useCallback((nextLocale: Locale) => {
    setLocaleState(nextLocale);
    const nextParams = new URLSearchParams(window.location.search);
    nextParams.set('lang', nextLocale);
    setSearchParams(nextParams, { replace: true });
  }, [setSearchParams]);

  const value = useMemo(() => ({ locale, messages: translations[locale], setLocale }), [locale, setLocale]);
  return <LanguageContext.Provider value={value}>{children}</LanguageContext.Provider>;
}

export function useLanguage() {
  const context = useContext(LanguageContext);
  if (!context) throw new Error('useLanguage must be used within LanguageProvider');
  return context;
}

export function getLocaleLabel(locale: Locale) {
  return localeOptions.find((option) => option.id === locale)?.fullLabel ?? locale;
}
