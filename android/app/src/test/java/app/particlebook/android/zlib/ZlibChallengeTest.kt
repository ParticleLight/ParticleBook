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
    fun nonceSearchIsBounded() {
        val started = System.currentTimeMillis()
        val nonce = ZlibChallenge.solveNonce("549E967E36C0ACE8E4A01AE0655AA2BE116C727B")
        val elapsed = System.currentTimeMillis() - started

        assertEquals(348329, nonce)
        // 上限 5,000,000 次；实测 34.8 万次 < 1.5s（CI/低配机器留余量）
        assertTrue("nonce search took ${elapsed}ms", elapsed < 5000)
    }
}
