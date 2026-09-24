package dev.easyide.extensions.whenclause

import dev.easyide.extensions.ExtensionPolicy

sealed interface WhenParseResult {
    data class Ok(val expr: WhenExpr) : WhenParseResult

    /** [offset] is the 0-based character offset of the offending token (column = offset + 1). */
    data class Error(val offset: Int, val message: String) : WhenParseResult
}

/**
 * Recursive-descent parser for extension-runtime.md sec 6.1:
 *
 * ```
 * expr    := and ('||' and)*          and := unary ('&&' unary)*       unary := '!' unary | cmp
 * cmp     := primary (op value | 'in' container | 'not' 'in' container | '=~' REGEX)?
 * primary := '(' expr ')' | IDENT | 'true' | 'false'
 * ```
 *
 * Precedence low to high: `||`, `&&`, `!`, comparison. Only a key may be compared.
 */
object WhenParser {
    /** Parenthesis nesting bound, so a hostile manifest cannot exhaust the stack. */
    const val MAX_DEPTH = 64

    fun parse(text: String): WhenParseResult {
        if (text.length > ExtensionPolicy.MAX_WHEN_LENGTH) {
            return WhenParseResult.Error(0, "when-clause longer than ${ExtensionPolicy.MAX_WHEN_LENGTH} characters")
        }
        // Syntax errors in author text are expected input, reported as a value.
        return try {
            val tokens = WhenLexer(text).tokens()
            if (tokens.first().kind == TokenKind.EOF) return WhenParseResult.Error(0, "empty when-clause")
            WhenParseResult.Ok(Parser(tokens).parseAll())
        } catch (e: WhenSyntaxError) {
            WhenParseResult.Error(e.offset, e.message)
        }
    }

    /** Canonical text: `parse(normalize(e))` yields a tree equal to `e`. */
    fun normalize(e: WhenExpr): String = when (e) {
        is WhenExpr.Const -> e.value.toString()
        is WhenExpr.Key -> e.name
        is WhenExpr.Not -> "!" + when (e.expr) {
            is WhenExpr.Key, is WhenExpr.Const, is WhenExpr.Not -> normalize(e.expr)
            else -> "(" + normalize(e.expr) + ")"
        }
        is WhenExpr.And -> e.parts.joinToString(" && ") { if (it is WhenExpr.Or) "(" + normalize(it) + ")" else normalize(it) }
        is WhenExpr.Or -> e.parts.joinToString(" || ") { normalize(it) }
        is WhenExpr.Compare -> "${e.key} ${e.op.text} ${quote(e.literal)}"
        is WhenExpr.Matches -> "${e.key} =~ /${e.pattern.replace("/", "\\/")}/${e.flags}"
        is WhenExpr.In -> e.key + (if (e.negated) " not in " else " in ") + when (val c = e.container) {
            is WhenExpr.Container.KeyRef -> c.name
            is WhenExpr.Container.Literal -> quote(c.items.joinToString(","))
        }
    }

    private fun quote(s: String): String = "'" + s.replace("'", "\\'") + "'"

    private class Parser(private val tokens: List<Token>) {
        private var i = 0
        private var depth = 0

        fun parseAll(): WhenExpr {
            val e = expr()
            val t = peek()
            if (t.kind != TokenKind.EOF) throw WhenSyntaxError(t.offset, "unexpected '${t.text}'")
            return e
        }

        private fun expr(): WhenExpr {
            val parts = arrayListOf(and())
            while (peek().kind == TokenKind.OR) { i++; parts += and() }
            return if (parts.size == 1) parts[0] else WhenExpr.Or(parts)
        }

        private fun and(): WhenExpr {
            val parts = arrayListOf(unary())
            while (peek().kind == TokenKind.AND) { i++; parts += unary() }
            return if (parts.size == 1) parts[0] else WhenExpr.And(parts)
        }

        private fun unary(): WhenExpr {
            if (peek().kind == TokenKind.BANG) {
                i++
                enter()
                return WhenExpr.Not(unary()).also { depth-- }
            }
            return cmp()
        }

        private fun cmp(): WhenExpr {
            val start = peek()
            val primary = primary()
            val t = peek()
            val op = OPS[t.kind]
            val isComparison = op != null || t.kind == TokenKind.IN || t.kind == TokenKind.NOT || t.kind == TokenKind.MATCH
            if (!isComparison) return primary
            if (primary !is WhenExpr.Key || start.kind != TokenKind.IDENT) {
                throw WhenSyntaxError(t.offset, "only a context key can be compared")
            }
            i++
            return when {
                op != null -> WhenExpr.Compare(primary.name, op, value())
                t.kind == TokenKind.MATCH -> regex(primary.name)
                t.kind == TokenKind.IN -> WhenExpr.In(primary.name, container(), negated = false)
                else -> {
                    val inTok = next()
                    if (inTok.kind != TokenKind.IN) throw WhenSyntaxError(inTok.offset, "expected 'in' after 'not'")
                    WhenExpr.In(primary.name, container(), negated = true)
                }
            }
        }

        private fun primary(): WhenExpr {
            val t = next()
            return when (t.kind) {
                TokenKind.LPAREN -> {
                    enter()
                    val e = expr()
                    val close = next()
                    if (close.kind != TokenKind.RPAREN) throw WhenSyntaxError(close.offset, "expected ')'")
                    depth--
                    e
                }
                TokenKind.IDENT -> WhenExpr.Key(t.text)
                TokenKind.TRUE -> WhenExpr.Const(true)
                TokenKind.FALSE -> WhenExpr.Const(false)
                TokenKind.EOF -> throw WhenSyntaxError(t.offset, "unexpected end of when-clause")
                else -> throw WhenSyntaxError(t.offset, "unexpected '${t.text}'")
            }
        }

        private fun value(): String {
            val t = next()
            return when (t.kind) {
                TokenKind.STRING, TokenKind.NUMBER, TokenKind.IDENT, TokenKind.WORD, TokenKind.TRUE, TokenKind.FALSE -> t.text
                TokenKind.EOF -> throw WhenSyntaxError(t.offset, "expected a value after comparison")
                else -> throw WhenSyntaxError(t.offset, "expected a value, found '${t.text}'")
            }
        }

        private fun container(): WhenExpr.Container {
            val t = next()
            return when (t.kind) {
                TokenKind.IDENT -> WhenExpr.Container.KeyRef(t.text)
                TokenKind.STRING -> WhenExpr.Container.Literal(t.text.split(',').map { it.trim() }.filter { it.isNotEmpty() })
                else -> throw WhenSyntaxError(t.offset, "expected a context key or quoted list after 'in'")
            }
        }

        private fun regex(key: String): WhenExpr {
            val t = next()
            if (t.kind != TokenKind.REGEX) throw WhenSyntaxError(t.offset, "expected /regex/ after =~")
            // The pattern is author text; compile errors are reported at the literal.
            return try {
                WhenExpr.Matches(key, t.text, t.flags)
            } catch (e: IllegalArgumentException) {
                throw WhenSyntaxError(t.offset, "invalid regex: ${e.message?.lineSequence()?.first()}")
            }
        }

        private fun enter() {
            if (++depth > MAX_DEPTH) throw WhenSyntaxError(peek().offset, "nesting deeper than $MAX_DEPTH")
        }

        private fun peek(): Token = tokens[i]
        private fun next(): Token = tokens[i].also { if (it.kind != TokenKind.EOF) i++ }
    }

    private val OPS = mapOf(
        TokenKind.EQ to CompareOp.EQ, TokenKind.NE to CompareOp.NE, TokenKind.LT to CompareOp.LT,
        TokenKind.LE to CompareOp.LE, TokenKind.GT to CompareOp.GT, TokenKind.GE to CompareOp.GE,
    )
}
