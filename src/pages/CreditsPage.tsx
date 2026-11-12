import { useState } from 'react';
import { ArrowLeft, ArrowUpRight, Check, CheckCircle2, ChevronDown, ChevronUp, Code, Download, ExternalLink, FileText, HeartHandshake, Layers3, ShieldCheck, Sparkles } from 'lucide-react';
import { Link } from 'react-router-dom';
import { useLanguage } from '../i18n/LanguageContext';
import { downloadMeta } from '../generated/download-meta';
import { Reveal } from '../components/Reveal';

import gplNoticeText from '../../LICENSE?raw';

const preambleNoticeText = `================================================================================
        License for spektrafilm profiles, LUTs, and direct derivatives
                          by Andrea Volpato
            License: CC BY-SA 4.0 | Preamble v1.1 (2026-05-28)
================================================================================

This license applies to the original spektrafilm profiles that can be found at
https://github.com/andreavolpato/spektrafilm, in the subfolder
src/spektrafilm/data/profiles, and to all direct derivatives of the profiles,
such as copies in other projects, LUTs, or any other format that encodes the
same content.

LUTs and similar artifacts are interpreted as direct encodings of the
information in the original profiles. I chose not to release them under GPLv3
because GPL's software-shaped "derivative work" model fits LUTs poorly and would
have made adoption more difficult. CC BY-SA 4.0 preserves the same share-alike
spirit in a form that matches how creative-grading assets are actually handled.

Please do not modify this file. Changes to profiles and LUTs should be tracked
in a separate CHANGELOG.txt file shipped with them. A copy of this license is
available at:
https://github.com/andreavolpato/spektrafilm/blob/main/SPEKTRAFILM_LICENSE.txt

---

A note before the legal text.

spektrafilm is a film-emulation project, designed in the open, given away
freely, and made to stay that way. Use it on anything. Share it widely. Build
on it. The works you make are yours, completely.

What follows is the full text of the Creative Commons Attribution-ShareAlike
4.0 International Public License (CC BY-SA 4.0). It is the legal floor. The
spirit it exists to protect, in three lines:

  - Free use forever, on any production, of any size.
  - Full credit to the source, on every copy and every derivative.
  - No parasitism.

--------------------------------------------------------------------------------
WHAT YOU CAN DO
--------------------------------------------------------------------------------

Use these profiles and LUTs freely to grade anything - personal, educational,
or commercial; small project or feature film; any platform, any deliverable.
The works you make are yours. No royalties. No copyleft on your finished film
or photograph. No obligation flowing to your distributor, broadcaster, or
audience.

Share the files freely with friends, collaborators, vendors, and post houses.

Modify, remix, regrade, recombine. Modified profiles or LUTs must stay
CC BY-SA 4.0 and credit spektrafilm.

--------------------------------------------------------------------------------
ATTRIBUTION - ALWAYS, EVERYWHERE, FOREVER
--------------------------------------------------------------------------------

Every copy, redistribution, modification, or derivative of these files - in
any format, in any context, across any chain of hands - must preserve:

  - Andrea Volpato, named as the original author.
  - https://github.com/andreavolpato/spektrafilm, as the canonical source.
  - The CC BY-SA 4.0 license notice (this file, or a link to it).
  - A note that the file has been modified, if it has, and by whom.

--------------------------------------------------------------------------------
WHAT I ASK, BEYOND THE LEGAL FLOOR
--------------------------------------------------------------------------------

These are not legal requirements. They are the spirit of the project, stated
plainly, so nobody has to guess.

  - Keep this file unchanged when sharing profiles/LUTs, and if changes were
    made describe them in a file CHANGELOG.txt shipped together with this
    license.
  - Credit spektrafilm in the human layer too - project docs, about pages,
    credits, breakdowns. Not required on a finished film or photograph, but
    appreciated wherever it's natural.
  - Don't use "spektrafilm" or my name in product branding without asking.
  - Paid apps and tools that add real value are welcome. Charge for what you
    built - UI, performance, support, a service. Please don't repackage the
    LUTs themselves as a paid LUT pack.
  - Please don't train commercial AI models on the LUTs or their outputs.`;

export function CreditsPage() {
  const { messages } = useLanguage();
  const c = messages.credits;
  const [showGpl, setShowGpl] = useState(false);
  const [showPreamble, setShowPreamble] = useState(false);

  return (
    <main id="main-content" className="credits-page">
      <div className="credits-inner">
        <header className="credits-header">
          <Link className="back-link" to="/"><ArrowLeft size={16} />{c.back}</Link>
          <div className="section-kicker"><span>{c.eyebrow}</span><i /></div>
          <h1>{c.title1}<br />{c.title2}<span>{c.titleAccent}</span></h1>
          <p className="credits-intro">{c.intro}</p>
        </header>

        {/* Output Freedom Guarantee */}
        <Reveal className="credits-card output-card">
          <div className="card-topline">
            <span className="card-badge badge-green"><Sparkles size={14} />{c.outputCalloutBadge}</span>
            <ShieldCheck size={18} color="#6bb9ed" />
          </div>
          <h2>{c.outputCalloutTitle}</h2>
          <p>{c.outputCalloutBody}</p>
          <ul className="output-highlights">
            {c.outputHighlights.map((item) => (
              <li key={item.title}>
                <strong>{item.title}</strong>
                <span>{item.desc}</span>
              </li>
            ))}
          </ul>
        </Reveal>

        {/* Upstream Attribution & Non-Endorsement */}
        <Reveal className="credits-card">
          <div className="card-topline">
            <span className="card-badge"><HeartHandshake size={14} />{c.upstreamTitle}</span>
            <span className="card-badge badge-gold">{c.nonEndorsementBadge}</span>
          </div>
          <p>{c.nonEndorsementText}</p>
          <div className="provenance-grid">
            <div className="provenance-box">
              <h4>{c.upstreamSpektrafilmTitle}</h4>
              <p>{c.upstreamSpektrafilmBody}</p>
              <a className="provenance-link" href="https://github.com/andreavolpato/spektrafilm" target="_blank" rel="noreferrer">
                github.com/andreavolpato/spektrafilm <ExternalLink size={13} />
              </a>
            </div>
            <div className="provenance-box">
              <h4>{c.upstreamSpektralabTitle}</h4>
              <p>{c.upstreamSpektralabBody}</p>
              <a className="provenance-link" href="https://github.com/JamesQiu2005/SpektraLab" target="_blank" rel="noreferrer">
                github.com/JamesQiu2005/SpektraLab <ExternalLink size={13} />
              </a>
            </div>
          </div>
        </Reveal>

        {/* Software Code License: GNU GPL v3.0 */}
        <Reveal className="credits-card">
          <div className="card-topline">
            <span className="card-badge badge-gold"><Code size={14} />{c.codeLicenseBadge}</span>
            <a className="provenance-link" href="https://www.gnu.org/licenses/gpl-3.0.html" target="_blank" rel="noreferrer">
              {c.gplLinkText} <ExternalLink size={13} />
            </a>
          </div>
          <h2>{c.codeLicenseTitle}</h2>
          <p><strong>{c.codeLicenseScope}</strong></p>
          <p>{c.codeLicenseBody}</p>
          <p><small>{c.codeLicenseNoWarranty}</small></p>

          <div className="provenance-box" style={{ marginTop: '20px' }}>
            <h4>{c.correspondingSourceTitle}</h4>
            <p>{c.correspondingSourceBody}</p>
            <div style={{ marginTop: '14px', display: 'flex', gap: '12px', flexWrap: 'wrap' }}>
              <a className="button button-dark" href={downloadMeta.source.href} download={downloadMeta.source.filename}>
                <Download size={16} />{c.sourceDownloadButton} ({downloadMeta.source.size})
              </a>
              <a className="button button-ghost" href="https://github.com/Kenneth0416/SpektraLab-App" target="_blank" rel="noreferrer">
                GitHub Repository <ArrowUpRight size={16} />
              </a>
            </div>
          </div>

          <details className="text-viewer" open={showGpl} onToggle={(e) => setShowGpl(e.currentTarget.open)}>
            <summary>
              <span><FileText size={14} style={{ verticalAlign: 'middle', marginRight: '6px' }} />{c.gplFullTextButton}</span>
              {showGpl ? <ChevronUp size={16} /> : <ChevronDown size={16} />}
            </summary>
            <pre>{gplNoticeText}</pre>
          </details>
        </Reveal>

        {/* Optical Data License: CC BY-SA 4.0 */}
        <Reveal className="credits-card">
          <div className="card-topline">
            <span className="card-badge badge-gold"><Layers3 size={14} />{c.dataLicenseBadge}</span>
            <a className="provenance-link" href="https://creativecommons.org/licenses/by-sa/4.0/" target="_blank" rel="noreferrer">
              Creative Commons CC BY-SA 4.0 <ExternalLink size={13} />
            </a>
          </div>
          <h2>{c.dataLicenseTitle}</h2>
          <p><strong>{c.dataLicenseScope}</strong></p>
          <p>{c.dataLicenseProvenance}</p>
          <p>{c.dataLicensePreambleNote}</p>

          <div className="provenance-box" style={{ marginTop: '18px' }}>
            <h4>{c.dataLicenseClarificationTitle}</h4>
            <p>{c.dataLicenseClarificationBody}</p>
          </div>

          <details className="text-viewer" open={showPreamble} onToggle={(e) => setShowPreamble(e.currentTarget.open)}>
            <summary>
              <span><FileText size={14} style={{ verticalAlign: 'middle', marginRight: '6px' }} />{c.preambleFullTextButton}</span>
              {showPreamble ? <ChevronUp size={16} /> : <ChevronDown size={16} />}
            </summary>
            <pre>{preambleNoticeText}</pre>
          </details>
        </Reveal>

        {/* Dated Modification History */}
        <Reveal className="credits-card">
          <div className="card-topline">
            <span className="card-badge"><Check size={14} />GPLv3 SECTION 5(a)</span>
          </div>
          <h2>{c.modHistoryTitle}</h2>
          <div className="timeline-list">
            {c.modHistoryDates.map((item) => (
              <div className="timeline-item" key={item.date}>
                <div className="timeline-dot" />
                <time>{item.date}</time>
                <strong>{item.title}</strong>
                <p>{item.desc}</p>
              </div>
            ))}
          </div>
        </Reveal>

        {/* Third-Party Fonts & Libraries */}
        <Reveal className="credits-card">
          <div className="card-topline">
            <span className="card-badge"><CheckCircle2 size={14} />FONTS & RUNTIME</span>
          </div>
          <h2>{c.thirdPartyTitle}</h2>
          <table className="third-party-table">
            <thead>
              <tr>
                <th>Component</th>
                <th>Author / Project</th>
                <th>License</th>
                <th>Purpose</th>
              </tr>
            </thead>
            <tbody>
              {c.thirdPartyItems.map((item) => (
                <tr key={item.name}>
                  <td><strong>{item.name}</strong></td>
                  <td>{item.author}</td>
                  <td><a href={item.link} target="_blank" rel="noreferrer">{item.license} <ExternalLink size={11} /></a></td>
                  <td>{item.note}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </Reveal>

        {/* Brand & Trademark Notice */}
        <Reveal className="credits-card trademark-card">
          <div className="card-topline">
            <span className="card-badge">TRADEMARK NOTICE</span>
          </div>
          <h3>{c.brandNoticeTitle}</h3>
          <p>{c.brandNoticeBody}</p>
        </Reveal>
      </div>
    </main>
  );
}
