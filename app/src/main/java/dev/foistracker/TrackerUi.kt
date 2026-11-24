package dev.foistracker

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
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

private val light = lightColorScheme(
    primary = Color(0xFF303030), onPrimary = Color.White, primaryContainer = Color(0xFFE9E9E9),
    onPrimaryContainer = Color(0xFF202020), secondaryContainer = Color(0xFFE5E5E5),
    onSecondaryContainer = Color(0xFF222222), background = Color(0xFFF5F5F5),
    onBackground = Color(0xFF181818), surface = Color.White, onSurface = Color(0xFF181818),
    surfaceVariant = Color(0xFFEAEAEA), onSurfaceVariant = Color(0xFF454545),
    surfaceContainer = Color(0xFFF1F1F1), surfaceContainerHigh = Color(0xFFECECEC), outline = Color(0xFF686868)
)
private val dark = darkColorScheme(
    primary = Color(0xFFE5E5E5), onPrimary = Color(0xFF202020), primaryContainer = Color(0xFF333333),
    onPrimaryContainer = Color(0xFFEAEAEA), secondaryContainer = Color(0xFF363636),
    onSecondaryContainer = Color(0xFFE8E8E8), background = Color(0xFF121212),
    onBackground = Color(0xFFECECEC), surface = Color(0xFF1E1E1E), onSurface = Color(0xFFECECEC),
    surfaceVariant = Color(0xFF343434), onSurfaceVariant = Color(0xFFD0D0D0),
    surfaceContainer = Color(0xFF252525), surfaceContainerHigh = Color(0xFF2D2D2D), outline = Color(0xFFB0B0B0)
)
private val tabs = listOf("Home", "Search", "Tracking", "History", "Settings")
private val icons = listOf(Icons.Default.Home, Icons.Default.Search, Icons.Default.LocationOn, Icons.AutoMirrored.Filled.List, Icons.Default.Settings)
private val stations = listOf("RMN" to "Raman", "NDLS" to "New Delhi", "DLI" to "Delhi Junction",
    "HWH" to "Howrah Junction", "CSMT" to "Chhatrapati Shivaji Maharaj Terminus",
    "MAS" to "MGR Chennai Central", "SBC" to "KSR Bengaluru", "SC" to "Secunderabad Junction",
    "BZA" to "Vijayawada Junction", "VSKP" to "Visakhapatnam", "BBS" to "Bhubaneswar",
    "LKO" to "Lucknow", "CNB" to "Kanpur Central", "PNBE" to "Patna Junction", "BPL" to "Bhopal Junction",
    "NGP" to "Nagpur Junction", "ADI" to "Ahmedabad Junction", "ST" to "Surat", "PUNE" to "Pune Junction",
    "GZB" to "Ghaziabad", "TKD" to "Tughlakabad", "DDU" to "Pt. Deen Dayal Upadhyaya Junction", "CFS" to "")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackerApp(app: MainActivity) {
    val darkMode = isSystemInDarkTheme()
    val colors = if (darkMode) dark else light
    SideEffect {
        val bars = WindowCompat.getInsetsController(app.window, app.window.decorView)
        bars.isAppearanceLightStatusBars = !darkMode
        bars.isAppearanceLightNavigationBars = !darkMode
    }
    val signedIn by app.services.signedIn.collectAsState()
    MaterialTheme(colorScheme = colors) {
        Box(Modifier.fillMaxSize().background(colors.background)) {
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
                            IconButton(onClick = { app.refresh(page.query) }, enabled = !app.loading) {
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
                    Box(Modifier.weight(1f)) {
                        when {
                            page != null -> FreightUi(app, page)
                            challenge != null -> ChallengeUi(app, challenge)
                            else -> AnimatedContent(app.tab, label = "Primary navigation") { tab ->
                                when (tab) {
                                    0 -> Home(app, signedIn)
                                    1 -> Search(app, signedIn)
                                    2 -> SavedScreen(app, signedIn)
                                    3 -> History(app)
                                    else -> Preferences(app, signedIn)
                                }
                            }
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
private fun ActionButton(label: String, enabled: Boolean = true, action: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (pressed && enabled) 0.97f else 1f,
        animationSpec = spring(dampingRatio = 0.8f, stiffness = 500f),
        label = "Button press"
    )
    Button(
        onClick = action,
        enabled = enabled,
        interactionSource = interaction,
        modifier = Modifier.graphicsLayer { scaleX = scale; scaleY = scale },
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 2.dp, pressedElevation = 1.dp)
    ) { Text(label) }
}

@Composable
private fun Navigation(selected: Int, select: (Int) -> Unit) {
    NavigationBar(windowInsets = WindowInsets(0)) {
        tabs.forEachIndexed { index, title ->
            NavigationBarItem(
                selected = index == selected,
                onClick = { select(index) },
                icon = { Icon(icons[index], null) },
                label = { Text(title) }
            )
        }
    }
}

@Composable
private fun Panel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    ElevatedCard(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 3.dp)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp), content = content)
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
                    if (item.tracking) "Automatic tracking" else "Saved enquiry") { app.open(Query.fromStorage(item.key), true) }
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
                        if (query?.isFnr == true) app.beginChallenge(true, query)
                        else if (query != null) app.message = "Station-pair enquiries require ECBS sign-in."
                    }, enabled = !app.loading) { Text("Continue as guest") }
                }
            }
        }
    }
}

@Composable
private fun EntryTile(title: String, subtitle: String, click: () -> Unit) {
    Surface(onClick = click, shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth()) {
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
                ActionButton("Add saved freight") { editing = Saved("", "", false, false) }
            }
        }
        if (list.isEmpty()) item { Text("No saved entries match this search.") }
        items(list, key = { it.key }) { item ->
            val query = Query.fromStorage(item.key)
            val freight = app.history.find { it.key == item.key }?.freight?.firstOrNull()
            Panel {
                EntryTile(item.name.ifBlank { query.key }, query.key + if (item.favorite) " · Favourite" else "") { app.open(query, true) }
                if (freight != null) {
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
    editing?.let { item -> SavedDialog(app, item) { editing = null } }
    deleting?.let { item ->
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text("Delete saved freight?") },
            text = { Text(item.name.ifBlank { Query.fromStorage(item.key).key }) },
            confirmButton = { TextButton(onClick = { app.delete(item); deleting = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } })
    }
}

@Composable
private fun SavedDialog(app: MainActivity, item: Saved, dismiss: () -> Unit) {
    var key by remember { mutableStateOf(Query.fromStorage(item.key).key) }
    var name by remember { mutableStateOf(item.name) }
    var favorite by remember { mutableStateOf(item.favorite) }
    var tracking by remember { mutableStateOf(item.tracking) }
    val query = if (item.key.isBlank()) Query.parse(key, app.consignor) else Query.fromStorage(item.key)
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
private fun Preferences(app: MainActivity, signedIn: Boolean) {
    var clear by remember { mutableStateOf(false) }
    Feed {
        item {
            Panel {
                Title("Settings")
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
    val distanceText = when {
        distance == null -> "Not Reported"
        distance.any(Char::isLetter) -> distance
        else -> "$distance km"
    }
    val signedIn by app.services.signedIn.collectAsState()
    val active = signedIn && app.foreground && !app.locked && app.challenge == null && freight.arrived == null
    val refreshIssue = app.refreshIssue?.takeIf { it.first == page.query }?.second
    val polling = active && refreshIssue == null && app.message == null && !app.loading
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
    var stationFilter by remember(page.query.storageKey, page.selected) { mutableStateOf("") }
    var newest by remember(page.query.storageKey, page.selected) { mutableStateOf(false) }
    var expanded by remember(page.query.storageKey, page.selected) { mutableStateOf(setOf<Journey>()) }
    var editing by remember { mutableStateOf<Saved?>(null) }
    var exporting by remember { mutableStateOf(false) }
    var reportFormat by remember { mutableStateOf(ReportFormat.PDF) }
    val timeline = remember(freight.journey) {
        if (freight.journey.all { freightTime(it.departure) != null || freightTime(it.arrival) != null }) {
            freight.journey.sortedBy { freightTime(it.departure) ?: freightTime(it.arrival) }
        } else freight.journey
    }
    val rows = (if (newest) timeline.asReversed() else timeline).filter {
        listOf(it.station, it.zone, it.division, it.movement).any { value -> value.contains(stationFilter, true) }
    }
    val latest = timeline.lastOrNull { row ->
        val server = freight.values["Last reported station"] ?: return@lastOrNull false
        val code = row.station.substringAfterLast('(', "").substringBefore(')')
        server.equals(row.station, true) || code.isNotBlank() &&
            Regex("""(?<![A-Za-z0-9])${Regex.escape(code)}(?![A-Za-z0-9])""", RegexOption.IGNORE_CASE).containsMatchIn(server)
    } ?: timeline.lastOrNull { freightTime(it.arrival) != null || freightTime(it.departure) != null }
        .takeIf { timeline.all { row -> freightTime(row.arrival) != null || freightTime(row.departure) != null } }
    val item = app.saved.find { it.key == page.query.storageKey }
    PullToRefreshBox(isRefreshing = app.loading && !app.automaticRefresh, onRefresh = { app.refresh(page.query) }, modifier = Modifier.fillMaxSize()) {
        Feed {
            item {
                Panel {
                    Label("FNR · ${freight.fnr.ifBlank { page.query.key.takeIf { page.query.isFnr } ?: "Not Reported" }}")
                    Label(if (freight.arrived == null) "ESTIMATED ARRIVAL" else "CONFIRMED ARRIVAL")
                    Text(displayFreightTime(freight.arrived ?: freight.eta), fontSize = 40.sp,
                        lineHeight = 44.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    Label("REMAINING DISTANCE")
                    Text(distanceText, fontSize = 34.sp, lineHeight = 38.sp, fontWeight = FontWeight.Bold)
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
                    val liveStatus = when {
                        app.automaticRefresh -> "Live refresh · checking FOIS"
                        app.loading -> "Refreshing from FOIS"
                        refreshIssue != null -> refreshIssue
                        polling -> "Live refresh · checks every second"
                        !signedIn && freight.arrived == null -> "Manual refresh · verification may be required"
                        active -> "Live refresh paused"
                        else -> "Showing last reported data"
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (polling || app.automaticRefresh) {
                            val green = if (isSystemInDarkTheme()) Color(0xFF63E68A) else Color(0xFF198344)
                            Box(Modifier.size(10.dp).background(green, CircleShape))
                        }
                        Text(liveStatus, style = MaterialTheme.typography.labelLarge)
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
                    Text("${freight.get("Origin")} → ${freight.get("Destination")}", style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold)
                    Text("Last reported station · ${freight.get("Last reported station")}")
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ActionButton(if (item == null) "Save enquiry" else "Edit saved") {
                            editing = item ?: Saved(page.query.storageKey, "", false, false)
                        }
                        ActionButton("Copy report") { app.copy("Freight report", Reports.text(freight)) }
                        OutlinedButton(onClick = { exporting = true }) { Text("Export file") }
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
                    if (details) (fields + (freight.values.keys - fields.toSet())).distinct().forEach { field ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Label(field)
                                if (field == "Remaining distance") {
                                    Text(distanceText, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                                } else Text(freight.get(field))
                            }
                            if (freight.values.containsKey(field)) IconButton(onClick = { app.copy(field, freight.get(field)) }) {
                                Icon(painterResource(R.drawable.ic_clipboard), "Copy $field")
                            }
                        }
                    }
                }
            }
            item {
                Panel {
                    Title("Journey timeline")
                    Text("${timeline.size} station records")
                    if (timeline.isNotEmpty()) {
                        OutlinedTextField(stationFilter, { stationFilter = it }, Modifier.fillMaxWidth(),
                            label = { Text("Search journey") }, singleLine = true)
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            FilterChip(!newest, { newest = false }, { Text("Chronological") })
                            FilterChip(newest, { newest = true }, { Text("Reverse order") })
                        }
                        if (rows.isEmpty()) Text("No station records match this search.")
                        TextButton(onClick = {
                            app.copy("Journey history", timeline.joinToString("\n") { row ->
                                listOf(row.station, row.arrival, row.departure, row.zone, row.division, row.movement).joinToString(" · ") { it.ifBlank { "Not Reported" } }
                            })
                        }) { Text("Copy journey history") }
                    } else Text("Not Reported")
                }
            }
            items(rows) { row ->
                val highlighted = row == latest
                val marker = when {
                    sameStation(row.station, freight.values["Origin"]) -> "Origin"
                    sameStation(row.station, freight.values["Destination"]) -> "Destination"
                    else -> ""
                }
                Row(Modifier.padding(start = 9.dp)) {
                    Column(Modifier.width(18.dp).padding(top = 15.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.size(11.dp).background(
                            if (highlighted || row.departure.isNotBlank()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                            CircleShape))
                        Box(Modifier.size(2.dp, 60.dp).background(MaterialTheme.colorScheme.outline.copy(alpha = .45f)))
                    }
                    Panel(Modifier.weight(1f)) {
                        if (highlighted) Label("LATEST REPORTED")
                        if (marker.isNotBlank()) Label(marker)
                        Text(row.station, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text("Arrival · ${displayFreightTime(row.arrival.ifBlank { null })}")
                        Text("Departure · ${displayFreightTime(row.departure.ifBlank { null })}")
                        if (row.departure.isNotBlank()) Label("Departure reported")
                        TextButton(onClick = { expanded = if (row in expanded) expanded - row else expanded + row }) {
                            Text(if (row in expanded) "Hide station details" else "Station details")
                        }
                        if (row in expanded) {
                            Text("Railway zone · ${row.zone.ifBlank { "Not Reported" }}")
                            Text("Railway division · ${row.division.ifBlank { "Not Reported" }}")
                            Text("Movement · ${row.movement.ifBlank { "Not Reported" }}")
                        }
                    }
                }
            }
        }
    }
    editing?.let { SavedDialog(app, it) { editing = null } }
    if (exporting) {
        AlertDialog(onDismissRequest = { exporting = false }, title = { Text("Freight report") }, text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    ReportFormat.entries.forEach { format ->
                        FilterChip(reportFormat == format, { reportFormat = format }, { Text(format.name) })
                    }
                }
                TextButton(onClick = { exporting = false; app.export(freight, reportFormat) }) { Text("Save to device") }
                TextButton(onClick = { exporting = false; app.copy("Freight report", Reports.text(freight)) }) { Text("Copy complete report") }
            }
        }, confirmButton = { TextButton(onClick = { exporting = false }) { Text("Close") } })
    }
}

private fun durationLabel(minutes: Long): String = when {
    minutes >= 1440 -> "${minutes / 1440}d ${(minutes % 1440) / 60}h"
    minutes >= 60 -> "${minutes / 60}h ${minutes % 60}m"
    else -> "${minutes}m"
}

private fun sameStation(a: String, b: String?): Boolean {
    if (b == null) return false
    fun code(value: String) = value.substringAfterLast('(', value).substringBefore(')').trim()
    return a.equals(b, true) || code(a).equals(code(b), true)
}
