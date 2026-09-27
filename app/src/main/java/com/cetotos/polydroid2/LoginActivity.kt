package com.cetotos.polydroid2

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlin.concurrent.thread

/**
 * In-app launcher (tempest-style): log in with username/password, pick a
 * game, fetch a FRESH launch ticket from /games/<id>/play and start the
 * client with it directly - no browser round-trip, no stale tickets.
 *
 * Still accepts vortex:// deep links as a secondary path (browser flow).
 */
class LoginActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "PolyDroid2"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent?.data?.scheme == "vortex") {
            // browser deep link: use as-is (legacy path)
            Log.i(TAG, "Vortex deep link: ${intent.data}")
            prepareClientThenLaunch(intent.data.toString())
            return
        }
        if (VortexApi.isLoggedIn(this)) showGamePicker() else showLoginForm()
    }

    // ---------- login ----------

    private fun showLoginForm() {
        val ctx = this
        val pad = (16 * resources.displayMetrics.density).toInt()
        val user = EditText(ctx).apply { hint = "Username" }
        val pass = EditText(ctx).apply {
            hint = "Password"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val panel = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            addView(user)
            addView(pass)
        }
        MaterialAlertDialogBuilder(ctx)
            .setTitle("Log in to Vortex")
            .setView(panel)
            .setPositiveButton("Log in") { _, _ ->
                val u = user.text.toString().trim()
                val p = pass.text.toString()
                if (u.isEmpty() || p.isEmpty()) {
                    Toast.makeText(ctx, "Username and password required", Toast.LENGTH_SHORT).show()
                    finish()
                    return@setPositiveButton
                }
                doLogin(u, p)
            }
            .setNegativeButton("Cancel") { _, _ -> finish() }
            .setOnDismissListener { if (!VortexApi.isLoggedIn(ctx)) finish() }
            .show()
    }

    private fun doLogin(user: String, pass: String) {
        val dlg = MaterialAlertDialogBuilder(this)
            .setTitle("Signing in…")
            .setView(ProgressBar(this))
            .setCancelable(false)
            .show()
        thread {
            try {
                VortexApi.login(this, user, pass)
                runOnUiThread {
                    dlg.dismiss()
                    showGamePicker()
                }
            } catch (e: Exception) {
                Log.e(TAG, "login failed: ${e.message}")
                runOnUiThread {
                    dlg.dismiss()
                    MaterialAlertDialogBuilder(this)
                        .setTitle("Login failed")
                        .setMessage(e.message ?: "unknown error")
                        .setPositiveButton("Retry") { _, _ -> showLoginForm() }
                        .setNegativeButton("Cancel") { _, _ -> finish() }
                        .setOnDismissListener { finish() }
                        .show()
                }
            }
        }
    }

    // ---------- game picker ----------

    private fun showGamePicker() {
        val ctx = this
        val dlg = MaterialAlertDialogBuilder(ctx)
            .setTitle("Games")
            .setView(ProgressBar(ctx))
            .setCancelable(true)
            .setOnDismissListener { finish() }
            .show()
        thread {
            try {
                val games = VortexApi.listGames()
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    dlg.dismiss()
                    val names = games.map { "${it.name}  —  ${it.creator}" }.toTypedArray()
                    MaterialAlertDialogBuilder(ctx)
                        .setTitle("Play which game?")
                        .setItems(names) { _, which ->
                            launchGame(games[which].id, games[which].name)
                        }
                        .setNeutralButton("Log out") { _, _ ->
                            VortexApi.logout(ctx)
                            finish()
                        }
                        .setNegativeButton("Close", null)
                        .setOnDismissListener { finish() }
                        .show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    dlg.dismiss()
                    Toast.makeText(ctx, "Failed to list games: ${e.message}", Toast.LENGTH_LONG).show()
                    finish()
                }
            }
        }
    }

    // ---------- launch ----------

    private fun launchGame(gameId: Int, name: String) {
        val ctx = this
        val dlg = MaterialAlertDialogBuilder(ctx)
            .setTitle("Joining $name…")
            .setView(ProgressBar(ctx))
            .setCancelable(false)
            .show()
        thread {
            try {
                val uri = VortexApi.getPlayUri(ctx, gameId)
                Log.i(TAG, "fresh launch uri for game $gameId")
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    dlg.dismiss()
                    prepareClientThenLaunch(uri)
                }
            } catch (e: Exception) {
                Log.e(TAG, "get play uri failed: ${e.message}")
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    dlg.dismiss()
                    MaterialAlertDialogBuilder(ctx)
                        .setTitle("Couldn't join")
                        .setMessage("${e.message}\n\nIf the session expired, log in again.")
                        .setPositiveButton("OK") { _, _ -> finish() }
                        .setOnDismissListener { finish() }
                        .show()
                }
            }
        }
    }

    // ---------- client preparation (shared with deep-link path) ----------

    private fun prepareClientThenLaunch(deepLinkUrl: String) {
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = false
            max = 100
            progress = 0
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
                        .setMessage("${e.message}\n\nTry again to retry.")
                        .setPositiveButton("OK") { _, _ -> finish() }
                        .setOnDismissListener { finish() }
                        .show()
                }
            }
        }
    }
}
