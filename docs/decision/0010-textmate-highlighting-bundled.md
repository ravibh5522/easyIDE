# 0010 - Syntax highlighting: TextMate grammars, all bundled, tree-sitter deferred

Status: Accepted

## Context

The editor coloured code with `SyntaxHighlighter.kt`: 101 lines, four regexes, one keyword
set unioned across Kotlin, Python, JS and C, applied to every file regardless of type. It
coloured `#include` as a comment in C, greyed out the rest of the line after Python's `//`
operator, broke on Rust lifetimes and multi-line strings, and knew nothing of types,
functions or tags. It was always a placeholder.

[0009](0009-extension-platform-tiers.md) proposed Tier 0 as **tree-sitter for the top ~20
languages plus TextMate for breadth**, with grammars downloadable in language packs. The
product direction then changed: no extension packs, no downloads, everything in the build.

That change makes the two engines directly comparable on cost, and the numbers are not close.

## Decision

**TextMate grammars only, all 229 of them bundled in the APK. tree-sitter is deferred
entirely** - not scoped down, not partially adopted.

- Engine: [kotlin-textmate](https://github.com/ivan-magda/kotlin-textmate) 0.2.0 (MIT), with
  joni (MIT) for Oniguruma regex.
- Grammars: [tm-grammars](https://github.com/shikijs/textmate-grammars-themes), filtered by
  licence, normalised, and indexed by [tools/build-grammars.py](../../tools/build-grammars.py).
- Extension/filename to scope mapping: GitHub Linguist (MIT), read at build time only.
- Scopes collapse onto **21 semantic roles** via a single `ScopeRules` table, so themes
  define ~20 colours instead of tracking grammar-specific scope names.

## Alternatives considered

- **tree-sitter (KTreeSitter, MIT), as 0009 proposed.** Genuinely better output: a real
  parse tree, incremental reparse, and a foundation for folding and structural selection.
  Rejected on cost under "everything in the build": grammars are compiled `.so`, roughly
  0.5-1.5 MB **per language per ABI**, and easyIDE ships two ABIs. Twenty languages is tens
  of megabytes and an NDK toolchain, against **1.35 MB for all 229** TextMate grammars.
  Only 23 grammars are officially maintained by the tree-sitter org, so it could not have
  covered the breadth anyway. Revisit only if lexical highlighting proves insufficient.
- **[tm4e](https://github.com/eclipse-tm4e/tm4e) (Eclipse), the reference Java TextMate
  engine.** More mature than kotlin-textmate and better maintained upstream. Rejected on
  packaging: Eclipse does not publish it to Maven Central, and the only Central build is a
  third-party republish last released **December 2023** that drags in **Apache Batik**
  (SVG/CSS, AWT-bound) - unusable on Android and a stale supply chain.
- **[sora-editor](https://github.com/Rosemoe/sora-editor)**, which already has TextMate,
  tree-sitter and LSP wired together for Android. Rejected on licensing: **LGPL-2.1**, whose
  relinking obligations inside an APK conflict with selling a commercial licence under
  [0008](0008-noncommercial-source-available-licensing.md).
- **Shipping only a top-20 grammar subset.** Would have saved ~1 MB. Rejected as false
  economy: the marginal 209 grammars cost about a megabyte in total, and "my file type isn't
  supported" is the exact complaint that started this.

## Consequences

- **229 languages colour correctly, offline, with no download** - the same grammars VS Code
  uses. Verified: 229/229 compile, and a 7-assertion regression suite covering every defect
  of the old highlighter passes.
- **APK grew 21.6 -> 24.2 MB.** 1.35 MB of that is grammars; the rest is the tokenizer and
  its regex engine. Acceptable against a bundled 3.5 MB `mermaid.min.js`.
- **Highlighting is lexical, not semantic.** It knows `foo` is an identifier in a function
  position; it does not know what `foo` *is*. Go-to-definition, completion and diagnostics
  still require a language server. This ADR does not change the LSP plan.
- **31 upstream grammars are deliberately not shipped**: 5 GPL-3.0, 1 marked GNU, and 25
  whose licence could not be established. The 25 are an open question, not a judgement - the
  build script can include them once their upstream terms are confirmed. Recorded in
  [NOTICE.md](../../NOTICE.md).
- **Four grammars are structurally repaired at build time.** `jinja`, `xml`, `stata` and
  `wikitext` have `captures` blocks that deviate from the TextMate schema and do not parse
  otherwise. The repair is mechanical and lives in the build script, but it is a real
  dependency on upstream bugs staying the shape they are.
- **We adopted a proof-of-concept library.** kotlin-textmate is 44 stars, one maintainer,
  and its README lists five limitations. The two that bite are **no incremental
  tokenization** and **not thread-safe**. Mitigations shipped: every entry point is
  synchronized, and hard caps (4,000 lines / 400 KB / 2,000-char lines) stop a pathological
  file from freezing composition. Past those caps a file is fully editable but uncoloured.
  If upstream stalls, the tokenizer is ~160 KB of MIT Kotlin and can be vendored.
- **Incremental tokenization is now the top follow-up.** A full re-tokenize runs per buffer
  change on the composition thread. That is fine for the files the caps allow and wrong in
  principle; it is the next thing to fix.
- **The grammar set is generated, never hand-edited.** Adding or refreshing languages is
  `python3 tools/build-grammars.py`, which re-derives the licence filter, the repairs and
  the 846-extension index from upstream.
- **Nothing has been run on a device.** Tokenization latency while typing, memory on a large
  file, and how 21 roles look across the six themes are all unmeasured.
