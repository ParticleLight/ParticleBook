package app.particlebook.android.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsRedactorTest {

    @Test
    fun redactsUrlQueryTokensAndKeepsOtherParameters() {
        val input = "GET https://api.example.com/v1/books?token=abc123&page=2&access_token=xyz"
        assertEquals(
            "GET https://api.example.com/v1/books?token=<redacted>&page=2&access_token=<redacted>",
            DiagnosticsRedactor.redact(input),
        )
    }

    @Test
    fun redactsCookieAndSetCookieHeaders() {
        val input = "Cookie: session=abc; theme=dark\nSet-Cookie: token=xyz; Path=/"
        assertEquals(
            "Cookie: <redacted>\nSet-Cookie: <redacted>",
            DiagnosticsRedactor.redact(input),
        )
    }

    @Test
    fun redactsAuthorizationHeader() {
        val input = "Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.payload.signature"
        assertEquals("Authorization: <redacted>", DiagnosticsRedactor.redact(input))
    }

    @Test
    fun redactsEmailAddresses() {
        assertEquals(
            "contact <email> for access",
            DiagnosticsRedactor.redact("contact user.name+tag@example.co.uk for access"),
        )
    }

    @Test
    fun redactsAndroidDataAndStoragePaths() {
        assertEquals(
            "open <app-data> now",
            DiagnosticsRedactor.redact("open /data/user/0/app.particlebook/files/book.epub now"),
        )
        assertEquals(
            "<storage>",
            DiagnosticsRedactor.redact("/storage/emulated/0/Download/book.epub"),
        )
        assertEquals(
            "<storage>",
            DiagnosticsRedactor.redact("/sdcard/Books/book.epub"),
        )
    }

    @Test
    fun redactsWindowsUserPaths() {
        assertEquals(
            "open <user-path> now",
            DiagnosticsRedactor.redact("""open C:\Users\Alice\Books\book.epub now"""),
        )
    }

    @Test
    fun doesNotRedactNormalUrlPathSegments() {
        val input = "https://example.com/data/books"
        assertEquals(input, DiagnosticsRedactor.redact(input))
    }

    @Test
    fun redactsJsonAndKeyValueSecrets() {
        assertEquals(
            """{"token":"<redacted>","page":2}""",
            DiagnosticsRedactor.redact("""{"token":"abc","page":2}"""),
        )
        assertEquals(
            "password=<redacted> client_secret=<redacted>",
            DiagnosticsRedactor.redact("password=hunter2 client_secret=topsecret"),
        )
    }

    @Test
    fun redactionIsIdempotent() {
        val once = DiagnosticsRedactor.redact(
            "token=abc user@example.com /data/user/0/app/x\nCookie: a=b",
        )
        assertEquals(once, DiagnosticsRedactor.redact(once))
        assertFalse(once.contains("abc"))
        assertFalse(once.contains("user@example.com"))
    }
}
