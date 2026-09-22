package com.cetotos.polydroid2

import android.content.Context
import android.system.Os
import android.util.Log
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Manages the Vortex Linux client (https://playvortex.io).
 *
 * The client ships as an x86-64 type-2 AppImage bundled in the APK assets.
 * At install time the AppImage is copied out and self-extracted with
 * `--appimage-extract` under Box64 (no FUSE needed on Android), producing
 * squashfs-root/usr/bin/Vortex which is then launched through Box64.
 */
object VortexClient {
    private const val TAG = "PolyDroid2"
    private const val ASSET = "vortex/Vortex-linux-x86_64.AppImage"
    private const val MARKER = ".vortex_client_size"

    private const val EXTRACT_TIMEOUT_SEC = 600L

    fun clientDir(ctx: Context): File = File(ctx.filesDir, "vortex-client")
    fun appImageFile(ctx: Context): File = File(clientDir(ctx), "Vortex.AppImage")
    fun extractedDir(ctx: Context): File = File(clientDir(ctx), "squashfs-root")

    fun binary(ctx: Context): File = File(extractedDir(ctx), "usr/bin/Vortex")

    fun isInstalled(ctx: Context): Boolean = binary(ctx).exists()

    fun delete(ctx: Context) {
        val dir = clientDir(ctx)
        val link = File(RootFs.rootDir(ctx), "vortex")
        try {
            val lp = link.toPath()
            if (Files.isSymbolicLink(lp) &&
                Files.readSymbolicLink(lp).toFile().absolutePath.startsWith(dir.absolutePath)) {
                Files.delete(lp)
            }
        } catch (e: Exception) {
            Log.w(TAG, "symlink delete failed: ${e.message}")
        }
        if (dir.exists()) dir.deleteRecursively()
    }

    private fun assetSize(ctx: Context): Long = try {
        ctx.assets.openFd(ASSET).use { it.length }
    } catch (_: Exception) { -1L }

    private fun installedAssetSize(ctx: Context): Long {
        val m = File(clientDir(ctx), MARKER)
        return if (m.exists()) m.readText().trim().toLongOrNull() ?: -1L else -1L
    }

    /** Installs or repairs the Vortex client. Safe to call repeatedly. */
    fun install(ctx: Context, onProgress: (Int, String) -> Unit) {
        if (isInstalled(ctx) && installedAssetSize(ctx) == assetSize(ctx)) {
            activate(ctx)
            return
        }

        val dir = clientDir(ctx)
        if (dir.exists()) dir.deleteRecursively()
        dir.mkdirs()

        // 1. copy the AppImage out of the APK (asset access is not seekable/executable)
        onProgress(0, "Copying Vortex client…")
        val appImage = appImageFile(ctx)
        val total = assetSize(ctx)
        if (total <= 0) throw java.io.IOException("Vortex AppImage missing from assets")
        ctx.assets.open(ASSET).use { input ->
            appImage.outputStream().use { out ->
                val buf = ByteArray(1 shl 16)
                var done = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    val pct = (done * 20 / total).toInt().coerceIn(0, 20)
                    onProgress(pct, "Copying Vortex client…")
                }
            }
        }
        appImage.setReadable(true, false)
        appImage.setExecutable(true, false)
        File(dir, MARKER).writeText(total.toString())

        // 2. let the AppImage self-extract under Box64
        onProgress(22, "Extracting Vortex client…")
        extractAppImage(ctx)

        val bin = binary(ctx)
        if (!bin.exists()) {
            throw java.io.IOException("AppImage extraction failed (usr/bin/Vortex not found)")
        }
        bin.setExecutable(true, false)
        extractedDir(ctx).walkTopDown().filter { it.isFile }.forEach {
            it.setReadable(true, false)
        }

        activate(ctx)
        onProgress(100, "Ready")
        Log.i(TAG, "Vortex client installed at ${bin.absolutePath}")
    }

    /** Runs `<box64> Vortex.AppImage --appimage-extract` with the client dir as cwd. */
    private fun extractAppImage(ctx: Context) {
        val nativeDir = ctx.applicationInfo.nativeLibraryDir
        val rootPath = RootFs.rootDir(ctx).absolutePath
        val dir = clientDir(ctx)
        val appImage = appImageFile(ctx)

        val box64 = File(nativeDir, "libbox64.so")
        if (!box64.exists()) throw java.io.IOException("Box64 not found at ${box64.absolutePath}")

        File("$rootPath/tmp").mkdirs()

        val cmd = arrayOf(
            "/system/bin/sh", "-c",
            "export TMPDIR='$rootPath/tmp'; " +
            "export HOME='$rootPath/home/user'; " +
            "export BOX64_LOG=1; " +
            "export LD_LIBRARY_PATH='$rootPath/usr/lib/arm64-native:$nativeDir'; " +
            "LD_PRELOAD='$nativeDir/libhost_syscall_shim.so' " +
            "'$nativeDir/libbox64.so' '$appImage' --appimage-extract"
        )

        val proc = Runtime.getRuntime().exec(cmd, null, dir)
        val output = StringBuilder()
        fun drain(stream: java.io.InputStream, label: String) {
            try {
                stream.bufferedReader().use { r ->
                    r.lineSequence().forEach { line ->
                        Log.i(TAG, "[extract-$label] $line")
                        synchronized(output) { output.append(line).append('\n') }
                    }
                }
            } catch (_: java.io.IOException) {}
        }
        val t1 = thread { drain(proc.inputStream, "out") }
        val t2 = thread { drain(proc.errorStream, "err") }

        val finished = proc.waitFor(EXTRACT_TIMEOUT_SEC, TimeUnit.SECONDS)
        if (!finished) {
            proc.destroyForcibly()
            throw java.io.IOException("AppImage extraction timed out")
        }
        t1.join(2000); t2.join(2000)
        if (proc.exitValue() != 0) {
            val tail = synchronized(output) { output.toString().takeLast(2000) }
            throw java.io.IOException("AppImage extraction exited with ${proc.exitValue()}: $tail")
        }
    }

    /** Points rootfs/vortex at the extracted client so the guest can find it. */
    fun activate(ctx: Context) {
        val link = File(RootFs.rootDir(ctx), "vortex")
        val lp = link.toPath()
        try {
            if (Files.isSymbolicLink(lp)) {
                Files.delete(lp)
            } else if (link.exists()) {
                link.deleteRecursively()
            }
            Os.symlink(extractedDir(ctx).absolutePath, link.absolutePath)
        } catch (e: Exception) {
            Log.w(TAG, "vortex symlink failed: ${e.message}")
        }
    }
}
