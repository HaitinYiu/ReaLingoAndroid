package com.realingo.app.ui

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.realingo.app.core.Export
import com.realingo.app.core.HistoryStore
import com.realingo.app.core.langName
import com.realingo.app.core.t
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Translation history: sessions on disk, searchable, exportable, deletable. */
@Composable
fun HistoryScreen(onBack: () -> Unit, onShare: (String, String) -> Unit) {
    val p = LocalPalette.current
    val ctx = LocalContext.current
    var sessions by remember { mutableStateOf<List<HistoryStore.SessionMeta>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    var openId by remember { mutableStateOf<String?>(null) }
    var entries by remember { mutableStateOf<List<HistoryStore.Entry>>(emptyList()) }
    var confirmDelete by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableStateOf(0) }

    LaunchedEffect(refresh, query) {
        sessions = HistoryStore.search(ctx, query)
    }
    LaunchedEffect(openId) {
        entries = openId?.let { HistoryStore.read(ctx, it) } ?: emptyList()
    }

    val dateFmt = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { if (openId != null) openId = null else onBack() }) {
                Icon(Icons.Rounded.ArrowBack, null, tint = p.ink)
            }
            Column(Modifier.weight(1f)) {
                Text(
                    if (openId == null) t("翻译历史", "History") else t("这一场", "This session"),
                    color = p.ink, fontSize = 20.sp, fontWeight = FontWeight.Bold
                )
                if (openId != null) {
                    Text(
                        "${entries.size} ${t("句", "lines")}",
                        color = p.ink3, fontSize = 11.sp
                    )
                }
            }
            if (openId != null) {
                IconButton(onClick = {
                    val id = openId ?: return@IconButton
                    onShare("translation-$id.txt", Export.txt(entries))
                }) {
                    Icon(Icons.Rounded.FileDownload, null, tint = p.ink2, modifier = Modifier.size(20.dp))
                }
                IconButton(onClick = { confirmDelete = openId }) {
                    Icon(Icons.Rounded.Delete, null, tint = p.danger, modifier = Modifier.size(20.dp))
                }
            }
        }

        if (openId == null) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp)
                    .clip(SmallShape)
                    .background(p.track)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Rounded.Search, null, tint = p.ink3, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(8.dp))
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    textStyle = TextStyle(color = p.ink, fontSize = 14.sp),
                    modifier = Modifier.weight(1f),
                    decorationBox = { inner ->
                        if (query.isEmpty()) {
                            Text(t("搜索译文或原文", "Search translations"), color = p.ink3, fontSize = 13.sp)
                        }
                        inner()
                    }
                )
            }
            Spacer(Modifier.height(10.dp))
            if (sessions.isEmpty()) {
                Column(
                    Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        if (query.isEmpty()) t("还没有保存过翻译", "No saved sessions yet")
                        else t("没有匹配的场次", "Nothing matched"),
                        color = p.ink3, fontSize = 14.sp
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        t(
                            "在设置里打开「保存翻译历史」后，每场会存成一个文件",
                            "Turn on “Save translation history” in Settings"
                        ),
                        color = p.ink3.copy(alpha = 0.7f), fontSize = 12.sp
                    )
                }
            } else {
                LazyColumn(
                    Modifier.fillMaxSize().navigationBarsPadding(),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(sessions, key = { it.id }) { s ->
                        GlassCard(
                            Modifier.fillMaxWidth().clickable { openId = s.id },
                            padding = PaddingValues(14.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        "${dateFmt.format(Date(s.at))} · ${langName(s.source, com.realingo.app.core.I18n.zh)} → ${langName(s.target, com.realingo.app.core.I18n.zh)}",
                                        color = p.ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold
                                    )
                                    if (s.preview.isNotEmpty()) {
                                        Text(
                                            s.preview,
                                            color = p.ink3,
                                            fontSize = 12.5.sp,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.padding(top = 3.dp)
                                        )
                                    }
                                    Text(
                                        "${s.count} ${t("句", "lines")} · ${s.model}",
                                        color = p.ink3.copy(alpha = 0.75f),
                                        fontSize = 11.sp,
                                        modifier = Modifier.padding(top = 4.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize().navigationBarsPadding(),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(entries) { e ->
                    Column(Modifier.fillMaxWidth()) {
                        if (e.source.isNotEmpty()) {
                            Text(e.source, color = p.ink3, fontSize = 13.sp)
                            Spacer(Modifier.height(2.dp))
                        }
                        Text(e.target, color = p.ink, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
    }

    confirmDelete?.let { id ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text(t("删除这一场？", "Delete this session?")) },
            text = { Text(t("文件会从本机删除，无法恢复。", "The file is removed from this device.")) },
            confirmButton = {
                TextButton(onClick = {
                    HistoryStore.delete(ctx, id)
                    confirmDelete = null
                    openId = null
                    refresh++
                }) { Text(t("删除", "Delete"), color = p.danger) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = null }) { Text(t("取消", "Cancel")) }
            },
            containerColor = if (p.dark) Color(0xFF1B1F28) else Color.White,
            titleContentColor = p.ink,
            textContentColor = p.ink2,
        )
    }
}
