package com.alon.transcriber

import android.content.Context

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var apiKey: String
        get() = sp.getString("groq_key", "") ?: ""
        set(v) = sp.edit().putString("groq_key", v.trim()).apply()

    /** "he" for Hebrew, "" for auto-detect. */
    var language: String
        get() = sp.getString("language", "he") ?: "he"
        set(v) = sp.edit().putString("language", v).apply()
}
