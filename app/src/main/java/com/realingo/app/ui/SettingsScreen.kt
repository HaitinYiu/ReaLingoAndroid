package com.realingo.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.realingo.app.core.I18n
import com.realingo.app.core.LANGUAGES
import com.realingo.app.core.PRESETS
import com.realingo.app.core.PROTOCOLS
import com.realingo.app.core.Protocol
import com.realingo.app.core.Region
import com.realingo.app.core.SettingsStore
import com.realingo.app.core.VOICES
import com.realingo.app.core.canSpeak
import com.realingo.app.core.langName
import com.realingo.app.core.modelName
import com.realingo.app.core.t
import com.realingo.app.core.wsUrl

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val p = LocalPalette.current
    val settings = SettingsStore.value
    var picker by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Rounded.ArrowBack, null, tint = p.ink)
            }
            Text(t("设置", "Settings"), color = p.ink, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 14.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ---------------------------------------------------------- interface
            SectionLabel(t("接口", "ENDPOINT"))
            GlassCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        ProtocolChip("Qwen3.5", Protocol.QWEN35, settings.protocol) { setProtocol(Protocol.QWEN35) }
                        ProtocolChip("Qwen3.8", Protocol.QWEN38, settings.protocol) { setProtocol(Protocol.QWEN38) }
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        ProtocolChip("OpenAI", Protocol.OPENAI, settings.protocol) { setProtocol(Protocol.OPENAI) }
                        ProtocolChip("Gemini", Protocol.GEMINI, settings.protocol) { setProtocol(Protocol.GEMINI) }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    when (settings.protocol) {
                        Protocol.QWEN35 -> t(
                            "阿里云百炼 Qwen3.5 / Qwen3，60 / 18 语种",
                            "Alibaba Model Studio Qwen3.5 / Qwen3, 60 / 18 languages"
                        )
                        Protocol.QWEN38 -> t(
                            "阿里云百炼 Qwen3.8，60 语种，源语言自动检测",
                            "Alibaba Model Studio Qwen3.8, 60 languages, source auto-detected"
                        )
                        Protocol.OPENAI -> t(
                            "OpenAI gpt-realtime-translate，输出 13 种语言",
                            "OpenAI gpt-realtime-translate, 13 output languages"
                        )
                        Protocol.GEMINI -> t(
                            "Google Gemini Live Translate，54 种语言",
                            "Google Gemini Live Translate, 54 languages"
                        )
                    },
                    color = p.ink3, fontSize = 11.5.sp
                )

                Spacer(Modifier.height(14.dp))
                SectionLabel(t("API KEY", "API KEY"))
                val keyLabel = when (settings.protocol.vendor) {
                    com.realingo.app.core.Vendor.QWEN -> "DashScope"
                    com.realingo.app.core.Vendor.OPENAI -> "OpenAI"
                    com.realingo.app.core.Vendor.GEMINI -> "Google AI"
                }
                GlassField(
                    value = settings.key(),
                    onChange = { k -> SettingsStore.update { it.withKey(k) } },
                    placeholder = "$keyLabel API Key",
                    password = true
                )
                Text(
                    t(
                        "密钥只保存在本机应用私有目录，不会上传。",
                        "The key stays in this app's private storage and is never uploaded."
                    ),
                    color = p.ink3, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp)
                )

                if (settings.protocol.vendor == com.realingo.app.core.Vendor.QWEN) {
                    Spacer(Modifier.height(12.dp))
                    SectionLabel(t("区域", "REGION"))
                    Segmented(
                        options = listOf(t("北京", "Beijing"), t("新加坡", "Singapore")),
                        selected = if (settings.region == Region.BEIJING) 0 else 1,
                        onSelect = { i ->
                            SettingsStore.update { it.copy(region = if (i == 0) Region.BEIJING else Region.SINGAPORE) }
                        }
                    )
                    Spacer(Modifier.height(10.dp))
                    GlassField(
                        value = settings.workspaceId,
                        onChange = { v -> SettingsStore.update { it.copy(workspaceId = v) } },
                        placeholder = t("工作空间 ID（可选）", "Workspace ID (optional)")
                    )
                }
            }

            // ---------------------------------------------------------- model
            SectionLabel(t("模型", "MODEL"))
            GlassCard {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    PRESETS.getValue(settings.protocol).forEach { preset ->
                        Chip(
                            text = "${preset.name} · ${preset.langs}",
                            selected = settings.model() == preset.id
                        ) {
                            SettingsStore.update { it.copy(model = preset.id) }
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                GlassField(
                    value = settings.model,
                    onChange = { v -> SettingsStore.update { it.copy(model = v) } },
                    placeholder = modelName(settings.protocol, settings.model())
                )
                Spacer(Modifier.height(10.dp))
                Text(t("接入地址", "Endpoint"), color = p.ink3, fontSize = 11.sp)
                Text(
                    settings.wsUrl(),
                    color = p.ink2,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 3.dp)
                )
                Spacer(Modifier.height(8.dp))
                GlassField(
                    value = settings.url,
                    onChange = { v -> SettingsStore.update { it.copy(url = v) } },
                    placeholder = t("自定义地址（可选，兼容代理/私有部署）", "Custom endpoint (optional)")
                )
            }

            // ---------------------------------------------------------- languages
            SectionLabel(t("语言", "LANGUAGES"))
            GlassCard {
                SettingRow(
                    title = t("源语言", "Source language"),
                    value = langName(settings.sourceLang, I18n.zh),
                    onClick = if (PROTOCOLS.getValue(settings.protocol).autoSource) null else {
                        { picker = "source" }
                    }
                )
                if (PROTOCOLS.getValue(settings.protocol).autoSource) {
                    Text(
                        t("该接口始终自动识别源语言", "This endpoint always detects the source"),
                        color = p.ink3, fontSize = 11.sp
                    )
                }
                Box(Modifier.height(1.dp).fillMaxWidth().background(p.hairline))
                SettingRow(
                    title = t("目标语言", "Target language"),
                    value = langName(settings.targetLang, I18n.zh),
                    onClick = { picker = "target" }
                )
            }

            // ---------------------------------------------------------- speaking
            SectionLabel(t("朗读译文", "SPEAK TRANSLATION"))
            GlassCard {
                SwitchRow(
                    title = t("朗读译文", "Read the translation aloud"),
                    subtitle = if (canSpeak(settings.protocol, settings.targetLang))
                        t("由模型直接合成，不另接 TTS", "Synthesised by the model itself, no extra TTS")
                    else
                        t("该目标语言没有语音输出", "No speech output for this target language"),
                    checked = settings.speak && canSpeak(settings.protocol, settings.targetLang),
                    onChange = { on ->
                        SettingsStore.update {
                            it.copy(speak = on && canSpeak(it.protocol, it.targetLang))
                        }
                    }
                )
                if (!PROTOCOLS.getValue(settings.protocol).serverVoice && canSpeak(settings.protocol, settings.targetLang)) {
                    Box(Modifier.height(1.dp).fillMaxWidth().background(p.hairline))
                    SettingRow(
                        title = t("音色", "Voice"),
                        value = settings.voice,
                        onClick = { picker = "voice" }
                    )
                } else if (PROTOCOLS.getValue(settings.protocol).serverVoice) {
                    Text(
                        t("音色由服务端决定", "The service chooses the voice"),
                        color = p.ink3, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp)
                    )
                }
            }

            // ---------------------------------------------------------- hotwords
            SectionLabel(t("热词", "HOTWORDS"))
            GlassCard {
                if (settings.hotwords.isEmpty()) {
                    Text(
                        t("原文词 → 期望译法，最多 1000 条", "Source term → preferred translation, up to 1000"),
                        color = p.ink3, fontSize = 12.sp
                    )
                } else {
                    settings.hotwords.entries.forEachIndexed { index, (src, dst) ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(src, color = p.ink, fontSize = 14.sp, modifier = Modifier.weight(1f))
                            Text("→", color = p.ink3, fontSize = 13.sp)
                            Text(
                                dst,
                                color = p.ink2,
                                fontSize = 14.sp,
                                modifier = Modifier.weight(1f).padding(start = 10.dp)
                            )
                            IconButton(
                                onClick = {
                                    SettingsStore.update { s ->
                                        val m = LinkedHashMap(s.hotwords)
                                        m.remove(src)
                                        s.copy(hotwords = m)
                                    }
                                },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(Icons.Rounded.Close, null, tint = p.ink3, modifier = Modifier.size(16.dp))
                            }
                        }
                        if (index != settings.hotwords.size - 1) {
                            Box(Modifier.height(1.dp).fillMaxWidth().background(p.hairline))
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier
                        .clip(CircleShape)
                        .background(p.track)
                        .clickable { picker = "hotword" }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Rounded.Add, null, tint = p.accent, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(t("添加热词", "Add a hotword"), color = p.accent, fontSize = 13.sp)
                }
            }

            // ---------------------------------------------------------- subtitles
            SectionLabel(t("悬浮字幕", "FLOATING SUBTITLES"))
            GlassCard {
                SwitchRow(
                    title = t("显示悬浮字幕", "Show floating subtitles"),
                    subtitle = t("需要「显示在其他应用上层」权限", "Needs the display-over-other-apps permission"),
                    checked = settings.sub.show,
                    onChange = { on -> SettingsStore.setSub { it.copy(show = on) } }
                )
                Box(Modifier.height(1.dp).fillMaxWidth().background(p.hairline))
                Text(
                    t("显示内容", "Content"),
                    color = p.ink2, fontSize = 13.sp, modifier = Modifier.padding(top = 12.dp, bottom = 8.dp)
                )
                Segmented(
                    options = listOf(t("双语", "Both"), t("仅译文", "Target"), t("仅原文", "Source")),
                    selected = when (settings.sub.mode) {
                        "target" -> 1
                        "source" -> 2
                        else -> 0
                    },
                    onSelect = { i ->
                        SettingsStore.setSub {
                            it.copy(mode = listOf("both", "target", "source")[i])
                        }
                    }
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    t("对齐", "Alignment"),
                    color = p.ink2, fontSize = 13.sp, modifier = Modifier.padding(bottom = 8.dp)
                )
                Segmented(
                    options = listOf(t("左", "Left"), t("居中", "Center"), t("右", "Right")),
                    selected = when (settings.sub.align) {
                        "left" -> 0
                        "right" -> 2
                        else -> 1
                    },
                    onSelect = { i ->
                        SettingsStore.setSub { it.copy(align = listOf("left", "center", "right")[i]) }
                    }
                )

                Spacer(Modifier.height(12.dp))
                SliderRow(
                    label = t("背景不透明度", "Background opacity"),
                    value = settings.sub.opacity,
                    range = 0f..1f,
                    valueText = "${(settings.sub.opacity * 100).toInt()}%",
                    onChange = { v -> SettingsStore.setSub { it.copy(opacity = v) } }
                )
                SliderRow(
                    label = t("译文字号", "Translation size"),
                    value = settings.sub.tgtSize.toFloat(),
                    range = 12f..72f,
                    valueText = "${settings.sub.tgtSize}",
                    steps = 59,
                    onChange = { v -> SettingsStore.setSub { it.copy(tgtSize = v.toInt()) } }
                )
                SliderRow(
                    label = t("原文字号", "Source size"),
                    value = settings.sub.srcSize.toFloat(),
                    range = 12f..72f,
                    valueText = "${settings.sub.srcSize}",
                    steps = 59,
                    onChange = { v -> SettingsStore.setSub { it.copy(srcSize = v.toInt()) } }
                )

                Spacer(Modifier.height(6.dp))
                Text(t("译文颜色", "Translation colour"), color = p.ink2, fontSize = 13.sp)
                ColorRow(settings.sub.color) { c -> SettingsStore.setSub { it.copy(color = c) } }
                Spacer(Modifier.height(8.dp))
                Text(t("原文颜色", "Source colour"), color = p.ink2, fontSize = 13.sp)
                ColorRow(settings.sub.srcColor) { c -> SettingsStore.setSub { it.copy(srcColor = c) } }

                Spacer(Modifier.height(8.dp))
                SwitchRow(
                    title = t("文字描边", "Text outline"),
                    checked = settings.sub.outline,
                    onChange = { on -> SettingsStore.setSub { it.copy(outline = on) } }
                )
                SwitchRow(
                    title = t("长句自动加高", "Grow for long sentences"),
                    subtitle = t("关闭则单行滚动，只留最新", "Off: one line, scrolled to the newest"),
                    checked = settings.sub.grow,
                    onChange = { on -> SettingsStore.setSub { it.copy(grow = on) } }
                )
                SwitchRow(
                    title = t("锁定（点击穿透）", "Lock (click-through)"),
                    subtitle = t("锁定后不挡住下面的播放器；解锁可拖动", "Unlocked lets you drag the bar"),
                    checked = settings.sub.locked,
                    onChange = { on -> SettingsStore.setSub { it.copy(locked = on) } }
                )
            }

            // ---------------------------------------------------------- history
            SectionLabel(t("记录", "RECORDING"))
            GlassCard {
                SwitchRow(
                    title = t("保存翻译历史", "Save translation history"),
                    subtitle = t(
                        "默认不记录；开启后这一场存成一个文件",
                        "Off by default; each session becomes one file"
                    ),
                    checked = settings.history,
                    onChange = { on -> SettingsStore.update { it.copy(history = on) } }
                )
            }

            // ---------------------------------------------------------- appearance
            SectionLabel(t("外观", "APPEARANCE"))
            GlassCard {
                Text(t("主题", "Theme"), color = p.ink2, fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                Segmented(
                    options = listOf(t("跟随系统", "System"), t("浅色", "Light"), t("深色", "Dark")),
                    selected = when (settings.theme) {
                        "light" -> 1
                        "dark" -> 2
                        else -> 0
                    },
                    onSelect = { i ->
                        SettingsStore.update {
                            it.copy(theme = listOf("system", "light", "dark")[i])
                        }
                    }
                )
                Spacer(Modifier.height(12.dp))
                Text(t("界面语言", "Interface language"), color = p.ink2, fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                Segmented(
                    options = listOf("中文", "English"),
                    selected = if (I18n.zh) 0 else 1,
                    onSelect = { i ->
                        SettingsStore.update { it.copy(locale = if (i == 0) "zh" else "en") }
                        I18n.zh = i == 0
                    }
                )
            }

            Text(
                t(
                    "ReaLingo for Android · 基于 noSugarK/ReaLingo 移植（MIT）",
                    "ReaLingo for Android · ported from noSugarK/ReaLingo (MIT)"
                ),
                color = p.ink3,
                fontSize = 11.sp,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
    }

    // ------------------------------------------------------------- pickers
    when (picker) {
        "source" -> PickerSheet(
            title = t("源语言", "Source language"),
            items = listOf<com.realingo.app.core.Lang?>(null) + LANGUAGES,
            label = { l -> langName(l?.code ?: "auto", I18n.zh) },
            note = { l -> l?.let { if (I18n.zh) it.en else it.zh } },
            selectedLabel = langName(settings.sourceLang, I18n.zh),
            searchable = true,
            onDismiss = { picker = null },
            onPick = { l -> SettingsStore.update { it.copy(sourceLang = l?.code ?: "auto") } }
        )

        "target" -> PickerSheet(
            title = t("目标语言", "Target language"),
            items = com.realingo.app.core.languageCodes(settings.protocol, settings.model())
                .mapNotNull { c -> LANGUAGES.firstOrNull { it.code == c } },
            label = { l -> langName(l.code, I18n.zh) },
            note = { l -> if (I18n.zh) l.en else l.zh },
            selectedLabel = langName(settings.targetLang, I18n.zh),
            searchable = true,
            onDismiss = { picker = null },
            onPick = { l ->
                SettingsStore.update { s ->
                    s.copy(
                        targetLang = l.code,
                        speak = s.speak && canSpeak(s.protocol, l.code)
                    )
                }
            }
        )

        "voice" -> PickerSheet(
            title = t("音色", "Voice"),
            items = VOICES,
            label = { v -> "${v.id} · ${v.zh}" },
            note = { v -> if (I18n.zh) v.descZh else v.descEn },
            selectedLabel = "${settings.voice} · ${VOICES.firstOrNull { it.id == settings.voice }?.zh ?: ""}",
            searchable = true,
            onDismiss = { picker = null },
            onPick = { v -> SettingsStore.update { it.copy(voice = v.id) } }
        )

        "hotword" -> HotwordDialog(
            onDismiss = { picker = null },
            onAdd = { src, dst ->
                SettingsStore.update { s ->
                    val m = LinkedHashMap(s.hotwords)
                    m[src] = dst
                    s.copy(hotwords = m)
                }
                picker = null
            }
        )
    }
}

private fun setProtocol(p: Protocol) {
    SettingsStore.update { s ->
        val saved = s.savedModels[p.id]
        val next = saved ?: PRESETS.getValue(p).first().id
        val savedModels = s.savedModels + (s.protocol.id to s.model)
        s.copy(
            protocol = p,
            model = next,
            savedModels = savedModels,
            sourceLang = if (PROTOCOLS.getValue(p).autoSource) "auto" else s.sourceLang,
            speak = s.speak && canSpeak(p, s.targetLang),
        )
    }
}

@Composable
private fun ProtocolChip(label: String, protocol: Protocol, current: Protocol, onClick: () -> Unit) {
    val p = LocalPalette.current
    val on = protocol == current
    Box(
        Modifier
            .fillMaxWidth()
            .clip(SmallShape)
            .background(if (on) p.accent.copy(alpha = 0.14f) else p.track)
            .border(1.dp, if (on) p.accent.copy(alpha = 0.5f) else Color.Transparent, SmallShape)
            .clickable { onClick() }
            .padding(vertical = 11.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = if (on) p.accent else p.ink2,
            fontSize = 13.sp,
            fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium
        )
    }
}

@Composable
fun GlassField(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    password: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    val p = LocalPalette.current
    var shown by remember { mutableStateOf(false) }
    Row(
        modifier
            .fillMaxWidth()
            .clip(SmallShape)
            .background(p.track)
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = TextStyle(color = p.ink, fontSize = 14.sp),
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            visualTransformation = if (password && !shown) PasswordVisualTransformation()
            else VisualTransformation.None,
            modifier = Modifier.weight(1f),
            decorationBox = { inner ->
                if (value.isEmpty()) {
                    Text(placeholder, color = p.ink3, fontSize = 13.sp)
                }
                inner()
            }
        )
        if (password) {
            IconButton(onClick = { shown = !shown }, modifier = Modifier.size(28.dp)) {
                Icon(
                    if (shown) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                    null,
                    tint = p.ink3,
                    modifier = Modifier.size(17.dp)
                )
            }
        }
    }
}

@Composable
private fun SliderRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    valueText: String,
    steps: Int = 0,
    onChange: (Float) -> Unit,
) {
    val p = LocalPalette.current
    Column(Modifier.padding(vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = p.ink2, fontSize = 13.sp)
            Text(valueText, color = p.ink3, fontSize = 12.sp)
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            valueRange = range,
            steps = steps,
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = p.accent,
                inactiveTrackColor = p.track,
            )
        )
    }
}

private val COLOR_CHOICES = listOf(
    "#FFFFFF", "#FFE58A", "#7FE7FF", "#A8FFB0", "#FFB3D9", "#C9B6FF", "#10131A"
)

@Composable
private fun ColorRow(selected: String, onPick: (String) -> Unit) {
    val p = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        COLOR_CHOICES.forEach { hex ->
            val color = runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrDefault(Color.White)
            Box(
                Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(color)
                    .border(
                        width = if (hex.equals(selected, true)) 3.dp else 1.dp,
                        color = if (hex.equals(selected, true)) p.accent else p.hairline,
                        shape = CircleShape
                    )
                    .clickable { onPick(hex) }
            )
        }
    }
}

@Composable
private fun HotwordDialog(onDismiss: () -> Unit, onAdd: (String, String) -> Unit) {
    val p = LocalPalette.current
    var src by remember { mutableStateOf("") }
    var dst by remember { mutableStateOf("") }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .clip(CardShape)
                .background(if (p.dark) Color(0xFF1B1F28) else Color.White)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(t("添加热词", "Add a hotword"), color = p.ink, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            GlassField(src, { src = it }, t("原文词", "Source term"))
            GlassField(dst, { dst = it }, t("期望译法", "Preferred translation"))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                Text(
                    t("取消", "Cancel"),
                    color = p.ink3,
                    fontSize = 14.sp,
                    modifier = Modifier.clickable { onDismiss() }.padding(10.dp)
                )
                Spacer(Modifier.width(8.dp))
                Box(
                    Modifier
                        .clip(CircleShape)
                        .background(
                            if (src.isNotBlank() && dst.isNotBlank()) AccentGradient
                            else Brush.linearGradient(listOf(p.track, p.track))
                        )
                        .clickable(enabled = src.isNotBlank() && dst.isNotBlank()) {
                            onAdd(src.trim(), dst.trim())
                        }
                        .padding(horizontal = 16.dp, vertical = 9.dp)
                ) {
                    Text(t("添加", "Add"), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}
