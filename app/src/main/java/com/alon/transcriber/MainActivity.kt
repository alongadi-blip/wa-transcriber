package com.alon.transcriber

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handleIntent(intent)
        setContent { AppTheme { AppScreen(vm) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        val uris = mutableListOf<Uri>()
        when (intent.action) {
            Intent.ACTION_SEND -> {
                val u: Uri? = if (Build.VERSION.SDK_INT >= 33)
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
                u?.let { uris += it }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val list: List<Uri>? = if (Build.VERSION.SDK_INT >= 33)
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
                list?.let { uris += it }
            }
            else -> return
        }
        if (uris.isEmpty()) {
            intent.clipData?.let { cd ->
                for (i in 0 until cd.itemCount) cd.getItemAt(i).uri?.let { uris += it }
            }
        }
        vm.submit(uris)
    }
}

/* ---------- theme ---------- */

private val LightColors = lightColorScheme(
    primary = Color(0xFF0D9488), onPrimary = Color.White,
    primaryContainer = Color(0xFFCCFBF1), onPrimaryContainer = Color(0xFF134E4A),
    secondary = Color(0xFF14B8A6),
    background = Color(0xFFF0FDFA), onBackground = Color(0xFF134E4A),
    surface = Color.White, onSurface = Color(0xFF0F172A),
    surfaceVariant = Color(0xFFE6F4F1), onSurfaceVariant = Color(0xFF475569),
)
private val DarkColors = darkColorScheme(
    primary = Color(0xFF2DD4BF), onPrimary = Color(0xFF042F2E),
    primaryContainer = Color(0xFF115E59), onPrimaryContainer = Color(0xFFCCFBF1),
    background = Color(0xFF0B1514), onBackground = Color(0xFFE2F2EF),
    surface = Color(0xFF13201F), onSurface = Color(0xFFE2F2EF),
    surfaceVariant = Color(0xFF1C2D2B), onSurfaceVariant = Color(0xFFA7BDB9),
)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl, content = content)
    }
}

/* ---------- screens ---------- */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppScreen(vm: MainViewModel) {
    var apiKey by remember { mutableStateOf(vm.prefs.apiKey) }
    var showSettings by remember { mutableStateOf(apiKey.isBlank()) }
    val snackbar = remember { SnackbarHostState() }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("מתמלל", fontWeight = FontWeight.SemiBold) },
                actions = {
                    if (vm.items.isNotEmpty() && !showSettings) {
                        IconButton(onClick = { vm.clear() }) {
                            Icon(Icons.Outlined.DeleteSweep, contentDescription = "ניקוי")
                        }
                    }
                    if (apiKey.isNotBlank()) {
                        IconButton(onClick = { showSettings = !showSettings }) {
                            Icon(
                                if (showSettings) Icons.Outlined.Close else Icons.Outlined.Settings,
                                contentDescription = "הגדרות"
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when {
                showSettings -> SettingsScreen(vm.prefs) { saved ->
                    apiKey = saved
                    showSettings = false
                    // Retry anything that failed, e.g. because the key was missing or wrong
                    vm.items.filter { it.state is ItemState.Failed }.forEach { vm.retry(it.id) }
                }
                vm.items.isEmpty() -> IdleScreen()
                else -> ResultsList(vm, snackbar)
            }
        }
    }
}

@Composable
fun SettingsScreen(prefs: Prefs, onSaved: (String) -> Unit) {
    var key by remember { mutableStateOf(prefs.apiKey) }
    var lang by remember { mutableStateOf(prefs.language) }
    var visible by remember { mutableStateOf(false) }
    val uri = LocalUriHandler.current

    Column(
        Modifier.fillMaxSize().imePadding().padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("הגדרות", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Text(
            "התמלול מתבצע ב-Groq (חינמי). צריך מפתח API פעם אחת.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        OutlinedTextField(
            value = key, onValueChange = { key = it.trim() },
            label = { Text("מפתח Groq API") },
            placeholder = { Text("gsk_...") },
            singleLine = true,
            visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { visible = !visible }) {
                    Icon(
                        if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                        contentDescription = if (visible) "הסתר מפתח" else "הצג מפתח"
                    )
                }
            },
            modifier = Modifier.fillMaxWidth()
        )
        TextButton(onClick = { uri.openUri("https://console.groq.com/keys") }) {
            Icon(Icons.Outlined.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("קבלת מפתח באתר Groq")
        }

        Text("שפת ההודעות", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = lang == "he", onClick = { lang = "he" }, label = { Text("עברית") })
            FilterChip(selected = lang == "", onClick = { lang = "" }, label = { Text("זיהוי אוטומטי") })
        }

        Spacer(Modifier.weight(1f))
        Button(
            onClick = { prefs.apiKey = key; prefs.language = lang; onSaved(key) },
            enabled = key.isNotBlank(),
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(14.dp)
        ) { Text("שמירה", style = MaterialTheme.typography.titleMedium) }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
fun IdleScreen() {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.size(96.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Outlined.GraphicEq, contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(48.dp)
                )
            }
        }
        Spacer(Modifier.height(24.dp))
        Text(
            "שתפו הודעה קולית מוואטסאפ",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(20.dp))
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Step(1, "בצ'אט, לחיצה ארוכה על ההודעה הקולית")
                Step(2, "תפריט ⋮ ואז שיתוף")
                Step(3, "בוחרים באפליקציה \"מתמלל\"")
            }
        }
    }
}

@Composable
private fun Step(n: Int, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(28.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    "$n", color = MaterialTheme.colorScheme.onPrimary,
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
fun ResultsList(vm: MainViewModel, snackbar: SnackbarHostState) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(vm.items, key = { it.id }) { item ->
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(18.dp)) {
                    when (val s = item.state) {
                        ItemState.Loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
                            Spacer(Modifier.width(14.dp))
                            Text("מתמלל…", style = MaterialTheme.typography.bodyLarge)
                        }

                        is ItemState.Failed -> {
                            Row(verticalAlignment = Alignment.Top) {
                                Icon(
                                    Icons.Outlined.ErrorOutline, contentDescription = "שגיאה",
                                    tint = MaterialTheme.colorScheme.error
                                )
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    s.message, style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                            Spacer(Modifier.height(12.dp))
                            OutlinedButton(onClick = { vm.retry(item.id) }) {
                                Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("נסה שוב")
                            }
                        }

                        is ItemState.Done -> {
                            SelectionContainer {
                                Text(
                                    s.text,
                                    style = MaterialTheme.typography.bodyLarge.copy(
                                        textDirection = TextDirection.Content,
                                        lineHeight = MaterialTheme.typography.bodyLarge.fontSize * 1.55f
                                    )
                                )
                            }
                            Spacer(Modifier.height(16.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Button(
                                    onClick = {
                                        clipboard.setText(AnnotatedString(s.text))
                                        scope.launch { snackbar.showSnackbar("הטקסט הועתק") }
                                    },
                                    modifier = Modifier.weight(1f).height(48.dp)
                                ) {
                                    Icon(Icons.Outlined.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text("העתקה")
                                }
                                OutlinedButton(
                                    onClick = {
                                        val send = Intent(Intent.ACTION_SEND).apply {
                                            type = "text/plain"
                                            putExtra(Intent.EXTRA_TEXT, s.text)
                                        }
                                        context.startActivity(Intent.createChooser(send, "שיתוף התמלול"))
                                    },
                                    modifier = Modifier.weight(1f).height(48.dp)
                                ) {
                                    Icon(Icons.Outlined.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text("שיתוף")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
