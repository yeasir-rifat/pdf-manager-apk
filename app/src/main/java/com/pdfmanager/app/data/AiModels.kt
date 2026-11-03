package com.pdfmanager.app.data

/**
 * The models offered in Settings. Model names and free tier access change often, so this is the only
 * place that lists them. The ids are Google's "latest" aliases, which follow the current model of each
 * kind. Anything else goes through the Custom model field.
 */
object AiModels {
    /** [key] is what is stored and shown through a string; [id] is what is sent to Gemini. */
    class Model(val key: String, val id: String)

    /** Stored value of the Model choice when the person types their own model id. */
    const val CUSTOM = "custom"

    val list: List<Model> = listOf(
        Model("flash", "gemini-flash-latest"),
        Model("lite", "gemini-flash-lite-latest"),
        Model("pro", "gemini-pro-latest"),
    )

    val DEFAULT: Model = list[0]

    fun byKey(key: String?): Model? = list.firstOrNull { it.key == key }
}
