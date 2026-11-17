import { Link, useNavigate } from 'react-router-dom';
import { useLanguage } from '../i18n/LanguageContext';
import { armDownload } from '../support/gate';
import { coffeeCopy } from './coffeeCopy';
import '../styles/coffee.css';

export function CoffeePage() {
  const { locale } = useLanguage();
  const copy = coffeeCopy[locale];
  const navigate = useNavigate();

  const downloadAnyway = () => {
    armDownload('skipped');
    navigate('/download', { state: { downloadRequested: true } });
  };

  return (
    <main id="main-content" className="coffee-page">
      <p className="coffee-eyebrow">{copy.eyebrow}</p>
      <h1>{copy.title}</h1>
      <p className="coffee-lead">{copy.lead}</p>
      <p className="coffee-amount">{copy.amount}</p>

      <figure className="wechat-card">
        <img src="/payments/wechat-card.png" alt={copy.wechat} width="1076" height="1461" />
      </figure>
      <p className="pay-hint">{copy.scan}</p>

      <button className="coffee-refuse" type="button" onClick={downloadAnyway}>{copy.refuse}</button>
      <Link className="coffee-back" to="/download" state={{ downloadRequested: true }} onClick={() => armDownload('returned')}>{copy.thanksAction}</Link>
    </main>
  );
}
