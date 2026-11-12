import { useEffect } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { useLanguage } from '../i18n/LanguageContext';
import { ALIPAY_PAY, WECHAT_PAY, canDownload, readSupport, writeSupport } from '../support/gate';
import { coffeeCopy } from './coffeeCopy';
import '../styles/coffee.css';

export function CoffeePage() {
  const { locale } = useLanguage();
  const copy = coffeeCopy[locale];
  const navigate = useNavigate();

  useEffect(() => {
    if (canDownload()) navigate('/download', { replace: true });
  }, [navigate]);

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
  const refuse = () => {
    writeSupport('skipped');
    navigate('/download');
  };

  return (
    <main id="main-content" className="coffee-page">
      <p className="coffee-eyebrow">{copy.eyebrow}</p>
      <h1>{copy.title}</h1>
      <p className="coffee-lead">{copy.lead}</p>
      <p className="coffee-amount">{copy.amount}</p>
      <p className="pay-hint">{copy.amountHint}</p>

      <div className="pay-jump">
        <a className="pay-button wechat" href={WECHAT_PAY} onClick={pay}>{copy.wechat}</a>
        <a className="pay-button alipay" href={ALIPAY_PAY} onClick={pay}>{copy.alipay}</a>
        <p className="pay-hint">{copy.wechatHint}</p>
        <div className="pay-fallback">
          <img src="/payments/wechat-card.png" alt={copy.wechat} width="1199" height="1312" />
          <img src="/payments/alipay-card.png" alt={copy.alipay} width="1199" height="1312" />
        </div>
      </div>

      <div className="pay-codes">
        <p>{copy.scan}</p>
        <div>
          <figure>
            <img src="/payments/wechat-card.png" alt={copy.wechat} width="1199" height="1312" />
            <figcaption>{copy.wechat}</figcaption>
          </figure>
          <figure>
            <img src="/payments/alipay-card.png" alt={copy.alipay} width="1199" height="1312" />
            <figcaption>{copy.alipay}</figcaption>
          </figure>
        </div>
      </div>

      <button className="coffee-refuse" type="button" onClick={refuse}>{copy.refuse}</button>
      <Link className="coffee-back" to="/">Silvercam</Link>
    </main>
  );
}
