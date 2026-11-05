package app.particlebook.android.data

import java.util.Locale

/**
 * 应用层 CJK bigram 索引/查询。
 *
 * 为什么不用 FTS4 unicode61 直接索引中文：本机 SQLite 3.49.1 实测 1/2/4 字查询全部 0 命中
 * （详见 docs/android-port/parity/research-data.md）。这里把中文正文预处理成空格分隔的 bigram，
 * 再交给 FTS4 unicode61；查询侧同样生成 bigram，并用 phrase 保持相邻关系。
 *
 * 边界：单字 CJK 查询只索引单字，没有 unigram 兜底（v1 不接受单字全文检索，标题/作者过滤走 SQL）。
 * 拉丁文本按整词索引，大小写归一。
 */
object SearchTokenizer {

    fun indexText(raw: String): String {
        val tokens = mutableListOf<String>()
        val cjk = StringBuilder()
        val word = StringBuilder()

        fun flushCjk() {
            val run = cjk.toString()
            if (run.isEmpty()) return
            if (run.length == 1) {
                tokens += run
            } else {
                // 先 bigram 后 unigram：4 字查询的 bigram phrase 仍相邻；1 字查询命中 unigram。
                for (index in 0 until run.length - 1) {
                    tokens += run.substring(index, index + 2)
                }
                for (ch in run) {
                    tokens += ch.toString()
                }
            }
            cjk.setLength(0)
        }

        fun flushWord() {
            if (word.isEmpty()) return
            tokens += word.toString().lowercase(Locale.ROOT)
            word.setLength(0)
        }

        for (ch in raw) {
            when {
                isCjk(ch) -> {
                    flushWord()
                    cjk.append(ch)
                }
                ch.isLetterOrDigit() -> {
                    flushCjk()
                    word.append(ch)
                }
                else -> {
                    flushCjk()
                    flushWord()
                }
            }
        }
        flushCjk()
        flushWord()
        return tokens.joinToString(" ")
    }

    fun queryTokens(raw: String): List<String> {
        val tokens = mutableListOf<String>()
        val cjk = StringBuilder()
        val word = StringBuilder()

        fun flushCjk() {
            val run = cjk.toString()
            if (run.isEmpty()) return
            if (run.length == 1) {
                tokens += run
            } else {
                for (index in 0 until run.length - 1) {
                    tokens += run.substring(index, index + 2)
                }
            }
            cjk.setLength(0)
        }

        fun flushWord() {
            if (word.isEmpty()) return
            tokens += word.toString().lowercase(Locale.ROOT)
            word.setLength(0)
        }

        for (ch in raw) {
            when {
                isCjk(ch) -> {
                    flushWord()
                    cjk.append(ch)
                }
                ch.isLetterOrDigit() -> {
                    flushCjk()
                    word.append(ch)
                }
                else -> {
                    flushCjk()
                    flushWord()
                }
            }
        }
        flushCjk()
        flushWord()
        return tokens
    }

    /**
     * FTS4 MATCH 表达式。CJK bigram 必须作为一个 phrase（引号内空格分隔），
     * 否则 FTS 会按 AND 命中不相邻的 bigram，4 字查询会变宽。
     */
    fun ftsMatch(raw: String): String? {
        val tokens = queryTokens(raw)
        if (tokens.isEmpty()) return null
        val escaped = tokens.joinToString(" ") { it.replace("\"", "\"\"") }
        return "\"" + escaped + "\""
    }

    fun isCjk(ch: Char): Boolean {
        val code = ch.code
        return code in 0x3400..0x4DBF ||
            code in 0x4E00..0x9FFF ||
            code in 0xF900..0xFAFF ||
            code in 0x3040..0x30FF ||
            code in 0xAC00..0xD7AF
    }
}
