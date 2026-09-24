package dev.easyide.app.ui.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class NavRegistryTest {
    private val docker = Origin.Extension("acme.docker")
    private val agent = Origin.Extension("acme.agent")
    private val open = NavEnv(holds = { true }, resolves = { true })

    private fun item(
        id: String,
        order: Int = 100,
        scope: ScopeFilter = ScopeFilter.WORKSPACE,
        title: String = id.take(ShellLimits.NAV_TITLE_MAX),
        condition: String? = null,
        target: NavTarget = NavTarget.Container("c.$id"),
    ) = NavItem(id, title, IconRef("i"), target, order, scope, condition)

    private fun NavRegistry.ids(scope: ShellScope, prefs: NavPrefs = NavPrefs(), env: NavEnv = open) =
        visible(scope, prefs, env).map { it.id }

    @Test
    fun `core items appear per scope in default order`() {
        val core = CoreShell.navigation()
        assertEquals(listOf("home", "extensions", "settings"), core.ids(ShellScope.APP))
        assertEquals(listOf("files", "search", "git", "terminal", "problems", "outline", "extensions", "settings", "commands", "projects", "close-project"), core.ids(ShellScope.WORKSPACE))
    }

    @Test
    fun `extension items never outrank core whatever order they ask for`() {
        val r = CoreShell.navigation()
            .register(item("acme.docker.nav", order = 0), docker).registry
            .register(item("acme.agent.nav", order = -50), agent).registry
        assertEquals(
            listOf("files", "search", "git", "terminal", "problems", "outline", "extensions", "settings", "commands", "projects", "close-project", "acme.docker.nav", "acme.agent.nav"),
            r.ids(ShellScope.WORKSPACE),
        )
        assertEquals(ShellLimits.EXTENSION_ORDER_FLOOR, r.byId("acme.docker.nav")?.order)
    }

    @Test
    fun `extension items order among themselves by order then registration`() {
        val r = NavRegistry.EMPTY
            .register(item("acme.docker.b", order = 300), docker).registry
            .register(item("acme.docker.a", order = 200), docker).registry
            .register(item("acme.agent.c", order = 200), agent).registry
        assertEquals(listOf("acme.docker.a", "acme.agent.c", "acme.docker.b"), r.ids(ShellScope.WORKSPACE))
    }

    @Test
    fun `a core item wins a tie with an extension item at the same order`() {
        val r = NavRegistry.EMPTY
            .register(item("acme.docker.nav", order = 100), docker).registry
            .register(item("late", order = 100), Origin.Core).registry
        assertEquals(listOf("late", "acme.docker.nav"), r.ids(ShellScope.WORKSPACE))
    }

    @Test
    fun `registration refusals table`() {
        val base = CoreShell.navigation().register(item("acme.docker.one"), docker).registry
        val cases = listOf(
            Triple("core id taken", item("files") to docker, RejectReason.DUPLICATE_ID),
            Triple("own id taken", item("acme.docker.one") to docker, RejectReason.DUPLICATE_ID),
            Triple("another pack's id", item("acme.docker.one") to agent, RejectReason.DUPLICATE_ID),
            Triple("not namespaced", item("docker") to docker, RejectReason.NOT_NAMESPACED),
            Triple("someone else's namespace", item("acme.agent.x") to docker, RejectReason.NOT_NAMESPACED),
            Triple("bare extension id", item("acme.docker.") to docker, RejectReason.NOT_NAMESPACED),
            Triple("title too long", item("acme.docker.t", title = "A title over fourteen") to docker, RejectReason.TITLE_TOO_LONG),
        )
        cases.forEach { (name, entry, reason) ->
            val out = base.register(entry.first, entry.second)
            assertEquals(name, listOf(Rejection(entry.first.id, reason)), out.rejections)
            assertSame(name, base, out.registry)
        }
        assertTrue(base.register(item("acme.docker.t", title = "Fourteen chars"), docker).rejections.isEmpty())
    }

    @Test
    fun `an extension gets at most three items and core is not capped`() {
        var r = NavRegistry.EMPTY
        repeat(ShellLimits.NAV_ITEMS_PER_EXTENSION) { i -> r = r.register(item("acme.docker.n$i"), docker).registry }
        val fourth = r.register(item("acme.docker.n3"), docker)
        assertEquals(listOf(Rejection("acme.docker.n3", RejectReason.TOO_MANY)), fourth.rejections)
        assertTrue(fourth.registry.register(item("acme.agent.n0"), agent).rejections.isEmpty())
        repeat(5) { i -> r = r.register(item("core$i"), Origin.Core).registry }
        assertEquals(8, r.ids(ShellScope.WORKSPACE).size)
    }

    @Test
    fun `scope filter conditions and unresolved targets drop items`() {
        val r = NavRegistry.EMPTY
            .register(item("app", scope = ScopeFilter.APP), Origin.Core).registry
            .register(item("ws", scope = ScopeFilter.WORKSPACE), Origin.Core).registry
            .register(item("both", scope = ScopeFilter.BOTH), Origin.Core).registry
            .register(item("cond", condition = "workspaceContains:Dockerfile"), Origin.Core).registry
            .register(item("cmd", target = NavTarget.Command("x.run")), Origin.Core).registry
        assertEquals(listOf("app", "both"), r.ids(ShellScope.APP))
        assertEquals(listOf("ws", "both", "cond", "cmd"), r.ids(ShellScope.WORKSPACE))
        val strict = NavEnv(holds = { it != "workspaceContains:Dockerfile" }, resolves = { it !is NavTarget.Command })
        assertEquals(listOf("ws", "both"), r.ids(ShellScope.WORKSPACE, env = strict))
    }

    @Test
    fun `user hidden items disappear but hiding cannot empty the surface`() {
        val r = CoreShell.navigation().register(item("acme.docker.nav"), docker).registry
        assertEquals(
            listOf("files", "search", "terminal", "problems", "outline", "extensions", "settings", "commands", "projects", "close-project"),
            r.ids(ShellScope.WORKSPACE, NavPrefs(hidden = setOf("git", "acme.docker.nav"))),
        )
        assertEquals(listOf("home", "settings"), r.ids(ShellScope.APP, NavPrefs(hidden = setOf("extensions"))))
        val everything = NavPrefs(hidden = setOf("home", "extensions", "settings"))
        assertEquals(listOf("home", "extensions", "settings"), r.ids(ShellScope.APP, everything))
        assertEquals(emptyList<String>(), NavRegistry.EMPTY.ids(ShellScope.APP, everything))
    }

    @Test
    fun `user order comes first and the rest follow by default order`() {
        val r = CoreShell.navigation().register(item("acme.docker.nav"), docker).registry
        val prefs = NavPrefs(order = listOf("acme.docker.nav", "outline", "nope", "outline", "files"))
        assertEquals(
            listOf("acme.docker.nav", "outline", "files", "search", "git", "terminal", "problems", "extensions", "settings", "commands", "projects", "close-project"),
            r.ids(ShellScope.WORKSPACE, prefs),
        )
    }

    @Test
    fun `user order does not resurrect hidden items`() {
        val prefs = NavPrefs(order = listOf("outline", "files"), hidden = setOf("outline"))
        assertEquals("files", CoreShell.navigation().ids(ShellScope.WORKSPACE, prefs).first())
    }

    @Test
    fun `unregister removes only that extension's items`() {
        val r = CoreShell.navigation()
            .register(item("acme.docker.nav"), docker).registry
            .register(item("acme.agent.nav"), agent).registry
            .unregister("acme.docker")
        assertNull(r.byId("acme.docker.nav"))
        assertEquals("acme.agent.nav", r.byId("acme.agent.nav")?.id)
        assertEquals("files", r.byId("files")?.id)
    }

    private fun nav(n: Int) = (1..n).map { item("i$it", order = it) }
    private fun NavSplit.ids() = visible.map { it.id } to more.map { it.id }

    @Test
    fun `compact split keeps five cells and sends the rest to more in order`() {
        val cap = ShellLimits.COMPACT_NAV_CAPACITY
        val cases = listOf(
            Triple("none", emptyList<NavItem>(), emptySet<String>()) to (emptyList<String>() to emptyList<String>()),
            Triple("fits", nav(5), emptySet<String>()) to (listOf("i1", "i2", "i3", "i4", "i5") to emptyList<String>()),
            Triple("overflow", nav(7), emptySet<String>()) to (listOf("i1", "i2", "i3", "i4", "i5") to listOf("i6", "i7")),
            Triple("pinned overflow item stays", nav(7), setOf("i7")) to (listOf("i1", "i2", "i3", "i4", "i7") to listOf("i5", "i6")),
            Triple("two pinned", nav(8), setOf("i8", "i6")) to (listOf("i1", "i2", "i3", "i6", "i8") to listOf("i4", "i5", "i7")),
            Triple("pinned already visible", nav(7), setOf("i2")) to (listOf("i1", "i2", "i3", "i4", "i5") to listOf("i6", "i7")),
            Triple("too many pinned", nav(8), (2..8).map { "i$it" }.toSet()) to (listOf("i2", "i3", "i4", "i5", "i6") to listOf("i1", "i7", "i8")),
            Triple("unknown pinned ignored", nav(6), setOf("zzz")) to (listOf("i1", "i2", "i3", "i4", "i5") to listOf("i6")),
        )
        cases.forEach { (input, expected) ->
            val (name, items, pinned) = input
            assertEquals(name, expected, NavLayout.split(items, cap, pinned).ids())
        }
    }
}
