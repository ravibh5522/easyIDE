/**
 * Extension runtime (pure JVM): contribution stores (`contrib`), when-clause evaluation and
 * context keys (`whenclause`), the L1 action engine (`action`) and enablement/activation
 * (`host`). Manifest parsing, schema validation, capability rules and signatures live in the
 * Apache-2.0 `:extension-schema` module (services/shared/extension-schema, decision 0015)
 * under the same package names. The app constructs [dev.easyide.extensions.ExtensionsRuntime].
 * Design: docs/extension-sdk/lld/extension-runtime.md; contract: docs/extension-sdk/sdk-reference.md.
 */
package dev.easyide.extensions
