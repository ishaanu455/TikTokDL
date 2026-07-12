package com.example.tiktokdl

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.example.tiktokdl.data.DownloadEvents
import com.example.tiktokdl.data.DownloadHistoryStore
import com.example.tiktokdl.data.HistoryEntry
import com.example.tiktokdl.extractors.Platform
import com.example.tiktokdl.extractors.UrlDetector
import com.example.tiktokdl.service.DownloadService
import com.example.tiktokdl.ui.theme.MediaSaverTheme
import com.example.tiktokdl.ui.theme.BrandDark
import com.example.tiktokdl.ui.theme.BrandPrimary
import com.example.tiktokdl.ui.theme.BrandSecondary
import com.example.tiktokdl.ui.theme.BrandSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity() {

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val storagePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        requestRuntimePermissionsIfNeeded()

        setContent {
            MediaSaverTheme {
                var showIntro by remember { mutableStateOf(true) }
                LaunchedEffect(Unit) {
                    delay(900)
                    showIntro = false
                }
                Crossfade(targetState = showIntro, label = "intro-fade") { intro ->
                    if (intro) {
                        IntroScreen()
                    } else {
                        HomeScreen(
                            onDownload = { url, audioOnly -> startDownload(url, audioOnly) }
                        )
                    }
                }
            }
        }
    }

    private fun requestRuntimePermissionsIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (Build.VERSION.SDK_INT in Build.VERSION_CODES.O..Build.VERSION_CODES.P) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }
    }

    private fun startDownload(url: String, audioOnly: Boolean) {
        val intent = Intent(this, DownloadService::class.java).apply {
            putExtra(DownloadService.EXTRA_URL, url)
            putExtra(DownloadService.EXTRA_AUDIO_ONLY, audioOnly)
        }
        ContextCompat.startForegroundService(this, intent)
    }
}

/** Stable identity for a history entry — must match the grid's LazyVerticalGrid key. */
private fun entryKey(entry: HistoryEntry): String = "${entry.timestamp}_${entry.fileName}"

/**
 * Checks whether the media file behind [uriString] is still reachable. If the user deleted the
 * original from Gallery/Files, the content URI will fail to open and we treat the slot as gone.
 * Blocking call — always invoke from Dispatchers.IO.
 */
private fun fileStillExists(context: Context, uriString: String): Boolean {
    return try {
        val uri = Uri.parse(uriString)
        context.contentResolver.openFileDescriptor(uri, "r")?.use { true } ?: false
    } catch (e: Exception) {
        false
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(onDownload: (String, Boolean) -> Unit) {
    val context = LocalContext.current
    var linkText by remember { mutableStateOf("") }
    var history by remember { mutableStateOf(DownloadHistoryStore.load(context)) }
    var audioOnly by remember { mutableStateOf(false) }
    var clipboardSuggestion by remember { mutableStateOf<String?>(null) }
    var dismissedSuggestion by remember { mutableStateOf<String?>(null) }
    var removingKeys by remember { mutableStateOf(setOf<String>()) }
    val liveProgress by DownloadEvents.progress.collectAsState()

    val detectedPlatform = remember(linkText) { UrlDetector.detect(linkText) }
    val isValid = linkText.isNotBlank() && detectedPlatform == Platform.TIKTOK

    // Live refresh: the download Service pings this whenever a file finishes,
    // fails, so the gallery updates without the user reopening the app.
    LaunchedEffect(Unit) {
        DownloadEvents.changes.collect {
            history = DownloadHistoryStore.load(context)
        }
    }

    // If the user deletes a downloaded file from Gallery/Files directly, the app should notice
    // and let that slot animate away instead of leaving a broken thumbnail sitting there.
    LaunchedEffect(Unit) {
        while (true) {
            delay(2500)
            val snapshot = history
            val alreadyLeaving = removingKeys
            val missing = withContext(Dispatchers.IO) {
                snapshot.filter { entry ->
                    val uri = entry.fileUri
                    uri != null && entryKey(entry) !in alreadyLeaving && !fileStillExists(context, uri)
                }
            }
            if (missing.isNotEmpty()) {
                val missingKeys = missing.map { entryKey(it) }.toSet()
                removingKeys = removingKeys + missingKeys
                delay(300) // let the exit animation play before the item actually disappears
                DownloadHistoryStore.removeWhere(context) { entryKey(it) in missingKeys }
                history = DownloadHistoryStore.load(context)
                removingKeys = removingKeys - missingKeys
            }
        }
    }

    // Auto-paste detection: check the clipboard once when the screen appears.
    // We never fill the field automatically — just surface a dismissible suggestion.
    LaunchedEffect(Unit) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = clipboard.primaryClip
        if (clip != null && clip.itemCount > 0) {
            val pasted = clip.getItemAt(0).coerceToText(context).toString()
            val found = UrlDetector.extractUrlFromText(pasted)
            if (found != null && UrlDetector.detect(found) == Platform.TIKTOK && found != dismissedSuggestion) {
                clipboardSuggestion = found
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            HeaderBanner()

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 16.dp)
            ) {
                Text(
                    "Paste a TikTok link",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(10.dp))

                AnimatedVisibility(visible = clipboardSuggestion != null) {
                    val suggestion = clipboardSuggestion
                    if (suggestion != null) {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 10.dp),
                            shape = RoundedCornerShape(14.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = BrandSecondary.copy(alpha = 0.12f)
                            )
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Filled.ContentPaste, contentDescription = null, tint = BrandSecondary)
                                Spacer(Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        "TikTok link found in clipboard",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        suggestion,
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                        maxLines = 1
                                    )
                                }
                                TextButton(onClick = {
                                    linkText = suggestion
                                    dismissedSuggestion = suggestion
                                    clipboardSuggestion = null
                                }) {
                                    Text("Use", fontWeight = FontWeight.SemiBold, color = BrandPrimary)
                                }
                                IconButton(onClick = {
                                    dismissedSuggestion = suggestion
                                    clipboardSuggestion = null
                                }) {
                                    Icon(Icons.Filled.Close, contentDescription = "Dismiss")
                                }
                            }
                        }
                    }
                }

                LinkInputField(
                    value = linkText,
                    onValueChange = { linkText = it },
                    isValid = isValid,
                    isEmpty = linkText.isBlank(),
                    onPasteClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val clip = clipboard.primaryClip
                        if (clip != null && clip.itemCount > 0) {
                            val pasted = clip.getItemAt(0).coerceToText(context).toString()
                            val found = UrlDetector.extractUrlFromText(pasted)
                            if (found != null) linkText = found
                        }
                    }
                )

                AnimatedVisibility(visible = linkText.isNotBlank()) {
                    Column {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            if (isValid) "Ready to download" else "That doesn't look like a TikTok link",
                            color = if (isValid) BrandSuccess else MaterialTheme.colorScheme.error,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                        .padding(4.dp)
                ) {
                    DownloadModeChip(
                        label = "Video",
                        icon = Icons.Filled.PlayCircle,
                        selected = !audioOnly,
                        modifier = Modifier.weight(1f),
                        onClick = { audioOnly = false }
                    )
                    DownloadModeChip(
                        label = "Audio only",
                        icon = Icons.Filled.MusicNote,
                        selected = audioOnly,
                        modifier = Modifier.weight(1f),
                        onClick = { audioOnly = true }
                    )
                }

                Spacer(Modifier.height(12.dp))

                val downloadInteractionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                val isPressed by downloadInteractionSource.collectIsPressedAsState()
                val buttonScale by androidx.compose.animation.core.animateFloatAsState(
                    targetValue = if (isPressed) 0.96f else 1f,
                    animationSpec = androidx.compose.animation.core.spring(
                        dampingRatio = androidx.compose.animation.core.Spring.DampingRatioMediumBouncy,
                        stiffness = androidx.compose.animation.core.Spring.StiffnessMedium
                    ),
                    label = "download-button-scale"
                )

                Button(
                    onClick = {
                        if (isValid) {
                            onDownload(linkText.trim(), audioOnly)
                            history = listOf(
                                HistoryEntry(
                                    title = "Downloading...",
                                    platform = "TikTok",
                                    kind = if (audioOnly) "Audio" else "",
                                    fileName = "",
                                    timestamp = System.currentTimeMillis(),
                                    status = "downloading"
                                )
                            ) + history
                            linkText = ""
                        }
                    },
                    interactionSource = downloadInteractionSource,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .graphicsLayer {
                            scaleX = buttonScale
                            scaleY = buttonScale
                        },
                    shape = RoundedCornerShape(14.dp),
                    enabled = isValid,
                    colors = ButtonDefaults.buttonColors(containerColor = BrandPrimary)
                ) {
                    Icon(Icons.Filled.Download, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Download", fontWeight = FontWeight.SemiBold)
                }

                AnimatedVisibility(visible = liveProgress != null) {
                    val progress = liveProgress
                    if (progress != null) {
                        Column(modifier = Modifier.padding(top = 12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    progress.label,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                                )
                                if (!progress.indeterminate) {
                                    Text(
                                        "${progress.percent}%",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = BrandPrimary
                                    )
                                }
                            }
                            Spacer(Modifier.height(6.dp))
                            if (progress.indeterminate) {
                                LinearProgressIndicator(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(4.dp)),
                                    color = BrandPrimary,
                                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                                )
                            } else {
                                val animatedProgress by androidx.compose.animation.core.animateFloatAsState(
                                    targetValue = progress.percent / 100f,
                                    animationSpec = tween(220),
                                    label = "download-progress"
                                )
                                LinearProgressIndicator(
                                    progress = { animatedProgress },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(4.dp)),
                                    color = BrandPrimary,
                                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            Divider(color = MaterialTheme.colorScheme.surfaceVariant)

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Filled.History, contentDescription = null, tint = BrandSecondary)
                Spacer(Modifier.width(8.dp))
                Text("Recent downloads", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            }

            if (history.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "No downloads yet",
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(history, key = { entryKey(it) }) { entry ->
                        var entered by remember(entry) { mutableStateOf(false) }
                        LaunchedEffect(entry) { entered = true }
                        val isLeaving = entryKey(entry) in removingKeys
                        AnimatedVisibility(
                            visible = entered && !isLeaving,
                            enter = fadeIn(tween(280)) +
                                slideInVertically(tween(280)) { -it / 3 } +
                                scaleIn(tween(280), initialScale = 0.85f),
                            exit = fadeOut(tween(260)) +
                                scaleOut(tween(260), targetScale = 0.82f) +
                                shrinkVertically(tween(260))
                        ) {
                            GalleryTile(entry)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DownloadModeChip(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val backgroundColor by androidx.compose.animation.animateColorAsState(
        targetValue = if (selected) BrandPrimary else Color.Transparent,
        label = "chip-bg"
    )
    val contentColor by androidx.compose.animation.animateColorAsState(
        targetValue = if (selected) Color.White else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        label = "chip-content"
    )

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(9.dp))
            .background(backgroundColor)
            .clickable(onClick = onClick)
            .padding(vertical = 9.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = contentColor, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = contentColor)
    }
}

@Composable
fun LinkInputField(
    value: String,
    onValueChange: (String) -> Unit,
    isValid: Boolean,
    isEmpty: Boolean,
    onPasteClick: () -> Unit
) {
    val indicatorColor = when {
        isEmpty -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
        isValid -> BrandSuccess
        else -> MaterialTheme.colorScheme.error
    }

    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        placeholder = { Text("Paste a TikTok video link") },
        singleLine = true,
        shape = RoundedCornerShape(28.dp),
        leadingIcon = {
            AnimatedContent(targetState = if (isEmpty) 0 else if (isValid) 1 else 2, label = "link-status") { state ->
                when (state) {
                    0 -> Icon(Icons.Filled.Link, contentDescription = null, tint = indicatorColor)
                    1 -> Icon(Icons.Filled.CheckCircle, contentDescription = "Valid TikTok link", tint = indicatorColor)
                    else -> Icon(Icons.Filled.ErrorOutline, contentDescription = "Not a TikTok link", tint = indicatorColor)
                }
            }
        },
        trailingIcon = {
            IconButton(onClick = onPasteClick) {
                Icon(Icons.Filled.ContentPaste, contentDescription = "Paste from clipboard")
            }
        },
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = indicatorColor,
            unfocusedBorderColor = MaterialTheme.colorScheme.outline,
            cursorColor = BrandPrimary
        )
    )
}

@Composable
fun IntroScreen() {
    var animateIn by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { animateIn = true }

    val logoScale by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (animateIn) 1f else 0.6f,
        animationSpec = androidx.compose.animation.core.spring(
            dampingRatio = androidx.compose.animation.core.Spring.DampingRatioMediumBouncy,
            stiffness = androidx.compose.animation.core.Spring.StiffnessLow
        ),
        label = "logo-scale"
    )
    val logoAlpha by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (animateIn) 1f else 0f,
        animationSpec = tween(350),
        label = "logo-alpha"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(BrandDark, BrandPrimary))),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Image(
                painter = painterResource(R.drawable.ic_splash_logo),
                contentDescription = null,
                modifier = Modifier
                    .size(96.dp)
                    .graphicsLayer {
                        scaleX = logoScale
                        scaleY = logoScale
                        alpha = logoAlpha
                    }
                    .clip(RoundedCornerShape(26.dp))
            )
            Spacer(Modifier.height(16.dp))
            AnimatedVisibility(
                visible = animateIn,
                enter = fadeIn(tween(400, delayMillis = 150)) +
                    slideInVertically(tween(400, delayMillis = 150)) { it / 3 }
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "TikTok Saver",
                        color = Color.White,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "WITHOUT WATERMARK TIKTOK DOWNLOADS",
                        color = Color.White.copy(alpha = 0.75f),
                        fontSize = 11.sp,
                        letterSpacing = 1.5.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}

@Composable
fun HeaderBanner() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(120.dp)
            .background(
                Brush.linearGradient(listOf(BrandDark, BrandPrimary))
            ),
        contentAlignment = Alignment.CenterStart
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                "TikTok Saver",
                color = Color.White,
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                "Save TikTok videos, photos & sounds — no watermark",
                color = Color.White.copy(alpha = 0.85f),
                fontSize = 13.sp
            )
        }
    }
}

private fun openInExternalViewer(context: Context, entry: HistoryEntry) {
    val uriString = entry.fileUri ?: return
    val uri = Uri.parse(uriString)
    val mimeType = context.contentResolver.getType(uri)
        ?: when (entry.kind) {
            "Images" -> "image/*"
            "Audio" -> "audio/*"
            else -> "video/*"
        }

    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, mimeType)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, "No app found to open this file", Toast.LENGTH_SHORT).show()
    }
}

@Composable
fun GalleryTile(entry: HistoryEntry) {
    val context = LocalContext.current
    val canOpen = entry.status == "done" && entry.fileUri != null

    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface)
            .let {
                if (canOpen) {
                    it.clickable {
                        openInExternalViewer(context, entry)
                    }
                } else it
            }
    ) {
        when (entry.status) {
            "downloading" -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = BrandPrimary, strokeWidth = 3.dp, modifier = Modifier.size(28.dp))
            }
            "failed" -> Box(
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.error.copy(alpha = 0.08f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.ErrorOutline, contentDescription = "Failed", tint = MaterialTheme.colorScheme.error)
            }
            else -> {
                if (entry.kind == "Audio") {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(BrandSecondary.copy(alpha = 0.12f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Filled.MusicNote, contentDescription = null, tint = BrandSecondary)
                    }
                } else {
                    val isImage = entry.kind == "Images"
                    MediaThumbnail(
                        uriString = entry.fileUri,
                        isImage = isImage,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }

        if (entry.status == "done") {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f))))
                    .padding(horizontal = 6.dp, vertical = 4.dp)
            ) {
                val dateFormat = remember { SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()) }
                Text(
                    dateFormat.format(Date(entry.timestamp)),
                    color = Color.White,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

/**
 * Decodes a real thumbnail straight from the saved file — a video frame or a scaled-down
 * image. MediaStore.Downloads items don't reliably get an auto-generated thumbnail via
 * loadThumbnail(), so we read the frame/bitmap ourselves; this works on every API level
 * this app supports.
 */
@Composable
fun MediaThumbnail(uriString: String?, isImage: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var bitmap by remember(uriString) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(uriString) {
        if (uriString != null) {
            bitmap = withContext(Dispatchers.IO) {
                val uri = Uri.parse(uriString)
                try {
                    if (isImage) {
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            val options = BitmapFactory.Options().apply { inSampleSize = 4 }
                            BitmapFactory.decodeStream(input, null, options)
                        }
                    } else {
                        val retriever = MediaMetadataRetriever()
                        try {
                            retriever.setDataSource(context, uri)
                            retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                        } finally {
                            retriever.release()
                        }
                    }
                } catch (e: Exception) {
                    null
                }
            }
        }
    }

    Box(modifier = modifier.background(BrandDark.copy(alpha = 0.06f)), contentAlignment = Alignment.Center) {
        val current = bitmap
        if (current != null) {
            Image(
                bitmap = current.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
            if (!isImage) {
                Icon(
                    Icons.Filled.PlayCircle,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.9f),
                    modifier = Modifier.size(26.dp)
                )
            }
        } else {
            Icon(
                if (isImage) Icons.Filled.Image else Icons.Filled.PlayCircle,
                contentDescription = null,
                tint = BrandSecondary
            )
        }
    }
}
