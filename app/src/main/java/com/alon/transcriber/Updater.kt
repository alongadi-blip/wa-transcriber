package com.alon.transcriber

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/** Self-update from the latest GitHub Release of the public repo. */
object Updater {
    private const val LATEST = "https://api.github.com/repos/alongadi-blip/wa-transcriber/releases/latest"

    data class Release(val versionCode: Long, val apkUrl: String)

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    fun currentVersion(context: Context): Long =
        PackageInfoCompat.getLongVersionCode(
            context.packageManager.getPackageInfo(context.packageName, 0)
        )

    /** Returns the latest release if it is newer than the installed version, else null. */
    suspend fun check(context: Context): Release? = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder().url(LATEST)
                .header("Accept", "application/vnd.github+json").build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val json = JSONObject(resp.body?.string().orEmpty())
                // Tags look like v1.0.<versionCode>
                val code = json.optString("tag_name").substringAfterLast('.').toLongOrNull()
                    ?: return@use null
                val assets = json.optJSONArray("assets") ?: return@use null
                val apkUrl = (0 until assets.length()).map { assets.getJSONObject(it) }
                    .firstOrNull { it.optString("name").endsWith(".apk") }
                    ?.optString("browser_download_url") ?: return@use null
                if (code > currentVersion(context)) Release(code, apkUrl) else null
            }
        }.getOrNull()
    }

    /** The user must allow this app to install apps once (Android setting). */
    fun canInstall(context: Context) = context.packageManager.canRequestPackageInstalls()

    fun openInstallPermissionSettings(context: Context) {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    suspend fun downloadAndInstall(context: Context, release: Release) = withContext(Dispatchers.IO) {
        val file = File(context.cacheDir, "update.apk")
        http.newCall(Request.Builder().url(release.apkUrl).build()).execute().use { resp ->
            if (!resp.isSuccessful) throw TranscribeException("הורדת העדכון נכשלה (${resp.code})")
            file.outputStream().use { out -> resp.body!!.byteStream().copyTo(out) }
        }

        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            // Android 12+: a self-update can install without a confirmation screen
            if (Build.VERSION.SDK_INT >= 31) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("update.apk", 0, file.length()).use { out ->
                file.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val pending = PendingIntent.getBroadcast(
                context, sessionId, Intent(context, InstallReceiver::class.java), flags
            )
            session.commit(pending.intentSender)
        }
        file.delete()
    }
}

class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // System wants a confirmation tap
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            PackageInstaller.STATUS_SUCCESS -> Unit // the app restarts on the new version
            else -> Toast.makeText(
                context,
                "העדכון נכשל: " + (intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: ""),
                Toast.LENGTH_LONG
            ).show()
        }
    }
}
