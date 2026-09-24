package dev.easyide.app.ui.commands

import dev.easyide.extensions.schema.BuiltInCommands
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `services/shared/extension-schema/builtin-commands.json` is the API list `easyide-ext validate`
 * resolves built-in command references against; it must name exactly what the app implements.
 */
class BuiltInCommandsContractTest {
    @Test fun `shared built-in command list matches the app`() {
        assertEquals(CommandIds.ALL.sorted(), BuiltInCommands.IDS.sorted())
    }
}
