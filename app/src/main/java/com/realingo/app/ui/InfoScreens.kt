package com.realingo.app.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.realingo.app.BuildConfig
import com.realingo.app.core.Engine
import com.realingo.app.core.t

/** The `rt://raw` equivalent: every server frame, verbatim, for protocol debugging. */
@Composable
fun DiagScreen(onBack: () -> Unit) {
    val p = LocalPalette.current
    val raw by Engine.rawLog.collectAsState()
    val clip = LocalClipboardManager.current
    val listState = rememberLazyListState()

    LaunchedEffect(raw.size) {
        if (raw.isNotEmpty()) listState.animateScrollToItem(raw.lastIndex)
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Rounded.ArrowBack, null, tint = p.ink)
            }
            Column(Modifier.weight(1f)) {
                Text(t("原始事件", "Raw events"), color = p.ink, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text(
                    t("最近 300 帧，音频已折叠为字节数", "Last 300 frames, audio elided"),
                    color = p.ink3, fontSize = 11.sp
                )
            }
            IconButton(onClick = { clip.setText(AnnotatedString(raw.joinToString("\n"))) }) {
                Icon(Icons.Rounded.ContentCopy, null, tint = p.ink2, modifier = Modifier.size(19.dp))
            }
        }
        if (raw.isEmpty()) {
            Column(
                Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(t("开始一次翻译后，这里会出现服务端帧", "Start a session to see server frames"), color = p.ink3, fontSize = 13.sp)
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().navigationBarsPadding(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(raw) { line ->
                    Text(
                        line,
                        color = p.ink2,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        lineHeight = 14.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(p.glass)
                            .padding(8.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun AboutScreen(onBack: () -> Unit) {
    val p = LocalPalette.current
    val ctx = LocalContext.current
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Rounded.ArrowBack, null, tint = p.ink)
            }
            Text(t("关于", "About"), color = p.ink, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 18.dp).padding(top = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                Modifier
                    .size(78.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(AccentGradient),
                contentAlignment = Alignment.Center
            ) {
                Text("R", color = Color.White, fontSize = 36.sp, fontWeight = FontWeight.Black)
            }
            Spacer(Modifier.height(14.dp))
            Text("ReaLingo", color = p.ink, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text(
                "v${BuildConfig.VERSION_NAME} · Android",
                color = p.ink3, fontSize = 12.sp
            )
            Spacer(Modifier.height(18.dp))
            GlassCard(Modifier.fillMaxWidth()) {
                Text(
                    t(
                        "实时语音翻译 —— 支持阿里云百炼 Qwen、OpenAI 与 Google Gemini 的同声传译工具。三种音频来源、四种接口、源语言自动检测、朗读译文、悬浮字幕、翻译历史与 TXT / SRT 导出。",
                        "Realtime speech translation for Alibaba Model Studio Qwen, OpenAI and Google Gemini. Three audio sources, four endpoints, source auto-detection, spoken translations, floating subtitles, history and TXT / SRT export."
                    ),
                    color = p.ink2,
                    fontSize = 13.sp,
                    lineHeight = 20.sp
                )
                Spacer(Modifier.height(14.dp))
                Box(Modifier.height(1.dp).fillMaxWidth().background(p.hairline))
                Spacer(Modifier.height(12.dp))
                Row(
                    Modifier.fillMaxWidth().clickable {
                        runCatching {
                            ctx.startActivity(
                                Intent(
                                    Intent.ACTION_VIEW,
                                    Uri.parse("https://github.com/noSugarK/ReaLingo")
                                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                    },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            t("原项目", "Original project"),
                            color = p.ink, fontSize = 14.sp, fontWeight = FontWeight.Medium
                        )
                        Text(
                            "github.com/noSugarK/ReaLingo",
                            color = p.accent, fontSize = 12.sp
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    t(
                        "本应用是原桌面项目的 Android 移植，遵循 MIT 许可证；协议字段、轮次配对与重采样算法均对照原实现移植。",
                        "An Android port of the desktop project, MIT licensed; protocol fields, turn pairing and the resampler follow the original implementation."
                    ),
                    color = p.ink3, fontSize = 11.5.sp, lineHeight = 17.sp
                )
            }
        }
    }
}
