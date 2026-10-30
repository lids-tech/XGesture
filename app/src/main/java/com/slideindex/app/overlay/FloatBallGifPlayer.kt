package com.slideindex.app.overlay

import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * FV-style GIF playback: Handler tick + lightweight View invalidate.
 */
internal class FloatBallGifPlayer(
    looper: Looper = Looper.getMainLooper()
) {
    private val handler = Handler(looper)
    private var gifView: FloatBallGifView? = null
    private var sequence: FloatBallGifFrameDecoder.Sequence? = null
    private var frameIndex = 0
    private var paused = false

    /** ImageDecoder 解出的动图；非空时由它自己播放，不需要 tick。 */
    private var animatedDrawable: Drawable? = null
    private var streamingStartUptimeMs = 0L

    private val tickRunnable = object : Runnable {
        override fun run() {
            if (paused) return
            val view = gifView ?: return
            val seq = sequence ?: return
            if (!view.canAnimateFrame()) {
                // 球不可见 / 熄屏：没必要逐帧重绘，1 秒探一次即可。
                view.setAnimating(false)
                handler.postDelayed(this, HIDDEN_RECHECK_MS)
                return
            }
            view.setAnimating(true)
            when (seq) {
                is FloatBallGifFrameDecoder.Sequence.Cached -> {
                    if (seq.frames.isEmpty()) return
                    val frame = seq.frames[frameIndex]
                    view.showCachedFrame(frame.bitmap)
                    val delayMs = frame.delayMs
                    frameIndex = (frameIndex + 1) % seq.frames.size
                    handler.postDelayed(this, delayMs.toLong())
                }
                is FloatBallGifFrameDecoder.Sequence.Streaming -> {
                    val elapsed = ((SystemClock.uptimeMillis() - streamingStartUptimeMs) % seq.durationMs)
                        .toInt()
                    view.showStreamingFrame(
                        movie = seq.movie,
                        elapsedMs = elapsed,
                        outW = seq.width,
                        outH = seq.height
                    )
                    handler.postDelayed(this, STREAMING_TICK_MS.toLong())
                }
            }
        }
    }

    fun attach(view: FloatBallGifView) {
        gifView = view
    }

    /**
     * 用系统动图（ImageDecoder → AnimatedImageDrawable）播放：它自己按帧推进，
     * 不需要我们的 tick 循环，也不再经过 Movie 那套废弃 API。
     */
    fun setAnimatedDrawable(drawable: Drawable?) {
        stop()
        animatedDrawable = drawable
        sequence = null
        gifView?.setAnimatedDrawable(drawable)
    }

    fun setSequence(seq: FloatBallGifFrameDecoder.Sequence?) {
        stop()
        animatedDrawable = null
        // 位图归 FloatBallGifSequenceCache 所有，可能被别处共享，这里只能松手不能 recycle。
        sequence = seq
        frameIndex = 0
        streamingStartUptimeMs = SystemClock.uptimeMillis()
        seq?.let { showFirstFrame(it) }
    }

    fun setPaused(pause: Boolean) {
        if (paused == pause) return
        paused = pause
        gifView?.setAnimating(!pause && sequence != null)
        // 动图自己播：必须显式冻结/恢复，「闲置时停止动图」才对它生效。
        gifView?.setAnimatedFrozen(pause)
        if (pause) {
            handler.removeCallbacks(tickRunnable)
        } else {
            streamingStartUptimeMs = SystemClock.uptimeMillis()
            start()
        }
    }

    fun start() {
        if (paused || sequence == null) return
        gifView?.setAnimating(true)
        handler.removeCallbacks(tickRunnable)
        handler.post(tickRunnable)
    }

    /**
     * 从“完全透明 / 不可见”恢复时立刻补一拍。
     *
     * 隐藏期间 tick 会退化成每秒一次的探测；触摸显形后不等那 1 秒，直接唤醒。
     */
    fun wake() {
        // 显形时把动图的运行状态重新对齐一次（冻结中则保持不动）。
        gifView?.setAnimatedFrozen(paused)
        if (paused || sequence == null) return
        handler.removeCallbacks(tickRunnable)
        handler.post(tickRunnable)
    }

    fun stop() {
        handler.removeCallbacks(tickRunnable)
        gifView?.setAnimating(false)
    }

    fun release() {
        stop()
        animatedDrawable = null
        sequence = null
        gifView?.clearFrame()
        gifView = null
    }

    private fun showFirstFrame(seq: FloatBallGifFrameDecoder.Sequence) {
        val view = gifView ?: return
        when (seq) {
            is FloatBallGifFrameDecoder.Sequence.Cached -> {
                val first = seq.frames.firstOrNull()?.bitmap ?: return
                view.showCachedFrame(first)
            }
            is FloatBallGifFrameDecoder.Sequence.Streaming -> {
                view.showStreamingFrame(
                    movie = seq.movie,
                    elapsedMs = 0,
                    outW = seq.width,
                    outH = seq.height
                )
            }
        }
    }

    companion object {
        internal const val STREAMING_TICK_MS = 66

        /** 球不可见时的探测周期；恢复可见后由下一次探测自动继续播放。 */
        private const val HIDDEN_RECHECK_MS = 1_000L
    }
}
