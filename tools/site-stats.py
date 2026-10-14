#!/usr/bin/env python3
"""Generate the landing site's code statistics: python3 tools/site-stats.py

Reads git history and the tracked source tree, writes site/data/stats.json: lines of code by
language and module, test counts, and the .mailmap aliases. Commits, contributors and releases are
fetched live by site/site.js from the GitHub API; this file covers what the API cannot give. Stdlib only. The Pages workflow runs it on every
deploy (it needs full history: checkout with fetch-depth 0); run it locally to preview the site.
Authors are merged through .mailmap, so one person with several commit emails counts once.
"""
import datetime as dt
import json
import re
import subprocess
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "site/data/stats.json"

# Source code only: bundled assets (grammars, vendored minified JS, icon packs), test fixtures and
# docs would swamp the numbers with files nobody wrote by hand.
LANGUAGES = {
    ".kt": "Kotlin", ".kts": "Kotlin", ".java": "Java", ".py": "Python", ".sh": "Shell",
    ".ts": "TypeScript", ".js": "JavaScript", ".rs": "Rust", ".c": "C", ".h": "C",
    ".wat": "WebAssembly text", ".html": "HTML", ".css": "CSS", ".sql": "SQL",
}
EXCLUDE = re.compile(r"(^|/)(assets|testdata|testFixtures|docs|site/media)/|\.min\.js$")
TEST_DIR = re.compile(r"/src/(test|androidTest|testFixtures)/")
TEST_ANNOTATION = re.compile(r"^\s*@Test\b", re.M)
GITHUB_NOREPLY = re.compile(r"^(?:\d+\+)?([A-Za-z0-9-]+)@users\.noreply\.github\.com$")


def git(*args: str) -> str:
    return subprocess.run(["git", *args], cwd=ROOT, check=True, capture_output=True, text=True).stdout


def commits() -> list[tuple[str, str, dt.date]]:
    out = git("log", "--use-mailmap", "--format=%aN%x1f%aE%x1f%as")
    rows = []
    for line in out.splitlines():
        name, email, day = line.split("\x1f")
        rows.append((name, email, dt.date.fromisoformat(day)))
    return rows


def weekly(days: list[dt.date]) -> list[dict]:
    """Commits per ISO week (Monday), every week from the first to the last, empty ones included."""
    count = Counter(d - dt.timedelta(days=d.weekday()) for d in days)
    week, last = min(count), max(count)
    series = []
    while week <= last:
        series.append({"week": week.isoformat(), "commits": count.get(week, 0)})
        week += dt.timedelta(days=7)
    return series


def contributors(rows: list[tuple[str, str, dt.date]]) -> list[dict]:
    by_name: dict[str, dict] = {}
    for name, email, _ in rows:
        entry = by_name.setdefault(name, {"name": name, "commits": 0, "github": None})
        entry["commits"] += 1
        login = GITHUB_NOREPLY.match(email)
        if login and not entry["github"]:
            entry["github"] = login.group(1)
    return sorted(by_name.values(), key=lambda c: -c["commits"])


def module_of(path: str) -> str:
    parts = path.split("/")
    if parts[:2] == ["services", "mobile"] and len(parts) > 3:
        return f"mobile/{parts[2]}"
    if parts[0] == "services" and len(parts) > 2:
        return parts[1]
    return parts[0]


def code() -> dict:
    languages, modules = Counter(), Counter()
    production = tests = test_cases = files = 0
    for path in git("ls-files").splitlines():
        lang = LANGUAGES.get(Path(path).suffix)
        if not lang or EXCLUDE.search(path):
            continue
        try:
            text = (ROOT / path).read_text(encoding="utf-8")
        except (OSError, UnicodeDecodeError):
            continue
        lines = sum(1 for line in text.splitlines() if line.strip())
        files += 1
        languages[lang] += lines
        modules[module_of(path)] += lines
        if TEST_DIR.search(path):
            tests += lines
            test_cases += len(TEST_ANNOTATION.findall(text))
        else:
            production += lines
    return {
        "files": files,
        "lines": production + tests,
        "production_lines": production,
        "test_lines": tests,
        "test_cases": test_cases,
        "languages": [{"name": k, "lines": v} for k, v in languages.most_common()],
        "modules": [{"name": k, "lines": v} for k, v in modules.most_common(12)],
    }


def aliases() -> dict[str, str]:
    """Each raw commit email -> its .mailmap name, so the site merges live API commits the same way."""
    out = git("log", "--use-mailmap", "--format=%ae%x1f%aN")
    return dict(line.split("\x1f") for line in sorted(set(out.splitlines())))


def main() -> None:
    rows = commits()
    days = [d for _, _, d in rows]
    stats = {
        "generated": dt.date.today().isoformat(),
        "commits": len(rows),
        "first_commit": min(days).isoformat(),
        "last_commit": max(days).isoformat(),
        "releases": len(git("tag", "--list", "v*").split()),
        "decisions": len(list((ROOT / "docs/decision").glob("0*.md"))),
        "weekly": weekly(days),
        "contributors": contributors(rows),
        "aliases": aliases(),
        "code": code(),
    }
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps(stats, indent=1) + "\n")
    print(f"wrote {OUT.relative_to(ROOT)}: {stats['commits']} commits, {stats['code']['lines']} lines")


if __name__ == "__main__":
    main()
