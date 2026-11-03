package com.pdfmanager.app.data

import android.content.Context

/** Where [AiPrefs] keeps its values. Plain interface so the logic runs in unit tests without Android. */
interface AiStore {
    fun getString(key: String): String?
    fun getBoolean(key: String, default: Boolean): Boolean
    fun putString(key: String, value: String)
    fun putBoolean(key: String, value: Boolean)
    fun remove(key: String)
    fun clear()
}

/** The private preferences file "ai_settings". It is excluded from Android backup and from [BackupSettings]. */
class SharedPrefsAiStore(context: Context) : AiStore {
    private val prefs = context.applicationContext.getSharedPreferences(AiPrefs.FILE, Context.MODE_PRIVATE)
    override fun getString(key: String): String? = prefs.getString(key, null)
    override fun getBoolean(key: String, default: Boolean): Boolean = prefs.getBoolean(key, default)
    override fun putString(key: String, value: String) { prefs.edit().putString(key, value).apply() }
    override fun putBoolean(key: String, value: Boolean) { prefs.edit().putBoolean(key, value).apply() }
    override fun remove(key: String) { prefs.edit().remove(key).apply() }
    override fun clear() { prefs.edit().clear().commit() }
}

/**
 * Settings of the Ask AI feature: API key, model, answer language and length, first send consent and
 * the "panel opened" flag. Getters and setters only. The key is never logged or put in a URL.
 */
class AiPrefs(private val store: AiStore) {

    companion object {
        const val FILE = "ai_settings"
        const val KEY = "api_key"
        const val MODEL = "model"
        const val CUSTOM_MODEL = "custom_model"
        const val LANGUAGE = "language"
        const val LENGTH = "length"
        const val CONSENTED = "consented"
        const val SEEN = "seen"

        const val LANG_AUTO = "auto"
        const val LANG_EN = "en"
        const val LANG_BN = "bn"
        val languages = listOf(LANG_AUTO, LANG_EN, LANG_BN)

        const val LEN_SHORT = "short"
        const val LEN_NORMAL = "normal"
        const val LEN_LONG = "long"
        val lengths = listOf(LEN_SHORT, LEN_NORMAL, LEN_LONG)

        /**
         * Address of the hosted privacy.html. Settings, AI, Privacy opens it and the row is hidden while this is empty.
         * Paste the public address of your privacy page here, for example the GitHub Pages link of this repository.
         */
        const val PRIVACY_URL = ""

        /** How many characters of the key Settings shows. */
        const val HINT_CHARS = 4

        fun of(context: Context) = AiPrefs(SharedPrefsAiStore(context))
    }

    var apiKey: String
        get() = store.getString(KEY).orEmpty()
        set(value) {
            val clean = value.trim()
            if (clean.isEmpty()) store.remove(KEY) else store.putString(KEY, clean)
        }

    val hasKey: Boolean get() = apiKey.isNotEmpty()

    /** The last characters of the key, for the Settings row. Null when no key is saved. */
    fun keyHint(): String? {
        val key = apiKey
        if (key.isEmpty()) return null
        return "..." + key.takeLast(HINT_CHARS)
    }

    /** A stored model key: one of [AiModels.list] or [AiModels.CUSTOM]. Unknown values fall back to the default. */
    var model: String
        get() {
            val stored = store.getString(MODEL)
            return if (stored == AiModels.CUSTOM || AiModels.byKey(stored) != null) stored!! else AiModels.DEFAULT.key
        }
        set(value) { store.putString(MODEL, value) }

    var customModel: String
        get() = store.getString(CUSTOM_MODEL).orEmpty()
        set(value) {
            val clean = value.trim()
            if (clean.isEmpty()) store.remove(CUSTOM_MODEL) else store.putString(CUSTOM_MODEL, clean)
        }

    /** The model id to send. The custom id wins when Model is Custom and the field is filled in. */
    fun modelId(): String {
        if (model == AiModels.CUSTOM) {
            val custom = customModel
            if (custom.isNotEmpty()) return custom
            return AiModels.DEFAULT.id
        }
        return (AiModels.byKey(model) ?: AiModels.DEFAULT).id
    }

    var language: String
        get() = store.getString(LANGUAGE)?.takeIf { it in languages } ?: LANG_AUTO
        set(value) { store.putString(LANGUAGE, if (value in languages) value else LANG_AUTO) }

    var length: String
        get() = store.getString(LENGTH)?.takeIf { it in lengths } ?: LEN_NORMAL
        set(value) { store.putString(LENGTH, if (value in lengths) value else LEN_NORMAL) }

    var consented: Boolean
        get() = store.getBoolean(CONSENTED, false)
        set(value) { store.putBoolean(CONSENTED, value) }

    var seen: Boolean
        get() = store.getBoolean(SEEN, false)
        set(value) { store.putBoolean(SEEN, value) }

    /** Clear AI data: the key, the model choices and every flag go back to their first values. */
    fun clear() = store.clear()
}
