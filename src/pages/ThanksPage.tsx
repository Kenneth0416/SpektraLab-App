import { useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import { useLanguage } from '../i18n/LanguageContext';
import { readSupport, writeSupport } from '../support/gate';
import { coffeeCopy } from './coffeeCopy';
import '../styles/coffee.css';

export function ThanksPage() {
  const { locale } = useLanguage();
  const copy = coffeeCopy[locale];
  const navigate = useNavigate();

  useEffect(() => {
    const state = readSupport();
    if (state !== 'awaiting' && state !== 'returned') {
      navigate('/coffee', { replace: true });
      return;
    }
    writeSupport('returned');
    const timer = window.setTimeout(() => navigate('/download', { replace: true }), 1600);
    return () => window.clearTimeout(timer);
  }, [navigate]);

  return (
    <main id="main-content" className="coffee-page thanks-page">
      <p className="coffee-eyebrow">Silvercam</p>
      <h1>{copy.thanksTitle}</h1>
      <p className="coffee-lead">{copy.thanksLead}</p>
      <button className="pay-button wechat" type="button" onClick={() => navigate('/download')}>{copy.thanksAction}</button>
    </main>
  );
}
