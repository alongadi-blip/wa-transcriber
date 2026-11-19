package com.alon.transcriber

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

class TranscribeException(message: String) : Exception(message)

data class Transcript(val text: String, val durationSec: Double?, val language: String?)

private class HttpError(val code: Int, val apiMessage: String?) : Exception(apiMessage)

object Transcriber {
    private const val MAX_BYTES = 25L * 1024 * 1024
    // Base64 inflates by 4/3 and Gemini caps the whole request at 20 MB
    private const val GEMINI_MAX_INLINE = 14L * 1024 * 1024

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    suspend fun transcribe(context: Context, uri: Uri, prefs: Prefs): Transcript =
        withContext(Dispatchers.IO) {
            val engine = prefs.engine
            val key = prefs.keyFor(engine)
            if (key.isBlank()) throw TranscribeException("צריך להגדיר מפתח API בהגדרות")
            val language = prefs.language.ifBlank { null }

            val file = copyToCache(context, uri)
            try {
                if (file.length() == 0L) throw TranscribeException("הקובץ ששותף ריק")
                val duration = durationOf(file)
                val result = when (engine) {
                    Prefs.ENGINE_GEMINI -> gemini(file, key, language)
                    else -> groq(file, key, language)
                }
                result.copy(durationSec = result.durationSec ?: duration)
            } catch (e: TranscribeException) {
                throw e
            } catch (e: java.io.IOException) {
                throw TranscribeException("אין חיבור לאינטרנט או שהשרת לא הגיב")
            } finally {
                file.delete()
            }
        }

    /* ---------- shared file handling ---------- */

    /** Copies the shared content to a cache file with an extension the engines accept. */
    private fun copyToCache(context: Context, uri: Uri): File {
        // Only accept content:// (what WhatsApp and other apps share). A file:// URI could
        // point at this app's own private files, e.g. the prefs holding the API key.
        if (uri.scheme != "content") throw TranscribeException("סוג הקובץ ששותף לא נתמך")
        val cr = context.contentResolver
        val mime = cr.getType(uri)?.lowercase() ?: ""
        var displayName = ""
        runCatching {
            cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) displayName = c.getString(0) ?: ""
            }
        }
        val nameExt = displayName.substringAfterLast('.', "").lowercase()
        val ext = when {
            nameExt in setOf("mp3", "m4a", "mp4", "wav", "webm", "flac", "ogg", "mpeg", "mpga") -> nameExt
            nameExt == "opus" || nameExt == "oga" -> "ogg"
            "mpeg" in mime || "mp3" in mime -> "mp3"
            "mp4" in mime || "m4a" in mime || "aac" in mime -> "m4a"
            "wav" in mime -> "wav"
            "webm" in mime -> "webm"
            "flac" in mime -> "flac"
            else -> "ogg" // WhatsApp voice notes are Opus in an Ogg container
        }
        val out = File(context.cacheDir, "shared_audio_${System.nanoTime()}.$ext")
        cr.openInputStream(uri)?.use { input ->
            out.outputStream().use { output ->
                // Copy with a size cap so a huge shared file can't fill the device storage
                val buf = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > MAX_BYTES) {
                        output.close(); out.delete()
                        throw TranscribeException("הקובץ גדול מדי (מעל 25MB)")
                    }
                    output.write(buf, 0, n)
                }
            }
        } ?: throw TranscribeException("לא הצלחתי לקרוא את הקובץ ששותף")
        return out
    }

    private fun durationOf(file: File): Double? = runCatching {
        MediaMetadataRetriever().run {
            try {
                setDataSource(file.path)
                extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toDoubleOrNull()?.div(1000)
            } finally { release() }
        }
    }.getOrNull()?.takeIf { it > 0 }

    private fun mimeFor(file: File) = when (file.extension) {
        "mp3", "mpeg", "mpga" -> "audio/mpeg"
        "m4a" -> "audio/m4a"
        "mp4" -> "video/mp4"
        else -> "audio/${file.extension}"
    }

    private fun apiError(body: String): String? = runCatching {
        JSONObject(body).getJSONObject("error").getString("message")
    }.getOrNull()

    /* ---------- Groq Whisper ---------- */

    private fun groq(file: File, key: String, language: String?): Transcript {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", file.name, file.asRequestBody(mimeFor(file).toMediaType()))
            .addFormDataPart("model", "whisper-large-v3")
            .addFormDataPart("response_format", "verbose_json") // adds duration + language
            .addFormDataPart("temperature", "0")
            .apply { if (language != null) addFormDataPart("language", language) }
            .build()
        val req = Request.Builder().url("https://api.groq.com/openai/v1/audio/transcriptions")
            .header("Authorization", "Bearer ${key.trim()}")
            .post(body).build()

        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                val msg = apiError(text)
                throw TranscribeException(
                    when (resp.code) {
                        401 -> "מפתח ה-API של Groq לא תקין. בדוק אותו בהגדרות."
                        413 -> "הקובץ גדול מדי עבור Groq"
                        429 -> "עברת את מגבלת השימוש החינמית של Groq. נסה שוב מאוחר יותר."
                        else -> "שגיאה מ-Groq (${resp.code})" + (msg?.let { ": $it" } ?: "")
                    }
                )
            }
            val json = JSONObject(text)
            return Transcript(
                text = json.optString("text").trim()
                    .ifEmpty { throw TranscribeException("לא זוהה דיבור בהודעה") },
                durationSec = json.optDouble("duration").takeIf { !it.isNaN() && it > 0 },
                language = json.optString("language").takeIf { it.isNotBlank() }
            )
        }
    }

    /* ---------- Google Gemini ---------- */

    private const val GEMINI_URL = "https://generativelanguage.googleapis.com/v1beta/interactions"
    private const val GEMINI_PROMPT =
        "Transcribe this voice message word for word in its original language " +
        "(usually Hebrew). Add natural punctuation. Output only the transcript, " +
        "with no introduction, comments, timestamps or speaker labels."

    private fun gemini(file: File, key: String, language: String?): Transcript {
        if (file.length() > GEMINI_MAX_INLINE) throw TranscribeException("הקובץ גדול מדי עבור Gemini (מעל 14MB)")
        val mime = if (file.extension == "mpeg") "audio/mpeg" else mimeFor(file).replace("video/", "audio/")
        val audio = JSONObject()
            .put("type", "audio")
            .put("data", Base64.encodeToString(file.readBytes(), Base64.NO_WRAP))
            .put("mime_type", mime)

        // 1st choice: the dedicated speech-to-text model
        val transcribeBody = JSONObject()
            .put("model", "gemini-3.5-transcribe")
            .put("input", JSONArray().put(audio))
        if (language != null) {
            val code = if (language == "he") "he-IL" else language
            transcribeBody.put(
                "generation_config", JSONObject().put(
                    "transcription_config",
                    JSONObject().put("language_codes", JSONArray().put(code))
                )
            )
        }
        val text = try {
            geminiCall(key, transcribeBody)
        } catch (e: HttpError) {
            if (e.code != 400 && e.code != 404) throw geminiFriendly(e)
            if (e.apiMessage?.contains("API key", ignoreCase = true) == true) throw geminiFriendly(e)
            // Fallback: general Flash model with a transcription instruction
            val flashBody = JSONObject()
                .put("model", "gemini-3.8-flash")
                .put("input", JSONArray()
                    .put(JSONObject().put("type", "text").put("text", GEMINI_PROMPT))
                    .put(audio))
            try { geminiCall(key, flashBody) } catch (e2: HttpError) { throw geminiFriendly(e2) }
        }
        return Transcript(
            text = text.trim().ifEmpty { throw TranscribeException("לא זוהה דיבור בהודעה") },
            durationSec = null,
            language = language
        )
    }

    private fun geminiFriendly(e: HttpError) = TranscribeException(
        when {
            e.apiMessage?.contains("API key", true) == true || e.code == 401 || e.code == 403 ->
                "מפתח ה-API של Gemini לא תקין. בדוק אותו בהגדרות."
            e.code == 429 -> "עברת את מגבלת השימוש החינמית של Gemini. נסה שוב מאוחר יותר."
            else -> "שגיאה מ-Gemini (${e.code})" + (e.apiMessage?.let { ": $it" } ?: "")
        }
    )

    private fun geminiCall(key: String, body: JSONObject): String {
        val req = Request.Builder().url(GEMINI_URL)
            .header("x-goog-api-key", key.trim())
            .header("Api-Revision", "2026-05-20")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(req).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw HttpError(resp.code, apiError(raw))
            return extractText(JSONObject(raw))
                ?: throw TranscribeException("Gemini החזיר תשובה ללא טקסט")
        }
    }

    /** Reads the transcript from an Interactions response, tolerating shape variations. */
    private fun extractText(json: JSONObject): String? {
        json.optString("output_text").takeIf { it.isNotBlank() }?.let { return it }
        val parts = mutableListOf<String>()
        fun collectContent(arr: JSONArray?) {
            arr ?: return
            for (i in 0 until arr.length()) {
                val c = arr.optJSONObject(i) ?: continue
                c.optString("text").takeIf { it.isNotBlank() }?.let { parts += it }
            }
        }
        json.optJSONArray("steps")?.let { steps ->
            for (i in 0 until steps.length()) collectContent(steps.optJSONObject(i)?.optJSONArray("content"))
        }
        if (parts.isEmpty()) collectContent(json.optJSONArray("outputs"))
        if (parts.isEmpty()) json.optJSONArray("candidates")?.optJSONObject(0)
            ?.optJSONObject("content")?.optJSONArray("parts")?.let { collectContent(it) }
        return parts.joinToString("\n").ifBlank { null }
    }
}
