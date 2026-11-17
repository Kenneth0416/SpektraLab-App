#!/usr/bin/env python3
"""Offline coverage/provenance gate for the native decorative font.

Run: python3 -m pip install -r app/tools/requirements-fonts.txt
     python3 app/tools/verify_font_coverage.py
Uses all localized resources, not a fixed list of previously missing characters.
Cmap coverage is not a shaping, regional-glyph or on-device screenshot test.
"""
from pathlib import Path
import hashlib
import json
import sys
import unicodedata
import xml.etree.ElementTree as ET

from fontTools.ttLib import TTFont

APP = Path(__file__).resolve().parents[1]
FONT = APP / "src/main/res/font/lxgw_wenkai_regular.ttf"
LICENSE = APP / "src/main/assets/licenses/OFL-LXGWWenKai.txt"
FONT_SHA256 = "2d73eea94ad808999d90c0000c239a84a33b09d4bc6ab6c5b8bbf1eda8a7fc8b"
LICENSE_SHA256 = "1a25e35da1031c6c3436fde545bb9cb5aca954e9873afe510c834b8b79bd21a0"


def resource_characters(directory):
    """Include nested plural/array items and styled strings, excluding controls/space.

    Android escape syntax may add harmless ASCII characters to this conservative
    superset; placeholders are retained, while arbitrary runtime values are not.
    """
    chars = set()
    entries = 0
    for path in sorted(directory.glob("*.xml")):
        for node in ET.parse(path).getroot():
            if node.tag not in {"string", "plurals", "string-array"}:
                continue
            entries += 1
            chars.update(ord(c) for c in "".join(node.itertext())
                         if not c.isspace() and not unicodedata.category(c).startswith("C"))
    return entries, chars


def main():
    errors = []
    for path, expected in [(FONT, FONT_SHA256), (LICENSE, LICENSE_SHA256)]:
        if hashlib.sha256(path.read_bytes()).hexdigest() != expected:
            errors.append(f"{path.name}: differs from pinned official v1.530 bytes")
    font = TTFont(FONT)
    cmap = {cp for cp, glyph in font.getBestCmap().items() if glyph != ".notdef"}
    if font["OS/2"].usWeightClass != 400 or "fvar" in font:
        errors.append("Expected the upstream static Regular (400), not a variable/Bold face")
    report = {"version": font["name"].getDebugName(5), "bytes": FONT.stat().st_size,
              "cmap_codepoints": len(cmap), "resources": {}}
    for directory in sorted((APP / "src/main/res").glob("values*")):
        entries, chars = resource_characters(directory)
        if not entries:
            continue
        missing = chars - cmap
        report["resources"][directory.name] = {
            "entries": entries, "unique_nonspace_characters": len(chars),
            "missing": "".join(chr(cp) for cp in sorted(missing)),
        }
        if missing:
            errors.append(f"{directory.name}: unsupported resource codepoints " +
                          " ".join(f"U+{cp:04X}" for cp in sorted(missing)))
    # Broader than current labels: generated English dates/numbers and all BMP
    # basic/Extension-A Han characters for Chinese dates, recipes and names.
    # This intentionally does NOT promise every supplementary Han/IVS sequence.
    for name, start, end in [("ASCII printable", 0x20, 0x7E),
                             ("CJK Extension A", 0x3400, 0x4DBF),
                             ("CJK Unified Ideographs", 0x4E00, 0x9FFF)]:
        missing = set(range(start, end + 1)) - cmap
        report[name] = {"required": end - start + 1, "missing": len(missing)}
        if missing:
            errors.append(f"{name}: {len(missing)} missing codepoints")
    print(json.dumps(report, ensure_ascii=False, indent=2))
    for error in errors:
        print(error, file=sys.stderr)
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
