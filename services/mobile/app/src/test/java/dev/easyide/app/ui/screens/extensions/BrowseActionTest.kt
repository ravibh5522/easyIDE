package dev.easyide.app.ui.screens.extensions

import org.junit.Assert.assertEquals
import org.junit.Test

class BrowseActionTest {

    @Test fun `no compatible entry or no registry means nothing can be installed`() {
        assertEquals(BrowseAction.Unavailable, browseAction(entryVersion = null, registryId = "main", installedVersion = null))
        assertEquals(BrowseAction.Unavailable, browseAction(entryVersion = "1.0.0", registryId = null, installedVersion = null))
    }

    @Test fun `a row offers install, update or nothing by what is installed`() {
        assertEquals(BrowseAction.Install, browseAction("1.0.0", "main", null))
        assertEquals(BrowseAction.Update, browseAction("1.1.0", "main", "1.0.0"))
        assertEquals(BrowseAction.Installed, browseAction("1.1.0", "main", "1.1.0"))
    }
}
