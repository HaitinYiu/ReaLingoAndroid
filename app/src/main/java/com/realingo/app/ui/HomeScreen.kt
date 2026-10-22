package com.realingo.app.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.provider.Settings as AndroidSettings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Audiotrack
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.realingo.app.core.Engine
import com.realingo.app.core.Lang
import com.realingo.app.core.Protocol
import com.realingo.app.core.PROTOCOLS
import com.realingo.app.core.Settings
import com.realingo.app.core.SettingsStore
import com.realingo.app.core.SourceSpec
import com.realingo.app.core.canSpeak
import com.realingo.app.core.langName
import com.realingo.app.core.languageCodes
import com.realingo.app.core.modelName
import com.realingo.app.core.t
import com.realingo.app.service.TranslationService

private enum class SourceKind(val key: String) { MIC("mic"), SYSTEM("system"), FILE("file") }

@Composable
fun HomeScreen(
    onOpenSettings: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenDiag: () -> Unit,
    onOpenAbout: () -> Unit,
    onExport: (String) -> Unit,
) {
    val p = LocalPalette.current
    val ctx = LocalContext.current
    val settings = SettingsStore.value
    val status by Engine.status.collectAsState()
    val lines by Engine.lines.collectAsState()
    val live by Engine.live.collectAsState()
    val speaking by Engine.speaking.collectAsState()
    val level by Engine.level.collectAsState()
    val detected by Engine.detectedLang.collectAsState()
    val error by Engine.errorMsg.collectAsState()
    val warn by Engine.warnMsg.collectAsState()
    val progress by Engine.fileProgress.collectAsState()

    var sourceKind by remember { mutableStateOf(SourceKind.MIC) }
    var pendingStart by remember { mutableStateOf(false) }
    var showLangPicker by remember { mutableStateOf<String?>(null) }
    var showMenu by remember { mutableStateOf(false) }

    val running = Engine.isRunning

    fun launchSession(spec: SourceSpec) {
        val intent = TranslationService.startIntent(ctx, spec, settings.speak)
        ContextCompat.startForegroundService(ctx, intent)
    }

    val projectionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            launchSession(SourceSpec.Playback(result.resultCode, data))
        } else {
            Toast.makeText(ctx, t("未授权录制系统声音", "System audio permission denied"), Toast.LENGTH_SHORT).show()
        }
    }

    val fileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                ctx.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            launchSession(SourceSpec.File(uri))
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val ok = grants[Manifest.permission.RECORD_AUDIO] ?: true
        if (!ok) {
            Toast.makeText(ctx, t("需要麦克风权限", "Microphone permission required"), Toast.LENGTH_LONG).show()
            pendingStart = false
            return@rememberLauncherForActivityResult
        }
        if (pendingStart) {
            pendingStart = false
            when (sourceKind) {
                SourceKind.MIC -> launchSession(SourceSpec.Mic())
                SourceKind.SYSTEM -> {
                    val mgr = ctx.getSystemService(MediaProjectionManager::class.java)
                    projectionLauncher.launch(mgr.createScreenCaptureIntent())
                }
                SourceKind.FILE -> fileLauncher.launch(arrayOf("audio/*", "video/*"))
            }
        }
    }

    fun begin() {
        if (settings.key().trim().isEmpty()) {
            Toast.makeText(ctx, t("请先在设置里填入 API Key", "Add an API key in Settings first"), Toast.LENGTH_LONG).show()
            onOpenSettings()
            return
        }
        val needsMic = sourceKind != SourceKind.FILE
        val micOk = ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (needsMic && !micOk) {
            pendingStart = true
            val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                perms.add(Manifest.permission.POST_NOTIFICATIONS)
            }
            permissionLauncher.launch(perms.toTypedArray())
            return
        }
        when (sourceKind) {
            SourceKind.MIC -> launchSession(SourceSpec.Mic())
            SourceKind.SYSTEM -> {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                    Toast.makeText(ctx, t("系统声音需要 Android 10 以上", "System audio needs Android 10+"), Toast.LENGTH_LONG).show()
                } else {
                    val mgr = ctx.getSystemService(MediaProjectionManager::class.java)
                    projectionLauncher.launch(mgr.createScreenCaptureIntent())
                }
            }
            SourceKind.FILE -> fileLauncher.launch(arrayOf("audio/*", "video/*"))
        }
    }

    fun toggleSpeak() {
        if (!canSpeak(settings.protocol, settings.targetLang)) {
            Toast.makeText(
                ctx,
                t("这个目标语言没有语音输出", "This target language has no speech output"),
                Toast.LENGTH_LONG
            ).show()
            return
        }
        SettingsStore.update { it.copy(speak = !it.speak) }
    }

    fun toggleSubtitles() {
        if (!AndroidSettings.canDrawOverlays(ctx)) {
            Toast.makeText(ctx, t("请允许悬浮窗权限", "Allow display over other apps"), Toast.LENGTH_LONG).show()
            runCatching {
                ctx.startActivity(
                    Intent(
                        AndroidSettings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${ctx.packageName}")
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
            return
        }
        val next = !SettingsStore.value.sub.show
        SettingsStore.setSub { it.copy(show = next) }
        if (running) {
            ContextCompat.startForegroundService(ctx, TranslationService.toggleSubIntent(ctx))
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 14.dp)
        ) {
            // ---- header
            Row(
                Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(11.dp))
                        .background(AccentGradient),
                    contentAlignment = Alignment.Center
                ) {
                    Text("R", color = Color.White, fontWeight = FontWeight.Black, fontSize = 17.sp)
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "ReaLingo",
                        color = p.ink,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = (-0.2).sp
                    )
                    Text(
                        modelName(settings.protocol, settings.model()),
                        color = p.ink3,
                        fontSize = 11.5.sp,
                        maxLines = 1,
                        modifier = Modifier.clickable { onOpenSettings() }
                    )
                }
                IconButton(onClick = onOpenHistory, modifier = Modifier.size(38.dp)) {
                    Icon(Icons.Rounded.History, null, tint = p.ink2, modifier = Modifier.size(20.dp))
                }
                IconButton(onClick = onOpenSettings, modifier = Modifier.size(38.dp)) {
                    Icon(Icons.Rounded.Settings, null, tint = p.ink2, modifier = Modifier.size(20.dp))
                }
                Box {
                    IconButton(onClick = { showMenu = true }, modifier = Modifier.size(38.dp)) {
                        Icon(Icons.Rounded.MoreVert, null, tint = p.ink2, modifier = Modifier.size(20.dp))
                    }
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text(t("导出 TXT", "Export TXT")) },
                            leadingIcon = { Icon(Icons.Rounded.FileDownload, null) },
                            onClick = { showMenu = false; onExport("txt") }
                        )
                        DropdownMenuItem(
                            text = { Text(t("导出 SRT 字幕", "Export SRT")) },
                            leadingIcon = { Icon(Icons.Rounded.Subtitles, null) },
                            onClick = { showMenu = false; onExport("srt") }
                        )
                        DropdownMenuItem(
                            text = { Text(t("原始事件", "Raw events")) },
                            leadingIcon = { Icon(Icons.Rounded.BugReport, null) },
                            onClick = { showMenu = false; onOpenDiag() }
                        )
                        DropdownMenuItem(
                            text = { Text(t("关于", "About")) },
                            leadingIcon = { Icon(Icons.Rounded.Info, null) },
                            onClick = { showMenu = false; onOpenAbout() }
                        )
                    }
                }
            }

            // ---- language pair
            GlassCard(padding = androidx.compose.foundation.layout.PaddingValues(16.dp, 14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(
                        Modifier.weight(1f).clickable(enabled = !PROTOCOLS.getValue(settings.protocol).autoSource) {
                            showLangPicker = "source"
                        }
                    ) {
                        Text(t("源语言", "From"), color = p.ink3, fontSize = 11.sp)
                        Spacer(Modifier.height(3.dp))
                        Text(
                            langName(settings.sourceLang, com.realingo.app.core.I18n.zh),
                            color = p.ink,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1
                        )
                        if (detected.isNotEmpty() && settings.sourceLang == "auto") {
                            Text(
                                "${t("检测到", "Detected")} ${langName(detected, com.realingo.app.core.I18n.zh)}",
                                color = p.accent,
                                fontSize = 11.sp
                            )
                        }
                    }
                    Text(
                        "→",
                        color = p.ink3,
                        fontSize = 16.sp,
                        modifier = Modifier.padding(horizontal = 10.dp)
                    )
                    Column(
                        Modifier.weight(1f).clickable { showLangPicker = "target" },
                        horizontalAlignment = Alignment.End
                    ) {
                        Text(t("目标语言", "To"), color = p.ink3, fontSize = 11.sp)
                        Spacer(Modifier.height(3.dp))
                        Text(
                            langName(settings.targetLang, com.realingo.app.core.I18n.zh),
                            color = p.ink,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            textAlign = TextAlign.End
                        )
                        Text(
                            "${languageCodes(settings.protocol, settings.model()).size} ${t("种语言", "languages")}",
                            color = p.ink3,
                            fontSize = 11.sp
                        )
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            // ---- transcript
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                StatusPill(
                    text = statusText(status, speaking),
                    color = when (status) {
                        Engine.Status.ERROR -> p.danger
                        Engine.Status.CONNECTED -> p.ok
                        Engine.Status.CONNECTING, Engine.Status.STOPPING -> Color(0xFFFFB020)
                        else -> p.ink3
                    }
                )
                Spacer(Modifier.width(10.dp))
                if (running) LevelMeter(level, Modifier.weight(1f)) else Spacer(Modifier.weight(1f))
            }

            progress?.let { value ->
                LinearProgressIndicator(
                    progress = { value },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp, start = 4.dp, end = 4.dp)
                        .height(3.dp),
                    color = p.accent,
                    trackColor = p.track,
                )
            }

            AnimatedVisibility(visible = error.isNotEmpty() || warn.isNotEmpty()) {
                GlassCard(
                    Modifier.fillMaxWidth().padding(top = 8.dp),
                    padding = androidx.compose.foundation.layout.PaddingValues(12.dp)
                ) {
                    Text(
                        error.ifEmpty { warn },
                        color = if (error.isNotEmpty()) p.danger else p.ink2,
                        fontSize = 12.sp
                    )
                }
            }

            val listState = rememberLazyListState()
            LaunchedEffect(lines.size) {
                if (lines.isNotEmpty()) listState.animateScrollToItem(lines.lastIndex)
            }

            Column(Modifier.weight(1f).fillMaxWidth()) {
                if (lines.isEmpty() && live.target.isEmpty() && live.source.isEmpty()) {
                    EmptyHint(Modifier.weight(1f))
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 10.dp, bottom = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        items(lines, key = { it.id }) { line ->
                            Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                                if (line.source.isNotEmpty()) {
                                    Text(line.source, color = p.ink3, fontSize = 13.sp, lineHeight = 18.sp)
                                }
                                Text(
                                    line.target,
                                    color = p.ink,
                                    fontSize = 17.sp,
                                    lineHeight = 24.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }
                }
                LiveCard(live, running, speaking)
            }

            Spacer(Modifier.height(8.dp))

            // ---- controls
            Column(Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SourceKind.entries.forEach { kind ->
                        val on = sourceKind == kind
                        val label = when (kind) {
                            SourceKind.MIC -> t("麦克风", "Microphone")
                            SourceKind.SYSTEM -> t("系统声音", "System audio")
                            SourceKind.FILE -> t("音频文件", "Audio file")
                        }
                        Box(
                            Modifier
                                .padding(horizontal = 4.dp)
                                .clip(CircleShape)
                                .background(if (on) p.glassStrong else Color.Transparent)
                                .clickable(enabled = !running) { sourceKind = kind }
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text(
                                label,
                                color = if (on) p.ink else p.ink3,
                                fontSize = 12.sp,
                                fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RoundGhost(
                            onClick = { toggleSpeak() },
                            active = settings.speak && canSpeak(settings.protocol, settings.targetLang),
                            enabled = canSpeak(settings.protocol, settings.targetLang)
                        ) {
                            Icon(
                                if (settings.speak) Icons.Rounded.VolumeUp else Icons.Rounded.VolumeOff,
                                null,
                                tint = if (settings.speak) p.accent else p.ink2,
                                modifier = Modifier.size(21.dp)
                            )
                        }

                        val pulse = if (speaking) {
                            val tr = rememberInfiniteTransition(label = "pulse")
                            val v by tr.animateFloat(
                                initialValue = 0.92f, targetValue = 1.06f,
                                animationSpec = infiniteRepeatable(
                                    tween(900), RepeatMode.Reverse
                                ), label = "pulseV"
                            )
                            v
                        } else 1f

                        Box(
                            Modifier
                                .size((68 * pulse).dp)
                                .shadow(14.dp, CircleShape)
                                .clip(CircleShape)
                                .background(
                                    if (running) Brush.linearGradient(
                                        listOf(Color(0xFFFF453A), Color(0xFFFF6B5E))
                                    ) else AccentGradient
                                )
                                .clickable { if (running) Engine.stop() else begin() },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                if (running) Icons.Rounded.Stop else Icons.Rounded.Mic,
                                null,
                                tint = Color.White,
                                modifier = Modifier.size(28.dp)
                            )
                        }

                        RoundGhost(
                            onClick = { toggleSubtitles() },
                            active = settings.sub.show,
                            enabled = true
                        ) {
                            Icon(
                                Icons.Rounded.Subtitles, null,
                                tint = if (settings.sub.show) p.accent else p.ink2,
                                modifier = Modifier.size(21.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    showLangPicker?.let { target ->
        val protocol = settings.protocol
        val codes = languageCodes(protocol, settings.model())
        val items: List<Lang?> = if (target == "source") {
            listOf(null) + codes.mapNotNull { c -> com.realingo.app.core.LANGUAGES.firstOrNull { it.code == c } }
        } else {
            codes.mapNotNull { c -> com.realingo.app.core.LANGUAGES.firstOrNull { it.code == c } }
        }
        PickerSheet(
            title = if (target == "source") t("源语言", "Source language") else t("目标语言", "Target language"),
            items = items,
            label = { l ->
                if (l == null) langName("auto", com.realingo.app.core.I18n.zh)
                else langName(l.code, com.realingo.app.core.I18n.zh)
            },
            note = { l -> l?.let { if (com.realingo.app.core.I18n.zh) it.en else it.zh } },
            selectedLabel = if (target == "source") langName(settings.sourceLang, com.realingo.app.core.I18n.zh)
            else langName(settings.targetLang, com.realingo.app.core.I18n.zh),
            searchable = true,
            onDismiss = { showLangPicker = null },
            onPick = { l ->
                if (target == "source") {
                    SettingsStore.update { it.copy(sourceLang = l?.code ?: "auto") }
                } else if (l != null) {
                    SettingsStore.update { s ->
                        val stillSpeaks = canSpeak(s.protocol, l.code)
                        s.copy(targetLang = l.code, speak = s.speak && stillSpeaks)
                    }
                    showLangPicker = null
                }
            }
        )
    }
}

@Composable
private fun statusText(status: Engine.Status, speaking: Boolean): String = when (status) {
    Engine.Status.CONNECTING -> t("连接中…", "Connecting…")
    Engine.Status.CONNECTED -> if (speaking) t("正在聆听", "Listening") else t("已连接", "Connected")
    Engine.Status.STOPPING -> t("正在收尾…", "Finishing…")
    Engine.Status.CLOSED -> t("已结束", "Closed")
    Engine.Status.ERROR -> t("出错了", "Error")
    Engine.Status.IDLE -> t("待机", "Idle")
}

@Composable
private fun LevelMeter(level: Float, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    Box(
        modifier
            .height(4.dp)
            .clip(CircleShape)
            .background(p.track)
    ) {
        Box(
            Modifier
                .fillMaxWidth(level.coerceIn(0f, 1f))
                .height(4.dp)
                .clip(CircleShape)
                .background(Brush.horizontalGradient(listOf(p.accent, p.accent2)))
        )
    }
}

@Composable
private fun RoundGhost(
    onClick: () -> Unit,
    active: Boolean,
    enabled: Boolean,
    content: @Composable () -> Unit,
) {
    val p = LocalPalette.current
    Box(
        Modifier
            .size(52.dp)
            .clip(CircleShape)
            .background(if (active) p.accent.copy(alpha = 0.12f) else p.glass)
            .clickable(enabled = enabled) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

@Composable
private fun EmptyHint(modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    Column(
        modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(p.glass),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Rounded.GraphicEq, null,
                tint = p.ink3,
                modifier = Modifier.size(28.dp)
            )
        }
        Spacer(Modifier.height(14.dp))
        Text(
            t("点按麦克风开始同声传译", "Tap the microphone to start"),
            color = p.ink3,
            fontSize = 14.sp
        )
        Spacer(Modifier.height(6.dp))
        Text(
            t(
                "译文会实时出现在这里和悬浮字幕里",
                "Translations appear here and in the floating subtitles"
            ),
            color = p.ink3.copy(alpha = 0.7f),
            fontSize = 12.sp,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun LiveCard(live: Engine.Live, running: Boolean, speaking: Boolean) {
    val p = LocalPalette.current
    val visible = live.target.isNotEmpty() || live.source.isNotEmpty() ||
        live.targetStash.isNotEmpty() || live.sourceStash.isNotEmpty()
    AnimatedVisibility(visible = visible || running) {
        GlassCard(
            Modifier.fillMaxWidth().padding(top = 4.dp),
            padding = androidx.compose.foundation.layout.PaddingValues(14.dp, 12.dp),
            strong = true
        ) {
            if (live.source.isNotEmpty() || live.sourceStash.isNotEmpty()) {
                Text(
                    buildAnnotatedString {
                        append(live.source)
                        if (live.sourceStash.isNotEmpty()) {
                            withStyle(SpanStyle(color = p.ink3)) { append(live.sourceStash) }
                        }
                    },
                    color = p.ink2,
                    fontSize = 13.sp,
                    lineHeight = 18.sp
                )
                Spacer(Modifier.height(4.dp))
            }
            if (live.target.isNotEmpty() || live.targetStash.isNotEmpty()) {
                Text(
                    buildAnnotatedString {
                        append(live.target)
                        if (live.targetStash.isNotEmpty()) {
                            withStyle(SpanStyle(color = p.ink3)) { append(live.targetStash) }
                        }
                    },
                    color = p.ink,
                    fontSize = 19.sp,
                    lineHeight = 26.sp,
                    fontWeight = FontWeight.SemiBold
                )
            } else if (running) {
                Text(
                    if (speaking) t("识别中…", "Transcribing…") else t("等待说话…", "Waiting for speech…"),
                    color = p.ink3,
                    fontSize = 14.sp
                )
            }
        }
    }
}
