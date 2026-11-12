import { useEffect, useRef, useState } from 'react';
import { ArrowLeft, ArrowRight, Check, CheckCircle2, Clipboard, Copy, Download, FileArchive, FolderOpen, Github, Image, ShieldCheck, SlidersHorizontal, Smartphone, Terminal } from 'lucide-react';
import { Link } from 'react-router-dom';
import { downloadMeta } from '../generated/download-meta';
import { Reveal } from '../components/Reveal';
import { useLanguage } from '../i18n/LanguageContext';

type CopyTarget = 'android' | 'prototype' | 'clone';

async function copyText(text: string) {
  if (navigator.clipboard && window.isSecureContext) {
    try {
      await navigator.clipboard.writeText(text);
      return;
    } catch { /* Try the legacy copy path when clipboard permission is denied. */ }
  }
  const previousFocus = document.activeElement;
  const field = document.createElement('textarea');
  field.value = text;
  field.style.position = 'fixed';
  field.style.opacity = '0';
  document.body.appendChild(field);
  try {
    field.select();
    if (!document.execCommand('copy')) throw new Error('Copy failed');
  } finally {
    field.remove();
    if (previousFocus instanceof HTMLElement) previousFocus.focus({ preventScroll: true });
  }
}

const packageIcons = [Smartphone, SlidersHorizontal, Image];
const packagePaths = ['Offline WebView · APK', 'Film · Exposure · Grain · Halation', '4 SVG scenes · Grain texture'];

export function DownloadPage() {
  const { messages } = useLanguage();
  const m = messages.download;
  const apk = downloadMeta.android;
  const hasApk = Boolean(apk);
  const [copied, setCopied] = useState<CopyTarget | null>(null);
  const [copyFailed, setCopyFailed] = useState<CopyTarget | null>(null);
  const [downloaded, setDownloaded] = useState(false);
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null);

  useEffect(() => () => {
    if (timer.current) clearTimeout(timer.current);
  }, []);

  const handleCopy = async (target: CopyTarget, value: string) => {
    if (timer.current) clearTimeout(timer.current);
    setCopied(null);
    setCopyFailed(null);
    try {
      await copyText(value);
      setCopied(target);
      timer.current = setTimeout(() => setCopied(null), 2200);
    } catch {
      setCopyFailed(target);
    }
  };

  return (
    <main id="main-content" className="download-page">
      <section className="download-hero">
        <div className="download-orb" aria-hidden="true" />
        <div className="download-hero-inner">
          <div className="download-intro">
            <Link className="back-link" to="/"><ArrowLeft size={16} />{m.back}</Link>
            <div className="hero-eyebrow"><span className="safety-dot" />{m.eyebrow}</div>
            <h1>{m.title1}<br />{m.title2}<span>{m.titleAccent}</span></h1>
            <p>{m.intro}</p>
            <div className="download-assurances">
              {m.assurances.map((item) => <span key={item}><CheckCircle2 size={16} />{item}</span>)}
            </div>
            <div className="clone-box">
              <div><Github size={18} /><span>{m.cloneLabel}</span></div>
              <code>git clone https://github.com/Kenneth0416/SpektraLab-App.git</code>
              <button type="button" onClick={() => handleCopy('clone', 'git clone https://github.com/Kenneth0416/SpektraLab-App.git')} aria-label={m.copyClone}>
                {copied === 'clone' ? <Check size={17} /> : <Copy size={17} />}<span>{copied === 'clone' ? m.copied : m.copy}</span>
              </button>
            </div>
            {copyFailed === 'clone' && <p role="alert">{m.copyFailed}</p>}
            <a className="source-link" href={downloadMeta.source.href} download={downloadMeta.source.filename} aria-label={`${m.sourceTitle} ${m.sourceDescription}`}>
              <FileArchive size={18} /><span><small>{m.sourceKicker}</small>{m.sourceDownload}</span><strong>{downloadMeta.source.size}</strong>
            </a>
          </div>

          <div className="download-ticket">
            <div className="ticket-topline"><span><i />{m.packageReady}</span><span>APK / ANDROID</span></div>
            <div className="ticket-icon"><Smartphone size={42} strokeWidth={1.35} /><span>SC</span></div>
            <span className="ticket-label">{m.ticketLabel}</span>
            <h2>{m.brandName}<br />{m.ticketTitle}</h2>
            <p>{m.ticketDescription}</p>
            <div className="ticket-stats">
              <div><span>{m.stats[0]}</span><strong>{apk?.size ?? '—'}</strong></div>
              <div><span>{m.stats[1]}</span><strong>Android {apk?.minAndroid ?? '8.0+'}</strong></div>
              <div><span>{m.stats[2]}</span><strong>.APK</strong></div>
            </div>
            {hasApk ? (
              <a className="ticket-download" href={apk?.href} download={apk?.filename} onClick={() => setDownloaded(true)}>
                <Download size={21} /><span>{m.downloadFull}<small>{apk?.filename}</small></span><ArrowRight size={20} />
              </a>
            ) : (
              <button className="ticket-download" type="button" disabled><Download size={21} /><span>{m.downloadFull}<small>Silvercam-Android.apk</small></span></button>
            )}
            <div className="ticket-checksum">
              <span>{m.checksum}</span>
              <code title={apk?.sha256 ?? ''}>{apk ? `${apk.sha256.slice(0, 12)}···${apk.sha256.slice(-8)}` : '—'}</code>
              <button type="button" disabled={!hasApk} onClick={() => handleCopy('android', apk?.sha256 ?? '')} aria-label={copied === 'android' ? m.hashCopied : m.copyHash}>
                {copied === 'android' ? <Check size={15} /> : <Clipboard size={15} />}
              </button>
            </div>
            {copyFailed === 'android' && <p role="alert">{m.copyFailed}</p>}
            <div className="ticket-status" role="status">{!hasApk ? <><ShieldCheck size={15} />{m.apkUnavailable}</> : downloaded ? <><CheckCircle2 size={15} />{m.statusStarted}</> : <><ShieldCheck size={15} />{m.statusReady}</>}</div>
          </div>
        </div>
        <div className="download-hero-footer">
          <span>{m.heroFooterLeft}</span>
          <span>{m.heroFooterRight} <ArrowRight size={14} /></span>
        </div>
      </section>

      <section className="package-section">
        <div className="section-inner">
          <Reveal className="package-heading">
            <span className="section-number">{m.packageKicker}</span>
            <h2>{m.packageTitle1}<br />{m.packageTitle2}</h2>
            <p>{m.packageDescription}</p>
          </Reveal>
          <div className="package-contents">
            {m.packageItems.map((item, index) => {
              const Icon = packageIcons[index];
              return (
                <Reveal className="package-item" key={item.category} delay={index * 80}>
                  <div className="package-item-icon"><Icon size={25} /></div><span>{item.category}</span>
                  <h3>{item.title}</h3><p>{item.description}</p>
                  <code>{packagePaths[index]}</code>
                </Reveal>
              );
            })}
          </div>

          <Reveal className="prototype-download">
            <div className="prototype-file-icon"><FileArchive size={29} /></div>
            <div className="prototype-file-copy"><span>{m.prototypeKicker}</span><h3>{m.prototypeTitle}</h3><p>{m.prototypeDescription}</p></div>
            <div className="prototype-file-meta"><strong>{downloadMeta.prototype.size}</strong><span>{downloadMeta.prototype.fileCount} {m.prototypeFiles}</span></div>
            <a className="button button-dark" href={downloadMeta.prototype.href} download={downloadMeta.prototype.filename}><Download size={17} />{m.prototypeDownload}</a>
            <button className="prototype-hash" type="button" onClick={() => handleCopy('prototype', downloadMeta.prototype.sha256)} title={m.copyPrototypeHash}>
              {copied === 'prototype' ? <Check size={14} /> : <Clipboard size={14} />}{copied === 'prototype' ? m.prototypeHashCopied : m.copyPrototypeHash}
            </button>
          </Reveal>
          {copyFailed === 'prototype' && <p role="alert">{m.copyFailed}</p>}
        </div>
      </section>

      <section className="quickstart-section">
        <div className="section-inner quickstart-grid">
          <Reveal className="quickstart-copy">
            <span className="section-number">{m.quickKicker}</span>
            <h2>{m.quickTitle1}<br />{m.quickTitle2}</h2>
            <p>{m.quickDescription1} <code>Silvercam-Android.apk</code>{m.quickDescription2}</p>
            <div className="system-note"><Terminal size={18} /><span>{m.systemNote}</span></div>
          </Reveal>
          <Reveal className="terminal-window" delay={100}>
            <div className="terminal-top"><div><i /><i /><i /></div><span>silvercam — android</span><Terminal size={16} /></div>
            <div className="terminal-body">
              {m.quickSteps.map((step, index) => (
                <div key={step}><span>0{index + 1}</span><p><small>{step}</small><code>{m.quickActions[index]}</code></p></div>
              ))}
              <div className="terminal-success"><CheckCircle2 size={17} /><span>{m.terminalSuccess}</span></div>
            </div>
          </Reveal>
        </div>
      </section>

      <section className="notes-section">
        <div className="section-inner notes-grid">
          <Reveal className="honesty-card">
            <div className="honesty-icon"><ShieldCheck size={27} /></div>
            <span>{m.honestyKicker}</span>
            <h2>{m.honestyTitle1}<br />{m.honestyTitle2}</h2>
            <p>{m.honestyDescription}</p>
            <ul>{m.honestyChecks.map((item) => <li key={item}><Check size={16} />{item}</li>)}</ul>
          </Reveal>
          <Reveal className="faq-list" delay={100}>
            <span className="section-number">{m.faqKicker}</span>
            <h2>{m.faqTitle}</h2>
            {m.faqs.map((item, index) => <details key={item.q} open={index === 0}><summary>{item.q}<span>＋</span></summary><p>{item.a}</p></details>)}
          </Reveal>
        </div>
      </section>

      <section className="download-bottom">
        <Reveal className="download-bottom-inner">
          <FolderOpen size={35} strokeWidth={1.4} />
          <h2>{m.bottomTitle}</h2>
          <p>{apk?.size ?? '—'} · {m.bottomMeta}</p>
          {hasApk && <a className="button button-light" href={apk?.href} download={apk?.filename}><Download size={18} />{m.downloadPrefix} {apk?.filename}</a>}
          <div style={{ display: 'flex', gap: '20px', flexWrap: 'wrap', justifyContent: 'center', marginTop: '10px' }}>
            <Link to="/">{m.backHome}<ArrowRight size={16} /></Link>
            <Link to="/credits" style={{ color: '#bbb3a6', textDecoration: 'underline' }}>{m.viewCredits}</Link>
          </div>
        </Reveal>
      </section>
    </main>
  );
}
