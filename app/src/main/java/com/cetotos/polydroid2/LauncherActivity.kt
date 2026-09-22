package com.cetotos.polydroid2

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.LinearLayout
import android.widget.ProgressBar
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlin.concurrent.thread

class LauncherActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "PolyDroid2"
        private const val VORTEX_URL = "https://playvortex.io/home"
    }

    private var extractionInProgress = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_launcher)

        val root = findViewById<android.view.View>(R.id.launcher_root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(
                bars.left + 32.dpToPx(),
                bars.top + 32.dpToPx(),
                bars.right + 32.dpToPx(),
                bars.bottom + 32.dpToPx()
            )
            insets
        }

        findViewById<android.view.View>(R.id.btn_website).setOnClickListener {
            vortexWebsite()
        }

        findViewById<android.view.View>(R.id.btn_settings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        // pre-extract the rootfs (and Vortex client when possible) so the
        // first Play from the website is fast
        if (RootFs.needsExtraction(this)) {
            extractionInProgress = true
            val detailBar = ProgressBar(
                this, null, android.R.attr.progressBarStyleHorizontal
            ).apply {
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
                addView(detailBar)
            }
            val dialog = MaterialAlertDialogBuilder(this)
                .setTitle("Extracting files...")
                .setView(dialogView)
                .setCancelable(false)
                .create()
            dialog.setCanceledOnTouchOutside(false)
            dialog.show()

            thread {
                RootFs.extractAll(this) { detailPct, _, _, stageLabel ->
                    runOnUiThread {
                        dialog.setTitle(stageLabel)
                        detailBar.progress = detailPct
                    }
                }
                try {
                    VortexClient.install(this) { pct, label ->
                        runOnUiThread {
                            dialog.setTitle(label)
                            detailBar.progress = pct
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Vortex client pre-install failed (will retry on launch): ${e.message}")
                }
                runOnUiThread {
                    dialog.dismiss()
                    extractionInProgress = false
                }
            }
        }
    }

    private fun Int.dpToPx(): Int =
        (this * resources.displayMetrics.density).toInt()

    private fun vortexWebsite() {
        try {
            val customTabsIntent = CustomTabsIntent.Builder()
                .setShowTitle(false)
                .setUrlBarHidingEnabled(true)
                .build()
            customTabsIntent.launchUrl(this, Uri.parse(VORTEX_URL))
        } catch (e: Exception) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(VORTEX_URL)))
            } catch (e2: Exception) {
                Log.e(TAG, "No browser available!", e2)
            }
        }
    }
}
