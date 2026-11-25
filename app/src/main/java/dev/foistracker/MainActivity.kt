package dev.foistracker

import android.Manifest
import android.app.NotificationManager
import android.content.pm.PackageManager
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
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.runtime.*
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

data class Challenge(val guest: Boolean, val query: Query?, val image: Bitmap? = null, val revision: Int = 0,
    val notifyOnRefresh: Boolean = false)
enum class ThemeMode(val label: String) { LIGHT("Light"), DARK("Dark"), SYSTEM("System") }

data class ResultPage(val query: Query, val freight: List<Freight>, val cached: Boolean, val selected: Int = 0)

class MainActivity : FragmentActivity() {
    internal val services by lazy { Services.get(this) }
    internal var tab by mutableIntStateOf(0)
    internal var page by mutableStateOf<ResultPage?>(null)
    internal var challenge by mutableStateOf<Challenge?>(null)
    internal var loading by mutableStateOf(false)
    internal var automaticRefresh by mutableStateOf(false)
        private set
    internal var refreshIssue by mutableStateOf<Pair<Query, String>?>(null)
        private set
    internal var message by mutableStateOf<String?>(null)
    internal var locked by mutableStateOf(false)
    internal var biometricEnabled by mutableStateOf(false)
    internal var themeMode by mutableStateOf<ThemeMode?>(null)
        private set
    internal var queryText by mutableStateOf("")
    internal var consignor by mutableStateOf("FCI")
    internal var foreground by mutableStateOf(false)
        private set
    internal var notificationsAllowed by mutableStateOf(false)
        private set
    internal var interval by mutableLongStateOf(15)
    internal var remembered by mutableStateOf("")
    internal var storedPassword by mutableStateOf("")
    internal var saved by mutableStateOf(emptyList<Saved>())
    internal var history by mutableStateOf(emptyList<Entry>())
    private var pendingKey: String? = null
    private var prompting = false
    private var wentToBackground = false
    private val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { updateNotificationState() }
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        themeMode = ThemeMode.entries.firstOrNull { it.name == services.settings.getString("theme_mode", null) }
        interval = services.settings.getLong("interval", 15)
        biometricEnabled = services.settings.getBoolean("biometric", false)
        locked = biometricEnabled
        pendingKey = intent.getStringExtra("enquiry") ?: services.liveTracking.value?.query?.storageKey
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { migrateLegacy() }
            services.store.changes.collect {
                val (list, entries) = withContext(Dispatchers.IO) { services.store.saved() to services.store.history() }
                saved = list
                history = entries
                val current = page
                val latest = current?.let { result -> entries.firstOrNull { it.key == result.query.storageKey } }
                if (current != null && latest != null &&
                    latest.updated > (current.freight.maxOfOrNull(Freight::retrieved) ?: 0)) {
                    show(current.query, latest.freight)
                }
                if (!locked) openPending()
            }
        }
        lifecycleScope.launch(Dispatchers.IO) {
            Tracking.sync(this@MainActivity, services)
            Alert.entries.forEach { Tracking.channel(this@MainActivity, it) }
        }
        setContent {
            BackHandler(page != null || challenge != null) {
                if (!loading || automaticRefresh) {
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

    private fun updateNotificationState() {
        notificationsAllowed = getSystemService(NotificationManager::class.java).areNotificationsEnabled()
    }

    internal fun enableNotifications() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            (!services.settings.getBoolean("notification_prompt_shown", false) ||
                shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS))) {
            services.settings.edit().putBoolean("notification_prompt_shown", true).apply()
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else if (!notificationsAllowed) {
            startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
        }
    }

    override fun onResume() {
        super.onResume()
        foreground = true
        updateNotificationState()
        requestInitialNotificationPermission()
    }

    internal fun chooseTheme(mode: ThemeMode) {
        themeMode = mode
        services.settings.edit().putString("theme_mode", mode.name).apply()
        requestInitialNotificationPermission()
    }

    private fun requestInitialNotificationPermission() {
        if (foreground && themeMode != null && !locked && Build.VERSION.SDK_INT >= 33 && !notificationsAllowed &&
            !services.settings.getBoolean("notification_prompt_shown", false)) {
            enableNotifications()
        }
    }

    override fun onPause() {
        foreground = false
        super.onPause()
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
        refreshIssue = null
        val cached = history.find { it.key == query.storageKey }?.freight.orEmpty()
        if (cached.isNotEmpty()) page = ResultPage(query, cached, true)
        if (cached.isNotEmpty() && (cachedOnly || !canReachInternet())) return
        message = null
        if (services.signedIn.value) refresh(query)
        else beginChallenge(query.isFnr, query)
    }

    internal fun refresh(query: Query, automatic: Boolean = false, notifyOnRefresh: Boolean = false) {
        if (loading || automatic && (page?.query != query || !foreground || locked || challenge != null ||
                services.liveTracking.value?.query == query)) return
        if (!automatic) {
            message = null
            refreshIssue = null
        }
        if (!canReachInternet()) {
            page = page?.copy(cached = true)
            if (automatic) refreshIssue = query to "Live refresh paused · offline"
            else message = if (page == null) "No internet connection. Connect and retry the search."
                else "Offline. Showing the last retrieved freight result."
            return
        }
        if (!services.signedIn.value) {
            if (automatic) refreshIssue = query to "Live refresh paused · sign-in required"
            else beginChallenge(query.isFnr, query, notifyOnRefresh)
            return
        }
        execute(quietQuery = query.takeIf { automatic }) {
            try {
                val result = services.networkLock.withLock {
                    try {
                        val fresh = services.client.enquiry(query)
                        services.persistSession()
                        recordRefresh(query, fresh, notifyOnRefresh)
                    } catch (e: FoisError.Authentication) {
                        services.forgetSession()
                        throw e
                    }
                }
                withContext(Dispatchers.Main) {
                    if (!automatic || page?.query == query) show(query, result)
                }
            } catch (_: FoisError.Authentication) {
                if (automatic) {
                    withContext(Dispatchers.Main) {
                        if (page?.query == query) refreshIssue = query to "Live refresh paused · sign-in required"
                    }
                    return@execute
                }
                withContext(Dispatchers.Main) {
                    message = "Your ECBS session ended. Sign in again to continue."
                    initializeChallenge(false, query, notifyOnRefresh)
                }
                loadChallenge(reset = true)
            }
        }
    }

    internal fun beginChallenge(guest: Boolean, query: Query? = null, notifyOnRefresh: Boolean = false) {
        if (loading) return
        initializeChallenge(guest, query, notifyOnRefresh)
        execute { loadChallenge(reset = true) }
    }

    private fun initializeChallenge(guest: Boolean, query: Query?, notifyOnRefresh: Boolean) {
        challenge = Challenge(guest, query, notifyOnRefresh = notifyOnRefresh)
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
                        recordRefresh(query, listOf(services.client.publicFnr(query.key, answer.trim())), current.notifyOnRefresh)
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
                    if (!current.guest) message = if (current.query == null) "Signed in to ECBS." else null
                }
                val query = current.query
                if (query != null) {
                    if (result != null) show(query, result)
                    else {
                        val authorized = services.networkLock.withLock {
                            try {
                                val rows = services.client.enquiry(query)
                                services.persistSession()
                                recordRefresh(query, rows, current.notifyOnRefresh)
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

    private fun recordRefresh(query: Query, rows: List<Freight>, notifyOnRefresh: Boolean): List<Freight> {
        val key = query.storageKey
        val previous = services.store.cached(key)
        val updated = services.store.record(key, rows)
        Tracking.notifyChanges(this, services, key, previous, updated)
        if (notifyOnRefresh) {
            Tracking.notify(this, services, key, Alert.REFRESH, notificationArrivals(updated),
                updated.maxOf(Freight::retrieved).toString())
        }
        if (query.isFnr && updated.firstOrNull()?.arrived != null) services.store.pause(key)
        return updated
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

    internal fun startBackgroundLive(query: Query, freight: List<Freight>) {
        if (!foreground || !services.signedIn.value) return
        if (!notificationsAllowed) {
            enableNotifications()
            message = "Allow notifications, then tap Keep live in background again."
            return
        }
        try {
            ContextCompat.startForegroundService(this, Intent(this, LiveTrackingService::class.java)
                .putExtra(LiveTrackingService.QUERY, query.storageKey)
                .putExtra(LiveTrackingService.ARRIVAL, notificationArrivals(freight)))
        } catch (_: IllegalStateException) {
            message = "Android could not start background Live tracking. Try again while the app is open."
        } catch (_: SecurityException) {
            message = "Android blocked background Live tracking."
        }
    }

    internal fun stopBackgroundLive() {
        stopService(Intent(this, LiveTrackingService::class.java))
    }

    internal fun logout() {
        stopBackgroundLive()
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
        if (item.tracking && !notificationsAllowed) enableNotifications()
        lifecycleScope.launch(Dispatchers.IO) {
            services.store.save(item)
            Tracking.sync(this@MainActivity, services)
        }
    }

    internal fun delete(item: Saved) {
        if (services.liveTracking.value?.query?.storageKey == item.key) stopBackgroundLive()
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    services.store.delete(item.key)
                    Tracking.sync(this@MainActivity, services)
                }
                message = "Freight removed from Saved & Tracking."
            } catch (_: android.database.SQLException) {
                message = "Could not remove saved freight. Try again."
            }
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
        if (Build.VERSION.SDK_INT < 33) Toast.makeText(this, "Copied to clipboard", Toast.LENGTH_SHORT).show()
    }
    internal fun paste(): String = getSystemService(ClipboardManager::class.java).primaryClip?.takeIf { it.itemCount > 0 }
        ?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
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

    private fun execute(quietQuery: Query? = null, task: suspend () -> Unit) {
        if (loading) return
        automaticRefresh = quietQuery != null
        loading = true
        lifecycleScope.launch {
            try { withContext(Dispatchers.IO) { task() } }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (quietQuery != null) {
                    if (page?.query == quietQuery) refreshIssue = quietQuery to "Live refresh paused · tap Refresh to retry"
                } else message = when (e) {
                    is FoisError -> e.message
                    is SocketTimeoutException -> "FOIS timed out. Try again."
                    is UnknownHostException -> "FOIS could not be reached. Check your internet connection."
                    is SSLException -> "Android could not verify the FOIS HTTPS connection."
                    is IOException -> "The operation could not finish. Check your connection and try again."
                    else -> "The operation could not finish. Try again."
                }
            } finally {
                loading = false
                automaticRefresh = false
            }
        }
    }
}
