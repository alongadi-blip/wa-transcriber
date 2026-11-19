package com.alon.transcriber

import android.content.Context

class Prefs(context: Context) {
    companion object {
        const val ENGINE_GEMINI = "gemini"
        const val ENGINE_GROQ = "groq"
    }

    private val sp = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var groqKey: String
        get() = sp.getString("groq_key", "") ?: ""
        set(v) = sp.edit().putString("groq_key", v.trim()).apply()

    var geminiKey: String
        get() = sp.getString("gemini_key", "") ?: ""
        set(v) = sp.edit().putString("gemini_key", v.trim()).apply()

    /** Gemini is the default (better Hebrew); Groq stays available as an option. */
    var engine: String
        get() = sp.getString("engine", null) ?: ENGINE_GEMINI
        set(v) = sp.edit().putString("engine", v).apply()

    fun keyFor(engine: String) = if (engine == ENGINE_GEMINI) geminiKey else groqKey

    val activeKey: String get() = keyFor(engine)

    /** "he" for Hebrew, "" for auto-detect. */
    var language: String
        get() = sp.getString("language", "he") ?: "he"
        set(v) = sp.edit().putString("language", v).apply()
}
