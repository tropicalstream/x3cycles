package com.x3cycles.engine

import com.x3cycles.SettingsStore
import com.x3cycles.audio.Sfx
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random

enum class GameState { TITLE, COUNTDOWN, RACING, LEVEL_CLEAR, GAME_OVER }

interface GameHost {
    fun sfx(id: Int, pitch: Float = 1f, vol: Float = 1f)
    fun startDrone()
    fun stopDrone()
}

// Grid directions: 0=+x, 1=+z, 2=-x, 3=-z.
val DX = intArrayOf(1, 0, -1, 0)
val DZ = intArrayOf(0, 1, 0, -1)
fun leftOf(d: Int) = (d + 1) and 3
fun rightOf(d: Int) = (d + 3) and 3

class Cycle(var cx: Int, var cz: Int, var dir: Int, val hue: Float, val isPlayer: Boolean) {
    var progress = 0f
    var alive = true
    var pendingDir = -1
    val trail = ArrayList<IntArray>().apply { add(intArrayOf(cx, cz)) }
    fun headX() = cx + DX[dir] * progress
    fun headZ() = cz + DZ[dir] * progress
}

class Recognizer(var x: Float, var z: Float) { var fireTimer = 2f }
class Bolt(var x: Float, var z: Float, var vx: Float, var vz: Float) { var life = 4f }

class Particle {
    var x = 0f; var y = 0f; var z = 0f
    var vx = 0f; var vy = 0f; var vz = 0f
    var life = 0f; var maxLife = 1f; var hue = 0f
}

/**
 * X3 Cycles — a synthwave, isometric take on light cycles. Constant speed, two
 * controls (turn left / turn right). Clear a level by outlasting every rival
 * cycle; each level is a touch faster and adds a rival up to five, then wraps
 * back to one while hunter Recognizer tanks join the grid.
 */
class Game(private val store: SettingsStore, private val host: GameHost) {

    companion object {
        const val GRID = 40
        const val WALL_H = 0.7f
        const val BASE_SPEED = 6.5f
        const val SPEED_STEP = 0.7f
        const val COUNT_SECS = 5f
        const val RECOG_SPEED = 4.6f
        const val BOLT_SPEED = 9f
    }

    var state = GameState.TITLE; private set
    val cycles = ArrayList<Cycle>()
    val recognizers = ArrayList<Recognizer>()
    val bolts = ArrayList<Bolt>()
    val particles = ArrayList<Particle>()
    private val pool = ArrayDeque<Particle>()
    private val grid = IntArray(GRID * GRID)

    var level = 1; private set
    var score = 0; private set
    var highScore = 0; private set
    var bestLevel = 1; private set
    var speed = BASE_SPEED; private set
    var countdown = 0f; private set
    val countInt get() = (countdown - 0.0001f).toInt() + 1   // 5..1, 0 = GO
    var time = 0f; private set
    var shake = 0f; private set
    private var clearTimer = 0f
    private var lastBeep = -1
    private val rng = Random(System.nanoTime())

    var player: Cycle? = null; private set
    val opponentsAlive get() = cycles.count { !it.isPlayer && it.alive }
    var recognizerCount = 0; private set

    fun boot() {
        highScore = store.highScore
        bestLevel = store.bestLevel
        toTitle()
    }

    // ---------------------------------------------------------------- input

    fun turn(left: Boolean) {
        when (state) {
            GameState.TITLE, GameState.GAME_OVER -> startGame()
            GameState.COUNTDOWN, GameState.RACING -> {
                val p = player ?: return
                if (!p.alive) return
                val base = if (p.pendingDir >= 0) p.pendingDir else p.dir
                p.pendingDir = if (left) leftOf(base) else rightOf(base)
                host.sfx(Sfx.TURN, if (left) 1f else 1.18f, 0.7f)
            }
            else -> {}
        }
    }

    // --------------------------------------------------------------- update

    fun update(dt: Float) {
        time += dt
        shake = maxOf(0f, shake - dt * 3f)
        updateParticles(dt)
        when (state) {
            GameState.TITLE -> {}
            GameState.COUNTDOWN -> {
                countdown -= dt
                val c = countInt
                if (c != lastBeep) {
                    lastBeep = c
                    if (c > 0) host.sfx(Sfx.BEEP, 0.8f + (5 - c) * 0.12f)
                    else host.sfx(Sfx.GO)
                }
                if (countdown <= 0f) { state = GameState.RACING; host.startDrone() }
            }
            GameState.RACING -> updateRacing(dt)
            GameState.LEVEL_CLEAR -> { clearTimer -= dt; if (clearTimer <= 0f) startLevel(level + 1) }
            GameState.GAME_OVER -> {}
        }
    }

    private fun updateRacing(dt: Float) {
        for (c in cycles) if (c.alive) stepCycle(c, dt)
        updateRecognizers(dt)
        updateBolts(dt)

        val p = player
        if (p != null && !p.alive) { gameOver(); return }
        if (opponentsAlive == 0) {
            score += 500 * level
            state = GameState.LEVEL_CLEAR
            clearTimer = 2.2f
            host.stopDrone()
            host.sfx(Sfx.LEVELUP)
        }
    }

    private fun stepCycle(c: Cycle, dt: Float) {
        c.progress += speed * dt
        var guard = 0
        while (c.progress >= 1f && c.alive && guard++ < 4) {
            c.progress -= 1f
            if (!c.isPlayer) aiSteer(c) else applyPlayerTurn(c)
            val tx = c.cx + DX[c.dir]; val tz = c.cz + DZ[c.dir]
            if (blocked(tx, tz)) { derez(c); return }
            c.cx = tx; c.cz = tz
            grid[tz * GRID + tx] = 1
        }
    }

    private fun applyPlayerTurn(c: Cycle) {
        if (c.pendingDir >= 0 && c.pendingDir != c.dir && c.pendingDir != opposite(c.dir)) {
            c.dir = c.pendingDir
            c.trail.add(intArrayOf(c.cx, c.cz))
        }
        c.pendingDir = -1
    }

    private fun opposite(d: Int) = (d + 2) and 3

    private fun blocked(x: Int, z: Int): Boolean {
        if (x < 0 || x >= GRID || z < 0 || z >= GRID) return true
        return grid[z * GRID + x] != 0
    }

    /** Cheap survival AI: don't drive into a wall/trail; mildly hunt the player. */
    private fun aiSteer(c: Cycle) {
        val d = c.dir
        val straight = runLen(c, d)
        val l = runLen(c, leftOf(d))
        val r = runLen(c, rightOf(d))
        var nd = d
        if (straight <= 1) {
            nd = if (l >= r) leftOf(d) else rightOf(d)
        } else if (rng.nextFloat() < 0.05f + level * 0.006f) {
            // occasional maneuver, biased toward the player
            val p = player
            val want = if (p != null && rng.nextFloat() < 0.6f) towardPlayer(c, p) else if (rng.nextBoolean()) leftOf(d) else rightOf(d)
            if (runLen(c, want) > 2) nd = want
        }
        if (nd != d && nd != opposite(d)) { c.dir = nd; c.trail.add(intArrayOf(c.cx, c.cz)) }
    }

    private fun towardPlayer(c: Cycle, p: Cycle): Int {
        val ddx = p.cx - c.cx; val ddz = p.cz - c.cz
        return if (abs(ddx) > abs(ddz)) (if (ddx > 0) 0 else 2) else (if (ddz > 0) 1 else 3)
    }

    private fun runLen(c: Cycle, dir: Int): Int {
        var x = c.cx; var z = c.cz; var n = 0
        while (n < 10) {
            x += DX[dir]; z += DZ[dir]
            if (blocked(x, z)) break
            n++
        }
        return n
    }

    private fun derez(c: Cycle) {
        c.alive = false
        shake = maxOf(shake, 10f)
        explode(c.headX(), c.headZ(), c.hue)
        host.sfx(Sfx.DEREZ, if (c.isPlayer) 0.8f else 1.1f)
        if (!c.isPlayer) { score += 100 * level; host.sfx(Sfx.KILL, 1f, 0.9f) }
    }

    // --------------------------------------------------- recognizers & bolts

    private fun updateRecognizers(dt: Float) {
        val p = player ?: return
        if (!p.alive) return
        val hx = p.headX(); val hz = p.headZ()
        for (rec in recognizers) {
            val dx = hx - rec.x; val dz = hz - rec.z
            val dist = hypot(dx, dz).coerceAtLeast(0.001f)
            rec.x += dx / dist * RECOG_SPEED * dt
            rec.z += dz / dist * RECOG_SPEED * dt
            if (dist < 1.3f) { derez(p); return }
            rec.fireTimer -= dt
            if (rec.fireTimer <= 0f) {
                rec.fireTimer = 2.6f
                bolts.add(Bolt(rec.x, rec.z, dx / dist * BOLT_SPEED, dz / dist * BOLT_SPEED))
                host.sfx(Sfx.RECOG_FIRE)
            }
        }
    }

    private fun updateBolts(dt: Float) {
        val p = player
        var i = bolts.size - 1
        while (i >= 0) {
            val b = bolts[i]
            b.x += b.vx * dt; b.z += b.vz * dt; b.life -= dt
            if (p != null && p.alive && hypot(b.x - p.headX(), b.z - p.headZ()) < 0.7f) {
                derez(p); bolts.removeAt(i); i--; continue
            }
            if (b.life <= 0f || b.x < -2 || b.x > GRID + 2 || b.z < -2 || b.z > GRID + 2) bolts.removeAt(i)
            i--
        }
    }

    // -------------------------------------------------------- level flow

    fun startGame() {
        score = 0
        startLevel(1)
    }

    private fun startLevel(lv: Int) {
        level = lv
        speed = BASE_SPEED + (lv - 1) * SPEED_STEP
        val opponents = ((lv - 1) % 5) + 1
        recognizerCount = (lv - 1) / 5
        java.util.Arrays.fill(grid, 0)
        cycles.clear(); recognizers.clear(); bolts.clear()

        // Player starts near the viewer and rides FORWARD, away up the grid (-z).
        val pl = Cycle(GRID / 2, GRID - 5, 3, 0.5f, true) // cyan-ish
        occupy(pl)
        cycles.add(pl); player = pl

        // Rivals: spread across the far side heading down toward the player.
        for (i in 0 until opponents) {
            val x = (GRID * (i + 1) / (opponents + 1)).coerceIn(3, GRID - 4)
            val rc = Cycle(x, 4, 1, (0.02f + i * 0.16f) % 1f, false)
            occupy(rc); cycles.add(rc)
        }
        for (i in 0 until recognizerCount) {
            recognizers.add(Recognizer(4f + (GRID - 8f) * (i.toFloat() / maxOf(1, recognizerCount)), GRID / 2f))
        }

        countdown = COUNT_SECS
        lastBeep = -1
        state = GameState.COUNTDOWN
        host.sfx(Sfx.START)
        if (lv > bestLevel) { bestLevel = lv; store.bestLevel = lv }
        if (score > highScore) { highScore = score; store.highScore = score }
    }

    private fun occupy(c: Cycle) { grid[c.cz * GRID + c.cx] = 1 }

    private fun gameOver() {
        state = GameState.GAME_OVER
        host.stopDrone()
        host.sfx(Sfx.GAMEOVER)
        if (score > highScore) { highScore = score; store.highScore = score }
    }

    fun toTitle() {
        state = GameState.TITLE
        cycles.clear(); recognizers.clear(); bolts.clear()
        player = null
        host.stopDrone()
    }

    // ----------------------------------------------------------- particles

    private fun explode(x: Float, z: Float, hue: Float) {
        repeat(60) {
            val p = pool.removeFirstOrNull() ?: Particle()
            p.x = x; p.y = 0.3f; p.z = z
            val a = rng.nextFloat() * 6.2832f; val sp = 2f + rng.nextFloat() * 6f
            p.vx = cos(a) * sp; p.vz = sin(a) * sp; p.vy = 1f + rng.nextFloat() * 5f
            p.life = 0.9f + rng.nextFloat() * 0.6f; p.maxLife = p.life
            p.hue = (hue + rng.nextFloat() * 0.2f) % 1f
            particles.add(p)
        }
    }

    private fun updateParticles(dt: Float) {
        var i = particles.size - 1
        while (i >= 0) {
            val p = particles[i]
            p.life -= dt
            if (p.life <= 0f) { particles.removeAt(i); pool.addLast(p) }
            else { p.vy -= 9f * dt; p.x += p.vx * dt; p.y += p.vy * dt; p.z += p.vz * dt; p.vx *= 0.97f; p.vz *= 0.97f }
            i--
        }
    }
}
