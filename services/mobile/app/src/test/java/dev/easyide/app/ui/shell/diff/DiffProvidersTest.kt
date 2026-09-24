package dev.easyide.app.ui.shell.diff

import dev.easyide.app.ui.shell.DocumentUri
import dev.easyide.app.ui.shell.uri
import dev.easyide.sandbox.git.FileDiff
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class DiffProvidersTest {
    private class Fake(override val scheme: String, private val tag: String) : DiffProvider {
        override fun subject(uri: DocumentUri) = DiffSubject("$tag.txt", SideLabel.Named("a"), SideLabel.Named("b"))
        override suspend fun load(uri: DocumentUri): DiffOutcome = DiffOutcome.Ready(FileDiff.Text("$tag.txt", emptyList()))
        override fun actions(uri: DocumentUri): HunkActions? = null
    }

    private val git = Fake("git-diff", "git")
    private val ext = Fake("ext", "ext")

    @Test fun `a provider serves the documents of its scheme`() {
        val providers = DiffProviders.EMPTY.register(git).register(ext)
        assertSame(git, providers.forUri(uri("git-diff:///a.kt?base=HEAD&head=index")))
        assertSame(ext, providers.forUri(uri("ext://acme.docker/compose/1")))
        assertNull(providers.forUri(uri("file:///workspace/a.kt")))
    }

    @Test fun `a second provider for a scheme is refused and the first stays`() {
        val providers = DiffProviders.EMPTY.register(git).register(Fake("git-diff", "pack"))
        val served = providers.forUri(uri("git-diff:///a.kt?base=HEAD&head=index"))
        assertSame(git, served)
    }

    @Test fun `a provider can be removed by its scheme`() {
        val providers = DiffProviders.EMPTY.register(git).register(ext).unregister("ext")
        assertNull(providers.forUri(uri("ext://acme.docker/compose/1")))
        assertSame(git, providers.forUri(uri("git-diff:///a.kt?base=HEAD&head=index")))
    }

    @Test fun `registering does not change the registry it was called on`() {
        val empty = DiffProviders.EMPTY
        empty.register(git)
        assertNull(empty.forUri(uri("git-diff:///a.kt?base=HEAD&head=index")))
    }

    @Test fun `a provider loads through the same outcome whatever it reads`() = runTest {
        val outcome = ext.load(uri("ext://acme.docker/compose/1"))
        assertEquals("ext.txt", ((outcome as DiffOutcome.Ready).diff as FileDiff.Text).path)
    }
}
