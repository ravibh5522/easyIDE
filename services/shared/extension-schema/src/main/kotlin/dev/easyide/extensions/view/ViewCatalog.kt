package dev.easyide.extensions.view

/** The event a component raises, which says what its [ViewAction] means. */
enum class EventKind { CLICK, CHANGE, SUBMIT }

enum class PropKind { TEXT, PATH, FLAG, NUMBER, CHOICE, ICON, IMAGE, WHEN, PATHS, OPTIONS, COLUMNS, PATTERN }

class PropSpec(val name: String, val kind: PropKind, val required: Boolean = false, val values: List<String> = emptyList())

/** How a component holds other components: [children], a row template ([item]) and an empty state ([empty]). */
enum class Slots { NONE, MANY, PAIR, TABS, ITEM, ITEM_EMPTY, EMPTY }

enum class Availability { AVAILABLE, RESERVED }

/** What a component may carry. [event] set means the component takes an `action`. */
class ComponentSpec(
    val props: List<PropSpec>,
    val slots: Slots = Slots.NONE,
    val event: EventKind? = null,
    val availability: Availability = Availability.AVAILABLE,
)

/** The `viewSchema: 1` component types (extension-ui.md section 4.1). Version 1 is fixed: a new type is a version bump. */
enum class ViewType(val wire: String) {
    COLUMN("column"), ROW("row"), SECTION("section"), GROUP("group"), TABS("tabs"), TAB("tab"), SPLIT("split"),
    TEXT("text"), MARKDOWN("markdown"), KEY_VALUE("keyValue"), TAG("tag"), STATUS_DOT("statusDot"), PROGRESS("progress"),
    ICON("icon"), IMAGE("image"), CODE("code"), BANNER("banner"), EMPTY_STATE("emptyState"),
    LIST("list"), TREE("tree"), TABLE("table"), SPARKLINE("sparkline"), LOG_STREAM("logStream"), CHAT("chat"),
    TERMINAL("terminal"), DIFF("diff"),
    BUTTON("button"), ICON_BUTTON("iconButton"), TOGGLE("toggle"), FIELD("field"), SELECT("select"), SLIDER("slider"),
    SEARCH("search"), FORM("form"), COMPOSER("composer");

    val spec: ComponentSpec get() = ViewCatalog.specs.getValue(this)

    companion object {
        private val BY_WIRE = entries.associateBy { it.wire }
        fun parse(wire: String): ViewType? = BY_WIRE[wire]
    }
}

object ViewCatalog {
    val TONES = listOf("neutral", "accent", "success", "warning", "danger", "info")
    val ROLES = listOf("title", "body", "caption", "mono")
    val GAPS = listOf("none", "xs", "s", "m", "l")
    val ALIGNS = listOf("start", "center", "end", "stretch", "spread")
    val AXES = listOf("row", "column")
    val BUTTON_STYLES = listOf("primary", "secondary", "ghost", "danger")

    /** Props every component accepts: a stable key, a visibility condition and a share of the parent's free space. */
    val COMMON: List<PropSpec> = listOf(
        PropSpec("id", PropKind.PATH),
        PropSpec("when", PropKind.WHEN),
        PropSpec("weight", PropKind.NUMBER),
    )

    /** The props of an event binding: `action` or `open` names the target, the rest shape the call; decoded apart from [COMMON]. */
    val EVENT_PROPS = listOf("action", "open", "group", "args", "confirm", "as", "mode", "parse", "before", "after")

    private fun text(name: String, required: Boolean = false) = PropSpec(name, PropKind.TEXT, required)
    private fun path(name: String, required: Boolean = false) = PropSpec(name, PropKind.PATH, required)
    private fun flag(name: String) = PropSpec(name, PropKind.FLAG)
    private fun num(name: String) = PropSpec(name, PropKind.NUMBER)
    private fun choice(name: String, values: List<String>, required: Boolean = false) = PropSpec(name, PropKind.CHOICE, required, values)
    private fun icon(name: String, required: Boolean = false) = PropSpec(name, PropKind.ICON, required)
    private fun tone() = choice("tone", TONES)

    val specs: Map<ViewType, ComponentSpec> = mapOf(
        ViewType.COLUMN to ComponentSpec(listOf(choice("gap", GAPS), choice("padding", GAPS), choice("align", ALIGNS)), Slots.MANY),
        ViewType.ROW to ComponentSpec(listOf(choice("gap", GAPS), choice("padding", GAPS), choice("align", ALIGNS)), Slots.MANY, EventKind.CLICK),
        ViewType.SECTION to ComponentSpec(listOf(text("title", true)), Slots.MANY),
        ViewType.GROUP to ComponentSpec(emptyList(), Slots.MANY),
        ViewType.TABS to ComponentSpec(listOf(path("bind")), Slots.TABS),
        ViewType.TAB to ComponentSpec(listOf(text("title", true)), Slots.MANY),
        ViewType.SPLIT to ComponentSpec(listOf(choice("axis", AXES), num("ratio")), Slots.PAIR),
        ViewType.TEXT to ComponentSpec(listOf(text("value", true), choice("role", ROLES), tone(), num("maxLines"))),
        ViewType.MARKDOWN to ComponentSpec(listOf(text("value", true))),
        ViewType.KEY_VALUE to ComponentSpec(listOf(text("label", true), text("value", true), flag("mono"))),
        ViewType.TAG to ComponentSpec(listOf(text("value", true), tone())),
        ViewType.STATUS_DOT to ComponentSpec(listOf(tone(), text("label", true))),
        ViewType.PROGRESS to ComponentSpec(listOf(text("value"), text("label"), tone())),
        ViewType.ICON to ComponentSpec(listOf(icon("name", true), tone(), text("label"))),
        ViewType.IMAGE to ComponentSpec(listOf(PropSpec("src", PropKind.IMAGE, true), text("label", true), num("height"))),
        ViewType.CODE to ComponentSpec(listOf(text("value", true), text("language"))),
        ViewType.BANNER to ComponentSpec(listOf(text("value", true), tone())),
        ViewType.EMPTY_STATE to ComponentSpec(listOf(text("message", true), text("actionLabel")), Slots.NONE, EventKind.CLICK),
        ViewType.LIST to ComponentSpec(
            listOf(path("bind", true), path("key"), path("query"), PropSpec("filterFields", PropKind.PATHS)), Slots.ITEM_EMPTY,
        ),
        ViewType.TREE to ComponentSpec(listOf(path("bind", true), path("key"), path("childrenField")), Slots.ITEM),
        ViewType.TABLE to ComponentSpec(listOf(path("bind", true), path("key"), PropSpec("columns", PropKind.COLUMNS, true)), Slots.EMPTY),
        ViewType.SPARKLINE to ComponentSpec(listOf(path("bind", true), tone(), text("label", true))),
        ViewType.LOG_STREAM to ComponentSpec(listOf(path("bind", true), flag("follow"))),
        ViewType.CHAT to ComponentSpec(listOf(path("bind", true), flag("follow"))),
        ViewType.TERMINAL to ComponentSpec(emptyList(), availability = Availability.RESERVED),
        ViewType.DIFF to ComponentSpec(listOf(text("value", true))),
        ViewType.BUTTON to ComponentSpec(
            listOf(text("label", true), choice("style", BUTTON_STYLES), icon("icon"), PropSpec("enabledWhen", PropKind.WHEN)), event = EventKind.CLICK,
        ),
        ViewType.ICON_BUTTON to ComponentSpec(
            listOf(icon("icon", true), text("label", true), tone(), PropSpec("enabledWhen", PropKind.WHEN)), event = EventKind.CLICK,
        ),
        ViewType.TOGGLE to ComponentSpec(listOf(text("label", true), path("bind", true)), event = EventKind.CHANGE),
        ViewType.FIELD to ComponentSpec(
            listOf(text("label"), text("hint"), path("bind", true), flag("mono"), flag("multiline"), PropSpec("validate", PropKind.PATTERN)),
            event = EventKind.SUBMIT,
        ),
        ViewType.SELECT to ComponentSpec(
            listOf(text("label"), path("bind", true), PropSpec("options", PropKind.OPTIONS), path("optionsFrom")), event = EventKind.CHANGE,
        ),
        ViewType.SLIDER to ComponentSpec(
            listOf(text("label", true), path("bind", true), num("min"), num("max"), num("step")), event = EventKind.CHANGE,
        ),
        ViewType.SEARCH to ComponentSpec(listOf(text("hint"), path("bind", true)), event = EventKind.SUBMIT),
        ViewType.FORM to ComponentSpec(listOf(text("submitLabel")), Slots.MANY, EventKind.SUBMIT),
        ViewType.COMPOSER to ComponentSpec(listOf(text("hint"), path("bind", true), text("sendLabel")), event = EventKind.SUBMIT),
    )

    init {
        check(specs.keys == ViewType.entries.toSet()) { "every ViewType needs a ComponentSpec" }
    }
}
