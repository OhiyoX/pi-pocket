package id.harness.pipocket

import id.harness.pipocket.ui.CodeHighlight
import id.harness.pipocket.ui.CodeLang
import id.harness.pipocket.ui.TokenKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 「查看交付」里的代码高亮词法（用户 2026-10-24 要求）。纯数据，不依赖 Compose 渲染。 */
class CodeHighlightTest {

    private fun slice(text: String, start: Int, end: Int) = text.substring(start, end)

    @Test
    fun kotlinKeywordsStringsAndComments() {
        val code = "fun main() {\n  val name = \"pocket\" // 名字\n}\n"
        val tokens = CodeHighlight.tokens(code, CodeLang.Kotlin)

        assertTrue(tokens.any { it.kind == TokenKind.Keyword && slice(code, it.start, it.end) == "fun" })
        assertTrue(tokens.any { it.kind == TokenKind.Keyword && slice(code, it.start, it.end) == "val" })
        assertTrue(tokens.any { it.kind == TokenKind.String && slice(code, it.start, it.end) == "\"pocket\"" })
        assertTrue(tokens.any { it.kind == TokenKind.Function && slice(code, it.start, it.end) == "main" })
        val comment = tokens.first { it.kind == TokenKind.Comment }
        assertEquals("// 名字", slice(code, comment.start, comment.end))
    }

    @Test
    fun tokensAreOrderedAndNeverOverlap() {
        val code = "class A { /* a /* b */ val x = 1 } // tail"
        val tokens = CodeHighlight.tokens(code, CodeLang.Kotlin)
        assertTrue(tokens.isNotEmpty())
        var lastEnd = 0
        for (token in tokens) {
            assertTrue("token out of order: $token", token.start >= lastEnd)
            assertTrue(token.end > token.start)
            lastEnd = token.end
        }
    }

    @Test
    fun jsonHighlightsStringsAndNumbersWithoutKeywords() {
        val json = """{"count": 12, "ok": true}"""
        val tokens = CodeHighlight.tokens(json, CodeLang.Json)
        assertTrue(tokens.any { it.kind == TokenKind.String && slice(json, it.start, it.end) == "\"count\"" })
        assertTrue(tokens.any { it.kind == TokenKind.Number && slice(json, it.start, it.end) == "12" })
        // JSON 没有关键字：不能把 true 之类刷成关键字色（它属于字面量，不是关键字集合成员）
        assertTrue(tokens.none { it.kind == TokenKind.Keyword })
    }

    @Test
    fun yamlKeysBeforeColonAreHighlighted() {
        val yaml = "name: pi-pocket\nport: 8789\n"
        val tokens = CodeHighlight.tokens(yaml, CodeLang.Yaml)
        assertTrue(tokens.any { it.kind == TokenKind.Function && slice(yaml, it.start, it.end) == "name" })
        assertTrue(tokens.any { it.kind == TokenKind.Function && slice(yaml, it.start, it.end) == "port" })
        assertTrue(tokens.any { it.kind == TokenKind.Number && slice(yaml, it.start, it.end) == "8789" })
    }

    @Test
    fun shellHashIsACommentButNotInsideAWord() {
        val sh = "echo \"a#b\" # trailing\nURL=https://x#frag\n"
        val tokens = CodeHighlight.tokens(sh, CodeLang.Shell)
        val comments = tokens.filter { it.kind == TokenKind.Comment }.map { slice(sh, it.start, it.end) }
        assertEquals(listOf("# trailing"), comments)
        // 引号里的 # 是字符串内容，不是注释
        assertTrue(tokens.any { it.kind == TokenKind.String && slice(sh, it.start, it.end) == "\"a#b\"" })
    }

    @Test
    fun sqlKeywordsAreCaseInsensitive() {
        val sql = "SELECT id FROM users"
        val tokens = CodeHighlight.tokens(sql, CodeLang.Sql)
        val keywords = tokens.filter { it.kind == TokenKind.Keyword }.map { slice(sql, it.start, it.end) }
        assertEquals(listOf("SELECT", "FROM"), keywords)
    }

    @Test
    fun hugeFilesAreNotHighlighted() {
        val big = "val x = 1\n".repeat(20_000)
        assertTrue(big.length > CodeHighlight.MAX_HIGHLIGHT_CHARS)
        assertTrue(CodeHighlight.tokens(big, CodeLang.Kotlin).isEmpty())
    }

    @Test
    fun languageIsChosenByExtension() {
        assertEquals(CodeLang.Kotlin, CodeHighlight.languageOf("/a/B.kt"))
        assertEquals(CodeLang.TypeScript, CodeHighlight.languageOf("/a/b.tsx"))
        assertEquals(CodeLang.Yaml, CodeHighlight.languageOf("/a/c.yml"))
        assertEquals(CodeLang.Shell, CodeHighlight.languageOf("/a/install.sh"))
        assertEquals(CodeLang.Markdown, CodeHighlight.languageOf("/a/README.md"))
        assertEquals(CodeLang.Plain, CodeHighlight.languageOf("/a/photo.png"))
        assertEquals(CodeLang.Plain, CodeHighlight.languageOf("/a/README"))
        assertEquals(CodeLang.Shell, CodeHighlight.languageOf("/a/Dockerfile"))
    }
}
