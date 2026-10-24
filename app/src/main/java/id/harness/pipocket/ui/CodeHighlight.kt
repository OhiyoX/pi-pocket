package id.harness.pipocket.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight

/**
 * 「查看交付」的代码高亮（用户 2026-10-24：「读文件的时候支持代码高亮吧」）。
 *
 * 口径：
 * - **不引第三方高亮库**（app 要小而稳、且离线）：每个语言一套轻量词法（注释 / 字符串 / 数字 /
 *   关键字 / 类型 / 函数名 / 注解），够读代码就行；
 * - 颜色取终端/主题色（[TerminalColors]），与聊天里的行内码、代码块保持一致；
 * - 大文件不硬算：超过 [MAX_HIGHLIGHT_CHARS] 直接按纯文本渲染（否则输入法/滚动会卡）。
 *
 * 词法结果是纯数据（[CodeToken]），JVM 单测直接覆盖；[annotated] 只负责把 token 变成 AnnotatedString。
 */
enum class CodeLang {
    Kotlin, Java, JavaScript, TypeScript, Json, Yaml, Toml, Shell, Python,
    Go, Rust, C, Cpp, Xml, Html, Css, Sql, Markdown, Plain;

    val displayName: String
        get() = when (this) {
            Kotlin -> "Kotlin"; Java -> "Java"; JavaScript -> "JavaScript"; TypeScript -> "TypeScript"
            Json -> "JSON"; Yaml -> "YAML"; Toml -> "TOML"; Shell -> "Shell"; Python -> "Python"
            Go -> "Go"; Rust -> "Rust"; C -> "C"; Cpp -> "C++"; Xml -> "XML"; Html -> "HTML"
            Css -> "CSS"; Sql -> "SQL"; Markdown -> "Markdown"; Plain -> "文本"
        }
}

enum class TokenKind { Keyword, Type, String, Number, Comment, Annotation, Function }

/** 一个高亮片段（[start, end)）。未覆盖的部分按正文色渲染。 */
data class CodeToken(val kind: TokenKind, val start: Int, val end: Int)

/** 高亮用的颜色（由主题算好后传进纯逻辑，便于测试与复用）。 */
data class CodeColors(
    val keyword: Color,
    val type: Color,
    val string: Color,
    val number: Color,
    val comment: Color,
    val annotation: Color,
    val function: Color,
) {
    companion object {
        @Composable
        fun of(): CodeColors = CodeColors(
            keyword = MaterialTheme.colorScheme.primary,
            type = MaterialTheme.colorScheme.tertiary,
            string = TerminalColors.success(),
            number = TerminalColors.warning(),
            comment = TerminalColors.footer(),
            annotation = TerminalColors.heading(),
            function = TerminalColors.link(),
        )
    }
}

object CodeHighlight {
    /** 超过这么多字符就不高亮（纯文本渲染）：大文件高亮会明显拖慢滚动。 */
    const val MAX_HIGHLIGHT_CHARS = 150_000

    private val KOTLIN_KEYWORDS = setOf(
        "package", "import", "class", "object", "interface", "fun", "val", "var", "if", "else", "when",
        "for", "while", "do", "return", "break", "continue", "throw", "try", "catch", "finally", "is", "as",
        "in", "out", "by", "where", "init", "constructor", "this", "super", "null", "true", "false",
        "data", "sealed", "enum", "open", "internal", "private", "protected", "public", "override", "lateinit",
        "suspend", "inline", "reified", "companion", "const", "typealias", "expect", "actual", "annotation",
        "crossinline", "noinline", "vararg", "operator", "infix", "tailrec", "external", "abstract", "final",
    )
    private val JAVA_KEYWORDS = setOf(
        "package", "import", "class", "interface", "enum", "extends", "implements", "public", "private",
        "protected", "static", "final", "abstract", "synchronized", "volatile", "transient", "native",
        "if", "else", "switch", "case", "default", "for", "while", "do", "return", "break", "continue",
        "throw", "throws", "try", "catch", "finally", "new", "this", "super", "null", "true", "false",
        "instanceof", "record", "sealed", "permits", "yield", "var",
    )
    private val JS_KEYWORDS = setOf(
        "import", "export", "from", "as", "default", "const", "let", "var", "function", "class", "extends",
        "new", "this", "super", "return", "if", "else", "for", "while", "do", "switch", "case", "break",
        "continue", "try", "catch", "finally", "throw", "async", "await", "yield", "typeof", "instanceof",
        "in", "of", "delete", "void", "null", "undefined", "true", "false", "interface", "type", "enum",
        "implements", "public", "private", "protected", "readonly", "declare", "namespace", "satisfies", "keyof",
    )
    private val PY_KEYWORDS = setOf(
        "import", "from", "as", "def", "class", "return", "if", "elif", "else", "for", "while", "break",
        "continue", "try", "except", "finally", "raise", "with", "lambda", "yield", "pass", "global",
        "nonlocal", "assert", "del", "and", "or", "not", "in", "is", "None", "True", "False", "async", "await",
    )
    private val SHELL_KEYWORDS = setOf(
        "if", "then", "else", "elif", "fi", "for", "in", "do", "done", "case", "esac", "while", "until",
        "function", "return", "local", "export", "readonly", "declare", "unset", "shift", "source", "alias",
        "set", "trap", "exit", "exec", "eval", "true", "false",
    )
    private val GO_KEYWORDS = setOf(
        "package", "import", "func", "type", "struct", "interface", "map", "chan", "var", "const", "return",
        "if", "else", "for", "range", "switch", "case", "default", "break", "continue", "go", "defer",
        "select", "fallthrough", "goto", "nil", "true", "false", "iota",
    )
    private val RUST_KEYWORDS = setOf(
        "fn", "let", "mut", "const", "static", "struct", "enum", "trait", "impl", "mod", "pub", "use", "crate",
        "super", "self", "Self", "return", "if", "else", "match", "for", "while", "loop", "break", "continue",
        "move", "async", "await", "dyn", "where", "unsafe", "ref", "as", "in", "true", "false", "Some", "None", "Ok", "Err",
    )
    private val C_KEYWORDS = setOf(
        "if", "else", "for", "while", "do", "switch", "case", "default", "return", "break", "continue",
        "struct", "union", "enum", "typedef", "static", "extern", "const", "volatile", "sizeof", "goto",
        "inline", "register", "auto", "signed", "unsigned", "void", "char", "short", "int", "long", "float",
        "double", "class", "public", "private", "protected", "virtual", "override", "template", "typename",
        "namespace", "using", "new", "delete", "try", "catch", "throw", "true", "false", "nullptr", "auto",
    )
    private val SQL_KEYWORDS = setOf(
        "select", "from", "where", "insert", "into", "values", "update", "set", "delete", "create", "table",
        "alter", "drop", "index", "view", "join", "left", "right", "inner", "outer", "on", "group", "by",
        "order", "having", "limit", "offset", "union", "all", "distinct", "as", "and", "or", "not", "null",
        "case", "when", "then", "else", "end", "with", "primary", "key", "foreign", "references", "default",
    )
    private val TYPES = setOf(
        "String", "Int", "Long", "Double", "Float", "Boolean", "Char", "Byte", "Short", "Any", "Unit", "Nothing",
        "List", "Map", "Set", "Array", "MutableList", "MutableMap", "MutableSet", "Pair", "Triple", "Sequence",
        "Integer", "Object", "BigDecimal", "BigInteger", "Void", "number", "string", "boolean", "any", "unknown",
        "int", "char", "float", "double", "void", "size_t", "uint8_t", "uint32_t", "ssize_t", "str", "i32", "i64",
        "u8", "u32", "u64", "usize", "f32", "f64", "bool", "Vec", "Option", "Result",
    )

    /** 语言配置：行注释前缀、块注释边界、是否大小写敏感（SQL 不敏感）。 */
    private data class Spec(
        val keywords: Set<String> = emptySet(),
        val lineComments: List<String> = emptyList(),
        val blockComment: Pair<String, String>? = null,
        val ignoreCase: Boolean = false,
        /** YAML/TOML：`key:` / `key =` 的键当属性名（用 Function 色）。 */
        val keyValueBefore: Set<Char> = emptySet(),
        /** Shell 的 `#` 注释；Python 同。 */
        val hashComment: Boolean = false,
    )

    private fun specOf(lang: CodeLang): Spec = when (lang) {
        CodeLang.Kotlin -> Spec(KOTLIN_KEYWORDS, listOf("//"), "/*" to "*/")
        CodeLang.Java -> Spec(JAVA_KEYWORDS, listOf("//"), "/*" to "*/")
        CodeLang.JavaScript, CodeLang.TypeScript -> Spec(JS_KEYWORDS, listOf("//"), "/*" to "*/")
        CodeLang.Python -> Spec(PY_KEYWORDS, hashComment = true, blockComment = "\"\"\"" to "\"\"\"")
        CodeLang.Shell -> Spec(SHELL_KEYWORDS, hashComment = true)
        CodeLang.Go -> Spec(GO_KEYWORDS, listOf("//"), "/*" to "*/")
        CodeLang.Rust -> Spec(RUST_KEYWORDS, listOf("//"), "/*" to "*/")
        CodeLang.C, CodeLang.Cpp -> Spec(C_KEYWORDS, listOf("//"), "/*" to "*/")
        CodeLang.Sql -> Spec(SQL_KEYWORDS, listOf("--"), "/*" to "*/", ignoreCase = true)
        CodeLang.Json -> Spec(lineComments = emptyList())
        CodeLang.Yaml -> Spec(lineComments = emptyList(), hashComment = true, keyValueBefore = setOf(':'))
        CodeLang.Toml -> Spec(lineComments = emptyList(), hashComment = true, keyValueBefore = setOf('=', ':'))
        CodeLang.Xml, CodeLang.Html -> Spec(lineComments = emptyList(), blockComment = "<!--" to "-->")
        CodeLang.Css -> Spec(lineComments = emptyList(), blockComment = "/*" to "*/")
        CodeLang.Markdown -> Spec(lineComments = emptyList())
        CodeLang.Plain -> Spec()
    }

    /** 扩展名（小写，不含点）→ 语言。 */
    fun languageOf(path: String): CodeLang {
        val name = path.substringAfterLast('/')
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "kt", "kts" -> CodeLang.Kotlin
            "java" -> CodeLang.Java
            "js", "mjs", "cjs", "jsx" -> CodeLang.JavaScript
            "ts", "tsx", "mts", "cts" -> CodeLang.TypeScript
            "json", "jsonc" -> CodeLang.Json
            "yaml", "yml" -> CodeLang.Yaml
            "toml" -> CodeLang.Toml
            "sh", "bash", "zsh", "fish", "env" -> CodeLang.Shell
            "py", "pyi" -> CodeLang.Python
            "go" -> CodeLang.Go
            "rs" -> CodeLang.Rust
            "c", "h" -> CodeLang.C
            "cc", "cpp", "cxx", "hpp", "hh" -> CodeLang.Cpp
            "xml", "plist", "svg" -> CodeLang.Xml
            "html", "htm" -> CodeLang.Html
            "css", "scss", "less" -> CodeLang.Css
            "sql" -> CodeLang.Sql
            "md", "markdown" -> CodeLang.Markdown
            // 无扩展名但常见脚本名
            "dockerfile", "makefile", "gradlew" -> CodeLang.Shell
            else -> if (name == "Dockerfile" || name == "Makefile") CodeLang.Shell else CodeLang.Plain
        }
    }

    /** 词法：返回按位置升序、互不重叠的高亮片段。 */
    fun tokens(text: String, lang: CodeLang, maxChars: Int = MAX_HIGHLIGHT_CHARS): List<CodeToken> {
        if (text.length > maxChars) return emptyList()
        val spec = specOf(lang)
        val out = ArrayList<CodeToken>()
        var i = 0
        val n = text.length
        while (i < n) {
            val c = text[i]
            // 行注释
            val linePrefix = spec.lineComments.firstOrNull { text.startsWith(it, i) }
            if (linePrefix != null) {
                val end = text.indexOf('\n', i).let { if (it < 0) n else it }
                out += CodeToken(TokenKind.Comment, i, end)
                i = end
                continue
            }
            if (spec.hashComment && c == '#' && !isInsideWord(text, i)) {
                val end = text.indexOf('\n', i).let { if (it < 0) n else it }
                out += CodeToken(TokenKind.Comment, i, end)
                i = end
                continue
            }
            // 块注释
            var advanced = false
            spec.blockComment?.let { (open, close) ->
                if (text.startsWith(open, i)) {
                    val end = text.indexOf(close, i + open.length).let { if (it < 0) n else it + close.length }
                    out += CodeToken(TokenKind.Comment, i, end)
                    i = end
                    advanced = true
                }
            }
            if (advanced) continue
            if (i >= n) break
            val ch = text[i]
            // 字符串（含多为三引号的语言）
            val triple = if (text.startsWith("\"\"\"", i)) "\"\"\"" else null
            if (triple != null) {
                val end = text.indexOf(triple, i + 3).let { if (it < 0) n else it + 3 }
                out += CodeToken(TokenKind.String, i, end)
                i = end
                continue
            }
            if (ch == '"' || ch == '\'' || ch == '`') {
                var j = i + 1
                while (j < n && text[j] != ch) {
                    if (text[j] == '\\' && j + 1 < n) j++
                    j++
                }
                val end = if (j < n) j + 1 else n
                out += CodeToken(TokenKind.String, i, end)
                i = end
                continue
            }
            // 注解 @Foo
            if (ch == '@' && i + 1 < n && text[i + 1].isLetter()) {
                var j = i + 1
                while (j < n && (text[j].isLetterOrDigit() || text[j] == '_')) j++
                out += CodeToken(TokenKind.Annotation, i, j)
                i = j
                continue
            }
            // 数字
            if (ch.isDigit()) {
                var j = i
                while (j < n && (text[j].isLetterOrDigit() || text[j] == '.' || text[j] == '_')) j++
                out += CodeToken(TokenKind.Number, i, j)
                i = j
                continue
            }
            // 标识符 / 关键字
            if (ch.isLetter() || ch == '_') {
                var j = i
                while (j < n && (text[j].isLetterOrDigit() || text[j] == '_')) j++
                val word = text.substring(i, j)
                val lookup = if (spec.ignoreCase) word.lowercase() else word
                val kind = when {
                    spec.keywords.contains(lookup) || spec.keywords.contains(word) -> TokenKind.Keyword
                    TYPES.contains(word) -> TokenKind.Type
                    // 后面紧跟 `(` ⇒ 当作函数调用（含含参数的构造）
                    nextNonSpace(text, j) == '(' -> TokenKind.Function
                    spec.keyValueBefore.isNotEmpty() && nextNonSpace(text, j) in spec.keyValueBefore -> TokenKind.Function
                    else -> null
                }
                if (kind != null) out += CodeToken(kind, i, j)
                i = j
                continue
            }
            i++
        }
        return out
    }

    private fun isInsideWord(text: String, index: Int): Boolean = index > 0 && (text[index - 1].isLetterOrDigit() || text[index - 1] == '_')

    private fun nextNonSpace(text: String, from: Int): Char? {
        var j = from
        while (j < text.length && text[j].isWhitespace()) j++
        return text.getOrNull(j)
    }

    /** 词法结果 → AnnotatedString（未覆盖部分用正文色）。 */
    fun annotated(text: String, lang: CodeLang, colors: CodeColors): AnnotatedString {
        val tokens = tokens(text, lang)
        if (tokens.isEmpty()) return AnnotatedString(text)
        return buildAnnotatedString {
            append(text)
            for (token in tokens) {
                val style = when (token.kind) {
                    TokenKind.Keyword -> SpanStyle(color = colors.keyword, fontWeight = FontWeight.Medium)
                    TokenKind.Type -> SpanStyle(color = colors.type)
                    TokenKind.String -> SpanStyle(color = colors.string)
                    TokenKind.Number -> SpanStyle(color = colors.number)
                    TokenKind.Comment -> SpanStyle(color = colors.comment, fontStyle = FontStyle.Italic)
                    TokenKind.Annotation -> SpanStyle(color = colors.annotation)
                    TokenKind.Function -> SpanStyle(color = colors.function)
                }
                addStyle(style, token.start, token.end.coerceAtMost(text.length))
            }
        }
    }
}
