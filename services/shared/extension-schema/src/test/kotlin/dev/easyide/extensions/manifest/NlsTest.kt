package dev.easyide.extensions.manifest

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class NlsTest {
    @Test fun `bundles are looked up most specific locale first`() {
        assertEquals(
            listOf("l10n/package.nls.pt-br.json", "l10n/package.nls.pt.json", "l10n/package.nls.json", "package.nls.json"),
            Nls.candidates(Locale.forLanguageTag("pt-BR")),
        )
    }
}
