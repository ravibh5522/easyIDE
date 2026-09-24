#!/usr/bin/env python3
"""Regenerate services/mobile/app/src/main/assets/grammars/.

The editor colours code with real TextMate grammars. This script builds that
asset set from two upstream sources so it can be refreshed without hand-editing
229 files:

  tm-grammars (npm)   the grammars themselves, normalised to JSON, plus the
                      licence each one carries upstream
  GitHub Linguist     extension/filename -> tm_scope, which tm-grammars does
                      not carry and most grammars omit from their own fileTypes
  VS Code built-ins   language-configuration.json (brackets, auto-close pairs,
                      comments, indentation/onEnter rules), mapped to a grammar
                      through each extension's contributes.grammars scopeName.
                      Languages VS Code does not ship get no config; the editor
                      falls back to generic bracket rules for those.

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
import urllib.error
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
# Pinned so a regenerate is reproducible; the repo is MIT (checked at build time).
VSCODE_TAG = "1.139.0"
VSCODE_API = "https://api.github.com/repos/microsoft/vscode/contents/extensions?ref={t}"
VSCODE_RAW = "https://raw.githubusercontent.com/microsoft/vscode/{t}/{path}"
CONFIG_DIR = "config"
# Grammars owned by a first-party extension pack instead of the core set (M3: languages
# ship as extensions). They are still fetched and licence-checked here, then written into
# the pack; the core index neither lists them nor maps their file types. The pack's
# package.json `languages` entry is hand-maintained and must claim the same types.
PACK_GRAMMARS = {"source.python": "easyide.python"}
PACK_DIR = os.path.join(os.path.dirname(__file__), "..", "services", "mobile", "app",
                        "src", "main", "assets", "extensions")
# The language-configuration keys the editor reads; the rest (e.g. VS Code-only
# experimental keys) would be dead weight in the APK.
CONFIG_KEYS = ("comments", "brackets", "autoClosingPairs", "surroundingPairs",
               "autoCloseBefore", "colorizedBracketPairs", "folding",
               "indentationRules", "onEnterRules", "wordPattern")

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


def strip_jsonc(text):
    """VS Code config files are JSON with comments and trailing commas."""
    out, i, n = [], 0, len(text)
    while i < n:
        c = text[i]
        if c == '"':
            j = i + 1
            while text[j] != '"':
                j += 2 if text[j] == "\\" else 1
            out.append(text[i:j + 1])
            i = j + 1
        elif text.startswith("//", i):
            i = text.find("\n", i)
            i = n if i < 0 else i
        elif text.startswith("/*", i):
            i = text.index("*/", i) + 2
        elif c in "]}":
            # A trailing comma may sit before a comment, so it is dropped here,
            # after comments are gone, rather than by looking ahead.
            j = len(out) - 1
            while j >= 0 and out[j].isspace():
                j -= 1
            if j >= 0 and out[j] == ",":
                del out[j]
            out.append(c)
            i += 1
        else:
            out.append(c)
            i += 1
    return "".join(out)


def as_regex(value):
    """Regexes arrive as a bare string or {pattern, flags}; emit one shape."""
    if isinstance(value, str):
        return {"pattern": value, "flags": ""}
    return {"pattern": value["pattern"], "flags": value.get("flags", "")}


def as_pairs(items):
    """Pairs arrive as [open, close] or {open, close, notIn}; emit objects."""
    out = []
    for item in items or []:
        if isinstance(item, list):
            item = {"open": item[0], "close": item[1]}
        pair = {"open": item["open"], "close": item["close"]}
        if item.get("notIn"):
            pair["notIn"] = item["notIn"]
        out.append(pair)
    return out


def normalise_config(raw):
    cfg = {k: raw[k] for k in CONFIG_KEYS if k in raw}
    for key in ("brackets", "autoClosingPairs", "surroundingPairs", "colorizedBracketPairs"):
        if key in cfg:
            cfg[key] = as_pairs(cfg[key])
    if "wordPattern" in cfg:
        cfg["wordPattern"] = as_regex(cfg["wordPattern"])
    for key, value in list(cfg.get("indentationRules", {}).items()):
        cfg["indentationRules"][key] = as_regex(value)
    for rule in cfg.get("onEnterRules", []):
        for key in ("beforeText", "afterText", "previousLineText"):
            if key in rule:
                rule[key] = as_regex(rule[key])
    markers = cfg.get("folding", {}).get("markers")
    if markers:
        cfg["folding"]["markers"] = {k: as_regex(v) for k, v in markers.items()}
    return cfg


def fetch_language_configs(scopes):
    """scope -> (language id, normalised config) from VS Code's built-in extensions."""
    licence = fetch(VSCODE_RAW.format(t=VSCODE_TAG, path="LICENSE.txt")).decode()
    if not licence.startswith("MIT License"):
        sys.exit("VS Code licence is no longer MIT; language configs cannot be shipped")
    listing = json.loads(fetch(VSCODE_API.format(t=VSCODE_TAG)))
    found = {}
    for entry in sorted(listing, key=lambda e: e["name"]):
        if entry["type"] != "dir":
            continue
        base = f"extensions/{entry['name']}"
        try:
            pkg = json.loads(fetch(VSCODE_RAW.format(t=VSCODE_TAG, path=f"{base}/package.json")))
        except urllib.error.HTTPError:
            continue
        contributes = pkg.get("contributes", {})
        config_of = {lang["id"]: lang["configuration"]
                     for lang in contributes.get("languages", []) if lang.get("configuration")}
        for grammar in contributes.get("grammars", []):
            scope, lang = grammar.get("scopeName"), grammar.get("language")
            if scope not in scopes or scope in found or lang not in config_of:
                continue
            path = os.path.normpath(f"{base}/{config_of[lang]}")
            raw = json.loads(strip_jsonc(fetch(VSCODE_RAW.format(t=VSCODE_TAG, path=path)).decode()))
            found[scope] = (lang, normalise_config(raw))
    return found


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
    shutil.rmtree(os.path.join(out, CONFIG_DIR), ignore_errors=True)
    os.makedirs(os.path.join(out, CONFIG_DIR))

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

    print(f"fetching VS Code {VSCODE_TAG} language configurations ...")
    configs = fetch_language_configs(set(by_scope))
    for scope, (lang, cfg) in configs.items():
        name = f"{CONFIG_DIR}/{lang}.json"
        with open(os.path.join(out, name), "w") as fh:
            json.dump(cfg, fh, separators=(",", ":"))
        by_scope[scope]["config"] = name
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

    for scope, pack in PACK_GRAMMARS.items():
        g = by_scope.get(scope)
        if g is None:
            sys.exit(f"{scope} is owned by {pack} but was not built")
        dest = os.path.join(PACK_DIR, pack)
        shutil.move(os.path.join(out, g["name"] + ".json"),
                    os.path.join(dest, "syntaxes", g["name"] + ".tmLanguage.json"))
        if "config" in g:
            shutil.move(os.path.join(out, g["config"]),
                        os.path.join(dest, "language-configuration.json"))
        kept.remove(g)
        print(f"  {g['name']} handed to extension pack {pack}")
    by_extension = {k: v for k, v in by_extension.items() if v not in PACK_GRAMMARS}
    by_filename = {k: v for k, v in by_filename.items() if v not in PACK_GRAMMARS}

    with open(os.path.join(out, "index.json"), "w") as fh:
        json.dump({
            "grammars": [dict({"name": g["name"], "scope": g["scope"],
                               "file": g["name"] + ".json", "license": g["license"]},
                              **({"config": g["config"]} if "config" in g else {}))
                         for g in kept],
            "byExtension": dict(sorted(by_extension.items())),
            "byFilename": dict(sorted(by_filename.items())),
        }, fh, separators=(",", ":"))

    print(f"\n  {len(kept)} grammars written to {out}")
    print(f"  {len(by_extension)} extensions, {len(by_filename)} filenames mapped")
    print(f"  {len(configs)} language configurations (VS Code {VSCODE_TAG}, MIT)")
    print(f"  {len(repaired)} grammars repaired: {repaired}")
    print(f"  {len(skipped)} skipped for licensing:")
    for name, why in sorted(skipped, key=lambda t: (t[1], t[0])):
        print(f"      {name:22} {why}")
    counts = collections.Counter(g["license"] for g in kept)
    print(f"  licences shipped: {dict(counts.most_common())}")


if __name__ == "__main__":
    main()
