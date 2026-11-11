# Silvercam (銀鹽工坊 / 银盐工坊) — Licensing, Attribution & Modification Notices

**Display Brand:** Silvercam (Traditional Chinese: 銀鹽工坊; Simplified Chinese: 银盐工坊)  
*Notice regarding brand name:* "Silvercam" / "銀鹽工坊" / "银盐工坊" are display names adopted for this project to clearly distinguish it from upstream projects. This name is not trademark-cleared; no trademark clearance is claimed or implied.  
**Canonical Source Repository:** https://github.com/Kenneth0416/SpektraLab-App  
**Version:** 0.1.0  
**Date:** 2026-11-11  

---

## 1. Independent Downstream Project & Non-Endorsement Notice

Silvercam is an independent downstream port, interaction design prototype, and software application. It is **NOT** affiliated with, sponsored by, or endorsed by Andrea Volpato, the upstream [spektrafilm](https://github.com/andreavolpato/spektrafilm) project, or James Qiu ([SpektraLab](https://github.com/JamesQiu2005/SpektraLab)).

References to photographic film, motion-picture stock, or photo-paper names (such as Kodak, Portra, Ektar, Gold, UltraMax, Vision3, Double-X, Verita, Kodachrome, Ektachrome, Endura, Fujifilm, Velvia, Provia, Crystal Archive) describe historical emulsions and profile data ported from the upstream spektrafilm scientific models. All trademarks and trade names remain the property of their respective owners. No affiliation, sponsorship, or commercial endorsement is claimed or implied.

---

## 2. Dual Licensing Structure

Silvercam distinguishes between executable software code and spectral/profile data assets:

### (A) Application, Engine & Web Source Code — GNU GPL v3.0

- **Scope:** All Android application code (`app/`), Kotlin spectral film simulation runtime and DNG linear RAW developer (`engine/`), GLSL ES 3.00 shaders, web darkroom application and presentation website (`src/`, `public/`, `index.html`), build scripts (`scripts/`), and tools (`tools/`).
- **Upstream Origin:** Derived and ported from [spektrafilm](https://github.com/andreavolpato/spektrafilm) (and desktop GUI components by Andrea Volpato: `white_padding`, `gray_18_canvas`, virtual photo paper watermark concepts, and polaroid development animation) at commit `3bb2c2d2801ff68b92019cf1dbcbb133d60832bc` (v0.3.4), with interaction references from [SpektraLab](https://github.com/JamesQiu2005/SpektraLab).
- **License:** GNU General Public License, Version 3 (GPLv3).
- **License Text:** See the root [`LICENSE`](./LICENSE) file or https://www.gnu.org/licenses/gpl-3.0.html.
- **NO WARRANTY (GPLv3 Sections 15–16):**  
  *This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.*

### (B) Film Profiles, Paper Profiles & Optical Data — CC BY-SA 4.0

- **Scope:** Binary optical and spectral data files under `app/src/main/assets/spektra/` (`film/*.spk`, `paper/*.spk`, `global.spk`, `index.json`), generated from spektrafilm profile databases.
- **Author & Canonical Origin:** Andrea Volpato — https://github.com/andreavolpato/spektrafilm
- **License:** Creative Commons Attribution-ShareAlike 4.0 International (CC BY-SA 4.0).
- **Author Preamble v1.1:** Upstream author Andrea Volpato provided an accompanying preamble dated 2026-05-28, preserved verbatim in [`app/src/main/assets/spektra/SPEKTRAFILM_LICENSE.txt`](./app/src/main/assets/spektra/SPEKTRAFILM_LICENSE.txt).
- **Non-Binding Nature of Preamble Requests:** As explicitly stated by the upstream author in the preamble ("These are not legal requirements. They are the spirit of the project, stated plainly..."), requests regarding commercial AI training and standalone LUT reselling are polite requests rather than binding legal terms of the CC BY-SA 4.0 public license. Furthermore, lack of per-binary embedded credit (e.g. inside compact binary SPK tables) is not necessarily a copyright violation; attribution is provided fully across documentation, UI, and release archives.

### (C) User Outputs (Photographs & Videos) — No Copyleft

Per Section 2 of the upstream spektrafilm CC BY-SA 4.0 preamble and GNU GPLv3 principles:
- **Finished photographs and videos produced, graded, or exported using Silvercam / spektrafilm do NOT acquire copyleft.**
- The creative works you create are entirely yours.
- Free use forever, on any personal, educational, or commercial production. No royalties, no output licensing obligations, and no mandatory watermark on your exported images or videos.

---

## 3. GPL Corresponding Source Notice (GPLv3 Section 6)

- **Source Code Repository:** https://github.com/Kenneth0416/SpektraLab-App
- **Downloadable Release Source:** Shipped releases provide corresponding source ZIP archives (e.g. `Silvercam-App-source.zip`) containing the exact files, build configurations, and tools used to create the binaries.
- **Notice on Generic Repository URLs:** In compliance with GPLv3 Section 6, please note that a link to a generic repository homepage or moving default branch (`main`) does not guarantee a matching 1:1 state with any specific pre-built binary. Users and distributors should refer to the exact commit tags or release source archives matching that build.

---

## 4. Modification History & Dated Notices (GPLv3 Section 5(a))

- **2026-11-10:**
  - Ported upstream spektrafilm v0.3.4 (Python / NumPy / colour-science pipeline) to pure Kotlin/JVM (`engine/`) and GLSL ES 3.00 shaders.
  - Baked JSON profiles into compact SPK1 binary format (`tools/bake/bake_spektrafilm.py`).
  - Created Android native Jetpack Compose application (`app/`).
- **2026-11-11:**
  - Added linear DNG RAW developer, CameraX film viewfinder, and foldable posture adaptation.
  - Ported desktop GUI styles (paper border, 18% gray canvas, development animation).
  - Adopted display brand **Silvercam (銀鹽工坊 / 银盐工坊)** (not trademark-cleared) to distinguish from upstream spektrafilm and SpektraLab.
  - Replaced paper-back watermark graphic with independent neutral geometric artwork (`paper_back_mark.png`).
  - Launched trilingual web application and interactive darkroom landing page (Traditional Chinese, Simplified Chinese, English) with downloadable release packages.
  - Added comprehensive in-app and web credits, licensing documentation, and corresponding source packaging.

---

## 5. Third-Party Fonts & Component Licenses

- **Long Cang (Google Fonts):** Copyright 2018 The LongCang Project Authors. Licensed under SIL Open Font License, Version 1.1.
- **Martian Mono (Evil Martians):** Copyright 2020/2021 The Martian Mono Project Authors / Evil Martians / Roman Shamin. Licensed under SIL Open Font License, Version 1.1.
- **Noto Sans (Google Fonts):** Licensed under SIL Open Font License, Version 1.1.
- **Lucide Icons:** Copyright (c) Lucide Contributors. Licensed under ISC License.
- **React, React Router, Vite, fflate:** Licensed under MIT License.
