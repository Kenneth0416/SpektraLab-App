import { ChevronDown, Languages } from 'lucide-react';
import { localeOptions } from '../i18n/translations';
import type { Locale } from '../i18n/translations';
import { useLanguage } from '../i18n/LanguageContext';

export function LanguageSwitcher() {
  const { locale, messages, setLocale } = useLanguage();

  return (
    <label className="language-switcher">
      <Languages size={15} aria-hidden="true" />
      <span className="sr-only">{messages.nav.language}</span>
      <select value={locale} onChange={(event) => setLocale(event.target.value as Locale)} aria-label={messages.nav.language}>
        {localeOptions.map((option) => <option key={option.id} value={option.id}>{option.label}</option>)}
      </select>
      <ChevronDown size={12} aria-hidden="true" />
    </label>
  );
}
