package dev.easyide.extensions.whenclause

import dev.easyide.extensions.ExtensionPolicy

internal enum class TokenKind {
    IDENT, STRING, NUMBER, WORD, REGEX, TRUE, FALSE, IN, NOT,
    BANG, AND, OR, LPAREN, RPAREN, EQ, NE, LT, LE, GT, GE, MATCH, EOF,
}

/** [text] is the decoded value (STRING without quotes, REGEX pattern); [flags] only for REGEX. */
internal data class Token(val kind: TokenKind, val text: String, val offset: Int, val flags: String = "")

internal class WhenSyntaxError(val offset: Int, override val message: String) : Exception(message)

/**
 * Tokenizer for the when-clause grammar (extension-runtime.md sec 6.1).
 *
 * Context-sensitive in two places, both for VS Code compatibility: after `=~` it lexes a
 * `/regex/flags` literal, and after a comparison operator it lexes one unquoted word
 * (`resourceExtname == .py`, `x == a-b`) that the IDENT rule alone would split or reject.
 */
internal class WhenLexer(private val src: String) {
    private var pos = 0

    fun tokens(): List<Token> {
        val out = ArrayList<Token>()
        while (true) {
            skipSpace()
            if (pos >= src.length) { out += Token(TokenKind.EOF, "", pos); return out }
            val prev = out.lastOrNull()?.kind
            out += when {
                prev == TokenKind.MATCH -> regex()
                prev in COMPARISON && src[pos] != '\'' -> word()
                else -> next()
            }
        }
    }

    private fun next(): Token {
        val start = pos
        val c = src[pos]
        fun op(kind: TokenKind, len: Int): Token { pos += len; return Token(kind, src.substring(start, pos), start) }
        return when {
            c == '(' -> op(TokenKind.LPAREN, 1)
            c == ')' -> op(TokenKind.RPAREN, 1)
            c == '!' && peek(1) == '=' -> op(TokenKind.NE, 2)
            c == '!' -> op(TokenKind.BANG, 1)
            c == '&' && peek(1) == '&' -> op(TokenKind.AND, 2)
            c == '|' && peek(1) == '|' -> op(TokenKind.OR, 2)
            c == '=' && peek(1) == '=' -> op(TokenKind.EQ, 2)
            c == '=' && peek(1) == '~' -> op(TokenKind.MATCH, 2)
            c == '<' && peek(1) == '=' -> op(TokenKind.LE, 2)
            c == '<' -> op(TokenKind.LT, 1)
            c == '>' && peek(1) == '=' -> op(TokenKind.GE, 2)
            c == '>' -> op(TokenKind.GT, 1)
            c == '\'' -> string()
            c.isDigit() || (c == '-' && peek(1)?.isDigit() == true) -> number()
            isIdentStart(c) -> ident()
            else -> throw WhenSyntaxError(start, "unexpected character '$c'")
        }
    }

    private fun ident(): Token {
        val start = pos
        while (pos < src.length && isIdentPart(src[pos])) pos++
        val text = src.substring(start, pos)
        val kind = when (text) {
            "true" -> TokenKind.TRUE
            "false" -> TokenKind.FALSE
            "in" -> TokenKind.IN
            "not" -> TokenKind.NOT
            else -> TokenKind.IDENT
        }
        return Token(kind, text, start)
    }

    private fun number(): Token {
        val start = pos
        if (src[pos] == '-') pos++
        while (pos < src.length && src[pos].isDigit()) pos++
        if (pos < src.length - 1 && src[pos] == '.' && src[pos + 1].isDigit()) {
            pos++
            while (pos < src.length && src[pos].isDigit()) pos++
        }
        // `1abc` is not a number followed by a key; treat it as an error rather than guess.
        if (pos < src.length && isIdentPart(src[pos])) throw WhenSyntaxError(pos, "unexpected character '${src[pos]}' in number")
        return Token(TokenKind.NUMBER, src.substring(start, pos), start)
    }

    private fun string(): Token {
        val start = pos
        pos++ // opening quote
        val sb = StringBuilder()
        while (pos < src.length) {
            val c = src[pos]
            when {
                c == '\\' && peek(1) == '\'' -> { sb.append('\''); pos += 2 }
                c == '\'' -> { pos++; return Token(TokenKind.STRING, sb.toString(), start) }
                else -> { sb.append(c); pos++ }
            }
        }
        throw WhenSyntaxError(start, "unterminated string")
    }

    private fun word(): Token {
        val start = pos
        while (pos < src.length && !src[pos].isWhitespace() && src[pos] !in WORD_STOP) pos++
        if (pos == start) return next()
        val text = src.substring(start, pos)
        return when {
            text == "true" -> Token(TokenKind.TRUE, text, start)
            text == "false" -> Token(TokenKind.FALSE, text, start)
            NUMBER.matches(text) -> Token(TokenKind.NUMBER, text, start)
            else -> Token(TokenKind.WORD, text, start)
        }
    }

    private fun regex(): Token {
        val start = pos
        if (src[pos] != '/') throw WhenSyntaxError(pos, "expected /regex/ after =~")
        pos++
        val sb = StringBuilder()
        var inClass = false
        while (pos < src.length) {
            val c = src[pos]
            when {
                c == '\\' && pos + 1 < src.length -> {
                    // `\/` is an escaped delimiter; every other escape stays for the regex engine.
                    if (src[pos + 1] == '/') sb.append('/') else sb.append(c).append(src[pos + 1])
                    pos += 2
                }
                c == '[' -> { inClass = true; sb.append(c); pos++ }
                c == ']' -> { inClass = false; sb.append(c); pos++ }
                c == '/' && !inClass -> {
                    pos++
                    val flagStart = pos
                    while (pos < src.length && src[pos].isLetter()) pos++
                    val flags = src.substring(flagStart, pos)
                    flags.firstOrNull { it !in WhenExpr.Matches.VALID_FLAGS }?.let {
                        throw WhenSyntaxError(flagStart + flags.indexOf(it), "unknown regex flag '$it'")
                    }
                    if (sb.length > ExtensionPolicy.MAX_PATTERN_LENGTH) {
                        throw WhenSyntaxError(start, "regex longer than ${ExtensionPolicy.MAX_PATTERN_LENGTH} characters")
                    }
                    return Token(TokenKind.REGEX, sb.toString(), start, flags)
                }
                else -> { sb.append(c); pos++ }
            }
        }
        throw WhenSyntaxError(start, "unterminated regex")
    }

    private fun skipSpace() { while (pos < src.length && src[pos].isWhitespace()) pos++ }
    private fun peek(ahead: Int): Char? = src.getOrNull(pos + ahead)

    companion object {
        private val COMPARISON = setOf(TokenKind.EQ, TokenKind.NE, TokenKind.LT, TokenKind.LE, TokenKind.GT, TokenKind.GE)
        private const val WORD_STOP = "()!&|=<>'"
        private val NUMBER = Regex("-?[0-9]+(\\.[0-9]+)?")

        fun isIdentStart(c: Char): Boolean = c in 'A'..'Z' || c in 'a'..'z' || c == '_'
        fun isIdentPart(c: Char): Boolean = isIdentStart(c) || c.isDigit() || c == '.' || c == ':' || c == '-'
    }
}
