package com.realingo.app.service

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.realingo.app.core.Engine
import com.realingo.app.core.SettingsStore
import com.realingo.app.core.SubtitleStyle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * The floating subtitle bar: Android's equivalent of ReaLingo's separate always-on-top
 * subtitle window. Type_application_overlay keeps it above whatever is playing, and the
 * "locked" switch makes it click-through so it never eats a tap meant for the video.
 */
class OverlayController(private val context: Context) {
    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var jobs = mutableListOf<Job>()

    private var root: LinearLayout? = null
    private var srcView: TextView? = null
    private var tgtView: TextView? = null
    private var params: WindowManager.LayoutParams? = null
    private var style: SubtitleStyle = SettingsStore.value.sub

    fun isShowing() = root != null

    @SuppressLint("ClickableViewAccessibility")
    fun show() {
        if (root != null) return
        style = SettingsStore.value.sub

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            val metrics = context.resources.displayMetrics
            x = if (style.x == Int.MIN_VALUE) dp(12) else style.x.coerceIn(0, metrics.widthPixels)
            y = if (style.y == Int.MIN_VALUE) (metrics.heightPixels * 0.68f).toInt() else style.y
                .coerceIn(0, metrics.heightPixels)
        }

        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(13))
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(Color.BLACK)
                alpha = (style.opacity.coerceIn(0f, 1f) * 255).toInt().coerceIn(0, 255)
            }
        }

        val src = TextView(context).apply {
            setTextColor(Color.parseColor(style.srcColor))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, style.srcSize.toFloat())
            setLineSpacing(0f, 1.1f)
            visibility = View.GONE
        }
        val tgt = TextView(context).apply {
            setTextColor(Color.parseColor(style.color))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, style.tgtSize.toFloat())
            setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD))
            setLineSpacing(0f, 1.12f)
        }
        box.addView(src)
        box.addView(tgt)

        if (style.outline) {
            for (v in listOf(src, tgt)) {
                v.setShadowLayer(dp(4).toFloat(), 0f, 0f, Color.BLACK)
                v.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
            }
        }

        // Dragging only works while unlocked; a locked bar is click-through by definition.
        box.setOnTouchListener(object : View.OnTouchListener {
            private var startX = 0
            private var startY = 0
            private var touchX = 0f
            private var touchY = 0f

            override fun onTouch(v: View, e: MotionEvent): Boolean {
                if (SettingsStore.value.sub.locked) return false
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = lp.x
                        startY = lp.y
                        touchX = e.rawX
                        touchY = e.rawY
                        return true
                    }

                    MotionEvent.ACTION_MOVE -> {
                        lp.x = startX + (e.rawX - touchX).toInt()
                        lp.y = startY + (e.rawY - touchY).toInt()
                        runCatching { wm.updateViewLayout(box, lp) }
                        return true
                    }

                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        SettingsStore.setSub { it.copy(x = lp.x, y = lp.y) }
                        return true
                    }
                }
                return false
            }
        })

        val added = runCatching { wm.addView(box, lp) }.isSuccess
        if (!added) return
        root = box
        srcView = src
        tgtView = tgt
        params = lp
        applyStyle()

        jobs += scope.launch {
            Engine.live.collectLatest { render(it) }
        }
        jobs += scope.launch {
            Engine.status.collectLatest {
                // Show a hint while a session is open but nothing has been said yet.
                if (Engine.live.value.target.isEmpty() && Engine.live.value.source.isEmpty() &&
                    Engine.isRunning
                ) {
                    renderPlaceholder()
                }
            }
        }
        jobs += scope.launch {
            SettingsStore.subscription.collect {
                style = it
                applyStyle()
                render(Engine.live.value)
            }
        }
    }

    fun hide() {
        jobs.forEach { it.cancel() }
        jobs.clear()
        root?.let { runCatching { wm.removeView(it) } }
        root = null
        srcView = null
        tgtView = null
        params = null
    }

    private fun applyStyle() {
        val box = root ?: return
        box.setPadding(dp(18), dp(12), dp(18), dp(13))
        (box.background as? GradientDrawable)?.let {
            it.cornerRadius = dp(18).toFloat()
            it.setColor(Color.BLACK)
            it.alpha = (style.opacity.coerceIn(0f, 1f) * 255).toInt()
        }
        val g = when (style.align) {
            "left" -> Gravity.START
            "right" -> Gravity.END
            else -> Gravity.CENTER_HORIZONTAL
        }
        box.gravity = g
        srcView?.let {
            it.setTextColor(runCatching { Color.parseColor(style.srcColor) }.getOrDefault(Color.WHITE))
            it.setTextSize(TypedValue.COMPLEX_UNIT_SP, style.srcSize.toFloat())
            it.gravity = g
            it.visibility = if (style.mode == "target") View.GONE else View.VISIBLE
            it.setSingleLine(!style.grow)
            it.ellipsize = if (style.grow) null else TextUtils.TruncateAt.START
            it.setShadowLayer(if (style.outline) dp(4).toFloat() else 0f, 0f, 0f, Color.BLACK)
        }
        tgtView?.let {
            it.setTextColor(runCatching { Color.parseColor(style.color) }.getOrDefault(Color.WHITE))
            it.setTextSize(TypedValue.COMPLEX_UNIT_SP, style.tgtSize.toFloat())
            it.gravity = g
            it.setSingleLine(!style.grow)
            it.ellipsize = if (style.grow) null else TextUtils.TruncateAt.START
            it.setShadowLayer(if (style.outline) dp(4).toFloat() else 0f, 0f, 0f, Color.BLACK)
        }
        // Click-through, so the bar never blocks the player underneath.
        params?.let {
            it.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                (if (style.locked) WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE else 0)
            root?.let { v -> runCatching { wm.updateViewLayout(v, it) } }
        }
    }

    private fun render(live: Engine.Live) {
        val src = srcView ?: return
        val tgt = tgtView ?: return
        if (live.source.isEmpty() && live.target.isEmpty() && live.sourceStash.isEmpty() && live.targetStash.isEmpty()) {
            renderPlaceholder()
            return
        }
        src.text = styled(live.source, live.sourceStash)
        tgt.text = styled(live.target, live.targetStash)
        val hasSource = live.source.isNotEmpty() || live.sourceStash.isNotEmpty()
        src.visibility = if (style.mode == "target" || !hasSource) View.GONE else View.VISIBLE
        tgt.visibility = if (style.mode == "source") View.GONE else View.VISIBLE
    }

    private fun renderPlaceholder() {
        val tgt = tgtView ?: return
        val src = srcView ?: return
        src.visibility = View.GONE
        tgt.visibility = View.VISIBLE
        tgt.text = if (Engine.status.value == Engine.Status.CONNECTED) {
            com.realingo.app.core.t("正在聆听…", "Listening…")
        } else {
            com.realingo.app.core.t("ReaLingo 字幕", "ReaLingo subtitles")
        }
    }

    /** The unconfirmed tail is greyed: it is worth showing, but it can still change. */
    private fun styled(confirmed: String, stash: String): CharSequence {
        if (stash.isEmpty()) return confirmed
        val sb = SpannableStringBuilder(confirmed)
        if (confirmed.isNotEmpty() && !confirmed.endsWith(" ")) sb.append(' ')
        val start = sb.length
        sb.append(stash)
        sb.setSpan(
            ForegroundColorSpan(0x99FFFFFF.toInt()),
            start,
            sb.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        return sb
    }

    private fun dp(v: Int): Int =
        (v * context.resources.displayMetrics.density).toInt()
}
