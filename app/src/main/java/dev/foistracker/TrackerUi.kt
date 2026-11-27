package dev.foistracker

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.view.WindowCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.Instant

private val LocalDarkAppearance = compositionLocalOf { false }
private val LocalMotionScale = compositionLocalOf { 1f }
private val liveGreen = Color(0xFF39B987)
private val actionBlue = Color(0xFF2467D1)
private val dark = darkColorScheme(
    primary = Color(0xFF91C3FF), onPrimary = Color(0xFF101318), primaryContainer = Color(0xFF182D45),
    onPrimaryContainer = Color(0xFFF4F6FA), secondary = Color(0xFFA1ADBE), secondaryContainer = Color(0xFF303946),
    onSecondaryContainer = Color(0xFFF4F6FA), background = Color(0xFF101318), onBackground = Color(0xFFF4F6FA),
    surface = Color(0xFF1B2028), onSurface = Color(0xFFF4F6FA), surfaceVariant = Color(0xFF303946),
    onSurfaceVariant = Color(0xFFA1ADBE), outline = Color(0xFF303946), surfaceContainer = Color(0xFF1B2028),
    surfaceContainerHigh = Color(0xFF303946), error = Color(0xFFE86C72)
)
private val light = lightColorScheme(
    primary = Color(0xFF155FC5), onPrimary = Color.White, primaryContainer = Color(0xFFDCEBFC),
    onPrimaryContainer = Color(0xFF172337), secondary = Color(0xFF596779), secondaryContainer = Color(0xFFD6DFE9),
    onSecondaryContainer = Color(0xFF172337), background = Color(0xFFF3F5F8), onBackground = Color(0xFF172337),
    surface = Color.White, onSurface = Color(0xFF172337), surfaceVariant = Color(0xFFD6DFE9),
    onSurfaceVariant = Color(0xFF596779), outline = Color(0xFFD6DFE9), surfaceContainer = Color.White,
    surfaceContainerHigh = Color(0xFFDCEBFC), error = Color(0xFFB02434)
)
private val tabs = listOf("Home", "Without Credentials", "Settings")
private val icons = listOf(Icons.Default.Home, Icons.Default.Search, Icons.Default.Settings)
private val numberStyle = androidx.compose.ui.text.TextStyle(fontFeatureSettings = "tnum")
@OptIn(ExperimentalSharedTransitionApi::class)
private val LocalSharedTransition = compositionLocalOf<SharedTransitionScope?> { null }
private val LocalScreenTransition = compositionLocalOf<AnimatedVisibilityScope?> { null }

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun Modifier.sharedFreightNumber(page: ResultPage, index: Int): Modifier {
    val shared = LocalSharedTransition.current ?: return this
    val screen = LocalScreenTransition.current ?: return this
    val motion = LocalMotionScale.current
    if (motion == 0f) return this
    return with(shared) {
        sharedElement(rememberSharedContentState("fnr/${page.query.storageKey}/$index"), screen,
            boundsTransform = { _, _ -> tween((350 * motion).toInt()) })
    }
}

private val SecureDialog = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalSharedTransitionApi::class)
@Composable
fun TrackerApp(app: MainActivity) {
    val darkMode = when (app.themeMode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.SYSTEM, null -> isSystemInDarkTheme()
    }
    val colors = if (darkMode) dark else light
    SideEffect {
        val bars = WindowCompat.getInsetsController(app.window, app.window.decorView)
        bars.isAppearanceLightStatusBars = !darkMode
        bars.isAppearanceLightNavigationBars = !darkMode
    }
    val signedIn by app.services.signedIn.collectAsState()
    val recovery by app.services.recovery.collectAsState()
    LaunchedEffect(recovery, app.foreground, app.loading, app.locked) { app.maybePromptRecovery(recovery) }
    val page = app.page
    val live by app.services.liveTracking.collectAsState()
    val seconds by app.services.intervalSeconds.collectAsState()
    val candidate = page?.takeIf { it.freight.any { item -> item.source != "Public FNR" && item.arrived == null } }
    val polling = candidate != null && app.trackingEnabled && signedIn && app.foreground && !app.locked &&
        app.challenge == null && !app.loading && app.refreshIssue == null && live?.query != candidate.query
    val queryToPoll = candidate?.query?.takeIf { polling }
    LaunchedEffect(queryToPoll, candidate?.freight?.maxOfOrNull(Freight::retrieved), seconds) {
        if (queryToPoll != null) {
            delay(seconds * 1000)
            kotlinx.coroutines.yield()
            app.refresh(queryToPoll, automatic = true)
        }
    }
    val motion = if (app.reducedMotion) 0f else 1f / app.motionSpeed
    val background by animateColorAsState(colors.background, tween((225 * motion).toInt()), label = "Theme background")
    val stateHolder = rememberSaveableStateHolder()
    CompositionLocalProvider(LocalDarkAppearance provides darkMode, LocalMotionScale provides motion) {
        MaterialTheme(colorScheme = colors, typography = Typography()) {
            Box(Modifier.fillMaxSize().background(background)) {
                Column(Modifier.widthIn(max = 820.dp).fillMaxSize().safeDrawingPadding().imePadding()
                    .align(Alignment.TopCenter)) {
                    if (app.locked) {
                        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
                            Brand()
                            Text("Tracker locked", style = MaterialTheme.typography.headlineLarge)
                            Spacer(Modifier.height(12.dp))
                            ActionButton("Unlock") { app.authenticate(false) }
                            app.message?.let { Text(it, Modifier.padding(top = 12.dp)) }
                        }
                    } else {
                        val challenge = app.challenge
                        val page = app.visiblePage
                        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            if (challenge != null || app.searching || app.detailsOpen || app.settingsSection != null) {
                                IconButton(onClick = app::goBack) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                                }
                            }
                            Brand(Modifier.weight(1f))
                            IconButton(onClick = { app.chooseTheme(if (darkMode) ThemeMode.LIGHT else ThemeMode.DARK) }) {
                                Icon(painterResource(R.drawable.ic_theme), if (darkMode) "Use light theme" else "Use dark theme")
                            }
                            if (page != null && challenge == null && app.detailsOpen) {
                                IconButton(onClick = { app.refresh(page.query, notifyOnRefresh = true) }, enabled = !app.loading) {
                                    Icon(Icons.Default.Refresh, "Refresh freight")
                                }
                            }
                        }
                        if (challenge == null && !app.searching && (app.tab == 0 || app.detailsOpen)) {
                            TrackingToolbar(app, signedIn)
                        }
                        app.message?.let { message ->
                            Surface(color = colors.secondaryContainer, shape = RoundedCornerShape(16.dp),
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 3.dp)) {
                                Row(Modifier.padding(start = 13.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(message, Modifier.weight(1f).padding(vertical = 9.dp), style = MaterialTheme.typography.bodyMedium)
                                    IconButton(onClick = { app.message = null }) { Icon(Icons.Default.Close, "Dismiss message") }
                                }
                            }
                        }
                        if (app.loading && !app.automaticRefresh) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 20.dp))
                        val screen = when {
                            challenge != null && page == null -> "verification"
                            app.searching -> "search"
                            app.detailsOpen && page != null -> "freight"
                            else -> "tab/${app.tab}"
                        }
                        SharedTransitionLayout(Modifier.weight(1f)) {
                            val sharedScope = this
                            AnimatedContent(
                                targetState = screen, modifier = Modifier.fillMaxSize(),
                                transitionSpec = {
                                    (fadeIn(tween((290 * motion).toInt())) +
                                        slideInHorizontally(tween((300 * motion).toInt())) { it / 24 }) togetherWith
                                        fadeOut(tween((180 * motion).toInt()))
                                },
                                label = "Tracker screens"
                            ) { destination ->
                                val screenScope = this
                                CompositionLocalProvider(LocalSharedTransition provides sharedScope, LocalScreenTransition provides screenScope) {
                                    stateHolder.SaveableStateProvider(destination) {
                                        when (destination) {
                                            "verification" -> challenge?.let { ChallengeUi(app, it) }
                                            "search" -> Search(app, signedIn)
                                            "freight" -> page?.let { FreightUi(app, it) }
                                            "tab/0" -> Home(app, signedIn)
                                            "tab/1" -> PublicPage(app)
                                            else -> Preferences(app, signedIn)
                                        }
                                    }
                                }
                            }
                        }
                        if (challenge != null && page != null) {
                            Dialog(onDismissRequest = { if (!app.loading) app.closeChallenge() },
                                properties = DialogProperties(usePlatformDefaultWidth = false, securePolicy = SecureFlagPolicy.SecureOn)) {
                                Surface(Modifier.fillMaxWidth(.95f).widthIn(max = 620.dp).fillMaxHeight(.88f),
                                    shape = RoundedCornerShape(28.dp), color = colors.background) {
                                    Column {
                                        Row(Modifier.padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                                            Text(app.message.orEmpty(), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                            IconButton(onClick = app::closeChallenge, enabled = !app.loading) {
                                                Icon(Icons.Default.Close, "Close verification")
                                            }
                                        }
                                        if (app.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                                        ChallengeUi(app, challenge)
                                    }
                                }
                            }
                        }
                        if (challenge == null && !app.searching && !app.detailsOpen && app.settingsSection == null) {
                                Navigation(app.tab) { app.tab = it; app.message = null }
                            }
                    }
                }
            }
            if (app.themeMode == null && !app.locked && app.challenge == null) {
                AlertDialog(
                    onDismissRequest = { app.chooseTheme(ThemeMode.SYSTEM) },
                    properties = SecureDialog,
                    title = { Text("Choose your theme") },
                    text = { ThemeChoices(selected = null, choose = app::chooseTheme) },
                    confirmButton = {}
                )
            }
        }
    }
}

@Composable
private fun Brand(modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(12.dp)) {
            Icon(painterResource(R.drawable.ic_rail), null, modifier = Modifier.padding(8.dp).size(22.dp),
                tint = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        Column {
            Text("FOIS Tracker", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
            Text("RAIL MONITOR", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ActionButton(
    label: String, enabled: Boolean = true, modifier: Modifier = Modifier, action: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val motion = LocalMotionScale.current
    val haptic = LocalHapticFeedback.current
    val scale by animateFloatAsState(if (pressed && enabled && motion != 0f) 0.98f else 1f,
        tween((150 * motion).toInt()), label = "Button press")
    Button(onClick = {
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        action()
    }, enabled = enabled, interactionSource = interaction,
        shape = RoundedCornerShape(15.dp),
        colors = ButtonDefaults.buttonColors(containerColor = actionBlue, contentColor = Color.White),
        modifier = modifier.heightIn(min = 48.dp).graphicsLayer { scaleX = scale; scaleY = scale }
    ) { Text(label, fontWeight = FontWeight.SemiBold) }
}

@Composable
private fun Navigation(selected: Int, select: (Int) -> Unit) {
    val colors = MaterialTheme.colorScheme
    NavigationBar(
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        containerColor = colors.surface, windowInsets = WindowInsets(0)
    ) {
        tabs.forEachIndexed { index, title ->
            val active = index == selected
            val iconScale = if (active) 1.06f else 1f
            NavigationBarItem(
                selected = active,
                onClick = { select(index) },
                icon = { Icon(icons[index], null, Modifier.graphicsLayer { scaleX = iconScale; scaleY = iconScale }) },
                label = { Text(title, maxLines = 2, style = MaterialTheme.typography.labelSmall) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = colors.primary, selectedTextColor = colors.primary,
                    indicatorColor = colors.primaryContainer, unselectedIconColor = colors.onSurfaceVariant,
                    unselectedTextColor = colors.onSurfaceVariant
                )
            )
        }
    }
}

@Composable
private fun Panel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val colors = MaterialTheme.colorScheme
    Card(modifier = modifier.fillMaxWidth(), shape = RoundedCornerShape(15.dp),
        colors = CardDefaults.cardColors(containerColor = colors.surface)) {
        Column(Modifier.fillMaxWidth().padding(13.dp), verticalArrangement = Arrangement.spacedBy(6.dp), content = content)
    }
}

@Composable
private fun Label(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
}
@Composable
private fun Title(text: String) {
    Text(text, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
}
@Composable
private fun Feed(content: LazyListScope.() -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp, 8.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp), content = content)
}

@Composable
private fun Home(app: MainActivity, signedIn: Boolean) {
    val recovery by app.services.recovery.collectAsState()
    Feed {
        item {
            Panel {
                Text(when {
                    recovery != null && app.loading -> "Reconnecting automatically…"
                    recovery != null -> "Session expired · FOIS verification required"
                    signedIn -> "ECBS connected"
                    app.signedOut && app.savedAccount != null -> "Welcome back"
                    else -> "ECBS disconnected"
                }, style = MaterialTheme.typography.titleMedium)
                Text(if (app.hasSavedPassword) "Credentials saved · ${app.savedAccount.orEmpty()}" else "Credentials not saved",
                    style = MaterialTheme.typography.bodySmall)
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    TextButton(onClick = app::loginWithSaved, enabled = !app.loading) {
                        Text(if (signedIn) "Re-login" else if (app.hasSavedPassword) "Login with saved credentials" else "Sign in")
                    }
                    if (signedIn) TextButton(onClick = { app.logout() }) { Text("Sign out") }
                }
            }
        }
        item {
            Panel {
                Title("Freight search")
                ActionButton("Search freight", !app.loading, Modifier.fillMaxWidth()) { app.openSearch() }
            }
        }
        val result = app.page?.takeUnless { it.freight.firstOrNull()?.source == "Public FNR" }
        if (result != null) {
            item { Label(if (result.cached) "CACHED FREIGHT RESULTS" else "ACTIVE FREIGHT RESULTS") }
            items(result.freight.indices.toList(), key = { index ->
                val row = result.freight[index]
                "${result.query.storageKey}/${row.fnr}/$index"
            }) { index ->
                FreightCard(app, result, index)
            }
        }
        recent(app)
    }
}

@Composable
private fun PublicPage(app: MainActivity) {
    var input by rememberSaveable { mutableStateOf("") }
    val result = app.publicPage
    Feed {
        item {
            Panel {
                Title("Without Credentials")
                OutlinedTextField(input, { input = it.filter { ch -> ch in '0'..'9' }.take(11) },
                    Modifier.fillMaxWidth(), label = { Text("11-digit FNR") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number))
                ActionButton("Search freight", input.length == 11 && !app.loading) { app.enquire(Query(input), guest = true) }
            }
        }
        if (result != null) {
            items(result.freight.indices.toList()) { index -> FreightCard(app, result, index) }
            item { TextButton(onClick = { app.refresh(result.query, notifyOnRefresh = true) }) { Text("Refresh freight") } }
        }
    }
}

@Composable
private fun Search(app: MainActivity, signedIn: Boolean) {
    val initial = app.searchDraft
    val initialRoute = initial?.takeUnless(Query::isFnr)
    var route by rememberSaveable(initial?.storageKey) { mutableStateOf(initial?.isFnr != true) }
    var origin by rememberSaveable(initial?.storageKey) { mutableStateOf(initialRoute?.key?.substringBefore(' ') ?: "RMN") }
    var dest by rememberSaveable(initial?.storageKey) { mutableStateOf(initialRoute?.key?.substringAfter(' ') ?: "CFS") }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val learned = app.history.asSequence().flatMap { it.freight.asSequence() }
        .flatMap { sequenceOf(it.values["Origin"], it.values["Destination"]) }.filterNotNull()
        .mapNotNull { station -> Regex("""(.+?)\s*\(([A-Z0-9]{2,5})\)""").matchEntire(station)?.let { it.groupValues[2] to it.groupValues[1] } }
        .toList()
    Feed {
        item {
            Panel {
                Title("Freight search")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(!route, { route = false }, label = { Text("FNR enquiry") })
                    FilterChip(route, { route = true }, label = { Text("Station pair") })
                }
                if (route) {
                    StationField(app, "Origin station", origin, stations + learned) { origin = it }
                    StationField(app, "Destination station", dest, stations + learned) { dest = it }
                    TextButton(onClick = { val first = origin; origin = dest; dest = first }) { Text("Swap stations") }
                    OutlinedTextField(app.consignor, { app.consignor = it.take(30) }, Modifier.fillMaxWidth(),
                        label = { Text("Consignor filter") }, singleLine = true)
                    Text(if (signedIn) "Uses authorized ECBS freight enquiries." else "ECBS sign-in required. Your search will resume after sign-in.")
                    Row {
                        TextButton(onClick = {
                            val parsed = Query.parse(app.paste(), app.consignor)
                            if (parsed == null || parsed.isFnr) app.message = "Clipboard does not contain a station pair."
                            else { origin = parsed.key.substringBefore(' '); dest = parsed.key.substringAfter(' ') }
                        }) { Text("Paste route") }
                        TextButton(onClick = { origin = ""; dest = "" }) { Text("Clear") }
                    }
                } else {
                    SearchInput(app)
                    Row {
                        TextButton(onClick = {
                            val copied = app.paste().trim()
                            if (copied.matches(Regex("[0-9]{11}"))) app.queryText = copied
                            else app.message = "Clipboard does not contain an 11-digit FNR."
                        }) { Text("Paste FNR") }
                        TextButton(onClick = { app.queryText = "" }) { Text("Clear") }
                    }
                }
                ActionButton("Search freight", !app.loading) {
                    focus.clearFocus()
                    keyboard?.hide()
                    if (route) {
                        val stationList = stations + learned
                        fun code(text: String): String {
                            val value = text.trim()
                            return stationList.firstOrNull { it.second.isNotBlank() && it.second.equals(value, true) }
                                ?.first ?: value
                        }
                        val parsed = Query.parse("${code(origin)} ${code(dest)}", app.consignor)
                        if (parsed == null || parsed.isFnr) app.message = "Select two station codes or enter them directly."
                        else { app.queryText = parsed.key; app.enquire(parsed) }
                    } else if (app.queryText.matches(Regex("[0-9]{11}"))) app.enquire(Query(app.queryText))
                    else app.message = "Enter exactly 11 digits for the FNR."
                }
            }
        }
    }
}

private fun LazyListScope.recent(app: MainActivity) {
    item { Title("Recent searches") }
    items(app.recents, key = { "recent/${it.key}" }) { entry ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                EntryTile(Query.fromStorage(entry.key).key, displayTime(entry.updated)) {
                    app.openSearch(Query.fromStorage(entry.key))
                }
            }
            IconButton(onClick = { app.removeRecent(entry) }) { Icon(Icons.Default.Close, "Remove recent search") }
        }
    }
}

@Composable
private fun SearchInput(app: MainActivity) {
    OutlinedTextField(app.queryText, { app.queryText = it.filter { ch -> ch in '0'..'9' }.take(11) }, Modifier.fillMaxWidth(),
        label = { Text("11-digit FNR") }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number))
}

@Composable
private fun StationField(app: MainActivity, label: String, value: String,
    all: List<Pair<String, String>>, update: (String) -> Unit) {
    var suggestions by remember { mutableStateOf(false) }
    var online by remember(value) { mutableStateOf(emptyList<Pair<String, String>>()) }
    var status by remember(value) { mutableStateOf<String?>(null) }
    var loading by remember(value) { mutableStateOf(false) }
    var byCode by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    OutlinedTextField(value, { update(it.take(45)); suggestions = true }, Modifier.fillMaxWidth(),
        label = { Text(label) }, placeholder = { Text("Station code or name") }, singleLine = true)
    if (suggestions && value.isNotBlank()) {
        val available = online + all.filter { (code, name) ->
            code.startsWith(value, true) || name.contains(value, true)
        }
        available.distinctBy { it.first }.take(8).forEach { (code, name) ->
            TextButton(onClick = { update(code); suggestions = false }) {
                Text(if (name.isBlank()) code else "$code · $name")
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            FilterChip(byCode, { byCode = !byCode }, label = { Text(if (byCode) "By code" else "By name") })
            TextButton(enabled = !loading && value.trim().length >= 2, onClick = {
                loading = true
                status = null
                val input = value
                val matchCode = byCode
                val report: (List<Pair<String, String>>, String?) -> Unit = { rows, error ->
                    online = rows
                    status = error
                    loading = false
                }
                scope.launch {
                    try {
                        val rows = withContext(Dispatchers.IO) { app.services.client.findStations(input, matchCode) }
                        report(rows, if (rows.isEmpty()) "No stations returned by FOIS." else null)
                    } catch (_: java.io.IOException) {
                        report(emptyList(), "Station lookup unavailable. You can still enter a station code.")
                    }
                }
            }) { Text(if (loading) "Looking up stations…" else "Find stations on FOIS") }
        }
        status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun ChallengeUi(app: MainActivity, challenge: Challenge) {
    var username by remember(app.remembered) { mutableStateOf(app.remembered) }
    var password by remember(app.storedPassword) { mutableStateOf(app.storedPassword) }
    var answer by remember(challenge.revision) { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    var editCredentials by remember { mutableStateOf(app.replaceCredentials) }
    val reuse = !editCredentials && app.remembered.isNotBlank() && app.storedPassword.isNotBlank()
    Feed {
        item {
            Panel {
                Label(if (challenge.guest) "PUBLIC ENQUIRY" else "AUTHORIZED ECBS ACCESS")
                Title(if (challenge.guest) "Without Credentials" else if (reuse) "FOIS verification" else "Sign in to FOIS")
                challenge.query?.let { Text("Enquiry · ${it.key}", fontFamily = FontFamily.Monospace) }
                if (!challenge.guest && reuse) {
                    Text("Saved account · ${app.savedAccount.orEmpty()}")
                    TextButton(onClick = { editCredentials = true; password = "" }) { Text("Change credentials") }
                }
                if (!challenge.guest && !reuse) {
                    OutlinedTextField(username, { username = it }, Modifier.fillMaxWidth(), label = { Text("FOIS username") },
                        enabled = !app.loading, singleLine = true)
                    OutlinedTextField(password, { password = it }, Modifier.fillMaxWidth(), label = { Text("FOIS password") },
                        enabled = !app.loading, singleLine = true,
                        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Password),
                        trailingIcon = { TextButton(onClick = { visible = !visible }) { Text(if (visible) "Hide" else "Show") } })
                }
                Text("Enter the verification code shown by FOIS.")
                val bitmap = challenge.image
                if (bitmap != null) {
                    Image(bitmap.asImageBitmap(), "FOIS verification image",
                        Modifier.fillMaxWidth().heightIn(min = 66.dp, max = 130.dp).background(Color.White))
                } else if (app.loading) {
                    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth().height(66.dp)) {
                        Box(contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(26.dp)) }
                    }
                }
                TextButton(onClick = app::reloadChallenge, enabled = !app.loading) { Text("Refresh verification image") }
                OutlinedTextField(answer, { answer = it.take(40) }, Modifier.fillMaxWidth(), label = { Text("Verification code") },
                    enabled = !app.loading && bitmap != null, singleLine = true)
                ActionButton(if (challenge.guest) "Search freight" else if (reuse) "Verify and reconnect" else "Secure sign in",
                    !app.loading && bitmap != null) {
                    app.submit(if (reuse) app.remembered else username, if (reuse) app.storedPassword else password, answer)
                }
                if (!challenge.guest) {
                    TextButton(onClick = {
                        val query = challenge.query
                        app.closeChallenge()
                        if (query?.isFnr == true) app.beginChallenge(true, query, challenge.notifyOnRefresh)
                        else if (query != null) app.message = "Station-pair enquiries require ECBS sign-in."
                    }, enabled = !app.loading) { Text("Continue as guest") }
                }
            }
        }
    }
}

@Composable
private fun EntryTile(title: String, subtitle: String, click: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.98f else 1f, label = "Freight tile press")
    Surface(onClick = click, interactionSource = interaction, shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth().graphicsLayer { scaleX = scale; scaleY = scale }) {
        Row(Modifier.padding(horizontal = 17.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
        }
    }
}

@Composable
private fun SavedScreen(app: MainActivity, signedIn: Boolean) {
    var filter by remember { mutableStateOf("") }
    var sort by remember { mutableStateOf("Favourites") }
    var editing by remember { mutableStateOf<Saved?>(null) }
    var deleting by remember { mutableStateOf<Saved?>(null) }
    var draftKey by remember { mutableStateOf("") }
    val list = app.saved.filter {
        it.name.contains(filter, true) || Query.fromStorage(it.key).key.contains(filter, true)
    }.let { items ->
        when (sort) {
            "FNR / route" -> items.sortedBy { it.key }
            "Name" -> items.sortedBy { it.name.lowercase() }
            "Active first" -> items.sortedByDescending { it.tracking }
            else -> items
        }
    }
    Feed {
        item {
            Panel {
                Title("Saved & tracking")
                Text("${app.saved.count { it.tracking }} active · Refresh every ${app.interval} min")
                if (!signedIn) Text("Sign in to ECBS to enable background refresh.")
                OutlinedTextField(filter, { filter = it }, Modifier.fillMaxWidth(), label = { Text("Find saved freight") }, singleLine = true)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    listOf("Favourites", "Active first", "Name", "FNR / route").forEach {
                        FilterChip(sort == it, { sort = it }, { Text(it) })
                    }
                }
                ActionButton("Add saved freight") {
                    draftKey = Query.parse(filter.ifBlank { app.queryText }, app.consignor)?.storageKey.orEmpty()
                    editing = app.saved.find { it.key == draftKey } ?: Saved("", "", false, false)
                }
            }
        }
        if (list.isEmpty()) item { Text("No saved entries match this search.") }
        items(list, key = { it.key }) { item ->
            val query = Query.fromStorage(item.key)
            val freight = app.history.find { it.key == item.key }?.freight?.firstOrNull()
            Panel {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { editing = item }) { Text("Edit") }
                    TextButton(onClick = { deleting = item }) {
                        Text("Remove", color = MaterialTheme.colorScheme.error)
                    }
                }
                EntryTile(item.name.ifBlank { query.key }, query.key + if (item.favorite) " · Favourite" else "") { app.openSaved(query) }
                if (freight != null) {
                    RouteSummary(freight, query, compact = true)
                    Text("ETA · ${displayFreightTime(freight.eta)}")
                    Text("Last reported · ${freight.get("Last reported station").takeUnless { it == "Not Reported" } ?: "Not available"}")
                }
                if (query.isFnr) SettingSwitch("Automatic tracking", item.tracking) { app.save(item.copy(tracking = it)) }
            }
        }
    }
    editing?.let { item -> SavedDialog(app, item, initialKey = draftKey) { editing = null } }
    deleting?.let { item ->
        AlertDialog(properties = SecureDialog, onDismissRequest = { deleting = null }, title = { Text("Remove saved freight?") },
            text = { Text("Remove ${item.name.ifBlank { Query.fromStorage(item.key).key }} from Saved & Tracking?") },
            confirmButton = { TextButton(onClick = { app.delete(item); deleting = null }) { Text("Remove freight") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } })
    }
}

@Composable
private fun SavedDialog(app: MainActivity, item: Saved, initialKey: String = "", dismiss: () -> Unit) {
    val initial = Query.fromStorage(item.key.ifBlank { initialKey })
    var key by remember(initial) { mutableStateOf(initial.key) }
    var name by remember { mutableStateOf(item.name) }
    var favorite by remember { mutableStateOf(item.favorite) }
    var tracking by remember { mutableStateOf(item.tracking) }
    val query = if (item.key.isBlank()) Query.parse(key, initial.consignor.takeIf { initialKey.isNotBlank() } ?: app.consignor) else initial
    AlertDialog(properties = SecureDialog, onDismissRequest = dismiss, title = { Text(if (item.key.isBlank()) "Add saved freight" else "Edit saved freight") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(key, { key = it.take(90) }, label = { Text("FNR or station pair") }, readOnly = item.key.isNotBlank(), singleLine = true)
                OutlinedTextField(name, { name = it.take(80) }, label = { Text("Custom name") }, singleLine = true)
                SettingSwitch("Favourite", favorite) { favorite = it }
                if (query?.isFnr == true) SettingSwitch("Automatic tracking", tracking) { tracking = it }
            }
        }, confirmButton = { TextButton(enabled = query != null, onClick = {
            query?.let { app.save(Saved(it.storageKey, name.trim(), favorite, it.isFnr && tracking)); dismiss() }
        }) { Text("Save") } }, dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } })
}

@Composable
private fun SettingSwitch(label: String, checked: Boolean, update: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked, update)
    }
}

@Composable
private fun ThemeChoices(selected: ThemeMode?, choose: (ThemeMode) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ThemeMode.entries.forEach { mode ->
            FilterChip(selected = selected == mode, onClick = { choose(mode) }, label = { Text(mode.label) })
        }
    }
}

@Composable
private fun Preferences(app: MainActivity, signedIn: Boolean) {
    val categories = listOf("ECBS Account", "Tracking", "Appearance and Motion", "Notifications", "Security", "Storage", "About")
    var confirm by remember { mutableStateOf<String?>(null) }
    var interval by remember { mutableStateOf(false) }
    val seconds by app.services.intervalSeconds.collectAsState()
    val section = app.settingsSection
    if (section == "Storage") {
        Column(Modifier.fillMaxSize()) {
            TextButton(onClick = { confirm = "Clear cache" }) { Text("Clear offline cache and search history") }
            Box(Modifier.weight(1f)) { SavedScreen(app, signedIn) }
        }
    } else Feed {
        if (section == null) {
            item { Title("Settings") }
            items(categories) { category -> EntryTile(category, "") { app.settingsSection = category } }
        } else item {
            Panel {
                Title(section)
                when (section) {
                    "ECBS Account" -> {
                        Text(app.savedAccount ?: "No saved account")
                        Text(if (app.hasSavedPassword) "Password · ••••••••" else "No saved password")
                        Text("Session recovery uses saved credentials and any verification required by FOIS.")
                        TextButton(onClick = app::changeCredentials) { Text("Change credentials") }
                        if (app.hasSavedPassword) TextButton(onClick = { confirm = "Delete saved credentials" }) {
                            Text("Delete saved credentials")
                        }
                    }
                    "Tracking" -> {
                        TextButton(onClick = { interval = true }) { Text("Sync interval · ${intervalLabel(seconds)}") }
                        SettingSwitch("Keep live in background", app.backgroundEnabled, app::setBackground)
                        SettingSwitch("Start after reboot", app.startAfterReboot, app::setBootTracking)
                        Text("Android may defer saved freight checks after reboot. Open the app to resume fast background tracking.")
                    }
                    "Appearance and Motion" -> {
                        ThemeChoices(app.themeMode, app::chooseTheme)
                        Text("System Roboto")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(0.5f, 1f, 1.5f).forEach { speed ->
                                FilterChip(app.motionSpeed == speed, { app.changeMotion(speed = speed) }, { Text("${speed}×") })
                            }
                        }
                        SettingSwitch("Reduced motion", app.reducedMotion) { app.changeMotion(reduced = it) }
                    }
                    "Notifications" -> {
                        if (!app.notificationsAllowed) TextButton(onClick = app::enableNotifications) { Text("Enable notifications") }
                        SettingSwitch("Privacy notifications", app.privateNotifications, app::setNotificationPrivacy)
                        Text(if (app.privateNotifications) "1 freight update received" else "New station movement · Estimated arrival",
                            style = MaterialTheme.typography.bodySmall)
                        Alert.entries.forEach { alert ->
                            val key = "alert_${alert.name}"
                            var enabled by remember { mutableStateOf(app.services.settings.getBoolean(key, alert != Alert.REFRESH)) }
                            SettingSwitch(alert.label, enabled) {
                                enabled = it
                                app.services.settings.edit().putBoolean(key, it).apply()
                            }
                            TextButton(onClick = { app.channelSettings(alert) }) { Text("${alert.label} channel settings") }
                        }
                    }
                    "Security" -> SettingSwitch("Fingerprint / biometric app lock", app.biometricEnabled) {
                        if (it) app.authenticate(true) else app.disableBiometric()
                    }
                    "About" -> {
                        Text("FOIS Tracker ${BuildConfig.VERSION_NAME}")
                        Text("Native Kotlin · Jetpack Compose · Android 16")
                        Text("Independent application. Indian Railways does not endorse or operate this app.")
                    }
                }
            }
        }
    }
    if (interval) IntervalDialog(seconds, app::setSyncSeconds) { interval = false }
    confirm?.let { action ->
        AlertDialog(properties = SecureDialog, onDismissRequest = { confirm = null }, title = { Text("$action?") },
            confirmButton = { TextButton(onClick = {
                confirm = null
                if (action == "Clear cache") app.clearHistory() else app.deleteCredentials()
            }) { Text(action) } }, dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } })
    }
}

@Composable
private fun LiveRefreshLight() {
    val transition = androidx.compose.animation.core.rememberInfiniteTransition(label = "Live refresh")
    val alpha by transition.animateFloat(
        initialValue = 1f, targetValue = 0.25f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            animation = androidx.compose.animation.core.tween(650),
            repeatMode = androidx.compose.animation.core.RepeatMode.Reverse
        ), label = "Green indicator"
    )
    val green = if (LocalDarkAppearance.current) Color(0xFF63E68A) else Color(0xFF198344)
    Box(
        Modifier.size(22.dp).semantics { contentDescription = "Live refresh active" },
        contentAlignment = Alignment.Center
    ) {
        Box(Modifier.size(20.dp).graphicsLayer {
            this.alpha = alpha * 0.22f
            scaleX = 1.18f - alpha * 0.18f
            scaleY = scaleX
        }.background(green, CircleShape))
        Box(Modifier.size(9.dp).background(green, CircleShape))
    }
}

@Composable
private fun RouteSummary(freight: Freight, query: Query, compact: Boolean = false) {
    val origin = freight.values["Origin"] ?: query.key.substringBefore(' ').takeUnless { query.isFnr } ?: "Not available"
    val destination = freight.values["Destination"] ?: query.key.substringAfter(' ').takeUnless { query.isFnr } ?: "Not available"
    if (!compact) Label("ROUTE SUMMARY")
    Text("$origin → $destination", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    if (!compact) {
        listOf("Origin" to origin, "Destination" to destination).forEach { (name, station) ->
            val expanded = reportedStationLabel(station, freight.journey)
            if (expanded != station) Text("$name · $expanded", style = MaterialTheme.typography.bodySmall)
        }
        Text("Last reported station · ${freight.get("Last reported station").takeUnless { it == "Not Reported" } ?: "Not available"}")
        freight.values["Freight status"]?.let { Text("Status · $it") }
        freight.values["Consignment detail"]?.let { Text("Consignment detail · $it") }
    }
}

private fun durationLabel(minutes: Long): String = when {
    minutes >= 1440 -> "${minutes / 1440}d ${(minutes % 1440) / 60}h"
    minutes >= 60 -> "${minutes / 60}h ${minutes % 60}m"
    else -> "${minutes}m"
}

private val intervalPresets = listOf(0L, 1L, 5L, 10L, 15L, 30L, 60L, 120L, 300L, 600L, 900L)

private fun intervalLabel(seconds: Long) = when {
    seconds < 60 -> "$seconds sec"
    seconds % 60 == 0L -> "${seconds / 60} min"
    else -> "${seconds / 60}m ${seconds % 60}s"
}

@Composable
private fun IntervalDialog(seconds: Long, set: (Long) -> Unit, close: () -> Unit) {
    var selected by remember { mutableLongStateOf(seconds) }
    AlertDialog(properties = SecureDialog, onDismissRequest = close,
        title = { Text("Sync interval") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(intervalLabel(selected), style = MaterialTheme.typography.titleLarge.merge(numberStyle))
                Slider(selected.toFloat(), { selected = it.toLong() }, valueRange = 0f..900f, steps = 899)
                intervalPresets.chunked(3).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEach { value ->
                            FilterChip(selected == value, { selected = value }, { Text(intervalLabel(value)) })
                        }
                    }
                }
                Text("At 0 sec, each request starts after the previous one finishes. Server limits and Android background restrictions still apply.")
            }
        },
        confirmButton = { TextButton(onClick = { set(selected); close() }) { Text("Apply") } },
        dismissButton = { TextButton(onClick = close) { Text("Cancel") } })
}

@Composable
private fun TrackingToolbar(app: MainActivity, signedIn: Boolean) {
    val seconds by app.services.intervalSeconds.collectAsState()
    val running = app.trackingEnabled
    val live = running && signedIn && app.page?.freight?.any { it.source != "Public FNR" && it.arrived == null } == true &&
        app.refreshIssue == null
    var intervalOpen by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    Surface(color = colors.surface, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(3.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(.76f).heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center) {
                if (live && LocalMotionScale.current != 0f) LiveRefreshLight()
                Text(if (live) "LIVE" else "IDLE", Modifier.padding(start = 3.dp), maxLines = 1,
                    fontSize = 11.sp, color = if (live) if (LocalDarkAppearance.current) liveGreen else Color(0xFF08754C)
                    else colors.onSurfaceVariant, fontWeight = FontWeight.Bold)
            }
            ToolButton(if (app.backgroundEnabled) "BG ON" else "BG OFF", Modifier.weight(1f),
                if (app.backgroundEnabled) colors.primaryContainer else colors.surfaceVariant, colors.onSurface) {
                app.setBackground(!app.backgroundEnabled)
            }
            ToolButton(intervalLabel(seconds), Modifier.weight(1.22f), colors.surfaceVariant, colors.onSurface, true) { intervalOpen = true }
            ToolButton(if (running) "■ Stop" else "▶ Start", Modifier.weight(1f),
                if (running) (if (LocalDarkAppearance.current) colors.error else Color(0xFFB02434)) else actionBlue,
                if (running && LocalDarkAppearance.current) Color(0xFF101318) else Color.White) { app.setTracking(!running) }
        }
    }
    if (intervalOpen) IntervalDialog(seconds, app::setSyncSeconds) { intervalOpen = false }
}

@Composable
private fun ToolButton(label: String, modifier: Modifier, background: Color, foreground: Color,
                       dropdown: Boolean = false, click: () -> Unit) {
    val interactions = remember { MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    val motion = LocalMotionScale.current
    val scale by animateFloatAsState(if (pressed && motion > 0f) .98f else 1f, tween((145 * motion).toInt()), label = "Toolbar")
    Surface(onClick = click, interactionSource = interactions, color = background, contentColor = foreground,
        shape = RoundedCornerShape(14.dp), modifier = modifier.height(48.dp).graphicsLayer { scaleX = scale; scaleY = scale }) {
        Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, style = numberStyle,
                fontSize = 11.sp, fontWeight = FontWeight.Bold)
            if (dropdown) Icon(Icons.Default.ArrowDropDown, "Change sync interval", Modifier.size(15.dp))
        }
    }
}

private val quickFields = listOf("Locomotive number", "Locomotive type", "Load type", "Consignor", "Commodity", "Freight status")

private fun Freight.reported(field: String) = values[field]?.takeIf { it.isNotBlank() } ?: "Not available"

private fun bookmarkKey(page: ResultPage, freight: Freight) =
    if (page.freight.size > 1 && freight.fnr.matches(Regex("[0-9]{11}"))) Query(freight.fnr).storageKey else page.query.storageKey

@Composable
private fun Bookmark(app: MainActivity, page: ResultPage, freight: Freight) {
    val key = bookmarkKey(page, freight)
    val saved = app.saved.firstOrNull { it.key == key }
    IconButton(onClick = {
        if (saved == null) app.save(Saved(key, "", true, false)) else app.delete(saved)
    }) { Icon(Icons.Default.Star, if (saved == null) "Bookmark freight" else "Remove bookmark",
        tint = if (saved == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary) }
}

@Composable
private fun FreightCard(app: MainActivity, page: ResultPage, index: Int) {
    val freight = page.freight[index]
    var expanded by rememberSaveable { mutableStateOf(true) }
    var options by remember { mutableStateOf(false) }
    val motion = LocalMotionScale.current
    val open = { app.openDetails(page, index) }
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).clickable(role = Role.Button, onClick = open)) {
                Text(freight.fnr.ifBlank { "FNR not available" }, Modifier.sharedFreightNumber(page, index), style = MaterialTheme.typography.headlineSmall.merge(numberStyle),
                    fontWeight = FontWeight.Bold)
                Text("Rake · ${freight.reported("Rake identification")}", style = MaterialTheme.typography.bodyMedium)
                Text("Loading · ${freight.reported("Loading date")}", style = MaterialTheme.typography.bodySmall)
            }
            Bookmark(app, page, freight)
            IconButton(onClick = { options = true }) { Icon(Icons.Default.MoreVert, "Freight actions") }
        }
        Column(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = open), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            EtaPanel(freight)
            Movement(freight, page.query)
        }
        TextButton(onClick = { expanded = !expanded }) {
            Text(if (expanded) "Hide freight details" else "Show freight details")
            Icon(Icons.Default.ArrowDropDown, null)
        }
        AnimatedVisibility(expanded, enter = expandVertically(tween((250 * motion).toInt())),
            exit = shrinkVertically(tween((230 * motion).toInt()))) {
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                quickFields.filterNot { it == "Freight status" }.forEach { field -> Text("$field · ${freight.reported(field)}") }
            }
        }
        TextButton(onClick = open) { Text("Open full details") }
        Text("${if (page.cached) "Cached · " else ""}Last updated · ${displayTime(freight.retrieved)}",
            style = MaterialTheme.typography.bodySmall)
        app.refreshIssue?.takeIf { it.first == page.query }?.let { Text(it.second, style = MaterialTheme.typography.bodySmall) }
    }
    if (options) AlertDialog(properties = SecureDialog, onDismissRequest = { options = false },
        title = { Text("Freight actions") }, text = {
            Column {
                TextButton(onClick = { options = false; app.copy("Freight report", Reports.text(freight)) }) { Text("Copy report") }
                TextButton(onClick = { options = false; app.refresh(page.query, notifyOnRefresh = true) }) { Text("Refresh freight") }
                if (!page.query.isFnr && freight.fnr.matches(Regex("[0-9]{11}"))) {
                    TextButton(onClick = { options = false; app.enquire(Query(freight.fnr)) }) { Text("Open FNR enquiry") }
                }
            }
        },
        confirmButton = { TextButton(onClick = { options = false }) { Text("Done") } })
}

@Composable
private fun EtaPanel(freight: Freight) {
    val colors = MaterialTheme.colorScheme
    val motion = LocalMotionScale.current
    Surface(color = colors.primaryContainer, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Label(if (freight.arrived == null) "REVISED ETA" else "CONFIRMED ARRIVAL")
            AnimatedContent(freight.arrived ?: freight.eta, transitionSpec = {
                fadeIn(tween((400 * motion).toInt())) togetherWith fadeOut(tween((170 * motion).toInt()))
            }, label = "Arrival") {
                Text(it?.let(::displayFreightTime) ?: "Not available", style = MaterialTheme.typography.headlineMedium.merge(numberStyle),
                    fontWeight = FontWeight.Bold, color = colors.primary)
            }
            if (freight.arrived != null) Text("Last ETA · ${freight.eta?.let(::displayFreightTime) ?: "Not available"}")
            Text("Previous ETA · ${freight.previousEta?.let(::displayFreightTime) ?: "Not available"}",
                style = MaterialTheme.typography.bodyMedium.merge(numberStyle))
            val previous = freightTime(freight.previousEta)
            val revised = freightTime(freight.eta)
            if (previous != null && revised != null) {
                val minutes = Duration.between(previous, revised).toMinutes()
                Text(when {
                    minutes > 0 -> "${durationLabel(minutes)} later"
                    minutes < 0 -> "${durationLabel(-minutes)} earlier"
                    else -> "ETA unchanged"
                }, color = when {
                    minutes > 0 -> colors.error
                    minutes < 0 -> if (LocalDarkAppearance.current) liveGreen else Color(0xFF08754C)
                    else -> colors.onSurfaceVariant
                }, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun Movement(freight: Freight, query: Query) {
    val distance = freight.values["Remaining distance"]
    RouteSummary(freight, query)
    Label("REMAINING DISTANCE")
    Text(distance?.let { if (it.any(Char::isLetter)) it else "$it km" } ?: "Not available",
        style = MaterialTheme.typography.headlineMedium.merge(numberStyle), fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.error)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FreightUi(app: MainActivity, page: ResultPage) {
    val freight = page.freight.getOrNull(page.selected) ?: return
    val pager = rememberPagerState(initialPage = app.detailsTab) { 3 }
    val scope = rememberCoroutineScope()
    LaunchedEffect(pager.currentPage) { app.detailsTab = pager.currentPage }
    val motion = LocalMotionScale.current
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(freight.fnr.ifBlank { "Freight details" }, Modifier.weight(1f).sharedFreightNumber(page, page.selected),
                style = MaterialTheme.typography.titleLarge.merge(numberStyle), fontWeight = FontWeight.Bold)
            Bookmark(app, page, freight)
            IconButton(onClick = { app.copy("Freight report", Reports.text(freight)) }) {
                Icon(painterResource(R.drawable.ic_clipboard), "Copy complete freight report")
            }
        }
        PrimaryTabRow(selectedTabIndex = pager.currentPage) {
            listOf("Movement", "ETA", "Details").forEachIndexed { index, label ->
                Tab(selected = pager.currentPage == index, onClick = { scope.launch { pager.animateScrollToPage(index, animationSpec = tween((265 * motion).toInt())) } },
                    text = { Text(label) })
            }
        }
        PullToRefreshBox(isRefreshing = app.loading && !app.automaticRefresh,
            onRefresh = { app.refresh(page.query, notifyOnRefresh = true) }, modifier = Modifier.weight(1f)) {
            HorizontalPager(state = pager, modifier = Modifier.fillMaxSize(), verticalAlignment = Alignment.Top) { section ->
                Feed {
                    item {
                        when (section) {
                            0 -> Panel { Movement(freight, page.query) }
                            1 -> Panel { EtaPanel(freight) }
                            else -> Panel {
                                (fields + quickFields + freight.values.keys).distinct().forEach { field ->
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Column(Modifier.weight(1f)) { Label(field); Text(freight.reported(field)) }
                                        freight.values[field]?.let { value ->
                                            IconButton(onClick = { app.copy(field, value) }) {
                                                Icon(painterResource(R.drawable.ic_clipboard), "Copy $field")
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    item { Text("${if (page.cached) "Cached · " else ""}Last updated · ${displayTime(freight.retrieved)}") }
                }
            }
        }
    }
}
