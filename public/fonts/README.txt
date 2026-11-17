Silvercam website fonts
======================

Silvercam Hand Web Regular is a renamed, modified web subset of LXGW WenKai
1.530 Regular, released by LXGW on the official project:
https://github.com/lxgw/LxgwWenKai/releases/tag/v1.530
Copyright 2021-2026 LXGW; Copyright 2020 The Klee Project Authors.

Modifications by the Silvercam project (2026-11-13): subset to the actual
handwritten labels in all three website languages plus printable ASCII;
convert TTF to WOFF2; rename the primary family/full/PostScript names to
Silvercam Hand Web to avoid using the upstream Reserved Font Names.
Glyph outlines are not redrawn or merged with any other font.
This is an independent derivative, not endorsed by the upstream authors.
The full upstream copyright, additional permission and SIL OFL 1.1 text are
preserved in OFL-WenKai.txt and in the font metadata where originally present.

Martian Mono 1.1.0 Standard Regular/Medium/Bold (400/500/700) are unmodified
WOFF2 files from the official release (only the filenames were changed):
https://github.com/evilmartians/mono/releases/tag/v1.1.0
Copyright 2021 The Martian Mono Project Authors.
The full upstream license is in OFL-MartianMono.txt.

Both distributed font families are under SIL Open Font License 1.1, NOT the
application's GPL license. OFL permits commercial use, embedding, modification
and redistribution (including bundling with commercial software), subject to
its terms: do not sell the fonts by themselves; preserve copyright and license;
modified fonts remain OFL; respect Reserved Font Names; do not claim author
endorsement. Documents produced with these fonts are not required to use OFL.
Read the complete included license texts for the authoritative terms.

No full CJK body font is distributed. Body text and Chinese fallback in the
monospaced stack use fonts already provided by the operating system; the
website's explicit SC/TC fallback list follows zh-Hans/zh-Hant. Those system
fonts retain their own licenses and are not all OFL. system-ui can select a
platform-specific font before the named fallback list. Script locale does not
guarantee Taiwan/Hong Kong regional glyph preferences or full Unicode coverage.

Coverage and reproduction
-------------------------
The shared src/typography/handwriting.ts function supplies both the rendered
handwritten text in HomePage and the verifier's corpus. Do not add hand-styled
labels outside that corpus without extending the verifier. The shipped hand
subset includes 119 Unicode codepoints, including all current handwritten
labels, U+2713 CHECK MARK, U+00D7 MULTIPLICATION SIGN and printable ASCII.
New Chinese text may require rebuilding; CSS keeps system fallback for dynamic
or future content. Coverage of this corpus is not coverage of all Chinese.

Prerequisites: Node >=22 with type stripping; Python 3 with fontTools 4.62.1
and Brotli 1.2.0 (pinned in scripts/requirements-web-fonts.txt).
From the project root (install Python packages in your chosen virtualenv):
  python3 -m pip install -r scripts/requirements-web-fonts.txt
  python3 scripts/web-fonts.py
    Offline verification of coverage, weights, artifact hashes and licenses.
  python3 scripts/web-fonts.py --build
    Download checksum-pinned official release assets and regenerate fonts.
  python3 scripts/web-fonts.py --build --source-dir /path/to/cache
    Offline rebuild from wenkai.ttf, wenkai-ofl.txt, martian.zip,
    martian-ofl.txt matching the checksums pinned in the script.

manifest.json records official URLs, source hashes, output hashes and sizes.
No Google Fonts request is needed at runtime. WOFF2 faces use font-display:swap,
so system fonts may appear briefly before loading. Handwritten text explicitly
uses weight 400; Martian Mono has real 400/500/700 faces. Layout must still be
checked after changing fonts, translations, operating system or font scaling.
