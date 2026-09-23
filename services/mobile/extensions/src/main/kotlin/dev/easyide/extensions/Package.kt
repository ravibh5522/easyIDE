/**
 * Extension runtime (pure JVM): manifest schema validation and parsing (`manifest`, `schema`),
 * contribution stores (`contrib`), when-clauses and context keys (`whenclause`), the L1 action
 * engine (`action`), capabilities (`capability`) and enablement/activation (`host`). The app
 * constructs [dev.easyide.extensions.ExtensionsRuntime]. Design:
 * docs/extension-sdk/lld/extension-runtime.md; contract: docs/extension-sdk/sdk-reference.md.
 */
package dev.easyide.extensions
