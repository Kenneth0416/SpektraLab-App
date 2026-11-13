import { useLayoutEffect, useRef } from 'react';
import { ArrowDown, ArrowRight, ArrowUpRight, Camera, Check, Download, Film, Image, Layers3, SlidersHorizontal, Sparkles, Video } from 'lucide-react';
import { Link } from 'react-router-dom';
import { setupHomeMotion } from '../motion/homeMotion';
import { FilmLab } from '../components/FilmLab';
import { FilmStrip } from '../components/FilmStrip';
import { Reveal } from '../components/Reveal';
import { useLanguage } from '../i18n/LanguageContext';

const workflowMedia = [
  { image: '/assets/scenes/thumbs/snow-pines.jpg', icon: Image },
  { image: '/assets/scenes/thumbs/spiral-atrium.jpg', icon: SlidersHorizontal },
  { image: '/assets/scenes/thumbs/woods-cow.jpg', icon: Camera },
];
const mediaIcons = [Image, Video, Camera];

export function HomePage() {
  const { messages, locale } = useLanguage();
  const m = messages.home;
  const mainRef = useRef<HTMLElement>(null);

  useLayoutEffect(() => {
    if (!mainRef.current) return;
    return setupHomeMotion(mainRef.current);
  }, []);

  return (
    <main id="main-content" ref={mainRef}>
      <section
        className="hero"
        onPointerMove={(event) => {
          const bounds = event.currentTarget.getBoundingClientRect();
          event.currentTarget.style.setProperty('--pointer-x', `${event.clientX - bounds.left}px`);
          event.currentTarget.style.setProperty('--pointer-y', `${event.clientY - bounds.top}px`);
        }}
      >
        <div className="hero-ambient" aria-hidden="true" />
        <div className="hero-grid">
          <div className="hero-copy">
            <div className="hero-eyebrow"><span className="safety-dot" />{m.heroEyebrow}</div>
            <h1>{m.title1}<br />{m.title2}{locale === 'en' ? ' ' : null}<span className="memory-word">{m.memory}<svg viewBox="0 0 240 18" aria-hidden="true"><path d="M4 12C55 2 112 18 235 6" /></svg></span></h1>
            <p className="hero-intro">{m.intro}</p>
            <div className="hero-actions button-group">
              <Link className="button button-primary" to="/download"><Download size={18} /><span className="button-label">{m.download}</span><ArrowRight size={17} /></Link>
              <a className="button button-ghost" href="#craft"><span className="button-label">{m.explore}</span><ArrowDown size={17} /></a>
            </div>
            <div className="hero-specs" aria-label={m.specsLabel}>
              <div><strong>06</strong><span>{m.specs[0]}</span></div>
              <div><strong>05</strong><span>{m.specs[1]}</span></div>
              <div><strong>∞</strong><span>{m.specs[2]}</span></div>
            </div>
            <p className="prototype-note"><span />{m.prototypeNote}</p>
          </div>
          <div className="hero-lab" id="darkroom">
            <div className="lab-orbit orbit-one" aria-hidden="true" />
            <div className="lab-orbit orbit-two" aria-hidden="true" />
            <FilmLab />
            <div className="recipe-note" aria-hidden="true"><span>{messages.lab.recipeTitle}</span><strong>{messages.lab.recipeName}</strong><i>{messages.lab.recipeTime}</i></div>
          </div>
        </div>
        <div className="hero-side-type" aria-hidden="true">35MM · PROCESS ECN-2 · HANDLE WITH CARE</div>
      </section>

      <FilmStrip />

      <section className="craft-section" id="craft">
        <div className="section-inner">
          <Reveal className="craft-heading">
            <div className="section-kicker"><span>{m.craftKicker}</span><i /></div>
            <h2>{m.craftTitle1}<br />{m.craftTitle2}<span>{m.craftAccent}</span></h2>
            <p>{m.craftDescription}</p>
          </Reveal>

          <div className="craft-grid">
            <Reveal className="craft-card film-card">
              <div className="card-heading"><div><Film size={21} /><span>FILM STOCKS</span></div><span>{m.filmOverline}</span></div>
              <h3>{m.filmTitle1}<br />{m.filmTitle2}</h3>
              <div className="film-stock-list">
                {[
                  ['PORTRA', '400', '#edc58e'], ['EKTAR', '100', '#ff684f'], ['GOLD', '200', '#f2ba42'],
                  ['ULTRAMAX', '400', '#6bb9ed'], ['VISION3', '500T', '#88aef2'], ['DOUBLE-X', '250', '#d9d5ca'],
                ].map(([name, iso, color]) => (
                  <div key={name} style={{ '--stock-color': color } as React.CSSProperties}><i /><strong>{name}</strong><span>ISO {iso}</span></div>
                ))}
              </div>
              <p>{m.filmDescription}</p>
            </Reveal>

            <Reveal className="craft-card paper-card" delay={90}>
              <div className="card-heading"><div><Layers3 size={21} /><span>FILM × PAPER</span></div><span>{m.paperOverline}</span></div>
              <div className="paper-equation"><span>FILM</span><b>×</b><span>PAPER</span></div>
              <h3>{m.paperTitle}</h3>
              <p>{m.paperDescription}</p>
              <div className="paper-swatches" aria-hidden="true"><i /><i /><i /><i /></div>
            </Reveal>

            <Reveal className="craft-card texture-card">
              <img src="/assets/scenes/thumbs/spiral-atrium.jpg" alt={m.textureAlt} width="480" height="317" decoding="async" loading="lazy" />
              <div className="texture-wash" aria-hidden="true" />
              <div className="texture-content">
                <div className="card-heading"><div><Sparkles size={21} /><span>TEXTURE & LIGHT</span></div><span>{m.textureOverline}</span></div>
                <div><h3>{m.textureTitle}</h3><p>{m.textureDescription}</p></div>
              </div>
            </Reveal>

            <Reveal className="craft-card recipe-card" delay={90}>
              <div className="card-heading"><div><SlidersHorizontal size={21} /><span>YOUR RECIPE</span></div><span>{m.recipeOverline}</span></div>
              <div className="index-card">
                <span className="index-tape" aria-hidden="true" />
                <small>{m.recipeCode}</small>
                <strong>{m.recipeName}</strong>
                <p>{m.recipeStock}</p>
                <dl><div><dt>{m.recipeLabels[0]}</dt><dd>1.6</dd></div><div><dt>{m.recipeLabels[1]}</dt><dd>CineBloom</dd></div><div><dt>{m.recipeLabels[2]}</dt><dd>XPan</dd></div></dl>
                <span className="hand-check">{m.recipeHandnote}</span>
              </div>
              <p className="recipe-caption">{m.recipeCaption}</p>
            </Reveal>
          </div>
        </div>
      </section>

      <section className="workflow-section" id="workflow">
        <div className="section-inner">
          <Reveal className="workflow-heading">
            <div><span className="section-number">{m.workflowKicker}</span><h2>{m.workflowTitle1}<br /><em>{m.workflowTitle2}</em></h2></div>
            <p>{m.workflowDescription}</p>
          </Reveal>
          <div className="workflow-grid">
            {m.steps.map((step, index) => {
              const Icon = workflowMedia[index].icon;
              return (
                <Reveal className="workflow-step" key={step.subtitle} delay={index * 100}>
                  <div className="workflow-image">
                    <img src={workflowMedia[index].image} alt="" width="480" height="270" decoding="async" loading="lazy" />
                    <span>0{index + 1}</span>
                    <div className="workflow-icon"><Icon size={23} /></div>
                  </div>
                  <span className="workflow-subtitle">{step.subtitle}</span>
                  <h3>{step.title}</h3>
                  <p>{step.copy}</p>
                </Reveal>
              );
            })}
          </div>
          <Reveal className="format-rail">
            <span>{m.formatPrompt}</span>
            <div><strong>HALF</strong><strong>135</strong><strong>XPan</strong><strong>6×6</strong><strong>6×7</strong></div>
            <span><Check size={15} />{m.formatNote}</span>
          </Reveal>
        </div>
      </section>

      <section className="media-section">
        <div className="section-inner media-grid">
          <Reveal className="media-copy">
            <span className="section-number">{m.mediaKicker}</span>
            <h2>{m.mediaTitle1}<br />{m.mediaTitle2}</h2>
            <p>{m.mediaDescription}</p>
            <ul>{m.mediaFeatures.map((feature, index) => { const Icon = mediaIcons[index]; return <li key={feature}><Icon size={17} />{feature}</li>; })}</ul>
          </Reveal>
          <Reveal className="media-visual" delay={100}>
            <div className="media-frame media-photo"><img src="/assets/scenes/thumbs/snow-tree.jpg" alt={m.mediaPhotoAlt} width="480" height="222" decoding="async" loading="lazy" /><span>{m.mediaPhotoLabel}</span></div>
            <div className="media-frame media-video"><img src="/assets/scenes/thumbs/snow-pines.jpg" alt={m.mediaVideoAlt} width="480" height="222" decoding="async" loading="lazy" /><span><i />{m.mediaVideoLabel}</span><div className="video-progress"><i /></div></div>
            <span className="media-handnote">{m.mediaHandnote}</span>
          </Reveal>
        </div>
      </section>

      <section className="download-cta">
        <div className="cta-safety-light" aria-hidden="true" />
        <Reveal className="cta-inner">
          <div className="cta-topline"><span className="safety-dot" />{m.ctaKicker}</div>
          <h2>{m.ctaTitle1}<br />{m.ctaTitle2}</h2>
          <p>{m.ctaDescription1}<br />{m.ctaDescription2}</p>
          <div className="cta-actions button-group">
            <Link className="button button-light" to="/download"><Download size={19} /><span className="button-label">{m.ctaDownload}</span><ArrowRight size={18} /></Link>
            <a className="button button-ghost" href="https://github.com/Kenneth0416/SpektraLab-App" target="_blank" rel="noreferrer"><span className="button-label">{m.ctaGithub}</span><ArrowUpRight size={17} /></a>
          </div>
          <span className="cta-note">{m.ctaNote}</span>
        </Reveal>
        <div className="cta-watermark" aria-hidden="true">SILVERCAM</div>
      </section>
    </main>
  );
}
