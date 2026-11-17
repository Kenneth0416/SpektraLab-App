# Native decorative font

The Android app embeds the **unmodified LXGW WenKai Regular v1.530** release as
`app/src/main/res/font/lxgw_wenkai_regular.ttf`. It replaces the incomplete Long
Cang subset. Body text still uses the Material/Android system font and locale;
unsupported decorative characters keep the Android fallback path.

## Reproducible provenance

- Official source/release: https://github.com/lxgw/LxgwWenKai/releases/tag/v1.530
- Original font: https://github.com/lxgw/LxgwWenKai/releases/download/v1.530/LXGWWenKai-Regular.ttf
- Font SHA-256: `2d73eea94ad808999d90c0000c239a84a33b09d4bc6ab6c5b8bbf1eda8a7fc8b`
- Size: 25,928,940 bytes; 46,926 Unicode cmap entries; static weight 400
- License: https://raw.githubusercontent.com/lxgw/LxgwWenKai/v1.530/OFL.txt
- License SHA-256: `1a25e35da1031c6c3436fde545bb9cb5aca954e9873afe510c834b8b79bd21a0`
- Bundled verbatim license: `app/src/main/assets/licenses/OFL-LXGWWenKai.txt`
- Official build/source instructions: https://github.com/lxgw/LxgwWenKai/tree/v1.530

Renaming the resource filename does not change the font bytes or internal family
name. The whole original Regular face is retained rather than creating an
Android subset. This covers all 20,992 CJK basic-block and 6,592 Extension-A code
points, in addition to current translations, built-in recipe names and normal
English/Chinese generated dates. It also serves dynamic names in these blocks;
it does **not** guarantee all supplementary Han, variation sequences, scripts,
combining-mark positioning or OEM-specific regional glyph conventions.

## License and weight policy

SIL OFL 1.1 permits commercial use, embedding, bundling and redistribution with
software (conditions 1–2); the font must not be sold by itself. Font derivatives
remain OFL (condition 5); using the font does not impose OFL on output documents
or replace the app code license. Preserve both LXGW and Klee copyrights and the
entire license, including the upstream additional permission.

Reserved Font Names are `霞鹜`, `霞鶩`, `落霞孤鹜`, `落霞孤鶩`, `LXGW`. The unchanged
release can keep these names. The additional web-delivery permission is **not**
used as permission for a future Android TTF subset: any modified Android font
would need a non-reserved primary family/PostScript name (or explicit permission)
and updated provenance, retaining original copyright/license information.
Do not use authorship to imply endorsement (condition 4).

Only Regular is bundled. The coffee title explicitly requests Normal rather
than synthesizing Bold; other hand-styled text uses the normal weight. No font
scale override is introduced. Martian Mono remains the separate code/numeric
font; it is not a Chinese body font.

## Offline verification

```sh
python3 -m pip install -r app/tools/requirements-fonts.txt
python3 app/tools/verify_font_coverage.py
```

The script verifies original font/license hashes and Regular metadata, extracts
**all** string/plural/string-array text from every `values*` resource directory,
and requires zero missing non-space printable code points. Checking all resource
text is deliberately stronger than only the current hand-font call sites, so
new translations and renamed recipes cannot silently regress. It also checks
ASCII and the full basic/Extension-A blocks for dynamic date/name coverage.

Cmap checks do not prove final shaping, font selection, wrapping or screenshots.
Native Graphics Compose tests supplement them; physical-device/OEM checks remain
necessary, especially fontScale 2, mixed system/app languages and rare names.

## Size tradeoff

The original Long Cang subset was 235,296 bytes; the replacement is 25,928,940
bytes (about 24.7 MiB). A zlib level-6 compression comparison yielded 161,647 vs
12,924,790 bytes, a difference of 12,763,143 bytes (about 12.2 MiB); this is an
estimate of compressed font payload, **not** a measured before/after APK delta.
The full font is intentional for broader dynamic names and simple, verifiable
original-file redistribution. No second CJK body font or Bold file is bundled.

The isolated debug build measured `res/font/lxgw_wenkai_regular.ttf` at
12,904,464 compressed bytes (ZIP DEFLATE), with the extracted SHA-256 identical
to the official release. That whole debug APK was 58,531,272 bytes; this is an
absolute measurement of the concurrent workspace build, not a before/after size
comparison or a claim about the final release APK.
