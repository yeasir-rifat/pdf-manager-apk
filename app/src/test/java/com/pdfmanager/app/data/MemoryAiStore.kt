package com.pdfmanager.app.data

/** An in-memory [AiStore] for unit tests. */
class MemoryAiStore : AiStore {
    val map = HashMap<String, Any>()
    override fun getString(key: String): String? = map[key] as? String
    override fun getBoolean(key: String, default: Boolean): Boolean = map[key] as? Boolean ?: default
    override fun putString(key: String, value: String) { map[key] = value }
    override fun putBoolean(key: String, value: Boolean) { map[key] = value }
    override fun remove(key: String) { map.remove(key) }
    override fun clear() { map.clear() }
}
