#!/usr/bin/env python3
"""Build the landing site's articles and the sitemap: python3 tools/site-pages.py

Each file in tools/site-pages/ is an HTML fragment with a front-matter comment on top:

    <!--
    slug: blog/run-python-on-android   (output path under site/, without .html)
    title: Page title for the <title> and the h1
    description: One or two sentences for search results
    date: 2026-10-14
    kind: post | guide                 (post: listed on blog/index.html; guide: a top-level page)
    -->

The script wraps every fragment in the site's shell (head with canonical, Open Graph and
Article JSON-LD; nav; footer), writes blog/index.html, and rewrites site/sitemap.xml from the
hand-written pages below plus everything generated. Stdlib only; commit what it writes.
"""
import datetime as dt
import html
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "tools/site-pages"
SITE = ROOT / "site"
BASE = "https://ravibh5522.github.io/easyIDE/"
HAND_WRITTEN = ["", "features.html", "download.html", "stats.html", "team.html"]
NAV = [("features.html", "Features"), ("download.html", "Download"), ("blog/", "Blog"),
       ("stats.html", "Stats"), ("team.html", "Team")]
CURRENT = ' aria-current="page"'
AUTHOR = {"@type": "Person", "name": "Ravi Kumar", "url": BASE + "team.html"}


def parse(path: Path) -> dict:
    text = path.read_text(encoding="utf-8")
    m = re.match(r"\s*<!--(.*?)-->\s*", text, re.S)
    if not m:
        raise SystemExit(f"{path}: missing front matter")
    meta = dict(line.split(":", 1) for line in m.group(1).strip().splitlines())
    meta = {k.strip(): v.strip() for k, v in meta.items()}
    meta["body"] = text[m.end():]
    return meta


def shell(slug: str, title: str, description: str, body: str, ld: dict, current: str = "") -> str:
    up = "../" * slug.count("/")
    url = BASE + (slug + ".html" if not slug.endswith("/") else slug)
    nav = "".join(
        f'<a href="{up}{href}"{CURRENT if href == current else ""}>{label}</a>' for href, label in NAV
    )
    esc = html.escape
    return f"""<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>{esc(title)}</title>
<meta name="description" content="{esc(description)}">
<link rel="canonical" href="{url}">
<meta name="theme-color" content="#0f1115">
<meta property="og:type" content="article">
<meta property="og:site_name" content="easyIDE">
<meta property="og:url" content="{url}">
<meta property="og:title" content="{esc(title)}">
<meta property="og:description" content="{esc(description)}">
<meta property="og:image" content="{BASE}media/og-card.png">
<meta name="twitter:card" content="summary_large_image">
<link rel="stylesheet" href="{up}style.css">
<link rel="icon" type="image/png" sizes="512x512" href="{up}icon.png">
<script type="application/ld+json">
{json.dumps(ld, indent=1)}
</script>
</head>
<body class="plain">
<header class="nav"><a class="brand" href="{up}./"><img src="{up}icon.png" alt="" width="28" height="28">easyIDE</a><nav>{nav}<a href="https://github.com/ravibh5522/easyIDE">GitHub</a></nav></header>
<main>
{body}
</main>
<footer>
<nav><a href="{up}features.html">Features</a><a href="{up}download.html">Download</a><a href="{up}blog/">Blog</a><a href="{up}vs-code-for-android.html">VS Code for Android</a><a href="{up}acode-alternative.html">Acode alternative</a><a href="{up}best-code-editors-for-android.html">Best Android code editors</a><a href="{up}stats.html">Stats</a><a href="{up}team.html">Team</a></nav>
easyIDE is open source under AGPL-3.0 &middot; built by <a href="{up}team.html">Ravi Kumar</a> &middot; <a href="https://github.com/ravibh5522/easyIDE">GitHub</a>
</footer>
</body>
</html>
"""


def article(page: dict) -> str:
    slug, title = page["slug"], page["title"]
    ld = {
        "@context": "https://schema.org",
        "@type": "BlogPosting" if page["kind"] == "post" else "Article",
        "headline": title,
        "description": page["description"],
        "datePublished": page["date"],
        "dateModified": page.get("updated", page["date"]),
        "author": AUTHOR,
        "publisher": {"@type": "Organization", "name": "easyIDE", "logo": {"@type": "ImageObject", "url": BASE + "icon.png"}},
        "image": BASE + "media/og-card.png",
        "mainEntityOfPage": BASE + slug + ".html",
    }
    up = "../" * slug.count("/")
    crumbs = f'<a href="{up}./">easyIDE</a> / ' + (f'<a href="{up}blog/">Blog</a> / ' if page["kind"] == "post" else "")
    when = dt.date.fromisoformat(page["date"]).strftime("%B %-d, %Y")
    body = f"""<article class="prose">
<p class="crumbs">{crumbs}<span>{html.escape(title)}</span></p>
<h1>{html.escape(title)}</h1>
<p class="byline">By <a href="{up}team.html">Ravi Kumar</a>, lead developer &middot; {when}</p>
{page["body"].strip()}
<div class="cta" style="margin-top:48px">
<h2>Try easyIDE</h2>
<p class="muted">Free and open source. Android 8.0+, no root.</p>
<div class="btns"><a class="btn" href="{up}download.html">Download</a><a class="btn alt" href="https://github.com/ravibh5522/easyIDE">Source on GitHub</a></div>
</div>
</article>"""
    return shell(slug, title, page["description"], body, ld, "blog/" if page["kind"] == "post" else "")


def blog_index(posts: list[dict]) -> str:
    cards = "\n".join(
        f'<a class="card post-card" href="{p["slug"].split("/", 1)[1]}.html"><span class="muted">'
        f'{dt.date.fromisoformat(p["date"]).strftime("%b %-d, %Y")}</span><h3>{html.escape(p["title"])}</h3>'
        f'<p>{html.escape(p["description"])}</p></a>'
        for p in posts
    )
    body = f"""<section class="page-head">
<h1>Blog</h1>
<p>Guides for coding on an Android tablet: Python, Node.js, git and AI tools on a real Linux userland, and how easyIDE works.</p>
</section>
<section><div class="grid">
{cards}
</div></section>"""
    ld = {"@context": "https://schema.org", "@type": "Blog", "name": "easyIDE blog", "url": BASE + "blog/",
          "blogPost": [{"@type": "BlogPosting", "headline": p["title"], "url": BASE + p["slug"] + ".html",
                        "datePublished": p["date"]} for p in posts]}
    return shell("blog/", "easyIDE blog - coding on Android tablets", "Guides for coding on an Android tablet with easyIDE: Python, Node.js, git, Claude Code and more, on a real Linux userland.", body, ld, "blog/")


def sitemap(pages: list[dict]) -> str:
    urls = [(BASE + p, None) for p in HAND_WRITTEN] + [(BASE + "blog/", None)]
    urls += [(BASE + p["slug"] + ".html", p.get("updated", p["date"])) for p in pages]
    rows = "\n".join(
        f"  <url><loc>{u}</loc>{f'<lastmod>{m}</lastmod>' if m else ''}</url>" for u, m in urls
    )
    return f'<?xml version="1.0" encoding="UTF-8"?>\n<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">\n{rows}\n</urlset>\n'


def main() -> None:
    pages = [parse(p) for p in sorted(SRC.glob("*.html"))]
    for page in pages:
        out = SITE / (page["slug"] + ".html")
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_text(article(page), encoding="utf-8")
    posts = sorted((p for p in pages if p["kind"] == "post"), key=lambda p: p["date"], reverse=True)
    (SITE / "blog").mkdir(exist_ok=True)
    (SITE / "blog/index.html").write_text(blog_index(posts), encoding="utf-8")
    (SITE / "sitemap.xml").write_text(sitemap(pages), encoding="utf-8")
    print(f"wrote {len(pages)} pages, blog index with {len(posts)} posts, sitemap")


if __name__ == "__main__":
    main()
