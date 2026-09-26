package com.cetotos.polydroid2

import android.app.ActivityManager
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Collects app/client logs and saves them as a single text file into the
 * device's public Download folder (/storage/emulated/0/Download/) via
 * MediaStore — no storage permission needed on API 29+.
 */
object LogReporter {
    private const val TAG = "PolyDroid2"
    private const val GAME_LOG_TAIL = 5000
    private const val LOGCAT_TAIL = 1500

    enum class Client(val label: String, val logName: String) {
        VORTEX("Vortex", "vortex.log");
    }

    fun defaultClient(ctx: Context): Client = Client.VORTEX

    fun promptAndSend(
        ctx: Context,
        note: String = "",
        onProgress: (String) -> Unit,
        onDone: (success: Boolean, msg: String) -> Unit,
    ) {
        MaterialAlertDialogBuilder(ctx)
            .setTitle("Save logs")
            .setMessage("Collects app, client, box64 session and logcat logs into a single text file in your Download folder.")
            .setPositiveButton("Save") { _, _ -> send(ctx, Client.VORTEX, note, onProgress, onDone) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    fun send(
        ctx: Context,
        client: Client,
        note: String,
        onProgress: (String) -> Unit,
        onDone: (success: Boolean, msg: String) -> Unit,
    ) {
        Thread {
            try {
                onProgress("Reading logs…")
                val report = buildString {
                    appendLine("==== VortexDroid log bundle ====")
                    appendLine("Saved: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
                    appendLine()
                    appendLine("===== report =====")
                    appendLine(buildReport(ctx, client, note))
                    appendLine()
                    appendLine("===== ${client.logName} (tail $GAME_LOG_TAIL lines) =====")
                    appendLine(readGameLog(ctx, client))
                    appendLine()
                    appendLine("===== session.log (box64, tail $GAME_LOG_TAIL lines) =====")
                    appendLine(readSessionLog(ctx))
                    appendLine()
                    appendLine("===== logcat (tail $LOGCAT_TAIL lines) =====")
                    appendLine(readLogcat())
                }.toByteArray(Charsets.UTF_8)

                onProgress("Saving…")
                val name = "vortexdroid-log-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())}.txt"
                val uri = saveToDownloads(ctx, name, report)
                if (uri != null) {
                    Log.i(TAG, "log bundle saved to Download/$name ($uri)")
                    onDone(true, "Saved to Download/$name")
                } else {
                    onDone(false, "Failed to write to Download folder")
                }
            } catch (e: Exception) {
                Log.e(TAG, "failed to save logs: ${e.message}", e)
                onDone(false, "Failed with: ${e.message}")
            }
        }.start()
    }

    /** API 29+: MediaStore.Downloads needs no permission for own contributions. */
    private fun saveToDownloads(ctx: Context, name: String, bytes: ByteArray): Uri? {
        return try {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = ctx.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
            resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: run {
                resolver.delete(uri, null, null)
                return null
            }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            uri
        } catch (e: Exception) {
            Log.e(TAG, "saveToDownloads failed: ${e.message}", e)
            null
        }
    }

    private fun buildReport(ctx: Context, client: Client, note: String): String {
        val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        val vCode = if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else @Suppress("DEPRECATION") pi.versionCode.toLong()
        val clientVer = if (VortexClient.isInstalled(ctx)) "installed" else "not installed"
        val soc = if (Build.VERSION.SDK_INT >= 31) "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}" else "unknown"
        val mem = ActivityManager.MemoryInfo().also {
            (ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(it)
        }
        return buildString {
            appendLine("App version: ${pi.versionName} (code $vCode)")
            appendLine("Client: ${client.label} ($clientVer)")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("SOC: $soc")
            appendLine("Hardware: ${Build.BOARD} / ${Build.HARDWARE}")
            appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("ABIs: ${Build.SUPPORTED_ABIS.joinToString()}")
            appendLine("RAM: ${mem.totalMem / (1024 * 1024)} MB")
            appendLine("Vulkan driver: ${SettingsActivity.getVulkanDriver(ctx)}")
            appendLine("Safe mode: ${SettingsActivity.isSafeMode(ctx)}")
            if (note.isNotBlank()) appendLine("Note: $note")
        }
    }

    private fun readGameLog(ctx: Context, client: Client): String {
        val root = RootFs.rootDir(ctx)
        val logsDir = File(root, "home/user/.vortex/logs")
        val file = logsDir.listFiles()?.filter { it.isFile }?.maxByOrNull { it.lastModified() }
        if (file == null || !file.exists()) return "${client.logName} not found (was ${client.label} run this session?)"
        val crash = File(logsDir, "crash.log")
        val tail = { f: File ->
            val lines = f.readLines()
            val start = (lines.size - GAME_LOG_TAIL).coerceAtLeast(0)
            lines.subList(start, lines.size).joinToString("\n")
        }
        return if (crash.exists() && crash.length() > 0) {
            tail(file) + "\n\n----- crash.log -----\n" + tail(crash)
        } else {
            tail(file)
        }
    }

    private fun readSessionLog(ctx: Context): String {
        val files = Box64Launcher.sessionLogFiles(ctx)
        if (files.isEmpty()) return "no session log"
        val lines = files.flatMap { f ->
            try { f.readLines() } catch (e: Exception) { listOf("failed to read ${f.name}: ${e.message}") }
        }
        val start = (lines.size - GAME_LOG_TAIL).coerceAtLeast(0)
        return lines.subList(start, lines.size).joinToString("\n")
    }

    private fun readLogcat(): String {
        val main = runLogcat(
            "logcat", "-d", "-v", "time",
            "PolyDroid2:*", "PolyDroid2-Vulkan:*", "PolyDroid2-window:*",
            "Box64:*", "BOX64:*",
            "*:S"
        ) ?: "No matching logcat entries found"
        val crash = runLogcat("logcat", "-d", "-b", "crash", "-v", "time")
        return if (crash == null) main else "$main\n\n----- crash buffer -----\n$crash"
    }

    private fun runLogcat(vararg cmd: String): String? {
        return try {
            val proc = Runtime.getRuntime().exec(cmd)
            val lines = proc.inputStream.bufferedReader().readLines()
            proc.waitFor()
            if (lines.isEmpty()) return null
            val start = (lines.size - LOGCAT_TAIL).coerceAtLeast(0)
            lines.subList(start, lines.size).joinToString("\n")
        } catch (e: Exception) {
            "Failed to read logcat: ${e.message}"
        }
    }
}
