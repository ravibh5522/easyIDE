package dev.easyide.lsp.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Parsers against payloads shaped like real servers' (pyright, gopls, tsserver, ruff). */
class ParsersTest {
    private fun j(s: String): JsonElement = Json.parseToJsonElement(s)

    @Test
    fun pyrightCompletionListWithItemDefaults() {
        val list = CompletionList.fromJson(j("""
            {"isIncomplete":true,
             "itemDefaults":{"editRange":{"start":{"line":3,"character":4},"end":{"line":3,"character":6}},"commitCharacters":["."],"data":{"uri":"file:///workspace/a.py"}},
             "items":[
               {"label":"append","kind":2,"sortText":"09.9999.append","data":{"workspacePath":"/workspace"}},
               {"label":"count","kind":2,"labelDetails":{"detail":"(value)","description":"int"},"tags":[1]},
               {"kind":2},
               {"label":"copy","textEdit":{"range":{"start":{"line":3,"character":0},"end":{"line":3,"character":2}},"newText":"copy()"},"insertTextFormat":2}
             ]}
        """))
        assertTrue(list.isIncomplete)
        assertEquals(3, list.items.size)
        val append = list.items[0]
        assertEquals(CompletionItemKind.METHOD, append.kind)
        assertEquals(listOf("."), append.commitCharacters)
        assertEquals(CompletionEdit.Replace(TextEdit(Range(Position(3, 4), Position(3, 6)), "append")), append.edit)
        // Item data wins over the default; resolve sends the merged raw object back.
        assertEquals(j("""{"workspacePath":"/workspace"}"""), append.raw["data"])
        val count = list.items[1]
        assertTrue(count.deprecated)
        assertEquals("(value)", count.labelDetail)
        assertEquals(j("""{"uri":"file:///workspace/a.py"}"""), count.raw["data"])
        val copy = list.items[2]
        assertEquals(InsertTextFormat.SNIPPET, copy.insertTextFormat)
        assertEquals("copy()", copy.edit!!.newText)
    }

    @Test
    fun tsserverInsertReplaceEditAndBareArrayResult() {
        val list = CompletionList.fromJson(j("""
            [{"label":"useState","kind":3,"filterText":"useState","insertText":"useState",
              "textEdit":{"newText":"useState","insert":{"start":{"line":0,"character":9},"end":{"line":0,"character":12}},
                          "replace":{"start":{"line":0,"character":9},"end":{"line":0,"character":17}}},
              "additionalTextEdits":[{"range":{"start":{"line":0,"character":0},"end":{"line":0,"character":0}},"newText":"import { useState } from 'react';\n"}],
              "command":{"title":"","command":"_typescript.onCompletionAccepted","arguments":[{"file":"/workspace/a.ts"}]}}]
        """))
        val item = list.items.single()
        assertFalse(list.isIncomplete)
        val edit = item.edit as CompletionEdit.InsertReplace
        assertEquals(12, edit.insert.end.character)
        assertEquals(17, edit.replace.end.character)
        assertEquals(1, item.additionalTextEdits.size)
        assertEquals("_typescript.onCompletionAccepted", item.command!!.command)
        assertEquals(CompletionList.EMPTY, CompletionList.fromJson(j("null")))
    }

    @Test
    fun hoverShapes() {
        val pyright = Hover.fromJson(j("""{"contents":{"kind":"markdown","value":"```python\ndef f() -> int\n```"},"range":{"start":{"line":1,"character":4},"end":{"line":1,"character":5}}}"""))!!
        assertEquals(MarkupKind.MARKDOWN, pyright.contents.kind)
        assertEquals(Range(Position(1, 4), Position(1, 5)), pyright.range)
        val legacy = Hover.fromJson(j("""{"contents":[{"language":"go","value":"func Println(a ...any)"},"Println formats using the default formats."]}"""))!!
        assertEquals(MarkupKind.MARKDOWN, legacy.contents.kind)
        assertTrue(legacy.contents.value.startsWith("```go\nfunc Println"))
        assertTrue(legacy.contents.value.contains("default formats\\."))
        assertNull(Hover.fromJson(j("""{"contents":""}""")))
        assertNull(Hover.fromJson(j("null")))
    }

    @Test
    fun signatureHelpStringAndOffsetLabels() {
        val pyright = SignatureHelp.fromJson(j("""
            {"signatures":[{"label":"(a: int, b: int) -> int","parameters":[{"label":"a: int"},{"label":"b: int"}],"activeParameter":1}],
             "activeSignature":0,"activeParameter":0}
        """))!!
        val sig = pyright.active!!
        assertEquals(1, pyright.activeParameter) // the signature's own wins
        assertEquals("b: int", sig.label.substring(sig.parameters[1].labelStart, sig.parameters[1].labelEnd))
        val ts = SignatureHelp.fromJson(j("""{"signatures":[{"label":"f(x, x)","parameters":[{"label":[2,3]},{"label":[5,6]}]}],"activeSignature":7}"""))!!
        assertEquals(0, ts.activeSignature)
        assertEquals(5, ts.active!!.parameters[1].labelStart)
        // Same string twice resolves to two different spans.
        val dup = SignatureHelp.fromJson(j("""{"signatures":[{"label":"g(x, x)","parameters":[{"label":"x"},{"label":"x"}]}]}"""))!!
        assertEquals(listOf(2, 5), dup.active!!.parameters.map { it.labelStart })
        assertNull(SignatureHelp.fromJson(j("""{"signatures":[]}""")))
    }

    @Test
    fun navigationTargetsFromAllThreeShapes() {
        val loc = NavTarget.listFromJson(j("""{"uri":"file:///workspace/a.py","range":{"start":{"line":1,"character":0},"end":{"line":1,"character":3}}}"""))
        assertEquals(1, loc.size)
        val gopls = NavTarget.listFromJson(j("""
            [{"originSelectionRange":{"start":{"line":5,"character":1},"end":{"line":5,"character":8}},
              "targetUri":"file:///usr/lib/go/src/fmt/print.go",
              "targetRange":{"start":{"line":270,"character":0},"end":{"line":275,"character":1}},
              "targetSelectionRange":{"start":{"line":272,"character":5},"end":{"line":272,"character":12}}}]
        """)).single()
        assertEquals(272, gopls.range.start.line)
        assertEquals(270, gopls.fullRange.start.line)
        assertEquals(5, gopls.originSelectionRange!!.start.line)
        assertTrue(NavTarget.listFromJson(j("null")).isEmpty())
        // A reversed range from a sloppy server is normalised.
        val rev = NavTarget.listFromJson(j("""[{"uri":"u","range":{"start":{"line":2,"character":0},"end":{"line":1,"character":0}}}]""")).single()
        assertTrue(rev.range.start <= rev.range.end)
    }

    @Test
    fun documentSymbolsHierarchicalAndFlat() {
        val tree = SymbolNode.listFromJson(j("""
            [{"name":"A","kind":5,"range":{"start":{"line":0,"character":0},"end":{"line":9,"character":0}},
              "selectionRange":{"start":{"line":0,"character":6},"end":{"line":0,"character":7}},
              "children":[{"name":"m","detail":"(self)","kind":6,"range":{"start":{"line":1,"character":4},"end":{"line":2,"character":0}},
                           "selectionRange":{"start":{"line":1,"character":8},"end":{"line":1,"character":9}}}]}]
        """))
        assertEquals("m", tree.single().children.single().name)
        assertEquals(SymbolKind.METHOD, tree.single().children.single().kind)
        val flat = SymbolNode.listFromJson(j("""
            [{"name":"m","kind":6,"location":{"uri":"u","range":{"start":{"line":1,"character":4},"end":{"line":2,"character":0}}},"containerName":"A"},
             {"name":"A","kind":5,"location":{"uri":"u","range":{"start":{"line":0,"character":0},"end":{"line":9,"character":0}}}},
             {"name":"B","kind":99,"location":{"uri":"u","range":{"start":{"line":10,"character":0},"end":{"line":12,"character":0}}}}]
        """))
        assertEquals(listOf("A", "B"), flat.map { it.name })
        assertEquals("m", flat[0].children.single().name)
        assertEquals(SymbolKind.VARIABLE, flat[1].kind)
    }

    @Test
    fun workspaceEditChangesAndDocumentChanges() {
        val changes = WorkspaceEdit.fromJson(j("""{"changes":{"file:///workspace/a.py":[{"range":{"start":{"line":0,"character":0},"end":{"line":0,"character":1}},"newText":"b"}]}}"""))!!
        assertEquals(EditOperation.Text("file:///workspace/a.py", null, listOf(TextEdit(Range(Position(0, 0), Position(0, 1)), "b"))), changes.operations.single())
        val doc = WorkspaceEdit.fromJson(j("""
            {"changes":{"ignored":[]},"documentChanges":[
              {"textDocument":{"uri":"file:///workspace/a.ts","version":7},"edits":[{"range":{"start":{"line":0,"character":0},"end":{"line":0,"character":0}},"newText":"x","annotationId":"a"}]},
              {"kind":"create","uri":"file:///workspace/new.ts","options":{"ignoreIfExists":true}},
              {"kind":"rename","oldUri":"file:///workspace/old.ts","newUri":"file:///workspace/b.ts","options":{"overwrite":true}},
              {"kind":"delete","uri":"file:///workspace/gone.ts","options":{"recursive":true}},
              {"kind":"mystery"}]}
        """))!!
        assertEquals(4, doc.operations.size)
        assertEquals(7, (doc.operations[0] as EditOperation.Text).version)
        assertTrue((doc.operations[1] as EditOperation.Create).ignoreIfExists)
        assertTrue((doc.operations[2] as EditOperation.Rename).overwrite)
        assertTrue((doc.operations[3] as EditOperation.Delete).recursive)
    }

    @Test
    fun prepareRenameShapes() {
        assertEquals(PrepareRename.At(Range(Position(1, 2), Position(1, 5)), null), PrepareRename.fromJson(j("""{"start":{"line":1,"character":2},"end":{"line":1,"character":5}}""")))
        assertEquals("foo", (PrepareRename.fromJson(j("""{"range":{"start":{"line":1,"character":2},"end":{"line":1,"character":5}},"placeholder":"foo"}""")) as PrepareRename.At).placeholder)
        assertEquals(PrepareRename.DefaultBehavior, PrepareRename.fromJson(j("""{"defaultBehavior":true}""")))
        assertNull(PrepareRename.fromJson(j("null")))
    }

    @Test
    fun ruffCodeActionsMixedWithCommands() {
        val actions = CodeAction.listFromJson(j("""
            [{"title":"Ruff: Remove unused import","kind":"quickfix","isPreferred":true,
              "diagnostics":[{"range":{"start":{"line":0,"character":7},"end":{"line":0,"character":9}},"message":"`os` imported but unused","code":"F401","source":"Ruff","data":{"fix":1}}],
              "data":{"uri":"file:///workspace/a.py"}},
             {"title":"Organize Imports","command":"ruff.applyOrganizeImports","arguments":[{"uri":"file:///workspace/a.py"}]},
             {"title":"Extract","kind":"refactor.extract","disabled":{"reason":"Select an expression"}}]
        """))
        assertEquals(3, actions.size)
        assertTrue(actions[0].needsResolve)
        assertTrue(actions[0].isPreferred)
        assertEquals("F401", actions[0].diagnostics.single().code)
        assertEquals("ruff.applyOrganizeImports", actions[1].command!!.command)
        assertNull(actions[1].kind)
        assertEquals("Select an expression", actions[2].disabledReason)
    }

    @Test
    fun diagnosticsKeepRawDataAndNumericCodes() {
        val p = PublishDiagnostics.fromJson(j("""
            {"uri":"file:///workspace/a.py","version":3,"diagnostics":[
              {"range":{"start":{"line":0,"character":0},"end":{"line":0,"character":6}},"severity":1,"code":"reportMissingImports",
               "codeDescription":{"href":"https://github.com/microsoft/pyright/blob/main/docs/configuration.md"},"source":"Pyright",
               "message":"Import \"nump\" could not be resolved","tags":[1,9],"data":{"x":1},
               "relatedInformation":[{"location":{"uri":"file:///workspace/b.py","range":{"start":{"line":1,"character":0},"end":{"line":1,"character":1}}},"message":"here"}]},
              {"range":{"start":{"line":2,"character":0},"end":{"line":2,"character":1}},"code":2304,"message":"Cannot find name 'x'."},
              {"message":"no range"}]}
        """))!!
        assertEquals(3, p.version)
        assertEquals(2, p.diagnostics.size)
        val d = p.diagnostics[0]
        assertEquals(DiagnosticSeverity.ERROR, d.severity)
        assertEquals(setOf(DiagnosticTag.UNNECESSARY), d.tags)
        assertEquals(j("""{"x":1}"""), d.raw["data"])
        assertEquals("here", d.related.single().message)
        assertEquals("2304", p.diagnostics[1].code)
        assertNull(p.diagnostics[1].severity)
    }

    @Test
    fun pullDiagnosticReports() {
        val full = DocumentDiagnosticReport.fromJson(j("""{"kind":"full","resultId":"r1","items":[{"range":{"start":{"line":0,"character":0},"end":{"line":0,"character":1}},"message":"m"}]}""")) as DocumentDiagnosticReport.Full
        assertEquals("r1", full.resultId)
        assertEquals(1, full.items.size)
        assertEquals(DocumentDiagnosticReport.Unchanged("r1"), DocumentDiagnosticReport.fromJson(j("""{"kind":"unchanged","resultId":"r1"}""")))
        assertNull(DocumentDiagnosticReport.fromJson(j("""{"kind":"weird"}""")))
    }

    @Test
    fun decorationsParse() {
        val hints = InlayHint.listFromJson(j("""
            [{"position":{"line":2,"character":10},"label":[{"value":": "},{"value":"number","location":{"uri":"file:///usr/lib/node_modules/typescript/lib/lib.es5.d.ts","range":{"start":{"line":1,"character":0},"end":{"line":1,"character":6}}}}],"kind":1,"paddingLeft":true},
             {"position":{"line":3,"character":4},"label":"x:","kind":2},
             {"position":{"line":3,"character":4},"label":[]}]
        """))
        assertEquals(listOf(": number", "x:"), hints.map { it.text })
        assertEquals(InlayHintKind.TYPE, hints[0].kind)
        assertTrue(hints[0].label[1].location != null)
        val folds = FoldingRange.listFromJson(j("""[{"startLine":0,"endLine":4,"kind":"imports"},{"startLine":5,"endLine":5},{"startLine":9,"endLine":3}]"""))
        assertEquals(listOf(FoldingRange(0, 4, "imports", null)), folds)
        val chain = SelectionRangeChain.listFromJson(j("""
            [{"range":{"start":{"line":1,"character":4},"end":{"line":1,"character":7}},
              "parent":{"range":{"start":{"line":1,"character":0},"end":{"line":1,"character":12}},
              "parent":{"range":{"start":{"line":0,"character":0},"end":{"line":3,"character":0}}}}}]
        """)).single()
        assertEquals(3, chain.ranges.size)
        val lens = CodeLens.listFromJson(j("""[{"range":{"start":{"line":0,"character":0},"end":{"line":0,"character":3}},"data":{"id":1}},{"range":{"start":{"line":4,"character":0},"end":{"line":4,"character":3}},"command":{"title":"2 references","command":"editor.action.showReferences"}}]"""))
        assertNull(lens[0].command)
        assertEquals("2 references", lens[1].command!!.title)
        val links = DocumentLink.listFromJson(j("""[{"range":{"start":{"line":0,"character":0},"end":{"line":0,"character":10}},"target":"https://example.org"},{"range":{"start":{"line":1,"character":0},"end":{"line":1,"character":3}}}]"""))
        assertEquals("https://example.org", links[0].target)
        assertNull(links[1].target)
        val highlights = DocumentHighlight.listFromJson(j("""[{"range":{"start":{"line":0,"character":0},"end":{"line":0,"character":1}},"kind":3},{"range":{"start":{"line":1,"character":0},"end":{"line":1,"character":1}}}]"""))
        assertEquals(listOf(DocumentHighlightKind.WRITE, DocumentHighlightKind.TEXT), highlights.map { it.kind })
    }

    @Test
    fun serverCapabilitiesFromRealShapes() {
        val pyright = ServerCapabilities.fromJson(j("""
            {"textDocumentSync":2,"definitionProvider":{"workDoneProgress":true},"hoverProvider":true,
             "completionProvider":{"triggerCharacters":[".","[","\"","'"],"resolveProvider":true},
             "signatureHelpProvider":{"triggerCharacters":["(",",",")"],"retriggerCharacters":[","]},
             "renameProvider":{"prepareProvider":true},"codeActionProvider":{"codeActionKinds":["quickfix","source.organizeImports"]},
             "documentFormattingProvider":false,"diagnosticProvider":{"identifier":"pyright","interFileDependencies":true,"workspaceDiagnostics":false},
             "executeCommandProvider":{"commands":["pyright.organizeimports"]}}
        """).jsonObject)
        assertEquals(SyncKind.INCREMENTAL, pyright.sync.change)
        assertTrue(pyright.sync.openClose && pyright.sync.save)
        assertTrue(pyright.supports(LspFeature.DEFINITION))
        assertFalse(pyright.supports(LspFeature.FORMATTING))
        assertFalse(pyright.supports(LspFeature.REFERENCES))
        assertTrue(pyright.supports(LspFeature.DIAGNOSTICS))
        assertTrue(pyright.pullDiagnostics)
        assertEquals("pyright", pyright.diagnosticIdentifier)
        assertTrue(pyright.prepareRename && pyright.completionResolve)
        assertEquals(listOf(","), pyright.signatureRetriggerCharacters)

        val gopls = ServerCapabilities.fromJson(j("""
            {"textDocumentSync":{"openClose":true,"change":2,"save":{}},"documentOnTypeFormattingProvider":{"firstTriggerCharacter":"\n"},
             "semanticTokensProvider":{"legend":{"tokenTypes":["namespace","type","function"],"tokenModifiers":["declaration","readonly"]},"full":{"delta":true},"range":true},
             "inlayHintProvider":{},"positionEncoding":"utf-16"}
        """).jsonObject)
        assertTrue(gopls.sync.save && !gopls.sync.saveIncludesText)
        assertTrue(gopls.supports(LspFeature.ON_TYPE_FORMATTING))
        assertTrue(gopls.supports(LspFeature.SEMANTIC_TOKENS) && gopls.semanticTokensDelta && gopls.semanticTokensRange)
        assertTrue(gopls.supports(LspFeature.INLAY_HINTS))
        assertEquals(SyncOptions.NONE, SyncOptions.fromJson(j("0")))
    }

    @Test
    fun semanticTokensDecodeAndDelta() {
        val legend = SemanticTokensLegend(listOf("namespace", "type", "function"), listOf("declaration", "readonly"))
        // line 0 col 5 len 3 function [declaration]; line 0 col 10 len 2 type; line 2 col 1 len 4 namespace [readonly]; unknown type 9
        val data = intArrayOf(0, 5, 3, 2, 1, 0, 5, 2, 1, 0, 2, 1, 4, 0, 2, 0, 3, 1, 9, 0)
        val tokens = SemanticTokensCodec.decode(data, legend)
        assertEquals(
            listOf(
                SemanticToken(0, 5, 3, "function", setOf("declaration")),
                SemanticToken(0, 10, 2, "type", emptySet()),
                SemanticToken(2, 1, 4, "namespace", setOf("readonly")),
            ),
            tokens,
        )
        val edits = listOf(SemanticTokensEdit(5, 5, intArrayOf(0, 6, 2, 1, 0)), SemanticTokensEdit(0, 0, intArrayOf()))
        val applied = SemanticTokensCodec.applyEdits(data, edits)!!
        assertEquals(11, SemanticTokensCodec.decode(applied, legend)[1].start)
        assertNull(SemanticTokensCodec.applyEdits(data, listOf(SemanticTokensEdit(18, 5, intArrayOf()))))
        assertNull(SemanticTokensCodec.applyEdits(data, listOf(SemanticTokensEdit(0, 6, intArrayOf()), SemanticTokensEdit(5, 1, intArrayOf()))))
        val delta = SemanticTokensDeltaResult.fromJson(j("""{"resultId":"2","edits":[{"start":5,"deleteCount":5,"data":[0,6,2,1,0]}]}""")) as SemanticTokensDeltaResult.Delta
        assertEquals("2", delta.resultId)
        assertTrue(SemanticTokensDeltaResult.fromJson(j("""{"resultId":"3","data":[0,0,1,0,0]}""")) is SemanticTokensDeltaResult.Full)
    }
}
