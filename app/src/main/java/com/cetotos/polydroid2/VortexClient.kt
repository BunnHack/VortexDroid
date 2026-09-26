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

        neutralizePthreadIntercept(bin)

        // The client's Linux bootstrap re-launches itself from the XDG
        // location (~/.local/share/vortex/Vortex.AppImage) when handling a
        // deep link with APPIMAGE set. Stage the real file there so the
        // re-exec finds it (it would otherwise hit the AppImage runtime and
        // fail on /dev/fuse).
        run {
            // The bootstrap re-execs ~/.local/share/vortex/Vortex.AppImage to
            // handle deep links. Staging the real AppImage there runs its
            // static runtime -> AppRun (#!/bin/sh, absent on Android) -> exit 1.
            // Stage the extracted client binary instead: the re-exec then runs
            // the client directly with the vortex:// argv, no runtime needed.
            val xdgVortex = java.io.File(RootFs.rootDir(ctx), "home/user/.local/share/vortex")
            xdgVortex.mkdirs()
            val dst = java.io.File(xdgVortex, "Vortex.AppImage")
            val src = binary(ctx)
            if (!dst.exists() || dst.length() != src.length()) {
                src.copyTo(dst, overwrite = true)
                dst.setExecutable(true, false)
                dst.setReadable(true, false)
                Log.i(TAG, "staged client binary as AppImage shim at ${dst.absolutePath}")
            }
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

    /**
     * The client bundles a crash-handler that exports its own `pthread_create`
     * (an interceptor that swaps thread entry points to install a 16KB
     * alternate signal stack per thread). The ELF dynamic symbol of the main
     * binary takes precedence over libc's, so under box64 every thread spawn
     * funnels through the interceptor, which corrupts the emulated stack
     * layout and SIGSEGVs inside the wrapper prologue.
     *
     * Rename the symbol in-place inside .dynstr (same length, NUL-padded) so
     * the interceptor never engages: client code calls the libc/box64
     * `pthread_create` (my_pthread_create bridge) directly. The crash
     * handler's dlsym-based self-detection then finds nothing to hook and
     * cleanly falls back to the plain function.
     */
    private fun neutralizePthreadIntercept(bin: File) {
        val TARGET = "pthread_create"
        val REPLACE = "xthread_create"
        try {
            java.io.RandomAccessFile(bin, "rw").use { raf ->
                val header = ByteArray(64)
                raf.readFully(header)
                if (header[0] != 0x7f.toByte() || header[1] != 'E'.code.toByte()) return
                val e_shoff = le64(header, 40)
                val e_shentsize = le16(header, 58).toInt()
                val e_shnum = le16(header, 60).toInt()
                raf.seek(e_shoff)
                val shdrs = ByteArray(e_shentsize * e_shnum)
                raf.readFully(shdrs)

                var renamed = 0
                for (i in 0 until e_shnum) {
                    val off = i * e_shentsize
                    val shType = le32(shdrs, off + 4).toInt()
                    // SHT_STRTAB = 3 (covers .dynstr and .strtab)
                    if (shType != 3) continue
                    val shOffset = le64(shdrs, off + 24)
                    val shSize = le64(shdrs, off + 32).toInt()
                    raf.seek(shOffset)
                    val strtab = ByteArray(shSize)
                    raf.readFully(strtab)
                    var idx = 0
                    while (idx < shSize) {
                        var end = idx
                        while (end < shSize && strtab[end] != 0.toByte()) end++
                        if (end - idx == TARGET.length &&
                            String(strtab, idx, TARGET.length, Charsets.US_ASCII) == TARGET) {
                            // rename first char; keep length identical
                            System.arraycopy(
                                REPLACE.toByteArray(Charsets.US_ASCII), 0,
                                strtab, idx, REPLACE.length
                            )
                            raf.seek(shOffset + idx)
                            raf.write(strtab, idx, end - idx)
                            renamed++
                        }
                        idx = end + 1
                    }
                }
                Log.i(TAG, "pthread interceptor neutralized ($renamed symbol tables patched)")
            }
        } catch (e: Exception) {
            Log.w(TAG, "pthread neutralize failed (continuing anyway): ${e.message}")
        }
    }

    private fun le16(b: ByteArray, o: Int) =
        ((b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8)).toLong()

    private fun le32(b: ByteArray, o: Int): Long {
        var v = 0L
        for (i in 3 downTo 0) v = (v shl 8) or (b[o + i].toLong() and 0xff)
        return v
    }

    private fun le64(b: ByteArray, o: Int): Long {
        var v = 0L
        for (i in 7 downTo 0) v = (v shl 8) or (b[o + i].toLong() and 0xff)
        return v
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
