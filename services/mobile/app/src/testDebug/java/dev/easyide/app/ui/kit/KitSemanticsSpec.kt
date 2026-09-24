package dev.easyide.app.ui.kit

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import dev.easyide.app.ui.theme.EasyIdeTheme
import dev.easyide.app.ui.theme.ThemeMode
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The accessibility contract of every interactive kit primitive (kit.md 3.1): a role, a name (its
 * text or content description), its enabled / selected / toggle state, and a hit box that follows
 * the width class (density.md 2). On a phone (COMPACT, Comfortable) an isolated control's layout
 * box is at least the 44dp touch floor; on a wide window (Dense) it is the 32dp hit box and the
 * touch region, which Compose pads without changing layout, is at least 40dp. Rows, fields, tabs
 * and menu items are the control tokens tall. Runs on the JVM under Robolectric with the same
 * three settings as the screenshot spike (`app/build.gradle.kts`); debug only, because the compose
 * test manifest is.
 */
abstract class KitSemanticsSpec(
    /** Layout box of a small control, and the touch region the theme's floor gives it. */
    private val hitBox: Dp,
    private val touchFloor: Dp,
    private val rowHeight: Dp,
    private val fieldHeight: Dp,
    private val tabHeight: Dp,
) {
    abstract val indentStep: Dp


    @get:Rule val compose = createComposeRule()

    private fun show(content: @Composable () -> Unit) = compose.setContent { EasyIdeTheme(themeMode = ThemeMode.DARK) { content() } }

    private fun SemanticsNodeInteraction.hasRole(role: Role) = assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, role))

    private fun SemanticsNodeInteraction.assertTouchFloor(): SemanticsNodeInteraction {
        val box = getBoundsInRoot()
        assertTrue("hit box ${box.width} x ${box.height} is under $hitBox", box.width >= hitBox && box.height >= hitBox)
        val touch = fetchSemanticsNode().touchBoundsInRoot
        val density = compose.density.density
        assertTrue("touch region ${touch.width / density} x ${touch.height / density} is under $touchFloor", touch.width / density >= touchFloor.value - 0.5f && touch.height / density >= touchFloor.value - 0.5f)
        return this
    }

    private fun assertMinHeight(node: SemanticsNodeInteraction, min: Dp) {
        val height = node.getBoundsInRoot().height
        assertTrue("height $height is under $min", height >= min)
    }

    @Test fun `button has role, name, states and the touch floor`() {
        show {
            KitButton("Save", {})
            KitButton("Off", {}, enabled = false)
            KitButton("Wait", {}, loading = true)
        }
        compose.onNodeWithText("Save").hasRole(Role.Button).assertIsEnabled().assertTouchFloor()
        compose.onNodeWithText("Off").hasRole(Role.Button).assertIsNotEnabled().assertTouchFloor()
        compose.onNodeWithText("Wait").assertIsNotEnabled().assertTouchFloor()
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.StateDescription))
    }

    @Test fun `icon button is named by its description`() {
        show {
            KitIconButton(Icons.Filled.Delete, "Delete", {})
            KitIconButton(Icons.Filled.Delete, "Delete off", {}, enabled = false)
        }
        compose.onNodeWithContentDescription("Delete").hasRole(Role.Button).assertIsEnabled().assertTouchFloor()
        compose.onNodeWithContentDescription("Delete off").hasRole(Role.Button).assertIsNotEnabled().assertTouchFloor()
    }

    @Test fun `tappable tag reports selected and keeps the touch floor`() {
        show {
            KitTag("On", selected = true, onClick = {})
            KitTag("Off", onClick = {})
        }
        compose.onNodeWithText("On").hasRole(Role.Button).assertIsSelected().assertTouchFloor()
        compose.onNodeWithText("Off").hasRole(Role.Button).assertIsNotSelected().assertTouchFloor()
    }

    @Test fun `toggles report their role and on-off state`() {
        show {
            KitToggle(true, {}, kind = ToggleKind.Switch)
            KitToggle(false, {}, kind = ToggleKind.Check)
            KitToggle(true, {}, kind = ToggleKind.Radio)
            KitToggle(false, {}, kind = ToggleKind.Switch, enabled = false)
        }
        val toggles = compose.onAllNodesWithTag("kit:toggle")
        toggles[0].hasRole(Role.Switch).assertIsOn().assertTouchFloor()
        toggles[1].hasRole(Role.Checkbox).assertIsOff().assertTouchFloor()
        toggles[2].hasRole(Role.RadioButton).assertIsSelected().assertTouchFloor()
        toggles[3].assertIsNotEnabled().assertTouchFloor()
    }

    @Test fun `tappable row is a button named by its title with selected state`() {
        show {
            KitRow("Plain", onClick = {})
            KitRow("Chosen", selected = true, onClick = {})
            KitRow("Locked", enabled = false, onClick = {})
        }
        // An unselected row says nothing about selection, so a screen reader does not announce "not selected".
        compose.onNodeWithText("Plain").hasRole(Role.Button).assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Selected)).assertHasClickAction()
        assertMinHeight(compose.onNodeWithText("Plain"), rowHeight)
        compose.onNodeWithText("Chosen").assertIsSelected()
        compose.onNodeWithText("Locked").assertIsNotEnabled()
    }

    @Test fun `tabs are tab-role nodes with one selected`() {
        show { KitTabs(listOf("Files", "Search", "Git"), selected = 1, onSelect = {}) }
        compose.onNodeWithText("Files").hasRole(Role.Tab).assertIsNotSelected()
        assertMinHeight(compose.onNodeWithText("Files"), tabHeight)
        compose.onNodeWithText("Search").hasRole(Role.Tab).assertIsSelected()
        compose.onNodeWithText("Git").hasRole(Role.Tab).assertIsNotSelected()
    }

    @Test fun `segmented choice is tabs and a long choice is radio rows`() {
        show {
            KitChoice(listOf(0, 1), 0, { if (it == 0) "A" else "B" }, {})
            KitChoice(listOf(0, 1, 2), 2, { "A rather long option label number $it" }, {})
        }
        compose.onNodeWithText("A").hasRole(Role.Tab).assertIsSelected()
        assertMinHeight(compose.onNodeWithText("A"), fieldHeight)
        compose.onNodeWithText("B").assertIsNotSelected()
        compose.onNodeWithText("A rather long option label number 2").hasRole(Role.RadioButton).assertIsSelected()
        assertMinHeight(compose.onNodeWithText("A rather long option label number 2"), rowHeight)
        compose.onNodeWithText("A rather long option label number 0").assertIsNotSelected()
    }

    @Test fun `field is named by its label, shows its error and is at least a touch target tall`() {
        show { KitField("value", {}, label = "Name", error = "Taken") }
        val field = compose.onNodeWithContentDescription("Name")
        field.assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Error))
        assertMinHeight(field, fieldHeight)
    }

    @Test fun `banner dismiss and action are named buttons with the touch floor`() {
        show { KitBanner("Heads up", action = KitAction("Retry") {}, onDismiss = {}) }
        compose.onNodeWithText("Retry").hasRole(Role.Button).assertTouchFloor()
        compose.onNodeWithContentDescription("Dismiss").hasRole(Role.Button).assertTouchFloor()
    }

    @Test fun `empty state action is a named button with the touch floor`() {
        show { KitEmptyState(EmptyArt.Prompt, "Nothing here", action = KitAction("New file") {}) }
        compose.onNodeWithText("New file").hasRole(Role.Button).assertTouchFloor()
    }

    @Test fun `scaffold back is a named button with the touch floor`() {
        show { KitScaffold("Page", onBack = {}) {} }
        compose.onNodeWithContentDescription("Back").hasRole(Role.Button).assertTouchFloor()
    }

    @Test fun `dialog actions are named buttons`() {
        show { KitDialog("Rename", {}, confirm = KitAction("Save") {}, dismiss = KitAction("Cancel") {}) {} }
        compose.onNodeWithText("Save").hasRole(Role.Button).assertTouchFloor()
        compose.onNodeWithText("Cancel").hasRole(Role.Button).assertTouchFloor()
    }

    @Test fun `menu entries are named buttons and a checked entry exposes its state`() {
        show {
            KitMenu(
                expanded = true,
                onDismiss = {},
                items = listOf(
                    KitMenuItem.Action("Open", {}),
                    KitMenuItem.Action("Word wrap", {}, checked = true),
                    KitMenuItem.Action("Locked", {}, enabled = false),
                ),
            )
        }
        compose.onNodeWithText("Open").hasRole(Role.Button).assertIsEnabled()
        assertMinHeight(compose.onNodeWithText("Open"), rowHeight)
        compose.onNodeWithText("Word wrap").assertIsOn()
        compose.onNodeWithText("Locked").assertIsNotEnabled()
    }

    @Test fun `row is one line tall at the row token and a two column row keeps it`() {
        show {
            KitRow("Title", subtitle = "A description that sits inline after the title", trailing = { KitTag("v1") })
            KitTwoColumnRow("Setting", description = "About it", id = "two-column") { KitToggle(true, {}) }
        }
        val row = compose.onNodeWithText("Title").getBoundsInRoot()
        assertTrue("row ${row.height} is not the row token $rowHeight", row.height >= rowHeight && row.height < rowHeight + 8.dp)
        assertMinHeight(compose.onNodeWithTag("kit:two-column"), rowHeight)
    }

    @Test fun `row leading columns are fixed so titles align`() {
        show {
            KitRow("Alpha", leading = { KitTag("a") }, twistie = Twistie.Leaf)
            KitRow("Beta", leading = { KitTag("b") }, twistie = Twistie.Expanded)
            KitRow("Gamma", leading = { KitTag("c") }, twistie = Twistie.Collapsed)
        }
        val lefts = listOf("Alpha", "Beta", "Gamma").map { compose.onNodeWithText(it, useUnmergedTree = true).getBoundsInRoot().left }
        assertTrue("title edges $lefts differ", lefts.all { (it - lefts.first()).value in -0.5f..0.5f })
    }

    @Test fun `a nested row is indented by exactly one indent step`() {
        show {
            KitRow("Parent", twistie = Twistie.Expanded)
            KitRow("Child", twistie = Twistie.Leaf, level = 1)
        }
        val step = compose.onNodeWithText("Child", useUnmergedTree = true).getBoundsInRoot().left - compose.onNodeWithText("Parent", useUnmergedTree = true).getBoundsInRoot().left
        assertTrue("indent $step", (step.value - indentStep.value) in -0.5f..0.5f)
    }
}

/** A phone: COMPACT width, so auto density is Comfortable and the 44dp floor applies. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
// SDK 35, not compileSdk 37: Espresso, pulled in by the compose test rule, calls InputManager.getInstance().
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class KitSemanticsCompactTest : KitSemanticsSpec(44.dp, 44.dp, 36.dp, 40.dp, 40.dp) {
    override val indentStep = 14.dp
}

/** A tablet in landscape: EXPANDED width, so auto density is Dense and the touch floor is 40dp. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w1152dp-h720dp-xxhdpi")
class KitSemanticsWideTest : KitSemanticsSpec(32.dp, 40.dp, 28.dp, 30.dp, 34.dp) {
    override val indentStep = 12.dp
}
