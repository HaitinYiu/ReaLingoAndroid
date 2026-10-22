package com.realingo.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val CardShape = RoundedCornerShape(22.dp)
val SmallShape = RoundedCornerShape(14.dp)

/** The glass primitive: translucent fill, hairline refraction edge, soft shadow. */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    padding: PaddingValues = PaddingValues(16.dp),
    strong: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val p = LocalPalette.current
    Column(
        modifier
            .shadow(
                elevation = 18.dp,
                shape = CardShape,
                ambientColor = if (p.dark) Color.Black else Color(0x33101A28),
                spotColor = if (p.dark) Color.Black else Color(0x44101A28)
            )
            .clip(CardShape)
            .background(if (strong) p.glassStrong else p.glass)
            .border(
                width = 1.dp,
                brush = Brush.linearGradient(
                    listOf(p.border, p.border.copy(alpha = 0.25f), p.border.copy(alpha = 0.6f)),
                    start = Offset.Zero,
                    end = Offset(420f, 420f)
                ),
                shape = CardShape
            )
            .padding(padding),
        content = content
    )
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    Text(
        text,
        modifier = modifier.padding(start = 4.dp, bottom = 8.dp),
        color = p.ink3,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.4.sp
    )
}

@Composable
fun StatusPill(text: String, color: Color, modifier: Modifier = Modifier) {
    val p = LocalPalette.current
    Row(
        modifier
            .clip(CircleShape)
            .background(p.glassStrong.copy(alpha = if (p.dark) 0.5f else 0.75f))
            .border(1.dp, p.hairline, CircleShape)
            .padding(horizontal = 11.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(7.dp))
        Text(text, color = p.ink2, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

/** Three-way (or n-way) segmented control used for protocols, modes and alignment. */
@Composable
fun Segmented(
    options: List<String>,
    selected: Int,
    modifier: Modifier = Modifier,
    onSelect: (Int) -> Unit,
) {
    val p = LocalPalette.current
    Row(
        modifier
            .clip(SmallShape)
            .background(p.track)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        options.forEachIndexed { i, label ->
            val on = i == selected
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(11.dp))
                    .background(if (on) p.glassStrong else Color.Transparent)
                    .clickable { onSelect(i) }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    label,
                    fontSize = 12.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (on) p.ink else p.ink3,
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium
                )
            }
        }
    }
}

@Composable
fun SettingRow(
    title: String,
    subtitle: String? = null,
    value: String? = null,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val p = LocalPalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = p.ink, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            if (subtitle != null) {
                Text(subtitle, color = p.ink3, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
            }
        }
        if (value != null) {
            Text(
                value,
                color = p.ink2,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 12.dp)
            )
        }
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            trailing()
        }
    }
}

@Composable
fun SwitchRow(title: String, subtitle: String? = null, checked: Boolean, onChange: (Boolean) -> Unit) {
    val p = LocalPalette.current
    SettingRow(
        title = title,
        subtitle = subtitle,
        trailing = {
            Switch(
                checked = checked,
                onCheckedChange = onChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = p.accent,
                    uncheckedThumbColor = Color.White,
                    uncheckedTrackColor = p.track,
                    uncheckedBorderColor = Color.Transparent
                )
            )
        }
    )
}

@Composable
fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    val p = LocalPalette.current
    Box(
        Modifier
            .clip(CircleShape)
            .background(if (selected) p.accent.copy(alpha = 0.16f) else p.track)
            .border(
                1.dp,
                if (selected) p.accent.copy(alpha = 0.55f) else Color.Transparent,
                CircleShape
            )
            .clickable { onClick() }
            .padding(horizontal = 13.dp, vertical = 7.dp)
    ) {
        Text(
            text,
            color = if (selected) p.accent else p.ink2,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> PickerSheet(
    title: String,
    items: List<T>,
    label: (T) -> String,
    note: (T) -> String? = { null },
    selectedLabel: String? = null,
    searchable: Boolean = false,
    onDismiss: () -> Unit,
    onPick: (T) -> Unit,
) {
    val p = LocalPalette.current
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var query by remember { mutableStateOf("") }
    val shown = remember(items, query) {
        if (query.isBlank()) items
        else items.filter {
            label(it).contains(query, true) || (note(it)?.contains(query, true) ?: false)
        }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state,
        containerColor = if (p.dark) Color(0xFF171B23) else Color(0xFFF7F9FC),
        dragHandle = null,
    ) {
        Column(Modifier.padding(horizontal = 18.dp).padding(top = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, color = p.ink, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
            if (searchable) {
                Spacer(Modifier.height(12.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(SmallShape)
                        .background(p.track)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Rounded.Search, null,
                        tint = p.ink3, modifier = Modifier.size(17.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    BasicTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        textStyle = TextStyle(color = p.ink, fontSize = 14.sp),
                        modifier = Modifier.weight(1f),
                        decorationBox = { inner ->
                            if (query.isEmpty()) {
                                Text(
                                    com.realingo.app.core.t("搜索", "Search"),
                                    color = p.ink3, fontSize = 14.sp
                                )
                            }
                            inner()
                        }
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            LazyColumn(Modifier.heightIn(max = 460.dp)) {
                items(shown) { item ->
                    val text = label(item)
                    val isSelected = text == selectedLabel
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                onPick(item)
                                onDismiss()
                            }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text,
                                color = p.ink,
                                fontSize = 15.sp,
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                            )
                            note(item)?.let {
                                Text(
                                    it, color = p.ink3, fontSize = 12.sp,
                                    modifier = Modifier.padding(top = 2.dp),
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        if (isSelected) {
                            Icon(Icons.Rounded.Check, null, tint = p.accent, modifier = Modifier.size(19.dp))
                        }
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}
