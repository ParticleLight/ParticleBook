package app.particlebook.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchTokenizerTest {

    @Test
    fun cjkIndexAndQueryUseTheSameBigramSpaceSeparatedVocabulary() {
        val indexed = SearchTokenizer.indexText("这本书非常好看")
        // 先 bigram 后 unigram，bigram 之间用空格分隔；4 字查询的 phrase 仍相邻。
        assertTrue(indexed, indexed.contains("非常 常好 好看"))
        assertEquals(listOf("非常", "常好", "好看"), SearchTokenizer.queryTokens("非常好看"))
        assertEquals("\"非常 常好 好看\"", SearchTokenizer.ftsMatch("非常好看"))
        assertEquals("\"好看\"", SearchTokenizer.ftsMatch("好看"))
        assertEquals("\"看\"", SearchTokenizer.ftsMatch("看"))
    }

    @Test
    fun fakeFtsFindsOneTwoAndFourCharacterCjkQueries() {
        val indexed = SearchTokenizer.indexText("这本书非常好看")
        assertTrue("1 字应命中 unigram", TestFts.matches(indexed, "看"))
        assertTrue("2 字应命中 bigram", TestFts.matches(indexed, "好看"))
        assertTrue("4 字应命中连续 bigram phrase", TestFts.matches(indexed, "非常好看"))
        assertFalse("不相邻的 4 字不应命中", TestFts.matches(indexed, "这书"))
        assertFalse("不存在的字不应命中", TestFts.matches(indexed, "难看"))
    }

    @Test
    fun phraseOrderMattersAcrossSeparateCjkRuns() {
        val indexed = SearchTokenizer.indexText("很好。好看")
        assertTrue(TestFts.matches(indexed, "很好"))
        assertTrue(TestFts.matches(indexed, "好看"))
        assertFalse("跨句号拼出的 4 字不应命中", TestFts.matches(indexed, "很好看"))
    }

    @Test
    fun latinWordsAreLowercasedAndPhraseAdjacencyIsPreserved() {
        val indexed = SearchTokenizer.indexText("Hello, World!")
        assertEquals("hello world", indexed)
        assertTrue(TestFts.matches(indexed, "hello"))
        assertTrue(TestFts.matches(indexed, "Hello World"))
        assertFalse(TestFts.matches(indexed, "World Hello"))
    }

    @Test
    fun repositorySearchEndToEndUsesTokenizerAndPhraseSemantics() {
        val repository = TestLibraryRepository()
        val id = repository.saveBook(BookRow(id = 1, stableKey = "zlib:1", title = "测试书", format = "epub"))
        repository.saveSearchDocument(id, "body", "chapter-1", "这本书非常好看。")
        assertEquals(1, repository.search("看").size)
        assertEquals(1, repository.search("好看").size)
        assertEquals(1, repository.search("非常好看").size)
        assertEquals(0, repository.search("难看").size)
    }

    @Test
    fun emptyQueryProducesNoMatchExpression() {
        assertNull(SearchTokenizer.ftsMatch("   "))
        assertNull(SearchTokenizer.ftsMatch("！？，。"))
        assertEquals("", SearchTokenizer.indexText("！？，。"))
    }
}
