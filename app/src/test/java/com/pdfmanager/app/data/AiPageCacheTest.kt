package com.pdfmanager.app.data

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiPageCacheTest {

    private val dir: File = Files.createTempDirectory("ai-cache").toFile()

    @After fun clean() { AiPageCache.clear(); dir.deleteRecursively() }

    private fun file(name: String, bytes: Int = 3) = File(dir, name).apply { writeBytes(ByteArray(bytes) { 1 }) }

    private fun put(uri: String, page: Int, f: File = file("$uri-$page.pdf")) =
        AiPageCache.put(AiPageCache.key(uri, page), f, f.length(), AiScope.Page(page), 40)

    @Test fun closingACachedSourceKeepsTheFile() {
        val f = file("a.pdf")
        put("u", 12, f).close()
        assertTrue(f.exists())
    }

    @Test fun samePageIsFoundAgainAndOtherPagesAreNot() {
        put("u", 12)
        val again = AiPageCache.get(AiPageCache.key("u", 12))
        assertNotNull(again)
        assertEquals(AiScope.Page(12), again!!.scope)
        assertEquals(40, again.totalPages)
        assertEquals(1, again.pages)
        assertNull(AiPageCache.get(AiPageCache.key("u", 13)))
        assertNull(AiPageCache.get(AiPageCache.key("other", 12)))
    }

    @Test fun onlyTheNewestThreeAreKeptAndTheOldestFileIsDeleted() {
        val first = file("first.pdf")
        put("u", 1, first)
        put("u", 2); put("u", 3); put("u", 4)
        assertEquals(3, AiPageCache.size())
        assertFalse(first.exists())
        assertNull(AiPageCache.get(AiPageCache.key("u", 1)))
        assertNotNull(AiPageCache.get(AiPageCache.key("u", 4)))
    }

    @Test fun aFileThatVanishedOrChangedIsNotReused() {
        val f = file("gone.pdf")
        put("u", 5, f)
        f.delete()
        assertNull(AiPageCache.get(AiPageCache.key("u", 5)))
        val g = file("changed.pdf")
        put("u", 6, g)
        g.writeBytes(ByteArray(10))
        assertNull(AiPageCache.get(AiPageCache.key("u", 6)))
        assertFalse(g.exists())
    }

    @Test fun clearDeletesEveryKeptFile() {
        val a = file("x.pdf"); val b = file("y.pdf")
        put("u", 1, a); put("u", 2, b)
        AiPageCache.clear()
        assertEquals(0, AiPageCache.size())
        assertFalse(a.exists()); assertFalse(b.exists())
    }

    @Test fun uncachedSourceStillDeletesItsFile() {
        val f = file("plain.pdf")
        AiPdfSource(f, 3, 1, AiScope.Page(2), 10).close()
        assertFalse(f.exists())
    }
}
