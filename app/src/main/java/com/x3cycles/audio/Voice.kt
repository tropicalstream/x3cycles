package com.x3cycles.audio

import android.content.Context
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.File

/**
 * The MONOPOLY CONTROL PROTOCOL speaks — and the IO Tower sings.
 *
 * Same pattern as x3breakout's commentary system: clips are pre-generated
 * offline by tools/generate_commentary.py (Fish TTS, the same MCP voice
 * model), the APK stays network-free, and any event whose clip is missing
 * is silently skipped — but its caption still shows, so the dialog reads
 * even before you have generated the audio.
 */
class Voice(private val context: Context) {

    private class Line(val id: String, val quote: String)

    private val byEvent = HashMap<String, MutableList<Line>>()
    private val main = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var music: MediaPlayer? = null
    @Volatile var volume = 0.95f

    // The MONOPOLY CONTROL PROTOCOL always finishes his sentence. Nothing
    // interrupts a playing clip — not even a story beat (an override sounds
    // exactly like him stuttering over himself, which is beneath him).
    // Instead: while he is speaking, story beats (priority >= 1) WAIT in a
    // one-deep pending slot and play the moment the current line ends;
    // incidentals (priority 0) are simply lost to the moment.
    // play() arrives from the game/GL thread, completions on main — all
    // state transitions are synchronized on `gate`.
    private val gate = Any()
    private var activePriority = -1
    private var pending: Line? = null
    private var pendingPriority = -1

    fun load() {
        runCatching {
            val raw = context.assets.open("commentary.json")
                .bufferedReader().use { it.readText() }
            val arr = JSONObject(raw).getJSONArray("lines")
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                byEvent.getOrPut(o.getString("event")) { mutableListOf() }
                    .add(Line(o.getString("id"), o.optString("quote", "")))
            }
        }
    }

    /**
     * Speak a line for the first event that has any. If he is mid-sentence:
     * a story beat (priority >= 1) queues and plays right after (newest
     * outranking beat wins the single pending slot); an incidental
     * (priority 0) is dropped. Returns the quote for the vector-font
     * caption whenever the line will be heard — now or queued — and null
     * when it was dropped, so the caption always matches the audio.
     */
    fun play(priority: Int, vararg events: String): String? {
        for (ev in events) {
            val list = byEvent[ev] ?: continue
            if (list.isEmpty()) continue
            val line = list.random()
            synchronized(gate) {
                if (activePriority >= 0) {
                    if (priority < 1) return null                 // lost to the moment
                    if (priority < pendingPriority) return null   // a bigger beat already waits
                    pending = line
                    pendingPriority = priority
                    return line.quote
                }
                activePriority = priority
            }
            main.post { speak(line.id) }
            return line.quote
        }
        return null
    }

    /** A clip ended (or failed) — start whatever beat was waiting. */
    private fun finishAndDrain() {
        val next: Line?
        synchronized(gate) {
            next = pending
            pending = null
            activePriority = if (next != null) pendingPriority else -1
            pendingPriority = -1
        }
        if (next != null) speak(next.id) else duckMusic(false)
    }

    private fun speak(id: String) {
        val f = cacheAsset("voice/$id.mp3")
        if (f == null) { finishAndDrain(); return }   // no clip yet — caption already shown
        try {
            player?.release()
            player = null
            duckMusic(true)
            val mp = MediaPlayer()
            mp.setDataSource(f.absolutePath)
            mp.setOnCompletionListener { done ->
                done.release()
                if (player === done) { player = null; finishAndDrain() }
            }
            mp.setOnErrorListener { p, _, _ ->
                p.release()
                if (player === p) { player = null; finishAndDrain() }
                true
            }
            mp.setVolume(volume, volume)
            mp.prepare()
            mp.start()
            player = mp
        } catch (t: Throwable) {
            player = null
            finishAndDrain()
        }
    }

    /** The IO Tower theme drops under the MCP's voice so it never gets buried. */
    private fun duckMusic(down: Boolean) {
        runCatching {
            val v = if (down) 0.28f else 0.75f
            music?.setVolume(v, v)
        }
    }

    /** The IO Tower cover, looping under the title grid. */
    fun titleMusic(on: Boolean) {
        main.post {
            if (on) {
                if (music != null) return@post
                runCatching {
                    val f = cacheAsset("music/io_tower.mp3") ?: return@post
                    val mp = MediaPlayer()
                    mp.setDataSource(f.absolutePath)
                    mp.isLooping = true
                    mp.setVolume(0.75f, 0.75f)
                    mp.prepare()
                    mp.start()
                    music = mp
                }
            } else {
                runCatching { music?.stop(); music?.release() }
                music = null
            }
        }
    }

    private fun cacheAsset(path: String): File? = runCatching {
        val out = File(context.cacheDir, path.replace('/', '_'))
        if (!out.exists() || out.length() == 0L) {
            context.assets.open(path).use { i -> out.outputStream().use { o -> i.copyTo(o) } }
        }
        out
    }.getOrNull()

    fun pauseAll() {
        synchronized(gate) {
            activePriority = -1
            pending = null
            pendingPriority = -1
        }
        main.post {
            runCatching { player?.release() }
            player = null
            runCatching { music?.stop(); music?.release() }
            music = null
        }
    }

    fun release() = pauseAll()
}
