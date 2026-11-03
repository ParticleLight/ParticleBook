package app.particlebook.android.zlib

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ZlibChallengeTest {

    private fun fixture(name: String): String =
        javaClass.classLoader!!.getResourceAsStream("zlib/$name")!!.use {
            it.readBytes().toString(Charsets.UTF_8)
        }

    @Test
    fun solvesChallengeFixture() {
        val html = fixture("challenge.html")

        val constant = ZlibChallenge.extractConstant(html)
        assertEquals("549E967E36C0ACE8E4A01AE0655AA2BE116C727B", constant)

        val solution = ZlibChallenge.solve(html)
        assertNotNull(solution)
        assertEquals(348329, solution!!.nonce)
        assertEquals("549E967E36C0ACE8E4A01AE0655AA2BE116C727B348329", solution.token)
    }

    @Test
    fun nonceIndexFollowsConstantPrefix() {
        // 页面 JS：n1 = parseInt('0x' + 常量首字符)，服务端轮换常量后下标跟着变。
        assertEquals(5, ZlibChallenge.nonceIndex("549E967E36C0ACE8E4A01AE0655AA2BE116C727B"))
        assertEquals(6, ZlibChallenge.nonceIndex("690F18407F22620E29113A816269C2F73D3E5766"))
        assertEquals(10, ZlibChallenge.nonceIndex("A9C3F18407F22620E29113A816269C2F73D3E5766"))
        assertEquals(15, ZlibChallenge.nonceIndex("F0C3F18407F22620E29113A816269C2F73D3E5766"))
        assertEquals(null, ZlibChallenge.nonceIndex(""))
        assertEquals(null, ZlibChallenge.nonceIndex("ZZ"))
    }

    @Test
    fun solvesRotatedConstantFixture() {
        // 2026-11-03 真机/桌面实抓：服务端把常量从 549E96… 轮换成 690F18…（首位 6 → 校验字节 6/7）。
        val html = fixture("challenge-rotated.html")
        val constant = ZlibChallenge.extractConstant(html)
        assertEquals("690F18407F22620E29113A816269C2F73D3E5766", constant)

        val solution = ZlibChallenge.solve(html)
        assertNotNull(solution)
        assertEquals(79746, solution!!.nonce)

        // 独立复核：摘要的 [6]==0xB0、[7]==0x0B 才符合该常量对应的页面条件。
        val digest = java.security.MessageDigest.getInstance("SHA-1")
            .digest((constant + solution.nonce).toByteArray(Charsets.US_ASCII))
        assertEquals(0xB0.toByte(), digest[6])
        assertEquals(0x0B.toByte(), digest[7])
    }

    @Test
    fun nonceSearchIsBounded() {
        val started = System.currentTimeMillis()
        val nonce = ZlibChallenge.solveNonce("549E967E36C0ACE8E4A01AE0655AA2BE116C727B")
        val elapsed = System.currentTimeMillis() - started

        assertEquals(348329, nonce)
        // 上限 5,000,000 次；实测 34.8 万次 < 1.5s（CI/低配机器留余量）
        assertTrue("nonce search took ${elapsed}ms", elapsed < 5000)
    }
}
