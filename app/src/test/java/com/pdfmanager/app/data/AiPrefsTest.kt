package com.pdfmanager.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiPrefsTest {

    private class MemoryStore : AiStore {
        val map = mutableMapOf<String, Any>()
        override fun getString(key: String) = map[key] as? String
        override fun getBoolean(key: String, default: Boolean) = map[key] as? Boolean ?: default
        override fun putString(key: String, value: String) { map[key] = value }
        override fun putBoolean(key: String, value: Boolean) { map[key] = value }
        override fun remove(key: String) { map.remove(key) }
        override fun clear() = map.clear()
    }

    private val store = MemoryStore()
    private val prefs = AiPrefs(store)

    @Test fun defaults() {
        assertEquals("", prefs.apiKey)
        assertFalse(prefs.hasKey)
        assertNull(prefs.keyHint())
        assertEquals(AiModels.DEFAULT.key, prefs.model)
        assertEquals(AiModels.DEFAULT.id, prefs.modelId())
        assertEquals("", prefs.customModel)
        assertEquals(AiPrefs.LANG_AUTO, prefs.language)
        assertEquals(AiPrefs.LEN_NORMAL, prefs.length)
        assertFalse(prefs.consented)
        assertFalse(prefs.seen)
    }

    @Test fun keyIsTrimmedAndShowsOnlyLastFour() {
        prefs.apiKey = "  AIzaSyABCDEFGH1234a1b2 \n"
        assertEquals("AIzaSyABCDEFGH1234a1b2", prefs.apiKey)
        assertTrue(prefs.hasKey)
        assertEquals("...a1b2", prefs.keyHint())
    }

    @Test fun shortKeyNeverShowsMoreThanItHas() {
        prefs.apiKey = "abc"
        assertEquals("...abc", prefs.keyHint())
    }

    @Test fun blankKeyRemovesIt() {
        prefs.apiKey = "abcdef"
        prefs.apiKey = "   "
        assertFalse(prefs.hasKey)
        assertFalse(store.map.containsKey(AiPrefs.KEY))
    }

    @Test fun listedModelsMapToTheirIds() {
        AiModels.list.forEach {
            prefs.model = it.key
            assertEquals(it.id, prefs.modelId())
        }
    }

    @Test fun customModelWinsOnlyWhenModelIsCustom() {
        prefs.customModel = "  my-model-1 "
        prefs.model = "pro"
        assertEquals(AiModels.byKey("pro")!!.id, prefs.modelId())
        prefs.model = AiModels.CUSTOM
        assertEquals("my-model-1", prefs.modelId())
    }

    @Test fun customWithEmptyFieldFallsBackToDefault() {
        prefs.model = AiModels.CUSTOM
        assertEquals(AiModels.DEFAULT.id, prefs.modelId())
    }

    @Test fun unknownStoredValuesFallBack() {
        store.putString(AiPrefs.MODEL, "gone")
        store.putString(AiPrefs.LANGUAGE, "fr")
        store.putString(AiPrefs.LENGTH, "huge")
        assertEquals(AiModels.DEFAULT.key, prefs.model)
        assertEquals(AiPrefs.LANG_AUTO, prefs.language)
        assertEquals(AiPrefs.LEN_NORMAL, prefs.length)
    }

    @Test fun valuesRoundTrip() {
        prefs.language = AiPrefs.LANG_BN
        prefs.length = AiPrefs.LEN_LONG
        prefs.consented = true
        prefs.seen = true
        val again = AiPrefs(store)
        assertEquals(AiPrefs.LANG_BN, again.language)
        assertEquals(AiPrefs.LEN_LONG, again.length)
        assertTrue(again.consented)
        assertTrue(again.seen)
    }

    @Test fun clearRemovesEverything() {
        prefs.apiKey = "secret-key-1234"
        prefs.model = AiModels.CUSTOM
        prefs.customModel = "x"
        prefs.language = AiPrefs.LANG_EN
        prefs.length = AiPrefs.LEN_SHORT
        prefs.consented = true
        prefs.seen = true
        prefs.clear()
        assertTrue(store.map.isEmpty())
        assertFalse(prefs.hasKey)
        assertFalse(prefs.consented)
        assertFalse(prefs.seen)
        assertEquals(AiModels.DEFAULT.id, prefs.modelId())
    }
}
