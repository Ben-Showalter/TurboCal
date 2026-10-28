package com.turbocal.app.notify

import android.app.Notification
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.format.DateFormat
import android.util.Log
import androidx.core.content.ContextCompat
import com.turbocal.app.R
import com.turbocal.core.notify.coverLine
import com.turbocal.core.notify.shouldReshow
import com.turbocal.core.notify.tickerFrameCount
import com.turbocal.core.notify.tickerFrames
import java.util.Date

/**
 * Reminder cards on the Kyocera **outer display** (sub-LCD), ported from
 * MatChat's CoverScreenNotifier and following the guide (docs/platform.md
 * "Outer display and LED"):
 *  - reflection on `jp.kyocera.sublcd.SubLcdManager` + `SubLcdNotificationExtender`
 *    (message-card template, one line beside a 96 px nodpi icon), every call in
 *    runCatching, so off Kyocera this is a silent no-op;
 *  - every post has a duration; text wider than the cover scrolls by reposting
 *    the same id, whole passes only;
 *  - the time leads the line (owner: the card covers the outer clock);
 *  - shown again when the flip closes (SCREEN_OFF, the guide's fallback; Kyocera's
 *    ISubLcdCallback is not used, it needs a compile-only stub module), stopped
 *    when it opens (SCREEN_ON), until cancelled;
 *  - never calls SubLcdManager.notifyIconToAnnunciatorTray (it crashed SystemUI).
 * Main thread only.
 */
internal object CoverScreenNotifier {
    private const val TAG = "TurboCalCover"

    private const val SUBLCD_LAYOUT_RICH_CARD = 0x010900af
    private const val SUBLCD_CATEGORY_MESSAGE = 2
    private const val SUBLCD_PRIORITY = 0
    private const val CARD_DURATION_MS = 15_000
    private const val TICKER_WINDOW = 9
    private const val TICKER_SCROLL_STEP = 2
    private const val COVER_MAX_CHARS = 120
    private const val TICKER_STEP_MS = 400L
    private const val TICKER_PAUSE_STEPS = 3
    private const val TICKER_HOLD_MS = 1_500

    private val main = Handler(Looper.getMainLooper())
    private val active = LinkedHashMap<Int, String>()
    private val tickers = HashMap<Int, Runnable>()
    private var screenReceiver: BroadcastReceiver? = null
    private var lastShownAt = 0L
    private var lastShowingMs = CARD_DURATION_MS.toLong()

    fun notify(context: Context, id: Int, text: String) {
        val app = context.applicationContext
        main.post {
            active.remove(id)
            active[id] = text
            startWatching(app)
            show(app, id, text)
        }
    }

    fun cancel(context: Context, id: Int) {
        val app = context.applicationContext
        main.post {
            active.remove(id)
            stopTicker(id)
            cancelCard(app, id)
            lastShownAt = 0L
            if (active.isEmpty()) stopWatching(app)
        }
    }

    fun cancelAll(context: Context) {
        val app = context.applicationContext
        main.post {
            active.keys.toList().forEach { id ->
                stopTicker(id)
                cancelCard(app, id)
            }
            active.clear()
            lastShownAt = 0L
            stopWatching(app)
        }
    }

    private fun maybeReshow(context: Context) {
        val sinceShown = SystemClock.elapsedRealtime() - lastShownAt
        if (!shouldReshow(active.isNotEmpty(), sinceShown, lastShowingMs)) return
        val latest = active.entries.last()
        show(context, latest.key, latest.value)
    }

    private fun show(context: Context, id: Int, text: String) {
        tickers.keys.toList().forEach(::stopTicker)
        lastShownAt = SystemClock.elapsedRealtime()
        val time = DateFormat.getTimeFormat(context).format(Date())
        val shown = coverLine(time, text, COVER_MAX_CHARS)
        if (shown.length <= TICKER_WINDOW) {
            lastShowingMs = CARD_DURATION_MS.toLong()
            post(context, id, shown, CARD_DURATION_MS)
            return
        }
        val cycle = tickerFrames(shown, TICKER_WINDOW, TICKER_PAUSE_STEPS, TICKER_SCROLL_STEP)
        val frames = tickerFrameCount(cycle.size, TICKER_STEP_MS, CARD_DURATION_MS.toLong())
        lastShowingMs = frames * TICKER_STEP_MS
        val ticker = object : Runnable {
            private var frame = 0
            override fun run() {
                post(context, id, cycle[frame % cycle.size], TICKER_HOLD_MS)
                frame++
                if (frame < frames) main.postDelayed(this, TICKER_STEP_MS) else tickers.remove(id)
            }
        }
        tickers[id] = ticker
        ticker.run()
    }

    private fun stopTicker(id: Int) {
        tickers.remove(id)?.let(main::removeCallbacks)
    }

    private fun startWatching(context: Context) {
        if (screenReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                when (intent.action) {
                    Intent.ACTION_SCREEN_OFF -> maybeReshow(context) // flip closed
                    Intent.ACTION_SCREEN_ON -> { // flip opened: the main screen shows the notification
                        active.keys.forEach { id ->
                            stopTicker(id)
                            cancelCard(context, id)
                        }
                        lastShownAt = 0L
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        screenReceiver = receiver
    }

    private fun stopWatching(context: Context) {
        screenReceiver?.let { runCatching { context.unregisterReceiver(it) } }
        screenReceiver = null
    }

    private fun post(context: Context, id: Int, text: String, durationMs: Int) {
        runCatching {
            val managerClass = Class.forName("jp.kyocera.sublcd.SubLcdManager")
            val manager = managerClass.getMethod("getInstance", Context::class.java).invoke(null, context)
            managerClass.getMethod("notify", Int::class.javaPrimitiveType, Notification::class.java)
                .invoke(manager, id, buildCard(context, text, durationMs))
        }.onFailure { Log.d(TAG, "sub-LCD notify unavailable id=$id: ${it.javaClass.simpleName}") }
    }

    /** The card: a Notification carrying the sub-LCD extender. Never posted to a channel. */
    @Suppress("DEPRECATION")
    private fun buildCard(context: Context, text: String, durationMs: Int): Notification {
        val extenderClass = Class.forName("jp.kyocera.sublcd.SubLcdNotificationExtender")
        val extender = extenderClass.getConstructor(Context::class.java).newInstance(context)
        extenderClass.getMethod("setCategory", Int::class.javaPrimitiveType).invoke(extender, SUBLCD_CATEGORY_MESSAGE)
        extenderClass.getMethod("setLayout", Int::class.javaPrimitiveType).invoke(extender, SUBLCD_LAYOUT_RICH_CARD)
        extenderClass.getMethod("setPriority", Int::class.javaPrimitiveType).invoke(extender, SUBLCD_PRIORITY)
        extenderClass.getMethod("setIcon", Int::class.javaPrimitiveType).invoke(extender, R.drawable.ic_cover_turbocal)
        extenderClass.getMethod("setText", CharSequence::class.java).invoke(extender, text)
        extenderClass.getMethod("setDuration", Int::class.javaPrimitiveType).invoke(extender, durationMs)
        val builder = Notification.Builder(context).setSmallIcon(R.drawable.ic_stat_turbocal).setContentText(text)
        Notification.Builder::class.java.getMethod("extend", Notification.Extender::class.java).invoke(builder, extender)
        return builder.build()
    }

    private fun cancelCard(context: Context, id: Int) {
        runCatching {
            val managerClass = Class.forName("jp.kyocera.sublcd.SubLcdManager")
            val manager = managerClass.getMethod("getInstance", Context::class.java).invoke(null, context)
            managerClass.getMethod("cancel", Int::class.javaPrimitiveType).invoke(manager, id)
        }
    }
}
