package app.siding.browser

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.speech.RecognizerIntent
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import app.siding.browser.data.BrowserStore
import app.siding.browser.ui.BrowserApp
import app.siding.browser.web.BrowserHost
import app.siding.browser.web.CabinEngine

class MainActivity : ComponentActivity(), BrowserHost {
    private lateinit var engine: CabinEngine
    private var permissionResult: ((Boolean) -> Unit)? = null
    private var permissionQueue: List<String> = emptyList()
    private var fileCallback: ValueCallback<Array<Uri>>? = null

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted: Boolean ->
        if (!granted) {
            permissionQueue = emptyList()
            permissionResult?.invoke(false)
            permissionResult = null
        } else {
            continuePermissionQueue()
        }
    }

    private val fileLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val callback = fileCallback
        fileCallback = null
        val uris = WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
        callback?.onReceiveValue(uris)
    }

    private val speechLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val spoken = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (!spoken.isNullOrBlank()) engine.submit(spoken)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = BrowserStore(getSharedPreferences("siding", MODE_PRIVATE))
        engine = CabinEngine(this, store, this)
        setContent {
            BrowserApp(
                engine = engine,
                onShare = { shareCurrent() },
                onMic = { listen() },
                onExit = { moveTaskToBack(true) },
            )
        }
        engine.openExternal(readIncoming(intent))
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        engine.openExternal(readIncoming(intent))
    }

    override fun onResume() {
        super.onResume()
        engine.onHostResume()
    }

    override fun onPause() {
        engine.onHostPause()
        super.onPause()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        engine.onConfigurationChanged()
    }

    override fun onDestroy() {
        if (::engine.isInitialized) engine.destroy()
        fileCallback?.onReceiveValue(null)
        fileCallback = null
        super.onDestroy()
    }

    private fun continuePermissionQueue() {
        if (permissionQueue.isEmpty()) {
            permissionResult?.invoke(true)
            permissionResult = null
            return
        }
        val next = permissionQueue.first()
        permissionQueue = permissionQueue.drop(1)
        permissionLauncher.launch(next)
    }

    override fun askRuntime(permissions: List<String>, result: (Boolean) -> Unit) {
        val missing = permissions.distinct().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            result(true)
            return
        }
        permissionResult = result
        permissionQueue = missing.drop(1)
        permissionLauncher.launch(missing.first())
    }

    override fun chooseFile(callback: ValueCallback<Array<Uri>>?, params: WebChromeClient.FileChooserParams) {
        fileCallback?.onReceiveValue(null)
        fileCallback = callback
        try {
            fileLauncher.launch(params.createIntent())
        } catch (_: ActivityNotFoundException) {
            fileCallback = null
            callback?.onReceiveValue(null)
            engine.toast("No file picker on this device")
        }
    }

    override fun setImmersive(enabled: Boolean) {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (enabled) {
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun shareCurrent() {
        val url = engine.snapshot.pageUrl ?: return
        if (!url.startsWith("http")) return
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, url)
        }
        try {
            startActivity(Intent.createChooser(send, "Share this page"))
        } catch (_: ActivityNotFoundException) {
            engine.toast("Nothing on this device can share that page")
        }
    }

    private fun listen() {
        askRuntime(listOf(Manifest.permission.RECORD_AUDIO)) { granted ->
            if (!granted) {
                engine.toast("Microphone permission is off")
                return@askRuntime
            }
            val recognize = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PROMPT, "Search or speak a site")
            }
            try {
                speechLauncher.launch(recognize)
            } catch (_: ActivityNotFoundException) {
                engine.toast("No speech recognizer on this device")
            }
        }
    }

    private fun readIncoming(intent: Intent?): String? {
        if (intent == null) return null
        return when (intent.action) {
            Intent.ACTION_VIEW -> intent.dataString
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            else -> null
        }
    }
}
