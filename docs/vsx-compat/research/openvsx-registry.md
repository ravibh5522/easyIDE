# Open VSX registry client + `.vsix` install pipeline (primary-source notes)

Agent OPENVSX-REG, 2026-09-25. Documentation-only audit. Capability ids: `ui.extensions-ui`, `ext.deps`, `ext.pack`,
`ext.update`, `ext.kind`, `ext.killswitch`, `l10n`, `ext.enable`.

## 0. Sources and pinned revisions

| Tag used below | What | Revision |
|---|---|---|
| `ovsx@edab3e4` | `git clone https://github.com/eclipse/openvsx` (server) | edab3e447d04a5fc65c7dc6d6c52ff6e1495f5e8 (2026-09-25) |
| `ovsx-wiki@54c40c0` | `https://github.com/eclipse/openvsx.wiki.git` | 54c40c0ce6957df5be2ca521f54af80aea1e34d2 |
| `fdn-site@10340c3` | `https://github.com/EclipseFdn/open-vsx.org` (deployment of open-vsx.org) | 10340c3443d6838e971d8caf516e827fa29e8fdc |
| `fdn-wiki@b9f9568` | `https://github.com/EclipseFdn/open-vsx.org.wiki.git` | b9f9568f2cd2f8a3717158f9e72a9c56773c3584 |
| `vsce@c1eebf3` | `https://github.com/microsoft/vscode-vsce` | c1eebf3b1d0cf90da1aca18e4ab58311bbe39d61 |
| `vscode@0b16cb9` | `$VS` (microsoft/vscode main) | 0b16cb97868058754d545e5f9ced99211ccc290b |
| `WT@49725be` | easyIDE worktree `audit/vsx` | 49725be8e3ef29c404e61161e0dd7ce433d49146 |

Saved artefacts (all under `$SP/research-openvsx/`): `api-docs.json` (all groups, 222 KB, from
`https://open-vsx.org/v3/api-docs`), `api-docs-registry.json`, `api-docs-vscode-adapter.json`
(the Swagger UI loads `/v3/api-docs/swagger-config` -> groups "Registry API", "VSCode Adapter", "Admin API"),
`terms-of-use.md` and `publisher-agreement-v1.1.md` (the SPA routes `/terms-of-use` to `/documents/terms-of-use.md`,
found in `bundle.js`), `ovsx-faq.txt`, `extension-control.json`, `vsce-sign.npm.json`, `vsce-sign-pkg/`,
`docs/*.txt` (code.visualstudio.com pages), `live/*` (recorded API responses, `ra.sigzip`, `pubkey.txt`,
`ra.vsixmanifest`, `ra.content_types.xml`). The sample `rust-lang.rust-analyzer-0.4.3061@linux-arm64.vsix` is in
`/root/.cache/easyide-corpus/openvsx-reg/`.

---

## (a) Endpoint table (Registry API + VS Code adapter), base `https://open-vsx.org`

Every `/api/**` and `/vscode/**` response carries `X-RateLimit-Limit` / `X-RateLimit-Remaining` (OpenAPI; observed
`x-ratelimit-limit: 10800`). All paths below are GET unless noted. Source: `api-docs-registry.json`, live calls 2026-09-25.

| Purpose | Path | Key params | Response fields we need |
|---|---|---|---|
| Search | `/api/-/search` | `query`, `category`, `targetPlatform` (enum of 12), `size` (default 18, max 1000), `offset`, `sortBy` = relevance\|timestamp\|rating\|downloadCount, `sortOrder` = asc\|desc, `includeAllVersions` (default false) | `offset`, `totalSize`, `extensions[]` of SearchEntry: `url, files{download,icon,...}, name, namespace, version, timestamp, verified, displayName, description, deprecated, downloadCount, averageRating, reviewCount, allVersionsUrl` |
| Metadata query (v1) | `/api/-/query` | `namespaceName, extensionName, extensionVersion, extensionId (ns.name), extensionUuid, namespaceUuid, includeAllVersions, targetPlatform, size, offset` | `offset, totalSize, extensions[]` (full Extension objects). POST form is "Deprecated" (live POST -> 301) |
| Metadata query (v2) | `/api/v2/-/query` | same params | same; with `includeAllVersions=true` returns one Extension per version (with `preRelease`, `engines`, `targetPlatform`) paginated - the right primitive for version selection (live: redhat.java universal totalSize 613) |
| Latest version | `/api/{ns}/{name}` | - | Extension (see field list below). Without a platform the server prefers universal (see (b)) |
| Latest for platform | `/api/{ns}/{name}/{targetPlatform}` | - | Extension; **404 "Extension not found: x (linux-arm64)" when only universal exists - no server fallback** (live, redhat.vscode-yaml) |
| Specific version | `/api/{ns}/{name}/{version}` and `/api/{ns}/{name}/{targetPlatform}/{version}` | `version` may be an alias `latest` / `pre-release` | Extension |
| Version map | `/api/{ns}/{name}[/{tp}]/versions` | `size, offset` | `{offset,totalSize,versions{ver:url}}` (`allVersions` in Extension is "Deprecated: only returns the last 100 versions") |
| Version refs | `/api/{ns}/{name}[/{tp}]/version-references` | `size, offset` | `versions[]{version, targetPlatform, engines, url, files{download,signature,sha256,publicKey}}` - **no `preRelease` flag** (live) |
| File | `/api/{ns}/{name}[/{tp}]/{version}/file/**` | path = file name | 302 to CDN `https://openvsx.eclipsecontent.org/...` (live; `cache-control: max-age=604800` on the redirect). Named files in `files{}`: `download` (.vsix), `signature` (.sigzip), `sha256`, `manifest` (package.json), `vsixmanifest`, `readme`, `changelog`, `license`, `icon`, `publicKey` |
| Public key | `/api/-/public-key/{publicId}` | publicId (uuid) | `text/plain` PEM; live: `-----BEGIN PUBLIC KEY----- MCowBQYDK2VwAyEAje+vAaSS1zHV5WHCJSa5UXvxRo6+yerEU3IEmtuEuF4=` (OID 1.3.101.112 = Ed25519) |
| Namespace | `/api/{namespace}` | - | `name, extensions{name:url}, verified` ("Indicates whether the namespace has an owner") |
| Namespace details | `/api/{namespace}/details` | - | `name, displayName, description, logo, website, supportLink, socialLinks, extensions[], verified` |
| Namespace logo | `/api/{namespace}/logo/{fileName}` | - | image |
| Reviews | `/api/{ns}/{name}/reviews` | - | `reviews[]{rating, comment, timestamp, user}`, `postUrl`, `deleteUrl` |
| Changes feed | `/api/-/version-changes` ("[Preview]") | `after` (cursor), `since`, `until`, `size` | `changes[]{namespace,name,version,targetPlatform,state=ACTIVE\|INACTIVE\|REMOVED,timestamp,lastUpdated,url}`, `hasMore`, `nextCursor` (live 200). Useful for revocation/takedown detection |
| Registry version | `/api/version` | - | `version` |
| VS Code gallery | POST `/vscode/gallery/extensionquery` (and GET `?q=`) | Marketplace JSON `{filters[{criteria[{filterType,value}],pageNumber,pageSize}],flags}` | Marketplace shape: `results[].extensions[]{extensionId, extensionName, publisher, versions[]{version, targetPlatform, properties[Microsoft.VisualStudio.Code.Engine/.PreRelease/.ExtensionDependencies/.ExtensionPack/...], files[assetType,source]}, statistics, flags}`. Live: redhat.java returns 3866 versions (8.9 MB) in one response; the same query for rust-analyzer intermittently returned an HTML error page |
| Gallery latest | `/vscode/gallery/{ns}/{name}/latest` | - | single Marketplace-shaped extension |
| Gallery package | `/vscode/gallery/publishers/{ns}/vsextensions/{name}/{version}/vspackage` | `targetPlatform` | .vsix |
| Gallery asset | `/vscode/asset/{ns}/{name}/{version}/{assetType}/**` | `targetPlatform` | asset (asset types incl. `Microsoft.VisualStudio.Services.VsixSignature`, `...PublicKey`) |
| Browse package | `/vscode/unpkg/{ns}/{name}/{version}/**` | - | file inside the package |
| Item page | `/vscode/item?itemName=ns.name` | - | redirect to web UI |

Extension object fields (`components.schemas.Extension`, registry group): `namespaceUrl, reviewsUrl, files, name, namespace,
targetPlatform, version, preRelease, publishedBy{loginName,fullName,avatarUrl,homepage,provider}, reviewStatus
(published|under_review|rejected), reviewMessage, verified, allVersions (deprecated), allVersionsUrl, averageRating,
downloadCount, reviewCount, versionAlias[] ('latest'|'pre-release'), timestamp, preview, displayName, namespaceDisplayName,
description, engines{}, categories, extensionKind[], tags, license, homepage, repository, sponsorLink, bugs, markdown,
galleryColor, galleryTheme, localizedLanguages, qna, badges[]{url,href,description}, dependencies[]{namespace,extension,url},
bundledExtensions[] (= extensionPack), downloads{tp:url}, allTargetPlatformVersions[], url, deprecated, replacement{url,
displayName}, downloadable`. Note: `extensionKind` description: "Values are \"ui\" (run locally), ...".

Configuring a VS Code-derived product (ovsx-wiki@54c40c0 `Using-Open-VSX-in-VS-Code.md:6-10`):
`"serviceUrl": "https://open-vsx.org/vscode/gallery"`, `"itemUrl": "https://open-vsx.org/vscode/item"`,
`"resourceUrlTemplate": "https://open-vsx.org/vscode/unpkg/{publisher}/{name}/{version}/{path}"`,
`"extensionUrlTemplate": "https://open-vsx.org/vscode/gallery/{publisher}/{name}/latest"`.

**Recommendation for easyIDE**: use the native Registry API (`/api/-/search`, `/api/v2/-/query`, `/api/{ns}/{name}/{tp}/{v}`)
rather than the Marketplace adapter: smaller responses, typed fields (`preRelease`, `deprecated`, `replacement`, `verified`,
`files.sha256/signature/publicKey`), paginated versions. `docs/extensions/arch.md:91-93` currently names the `/vscode/gallery`
adapter; `docs/extension-sdk/lld/registry-and-install.md:339-341` names the native API - the LLD is the better choice.

---

## (b) Target platforms and selection rule

### Facts

| Source | Values / rule |
|---|---|
| Open VSX `ovsx@edab3e4 server/src/main/java/org/eclipse/openvsx/util/TargetPlatform.java:16-55` | 12 names: `universal, win32-x64, win32-ia32, win32-arm64, linux-x64, linux-arm64, linux-armhf, alpine-x64, alpine-arm64, darwin-x64, darwin-arm64, web`. The class has **no fallback logic** (only `isValid`, `isUniversal` at :60-66) |
| Open VSX publish: `ExtensionProcessor.java:355-363` | TargetPlatform read from `extension.vsixmanifest` `Identity@TargetPlatform`; "if (targetPlatform.isEmpty()) targetPlatform = TargetPlatform.NAME_UNIVERSAL" |
| Open VSX "latest" query: `repositories/ExtensionVersionJooqRepository.java:1484-1510` (`findLatestQuery`) | filters `TARGET_PLATFORM.eq(tp)` only when a valid tp is given; order: `SEMVER_MAJOR desc, MINOR desc, PATCH desc, SEMVER_IS_PRE_RELEASE asc, UNIVERSAL_TARGET_PLATFORM desc, TARGET_PLATFORM asc, TIMESTAMP desc`. So without tp, universal wins ties; `onlyPreRelease=false` does **not** exclude `preRelease=true` versions |
| Open VSX aliases: `LocalRegistryService.java:1085-1097` | `latest` = `findLatestVersionForAllUrls(ext, tp, false, ...)`, `pre-release` = same with `onlyPreRelease=true`. Live: redhat.java 1.57.2026092508 has `preRelease: true` and `versionAlias: ['latest','pre-release']` -> **Open VSX `latest` can be a pre-release** |
| Open VSX search with `targetPlatform=linux-arm64` (live) | 15 hits for "yaml" vs 374 without; universal extensions are **excluded** by the filter |
| VS Code enum `vscode@0b16cb9 src/vs/platform/extensions/common/extensions.ts:383-402` | same 11 platform names **minus `win32-ia32`**, plus `unknown`, `undefined` |
| VS Code host platform `extensionManagement/common/extensionManagement.ts:88-131` + `common/extensionManagementUtil.ts:174-198` | Linux arm64 -> `linux-arm64`; if `/etc/os-release` (or `/usr/lib/os-release`) `ID=alpine` -> `alpine-arm64`; `arm` -> `linux-armhf` |
| VS Code compatibility `extensionManagement.ts:133-165` (`isTargetPlatformCompatible`) | incompatible if product is `web` and extension has no web build; compatible if ext tp is `undefined` or `universal`; `unknown` -> false; else only exact match. **No linux-x64 or alpine fallback for linux-arm64** |
| VS Code web tag `extensionGalleryService.ts:407-428` (`getAllTargetPlatforms`) + `extensionManagement.ts:25` | `web` counted only if the extension has tag `__web_extension` (vsce adds it when `extensionKind` includes `web`: `vsce@c1eebf3 src/package.ts:716`, `isWebKind` :1184-1187) |
| VS Code preference `extensionGalleryService.ts:430-495` (`sortExtensionVersions`, `filterLatestExtensionVersionsForTargetPlatform`) | per version number, a build whose tp equals the product tp replaces a universal/undefined build of the same version; latest release and latest pre-release kept separately |
| VS Code VSIX sideload `node/extensionManagementService.ts:281` | a local `.vsix` gets `ExtensionKey(id, version)` with **no target platform** - the vsixmanifest `TargetPlatform` is not read (see (f)) |
| VS Code docs publishing-extension (`docs/publishing-extension.txt:467-469`) | "If you don't pass this flag, that package will be used as a fallback for all platforms that have no platform-specific package." / "The currently available platforms are: win32-x64, win32-arm64, linux-x64, linux-arm64, linux-armhf, alpine-x64, alpine-arm64, darwin-x64, darwin-arm64 and web." / "The web platform respects the browser entry point in the package.json." |
| vsce `src/package.ts:454-465` (`Targets`) | the same 10 targets; `win32-ia32` is not packable by current vsce (legacy on Open VSX only) |
| Extension host runtimes (`docs/extension-host.txt:196-198`) | "Node.js - Extensions are running in a Node.js runtime. ... Extensions need a main entry file to run in it." / "Browser - Extensions are running in Browser WebWorker runtime. ... Extensions need a browser entry file" |
| VS Code kind deduction `workbench/services/extensions/common/extensionManifestPropertiesService.ts:262-293` | `main` -> `['workspace']` (desktop); `browser` only -> `['web']`; no code -> all kinds filtered by contribution points; packs/deps -> `['workspace']` on desktop |

### Proposal: easyIDE selection (guest = Ubuntu glibc arm64, Node host)

1. Product platform = `linux-arm64`, computed like `computeTargetPlatform` from the **guest** `/etc/os-release`
   (`ID=alpine` would give `alpine-arm64`; our proot Ubuntu gives `linux-arm64`). Never use the Android host arch string.
2. Acceptable build tps, in preference order: `linux-arm64` > `universal`. Never: `alpine-*` (musl binaries), `linux-armhf`
   (32-bit), `linux-x64`/`darwin-*`/`win32-*`, `web`.
3. Because the server does not fall back, the client must ask twice (or ask without tp and filter):
   `GET /api/v2/-/query?extensionId=<id>&targetPlatform=linux-arm64&includeAllVersions=true&size=N` and the same with
   `universal`; merge by version; per version prefer linux-arm64 (VS Code `filterLatestExtensionVersionsForTargetPlatform`).
4. Search: never pass `targetPlatform` to `/api/-/search` (it hides universal extensions); filter results after resolving.
5. Pick the newest version with `preRelease == false` whose `engines.vscode` passes our `isEngineValid` port (see (i));
   pre-release only when the user opted in per extension. If only pre-releases exist, show VS Code's error semantics
   ("has no release version", `abstractExtensionManagementService.ts:728-731`) with an explicit opt-in.
6. `web`: a `web` build is never installed on our Node host. A universal package whose manifest has `browser` but no `main`
   is a web-worker extension (`extensionKind` deduces `['web']`); it cannot run in our Node host as designed ->
   classify **Not-planned for Node host / needs a web-worker host** (`ext.kind`). UNVERIFIED how many of these would work
   if loaded in Node anyway; measure on the corpus.
7. Declarative packages (no `main`, no `browser`) are platform-neutral; install the universal build.
8. An extension that publishes only `linux-x64` (and no universal) is **incompatible** (same as VS Code); show "not built for
   ARM64". No x86 emulation.

---

## (c) Integrity: sha256 and signature

### Facts (all verified)

| Item | Evidence |
|---|---|
| `.sha256` content = lowercase hex SHA-256 of the whole `.vsix`, no filename | `ExtensionProcessor.java:452-466` (`DigestUtils.sha256Hex(input)`; `Files.writeString`); live: `afde51b7...fbdcb` equals `sha256sum` of the rust-analyzer linux-arm64 vsix |
| `.sigzip` = zip with `.signature.sig` (raw 64-byte Ed25519 signature), `.signature.manifest` (JSON), `.signature.p7s` (0 bytes) | `publish/ExtensionVersionIntegrityService.java:137-172`; comment at :161: "Add dummy file to the archive because VS Code checks if it exists"; live `unzip -l ra.sigzip` = 64 / 2011 / 0 bytes |
| Signed message = **the entire .vsix bytes**, algorithm pure Ed25519 (RFC 8032, no prehash, no context) | `ExtensionVersionIntegrityService.java:193-202` (`privateKeyParameters.sign(Ed25519.Algorithm.Ed25519, null, message, ...)` with `message = Files.readAllBytes(extensionFile)`); server-side verifier :70-123 (mirror use) |
| `.signature.manifest` = `{"package":{size,digests{sha256(base64)}},"entries":{base64(name):{size,digests{sha256}}}}`, **not itself signed** | `ExtensionVersionIntegrityService.java:204-246`; live: openssl verify of the manifest bytes fails, of the vsix bytes succeeds |
| Key: Ed25519 key pair generated server-side, `publicId` uuid, `active` flag, modes `create`/`renew` | `migration/GenerateKeyPairJobService.java:58-72`; `entities/SignatureKeyPair.java:26-27`; dev config `ovsx.integrity.key-pair: create # create, renew, delete, 'undefined'` (`server/src/dev/resources/application.yml:146`) |
| Public key served as PEM SPKI | `/api/-/public-key/{publicId}`; live key id `14ccb407-4e79-41ed-be5a-6d608325c45a` is used by every version sampled (HookyQR.beautify 1.4.11 from 2020, redhat.vscode-yaml, esbenp.prettier-vscode, ms-python.python, PKief.material-icon-theme, rust-analyzer) -> old versions were re-signed (`migration/ExtensionVersionSignatureJobRequestHandler.java`) |
| **Verification without Microsoft code works** | `openssl pkeyutl -verify -pubin -inkey pubkey.txt -rawin -in <vsix> -sigfile .signature.sig` -> "Signature Verified Successfully" (2026-09-25, rust-analyzer 0.4.3061 linux-arm64) |
| VS Code verifies via `@vscode/vsce-sign` loaded dynamically | `vscode@0b16cb9 src/vs/platform/extensionManagement/node/extensionSignatureVerificationService.ts:69-72` (`const mod = '@vscode/vsce-sign'; return import(mod);`), call :90; downloader `node/extensionDownloader.ts:41,64-106,141-147` (`validate(location.fsPath, '.signature.p7s')`) |
| VS Code policy | setting `extensions.verifySignature` (`extensionManagement.ts:720`), default true (`node/extensionManagementService.ts:341-344`); gallery `isSigned: !!assets.signature` (`extensionGalleryService.ts:573`); failure -> delete + error unless `NotSigned` and signature not required; skipped when `!isBuilt` or on `linux-armhf` (:348-353) |
| `@vscode/vsce-sign` is **proprietary** | npm `https://registry.npmjs.org/@vscode/vsce-sign` 2.1.0: `"license": "SEE LICENSE IN LICENSE.txt"`, README: "@vscode/vsce-sign is licensed under a [Microsoft software license](LICENSE.txt)." LICENSE.txt: "You may install and use any number of copies of the software only with Microsoft Visual Studio, Visual Studio for Mac, Visual Studio Code, Azure DevOps, Team Foundation Server, Code Spaces and successor Microsoft products and services" and "You may not ... reverse engineer, decompile or disassemble the software". Platform packages (`@vscode/vsce-sign-linux-arm64` 2.0.6) same licence field |
| easyIDE already ships a pure-Java Ed25519 verifier | Tink `Ed25519Verify` wrapper `services/mobile/app/src/main/java/dev/easyide/app/extensions/registry/TinkEd25519.kt:12-23` (32-byte raw key, 64-byte sig); ADR `docs/decision/0016-extension-registry-static-index-ed25519.md:76-85` |

UNVERIFIED: whether `@vscode/vsce-sign` itself accepts Open VSX `.sigzip` (the dummy `.p7s` suggests VS Code-based products
only check presence). Not needed: we must not use vsce-sign anyway (licence restricts use to Microsoft products).

### Design options

| Option | What it proves | Cost | Verdict |
|---|---|---|---|
| O1 sha256 only (current LLD sec 9) | bytes match what the API says; nothing if the API/CDN is compromised (sha256 comes from the same origin over TLS) | trivial | insufficient alone |
| **O2 sha256 + Open VSX Ed25519 `.sigzip`**, key pinned in APK | package was signed by the Open VSX registry key; a CDN/object-store compromise cannot forge. Does not prove publisher identity or safety | ~S: unzip `.sigzip`, take `.signature.sig`, strip 12-byte SPKI prefix `302a300506032b6570032100` from the PEM DER to get the 32-byte key, `TinkEd25519.verify(key, vsixBytes, sig)` | **recommended** |
| O3 O2 + per-entry check against `.signature.manifest` | detects nothing extra (manifest is unsigned; whole-file sig already covers entries) | S | optional diagnostics only |
| O4 use vsce-sign | - | licence forbids | rejected |

Key trust for O2: pin `publicId 14ccb407-4e79-41ed-be5a-6d608325c45a` + raw key bytes in the APK (`RegistryPolicy`);
if a version names another `publicKey` id (key renewal, `KEYPAIR_MODE_RENEW`), fetch it over TLS and show "Open VSX signing
key changed" (TOFU on the registry key, never silent). Signature failure = hard stop (same rule as ADR 0016). Missing
signature: UNVERIFIED whether any active version lacks `files.signature` - measure over the corpus; treat as "unsigned,
confirm" not as failure. Correction to docs: ADR 0016:48-49 says Open VSX "carries only its own sha256" and LLD sec 9 says
"sha256 from Open VSX only" - both outdated; Open VSX signs every version.

---

## (d) Rate limits, Terms of Use, caching/mirroring

### Rate limits (facts)

| Item | Evidence |
|---|---|
| Free tier | fdn-wiki@b9f9568 `Rate-limiting.md:9`: "**Standard Limit**: < 3 requests per second (RPS)  / <**10,800 Requests Per Hour**"; :11 "**Scope**: This limit applies to standard community requests, including extension searches, metadata retrievals, and downloads via IDEs or CLI tools."; :12 "When the limit for your current tier is reached, the API returns an HTTP 429 Too Many Requests response." |
| Keyed by client IP | production config `fdn-site@10340c3 configuration/application-production.yml:49` `ip-address-function: '(getHeader("X-Fastly-Real-IP")?: getHeader("X-Real-IP")?: getHeader("X-Forwarded-For")?: getRemoteAddr())...'`; wiki :57 "multiple users may share a single egress IP address. Collective traffic from one network may trigger the community limit." |
| Scope | production config :53-57 filter `url: '/(api|vscode)/.*'`, exposes `Retry-After, X-RateLimit-Limit, X-RateLimit-Remaining, X-RateLimit-Reset` |
| 429 body (RFC 9457) | production config :59-67 / wiki :28-37: `{"type":"https://open-vsx.org/probs/rate-limit-exceeded","title":"Too Many Requests","status":429,"detail":"...","documentation":"...Rate-Limiting","contact":"infrastructure@eclipse-foundation.org"}` |
| Headers on 429 | `ovsx@edab3e4 server/src/main/java/org/eclipse/openvsx/ratelimit/filter/RateLimitServletFilter.java:37-39,112,119`: `X-RateLimit-Remaining: 0`, `X-RateLimit-Reset` ("Number of seconds until the rate limit tokens will be fully filled"), `Retry-After` (seconds); implemented with bucket4j (`server/build.gradle:121-122`) |
| Higher tiers | wiki :18-22 Enterprise < 15 RPS, Enterprise XL <= 50, Platform > 50; "managed through authenticated tokens" (token prefix `ovsx_rl_`, config :44). UNVERIFIED how a client sends the token (header name) - read `ratelimit/IdentityService.java` before relying on it |

Caveat: `application-production.yml:8-12` says the values "need confirming against current production infra before merging";
the live header `x-ratelimit-limit: 10800` matches the wiki.

Client rules we should adopt: one request at a time per device, <= 1 RPS steady; honour `Retry-After`; back off on 429
without retry storms; cache metadata (ETag is sent, but `If-None-Match` returned 200 not 304 on `/api/redhat/java` live -
so cache by time, UNVERIFIED for file endpoints); prefer one `v2/-/query` over N per-version calls; never poll per-extension
for updates more than daily (VS Code checks every 12 h: `extensionsWorkbenchService.ts:977`). Downloads are 302 redirects to
`openvsx.eclipsecontent.org` (CDN); allow that host. UNVERIFIED whether the CDN hop is rate-limited separately.

### Terms of Use (open-vsx.org `/documents/terms-of-use.md`, "February 11, 2021")

Relevant clauses (quoted, `terms-of-use.md` line):
- :5 "By accessing, browsing, or using this Website including downloading Content from this Website, you acknowledge that you
  have read, understand, and agree to be bound by these terms." - "you" includes "any person or entity which has granted to
  such individual the authority to enter into this agreement on its behalf".
- :9 "This Website makes available Content from entities other than the Eclipse Foundation (\"Third Party Publishers\"),
  under terms and licenses (including proprietary licenses) provided by such Third Party Publishers. It is your
  responsibility when accessing and using any Content to comply with terms and licenses associated with that Content."
- :11 "The Eclipse Foundation may remove, update, or modify Content from this Website at any time without notice."
- :13 "By making the Content available for access by you on this Website, neither the Eclipse Foundation nor the Members
  grant any licenses to any copyrights, patents or any other intellectual property rights in the Content"
- :15 logos/trademarks: "no licenses or other rights in or to such logos and/or trademarks are granted to you."
- :36 "the right to block access from a particular Internet address to this Website."
- No clause on automated access, API use, caching or mirroring was found in the ToU (searched the whole 36-line text).
  API `info.termsOfService` in `api-docs.json` points to `https://www.eclipse.org/legal/termsofuse.php` (general Eclipse
  ToU, fetched 200 but not analysed - UNVERIFIED for automated-access clauses).

Publisher Agreement v1.1 ("September 2025", `publisher-agreement-v1.1.md`):
- :45 publisher grants Eclipse "the worldwide, non-exclusive right to host, install, use, reproduce, transmit, publicly
  perform and display, format and make available to end-users (including through multiple tiers of distribution)".
- :53 "All Registry Offerings and associated Offering Contents must be licensed to end-users. ... If you fail to specify a
  license in the Listing Information for an Offering, the Offering and Offering Contents will be made available under the MIT
  license ... Such licenses and grants will be between you and licensees and end users".
- :49 Eclipse may monetise "offering, for a fee, enhanced performance of the Registry for specific users" (the paid tiers).

FAQ (`https://www.eclipse.org/legal/open-vsx-registry-faq/`, `ovsx-faq.txt`): "If you are consuming extensions in
applications that use the Open VSX Registry (e.g. in Gitpod, VSCodium or Eclipse Theia IDE) there will be no usage impact."
"Auto-update is a feature of VS Code, Eclipse Theia IDE (or other applications consuming extensions). ... Open VSX Registry
itself does not provide any auto-update facility." "All extensions in the Open VSX Registry are made available without fee,
subject to the terms of the respective licenses."

Conclusions for a third-party client app:
- Downloading on behalf of end users is the normal use (FAQ lists third-party apps); licence of each extension binds the
  end user -> **show the licence before install** (see (j)).
- Caching downloaded packages on the device for offline reinstall: no clause forbids it; the extension's own licence governs
  redistribution. Device-local cache = fine. **Re-hosting/mirroring** packages on an easyIDE server = redistribution under each
  extension's licence (Eclipse grants no licence, ToU :13) -> do not mirror without per-licence review. Open VSX itself
  supports mirror mode for operators (`ovsx@edab3e4 doc/mirror.md:3-9`: "Open VSX can run as a **mirror** of another
  registry ... The packages themselves are **not** copied").
- Bulk crawling (e.g. our compatibility corpus) must stay under 3 RPS / 10,800 per hour per IP; for CI-scale needs the wiki
  offers OSS elevated access (`Rate-limiting.md:10`).

---

## (e) Verified publishers / namespaces - display rules

| Rule | Evidence |
|---|---|
| Namespace = `publisher` field; regex `[\w\-\+\$~]+` | ovsx-wiki `Namespace-Access.md:18` |
| Creating a namespace makes you contributor, not owner; "Initially the namespace has no owner, therefore it is regarded as _unverified_" | `Namespace-Access.md:23` |
| "An extension version is regarded as _verified_ if its namespace is verified and its publishing user is a member of the namespace." Verified -> shield icon; unverified -> warning icon | `Namespace-Access.md:25` |
| Code: `isVerifiedPublisher = user.isPrivileged() \|\| membershipJooqRepo.isVerified(namespace, user)`; `false` when the version has no publisher | `repositories/RepositoryService.java:512-527` |
| Privileged `@open-vsx` account republishes unmaintained extensions, "even if it is not a member of its namespace" | `Namespace-Access.md:44` |
| Warning reasons: owner never claimed; publisher removed from namespace; published by @open-vsx into someone else's namespace | `Namespace-Access.md:48-52` |
| Ownership claims are public GitHub issues on EclipseFdn/open-vsx.org | `Namespace-Access.md:31` |
| Publish-time name checks: namespace-ownership-check against the VS Marketplace (enforced, `gallery-url: marketplace.visualstudio.com/_apis/public/gallery`) and similarity (not enforced) | `application-production.yml:80-87,112-117` |
| API fields: per version `verified`, `publishedBy{loginName,provider,...}`; namespace `verified` ("has an owner") | OpenAPI schemas |

Display proposal (`ui.extensions-ui`): badge per version = verified shield only when `verified == true`; otherwise a warning
chip "Unverified publisher" plus `publishedBy.loginName`; if `publishedBy.loginName == "open-vsx"` show "Republished by Open
VSX"; namespace page shows `namespace.verified` separately. Never merge the two meanings. `reviewStatus != published`
(`under_review`/`rejected`) -> not installable.

---

## (f) VSIX anatomy and VS Code install steps

### Anatomy (vsce writes it)

| Part | Evidence |
|---|---|
| `extension.vsixmanifest` (XML, root) - `Identity@Id/Version/Publisher/TargetPlatform`, DisplayName, Description, Tags, Categories, GalleryFlags, Badges, Properties (`Microsoft.VisualStudio.Code.Engine`, `.ExtensionDependencies`, `.ExtensionPack`, `.ExtensionKind`, `.LocalizedLanguages`, `.EnabledApiProposals`, `.PreRelease`, `.ExecutesCode`, `.SponsorLink`), `License`, Assets | `vsce@c1eebf3 src/package.ts:1525-1621` (`toVsixManifest`); TargetPlatform attr :1531; live `ra.vsixmanifest:4` `TargetPlatform="linux-arm64"`, :18 PreRelease, :34 License |
| `[Content_Types].xml` (OPC) - one `<Default Extension=... ContentType=.../>` per file extension, sorted | `src/package.ts:1629-1656` (`toContentTypes`); live `ra.content_types.xml` |
| Both added at packaging | `src/package.ts:1855-1858` |
| Extension files under `extension/` | `src/util.ts:256-258` `filePathToVsixPath` = `` `extension/${originalFilePath}` `` |
| Default excluded files (`defaultIgnore`) | `src/package.ts:1658-1691` (lockfiles, `.vscodeignore`, `.github`, `**/.git`, `**/*.vsix`, `**/*.vsixmanifest`, `.vscode-test/**`, ...) plus `.vscodeignore` |
| NLS applied at package time for listing metadata | `src/package.ts:1468-1509` (`readManifest` reads `package.nls.json`, `patchNLS`) - so vsixmanifest DisplayName/Description are already English-resolved; `extension/package.json` keeps `%key%` |
| Unix modes are preserved in the zip | live `unzip -Z`: `-rwxr-xr-x ... extension/server/rust-analyzer` (40.7 MB) |

### Install (VS Code desktop, `vscode@0b16cb9 src/vs/platform/...`)

| Step | Where |
|---|---|
| 1 Resolve compatible gallery version (malicious list, deprecation auto-migrate, target platform, engine, pre-release) | `extensionManagement/common/abstractExtensionManagementService.ts:704-760`; `extensionGalleryService.ts:997-1044` (`isValidVersion`) |
| 2 Download `.vsix`; if signing enabled and `isSigned`, download `.sigzip`, check it is a zip containing `.signature.p7s`, run vsce-sign | `extensionManagement/node/extensionDownloader.ts:64-106,141-167` |
| 3 Signature policy (hard fail unless NotSigned and not required) | `node/extensionManagementService.ts:340-380` |
| 4 Read `extension/package.json` from the zip (the only manifest read) | `node/extensionManagementUtil.ts:24-36` (`buffer(vsixPath, 'extension/package.json')`) |
| 5 Check manifest id/version == gallery id/version ("manifest mismatch with Marketplace") | `node/extensionManagementService.ts:307-311` |
| 5b Sideloaded VSIX: `engines.vscode` check, allowed-extensions policy, no target platform | same file :145-175, :281 |
| 6 Extract **only entries under `extension/`** (strip prefix) to `<ext-dir>/.<uuid>` temp dir; path check "Invalid file" when the target dir escapes; Unix mode bits applied from zip external attributes | `node/extensionManagementService.ts:620-650` (`extract(zipPath, temp, { sourcePath: 'extension', overwrite: true })`); `base/node/zip.ts:53-59` (`modeFromEntry`), :75-80, :143-148, :320 |
| 7 Write metadata (`installedTimestamp`, `targetPlatform`, `size`) into the extracted manifest | `node/extensionManagementService.ts:655-669` |
| 8 Atomic rename temp -> `<publisher>.<name>-<version>[-<targetPlatform>]` | :675-688; folder name `ExtensionKey.toString()` `common/extensionManagementUtil.ts` (`${id}-${version}${tp !== UNDEFINED ? -tp : ''}`) |
| 9 Scan the installed folder (NLS, validation) and add to profile `extensions.json` | :696; `extensionsScannerService.ts` |
| `extension.vsixmanifest`, `[Content_Types].xml` | **never read** on install (grep of `src/vs/platform` and `src/vs/workbench/services` finds no reader); dropped because extraction keeps only `extension/` |

easyIDE equivalent must: (1) keep only `extension/`; (2) **preserve the executable bit** (java.util.zip does not expose external
attributes - needs Commons Compress `ZipArchiveEntry.getUnixMode()` or an equivalent reader; `PackageUnpacker.kt:38-61`
currently drops modes); (3) reject `..`/absolute/symlink entries (already done, `PackageUnpacker.kt:42-49`).

---

## (g) Dependency and extension-pack resolution

| Rule (VS Code) | Evidence |
|---|---|
| `extensionDependencies` not already installed are resolved; `extensionPack` members added only if new relative to the installed version of the pack | `abstractExtensionManagementService.ts:648-700` (`getAllDepsAndPackExtensions`) |
| Resolution is recursive with a `knownIdentifiers` set (cycle-safe), batch `galleryService.getExtensions(ids, {preRelease})` | same, :654-695 |
| A dependency that has no compatible version **fails the whole install**; an incompatible pack member is **skipped** ("Skipping the packed extension as it cannot be installed") | :683-691 |
| Deps/packs inherit pre-release preference: explicit `installPreReleaseVersion` -> prefer pre-release; explicitly chosen release -> release | :399-405 |
| Already-installed ids are skipped; tasks created with `pinned:false` and context `EXTENSION_INSTALL_DEP_PACK_CONTEXT` | :406-416 |
| VSIX sideload: if deps cannot be fetched, only a warning ("Cannot install dependencies of extension") | :417-430 |
| **Install order: all tasks run in parallel** (`joinAllSettled`), no topological order at install | :440-460 |
| Rollback: if the root task failed, deps/pack members installed by it are uninstalled (`versionOnly`) | :486-520 |
| **Ordering is enforced at activation**: "Cannot activate the '{0}' extension because it depends on unknown extension '{1}'" / "... because its dependency '{1}' failed to activate" | `workbench/api/common/extHostExtensionActivator.ts:313,404` |
| Open VSX refuses to publish unresolved dependencies ("Cannot resolve dependency") | `ovsx@edab3e4 publish/PublishExtensionVersionHandler.java:477-481` |
| API: `dependencies[]` and `bundledExtensions[]` (= pack) with `namespace/extension/url` | Extension schema |

easyIDE proposal (`ext.deps`, `ext.pack`): resolve the closure before the install sheet (one list with per-item platform,
version, licence, verified, size), apply the same fail/skip rule, commit each into the same scope, and run activation in
topological order. Scope rule: a dep of an ENVIRONMENT-scoped extension must be installed in the same environment (UNVERIFIED
design choice; VS Code has no equivalent - its closest is `extensionKind` placement).

---

## (h) NLS

| Rule | Evidence |
|---|---|
| Whole-string `%key%` only (`length > 1 && str[0] === '%' && str[length-1] === '%'`), recursive over objects/arrays | `extensionManagement/common/extensionNls.ts:30-80` |
| Missing key -> falls back to the original bundle, else warn "Couldn't find message for key" and keep literal | same :38-55 |
| `commands[].title/category` become `{value, original}` when translated differs | same :57-70 |
| Bundle lookup: `package.nls.<locale>.json`, then strip `-suffix` repeatedly, then `package.nls.json`; dev/pseudo/no language -> default | `extensionsScannerService.ts:909-930` (`findMessageBundles`) |
| Language-pack translations (`nlsConfiguration.translations["publisher.name"]`) take precedence | `extensionsScannerService.ts:825-845` |
| Runtime strings: `l10n` manifest field -> `bundle.l10n.<lang>.json` used by `vscode.l10n` | `workbench/services/extensions/common/extensionsRegistry.ts:642-645`; `workbench/api/common/extHostLocalizationService.ts:103` |
| vsce resolves `package.nls.json` into the vsixmanifest listing at package time; `extension/package.json` keeps placeholders | `vsce@c1eebf3 src/package.ts:1468-1509` |
| Open VSX: `displayName`/`description` come from the vsixmanifest (already English) and `localizedLanguages` from property `Microsoft.VisualStudio.Code.LocalizedLanguages`; no server-side `%key%` handling found (`grep -i "package.nls"` in `server/src/main` = no hits) | `ExtensionProcessor.java:266-271,318-322` |

easyIDE: `services/shared/extension-schema/src/main/kotlin/dev/easyide/extensions/manifest/Nls.kt:1-30` already implements
whole-string `%key%` with most-specific-locale-first bundles ("VS Code semantics"). Delta: the `{value, original}` pair for
command titles, language-pack translations (Not-planned), and the `l10n`/`bundle.l10n.*.json` runtime API (`l10n`, host side).

---

## (i) Updates, rollback, pre-release, deprecation, engines

| Topic | Facts |
|---|---|
| Update cadence (VS Code) | `extensionsWorkbenchService.ts:977` "UpdatesCheckInterval = 1000 * 60 * 60 * 12; // 12 hours"; settings `extensions.autoUpdate`, `extensions.autoCheckUpdates` (`workbench/contrib/extensions/common/extensions.ts:190,192`); `checkForUpdates` :2035, `autoUpdateExtensions` :2274 |
| Open VSX has no update push | FAQ: "Open VSX Registry itself does not provide any auto-update facility." The `version-changes` feed (preview) reports `ACTIVE/INACTIVE/REMOVED` transitions and can drive both update checks and takedown notices (ovsx-wiki `Registry-Changes-Feed.md`) |
| Pre-release | set by `vsce --pre-release` -> vsixmanifest property `Microsoft.VisualStudio.Code.PreRelease` (`vsce src/package.ts` Properties block); Open VSX stores it (`ExtensionProcessor.java:280-286`) and exposes `preRelease`; `latest` alias may point at a pre-release (see (b)). VS Code keeps latest release and latest pre-release separately (`filterLatestExtensionVersionsForTargetPlatform`) and refuses release-only install when none exists (`abstractExtensionManagementService.ts:728-731`) |
| Preview flag | `preview` = package.json `preview` (Marketplace "Preview" label); unrelated to pre-release |
| Deprecation | Extension `deprecated` + `replacement{url,displayName}`; source is the `extension-control/extensions.json` in open-vsx/publish-extensions, processed "by a batch job that runs nightly" (fdn-wiki `Managing-Extensions.md:25-29`; `extension_control/ExtensionControlService.java:133-178`). Live file: 944 `malicious`, 128 `deprecated` (entries like `{"disallowInstall": true, "extension": {"id": ..., "displayName": ...}}`), `migrateToPreRelease` 3, `search` 5 |
| VS Code deprecation/malicious | `checkAndGetCompatibleVersion` refuses malicious ("reported to be problematic") and auto-migrates `deprecated[].extension.autoMigrate` (`abstractExtensionManagementService.ts:704-720`); manifest from product `extensionsGallery.controlUrl` (`extensionGalleryService.ts:608,625,1916-1930`) |
| `engines.vscode` grammar | `extensions/common/extensionValidator.ts:36` `/^(\^|>=)?((\d+)|x)\.((\d+)|x)\.((\d+)|x)(\-.*)?$/`; `^` with major>0 frees minor+patch (:94-101) so `^1.101.0` = `>=1.101.0 <2.0.0`; `>=` = minimum (:163-185); `-YYYYMMDD[HH[MM]]` suffix = not-before product date (:37,103-110); major must be specific (`1.x.x` ok, `x.x.x` rejected, :359-371); `*` accepted only by `isEngineValid` (:343-346) |
| When engines are checked | gallery install (`extensionGalleryService.ts:1038-1061`), VSIX sideload (`node/extensionManagementService.ts:153-155`); at scan time only for extensions with code - "No version check for builtin or declarative extensions" (`extensionValidator.ts:333-341`) |
| Rollback in VS Code | installs a specific version and pins it ("Install Specific Version"); old version folders cleaned later. UNVERIFIED exact cleanup path - not needed for us |

easyIDE proposal (`ext.update`): keep ADR 0016 rules (notify, diff, tap; never auto-update), daily check using one
`v2/-/query` per installed id (or the `version-changes` feed since the last cursor - one request for all), `Retry-After`
respected. Declare an easyIDE "VS Code API version" constant (e.g. `1.10x.0`) and port `isValidVersion` exactly; add a
separate Kotlin port because `SemVerRange.kt` (npm semantics, `services/shared/.../manifest/SemVerRange.kt:1-20`) differs
(npm `^0.x` and date suffix rules). Pull `extension-control/extensions.json` (raw.githubusercontent.com works) for the
malicious list -> kill switch (`ext.killswitch`) and "deprecated, use X" banner.

---

## (j) Licence metadata

| Fact | Evidence |
|---|---|
| API `license` = the raw `package.json` `license` string, not normalised | `ExtensionProcessor.java:327` `extVersion.setLicense(packageJson.path("license").stringValue(null))`; only char/size checks (`ExtensionValidator.java:124-125`) |
| Licence **file** = vsixmanifest `<License>` path, else asset `Microsoft.VisualStudio.Services.Content.License` | `ExtensionProcessor.java:566-571`; served as `files.license` |
| vsce: `SEE LICENSE IN <file>` selects that file; otherwise `LICENSE`, `LICENSE.md`, `LICENSE.txt` (case-insensitive, `licen[cs]e`); a license without extension gets `.txt`; missing -> prompt "Do you want to continue?" | `vsce src/package.ts:1060-1108` |
| Publish refused only when both string and file are absent and `requireLicense` is on: "This extension cannot be accepted because it has no license." | `publish/PublishExtensionVersionHandler.java:368-383`; production value of `requireLicense` UNVERIFIED (not in `fdn-site` config) |
| Policy: "Yes, all extensions must be licensed. Your extension's license should be expressed by including a license expression in the package.json manifest." / may be non-OSI | FAQ |
| Missing licence -> MIT by agreement | Publisher Agreement :53 |
| VS Code docs: "If you do have a LICENSE file in the root of your extension, the value for license should be \"SEE LICENSE IN <filename>\"." | `docs/extension-manifest.txt` (license row) |

Client rule: display `license` as given; if it parses as an SPDX expression show the id(s) with a link, if it matches
`^SEE LICENSE IN (.+)$` or is absent/`UNLICENSED`/unparseable show "See licence" and open `files.license`; if both are absent
show "No licence declared (Open VSX publisher terms: MIT)" citing Agreement :53. Always keep the licence file in the installed
package (like ADR 0029 does for Material Icon Theme). Flag non-OSI/proprietary strings (e.g. `SEE LICENSE IN`) before install.

---

## (k) Open VSX malware / security policy

| Control | Evidence |
|---|---|
| Publish-time checks: secret detection (enforced), blocklist of file SHA-256 (enforced), name similarity (monitor only), namespace ownership vs VS Marketplace (enforced) | ovsx-wiki `Extension-Scanning.md:5-11,28-76`; `application-production.yml:76-117` |
| Threat scanners on every publish: ClamAV REST (enforced, required), YARA (enforced, required), Argus SaaS (enforced, async) | `application-production.yml:119-200` |
| Outcomes: PASSED, QUARANTINED ("at least one file that matched against an **enforced** threat scanner (e.g., Yara, ClamAV)"), AUTO REJECTED, ERROR; admin ALLOW/BLOCK | fdn-wiki `Extension-Scans.md:93-150,203-250` |
| API surfaces it as `reviewStatus` (`published`/`under_review`/`rejected`) + `reviewMessage` | Extension schema |
| Malicious list blocks re-publish of listed ids | `publish/PublishExtensionVersionHandler.java:461-475`; list at `https://github.com/open-vsx/publish-extensions/raw/master/extension-control/extensions.json` (`ExtensionControlService.java:177-178`) |
| Takedowns/revocations visible as `INACTIVE`/`REMOVED` in the changes feed; bulk revoke admin API exists | OpenAPI `/api/-/version-changes`, `/admin/api/publisher/bulk-revoke` |
| Content scan of ToU: Eclipse "MAKES NO REPRESENTATIONS OR WARRANTIES ABOUT THE SECURITY" | `terms-of-use.md:28` |

Implication: scanning is best-effort and says nothing about runtime behaviour (proot gives no isolation, ADR 0009 :108-115).
easyIDE should (1) refuse `reviewStatus != published`, (2) refuse ids on the `malicious` list at install and disable installed
ones on refresh (never auto-uninstall, ADR 0016 rule), (3) treat `REMOVED/INACTIVE` for an installed version as a revocation
notice, (4) keep the honest "as trusted as terminal code" copy.

---

## (l) easyIDE today - installer inventory and delta

### Inventory (WT@49725be, paths relative to WT)

| Component | Path:line | What it does |
|---|---|---|
| ADR 0005 env model | `docs/decision/0005-sandbox-environment-sharing-model.md:15-26` | environments shared by projects; env-scoped things live under `<files>/environments/<envId>/` |
| ADR 0009 tiers | `docs/decision/0009-extension-platform-tiers.md:22-26,41-56,108-115` | Open VSX "the only lawful registry"; Tier 2 = Node host; security is "disclosure and hygiene, not a boundary" |
| ADR 0013 package shape | `docs/decision/0013-extension-sdk-shape-manifest-easyext.md:24,29-30,44-46` | `.easyext` zip with VS Code-shaped `package.json` + `easyide` key; `.vsix` "a secondary, labelled source" |
| ADR 0016 registry | `docs/decision/0016-extension-registry-static-index-ed25519.md:24-42,48-50,76-87` | static signed git index, ed25519 root + publisher keys (RFC 8785 JCS), TOFU pins, revocations, hard-stop verification, never auto-update; Open VSX "opt-in secondary source (`extensions.openVsx.enabled`)"; Tink Ed25519 |
| ADR 0029 icon themes | `docs/decision/0029-material-icon-theme-default-and-icon-customisation.md:24-38,56` | Material Icon Theme vendored from pinned Open VSX vsix; "Open VSX browse is still not built" |
| LLD Open VSX adapter (proposed) | `docs/extension-sdk/lld/registry-and-install.md:334-357` | search/detail endpoints, sha256 only, `VsixReader` ignoring vsixmanifest, subset import (grammars, snippets, themes, iconThemes, configuration, keybindings), scope always GLOBAL, `CompatReport` |
| LLD storage | same :377-399 | `<files>/extensions/global/<id>/<version>` + `current`; env installs under `environments/<envId>/extensions/`; cache `<sha256>.easyext` |
| arch.md | `docs/extensions/arch.md:86-93,392-422` | Open VSX via `/vscode/gallery`; per-environment installs; no silent update; offline cache |
| Staging/unpack | `services/mobile/app/src/main/java/dev/easyide/app/extensions/install/PackageUnpacker.kt:35-95` | bounded copy (package/file/unpacked limits), rejects symlink entries and bad names; **drops Unix modes** |
| Local installer | `.../install/LocalInstaller.kt:76-332` | stage -> validate -> move into scope dir -> atomic `current` flip (:319-323) -> `state.json`; `commit(pkg, envId, source, origin)` :125-160 requires `envId` for ENVIRONMENT scope (:128); rollback :172-237 |
| Scope computation | `services/shared/extension-schema/src/main/kotlin/dev/easyide/extensions/manifest/ManifestChecks.kt:195-205` | ENVIRONMENT if `sandbox`, `languageServers`, sandbox capabilities or process actions; else GLOBAL |
| VS Code icon-theme adapter | `.../install/VsCodeIconThemeAdapter.kt:17-81` | for a picked `.vsix`/folder whose `package.json` has `engines.vscode`, no `engines.easyide`, and `contributes.iconThemes`: lifts `extension/` to root (:36,43,62-67), rewrites `package.json` to `{name,publisher lowercased, version, displayName, description, license, engines.easyide: ^AppApi.VERSION, categories:[Themes], contributes.iconThemes[{id,label,path}]}` (:48-59); everything else dropped. Wired at `ui/screens/extensions/ExtensionsViewModel.kt:347-358` |
| Static-index registry client | `.../registry/RegistryClient.kt:56-220`, `RegistryInstaller.kt:36-139`, `PackageCache.kt:10-96`, `HttpFetcher.kt:25-50`, `TrustStore.kt`, `RegistryPolicy.kt:7-34` | fetch `index.json`/`publishers`/`revocations` with ETag, verify root signatures (`RegistryVerifier.verifyIndex` :37, `verifyPublisher` :58), `trustEntry` (publisher key, revocation, TOFU/rotation) :71-106, `checkBytes` (size + sha256) :84; package cache by sha256; https-only redirects |
| Verifier core | `services/shared/extension-schema/src/main/kotlin/dev/easyide/extensions/registry/{Signatures.kt:16-109, RegistryVerifier.kt:30-122, Jcs.kt, JdkEd25519.kt}` | Ed25519 port, `SignatureVerifier.verifyPackage(archive, sig, key)` :84 already verifies a whole-archive signature |
| Source enum | `services/shared/extension-schema/src/main/kotlin/dev/easyide/extensions/manifest/ExtensionDescriptor.kt:19` | `BUILT_IN, REGISTRY, OPEN_VSX, SIDELOAD, DEV` (OPEN_VSX has UI strings only) |
| NLS | `services/shared/.../manifest/Nls.kt` | `%key%` substitution (see (h)) |
| Limits | `services/shared/.../settings/ExtensionSettings.kt:22-24` | `packageMb` 50, `unpackedMb` 200, `fileMb` 20 |

What the static-index registry verifies (for contrast): root-key signature over RFC 8785 canonical JSON of index/publishers/
revocations; per-entry publisher-key signature; package `size` + `sha256`; revocations; TOFU publisher pin with signed
rotation (ADR 0016:30-42). None of that exists for Open VSX; there, trust is "registry key signed these bytes".

### Delta for an Open VSX client (gap classes per COMMON.md)

| # | Need | State | Class | Effort | Risk |
|---|---|---|---|---|---|
| 1 | HTTP client for `/api/-/search`, `/api/v2/-/query`, `/api/{ns}/{name}/{tp}/{v}`, files, public key; 429/`Retry-After` handling, <=1 RPS, time-based metadata cache | no Open VSX code (grep `OpenVsx`/`open-vsx` in `services/**/*.kt`: only UI strings `ExtensionText.kt:21,31` and the adapter comment) | Missing-backend | M | Low |
| 2 | Browse/detail UI: search, categories, sort, verified badge, publisher, licence, deprecated/replacement, pre-release toggle, platform availability | none ("Open VSX browse is still not built", ADR 0029:56) | Missing-UI | M | Low |
| 3 | Target-platform resolver (linux-arm64 > universal, two queries, never alpine/web/armhf/x64) + `engines.vscode` port | none; `SemVerRange.kt` is npm-semantics | Missing-backend | S | Med (wrong pick = broken native binary) |
| 4 | sha256 check | generic `checkBytes` exists for index entries (`RegistryVerifier.kt:84`) | Partial | S | Low |
| 5 | `.sigzip` Ed25519 verification with pinned Open VSX key | primitive exists (`TinkEd25519`, `SignatureVerifier.verifyPackage`); no sigzip/PEM handling; ADR 0016/LLD assume "sha256 only" | Partial | S | Low |
| 6 | Full `.vsix` install (not subset): keep `extension/` only, preserve exec bits, keep `package.json` intact, `extension.vsixmanifest` ignored like VS Code | unpacker drops modes; only icon-theme adapter converts `.vsix`; LLD sec 9 designs a subset rewrite with scope forced GLOBAL | Partial | M | High (exec bits: rust-analyzer ships `extension/server/rust-analyzer` `-rwxr-xr-x`) |
| 7 | Size limits for real extensions | 50/20/200 MB defaults; live: redhat.java linux-arm64 vsix = 132,370,233 bytes; rust-analyzer binary 40.7 MB > `fileMb` 20 | Partial | S | Med |
| 8 | Scope for Tier-2 (code) extensions: they run in the environment's Node host -> ENVIRONMENT scope (ADR 0005 + arch.md "Extensions are per-environment"); LLD sec 9 says Open VSX is always GLOBAL (valid only for declarative subset) | design conflict | Missing-backend | S (decision) | Med |
| 9 | Dependency/pack closure, fail/skip rules, activation order | none (sdk has no `extensionDependencies` resolver for registry installs) | Missing-both | M | Med |
| 10 | Malicious/deprecated list (`extension-control/extensions.json`) + `reviewStatus` + changes feed -> kill switch/banners | revocation machinery exists for the static index only | Partial | S | Low |
| 11 | Update check (daily, notify-only), pre-release channel per extension, rollback via retained version | rollback + `current` flip exist (`LocalInstaller.kt:172-237`); no Open VSX version source | Partial | S | Low |
| 12 | Licence display + keep licence file; ToU/licence acceptance line in install sheet | none for Open VSX | Missing-UI | S | Low |
| 13 | NLS parity (`{value, original}` command titles, `l10n` bundles) | `%key%` done (`Nls.kt`) | Partial | S | Low |
| 14 | `browser`-only extensions | cannot run in Node host | Not-planned (needs web-worker host) | - | - |
| 15 | Docs corrections: ADR 0016:48-49 and LLD :343 ("sha256 only"), LLD :544 open issue 5 (answered here: `files.sha256` present on all sampled versions; field names verified), arch.md :91-93 (prefer native API over `/vscode/gallery`) | stale | - | S | Low |

### UNVERIFIED (how to verify)
- Share of active versions lacking `files.signature`/`files.sha256` -> sweep `/api/v2/-/query?...includeAllVersions` over the
  corpus (respect 10,800 req/h).
- Whether `If-None-Match`/304 works on any file endpoint and whether CDN downloads count toward the limit -> instrumented probe.
- Header/token format for rate-limit tiers -> read `ovsx@edab3e4 server/.../ratelimit/IdentityService.java`.
- Production `requireLicense` value -> ask Eclipse or read deployed base `application.yml` (not in `fdn-site`).
- `@vscode/vsce-sign` handling of Open VSX sigzip -> irrelevant for us (licence), left open.
- Eclipse general ToU (`https://www.eclipse.org/legal/termsofuse.php`) clauses on automated access -> text fetched, not analysed.
- How many universal `browser`-only extensions would work in Node anyway -> corpus run.
