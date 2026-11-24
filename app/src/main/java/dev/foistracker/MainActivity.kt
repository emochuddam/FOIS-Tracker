package dev.foistracker

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.runtime.*
import androidx.core.content.FileProvider
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

data class Challenge(val guest: Boolean, val query: Query?, val image: Bitmap? = null, val revision: Int = 0)
data class ResultPage(val query: Query, val freight: List<Freight>, val cached: Boolean, val selected: Int = 0)

class MainActivity : FragmentActivity() {
    internal val services by lazy { Services.get(this) }
    internal var tab by mutableIntStateOf(0)
    internal var page by mutableStateOf<ResultPage?>(null)
    internal var challenge by mutableStateOf<Challenge?>(null)
    internal var loading by mutableStateOf(false)
    internal var message by mutableStateOf<String?>(null)
    internal var locked by mutableStateOf(false)
    internal var biometricEnabled by mutableStateOf(false)
    internal var queryText by mutableStateOf("")
    internal var consignor by mutableStateOf("FCI")
    internal var theme by mutableStateOf("System")
    internal var interval by mutableLongStateOf(15)
    internal var remembered by mutableStateOf("")
    internal var storedPassword by mutableStateOf("")
    internal var saved by mutableStateOf(emptyList<Saved>())
    internal var history by mutableStateOf(emptyList<Entry>())
    private var report: Pair<Freight, ReportFormat>? = null
    private var pendingKey: String? = null
    private var prompting = false
    private var wentToBackground = false
    private val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
    private val saveReport = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = report
        report = null
        val uri = result.data?.data
        if (uri != null && data != null) {
            execute {
                contentResolver.openOutputStream(uri)?.use { Reports.write(data.second, data.first, it) }
                    ?: throw IOException()
                withContext(Dispatchers.Main) { message = "Report saved." }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        theme = services.settings.getString("theme", "System") ?: "System"
        interval = services.settings.getLong("interval", 15)
        biometricEnabled = services.settings.getBoolean("biometric", false)
        locked = biometricEnabled
        pendingKey = intent.getStringExtra("enquiry")
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { migrateLegacy() }
            services.store.changes.collect {
                val (list, entries) = withContext(Dispatchers.IO) { services.store.saved() to services.store.history() }
                saved = list
                history = entries
                if (!locked) openPending()
            }
        }
        lifecycleScope.launch(Dispatchers.IO) {
            Tracking.sync(this@MainActivity, services)
            Alert.entries.forEach { Tracking.channel(this@MainActivity, it) }
        }
        setContent {
            BackHandler(page != null || challenge != null) {
                if (!loading) {
                    if (challenge != null) closeChallenge() else page = null
                }
            }
            TrackerApp(this)
        }
        if (locked) window.decorView.post { authenticate(false) }
    }

    private fun migrateLegacy() {
        if (services.settings.getBoolean("legacy_v1_imported", false)) return
        val legacy = getSharedPreferences("MainActivity", MODE_PRIVATE)
        val items = services.store.saved().associateBy(Saved::key)
        legacy.getStringSet("fnrs", emptySet()).orEmpty().mapNotNull { Query.parse(it) }.filter { it.isFnr }.forEach {
            if (it.key !in items) services.store.save(Saved(it.key, "", false, false))
        }
        val updated = legacy.getLong("updated_at", 0)
        val legacyReport = legacy.getString("latest_output", "")?.takeIf { it.isNotBlank() }
        if (updated > 0 && legacyReport != null) {
            val fnr = Regex("[0-9]{11}").find(legacyReport.lineSequence().firstOrNull().orEmpty())?.value
            if (fnr != null && services.store.cached(fnr).isEmpty()) {
                val synthetic = org.jsoup.nodes.Document.createShell("")
                val table = synthetic.body().appendElement("table")
                legacyReport.lineSequence().drop(1).map { it.split("  |  ") }.filter { it.size >= 2 }.forEach { cells ->
                    val row = table.appendElement("tr")
                    cells.forEach { row.appendElement("td").text(it.trim()) }
                }
                val parsed = runCatching { FreightParser.details(synthetic.html(), fnr, public = true) }.getOrNull()
                if (parsed != null) services.store.record(fnr, listOf(parsed.copy(retrieved = updated)))
            }
        }
        services.settings.edit().putBoolean("legacy_v1_imported", true).apply()
    }

    override fun onStart() {
        super.onStart()
        if (wentToBackground && biometricEnabled) locked = true
        wentToBackground = false
    }

    override fun onStop() {
        super.onStop()
        wentToBackground = true
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingKey = intent.getStringExtra("enquiry")
        if (!locked) openPending()
    }

    private fun openPending() {
        val key = pendingKey ?: return
        pendingKey = null
        val stored = Query.fromStorage(key)
        val query = Query.parse(stored.key, stored.consignor) ?: return
        val cached = history.find { it.key == key }?.freight.orEmpty()
        if (cached.isNotEmpty()) page = ResultPage(query, cached, true)
        else open(query)
    }

    internal fun authenticate(enabling: Boolean) {
        if (prompting) return
        val options = BiometricManager.Authenticators.BIOMETRIC_STRONG
        if (BiometricManager.from(this).canAuthenticate(options) != BiometricManager.BIOMETRIC_SUCCESS) {
            message = "Set up a supported fingerprint or strong biometric in Android settings."
            return
        }
        prompting = true
        val prompt = BiometricPrompt(this, mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                prompting = false
                locked = false
                if (enabling) {
                    biometricEnabled = true
                    services.settings.edit().putBoolean("biometric", true).apply()
                }
                openPending()
            }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                prompting = false
                message = errString.toString()
            }
        })
        prompt.authenticate(BiometricPrompt.PromptInfo.Builder().setTitle("FOIS Tracker")
            .setSubtitle("Unlock freight tracking").setAllowedAuthenticators(options).setNegativeButtonText("Cancel").build())
    }

    internal fun disableBiometric() {
        biometricEnabled = false
        services.settings.edit().putBoolean("biometric", false).apply()
    }

    internal fun changeTheme(value: String) {
        theme = value
        services.settings.edit().putString("theme", value).apply()
    }

    internal fun changeInterval(value: Long) {
        interval = value
        services.settings.edit().putLong("interval", value).apply()
        lifecycleScope.launch(Dispatchers.IO) { Tracking.sync(this@MainActivity, services) }
    }

    internal fun search() {
        val query = Query.parse(queryText, consignor)
        if (query == null) {
            message = "Enter an 11-digit FNR or two different station codes, such as RMN CFS."
        } else open(query)
    }

    internal fun open(query: Query, cachedOnly: Boolean = false) {
        if (loading) return
        val cached = history.find { it.key == query.storageKey }?.freight.orEmpty()
        if (cached.isNotEmpty()) page = ResultPage(query, cached, true)
        if (cachedOnly && cached.isNotEmpty()) return
        message = null
        if (!canReachInternet()) {
            message = if (cached.isEmpty()) "No internet connection. Connect to the internet and retry."
                else "Offline. Showing the last retrieved freight result."
            return
        }
        if (services.signedIn.value) refresh(query)
        else beginChallenge(query.isFnr, query)
    }

    internal fun refresh(query: Query) {
        if (loading) return
        message = null
        if (!canReachInternet()) {
            page = page?.copy(cached = true)
            message = "Offline. Check your connection before refreshing."
            return
        }
        if (!services.signedIn.value) {
            beginChallenge(query.isFnr, query)
            return
        }
        execute {
            try {
                val result = services.networkLock.withLock {
                    try {
                        val fresh = services.client.enquiry(query)
                        services.persistSession()
                        val tracked = services.store.record(query.storageKey, fresh)
                        if (query.isFnr && tracked.firstOrNull()?.arrived != null) services.store.pause(query.storageKey)
                        tracked
                    } catch (e: FoisError.Authentication) {
                        services.forgetSession()
                        throw e
                    }
                }
                show(query, result)
            } catch (_: FoisError.Authentication) {
                withContext(Dispatchers.Main) {
                    message = "Your ECBS session ended. Sign in again to continue."
                    initializeChallenge(false, query)
                }
                loadChallenge(reset = true)
            }
        }
    }

    internal fun beginChallenge(guest: Boolean, query: Query? = null) {
        if (loading) return
        initializeChallenge(guest, query)
        execute { loadChallenge(reset = true) }
    }

    private fun initializeChallenge(guest: Boolean, query: Query?) {
        challenge = Challenge(guest, query)
        remembered = ""
        storedPassword = ""
    }

    internal fun closeChallenge() {
        challenge = null
        storedPassword = ""
    }

    internal fun reloadChallenge() {
        if (loading) return
        val current = challenge ?: return
        challenge = current.copy(image = null, revision = current.revision + 1)
        execute { loadChallenge(reset = current.image == null) }
    }

    private suspend fun loadChallenge(reset: Boolean) {
        val current = withContext(Dispatchers.Main) { challenge } ?: return
        val (bitmap, user, password) = services.networkLock.withLock {
            val user = if (current.guest) "" else services.vault.get("username").orEmpty()
            val password = if (current.guest) "" else services.vault.get("password").orEmpty()
            val bytes = services.client.image(current.guest, reset)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth !in 1..2048 || bounds.outHeight !in 1..1024) throw FoisError.Format()
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: throw FoisError.Format()
            Triple(bitmap, user, password)
        }
        withContext(Dispatchers.Main) {
            remembered = user
            storedPassword = password
            challenge = current.copy(image = bitmap)
        }
    }

    internal fun submit(user: String, password: String, answer: String, remember: Boolean, keep: Boolean) {
        val current = challenge ?: return
        if (answer.isBlank() || (!current.guest && (user.isBlank() || password.isBlank()))) {
            message = "Complete the required fields and enter the verification code."
            return
        }
        execute {
            try {
                val result = services.networkLock.withLock {
                    if (current.guest) {
                        val query = requireNotNull(current.query)
                        services.store.record(query.storageKey, listOf(services.client.publicFnr(query.key, answer.trim())))
                    } else {
                        services.client.signIn(user.trim(), password, answer.trim())
                        services.persistSession()
                        services.vault.put("username", user.trim().takeIf { remember || keep })
                        services.vault.put("password", password.takeIf { keep })
                        services.store.clearAlerts(Alert.AUTH.name)
                        services.store.clearAlerts(Alert.SESSION.name)
                        services.store.saved().filter { it.tracking }.forEach {
                            Tracking.notify(this@MainActivity, services, it.key, Alert.RESUMED, "Authorized refresh is available.",
                                System.currentTimeMillis().toString())
                        }
                        null
                    }
                }
                withContext(Dispatchers.Main) {
                    closeChallenge()
                    if (!current.guest) message = "Signed in to ECBS."
                }
                val query = current.query
                if (query != null) {
                    if (result != null) show(query, result)
                    else {
                        val authorized = services.networkLock.withLock {
                            try {
                                val rows = services.client.enquiry(query)
                                services.persistSession()
                                services.store.record(query.storageKey, rows)
                            } catch (e: FoisError.Authentication) {
                                services.forgetSession()
                                throw e
                            }
                        }
                        show(query, authorized)
                    }
                }
            } catch (e: FoisError.Verification) {
                withContext(Dispatchers.Main) {
                    message = e.message
                    challenge = current.copy(image = null, revision = current.revision + 1)
                }
                loadChallenge(reset = false)
            } catch (e: FoisError.Credentials) {
                withContext(Dispatchers.Main) {
                    message = e.message
                    challenge = current.copy(image = null, revision = current.revision + 1)
                }
                loadChallenge(reset = true)
            }
        }
    }

    private suspend fun show(query: Query, result: List<Freight>) = withContext(Dispatchers.Main) {
        val oldPage = page?.takeIf { it.query == query }
        val oldItem = oldPage?.freight?.getOrNull(oldPage.selected)
        val index = oldItem?.let { old -> result.indexOfFirst { row ->
            if (old.fnr.isNotEmpty()) row.fnr == old.fnr
            else old.values["Rake identification"] != null && old.values["Rake identification"] == row.values["Rake identification"]
        } } ?: 0
        page = ResultPage(query, result, false, index.coerceAtLeast(0))
    }

    internal fun logout() {
        execute {
            services.networkLock.withLock {
                try { services.client.signOut() }
                catch (_: IOException) { }
                finally {
                    services.forgetSession()
                    services.vault.put("password", null)
                }
            }
            withContext(Dispatchers.Main) { message = "Signed out. Background checks need a new ECBS session." }
        }
    }

    internal fun save(item: Saved) {
        if (item.tracking && Build.VERSION.SDK_INT >= 33) notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        lifecycleScope.launch(Dispatchers.IO) {
            services.store.save(item)
            Tracking.sync(this@MainActivity, services)
        }
    }

    internal fun delete(item: Saved) {
        lifecycleScope.launch(Dispatchers.IO) {
            services.store.delete(item.key)
            Tracking.sync(this@MainActivity, services)
        }
    }
    internal fun clearHistory() {
        execute {
            services.networkLock.withLock { services.store.clearHistory() }
            withContext(Dispatchers.Main) { page = null; message = "Search history and cached results cleared." }
        }
    }
    internal fun copy(label: String, text: String) {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(label, text))
        message = "Copied to clipboard."
    }
    internal fun paste(): String = getSystemService(ClipboardManager::class.java).primaryClip?.takeIf { it.itemCount > 0 }
        ?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
    internal fun export(freight: Freight, format: ReportFormat, share: Boolean, whatsApp: Boolean = false) {
        val id = freight.fnr.takeIf { it.matches(Regex("[0-9]{11}")) } ?: "Freight"
        val name = "FOIS-$id.${format.extension}"
        if (!share) {
            report = freight to format
            saveReport.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).setType(format.mime)
                .addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE, name))
        } else execute {
            val dir = File(cacheDir, "reports").apply { mkdirs() }
            val file = File(dir, name)
            file.outputStream().use { Reports.write(format, freight, it) }
            val uri = FileProvider.getUriForFile(this@MainActivity, "${packageName}.reports", file)
            val outgoing = Intent(Intent.ACTION_SEND).setType(format.mime).putExtra(Intent.EXTRA_STREAM, uri)
                .apply { clipData = ClipData.newUri(contentResolver, name, uri) }.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            withContext(Dispatchers.Main) {
                if (whatsApp) {
                    outgoing.setPackage("com.whatsapp")
                    try { startActivity(outgoing); return@withContext }
                    catch (_: android.content.ActivityNotFoundException) { outgoing.setPackage(null) }
                }
                startActivity(Intent.createChooser(outgoing, "Share freight report"))
            }
        }
    }
    internal fun channelSettings(alert: Alert) {
        startActivity(Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            .putExtra(Settings.EXTRA_CHANNEL_ID, Tracking.channel(this, alert)))
    }

    private fun canReachInternet(): Boolean {
        val connectivity = getSystemService(ConnectivityManager::class.java)
        return connectivity.getNetworkCapabilities(connectivity.activeNetwork)
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }

    private fun execute(task: suspend () -> Unit) {
        if (loading) return
        loading = true
        lifecycleScope.launch {
            try { withContext(Dispatchers.IO) { task() } }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                message = when (e) {
                    is FoisError -> e.message
                    is SocketTimeoutException -> "FOIS timed out. Try again."
                    is UnknownHostException -> "FOIS could not be reached. Check your internet connection."
                    is SSLException -> "Android could not verify the FOIS HTTPS connection."
                    is IOException -> "The operation could not finish. Check your connection and try again."
                    else -> "The operation could not finish. Try again."
                }
            } finally { loading = false }
        }
    }
}
