import { useEffect } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { useLanguage } from '../i18n/LanguageContext';
import { ALIPAY_PAY, WECHAT_PAY, armDownload, readSupport, writeSupport } from '../support/gate';
import { coffeeCopy } from './coffeeCopy';
import '../styles/coffee.css';

export function CoffeePage() {
  const { locale } = useLanguage();
  const copy = coffeeCopy[locale];
  const navigate = useNavigate();

  useEffect(() => {
    const returned = () => {
      if (document.visibilityState !== 'visible') return;
      if (readSupport() === 'awaiting') navigate('/thanks');
    };
    document.addEventListener('visibilitychange', returned);
    window.addEventListener('pageshow', returned);
    return () => {
      document.removeEventListener('visibilitychange', returned);
      window.removeEventListener('pageshow', returned);
    };
  }, [navigate]);

  const pay = () => writeSupport('awaiting');
  const downloadAnyway = () => {
    armDownload('skipped');
    navigate('/download');
  };

  return (
    <main id="main-content" className="coffee-page">
      <p className="coffee-eyebrow">{copy.eyebrow}</p>
      <h1>{copy.title}</h1>
      <p className="coffee-lead">{copy.lead}</p>
      <p className="coffee-amount">{copy.amount}</p>

      <div className="pay-jump">
        <div className="pay-actions">
          <a className="pay-button wechat" href={WECHAT_PAY} onClick={pay}><span className="button-label">{copy.wechat}</span></a>
          <a className="pay-button alipay" href={ALIPAY_PAY} onClick={pay}><span className="button-label">{copy.alipay}</span></a>
        </div>
        <p className="pay-hint">{copy.wechatHint}</p>
        <div className="pay-fallback">
          <img src="/payments/wechat-card.png" alt={copy.wechat} width="1076" height="1461" />
          <img src="/payments/alipay-card.png" alt={copy.alipay} width="1024" height="1536" />
        </div>
      </div>

      <div className="pay-codes">
        <p>{copy.scan}</p>
        <div>
          <figure>
            <img src="/payments/wechat-card.png" alt={copy.wechat} width="1076" height="1461" />
            <figcaption>{copy.wechat}</figcaption>
          </figure>
          <figure>
            <img src="/payments/alipay-card.png" alt={copy.alipay} width="1024" height="1536" />
            <figcaption>{copy.alipay}</figcaption>
          </figure>
        </div>
      </div>

      <button className="coffee-refuse" type="button" onClick={downloadAnyway}>{copy.refuse}</button>
      <Link className="coffee-back" to="/download">{copy.thanksAction}</Link>
    </main>
  );
}
