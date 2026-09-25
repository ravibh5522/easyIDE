# UI Redesign - UI Kit and Engineering Plan

Status: PROPOSED (2026-09-24). Part of [arch.md](arch.md). Rules it enforces: [ux-rules.md](ux-rules.md).
Properties it reads: [properties.md](properties.md). Structure it hosts: [shell-model.md](shell-model.md).

The kit is the **only** place raw Material widgets appear. Screens, panels, documents and
extension views are composed from kit primitives that read the token model, so identity,
density, corners, scale and accent change everywhere at once. This document specifies the
kit, what it replaces today, the token refactors it needs, and how it is tested. Figures come
from a code survey of `services/mobile/app/src/main/java/dev/easyide/app/ui` on 2026-09-24
(commit `62b8672`).

## 1. Baseline (measured)

| Area | Lines | Note |
|---|---|---|
| Non-workspace UI (home, settings, extensions, onboarding, newproject, components) | 8,590 | Home 2,363; Settings 2,910; Extensions 1,398; Onboarding 971; NewProject 529; components 419 |
| Stock widgets used directly | - | 60 `TextButton`, 21 `OutlinedTextField`, 29 `AlertDialog` (15 files, 7 in workspace), 12 `ListItem`, 8 `TopAppBar`, 8 `Scaffold`, 8 `FilterChip`, 8 `Button`, 6 `Card`, 4 `Switch` |
| Reads of `MaterialTheme.colorScheme` | 81 | in the five screen directories |
| Raw `N.dp` literals | 14 | `Spacing.` is used 130 times |
| Reads of `editorColors` | 31 files | the coding screen's palette accessor |
| UI tests | 0 | no `testTag`s, no Compose UI tests; the only structural dependency is the baseline-profile generator selectors |

The workspace already has a kit seed: `screens/workspace/ChromeControls.kt` (124 lines:
`DenseTextField`, `ChromeButton`) plus `ActivityBar` and `StatusBar`. Those are promoted into
the kit, not rewritten.

## 2. Packages

Each kit file stays under 150 lines; the kit imports no ViewModel, navigation or
extension-adapter code.

| Package | Contents |
|---|---|
| `ui/props/` | `Appearance`, `UiMetrics`, `Motion`, `Feel`, `AppearanceStore` (DataStore/settings), `LocalMetrics`, `LocalMotion`, `LocalFeel` |
| `ui/kit/` | `Tone`, the `Kit` accessor object, primitives (section 3), motif drawables (`CursorBlock`, `CropCorners`, `PromptGlyph`, `CellFillBar`), `KitSemantics` (touch floor, roles, headings), `Haptics` |
| `ui/shell/` | the shell engine: `ShellState`, `NavRegistry`, `ContainerRegistry`, `DocumentRegistry`, `PanelHost`, `StageHost` (editor groups), `NavSurface` (bottom bar / rail), `InputDock`, `AdaptiveScaffold`, `LayoutPresets` |
| `ui/shell/slots/` | `ShellSlots` and the moved extension view code (`ExtensionSlots.kt`) |
| `ui/icons/` | the custom vector set, the resolver with Material fallback |

Token reads: the kit reads `editorColors` (ADR 0019: chrome and editor are projections of one
`ThemeTokens`), `MaterialTheme.typography`, and `Kit.space/radius`. It does not read
`MaterialTheme.colorScheme`; `MaterialTheme` remains only as a bridge for any not-yet-migrated
Material widget and is removed at the end.

## 3. Primitives

Signatures are indicative. All take `modifier` first-optional; components with state
variants take `tone: Tone = Neutral` (Neutral, Accent, Success, Warning, Danger, Info; the one
mapping from tone to `ThemeTokens` colours lives in `Tone`).

```kotlin
@Immutable data class UiMetrics(val density: Density, val corners: Corners,
    val fontScale: Float, val touchFloor: Dp = 44.dp)   // space, radius, control derived

@Composable fun KitScaffold(title: String, onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {}, banner: (@Composable () -> Unit)? = null,
    maxContentWidth: Dp = Kit.contentMax, content: @Composable (PaddingValues) -> Unit)
@Composable fun KitSection(title: String?, description: String? = null,
    content: @Composable ColumnScope.() -> Unit)        // caps header (with prompt glyph) + KitGroup
@Composable fun KitGroup(tone: Tone = Neutral, content: @Composable ColumnScope.() -> Unit)
@Composable fun KitRow(title: String, subtitle: String? = null, leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null, onClick: (() -> Unit)? = null,
    selected: Boolean = false, enabled: Boolean = true, mono: Boolean = false)
@Composable fun KitField(value: String, onValueChange: (String) -> Unit, label: String? = null,
    hint: String? = null, error: String? = null, singleLine: Boolean = true, mono: Boolean = false,
    keyboard: KeyboardOptions = Default, trailing: (@Composable () -> Unit)? = null)
@Composable fun KitButton(text: String, onClick: () -> Unit, style: KitButtonStyle = Primary,
    icon: ImageVector? = null, loading: Boolean = false, enabled: Boolean = true)
@Composable fun KitIconButton(icon: ImageVector, contentDescription: String, onClick: () -> Unit,
    tone: Tone = Neutral, enabled: Boolean = true)
@Composable fun KitTag(text: String, tone: Tone = Neutral, selected: Boolean = false,
    icon: ImageVector? = null, onClick: (() -> Unit)? = null)
@Composable fun KitBanner(text: String, tone: Tone = Info, action: KitAction? = null, onDismiss: (() -> Unit)? = null)
@Composable fun KitDialog(title: String, onDismiss: () -> Unit, confirm: KitAction? = null,
    dismiss: KitAction? = null, tone: Tone = Neutral, content: @Composable ColumnScope.() -> Unit)
@Composable fun KitTabs(labels: List<String>, selected: Int, onSelect: (Int) -> Unit, style: TabStyle = Underline)
@Composable fun KitToggle(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?, kind: ToggleKind = Switch)
@Composable fun KitMenu(expanded: Boolean, onDismiss: () -> Unit, items: List<KitMenuItem>)
@Composable fun KitEmptyState(art: EmptyArt, message: String, action: KitAction?)
@Composable fun KitProgress(fraction: Float?, tone: Tone = Accent)   // null = cursor sweep
@Composable fun <T> KitChoice(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit)
@Composable fun KitStepper(steps: Int, current: Int)                // onboarding hero
```

### 3.1 Anatomy and states

| Primitive | Anatomy and rules |
|---|---|
| `KitGroup` | raised surface tone, 1dp hairline, radius `m`; separators drawn with `drawBehind`, inset to the text edge; no shadow; may not contain another group |
| `KitRow` | min height 48 (compact width; 44 medium, 40 expanded), padding 16 horizontal; leading slot (icon 20dp or mark, no tinted circle), title 13/Medium, support 12 muted (max 2 lines), trailing slot (value in mono, toggle, chevron, up to two icon buttons); selected = solid block marker at the left edge; pressed = tone step, focus = 2dp accent ring |
| `KitField` | flat, hairline border that becomes the accent ring on focus; label above (11 caps optional), error below in the error tone with the error rule; `mono` for paths/JSON; clear button when non-empty |
| `KitButton` | Primary (accent fill, on-accent text), Secondary (hairline outline), Ghost (text), Danger (error text on ghost, error fill only inside a destructive dialog); height 40 (48 in dialogs on compact); loading = cursor sweep replacing the label |
| `KitTag` | 20dp pill-free chip: mono 11, hairline, tone tint at 12% alpha; selectable variant fills with the tone |
| `KitDialog` | wraps a platform dialog with a focus trap and Back handling; radius `l`, hairline, overlay tone, title 15/Medium, no icon; **bottom sheet on compact**; actions ghost-left / primary-right |
| `KitTabs` | Underline = 2dp accent bar under the selected label (matches the editor tabs); Segmented = joined outlined segments with a CLOCK_TICK on change |
| `KitEmptyState` | up to five lines of ASCII/box-drawing art in a muted token + one sentence + one command action |
| `KitProgress` | determinate = cell-fill bar of blocks; indeterminate = cursor sweeping eight cells at 90ms per step after a 300ms delay |
| `CursorBlock` | drawn rectangle; states solid, hollow, blinking; the only user of the infinite transition |
| `CropCorners` | four 6dp L-marks; parameterised by tone; at most one per view |
| `NavSurface` | bottom bar (56dp, max five visible, "More" sheet) or rail (56dp, labels optional); active item = block marker + accent icon; badges as dot or capped number |
| `InputDock` | IME-aware container above the keyboard; tool row and key row; horizontal overflow scroll |

Every interactive primitive: 44dp minimum target, role and state semantics, visible focus,
hover state with a pointer, pressed state, disabled state, `testTag` derived from a stable id.

## 4. Extension slots stay intact

The extension adapters (`MenuModel`, `StatusItems`, `KeyRows`, `ActiveKeyRow`) are pure,
Compose-free and tested; they stay unchanged. Only one view file draws their output
(`workspace/ext/ExtensionSlots.kt`, 176 lines). The shell defines:

```kotlin
interface ShellSlots {
    @Composable fun titleActions()
    @Composable fun statusItems(side: StatusBarAlignment)
    @Composable fun touchToolbar()
    @Composable fun keyRow(surface: KeySurface)
    fun navigationItems(scope: ShellScope): List<NavItem>
    fun containers(placement: Placement): List<ContainerSpec>
    fun documentTypes(): List<DocumentType>
}
```

`WorkspaceContributions` implements it by delegating to the existing `menu()`,
`statusItems()`, `keyRow()`; the new methods read the new registries fed by the runtime
(see [extension-ui.md](extension-ui.md)). The five composables in `ExtensionSlots.kt` are
re-implemented on `KitIconButton`, `KitMenu` and kit text buttons; `EDITOR_TITLE_MAX_INLINE`
and the `ICON_TOKENS` table are kept. The stage settings `easyide.stages.defaultStage` and
`workbench.stages.placement` map onto container placement; `viewsContainers.activitybar`
maps to a sidebar container plus a navigation item.

## 5. What it replaces (inventory)

| Area (files, lines) | Stock widgets today | Kit replacement |
|---|---|---|
| Settings: `SettingsScreen` 384, `SettingRow` 268, `ThemePicker` 249, `IconThemePicker` 78, `KeyRowPicker` 105, `KeybindingsDialog` 194, `LanguageServersDialog` 179, `SettingsDialogs` 164, `ProjectTrustDialog` 65, `SettingsJsonEditor` 174, layer bar / storage rows / widgets 266 | Scaffold, TopAppBar, ListItem, Card, DropdownMenu, Switch, RadioButton, FilterChip, SuggestionChip, OutlinedTextField, AlertDialog | `KitScaffold`, `KitSection`, `KitRow`, `KitToggle`, `KitChoice`, `KitTag`, `KitField`, `KitMenu`, `KitDialog`; settings become **documents** with a category list panel |
| Extensions: `ExtensionsScreen` 309, `BrowseSection` 186, `InstallDialogs` 160, `CreateExtensionDialog` 123, `RollbackDialogs` 60 | Scaffold, TabRow, Card, Switch, DropdownMenu, AlertDialog, FilterChip, CircularProgressIndicator | `KitTabs`, `KitGroup`, `KitRow`, `KitToggle`, `KitDialog`, `KitProgress`; extension detail becomes a document |
| Home: `HomeScreen` 241, list 180, `ProjectCard` 156, `ProjectDetailPane` 215, tiles 118, empty states 136, dialogs 540 | Scaffold, TopAppBar, Button, OutlinedButton, Surface, DropdownMenu, AlertDialog | shell app scope + "Now" page + `KitRow`/card recipe + `KitEmptyState` + `KitDialog` |
| NewProject 342, Onboarding 313 + 289 | Scaffold, ListItem, FilterChip, Button, LinearProgressIndicator | `KitScaffold`, `KitRow`, `KitTag`, `KitField`, `KitButton`, `KitStepper`, `KitProgress` |
| Components: `ChoiceCard` 89, `EnvironmentBadge` 81, `ContextMenu` 34, `EmptyState` 106, `Skeleton` 31, `FolderPicker` 52 | RadioButton, Surface | `KitRow(selected)`, `KitTag`, `KitMenu`, `KitEmptyState`; `FolderPicker` and `Skeleton` stay |
| Workspace stock leftovers | 7 `AlertDialog` (unsaved, file context, LSP dialogs, extension prompts), `DropdownMenu` in `ExtensionSlots` | `KitDialog`, `KitMenu` |

Delete when done: `components/ChoiceCard.kt`, `components/EmptyState.kt`, the static
`EasyIdeShapes`, `SettingsWidgets` `SectionHeader`/`contentWidth`, the private `Section`/`Mono`/
`SafeModeBanner` copies in Extensions and Settings, `HomeTopBar`, `workspace/ChromeControls.kt`
(moved), then every remaining Scaffold/TopAppBar/ListItem/Card/FilterChip/AlertDialog import.

## 6. Token refactors required

| # | File | Change |
|---|---|---|
| 1 | `theme/Dimens.kt` `Spacing` | keep the 4dp grid as base constants; add `SpacingScale(density)` exposed as `Kit.space`; give the 130 call sites a composable getter or a codemod (find non-composable uses first: draw lambdas, top-level vals) |
| 2 | `Dimens.kt` `Radius` | add `RadiusScale(corners)` (sharp 0, soft 1, round 1.6 times); clamp to half the control height at use |
| 3 | `theme/Shape.kt` | `EasyIdeShapes` (static) becomes `easyIdeShapes(metrics)` so leftover Material widgets follow the corner setting |
| 4 | `Dimens.kt` `ControlSize`/`IconSize` | scale row/tab/header/key heights by density; add `touchFloor = 44.dp` that hit boxes may not go below |
| 5 | `theme/Type.kt` | `EasyIdeTypography` becomes `easyIdeTypography(fontScale, pairing)`; replace literal line heights with a per-role line/size ratio (`TypeStep(size, lineRatio, weight)`), so scaling never collapses leading |
| 6 | `Type.kt` `EasyIdeFonts` | add the pairing choice (geist / monoChrome / system) behind `appearance.fontPairing`; editor mono stays independent |
| 7 | `theme/Theme.kt` | `EasyIdeTheme` takes `Appearance`; provides `LocalMetrics`, `LocalMotion`, `LocalFeel` and the scaled `LocalDensity`; generalise the DYNAMIC `dynamicAccent` into an `accentOverride` valid in any mode |
| 8 | `ThemeTokens.kt`, `Palette.kt` | add `withAccent(Accent)` re-deriving the accent family; add `Accent.forBackground(bg)` choosing ON_ACCENT for AA |
| 9 | `EditorColors.kt`, new `theme/Tone.kt` | semantic accessors (`surface`, `raised`, `content`, `muted`, `hairline`, `accent`) and `Tone.container()/content()` from `ThemeTokens`, so kit and screens stop reading `MaterialTheme.colorScheme` |
| 10 | `ColorSchemeMapping.kt` | map `primary`, `primaryContainer`, `surfaceTint`, `secondary` from the overridden accent and keep `surfaceContainer*` on the panel/raised/overlay tokens, so a stock widget left mid-migration does not show the original accent |
New settings for properties go in a **new** file (an `AppearanceSettingsSchema`), not in
`SettingsSchema.kt`, which several in-flight branches edit.

## 7. Testing

| Kind | What |
|---|---|
| Import ratchet (JVM) | fails on banned Material imports outside `ui/kit`; allowlist starts with today's files and only shrinks |
| Unit (JVM) | `UiMetrics` monotonic and floored; `Appearance` codec (defaults, unknown keys, string ids, round trip); typography line height >= 1.15 x size; accent guard (200 random hues keep AA); tone mapping exhaustive; existing `extensions/adapters` tests stay unmodified and green (the slot contract) |
| Screenshot | Roborazzi 1.75.0 (Apache-2.0, Maven Central release 2026-09-21, repo pushed 2026-09-24) + Robolectric 4.17 (MIT, released 2026-09-10) in the JVM unit-test task. **Spike passed** on AGP 9.3.1 / Kotlin 2.4.10 / compileSdk 37: the gallery golden `KitGalleryGoldenTest` renders a composable in `EasyIdeTheme` and the bundled Geist Sans and Mono load and draw (checked in the PNG and by `ResourcesCompat.getFont`). Three settings are required and live in `app/build.gradle.kts`: `unitTests.isIncludeAndroidResources = true`, test JVM arg `--add-exports=java.base/jdk.internal.access=ALL-UNNAMED` (Robolectric's SDK 36+ shared-memory shadow fails on JDK 21 without it), and `@Config(sdk = [35])` on screenshot tests (Espresso from `ui-test-junit4` calls `InputManager.getInstance()`, absent in Robolectric's SDK 37 jar, so the default SDK fails with `NoSuchMethodException`). Record `:app:recordRoborazziDebug`, verify `:app:verifyRoborazziDebug` (a changed pixel fails it, checked); goldens in `app/src/test/screenshots`. Paparazzi is not adopted (only an alpha is current). Dropshots 0.6.0 stays the on-device fallback, not needed |
| Semantics | `androidx.compose.ui:ui-test-junit4` (BOM-managed) for role/state/name assertions on the gallery |
| Golden matrix | on the gallery only: density x corners x font scale {1, 1.3, 2} x light/dark; each migrated screen has one golden at default properties |
| On-device | adb + uiautomator: `wm size`/`density`, `font_scale` 1.3 and 2.0, night mode; assert every clickable node has bounds >= 44dp and text or description; no truncation at 200%; `dumpsys gfxinfo` jank before/after a Settings scroll; regenerate the baseline profile after Home and Onboarding change (its selectors use the Continue string resource and a scrollable Home list, so keep both) |
| Release | R8 build installed and smoke-run for every migrated screen |

## 8. Risks

| Risk | Mitigation |
|---|---|
| Performance of Settings and Extensions (LazyColumn) | immutable kit params, no `Modifier.composed`, hairlines via `drawBehind`, recomposition counts checked on `SettingRow` |
| R8 renames enums | persist by explicit string id; smoke a release build per phase |
| Accessibility regressions (Material gave 48dp, roles, focus traps for free) | the kit re-adds them (section 3.1) and the U-A11Y/U-INT rules are tested |
| 600-line cap | `WorkspaceViewModel` and `EditorPane` are near the cap and the in-flight branches add lines: split during merge, not during the redesign |
| Merge conflicts with in-flight branches | see [arch.md](arch.md) section 8 (sequencing) |
| Kit becomes a second Material | small surface (about 20 primitives), one file each, no theming parameters beyond `Tone` and the properties |
