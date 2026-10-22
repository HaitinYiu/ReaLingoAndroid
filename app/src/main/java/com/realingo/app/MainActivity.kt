package com.realingo.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import com.realingo.app.core.Engine
import com.realingo.app.core.Export
import com.realingo.app.core.HistoryStore
import com.realingo.app.core.I18n
import com.realingo.app.core.SettingsStore
import com.realingo.app.core.t
import com.realingo.app.ui.AboutScreen
import com.realingo.app.ui.AmbientBackground
import com.realingo.app.ui.DiagScreen
import com.realingo.app.ui.HistoryScreen
import com.realingo.app.ui.HomeScreen
import com.realingo.app.ui.ReaLingoTheme
import com.realingo.app.ui.SettingsScreen
import java.io.File

class ReaLingoApp : android.app.Application() {
    override fun onCreate() {
        super.onCreate()
        SettingsStore.init(this)
        I18n.zh = SettingsStore.value.locale != "en"
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SettingsStore.init(this)
        I18n.zh = SettingsStore.value.locale != "en"
        enableEdgeToEdge()
        setContent { AppRoot() }
    }
}

private enum class Screen { HOME, SETTINGS, HISTORY, DIAG, ABOUT }

@Composable
private fun AppRoot() {
    val settings = SettingsStore.value
    val dark = when (settings.theme) {
        "light" -> false
        "dark" -> true
        else -> isSystemInDarkTheme()
    }
    ReaLingoTheme(dark) {
        val view = LocalView.current
        val ctx = LocalContext.current
        if (!view.isInEditMode) {
            SideEffect {
                val window = (view.context as? Activity)?.window ?: return@SideEffect
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
        }

        var screen by remember { mutableStateOf(Screen.HOME) }

        Box(Modifier.fillMaxSize()) {
            AmbientBackground(dark)
            when (screen) {
                Screen.HOME -> HomeScreen(
                    onOpenSettings = { screen = Screen.SETTINGS },
                    onOpenHistory = { screen = Screen.HISTORY },
                    onOpenDiag = { screen = Screen.DIAG },
                    onOpenAbout = { screen = Screen.ABOUT },
                    onExport = { kind ->
                        val rows = Engine.lines.value.map { HistoryStore.Entry(it.at, it.source, it.target) }
                        if (rows.isEmpty()) {
                            Toast.makeText(
                                ctx,
                                t("还没有可导出的译文", "Nothing to export yet"),
                                Toast.LENGTH_SHORT
                            ).show()
                        } else {
                            val stamp = System.currentTimeMillis()
                            if (kind == "srt") {
                                shareText(ctx, "translation-$stamp.srt", Export.srt(rows))
                            } else {
                                shareText(ctx, "translation-$stamp.txt", Export.txt(rows))
                            }
                        }
                    }
                )

                Screen.SETTINGS -> SettingsScreen(onBack = { screen = Screen.HOME })
                Screen.HISTORY -> HistoryScreen(
                    onBack = { screen = Screen.HOME },
                    onShare = { name, content -> shareText(ctx, name, content) }
                )
                Screen.DIAG -> DiagScreen(onBack = { screen = Screen.HOME })
                Screen.ABOUT -> AboutScreen(onBack = { screen = Screen.HOME })
            }
        }

        BackHandler(enabled = screen != Screen.HOME) { screen = Screen.HOME }
    }
}

/** Writes the export to the cache and hands it to whatever the user picks. */
private fun shareText(context: Context, fileName: String, content: String) {
    runCatching {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val file = File(dir, fileName)
        file.writeText(content)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, fileName)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(
            Intent.createChooser(send, fileName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }.onFailure {
        Toast.makeText(context, it.message ?: "export failed", Toast.LENGTH_LONG).show()
    }
}
