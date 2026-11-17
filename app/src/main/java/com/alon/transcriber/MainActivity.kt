package com.alon.transcriber

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

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

/* ---------- theme: "Bubble" ---------- */

@OptIn(ExperimentalTextApi::class)
private fun rubik(weight: Int) = Font(
    R.font.rubik, FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight))
)

private val Rubik = FontFamily(rubik(400), rubik(500), rubik(600), rubik(700))

private fun rubikTypography(): Typography {
    val b = Typography()
    fun TextStyle.r() = copy(fontFamily = Rubik)
    return Typography(
        b.displayLarge.r(), b.displayMedium.r(), b.displaySmall.r(),
        b.headlineLarge.r(), b.headlineMedium.r(), b.headlineSmall.r(),
        b.titleLarge.r(), b.titleMedium.r(), b.titleSmall.r(),
        b.bodyLarge.r(), b.bodyMedium.r(), b.bodySmall.r(),
        b.labelLarge.r(), b.labelMedium.r(), b.labelSmall.r(),
    )
}

private val LightColors = lightColorScheme(
    primary = Color(0xFF0F766E), onPrimary = Color.White,
    primaryContainer = Color(0xFFCCEDE8), onPrimaryContainer = Color(0xFF0B2B28),
    secondary = Color(0xFF0F766E),
    background = Color(0xFFF3FAF8), onBackground = Color(0xFF0B2B28),
    surface = Color.White, onSurface = Color(0xFF0B2B28),
    surfaceVariant = Color(0xFFE3F2EF), onSurfaceVariant = Color(0xFF4B6461),
)
private val DarkColors = darkColorScheme(
    primary = Color(0xFF2DD4BF), onPrimary = Color(0xFF042F2E),
    primaryContainer = Color(0xFF134E4A), onPrimaryContainer = Color(0xFFCCEDE8),
    secondary = Color(0xFF2DD4BF),
    background = Color(0xFF0B1514), onBackground = Color(0xFFE2F2EF),
    surface = Color(0xFF15211F), onSurface = Color(0xFFE2F2EF),
    surfaceVariant = Color(0xFF1C2D2B), onSurfaceVariant = Color(0xFFA7BDB9),
)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        typography = rubikTypography()
    ) {
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
                title = {
                    Text(
                        "מתמלל", fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                },
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
                    containerColor = MaterialTheme.colorScheme.background,
                    actionIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            )
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (vm.update != null && !showSettings) UpdateBanner(vm)
            Box(Modifier.weight(1f).fillMaxWidth()) {
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
}

@Composable
fun UpdateBanner(vm: MainViewModel) {
    val context = LocalContext.current
    var needsPermission by remember { mutableStateOf(false) }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp)
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.SystemUpdate, contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    "גרסה חדשה זמינה",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.weight(1f)
                )
                if (vm.updating) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
                } else {
                    Button(onClick = {
                        needsPermission = !vm.installUpdate()
                        if (needsPermission) Updater.openInstallPermissionSettings(context)
                    }) { Text("עדכון") }
                }
            }
            val note = vm.updateError
                ?: if (needsPermission) "בפעם הראשונה בלבד: הפעל \"אפשר ממקור זה\", חזור ולחץ שוב עדכון" else null
            if (note != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    note, style = MaterialTheme.typography.bodyMedium,
                    color = if (vm.updateError != null) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onPrimaryContainer
                )
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
            shape = RoundedCornerShape(14.dp),
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
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = RoundedCornerShape(18.dp)
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
        Waveform(animated = false, modifier = Modifier.height(72.dp))
        Spacer(Modifier.height(24.dp))
        Text(
            "שתפו הודעה קולית מוואטסאפ",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(20.dp))
        Bubble {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
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
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.size(30.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    "$n", color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

/** Chat bubble: rounded with one tight corner as the "tail" (bottom-right in RTL). */
@Composable
fun Bubble(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp, bottomEnd = 24.dp, bottomStart = 6.dp)
    Surface(
        shape = shape,
        color = MaterialTheme.colorScheme.surface,
        modifier = modifier
            .fillMaxWidth()
            .shadow(14.dp, shape, ambientColor = Color(0x330F766E), spotColor = Color(0x330F766E))
    ) {
        Box(Modifier.padding(start = 22.dp, end = 22.dp, top = 22.dp, bottom = 18.dp)) { content() }
    }
}

/** Animated voice-wave bars shown while transcribing. */
@Composable
fun Waveform(animated: Boolean, modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.primary
    val heights = remember { List(18) { 0.3f + Random.nextFloat() * 0.7f } }
    val phase = if (animated) {
        rememberInfiniteTransition(label = "wave").animateFloat(
            0f, 1f, infiniteRepeatable(tween(1000, easing = LinearEasing)), label = "phase"
        ).value
    } else 0.25f
    Canvas(modifier.width((18 * 11).dp)) {
        val barW = 6.dp.toPx()
        val gap = (size.width - barW * heights.size) / (heights.size - 1)
        heights.forEachIndexed { i, h ->
            val k = if (animated) 0.35f + 0.65f * abs(sin(2 * PI * (phase + i * 0.07)).toFloat()) else 1f
            val barH = size.height * h * k
            drawRoundRect(
                color = color,
                topLeft = Offset(i * (barW + gap), (size.height - barH) / 2),
                size = Size(barW, barH),
                cornerRadius = CornerRadius(barW / 2)
            )
        }
    }
}

private fun formatDuration(sec: Double): String {
    val s = sec.roundToInt()
    return "%d:%02d".format(s / 60, s % 60)
}

private fun languageName(code: String?): String? = when (code?.lowercase()?.take(2)) {
    "he", "iw" -> "עברית"
    "en" -> "אנגלית"
    "ar" -> "ערבית"
    "ru" -> "רוסית"
    "fr" -> "צרפתית"
    "es" -> "ספרדית"
    else -> null
}

@Composable
fun ResultsList(vm: MainViewModel, snackbar: SnackbarHostState) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val single = vm.items.size == 1

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = if (single) Arrangement.Center else Arrangement.spacedBy(28.dp)
    ) {
        items(vm.items, key = { it.id }) { item ->
            when (val s = item.state) {
                ItemState.Loading -> Column(
                    Modifier.fillMaxWidth().padding(vertical = 40.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Waveform(animated = true, modifier = Modifier.height(72.dp))
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "מתמלל את ההודעה…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                is ItemState.Failed -> Bubble {
                    Column {
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
                        Spacer(Modifier.height(14.dp))
                        OutlinedButton(
                            onClick = { vm.retry(item.id) },
                            shape = RoundedCornerShape(18.dp),
                            border = BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
                        ) {
                            Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("נסה שוב")
                        }
                    }
                }

                is ItemState.Done -> {
                    var shown by remember(item.id) { mutableStateOf(false) }
                    LaunchedEffect(item.id) { shown = true }
                    AnimatedVisibility(
                        visible = shown,
                        enter = fadeIn(tween(450)) + slideInVertically(tween(450)) { it / 8 }
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                            Bubble {
                                Column {
                                    SelectionContainer {
                                        Text(
                                            s.result.text,
                                            style = MaterialTheme.typography.bodyLarge.copy(
                                                fontSize = 19.sp,
                                                lineHeight = 33.sp,
                                                textDirection = TextDirection.Content
                                            )
                                        )
                                    }
                                    val meta = listOfNotNull(
                                        s.result.durationSec?.let { "הודעה קולית · ${formatDuration(it)}" },
                                        languageName(s.result.language)
                                    )
                                    if (meta.isNotEmpty()) {
                                        Spacer(Modifier.height(12.dp))
                                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                            meta.forEach {
                                                Text(
                                                    it, style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Button(
                                    onClick = {
                                        clipboard.setText(AnnotatedString(s.result.text))
                                        scope.launch { snackbar.showSnackbar("הטקסט הועתק") }
                                    },
                                    shape = RoundedCornerShape(18.dp),
                                    modifier = Modifier.weight(1f).height(56.dp)
                                ) {
                                    Icon(Icons.Outlined.ContentCopy, contentDescription = null, modifier = Modifier.size(20.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text("העתקה", fontSize = 17.sp, fontWeight = FontWeight.Medium)
                                }
                                OutlinedButton(
                                    onClick = {
                                        val send = Intent(Intent.ACTION_SEND).apply {
                                            type = "text/plain"
                                            putExtra(Intent.EXTRA_TEXT, s.result.text)
                                        }
                                        context.startActivity(Intent.createChooser(send, "שיתוף התמלול"))
                                    },
                                    shape = RoundedCornerShape(18.dp),
                                    border = BorderStroke(2.dp, MaterialTheme.colorScheme.primary),
                                    modifier = Modifier.weight(1f).height(56.dp)
                                ) {
                                    Icon(Icons.Outlined.Share, contentDescription = null, modifier = Modifier.size(20.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text("שיתוף", fontSize = 17.sp, fontWeight = FontWeight.Medium)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
