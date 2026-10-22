package com.realingo.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * "Liquid glass" tokens, matching ReaLingo's src/styles/glass.css: a tinted canvas, three
 * drifting colour blobs, and translucent cards with a bright refraction edge.
 */
data class Palette(
    val dark: Boolean,
    val canvas: Color,
    val ink: Color,
    val ink2: Color,
    val ink3: Color,
    val glass: Color,
    val glassStrong: Color,
    val border: Color,
    val hairline: Color,
    val accent: Color,
    val accent2: Color,
    val danger: Color,
    val ok: Color,
    val track: Color,
)

val LightPalette = Palette(
    dark = false,
    canvas = Color(0xFFEEF1F7),
    ink = Color(0xFF10131A),
    ink2 = Color(0x9E10131A),
    ink3 = Color(0x6110131A),
    glass = Color(0x99FFFFFF),
    glassStrong = Color(0xF2FFFFFF),
    border = Color(0xE6FFFFFF),
    hairline = Color(0x1410131A),
    accent = Color(0xFF0A84FF),
    accent2 = Color(0xFF5E5CE6),
    danger = Color(0xFFFF453A),
    ok = Color(0xFF30D158),
    track = Color(0x1F10131A),
)

val DarkPalette = Palette(
    dark = true,
    canvas = Color(0xFF0B0D13),
    ink = Color(0xFFF2F4F8),
    ink2 = Color(0xC2F2F4F8),
    ink3 = Color(0x8CF2F4F8),
    glass = Color(0x85222733),
    glassStrong = Color(0xE61C202A),
    border = Color(0x2EFFFFFF),
    hairline = Color(0x1AFFFFFF),
    accent = Color(0xFF3D9BFF),
    accent2 = Color(0xFF7C7AFF),
    danger = Color(0xFFFF6961),
    ok = Color(0xFF32D74B),
    track = Color(0x29FFFFFF),
)

val LocalPalette = staticCompositionLocalOf { LightPalette }

val AccentGradient = Brush.linearGradient(
    listOf(Color(0xFF0A84FF), Color(0xFF5E5CE6), Color(0xFFBF5AF2))
)

@Composable
fun paletteFor(dark: Boolean): Palette = if (dark) DarkPalette else LightPalette

@Composable
fun ReaLingoTheme(dark: Boolean, content: @Composable () -> Unit) {
    val p = paletteFor(dark)
    val scheme = if (dark) {
        darkColorScheme(
            primary = p.accent,
            background = p.canvas,
            surface = p.canvas,
            onBackground = p.ink,
            onSurface = p.ink,
        )
    } else {
        lightColorScheme(
            primary = p.accent,
            background = p.canvas,
            surface = p.canvas,
            onBackground = p.ink,
            onSurface = p.ink,
        )
    }
    val base = Typography()
    val typography = base.copy(
        bodyLarge = base.bodyLarge.copy(fontSize = 16.sp),
        bodyMedium = base.bodyMedium.copy(fontSize = 14.sp),
        labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.2.sp),
    )
    CompositionLocalProvider(LocalPalette provides p) {
        MaterialTheme(colorScheme = scheme, typography = typography, content = content)
    }
}

/** The ambient mesh: three soft colour blobs that give the glass something to refract. */
@Composable
fun AmbientBackground(dark: Boolean = isSystemInDarkTheme()) {
    val p = LocalPalette.current
    Box(Modifier.fillMaxSize().background(p.canvas)) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val a = if (dark) 0.34f else 0.46f

            fun blob(cx: Float, cy: Float, r: Float, inner: Color, outer: Color) {
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(inner.copy(alpha = a), outer.copy(alpha = 0f)),
                        center = Offset(cx, cy),
                        radius = r
                    ),
                    radius = r,
                    center = Offset(cx, cy)
                )
            }

            blob(w * 0.12f, h * 0.06f, w * 0.62f, Color(0xFF6AB7FF), Color(0xFF2B6FFF))
            blob(w * 0.95f, h * 0.34f, w * 0.55f, Color(0xFFD58CFF), Color(0xFF7A5CFF))
            blob(w * 0.42f, h * 0.95f, w * 0.58f, Color(0xFF5FF0C8), Color(0xFF16B3D6))
        }
    }
}
