package com.realingo.app.core

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

enum class ThemeMode { SYSTEM, LIGHT, DARK }

data class SubtitleStyle(
    val show: Boolean = false,
    val mode: String = "both",        // both | target | source
    val opacity: Float = 0.5f,        // background alpha 0..1
    val srcSize: Int = 19,
    val tgtSize: Int = 30,
    val color: String = "#FFFFFF",
    val srcColor: String = "#A8C0DD",
    val outline: Boolean = true,
    val align: String = "center",     // left | center | right
    val grow: Boolean = true,         // false: one line per row, tail-aligned
    val locked: Boolean = true,       // click-through
    val x: Int = Int.MIN_VALUE,
    val y: Int = Int.MIN_VALUE,
)

data class Settings(
    val protocol: Protocol = Protocol.QWEN35,
    val apiKey: String = "",
    val openaiKey: String = "",
    val geminiKey: String = "",
    /** Empty => the protocol's own endpoint. */
    val url: String = "",
    val workspaceId: String = "",
    val region: Region = Region.BEIJING,
    val sourceLang: String = "auto",
    val targetLang: String = "en",
    val model: String = MODEL_QWEN3_5,
    val savedModels: Map<String, String> = emptyMap(),
    val history: Boolean = false,
    /** Source term -> preferred translation, sent as `translation.corpus.phrases`. */
    val hotwords: Map<String, String> = emptyMap(),
    val speak: Boolean = false,
    val voice: String = DEFAULT_VOICE,
    val theme: String = "system",
    val sub: SubtitleStyle = SubtitleStyle(),
    val locale: String = "zh",
) {
    fun key(): String = when (protocol.vendor) {
        Vendor.QWEN -> apiKey
        Vendor.OPENAI -> openaiKey
        Vendor.GEMINI -> geminiKey
    }

    fun withKey(k: String): Settings = when (protocol.vendor) {
        Vendor.QWEN -> copy(apiKey = k)
        Vendor.OPENAI -> copy(openaiKey = k)
        Vendor.GEMINI -> copy(geminiKey = k)
    }

    fun model(): String {
        val m = model.trim()
        if (m.isNotEmpty()) return m
        return when (protocol) {
            Protocol.QWEN35 -> MODEL_QWEN3_5
            Protocol.QWEN38 -> MODEL_QWEN3_8
            Protocol.OPENAI -> MODEL_OPENAI
            Protocol.GEMINI -> MODEL_GEMINI
        }
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("protocol", protocol.id)
        put("apiKey", apiKey)
        put("openaiKey", openaiKey)
        put("geminiKey", geminiKey)
        put("url", url)
        put("workspaceId", workspaceId)
        put("region", region.name)
        put("sourceLang", sourceLang)
        put("targetLang", targetLang)
        put("model", model)
        put("history", history)
        put("speak", speak)
        put("voice", voice)
        put("theme", theme)
        put("locale", locale)
        put("savedModels", JSONObject().apply { savedModels.forEach { (k, v) -> put(k, v) } })
        put("hotwords", JSONObject().apply { hotwords.forEach { (k, v) -> put(k, v) } })
        put("sub", JSONObject().apply {
            put("show", sub.show)
            put("mode", sub.mode)
            put("opacity", sub.opacity.toDouble())
            put("srcSize", sub.srcSize)
            put("tgtSize", sub.tgtSize)
            put("color", sub.color)
            put("srcColor", sub.srcColor)
            put("outline", sub.outline)
            put("align", sub.align)
            put("grow", sub.grow)
            put("locked", sub.locked)
            put("x", sub.x)
            put("y", sub.y)
        })
    }

    companion object {
        fun fromJson(o: JSONObject?): Settings {
            if (o == null) return Settings()
            fun str(k: String, d: String) = if (o.has(k) && !o.isNull(k)) o.optString(k, d) else d
            fun bool(k: String, d: Boolean) = o.optBoolean(k, d)
            val subJson = o.optJSONObject("sub")
            val sub = if (subJson == null) SubtitleStyle() else SubtitleStyle(
                show = subJson.optBoolean("show", false),
                mode = subJson.optString("mode", "both"),
                opacity = subJson.optDouble("opacity", 0.5).toFloat(),
                srcSize = subJson.optInt("srcSize", 19),
                tgtSize = subJson.optInt("tgtSize", 30),
                color = subJson.optString("color", "#FFFFFF"),
                srcColor = subJson.optString("srcColor", "#A8C0DD"),
                outline = subJson.optBoolean("outline", true),
                align = subJson.optString("align", "center"),
                grow = subJson.optBoolean("grow", true),
                locked = subJson.optBoolean("locked", true),
                x = subJson.optInt("x", Int.MIN_VALUE),
                y = subJson.optInt("y", Int.MIN_VALUE),
            )
            fun map(k: String): Map<String, String> {
                val obj = o.optJSONObject(k) ?: return emptyMap()
                val out = LinkedHashMap<String, String>()
                obj.keys().forEach { key -> out[key] = obj.optString(key) }
                return out
            }
            return Settings(
                protocol = Protocol.of(o.optString("protocol")),
                apiKey = str("apiKey", ""),
                openaiKey = str("openaiKey", ""),
                geminiKey = str("geminiKey", ""),
                url = str("url", ""),
                workspaceId = str("workspaceId", ""),
                region = Region.of(o.optString("region")),
                sourceLang = str("sourceLang", "auto"),
                targetLang = str("targetLang", "en"),
                model = str("model", ""),
                savedModels = map("savedModels"),
                history = bool("history", false),
                hotwords = map("hotwords"),
                speak = bool("speak", false),
                voice = str("voice", DEFAULT_VOICE),
                theme = str("theme", "system"),
                sub = sub,
                locale = str("locale", "zh"),
            )
        }
    }
}

object SettingsStore {
    private const val PREFS = "realingo.settings"
    private const val KEY = "settings"
    private var prefs: SharedPreferences? = null

    var value by mutableStateOf(Settings())
        private set

    /** Same subtitle style, for non-Compose consumers (the floating window). */
    private val subState = MutableStateFlow(Settings().sub)
    val subscription: StateFlow<SubtitleStyle> = subState

    fun init(context: Context) {
        if (prefs != null) return
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs?.getString(KEY, null)
        value = runCatching { Settings.fromJson(raw?.let { JSONObject(it) }) }.getOrDefault(Settings())
        subState.value = value.sub
    }

    fun update(block: (Settings) -> Settings) {
        value = block(value)
        subState.value = value.sub
        prefs?.edit()?.putString(KEY, value.toJson().toString())?.apply()
    }

    fun set(next: Settings) = update { next }

    fun setSub(block: (SubtitleStyle) -> SubtitleStyle) = update { it.copy(sub = block(it.sub)) }
}

/** UI language; Compose reads it as state so a toggle recomposes everything. */
object I18n {
    private val zhState = mutableStateOf(true)
    var zh: Boolean
        get() = zhState.value
        set(v) {
            zhState.value = v
        }
}

fun t(zh: String, en: String): String = if (I18n.zh) zh else en
