package com.pdfmanager.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiFileCacheTest {

    private var clock = 1_000_000L
    private val store = MemoryAiStore()
    private val cache = AiFileCache(store) { clock }
    private val uri = "https://generativelanguage.googleapis.com/v1beta/files/abc"

    @Test fun ttlIsFortySevenHours() {
        assertEquals(47L * 3600 * 1000, AiFileCache.TTL_MS)
    }

    @Test fun savedLinkComesBack() {
        cache.put("k", uri)
        assertEquals(uri, cache.get("k"))
        assertNull(cache.get("other"))
    }

    @Test fun linkIsGoodUntilTheLastMillisecondBeforeFortySevenHours() {
        cache.put("k", uri)
        clock += AiFileCache.TTL_MS - 1
        assertEquals(uri, cache.get("k"))
        clock += 1
        assertNull(cache.get("k"))
    }

    @Test fun expiredLinkIsDropped() {
        cache.put("k", uri)
        clock += AiFileCache.TTL_MS + 5
        assertNull(cache.get("k"))
        assertNull(store.getString(AiFileCache.STORE_KEY))
    }

    @Test fun putAgainRestartsTheClockAndReplaces() {
        cache.put("k", uri)
        clock += AiFileCache.TTL_MS - 10
        cache.put("k", "$uri-2")
        clock += 100
        assertEquals("$uri-2", cache.get("k"))
    }

    @Test fun removeForgetsOnlyThatLink() {
        cache.put("a", uri); cache.put("b", "$uri-b")
        cache.remove("a")
        assertNull(cache.get("a"))
        assertEquals("$uri-b", cache.get("b"))
    }

    @Test fun onlyTheNewestEntriesStay() {
        for (i in 1..AiFileCache.MAX_ENTRIES + 3) cache.put("k$i", "$uri$i")
        assertNull(cache.get("k1"))
        assertNull(cache.get("k3"))
        assertEquals("$uri${AiFileCache.MAX_ENTRIES + 3}", cache.get("k${AiFileCache.MAX_ENTRIES + 3}"))
    }

    @Test fun expiredEntriesArePrunedOnPut() {
        cache.put("old", uri)
        clock += AiFileCache.TTL_MS + 1
        cache.put("new", "$uri-n")
        assertFalse(store.getString(AiFileCache.STORE_KEY)!!.contains("old|"))
    }

    @Test fun badLinksAreNotStored() {
        cache.put("k", "")
        cache.put("k", "a|b")
        cache.put("k", "a\nb")
        assertNull(cache.get("k"))
    }

    @Test fun anotherApiKeyIsAnotherEntryAndTheKeyIsNeverStored() {
        val a = AiFileCache.key("f_1", "secret-key-AAAA")
        val b = AiFileCache.key("f_1", "secret-key-BBBB")
        assertNotEquals(a, b)
        assertEquals(a, AiFileCache.key("f_1", "secret-key-AAAA"))
        cache.put(a, uri)
        assertFalse(store.getString(AiFileCache.STORE_KEY)!!.contains("secret"))
        assertNull(cache.get(b))
    }

    @Test fun clearAiDataRemovesTheLinks() {
        cache.put("k", uri)
        AiPrefs(store).clear()
        assertNull(cache.get("k"))
    }

    @Test fun linksSurviveANewCacheObjectOnTheSameStore() {
        cache.put("k", uri)
        assertEquals(uri, AiFileCache(store) { clock }.get("k"))
        assertTrue(store.map.containsKey(AiFileCache.STORE_KEY))
    }
}
