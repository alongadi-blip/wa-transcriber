package com.alon.transcriber

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

class TranscribeException(message: String) : Exception(message)

data class Transcript(val text: String, val durationSec: Double?, val language: String?)

object GroqClient {
    private const val URL = "https://api.groq.com/openai/v1/audio/transcriptions"
    private const val MODEL = "whisper-large-v3"
    private const val MAX_BYTES = 25L * 1024 * 1024

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    /** Copies the shared content to a cache file with an extension Groq accepts. */
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
        val out = File(context.cacheDir, "shared_audio.$ext")
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

    suspend fun transcribe(context: Context, uri: Uri, apiKey: String, language: String?): Transcript =
        withContext(Dispatchers.IO) {
            val file = copyToCache(context, uri)
            try {
                if (file.length() == 0L) throw TranscribeException("הקובץ ששותף ריק")
                if (file.length() > MAX_BYTES) throw TranscribeException("הקובץ גדול מדי (מעל 25MB)")

                val mediaType = when (file.extension) {
                    "mp3" -> "audio/mpeg"
                    "m4a" -> "audio/mp4"
                    "mp4" -> "video/mp4"
                    else -> "audio/${file.extension}"
                }.toMediaType()

                val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                    .addFormDataPart("file", file.name, file.asRequestBody(mediaType))
                    .addFormDataPart("model", MODEL)
                    .addFormDataPart("response_format", "verbose_json") // adds duration + language
                    .addFormDataPart("temperature", "0")
                    .apply { if (!language.isNullOrBlank()) addFormDataPart("language", language) }
                    .build()

                val req = Request.Builder().url(URL)
                    .header("Authorization", "Bearer ${apiKey.trim()}")
                    .post(body).build()

                http.newCall(req).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        val apiMsg = runCatching {
                            JSONObject(text).getJSONObject("error").getString("message")
                        }.getOrNull()
                        throw TranscribeException(
                            when (resp.code) {
                                401 -> "מפתח ה-API של Groq לא תקין. בדוק אותו בהגדרות."
                                413 -> "הקובץ גדול מדי עבור Groq"
                                429 -> "עברת את מגבלת השימוש החינמית של Groq. נסה שוב מאוחר יותר."
                                else -> "שגיאה מ-Groq (${resp.code})" + (apiMsg?.let { ": $it" } ?: "")
                            }
                        )
                    }
                    val json = JSONObject(text)
                    val body = json.optString("text").trim()
                        .ifEmpty { throw TranscribeException("לא זוהה דיבור בהודעה") }
                    Transcript(
                        text = body,
                        durationSec = json.optDouble("duration").takeIf { !it.isNaN() && it > 0 },
                        language = json.optString("language").takeIf { it.isNotBlank() }
                    )
                }
            } catch (e: TranscribeException) {
                throw e
            } catch (e: java.io.IOException) {
                throw TranscribeException("אין חיבור לאינטרנט או שהשרת לא הגיב")
            } finally {
                file.delete()
            }
        }
}
