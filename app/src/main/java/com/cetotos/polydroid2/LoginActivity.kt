package com.cetotos.polydroid2

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.LinearLayout
import android.widget.ProgressBar
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlin.concurrent.thread

/**
 * Receives vortex:// deep links from the playvortex.io website.
 *
 * The Vortex client parses the full URL itself (scheme + host/path + query
 * carry the auth/launch tokens), so all we do is make sure the client is
 * installed and hand the URL over as the first launch argument.
 */
class LoginActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "PolyDroid2"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!handleVortexIntent(intent)) {
            finish()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleVortexIntent(intent)
    }

    private fun handleVortexIntent(intent: Intent): Boolean {
        val uri = intent.data ?: return false
        if (uri.scheme != "vortex") return false
        Log.i(TAG, "Vortex deep link: $uri")
        prepareClientThenLaunch(uri.toString())
        return true
    }

    private fun prepareClientThenLaunch(deepLinkUrl: String) {
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = false
            max = 100
            progress = 0
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        val dialogView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(64, 48, 64, 48)
            addView(bar)
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("Preparing Vortex client…")
            .setView(dialogView)
            .setCancelable(false)
            .create()
        dialog.setCanceledOnTouchOutside(false)
        dialog.show()

        thread {
            try {
                if (RootFs.needsExtraction(this)) {
                    runOnUiThread { dialog.setTitle("Extracting files…") }
                    RootFs.extractAll(this) { pct, _, _, label ->
                        runOnUiThread { bar.progress = pct; dialog.setTitle(label) }
                    }
                }
                VortexClient.install(this) { pct, label ->
                    runOnUiThread { bar.progress = pct; dialog.setTitle(label) }
                }
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    dialog.dismiss()
                    startActivity(Intent(this, GameActivity::class.java).apply {
                        putExtra("exec_args", deepLinkUrl)
                    })
                    finish()
                }
            } catch (e: Exception) {
                Log.e(TAG, "client prepare failed: ${e.message}", e)
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    dialog.dismiss()
                    MaterialAlertDialogBuilder(this)
                        .setTitle("Couldn't prepare the Vortex client")
                        .setMessage("${e.message}\n\nPress Play again to retry.")
                        .setPositiveButton("OK") { _, _ -> finish() }
                        .setOnDismissListener { finish() }
                        .show()
                }
            }
        }
    }
}
