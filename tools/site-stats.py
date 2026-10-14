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
import os
import re
import subprocess
import urllib.request
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
REPO = "ravibh5522/easyIDE"
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


def snapshot_commits() -> list[dict]:
    """The last year of commits in the GitHub API's shape, for when the site cannot reach the API."""
    since = (dt.date.today() - dt.timedelta(days=365)).isoformat()
    out = git("log", f"--since={since}", "--format=%H%x1f%an%x1f%ae%x1f%aI%x1f%s")
    commits = []
    for line in out.splitlines():
        sha, name, email, date, subject = line.split("\x1f")
        user = re.match(r"^(\d+)\+([A-Za-z0-9-]+)@users\.noreply\.github\.com$", email)
        commits.append({
            "html_url": f"https://github.com/{REPO}/commit/{sha}",
            "commit": {"message": subject, "author": {"name": name, "email": email, "date": date}},
            "author": {"login": user.group(2), "avatar_url": f"https://avatars.githubusercontent.com/u/{user.group(1)}?v=4"} if user else None,
        })
    return commits


def api(path: str):
    """Boundary: the network. The Pages workflow passes GITHUB_TOKEN; without it or offline, None."""
    req = urllib.request.Request(f"https://api.github.com/repos/{REPO}{path}", headers={"Accept": "application/vnd.github+json"})
    if os.environ.get("GITHUB_TOKEN"):
        req.add_header("Authorization", "Bearer " + os.environ["GITHUB_TOKEN"])
    try:
        with urllib.request.urlopen(req, timeout=20) as res:
            return json.load(res)
    except (OSError, ValueError):
        return None


def snapshot_releases():
    releases = api("/releases?per_page=30")
    if releases is None:
        return None
    keep = ("name", "size", "download_count", "browser_download_url")
    return [
        {"tag_name": r["tag_name"], "html_url": r["html_url"], "published_at": r["published_at"],
         "assets": [{k: a[k] for k in keep} for a in r["assets"]]}
        for r in releases
    ]


def snapshot_repo():
    repo = api("")
    return None if repo is None else {k: repo[k] for k in ("stargazers_count", "forks_count", "open_issues_count")}


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
        # What site.js shows when the visitor's own API calls fail (60 an hour per IP, shared on CGNAT).
        "snapshot": {"commits": snapshot_commits(), "releases": snapshot_releases(), "repo": snapshot_repo()},
        "code": code(),
    }
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps(stats, indent=1) + "\n")
    print(f"wrote {OUT.relative_to(ROOT)}: {stats['commits']} commits, {stats['code']['lines']} lines")


if __name__ == "__main__":
    main()
