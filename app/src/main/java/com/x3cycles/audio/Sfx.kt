package com.x3cycles.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/**
 * Synthesized SFX bank for X3 Cycles (no audio binaries ship). The DRONE loops
 * as the light-cycle engine hum while a race is on.
 */
class Sfx(private val context: Context) {

    companion object {
        const val TURN = 0
        const val BEEP = 1        // countdown 5..1
        const val GO = 2
        const val DEREZ = 3       // the player/rival shattering
        const val KILL = 4        // rival down confirm
        const val LEVELUP = 5
        const val RECOG_FIRE = 6
        const val START = 7
        const val GAMEOVER = 8
        const val SPAWN = 9
        const val HISCORE = 10
        const val WARN = 11
        const val BLIP = 12
        const val POWER = 13      // power-up collected
        const val ZAP = 14
        const val JUMP = 15       // auto-hop over an enemy beam
        const val EXTRA = 16      // bonus life ("1-UP")
        const val BEAMOUT = 17    // a downed rival's light wall powering off
        const val DRONE = 18
        private const val COUNT = 19
        private const val RATE = 22050
    }

    private val pool = SoundPool.Builder()
        .setMaxStreams(12)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        ).build()

    private val ids = IntArray(COUNT)
    @Volatile private var loaded = false
    @Volatile var volume = 0.9f
    private var droneStream = 0
    private val rng = Random(4)

    fun loadAsync() {
        Thread {
            runCatching {
                val dir = File(context.cacheDir, "sfx").apply { mkdirs() }
                ids[TURN] = load(dir, "turn", synthTurn())
                ids[BEEP] = load(dir, "beep", synthBeep())
                ids[GO] = load(dir, "go", arpeggio(intArrayOf(523, 784, 1046), 80, 0.7f))
                ids[DEREZ] = load(dir, "derez", synthDerez())
                ids[KILL] = load(dir, "kill", synthKill())
                ids[LEVELUP] = load(dir, "lvl", arpeggio(intArrayOf(392, 523, 659, 784, 1046), 90, 0.7f))
                ids[RECOG_FIRE] = load(dir, "rfire", synthRecogFire())
                ids[START] = load(dir, "start", synthStart())
                ids[GAMEOVER] = load(dir, "over", synthGameover())
                ids[SPAWN] = load(dir, "spawn", synthSpawn())
                ids[HISCORE] = load(dir, "hi", arpeggio(intArrayOf(523, 659, 784, 1046, 1318, 1568), 85, 0.7f))
                ids[WARN] = load(dir, "warn", synthWarn())
                ids[BLIP] = load(dir, "blip", synthBlip())
                ids[POWER] = load(dir, "power", synthPower())
                ids[ZAP] = load(dir, "zap", synthZap())
                ids[JUMP] = load(dir, "jump", synthJump())
                ids[EXTRA] = load(dir, "extra", arpeggio(intArrayOf(784, 1046, 1318, 1568, 2093), 68, 0.7f))
                ids[BEAMOUT] = load(dir, "beamout", synthBeamOut())
                ids[DRONE] = load(dir, "drone", synthDrone())
                loaded = true
            }
        }.start()
    }

    fun play(id: Int, pitch: Float = 1f, vol: Float = 1f) {
        if (!loaded || id < 0 || id >= COUNT) return
        val s = ids[id]; if (s == 0) return
        val v = (volume * vol).coerceIn(0f, 1f); if (v <= 0f) return
        pool.play(s, v, v, 1, 0, pitch.coerceIn(0.5f, 2f))
    }

    fun startDrone() {
        if (!loaded || droneStream != 0) return
        val v = (volume * 0.45f).coerceIn(0f, 1f)
        droneStream = pool.play(ids[DRONE], v, v, 0, -1, 1f)
    }

    fun stopDrone() { if (droneStream != 0) { pool.stop(droneStream); droneStream = 0 } }
    fun release() { runCatching { pool.release() } }

    // ------------------------------------------------------------ synthesis

    private fun buf(ms: Int, gen: (Float) -> Float): ShortArray {
        val n = RATE * ms / 1000
        return ShortArray(n) { i -> (gen(i.toFloat() / RATE).coerceIn(-1f, 1f) * 30000f).toInt().toShort() }
    }
    private fun sine(f: Float, t: Float) = sin(2.0 * PI * f * t).toFloat()
    private fun saw(f: Float, t: Float): Float { val p = (f * t) % 1f; return 2f * p - 1f }
    private fun sq(f: Float, t: Float) = if ((f * t) % 1f < 0.5f) 1f else -1f
    private fun noise() = rng.nextFloat() * 2f - 1f

    private fun synthTurn() = buf(70) { t -> sq(700f + 900f * t, t) * exp(-t * 22f) * 0.4f }
    private fun synthBeep() = buf(120) { t -> sine(660f, t) * exp(-t * 9f) * 0.5f }
    private fun synthKill() = buf(200) { t -> (saw(900f - 500f * t, t) + 0.4f * noise()) * exp(-t * 12f) * 0.5f }
    private fun synthDerez() = buf(600) { t ->
        val f = 300f - t * 200f
        val crush = if ((t * 40f).toInt() % 2 == 0) 1f else 0.4f
        ((noise() * 0.6f + sq(f, t) * 0.5f) * crush) * exp(-t * 4.5f)
    }
    private fun synthRecogFire() = buf(300) { t -> (saw(220f - 120f * t, t) * 0.5f + 0.4f * noise() * exp(-t * 30f)) * exp(-t * 5f) }
    private fun synthStart() = buf(400) { t -> sine(200f + 600f * t, t) * exp(-t * 4f) * 0.5f }
    private fun synthGameover() = buf(800) { t ->
        val f = if (t < 0.4f) 330f - t * 200f else 250f - (t - 0.4f) * 120f
        (saw(f, t) * 0.4f + sine(f, t) * 0.4f) * exp(-t * 2.2f)
    }
    private fun synthSpawn() = buf(260) { t -> sine(1400f - 900f * t, t) * exp(-t * 7f) * 0.5f }
    private fun synthWarn() = buf(150) { t -> sq(500f, t) * exp(-t * 8f) * 0.4f }
    private fun synthBlip() = buf(45) { t -> sine(1000f, t) * exp(-t * 45f) * 0.5f }
    private fun synthPower() = buf(320) { t -> sine(280f + 900f * t, t) * exp(-t * 5f) * 0.5f }
    private fun synthZap() = buf(160) { t -> (saw(1500f + 300f * sine(80f, t), t) + 0.4f * noise()) * exp(-t * 12f) * 0.5f }
    /** A crumbling downward power-off sweep for a rival's light wall derezzing. */
    private fun synthBeamOut() = buf(520) { t ->
        val f = 620f - 520f * (t / 0.5f).coerceAtMost(1f)
        val crush = if ((t * 60f).toInt() % 2 == 0) 1f else 0.5f
        ((sq(f, t) * 0.4f + noise() * 0.4f) * crush) * exp(-t * 3.4f)
    }
    /** A springy upward whoosh for the auto-hop over an enemy beam. */
    private fun synthJump() = buf(300) { t ->
        val sweep = 260f + 1500f * (t / 0.3f).coerceAtMost(1f)
        (sine(sweep, t) * 0.5f + sq(sweep * 0.5f, t) * 0.2f) * exp(-t * 5.5f)
    }

    /** Loopable engine hum — detuned saws with a slow throb. */
    private fun synthDrone(): ShortArray = buf(2000) { t ->
        val throb = 0.7f + 0.3f * sin(2f * PI.toFloat() * 4f * t)
        (saw(70f, t) * 0.3f + saw(70.6f, t) * 0.3f + sine(140f, t) * 0.2f) * throb * 0.5f
    }

    private fun arpeggio(freqs: IntArray, noteMs: Int, amp: Float): ShortArray {
        val total = noteMs * freqs.size + 220
        return buf(total) { t ->
            var v = 0f
            for ((i, f) in freqs.withIndex()) {
                val start = i * noteMs / 1000f
                if (t >= start) { val lt = t - start; v += (sine(f.toFloat(), lt) + 0.3f * sine(f * 2f, lt)) * exp(-lt * 5.5f) * amp * 0.4f }
            }
            v
        }
    }

    // ------------------------------------------------------------- wav

    private fun DataOutputStream.wInt(v: Int) { write(v and 0xFF); write((v shr 8) and 0xFF); write((v shr 16) and 0xFF); write((v shr 24) and 0xFF) }
    private fun DataOutputStream.wShort(v: Int) { write(v and 0xFF); write((v shr 8) and 0xFF) }

    private fun load(dir: File, name: String, pcm: ShortArray): Int {
        val f = File(dir, "$name.wav")
        val dataLen = pcm.size * 2
        DataOutputStream(BufferedOutputStream(FileOutputStream(f))).use { o ->
            o.writeBytes("RIFF"); o.wInt(36 + dataLen); o.writeBytes("WAVE")
            o.writeBytes("fmt "); o.wInt(16); o.wShort(1); o.wShort(1)
            o.wInt(RATE); o.wInt(RATE * 2); o.wShort(2); o.wShort(16)
            o.writeBytes("data"); o.wInt(dataLen)
            for (s in pcm) o.wShort(s.toInt())
        }
        return pool.load(f.absolutePath, 1)
    }
}
