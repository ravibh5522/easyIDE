package dev.easyide.lsp.protocol

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClientCapabilitiesTest {
    private val allLayers = ClientUi(UiLayer.entries.toSet(), listOf("class", "function"), listOf("declaration"))
    private val noLayers = ClientUi(emptySet(), emptyList(), emptyList())

    private fun JsonObject.at(vararg path: String): JsonObject? =
        path.fold(this as JsonObject?) { o, k -> o?.get(k) as? JsonObject }

    @Test
    fun generalOffersUtf16OnlyAndStaleRequestSupport() {
        val caps = ClientCapabilitiesBuilder.build(Milestone.M2, allLayers)
        assertEquals(listOf("utf-16"), caps.at("general")!!["positionEncodings"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertTrue(caps.at("general", "staleRequestSupport")!!["cancel"]!!.jsonPrimitive.boolean)
        assertEquals("abort", caps.at("workspace", "workspaceEdit")!!["failureHandling"]!!.jsonPrimitive.content)
        assertNull(caps.at("window")!!["showDocument"])
    }

    @Test
    fun m2AdvertisesM2FeaturesOnly() {
        val caps = ClientCapabilitiesBuilder.build(Milestone.M2, allLayers)
        val td = caps.at("textDocument")!!
        for (k in listOf("completion", "hover", "signatureHelp", "definition", "declaration", "typeDefinition", "implementation",
            "references", "documentHighlight", "documentSymbol", "rename", "codeAction", "formatting", "rangeFormatting", "onTypeFormatting",
            "publishDiagnostics", "diagnostic", "synchronization")) {
            assertTrue("missing $k", td.containsKey(k))
        }
        for (k in listOf("inlayHint", "semanticTokens", "foldingRange", "selectionRange", "codeLens", "documentLink")) {
            assertFalse("unexpected $k at M2", td.containsKey(k))
        }
        assertNull(caps.at("workspace")!!["symbol"])
        assertTrue(td.at("rename")!!["prepareSupport"]!!.jsonPrimitive.boolean)
        assertTrue(td.at("completion", "completionItem")!!["insertReplaceSupport"]!!.jsonPrimitive.boolean)
        assertEquals(25, td.at("completion", "completionItemKind")!!["valueSet"]!!.jsonArray.size)
    }

    @Test
    fun m4AddsItsFeaturesWithRefreshSupport() {
        val caps = ClientCapabilitiesBuilder.build(Milestone.M4, allLayers)
        val td = caps.at("textDocument")!!
        for (k in listOf("inlayHint", "semanticTokens", "foldingRange", "selectionRange", "codeLens", "documentLink")) assertTrue(k, td.containsKey(k))
        assertEquals(listOf("class", "function"), td.at("semanticTokens")!!["tokenTypes"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertFalse(td.at("semanticTokens")!!["multilineTokenSupport"]!!.jsonPrimitive.boolean)
        assertTrue(td.at("foldingRange")!!["lineFoldingOnly"]!!.jsonPrimitive.boolean)
        assertEquals(5000, td.at("foldingRange")!!["rangeLimit"]!!.jsonPrimitive.int)
        assertTrue(caps.at("workspace", "semanticTokens")!!["refreshSupport"]!!.jsonPrimitive.boolean)
        assertTrue(caps.at("workspace")!!.containsKey("symbol"))
    }

    @Test
    fun missingDecorationLayerRemovesItsCapability() {
        val features = ClientCapabilitiesBuilder.advertisedFeatures(Milestone.M4, noLayers)
        for (f in listOf(LspFeature.COMPLETION, LspFeature.HOVER, LspFeature.SIGNATURE_HELP, LspFeature.DOCUMENT_HIGHLIGHT,
            LspFeature.INLAY_HINTS, LspFeature.CODE_LENS, LspFeature.SEMANTIC_TOKENS, LspFeature.DOCUMENT_LINK)) {
            assertFalse("$f needs a layer", f in features)
        }
        // Panels and edits need no decoration layer.
        for (f in listOf(LspFeature.DIAGNOSTICS, LspFeature.DEFINITION, LspFeature.REFERENCES, LspFeature.DOCUMENT_SYMBOL,
            LspFeature.RENAME, LspFeature.FORMATTING, LspFeature.CODE_ACTION, LspFeature.FOLDING_RANGE)) {
            assertTrue("$f needs no layer", f in features)
        }
        val td = ClientCapabilitiesBuilder.build(Milestone.M4, noLayers).at("textDocument")!!
        assertFalse(td.containsKey("completion"))
        assertFalse(td.containsKey("semanticTokens"))
        assertTrue(td.containsKey("publishDiagnostics"))
    }

    @Test
    fun codeLensNeedsBetweenLineBlocksOrGutterIcons() {
        val gutterOnly = ClientUi(setOf(UiLayer.GUTTER_ICON), emptyList(), emptyList())
        assertTrue(LspFeature.CODE_LENS in ClientCapabilitiesBuilder.advertisedFeatures(Milestone.M4, gutterOnly))
        val blocksOnly = ClientUi(setOf(UiLayer.BETWEEN_LINE_BLOCK), emptyList(), emptyList())
        assertTrue(LspFeature.CODE_LENS in ClientCapabilitiesBuilder.advertisedFeatures(Milestone.M4, blocksOnly))
        val caps = ClientCapabilitiesBuilder.build(Milestone.M4, gutterOnly)
        assertTrue(caps.at("workspace", "codeLens")!!["refreshSupport"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun semanticTokensNeedTheOverlayLayerAndATokenList() {
        val overlay = ClientUi(setOf(UiLayer.TOKEN_OVERLAY), listOf("function"), listOf("readonly"))
        assertTrue(LspFeature.SEMANTIC_TOKENS in ClientCapabilitiesBuilder.advertisedFeatures(Milestone.M4, overlay))
        assertFalse(LspFeature.SEMANTIC_TOKENS in ClientCapabilitiesBuilder.advertisedFeatures(Milestone.M4, overlay.copy(semanticTokenTypes = emptyList())))
        val st = ClientCapabilitiesBuilder.build(Milestone.M4, overlay).at("textDocument", "semanticTokens")!!
        assertEquals(listOf("readonly"), st["tokenModifiers"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertTrue(st.at("requests", "full")!!["delta"]!!.jsonPrimitive.boolean)
        assertTrue(st["augmentsSyntaxTokens"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun withheldFeatureIsNeverAdvertised() {
        val ui = allLayers.copy(withheld = setOf(LspFeature.FOLDING_RANGE, LspFeature.SELECTION_RANGE, LspFeature.DOCUMENT_LINK))
        val features = ClientCapabilitiesBuilder.advertisedFeatures(Milestone.M4, ui)
        assertFalse(LspFeature.FOLDING_RANGE in features)
        assertFalse(LspFeature.DOCUMENT_LINK in features)
        assertTrue(LspFeature.INLAY_HINTS in features)
        val td = ClientCapabilitiesBuilder.build(Milestone.M4, ui).at("textDocument")!!
        assertFalse(td.containsKey("foldingRange"))
        assertFalse(td.containsKey("selectionRange"))
        assertFalse(td.containsKey("documentLink"))
        assertTrue(td.containsKey("inlayHint"))
    }
}
