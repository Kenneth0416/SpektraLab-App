#!/usr/bin/env python3
"""Fail on missing translations, mismatched placeholders, or hard-coded Han UI strings."""

from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / "app/src/main/res"
LOCALES = {
    "English (default)": RES / "values/strings.xml",
    "Simplified Chinese": RES / "values-zh-rCN/strings.xml",
    "Traditional Chinese": RES / "values-zh-rTW/strings.xml",
}
PLACEHOLDER = re.compile(r"%(?:\d+\$)?[a-zA-Z%]")
STRING_LITERAL = re.compile(r'"(?:\\.|[^"\\])*"')
HAN = re.compile(r"[\u3400-\u4dbf\u4e00-\u9fff\uf900-\ufaff]")


def entries(path: Path) -> dict[str, str]:
    root = ET.parse(path).getroot()
    result: dict[str, str] = {}
    for node in root:
        if node.tag not in {"string", "plurals"}:
            continue
        name = node.attrib.get("name")
        if not name:
            raise ValueError(f"{path}: <{node.tag}> without a name")
        key = f"{node.tag}/{name}"
        if key in result:
            raise ValueError(f"{path}: duplicate {key}")
        if node.tag == "string":
            text = " ".join("".join(node.itertext()))
        else:
            # Plural quantities legitimately differ by language; compare the shared "other" form.
            forms = node.findall("item")
            form = next((item for item in forms if item.attrib.get("quantity") == "other"), forms[0])
            text = "".join(form.itertext())
        result[key] = text
    return result


def without_comments(text: str) -> str:
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    return re.sub(r"//[^\n]*", "", text)


def main() -> int:
    errors: list[str] = []
    localized: dict[str, dict[str, str]] = {}
    for language, path in LOCALES.items():
        try:
            localized[language] = entries(path)
        except (ET.ParseError, ValueError) as exc:
            errors.append(str(exc))
    if errors:
        print("\n".join(errors), file=sys.stderr)
        return 1

    baseline = localized["English (default)"]
    for language, resources in localized.items():
        missing = sorted(baseline.keys() - resources.keys())
        extra = sorted(resources.keys() - baseline.keys())
        if missing:
            errors.append(f"{language}: missing {', '.join(missing)}")
        if extra:
            errors.append(f"{language}: extra {', '.join(extra)}")
        for key in sorted(baseline.keys() & resources.keys()):
            expected = sorted(PLACEHOLDER.findall(baseline[key]))
            actual = sorted(PLACEHOLDER.findall(resources[key]))
            if expected != actual:
                errors.append(f"{language}: {key} placeholders {actual}, expected {expected}")
            if not resources[key].strip():
                errors.append(f"{language}: {key} is empty")

    source_roots = [ROOT / "app/src/main/java", ROOT / "engine/src/main/kotlin"]
    for source_root in source_roots:
        for path in source_root.rglob("*.kt"):
            source = without_comments(path.read_text())
            for literal in STRING_LITERAL.findall(source):
                if HAN.search(literal):
                    line = source[: source.find(literal)].count("\n") + 1
                    errors.append(f"{path.relative_to(ROOT)}:{line}: hard-coded Han string {literal}")

    config = ET.parse(RES / "xml/locales_config.xml").getroot()
    android_name = "{http://schemas.android.com/apk/res/android}name"
    tags = {node.attrib[android_name] for node in config.findall("locale")}
    if tags != {"en", "zh-Hans", "zh-Hant"}:
        errors.append(f"locales_config.xml: unexpected locales {sorted(tags)}")

    if errors:
        print("i18n checks failed:\n- " + "\n- ".join(errors), file=sys.stderr)
        return 1
    print(f"i18n OK: {len(baseline)} resources match in English, Simplified Chinese, and Traditional Chinese; no hard-coded Han strings.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
