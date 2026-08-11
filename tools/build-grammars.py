#!/usr/bin/env python3
"""Regenerate services/mobile/app/src/main/assets/grammars/.

The editor colours code with real TextMate grammars. This script builds that
asset set from two upstream sources so it can be refreshed without hand-editing
229 files:

  tm-grammars (npm)   the grammars themselves, normalised to JSON, plus the
                      licence each one carries upstream
  GitHub Linguist     extension/filename -> tm_scope, which tm-grammars does
                      not carry and most grammars omit from their own fileTypes

Only permissively licensed grammars are emitted -- easyIDE is sold commercially
(decision 0008), so GPL grammars are excluded and anything whose licence cannot
be established upstream is left out until it can be. See NOTICE.md.

Usage:  python3 tools/build-grammars.py [--out DIR]
Needs:  pyyaml, network access.
"""

import argparse
import collections
import io
import json
import os
import re
import shutil
import sys
import tarfile
import urllib.request

TM_GRAMMARS = "https://registry.npmjs.org/tm-grammars/-/tm-grammars-{v}.tgz"
TM_VERSION = "1.32.3"
LINGUIST = ("https://raw.githubusercontent.com/github-linguist/linguist/"
            "main/lib/linguist/languages.yml")

# Licences we may ship inside a commercially licensed APK.
SAFE_LICENSES = {"MIT", "Apache-2.0", "ISC", "BSD-3-Clause", "MPL-2.0"}
# github.com/textmate/* bundles carry an explicit grant to "copy, use, modify,
# sell and distribute", which tm-grammars records as no SPDX id.
TEXTMATE_ORG = "github.com/textmate/"

# Extensions several languages legitimately claim. Linguist ranks them by corpus
# frequency, which is not what a code editor wants (.php -> Hack, .sql -> PL/SQL).
EXTENSION_OVERRIDES = {
    "php": "source.php", "sql": "source.sql", "gradle": "source.groovy",
    "cpp": "source.cpp", "hpp": "source.cpp", "cc": "source.cpp",
    "cxx": "source.cpp", "hh": "source.cpp", "ipp": "source.cpp",
}


def fetch(url, timeout=120):
    with urllib.request.urlopen(url, timeout=timeout) as r:
        return r.read()


def normalise_captures(node, fixes):
    """Repair capture blocks that upstream grammars get structurally wrong.

    The tokenizer's model expects `captures` to be an object keyed by capture
    group index, with object values. Four bundled grammars deviate: jinja uses a
    list, stata and wikitext use bare strings, and xml has `name`/`end` keys that
    belong to the parent rule. All four fail to parse without this.
    """
    if isinstance(node, dict):
        for key in ("captures", "beginCaptures", "endCaptures", "whileCaptures"):
            block = node.get(key)
            if block is None:
                continue
            if isinstance(block, list):
                node[key] = {str(i): (v if isinstance(v, dict) else {"name": v})
                             for i, v in enumerate(block)}
                fixes.append(f"{key}:list->map")
            elif isinstance(block, dict):
                fixed = {}
                for k, v in block.items():
                    if not k.isdigit():
                        fixes.append(f"{key}:drop-{k}")
                        continue
                    fixed[k] = v if isinstance(v, dict) else {"name": v}
                if fixed:
                    node[key] = fixed
                else:
                    del node[key]
        for value in list(node.values()):
            normalise_captures(value, fixes)
    elif isinstance(node, list):
        for value in node:
            normalise_captures(value, fixes)


def parse_tm_index(js):
    """tm-grammars ships its metadata as an ES module, not JSON."""
    out = []
    for entry in re.findall(r"\n  \{\n(.*?)\n  \},", js, re.S):
        def field(key):
            m = re.search(rf"^    {key}: '([^']*)'", entry, re.M)
            return m.group(1) if m else None
        name, scope = field("name"), field("scopeName")
        if not name or not scope:
            continue
        aliases = re.search(r"^    aliases: \[(.*?)\]", entry, re.S | re.M)
        out.append({
            "name": name, "scope": scope,
            "license": field("license"), "source": field("source") or "",
            "aliases": re.findall(r"'([^']+)'", aliases.group(1)) if aliases else [],
        })
    return out


def slug(text):
    return re.sub(r"[^a-z0-9]", "", text.lower().replace("++", "pp").replace("#", "sharp"))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default="services/mobile/app/src/main/assets/grammars")
    args = ap.parse_args()

    try:
        import yaml
    except ImportError:
        sys.exit("needs pyyaml:  pip install pyyaml")

    print(f"fetching tm-grammars {TM_VERSION} ...")
    tar = tarfile.open(fileobj=io.BytesIO(fetch(TM_GRAMMARS.format(v=TM_VERSION))))
    files = {os.path.basename(m.name): tar.extractfile(m).read()
             for m in tar.getmembers()
             if m.isfile() and "/grammars/" in m.name and m.name.endswith(".json")}
    meta = parse_tm_index(
        tar.extractfile([m for m in tar.getmembers()
                         if m.name.endswith("package/index.js")][0]).read().decode())
    print(f"fetching Linguist languages.yml ...")
    languages = yaml.safe_load(fetch(LINGUIST))

    out = args.out
    os.makedirs(out, exist_ok=True)
    for stale in os.listdir(out):
        if stale.endswith(".json"):
            os.remove(os.path.join(out, stale))

    kept, skipped, repaired = [], [], []
    for g in meta:
        permissive = g["license"] in SAFE_LICENSES or (
            g["license"] is None and TEXTMATE_ORG in g["source"])
        blob = files.get(g["name"] + ".json")
        if blob is None:
            continue
        if not permissive:
            skipped.append((g["name"], g["license"] or "undeclared"))
            continue
        raw = json.loads(blob)
        fixes = []
        normalise_captures(raw, fixes)
        if fixes:
            repaired.append((g["name"], len(fixes)))
        with open(os.path.join(out, g["name"] + ".json"), "w") as fh:
            json.dump(raw, fh, separators=(",", ":"))
        g["ext"] = sorted({e.lower().lstrip(".") for e in (raw.get("fileTypes") or [])})
        g["license"] = g["license"] or "TextMate-permissive"
        kept.append(g)

    by_scope = {g["scope"]: g for g in kept}
    by_name = {}
    for g in kept:
        by_name.setdefault(slug(g["name"]), g)
        for alias in g["aliases"]:
            by_name.setdefault(slug(alias), g)

    ext_candidates = collections.defaultdict(list)
    file_candidates = collections.defaultdict(list)
    for lang_name, spec in languages.items():
        grammar = by_scope.get(spec.get("tm_scope")) or by_name.get(slug(lang_name))
        if not grammar:
            continue
        # Scope agreement is stronger evidence than a name collision, and a
        # programming language beats markup/data for a shared extension.
        rank = 0 if by_scope.get(spec.get("tm_scope")) is grammar else 1
        if spec.get("type") != "programming":
            rank += 2
        if slug(lang_name) == slug(grammar["name"]):
            rank -= 1
        for e in (spec.get("extensions") or []):
            ext_candidates[e.lower().lstrip(".")].append((rank, lang_name, grammar["scope"]))
        for f in (spec.get("filenames") or []):
            file_candidates[f.lower()].append((rank, lang_name, grammar["scope"]))

    def resolve(candidates):
        return {k: sorted(v, key=lambda t: (t[0], t[1]))[0][2] for k, v in candidates.items()}

    by_extension = resolve(ext_candidates)
    by_filename = resolve(file_candidates)
    for ext, scope in EXTENSION_OVERRIDES.items():
        if scope in by_scope:
            by_extension[ext] = scope
    for g in kept:                       # a grammar's own fileTypes fill any gap
        for e in g["ext"]:
            by_extension.setdefault(e, g["scope"])

    with open(os.path.join(out, "index.json"), "w") as fh:
        json.dump({
            "grammars": [{"name": g["name"], "scope": g["scope"],
                          "file": g["name"] + ".json", "license": g["license"]}
                         for g in kept],
            "byExtension": dict(sorted(by_extension.items())),
            "byFilename": dict(sorted(by_filename.items())),
        }, fh, separators=(",", ":"))

    print(f"\n  {len(kept)} grammars written to {out}")
    print(f"  {len(by_extension)} extensions, {len(by_filename)} filenames mapped")
    print(f"  {len(repaired)} grammars repaired: {repaired}")
    print(f"  {len(skipped)} skipped for licensing:")
    for name, why in sorted(skipped, key=lambda t: (t[1], t[0])):
        print(f"      {name:22} {why}")
    counts = collections.Counter(g["license"] for g in kept)
    print(f"  licences shipped: {dict(counts.most_common())}")


if __name__ == "__main__":
    main()
