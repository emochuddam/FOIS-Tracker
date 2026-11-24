package dev.foistracker

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
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

private val actionGradient = Brush.horizontalGradient(listOf(Color(0xFF106FCA), Color(0xFF3920EB)))
private val LocalDarkAppearance = compositionLocalOf { false }
private val darkActionGradient = Brush.horizontalGradient(listOf(Color(0xFF7544D7), Color(0xFF5445CC)))
private val light = lightColorScheme(
    primary = Color(0xFF3325CA), onPrimary = Color.White, primaryContainer = Color(0xFFE7E8FF),
    onPrimaryContainer = Color(0xFF211680), secondary = Color(0xFF485671), secondaryContainer = Color(0xFFE5EBF6),
    onSecondaryContainer = Color(0xFF24314E), background = Color(0xFFDFE8EF),
    onBackground = Color(0xFF202638), surface = Color(0xFFFCFCFF), onSurface = Color(0xFF202638),
    surfaceVariant = Color(0xFFE5E9F1), onSurfaceVariant = Color(0xFF465366),
    surfaceContainer = Color(0xFFF0F3FA), surfaceContainerHigh = Color(0xFFE9EDF7), outline = Color(0xFF677487)
)
private val dark = darkColorScheme(
    primary = Color(0xFFBF9DFF), onPrimary = Color(0xFF33176D), primaryContainer = Color(0xFF342458),
    onPrimaryContainer = Color(0xFFE8D9FF), secondary = Color(0xFFA8BFD6), secondaryContainer = Color(0xFF142B40),
    onSecondaryContainer = Color(0xFFDBE9F7), background = Color(0xFF252C3B),
    onBackground = Color(0xFFF3F7FF), surface = Color(0xFF0E1927), onSurface = Color(0xFFF3F7FF),
    surfaceVariant = Color(0xFF192C3F), onSurfaceVariant = Color(0xFFABC0D5),
    surfaceContainer = Color(0xFF112234), surfaceContainerHigh = Color(0xFF172B3F), outline = Color(0xFF7894B0)
)
private val lightTypography = trackerTypography(FontFamily(
    Font(R.font.montserrat_regular), Font(R.font.montserrat_semibold, FontWeight.SemiBold),
    Font(R.font.montserrat_bold, FontWeight.Bold)
))
private val darkTypography = trackerTypography(FontFamily(
    Font(R.font.prompt_regular), Font(R.font.prompt_semibold, FontWeight.SemiBold),
    Font(R.font.prompt_bold, FontWeight.Bold)
))

private fun trackerTypography(family: FontFamily) = Typography().run {
    copy(
        displayLarge = displayLarge.copy(fontFamily = family), displayMedium = displayMedium.copy(fontFamily = family),
        displaySmall = displaySmall.copy(fontFamily = family), headlineLarge = headlineLarge.copy(fontFamily = family),
        headlineMedium = headlineMedium.copy(fontFamily = family), headlineSmall = headlineSmall.copy(fontFamily = family),
        titleLarge = titleLarge.copy(fontFamily = family), titleMedium = titleMedium.copy(fontFamily = family),
        titleSmall = titleSmall.copy(fontFamily = family), bodyLarge = bodyLarge.copy(fontFamily = family),
        bodyMedium = bodyMedium.copy(fontFamily = family), bodySmall = bodySmall.copy(fontFamily = family),
        labelLarge = labelLarge.copy(fontFamily = family), labelMedium = labelMedium.copy(fontFamily = family),
        labelSmall = labelSmall.copy(fontFamily = family)
    )
}
private val tabs = listOf("Home", "Search", "Tracking", "History", "Settings")
private val icons = listOf(Icons.Default.Home, Icons.Default.Search, Icons.Default.LocationOn, Icons.AutoMirrored.Filled.List, Icons.Default.Settings)
@OptIn(ExperimentalMaterial3Api::class)
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
    val backdrop = remember(darkMode) {
        Brush.linearGradient(
            if (darkMode) listOf(Color(0xFF272D3F), Color(0xFF212632))
            else listOf(Color(0xFFD0DEE4), Color(0xFFEBEFF9))
        )
    }
    CompositionLocalProvider(LocalDarkAppearance provides darkMode) {
        MaterialTheme(colorScheme = colors, typography = if (darkMode) darkTypography else lightTypography) {
            Box(Modifier.fillMaxSize().background(backdrop)) {
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
                        val page = app.page
                        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            if (challenge != null || page != null) {
                                IconButton(onClick = { if (!app.loading || app.automaticRefresh) { if (challenge != null) app.closeChallenge() else app.page = null } }) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                                }
                            }
                            Brand(Modifier.weight(1f))
                            if (page != null && challenge == null) {
                                IconButton(onClick = { app.refresh(page.query, notifyOnRefresh = true) }, enabled = !app.loading) {
                                    Icon(Icons.Default.Refresh, "Refresh freight")
                                }
                            }
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
                        AnimatedContent(
                            targetState = Triple(app.tab, page, challenge),
                            modifier = Modifier.weight(1f),
                            contentKey = { (tab, result, verification) ->
                                when {
                                    result != null -> "result/${result.query.storageKey}"
                                    verification != null -> "verification"
                                    else -> "tab/$tab"
                                }
                            },
                            transitionSpec = {
                                val direction = if (targetState.first < initialState.first) -1 else 1
                                (fadeIn(tween(200)) + slideInHorizontally(tween(240)) { it * direction / 20 }) togetherWith
                                    (fadeOut(tween(130)) + slideOutHorizontally(tween(200)) { -it * direction / 24 })
                            },
                            label = "Tracker screens"
                        ) { (tab, result, verification) ->
                            when {
                                result != null -> FreightUi(app, result)
                                verification != null -> ChallengeUi(app, verification)
                                tab == 0 -> Home(app, signedIn)
                                tab == 1 -> Search(app, signedIn)
                                tab == 2 -> SavedScreen(app, signedIn)
                                tab == 3 -> History(app)
                                else -> Preferences(app, signedIn)
                            }
                        }
                        if (challenge != null && page != null) {
                            Dialog(onDismissRequest = { if (!app.loading) app.closeChallenge() },
                                properties = DialogProperties(usePlatformDefaultWidth = false)) {
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
                        if (challenge == null && page == null) Navigation(app.tab) { app.tab = it; app.message = null }
                    }
                }
            }
            if (app.themeMode == null && !app.locked && app.challenge == null) {
                AlertDialog(
                    onDismissRequest = { app.chooseTheme(ThemeMode.SYSTEM) },
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
        Text("FOIS Tracker", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
private fun ActionButton(
    label: String, enabled: Boolean = true, modifier: Modifier = Modifier, action: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (pressed && enabled) 0.97f else 1f,
        animationSpec = spring(dampingRatio = 0.86f, stiffness = 320f),
        label = "Button press"
    )
    val elevation by androidx.compose.animation.core.animateDpAsState(
        if (!enabled) 0.dp else if (pressed) 2.dp else 6.dp,
        label = "Button shadow"
    )
    val shape = RoundedCornerShape(50)
    val ink = MaterialTheme.colorScheme
    Button(
        onClick = action,
        enabled = enabled,
        interactionSource = interaction,
        shape = shape,
        colors = ButtonDefaults.buttonColors(
            containerColor = Color.Transparent, contentColor = Color.White,
            disabledContainerColor = ink.surfaceVariant, disabledContentColor = ink.onSurfaceVariant
        ),
        modifier = modifier.heightIn(min = 48.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .shadow(elevation, shape)
            .then(if (enabled) Modifier.background(if (LocalDarkAppearance.current) darkActionGradient else actionGradient, shape) else Modifier)
    ) { Text(label, fontWeight = FontWeight.SemiBold) }
}

@Composable
private fun Navigation(selected: Int, select: (Int) -> Unit) {
    val shape = RoundedCornerShape(27.dp)
    val colors = MaterialTheme.colorScheme
    NavigationBar(
        modifier = Modifier.padding(horizontal = 14.dp, vertical = 5.dp).shadow(8.dp, shape).clip(shape),
        containerColor = colors.surface, windowInsets = WindowInsets(0)
    ) {
        tabs.forEachIndexed { index, title ->
            val active = index == selected
            val iconScale by animateFloatAsState(if (active) 1.12f else 1f,
                animationSpec = spring(dampingRatio = 0.8f, stiffness = 330f), label = "$title tab")
            NavigationBarItem(
                selected = active,
                onClick = { select(index) },
                icon = { Icon(icons[index], null, Modifier.graphicsLayer { scaleX = iconScale; scaleY = iconScale }) },
                label = { Text(title, maxLines = 1) },
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
    val sheen = remember(colors) { Brush.horizontalGradient(listOf(colors.surface, colors.surfaceContainer)) }
    Card(
        modifier = modifier.fillMaxWidth().shadow(6.dp, RoundedCornerShape(27.dp)),
        shape = RoundedCornerShape(27.dp),
        colors = CardDefaults.cardColors(containerColor = colors.surface),
        border = BorderStroke(1.dp, if (LocalDarkAppearance.current) colors.outline.copy(alpha = 0.16f) else colors.surface.copy(alpha = 0.8f))
    ) {
        Column(Modifier.fillMaxWidth().background(sheen).padding(17.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp), content = content)
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
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(18.dp, 10.dp),
        verticalArrangement = Arrangement.spacedBy(13.dp), content = content)
}

@Composable
private fun Home(app: MainActivity, signedIn: Boolean) {
    Feed {
        item {
            Panel {
                Label("RAIL FREIGHT ENQUIRY")
                Text("Track your freight", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
                SearchInput(app)
                ActionButton("Search freight", !app.loading) { app.search() }
            }
        }
        item {
            Panel {
                Label("ECBS ACCOUNT")
                Text(if (signedIn) "ECBS session available" else "Guest mode", style = MaterialTheme.typography.titleLarge)
                Text(if (signedIn) "${app.saved.count { it.tracking }} consignments scheduled for tracking"
                    else "Sign in for station-pair enquiries and automatic tracking.")
                if (!signedIn) TextButton(onClick = { app.beginChallenge(false) }, enabled = !app.loading) { Text("Sign in to ECBS") }
            }
        }
        if (app.saved.isNotEmpty()) {
            item { Title("Saved freight") }
            items(app.saved.take(4), key = { "saved/${it.key}" }) { item ->
                EntryTile(item.name.ifBlank { Query.fromStorage(item.key).key },
                    if (item.tracking) "Automatic tracking" else "Saved enquiry") { app.open(Query.fromStorage(item.key)) }
            }
        }
        if (app.history.isNotEmpty()) {
            item { Title("Recent searches") }
            items(app.history.take(5), key = { "history/${it.key}" }) { entry ->
                EntryTile(Query.fromStorage(entry.key).key, displayTime(entry.updated)) { app.open(Query.fromStorage(entry.key), true) }
            }
        }
    }
}

@Composable
private fun Search(app: MainActivity, signedIn: Boolean) {
    val initialRoute = remember { Query.parse(app.queryText)?.takeUnless(Query::isFnr) }
    var route by remember { mutableStateOf(initialRoute != null) }
    var origin by remember { mutableStateOf(initialRoute?.key?.substringBefore(' ').orEmpty()) }
    var dest by remember { mutableStateOf(initialRoute?.key?.substringAfter(' ').orEmpty()) }
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
                } else SearchInput(app)
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
                        else { app.queryText = parsed.key; app.open(parsed) }
                    } else app.search()
                }
            }
        }
        recent(app)
    }
}

private fun LazyListScope.recent(app: MainActivity) {
    if (app.history.isNotEmpty()) {
        item { Title("Recent searches") }
        items(app.history.take(10), key = { it.key }) { entry ->
            EntryTile(Query.fromStorage(entry.key).key, displayTime(entry.updated)) { app.open(Query.fromStorage(entry.key), true) }
        }
    }
}

@Composable
private fun SearchInput(app: MainActivity) {
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    OutlinedTextField(app.queryText, { app.queryText = it.take(90) }, Modifier.fillMaxWidth(),
        label = { Text("FNR or station pair") }, placeholder = { Text("11 digits or RMN CFS") },
        singleLine = true, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = {
            focus.clearFocus()
            keyboard?.hide()
            app.search()
        }), trailingIcon = {
            if (app.queryText.isNotEmpty()) IconButton(onClick = { app.queryText = "" }) { Icon(Icons.Default.Clear, "Clear search") }
        })
    TextButton(onClick = { app.queryText = app.paste().take(90) }) { Text("Paste from clipboard") }
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
    var rememberUser by remember(app.remembered) { mutableStateOf(app.remembered.isNotEmpty()) }
    var savePassword by remember(app.storedPassword) { mutableStateOf(app.storedPassword.isNotEmpty()) }
    Feed {
        item {
            Panel {
                Label(if (challenge.guest) "PUBLIC ENQUIRY" else "AUTHORIZED ECBS ACCESS")
                Title(if (challenge.guest) "Verify your FNR enquiry" else "Sign in to FOIS")
                challenge.query?.let { Text("Enquiry · ${it.key}", fontFamily = FontFamily.Monospace) }
                if (!challenge.guest) {
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
                if (!challenge.guest) {
                    SettingSwitch("Remember username", rememberUser) { rememberUser = it }
                    SettingSwitch("Store password on this device", savePassword) { savePassword = it }
                    if (savePassword) Text("Encrypted with Android Keystore. You must still complete FOIS verification.")
                }
                ActionButton(if (challenge.guest) "Submit enquiry" else "Secure sign in", !app.loading && bitmap != null) {
                    app.submit(username, password, answer, rememberUser, savePassword)
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
                EntryTile(item.name.ifBlank { query.key }, query.key + if (item.favorite) " · Favourite" else "") { app.open(query) }
                if (freight != null) {
                    RouteSummary(freight, query, compact = true)
                    Text("ETA · ${displayFreightTime(freight.eta)}")
                    Text("Last reported · ${freight.get("Last reported station")}")
                }
                if (query.isFnr) SettingSwitch("Automatic tracking", item.tracking) { app.save(item.copy(tracking = it)) }
                Row {
                    TextButton(onClick = { editing = item }) { Text("Edit") }
                    TextButton(onClick = { deleting = item }) { Text("Delete") }
                }
            }
        }
    }
    editing?.let { item -> SavedDialog(app, item, initialKey = draftKey) { editing = null } }
    deleting?.let { item ->
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text("Delete saved freight?") },
            text = { Text(item.name.ifBlank { Query.fromStorage(item.key).key }) },
            confirmButton = { TextButton(onClick = { app.delete(item); deleting = null }) { Text("Delete") } },
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
    AlertDialog(onDismissRequest = dismiss, title = { Text(if (item.key.isBlank()) "Add saved freight" else "Edit saved freight") },
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
private fun History(app: MainActivity) {
    var completed by remember { mutableStateOf(false) }
    val results = app.history.filter { !completed || it.freight.any { item -> item.arrived != null } }
    Feed {
        item {
            Panel {
                Title("Enquiry history")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(!completed, { completed = false }, { Text("All enquiries") })
                    FilterChip(completed, { completed = true }, { Text("Completed") })
                }
            }
        }
        if (results.isEmpty()) item { Text("No matching cached enquiries.") }
        items(results, key = { it.key }) { item ->
            EntryTile(Query.fromStorage(item.key).key,
                "Cached · ${displayTime(item.updated)} · ${item.freight.size} result(s)") {
                app.open(Query.fromStorage(item.key), true)
            }
        }
    }
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
    var clear by remember { mutableStateOf(false) }
    Feed {
        item {
            Panel {
                Title("Settings")
                Label("THEME")
                ThemeChoices(app.themeMode, app::chooseTheme)
                Label("ECBS ACCOUNT")
                Text(if (signedIn) "ECBS session available" else "Guest")
                ActionButton(if (signedIn) "Sign out" else "Sign in", !app.loading) {
                    if (signedIn) app.logout() else app.beginChallenge(false)
                }
                SettingSwitch("Fingerprint / biometric app lock", app.biometricEnabled) {
                    if (it) app.authenticate(true) else app.disableBiometric()
                }
            }
        }
        item {
            Panel {
                Label("BACKGROUND REFRESH")
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(15L, 30L, 60L, 120L).forEach { min ->
                        FilterChip(app.interval == min, { app.changeInterval(min) }, { Text("$min min") })
                    }
                }
                Text("Android schedules background work and may delay refresh to conserve battery. Automatic checks require a valid ECBS session.")
            }
        }
        item {
            Panel {
                Label("NOTIFICATIONS")
                if (!app.notificationsAllowed) TextButton(onClick = app::enableNotifications) { Text("Enable notifications") }
                Text("Set sound and vibration for each channel in Android.")
                Alert.entries.forEach { alert ->
                    val key = "alert_${alert.name}"
                    var enabled by remember { mutableStateOf(app.services.settings.getBoolean(key, true)) }
                    SettingSwitch(alert.label, enabled) {
                        enabled = it
                        app.services.settings.edit().putBoolean(key, it).apply()
                    }
                    TextButton(onClick = { app.channelSettings(alert) }) { Text("${alert.label} channel settings") }
                }
            }
        }
        item {
            Panel {
                Label("STORAGE")
                TextButton(onClick = { clear = true }) { Text("Clear cache and search history") }
                Label("ABOUT")
                Text("FOIS Tracker ${BuildConfig.VERSION_NAME}")
                Text("Independent application. Indian Railways does not endorse or operate this app.")
                Text("Journey positions reflect last reported station records from FOIS.")
            }
        }
    }
    if (clear) AlertDialog(onDismissRequest = { clear = false }, title = { Text("Clear cached enquiries?") },
        text = { Text("Previously retrieved freight and ETA history will be removed.") },
        confirmButton = { TextButton(onClick = { clear = false; app.clearHistory() }) { Text("Clear") } },
        dismissButton = { TextButton(onClick = { clear = false }) { Text("Cancel") } })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FreightUi(app: MainActivity, page: ResultPage) {
    val freight = page.freight.getOrNull(page.selected) ?: return
    val distance = freight.values["Remaining distance"]
    val distanceColor = if (distance != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    val distanceText = when {
        distance == null -> "Not Reported"
        distance.any(Char::isLetter) -> distance
        else -> "$distance km"
    }
    val signedIn by app.services.signedIn.collectAsState()
    val liveTracking by app.services.liveTracking.collectAsState()
    val backgroundLive = liveTracking?.takeIf { it.query == page.query }
    val active = signedIn && app.foreground && !app.locked && app.challenge == null && freight.arrived == null
    val refreshIssue = app.refreshIssue?.takeIf { it.first == page.query }?.second
    val polling = active && backgroundLive == null && refreshIssue == null && app.message == null && !app.loading
    var elapsed by remember(freight.retrieved) { mutableLongStateOf(0) }
    LaunchedEffect(freight.retrieved, app.foreground, page.cached) {
        while (app.foreground && !page.cached) {
            elapsed = ((System.currentTimeMillis() - freight.retrieved) / 1000).coerceAtLeast(0)
            if (elapsed >= 60) break
            delay(1000)
        }
    }
    LaunchedEffect(page.query.storageKey, freight.retrieved, polling) {
        if (polling) {
            // Start the next interval after the previous request finishes.
            delay(1_000)
            app.refresh(page.query, automatic = true)
        }
    }
    var editing by remember { mutableStateOf<Saved?>(null) }
    val item = app.saved.find { it.key == page.query.storageKey }
    PullToRefreshBox(isRefreshing = app.loading && !app.automaticRefresh, onRefresh = { app.refresh(page.query, notifyOnRefresh = true) }, modifier = Modifier.fillMaxSize()) {
        Feed {
            item {
                Panel {
                    Label("FNR · ${freight.fnr.ifBlank { page.query.key.takeIf { page.query.isFnr } ?: "Not Reported" }}")
                    if (freight.arrived == null || backgroundLive != null) {
                        ActionButton(
                            if (backgroundLive != null) "Stop background Live" else "Keep live in background",
                            enabled = app.challenge == null && (backgroundLive != null || !app.loading || app.automaticRefresh),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            if (backgroundLive != null) app.stopBackgroundLive()
                            else if (signedIn) app.startBackgroundLive(page.query, page.freight)
                            else app.beginChallenge(false, page.query)
                        }
                    }
                    Label(if (freight.arrived == null) "ESTIMATED ARRIVAL" else "CONFIRMED ARRIVAL")
                    AnimatedContent(
                        targetState = displayFreightTime(freight.arrived ?: freight.eta),
                        transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(110)) },
                        label = "Arrival time"
                    ) { arrival ->
                        Text(arrival, fontSize = 40.sp, lineHeight = 44.sp,
                            fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    }
                    Label("REMAINING DISTANCE")
                    Text(distanceText, fontSize = 34.sp, lineHeight = 38.sp, fontWeight = FontWeight.Bold, color = distanceColor)
                    if (freight.arrived != null && freight.eta != null) Text("Last ETA · ${displayFreightTime(freight.eta)}")
                    val previous = freightTime(freight.previousEta)
                    val current = freightTime(freight.eta)
                    if (freight.previousEta != null) {
                        Text("Previous ETA · ${displayFreightTime(freight.previousEta)}")
                        if (freight.eta != null && freight.eta != freight.previousEta) {
                            Text("Revised ETA · ${displayFreightTime(freight.eta)}")
                        }
                        if (previous != null && current != null) {
                            val minutes = Duration.between(previous, current).toMinutes()
                            if (minutes != 0L) Text("${durationLabel(kotlin.math.abs(minutes))} ${if (minutes > 0) "later" else "earlier"}",
                                color = if (minutes > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                        }
                    }
                    if (current != null && freight.arrived == null) {
                        val remaining = Duration.between(Instant.now(), current).toMinutes()
                        Text(if (remaining > 0) "Estimated time remaining · ${durationLabel(remaining)}" else "Estimated arrival time has passed",
                            color = if (remaining > 0) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error)
                    }
                    Text("${if (page.cached) "Cached result · " else ""}${freight.source}", style = MaterialTheme.typography.labelLarge)
                    if (backgroundLive?.paused == false || polling || app.automaticRefresh) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            LiveRefreshLight()
                            Text("Live", style = MaterialTheme.typography.labelLarge)
                        }
                    } else {
                        (backgroundLive?.takeIf { it.paused }?.let { "Background Live is waiting to retry." } ?: refreshIssue)
                            ?.let { Text(it, style = MaterialTheme.typography.labelLarge) }
                    }
                    if (!page.cached && elapsed < 60) Text("Updated ${elapsed}s ago", style = MaterialTheme.typography.bodySmall)
                    Text("Last successful update · ${displayTime(freight.retrieved)}", style = MaterialTheme.typography.bodySmall)
                    if (page.freight.size > 1) {
                        Label("${page.freight.size} MATCHING CONSIGNMENTS")
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            page.freight.forEachIndexed { index, match ->
                                FilterChip(index == page.selected, { app.page = page.copy(selected = index) }, {
                                    Text(match.fnr.ifEmpty { match.values["Rake identification"] ?: "Result ${index + 1}" })
                                })
                            }
                        }
                    }
                    RouteSummary(freight, page.query)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ActionButton(if (item == null) "Save enquiry" else "Edit saved") {
                            editing = item ?: Saved(page.query.storageKey, "", false, false)
                        }
                        ActionButton("Copy report") { app.copy("Freight report", Reports.text(freight)) }
                    }
                    if (freight.fnr.isNotBlank() && !page.query.isFnr) {
                        TextButton(onClick = { app.open(Query(freight.fnr), true) }, enabled = !app.loading) { Text("Open FNR ${freight.fnr}") }
                    }
                }
            }
            item {
                var details by remember(page.query.storageKey, page.selected) { mutableStateOf(true) }
                Panel {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Freight details", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        TextButton(onClick = { details = !details }) { Text(if (details) "Collapse" else "Expand") }
                    }
                    AnimatedVisibility(visible = details,
                        enter = expandVertically(tween(220)) + fadeIn(tween(180)),
                        exit = shrinkVertically(tween(180)) + fadeOut(tween(120))) {
                        Column {
                            (fields + (freight.values.keys - fields.toSet())).distinct().forEach { field ->
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Label(field)
                                        if (field == "Remaining distance") {
                                            Text(distanceText, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = distanceColor)
                                        } else Text(freight.get(field))
                                    }
                                    if (freight.values.containsKey(field)) IconButton(onClick = { app.copy(field, freight.get(field)) }) {
                                        Icon(painterResource(R.drawable.ic_clipboard), "Copy $field")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    editing?.let { SavedDialog(app, it) { editing = null } }
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
    val origin = freight.values["Origin"] ?: query.key.substringBefore(' ').takeUnless { query.isFnr } ?: "Not Reported"
    val destination = freight.values["Destination"] ?: query.key.substringAfter(' ').takeUnless { query.isFnr } ?: "Not Reported"
    if (!compact) Label("ROUTE SUMMARY")
    Text("$origin → $destination", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    if (!compact) {
        Text("Last reported station · ${freight.get("Last reported station")}")
        freight.values["Freight status"]?.let { Text("Status · $it") }
        freight.values["Consignment detail"]?.let { Text("Consignment detail · $it") }
    }
}

private fun durationLabel(minutes: Long): String = when {
    minutes >= 1440 -> "${minutes / 1440}d ${(minutes % 1440) / 60}h"
    minutes >= 60 -> "${minutes / 60}h ${minutes % 60}m"
    else -> "${minutes}m"
}
