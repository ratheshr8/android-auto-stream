package app.siding.browser.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import app.siding.browser.logic.Link
import app.siding.browser.logic.SearchEngine
import app.siding.browser.logic.Sheet
import app.siding.browser.logic.Startup
import app.siding.browser.logic.ThemeMode
import app.siding.browser.web.CabinEngine

@Composable
fun BrowserApp(
    engine: CabinEngine,
    onShare: () -> Unit,
    onMic: () -> Unit,
    onExit: () -> Unit,
) {
    val snap = engine.snapshot
    val base = LocalDensity.current
    val scale = snap.settings.displayPercent / 100f
    SidingTheme(dark = snap.dark, trueBlack = snap.settings.trueBlack) {
        CompositionLocalProvider(
            LocalDensity provides Density(base.density * scale, base.fontScale),
        ) {
            androidx.activity.compose.BackHandler { engine.onBack(onExit) }
            Box(
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background),
            ) {
                if (snap.drivingLock) {
                    DrivingLock()
                } else {
                    Column(Modifier.fillMaxSize()) {
                        if (snap.settings.showAddressBar && !snap.fullscreen) {
                            AddressBar(engine, onMic)
                        }
                        if (snap.findActive && !snap.fullscreen) FindBar(engine)
                        if (snap.loading && !snap.shelf) {
                            LinearProgressIndicator(
                                progress = { snap.progress / 100f },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            AndroidView(factory = { engine.pageContainer }, modifier = Modifier.fillMaxSize())
                            if (snap.shelf && !snap.fullscreen) StartPage(engine)
                            if (snap.pageError != null && !snap.fullscreen && !snap.shelf) {
                                ErrorPane(snap.pageError, engine::retry)
                            }
                        }
                        if (!snap.fullscreen) BottomBar(engine)
                    }
                    if (snap.sheet != null) SheetLayer(engine, onShare)
                    if (snap.permission != null) {
                        PermissionDialog(
                            summary = snap.permission.summary,
                            onAllow = { engine.respondPermission(true) },
                            onBlock = { engine.respondPermission(false) },
                        )
                    }
                    if (snap.message != null) {
                        Surface(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 72.dp)
                                .clickable { engine.dismissMessage() },
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.inverseSurface,
                        ) {
                            Text(
                                snap.message,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                                color = MaterialTheme.colorScheme.inverseOnSurface,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AddressBar(engine: CabinEngine, onMic: () -> Unit) {
    val snap = engine.snapshot
    var text by remember { mutableStateOf("") }
    var focused by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(snap.activeId, snap.pageUrl, snap.shelf) {
        if (!focused) text = if (snap.shelf) "" else snap.pageUrl.orEmpty()
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier
                .weight(1f)
                .onFocusChanged { focused = it.isFocused },
            singleLine = true,
            placeholder = { Text(if (snap.shelf) "Search or type a site" else "Address") },
            leadingIcon = {
                if (snap.secure && !snap.shelf) {
                    Icon(Icons.Filled.Lock, contentDescription = "Secure connection")
                } else {
                    Icon(Icons.Filled.Search, contentDescription = null)
                }
            },
            trailingIcon = {
                if (text.isNotEmpty()) {
                    IconButton(onClick = { text = "" }) {
                        Icon(Icons.Filled.Close, contentDescription = "Clear address")
                    }
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = {
                engine.submit(text)
                keyboard?.hide()
            }),
            shape = RoundedCornerShape(16.dp),
        )
        IconButton(onClick = onMic) {
            Icon(Icons.Filled.Mic, contentDescription = "Speak a search")
        }
    }
}

@Composable
private fun FindBar(engine: CabinEngine) {
    var query by remember { mutableStateOf("") }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = {
                query = it
                engine.find(it)
            },
            modifier = Modifier.weight(1f),
            singleLine = true,
            placeholder = { Text("Find on page") },
        )
        Text(engine.snapshot.findLabel, modifier = Modifier.padding(horizontal = 8.dp))
        TextButton(onClick = { engine.findNext(false) }) { Text("Prev") }
        TextButton(onClick = { engine.findNext(true) }) { Text("Next") }
        IconButton(onClick = { engine.closeFind() }) {
            Icon(Icons.Filled.Close, contentDescription = "Close find")
        }
    }
}

@Composable
private fun BottomBar(engine: CabinEngine) {
    val snap = engine.snapshot
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val compact = maxWidth < 640.dp
        Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 3.dp) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 64.dp)
                    .padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BarButton("Back", Icons.AutoMirrored.Filled.ArrowBack, snap.canBack, compact) { engine.back() }
                BarButton("Forward", Icons.AutoMirrored.Filled.ArrowForward, snap.canForward, compact) { engine.forward() }
                BarButton("Reload", Icons.Filled.Refresh, !snap.shelf, compact) { engine.reload() }
                BarButton("Home", Icons.Filled.Home, true, compact) { engine.home() }
                BarButton("Tabs ${snap.tabs.size}", Icons.Filled.Add, true, compact) { engine.openSheet(Sheet.TABS) }
                BarButton("Menu", Icons.Filled.Menu, true, compact) { engine.openSheet(Sheet.MENU) }
            }
        }
    }
}

@Composable
private fun BarButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    enabled: Boolean,
    compact: Boolean,
    onClick: () -> Unit,
) {
    TextButton(onClick = onClick, enabled = enabled, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = label)
            if (!compact) {
                Text(label, fontSize = 12.sp, maxLines = 1)
            }
        }
    }
}

@Composable
private fun StartPage(engine: CabinEngine) {
    val snap = engine.snapshot
    var editing by remember { mutableStateOf<Int?>(null) }
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
    ) {
        Text("Siding", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.SemiBold)
        Text(
            "Parked browsing for YouTube and streaming sites.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "For passengers, and for when the car is parked.",
            modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
            color = MaterialTheme.colorScheme.primary,
        )
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val columns = if (maxWidth >= 720.dp) 4 else 2
            val rows = snap.quickLinks.chunked(columns)
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                rows.forEachIndexed { rowIndex, row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                        row.forEachIndexed { columnIndex, link ->
                            val index = rowIndex * columns + columnIndex
                            ShelfCard(
                                link = link,
                                modifier = Modifier.weight(1f),
                                onOpen = { if (link.url.isNotBlank()) engine.submit(link.url) else editing = index },
                                onEdit = { editing = index },
                            )
                        }
                        repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
        TextButton(onClick = { engine.openSheet(Sheet.NOTES) }) {
            Icon(Icons.Filled.Info, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("What will play")
        }
    }
    if (editing != null) {
        val index = editing ?: return
        val current = snap.quickLinks.getOrNull(index) ?: Link("Add", "")
        QuickEditDialog(
            initial = current,
            onDismiss = { editing = null },
            onSave = { title, url ->
                engine.saveQuickLink(index, title, url)
                editing = null
            },
            onClear = {
                engine.clearQuickLink(index)
                editing = null
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ShelfCard(
    link: Link,
    modifier: Modifier,
    onOpen: () -> Unit,
    onEdit: () -> Unit,
) {
    val empty = link.url.isBlank()
    Surface(
        modifier = modifier
            .height(96.dp)
            .combinedClickable(onClick = onOpen, onLongClick = onEdit),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Box(
                Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (empty) "+" else link.title.take(1).uppercase(),
                    color = MaterialTheme.colorScheme.onPrimary,
                    fontWeight = FontWeight.Bold,
                )
            }
            Text(
                if (empty) "Add a site" else link.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun ErrorPane(message: String, onRetry: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(28.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("This page didn’t open", style = MaterialTheme.typography.headlineSmall)
        Text(message, modifier = Modifier.padding(top = 8.dp, bottom = 16.dp))
        Button(onClick = onRetry) { Text("Try again") }
    }
}

@Composable
private fun DrivingLock() {
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Parked only", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.SemiBold)
        Text(
            "The car is moving, so playback is paused and the page is hidden. It stays that way until the vehicle is parked.",
            modifier = Modifier.padding(top = 12.dp),
            style = MaterialTheme.typography.titleMedium,
        )
    }
}

@Composable
private fun SheetLayer(engine: CabinEngine, onShare: () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.45f))
            .clickable { engine.closeSheet() },
    ) {
        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .fillMaxHeight(0.92f)
                .clickable(enabled = true, onClick = {}),
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        ) {
            when (engine.snapshot.sheet) {
                Sheet.MENU -> MenuSheet(engine, onShare)
                Sheet.TABS -> TabsSheet(engine)
                Sheet.BOOKMARKS -> LinkSheet(
                    "Bookmarks",
                    engine.snapshot.bookmarks,
                    "No bookmarks yet. Open a page and star it.",
                    engine::closeSheet,
                    engine::openHistory,
                ) { engine.removeBookmark(it) }
                Sheet.HISTORY -> HistorySheet(engine)
                Sheet.SETTINGS -> SettingsSheet(engine)
                Sheet.NOTES -> NotesSheet(engine)
                null -> Unit
            }
        }
    }
}

@Composable
private fun SheetHeader(title: String, onClose: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 8.dp, top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        IconButton(onClick = onClose) {
            Icon(Icons.Filled.Close, contentDescription = "Close")
        }
    }
}

@Composable
private fun MenuSheet(engine: CabinEngine, onShare: () -> Unit) {
    val snap = engine.snapshot
    Column(Modifier.fillMaxSize()) {
        SheetHeader("Menu", engine::closeSheet)
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                MenuRow("New tab") { engine.newTab() }
                MenuRow(if (snap.bookmarked) "Remove bookmark" else "Bookmark this page", enabled = snap.pageUrl != null) {
                    engine.toggleBookmark()
                    engine.closeSheet()
                }
                MenuRow("Bookmarks") { engine.openSheet(Sheet.BOOKMARKS) }
                MenuRow("History") { engine.openSheet(Sheet.HISTORY) }
                MenuRow("Find on page") {
                    engine.closeSheet()
                    engine.find("")
                }
                MenuRow("Share this page", enabled = snap.pageUrl?.startsWith("http") == true) {
                    engine.closeSheet()
                    onShare()
                }
                MenuRow(if (snap.settings.desktop) "Use mobile site" else "Request desktop site") {
                    engine.updateSettings(snap.settings.copy(desktop = !snap.settings.desktop))
                }
                MenuRow("What will play") { engine.openSheet(Sheet.NOTES) }
                MenuRow("Settings") { engine.openSheet(Sheet.SETTINGS) }
            }
        }
    }
}

@Composable
private fun MenuRow(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp),
        contentPadding = PaddingValues(horizontal = 20.dp),
    ) {
        Text(label, modifier = Modifier.fillMaxWidth(), style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun TabsSheet(engine: CabinEngine) {
    val snap = engine.snapshot
    Column(Modifier.fillMaxSize()) {
        SheetHeader("Tabs", engine::closeSheet)
        TextButton(onClick = { engine.newTab() }, modifier = Modifier.padding(horizontal = 12.dp)) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("New tab")
        }
        LazyColumn {
            items(snap.tabs, key = { it.id }) { tab ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 64.dp)
                        .clickable { engine.selectTab(tab.id) }
                        .padding(horizontal = 20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(tab.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                        Text(
                            tab.url ?: "Start page",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { engine.closeTab(tab.id) }) {
                        Icon(Icons.Filled.Close, contentDescription = "Close tab")
                    }
                }
            }
        }
    }
}

@Composable
private fun LinkSheet(
    title: String,
    links: List<Link>,
    empty: String,
    onClose: () -> Unit,
    onOpen: (String) -> Unit,
    onDelete: (String) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        SheetHeader(title, onClose)
        if (links.isEmpty()) {
            Text(empty, modifier = Modifier.padding(20.dp))
        }
        LazyColumn {
            items(links, key = { it.url }) { link ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 64.dp)
                        .clickable { onOpen(link.url) }
                        .padding(horizontal = 20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(link.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(link.url, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { onDelete(link.url) }) {
                        Icon(Icons.Filled.Close, contentDescription = "Remove")
                    }
                }
            }
        }
    }
}

@Composable
private fun HistorySheet(engine: CabinEngine) {
    Column(Modifier.fillMaxSize()) {
        SheetHeader("History", engine::closeSheet)
        TextButton(onClick = { engine.clearHistory() }, modifier = Modifier.padding(horizontal = 12.dp)) {
            Text("Clear history")
        }
        if (engine.snapshot.history.isEmpty()) {
            Text("Pages you open will show up here.", modifier = Modifier.padding(20.dp))
        }
        LazyColumn {
            items(engine.snapshot.history, key = { it.url }) { link ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 64.dp)
                        .clickable { engine.openHistory(link.url) }
                        .padding(horizontal = 20.dp, vertical = 10.dp),
                ) {
                    Text(link.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(link.url, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun SettingsSheet(engine: CabinEngine) {
    val settings = engine.snapshot.settings
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 28.dp),
    ) {
        SheetHeader("Settings", engine::closeSheet)
        Text("Appearance", modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp), fontWeight = FontWeight.SemiBold)
        ChoiceRow(
            options = ThemeMode.entries.map { it.name.lowercase().replaceFirstChar { c -> c.uppercase() } to it },
            selected = settings.theme,
        ) { engine.updateSettings(settings.copy(theme = it)) }
        SwitchRow("True black in dark theme", settings.trueBlack) {
            engine.updateSettings(settings.copy(trueBlack = it))
        }
        SwitchRow("Always show the address bar", settings.showAddressBar) {
            engine.updateSettings(settings.copy(showAddressBar = it))
        }
        SwitchRow("Darken supported pages", settings.darkenPages) {
            engine.updateSettings(settings.copy(darkenPages = it))
        }
        Text(
            "Display size ${settings.displayPercent}%",
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
        Slider(
            value = settings.displayPercent.toFloat(),
            onValueChange = { engine.updateSettings(settings.copy(displayPercent = it.toInt())) },
            valueRange = 80f..150f,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Text("Search", modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp), fontWeight = FontWeight.SemiBold)
        ChoiceRow(SearchEngine.entries.map { it.label to it }, settings.search) {
            engine.updateSettings(settings.copy(search = it))
        }
        Text("Startup", modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp), fontWeight = FontWeight.SemiBold)
        ChoiceRow(
            listOf(
                "Start page" to Startup.START_PAGE,
                "Home page" to Startup.HOME,
                "Last tabs" to Startup.LAST_TABS,
            ),
            settings.startup,
        ) { engine.updateSettings(settings.copy(startup = it)) }
        var home by remember(settings.homeUrl) { mutableStateOf(settings.homeUrl) }
        OutlinedTextField(
            value = home,
            onValueChange = { home = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            label = { Text("Home page") },
            placeholder = { Text("Leave empty to use the shelf") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { engine.updateSettings(settings.copy(homeUrl = home)) }),
        )
        TextButton(
            onClick = { engine.updateSettings(settings.copy(homeUrl = home)) },
            modifier = Modifier.padding(horizontal = 12.dp),
        ) { Text("Save home page") }
        Text("Privacy", modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp), fontWeight = FontWeight.SemiBold)
        Text(
            "Siding does not store passwords or payment details. Cookies keep you signed in until you clear them.",
            modifier = Modifier.padding(horizontal = 20.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = { engine.clearBrowsingData() }, modifier = Modifier.padding(horizontal = 12.dp)) {
            Text("Clear cookies, cache, history, and permissions")
        }
    }
}

@Composable
private fun <T> ChoiceRow(options: List<Pair<String, T>>, selected: T, onSelect: (T) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { (label, value) ->
            if (value == selected) {
                Button(onClick = { onSelect(value) }, modifier = Modifier.weight(1f)) {
                    Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            } else {
                OutlinedButton(onClick = { onSelect(value) }, modifier = Modifier.weight(1f)) {
                    Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}

@Composable
private fun NotesSheet(engine: CabinEngine) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 24.dp),
    ) {
        SheetHeader("What will play", engine::closeSheet)
        Text(
            """
            Siding opens the official websites. You sign in yourself.

            YouTube’s mobile site often plays in this window. Netflix, Prime Video, Disney+, JioHotstar, Sony LIV, Zee5, Crunchyroll, and similar services sometimes refuse an in-app browser, ask for their own app, or require hardware DRM that Android’s WebView does not provide. Siding uses the system player and does not bypass those checks.

            Video and audio pause when you leave the app and when the car reports that driving restrictions are on. If you are the driver, leave Siding closed.

            Android Auto on a phone currently accepts parked games, not general browsers. Siding declares the browser category used by Android Automotive OS. It does not label itself as a game or a navigation app, and it does not stay interactive while the car is moving.

            Names of streaming services belong to their owners. The shelf is only a shortcut to their sites.
            """.trimIndent(),
            modifier = Modifier.padding(horizontal = 20.dp),
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

@Composable
private fun PermissionDialog(summary: String, onAllow: () -> Unit, onBlock: () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.45f)),
        contentAlignment = Alignment.Center,
    ) {
        Surface(shape = RoundedCornerShape(20.dp), modifier = Modifier.widthIn(max = 480.dp).padding(24.dp)) {
            Column(Modifier.padding(20.dp)) {
                Text("Site permission", style = MaterialTheme.typography.titleLarge)
                Text(summary, modifier = Modifier.padding(top = 8.dp, bottom = 16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = onBlock, modifier = Modifier.weight(1f)) { Text("Block") }
                    Button(onClick = onAllow, modifier = Modifier.weight(1f)) { Text("Allow") }
                }
            }
        }
    }
}

@Composable
private fun QuickEditDialog(
    initial: Link,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit,
    onClear: () -> Unit,
) {
    var title by remember { mutableStateOf(if (initial.url.isBlank()) "" else initial.title) }
    var url by remember { mutableStateOf(initial.url) }
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.45f))
            .clickable { onDismiss() },
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier
                .widthIn(max = 520.dp)
                .padding(24.dp)
                .clickable(enabled = true, onClick = {}),
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Shelf shortcut", style = MaterialTheme.typography.titleLarge)
                OutlinedTextField(title, { title = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(url, { url = it }, label = { Text("Site") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onClear) { Text("Clear") }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Button(onClick = { onSave(title, url) }) { Text("Save") }
                }
            }
        }
    }
}
