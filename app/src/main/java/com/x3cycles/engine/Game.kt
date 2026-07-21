package com.x3cycles.engine

import com.x3cycles.SettingsStore
import com.x3cycles.audio.Sfx
import com.x3cycles.gl.StrokeFont
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random

enum class GameState { TITLE, COUNTDOWN, RACING, LIFE_LOST, LEVEL_CLEAR, GAME_OVER }

interface GameHost {
    fun sfx(id: Int, pitch: Float = 1f, vol: Float = 1f)
    fun startDrone()
    fun stopDrone()
    /** MCP speaks: first event with lines wins; returns the quote for the caption.
     *  `priority` guards against interrupting a line already in progress. */
    fun voice(priority: Int, vararg events: String): String?
    /** The IO Tower theme under the title grid. */
    fun titleMusic(on: Boolean)
}

// Grid directions: 0=+x, 1=+z, 2=-x, 3=-z.
val DX = intArrayOf(1, 0, -1, 0)
val DZ = intArrayOf(0, 1, 0, -1)
fun leftOf(d: Int) = (d + 1) and 3
fun rightOf(d: Int) = (d + 3) and 3

class Cycle(var cx: Int, var cz: Int, var dir: Int, val hue: Float, val isPlayer: Boolean) {
    var progress = 0f
    var alive = true
    var derezT = 0f              // >0 while this cycle's beam dissolves away
    // Queued relative turns (true = left, false = right), one applied per cell.
    // A FIFO buffer so a rapid double-flick becomes a staircase instead of
    // collapsing into a single net direction.
    val pendingTurns = ArrayDeque<Boolean>()
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
        // Grid occupancy owners, so the jump only fires over an enemy beam.
        const val P_TRAIL = 1
        const val R_TRAIL = 2
        // Lives: start with three, earn a bonus life every milestone up to a cap
        // (classic arcade practice — generous early, capped to keep the HUD sane).
        const val START_LIVES = 3
        const val MAX_LIVES = 5
        const val EXTRA_LIFE_STEP = 5000
        const val BEAM_DEREZ_DUR = 0.7f   // how long a downed rival's wall dissolves
        const val MAX_QUEUED_TURNS = 3    // rapid-input buffer depth for the player
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
    var lives = START_LIVES; private set
    private var nextLifeScore = EXTRA_LIFE_STEP
    var speed = BASE_SPEED; private set
    var countdown = 0f; private set
    val countInt get() = (countdown - 0.0001f).toInt() + 1   // 5..1, 0 = GO
    var time = 0f; private set
    var shake = 0f; private set
    private var clearTimer = 0f
    private var lifeLostTimer = 0f
    private var lastBeep = -1
    private val rng = Random(System.nanoTime())

    var player: Cycle? = null; private set
    val opponentsAlive get() = cycles.count { !it.isPlayer && it.alive }
    var recognizerCount = 0; private set

    // Jump power-up: one collectible per level; once picked up it auto-fires the
    // first time the player would ride into an enemy beam, hopping clear of it.
    var powerX = -1; private set
    var powerZ = -1; private set
    var powerActive = false; private set   // pickup sitting on the grid
    var jumpArmed = false; private set      // collected, waiting to auto-fire
    var jumpAnim = 0f; private set          // >0 briefly after a hop (for FX)

    // ---- the MCP's voice: caption of whatever it just said ----
    var caption = ""; private set
    var captionT = 0f; private set
    private var saidTitleLine = false

    // priority 1 = story beat (start / clear / life lost / game over / title),
    // priority 0 = incidental (kill taunt, extra life). A beat can override a
    // taunt in progress; nothing overrides a beat; nothing overrides its equal.
    private fun mcpSays(vararg events: String, priority: Int = 1) {
        val quote = host.voice(priority, *events) ?: return
        caption = quote.uppercase()
        captionT = 4.6f
    }

    // ---- the title attract: a demo cycle draws "X3 PRO" in light on the grid ----
    // Word strokes in grid space [x0,z0,x1,z1], in draw order; the cycle rides
    // them so its light wall spells the product name, then holds and redraws.
    val titleWordSegs = ArrayList<FloatArray>()
    private var titleWordLen = FloatArray(0)
    private var titleWordTotal = 0.001f
    var titleReveal = 0f; private set        // grid-length of the word drawn so far
    var titleWordAlpha = 1f; private set     // fades on the loop tail before a redraw
    var titleX = 8f; private set
    var titleZ = 30f; private set
    var titleHeadDX = 1f; private set        // drawing-head heading (the bike's nose)
    var titleHeadDZ = 0f; private set
    private var titleDrawT = 0f

    /** Lay "X3 PRO" onto the floor as grid-space wall strokes, reusing the HUD
     *  vector font. Built once; a clear strip in front of the tower. */
    private fun buildTitleWord() {
        if (titleWordSegs.isNotEmpty()) return
        val word = "X3 PRO"
        val s = 0.6f
        val inv = 0.70710677f                              // 1/sqrt(2)
        // Rotate the word onto the isometric screen axes so it reads upright and
        // horizontal (screen-right on the floor is (+x,-z), screen-up is (-x,-z)),
        // centred on a clear band in front of the central tower.
        val cX = 24f; val cZ = 28f                         // desired on-screen centre (world)
        val half = word.length * StrokeFont.ADVANCE * s / 2f
        val wx = cX - (half - 3f * s) * inv
        val wz = cZ + (half + 3f * s) * inv
        val sink = object : StrokeFont.LineSink {
            // draw() runs at baseline (0,0): x0 is the pen x, y0 the pen y (screen-down)
            override fun line(x0: Float, y0: Float, x1: Float, y1: Float) {
                titleWordSegs.add(floatArrayOf(
                    wx + (x0 + y0) * inv, wz - (x0 - y0) * inv,
                    wx + (x1 + y1) * inv, wz - (x1 - y1) * inv))
            }
        }
        StrokeFont.draw(word, 0f, 0f, s, sink)
        titleWordLen = FloatArray(titleWordSegs.size)
        var acc = 0f
        for (i in titleWordSegs.indices) {
            val q = titleWordSegs[i]
            acc += hypot((q[2] - q[0]).toDouble(), (q[3] - q[1]).toDouble()).toFloat()
            titleWordLen[i] = acc
        }
        titleWordTotal = acc.coerceAtLeast(0.001f)
    }

    private fun updateTitleRider(dt: Float) {
        buildTitleWord()
        val draw = 5f; val hold = 3.5f; val fade = 1.3f; val loop = draw + hold + fade
        titleDrawT += dt
        if (titleDrawT >= loop) titleDrawT -= loop
        val t = titleDrawT
        val frac = (t / draw).coerceAtMost(1f)
        titleWordAlpha = if (t <= draw + hold) 1f else (1f - (t - draw - hold) / fade).coerceIn(0f, 1f)
        val fe = frac * frac * (3f - 2f * frac)            // ease the sweep in and out
        titleReveal = fe * titleWordTotal
        // ride the drawing head along to the frontier of the revealed word
        if (titleWordSegs.isEmpty()) return
        var i = 0
        while (i < titleWordLen.size - 1 && titleWordLen[i] < titleReveal) i++
        val seg = titleWordSegs[i]
        val segStart = if (i == 0) 0f else titleWordLen[i - 1]
        val segLen = (titleWordLen[i] - segStart).coerceAtLeast(1e-4f)
        val f = ((titleReveal - segStart) / segLen).coerceIn(0f, 1f)
        titleX = seg[0] + (seg[2] - seg[0]) * f
        titleZ = seg[1] + (seg[3] - seg[1]) * f
        val ddx = seg[2] - seg[0]; val ddz = seg[3] - seg[1]
        val dl = hypot(ddx.toDouble(), ddz.toDouble()).toFloat().coerceAtLeast(1e-4f)
        titleHeadDX = ddx / dl; titleHeadDZ = ddz / dl
    }

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
                // Buffer the turn; it's resolved against the live heading when it
                // reaches the front of the queue (see applyPlayerTurn). Drop only
                // if the player is mashing well past the buffer depth.
                if (p.pendingTurns.size < MAX_QUEUED_TURNS) {
                    p.pendingTurns.addLast(left)
                    host.sfx(Sfx.TURN, if (left) 1f else 1.18f, 0.7f)
                }
            }
            else -> {}
        }
    }

    // --------------------------------------------------------------- update

    fun update(dt: Float) {
        time += dt
        shake = maxOf(0f, shake - dt * 3f)
        jumpAnim = maxOf(0f, jumpAnim - dt * 2.5f)
        captionT = maxOf(0f, captionT - dt)
        updateParticles(dt)
        updateDerez(dt)
        when (state) {
            GameState.TITLE -> updateTitleRider(dt)
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
            GameState.LIFE_LOST -> { lifeLostTimer -= dt; if (lifeLostTimer <= 0f) startLevel(level, respawn = true) }
            GameState.LEVEL_CLEAR -> { clearTimer -= dt; if (clearTimer <= 0f) startLevel(level + 1) }
            GameState.GAME_OVER -> {}
        }
    }

    private fun updateRacing(dt: Float) {
        for (c in cycles) if (c.alive) stepCycle(c, dt)
        updateRecognizers(dt)
        updateBolts(dt)

        val p = player
        if (p != null && !p.alive) { loseLife(); return }
        if (opponentsAlive == 0) {
            addScore(500 * level)
            state = GameState.LEVEL_CLEAR
            clearTimer = 3.4f   // room for the clear line to finish before the next start line
            host.stopDrone()
            host.sfx(Sfx.LEVELUP)
            mcpSays("l%02d_clear".format(level), "level_clear")
        }
    }

    /** Player derezzed: spend a life and restart the level, or end the run. */
    private fun loseLife() {
        lives--
        host.stopDrone()
        if (lives <= 0) {
            gameOver()
        } else {
            state = GameState.LIFE_LOST
            lifeLostTimer = 1.9f
            host.sfx(Sfx.WARN, 0.7f, 0.8f)
            mcpSays("life_lost")
        }
    }

    /** All scoring flows through here so bonus lives are awarded consistently. */
    private fun addScore(n: Int) {
        score += n
        while (score >= nextLifeScore) {
            nextLifeScore += EXTRA_LIFE_STEP
            if (lives < MAX_LIVES) {
                lives++
                host.sfx(Sfx.EXTRA)
                mcpSays("extra_life", priority = 0)
            }
        }
        if (score > highScore) { highScore = score; store.highScore = score }
    }

    private fun stepCycle(c: Cycle, dt: Float) {
        c.progress += speed * dt
        var guard = 0
        while (c.progress >= 1f && c.alive && guard++ < 4) {
            c.progress -= 1f
            if (!c.isPlayer) aiSteer(c) else applyPlayerTurn(c)
            val tx = c.cx + DX[c.dir]; val tz = c.cz + DZ[c.dir]
            if (blocked(tx, tz)) {
                // Charged player auto-hops a single light beam — enemy OR their
                // own — but never the arena wall/border. Once-per-level jump.
                if (c.isPlayer && jumpArmed && cellVal(tx, tz) > 0) {
                    val lx = tx + DX[c.dir]; val lz = tz + DZ[c.dir]
                    if (!blocked(lx, lz)) {
                        jumpArmed = false; jumpAnim = 1f
                        c.cx = lx; c.cz = lz
                        grid[lz * GRID + lx] = P_TRAIL // (tx,tz) stays a gap: airborne
                        jumpPuff(tx + 0.5f, tz + 0.5f, c.hue)
                        host.sfx(Sfx.JUMP)
                        continue
                    }
                }
                derez(c); return
            }
            c.cx = tx; c.cz = tz
            grid[tz * GRID + tx] = if (c.isPlayer) P_TRAIL else R_TRAIL
            if (c.isPlayer && powerActive && tx == powerX && tz == powerZ) {
                powerActive = false; jumpArmed = true
                host.sfx(Sfx.POWER, 1.1f, 0.9f)
            }
        }
    }

    private fun cellVal(x: Int, z: Int): Int {
        if (x < 0 || x >= GRID || z < 0 || z >= GRID) return -1
        return grid[z * GRID + x]
    }

    private fun applyPlayerTurn(c: Cycle) {
        // One queued turn per cell. A single relative turn is always a legal 90°
        // (never the current heading or a reversal), so it always applies; the
        // rest wait for upcoming cells, producing a proper staircase.
        if (c.pendingTurns.isEmpty()) return
        val left = c.pendingTurns.removeFirst()
        c.dir = if (left) leftOf(c.dir) else rightOf(c.dir)
        c.trail.add(intArrayOf(c.cx, c.cz))
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
        if (!c.isPlayer) {
            addScore(100 * level); host.sfx(Sfx.KILL, 1f, 0.9f)
            beamDerez(c) // only THIS rival's light wall powers down
            // the MCP does not appreciate losing riders (incidental — never
            // cuts off a level-clear or start beat)
            if (rng.nextFloat() < 0.4f && opponentsAlive > 0) mcpSays("kill_taunt", priority = 0)
        }
    }

    /** Dissolve one rival's beam: free its cells, spray it, and start the fade. */
    private fun beamDerez(c: Cycle) {
        c.derezT = BEAM_DEREZ_DUR
        clearTrailCells(c)
        sprayBeam(c)
        host.sfx(Sfx.BEAMOUT)
    }

    /** Zero out exactly the cells this cycle owned, so its wall stops blocking. */
    private fun clearTrailCells(c: Cycle) {
        val pts = c.trail
        for (i in 1 until pts.size) clearLineCells(pts[i - 1][0], pts[i - 1][1], pts[i][0], pts[i][1])
        if (pts.isNotEmpty()) clearLineCells(pts.last()[0], pts.last()[1], c.cx, c.cz)
        else if (c.cz in 0 until GRID && c.cx in 0 until GRID) grid[c.cz * GRID + c.cx] = 0
    }

    private fun clearLineCells(x0: Int, z0: Int, x1: Int, z1: Int) {
        val sx = sgn(x1 - x0); val sz = sgn(z1 - z0)
        var x = x0; var z = z0
        while (true) {
            // Guard on R_TRAIL so we never erase the player's beam or the border.
            if (x in 0 until GRID && z in 0 until GRID && grid[z * GRID + x] == R_TRAIL) grid[z * GRID + x] = 0
            if (x == x1 && z == z1) break
            x += sx; z += sz
        }
    }

    private fun sgn(v: Int) = if (v > 0) 1 else if (v < 0) -1 else 0

    /** A burst of sparks along the whole length of a derezzing wall. */
    private fun sprayBeam(c: Cycle) {
        val pts = ArrayList(c.trail).apply { add(intArrayOf(c.cx, c.cz)) }
        var budget = 90
        for (i in 1 until pts.size) {
            val sx = sgn(pts[i][0] - pts[i - 1][0]); val sz = sgn(pts[i][1] - pts[i - 1][1])
            var x = pts[i - 1][0]; var z = pts[i - 1][1]
            while (budget > 0) {
                spawnBeamSpark(x + 0.5f, z + 0.5f, c.hue); budget--
                if (x == pts[i][0] && z == pts[i][1]) break
                x += sx; z += sz
            }
        }
    }

    private fun spawnBeamSpark(x: Float, z: Float, hue: Float) {
        val p = pool.removeFirstOrNull() ?: Particle()
        p.x = x + (rng.nextFloat() - 0.5f) * 0.4f
        p.y = rng.nextFloat() * WALL_H
        p.z = z + (rng.nextFloat() - 0.5f) * 0.4f
        val a = rng.nextFloat() * 6.2832f; val sp = 0.5f + rng.nextFloat() * 2.5f
        p.vx = cos(a) * sp; p.vz = sin(a) * sp; p.vy = 1.5f + rng.nextFloat() * 4f
        p.life = 0.5f + rng.nextFloat() * 0.7f; p.maxLife = p.life
        p.hue = (hue + rng.nextFloat() * 0.15f) % 1f
        particles.add(p)
    }

    /** Tick down each dissolving beam, trickle sparks, then drop the dead cycle. */
    private fun updateDerez(dt: Float) {
        var i = cycles.size - 1
        while (i >= 0) {
            val c = cycles[i]
            if (c.derezT > 0f) {
                c.derezT -= dt
                if (rng.nextFloat() < 0.6f && c.trail.isNotEmpty()) {
                    val seg = c.trail[rng.nextInt(c.trail.size)]
                    spawnBeamSpark(seg[0] + 0.5f, seg[1] + 0.5f, c.hue)
                }
                if (c.derezT <= 0f) { c.derezT = 0f; cycles.removeAt(i) }
            }
            i--
        }
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
        lives = START_LIVES
        nextLifeScore = EXTRA_LIFE_STEP
        startLevel(1)
    }

    private fun startLevel(lv: Int, respawn: Boolean = false) {
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
        placePowerUp()
        jumpArmed = false
        jumpAnim = 0f

        countdown = COUNT_SECS
        lastBeep = -1
        state = GameState.COUNTDOWN
        host.titleMusic(false)
        host.sfx(Sfx.START)
        // On a respawn you're mid-level — don't replay the intro taunt (and
        // don't let it step on the life-lost line still finishing).
        if (!respawn) mcpSays("l%02d_start".format(lv), "level_start")
        if (lv > bestLevel) { bestLevel = lv; store.bestLevel = lv }
        if (score > highScore) { highScore = score; store.highScore = score }
    }

    private fun occupy(c: Cycle) { grid[c.cz * GRID + c.cx] = if (c.isPlayer) P_TRAIL else R_TRAIL }

    /** Drop the level's single jump pickup on an empty cell away from the player. */
    private fun placePowerUp() {
        powerActive = false; powerX = -1; powerZ = -1
        val pl = player
        var tries = 0
        while (tries++ < 300) {
            val x = 3 + rng.nextInt(GRID - 6)
            val z = 3 + rng.nextInt(GRID - 6)
            if (grid[z * GRID + x] != 0) continue
            if (pl != null && abs(x - pl.cx) + abs(z - pl.cz) < 6) continue
            powerX = x; powerZ = z; powerActive = true
            return
        }
    }

    private fun jumpPuff(x: Float, z: Float, hue: Float) {
        repeat(18) {
            val p = pool.removeFirstOrNull() ?: Particle()
            p.x = x; p.y = 0.2f; p.z = z
            val a = rng.nextFloat() * 6.2832f; val sp = 1f + rng.nextFloat() * 3f
            p.vx = cos(a) * sp; p.vz = sin(a) * sp; p.vy = 3f + rng.nextFloat() * 3f
            p.life = 0.5f + rng.nextFloat() * 0.3f; p.maxLife = p.life
            p.hue = (hue + 0.5f) % 1f
            particles.add(p)
        }
    }

    private fun gameOver() {
        state = GameState.GAME_OVER
        host.stopDrone()
        host.sfx(Sfx.GAMEOVER)
        mcpSays("game_over")
        if (score > highScore) { highScore = score; store.highScore = score }
    }

    fun toTitle() {
        state = GameState.TITLE
        cycles.clear(); recognizers.clear(); bolts.clear()
        player = null
        powerActive = false; jumpArmed = false; jumpAnim = 0f
        host.stopDrone()
        // the attract loop: IO Tower theme + a demo cycle drawing "X3 PRO"
        titleDrawT = 0f
        host.titleMusic(true)
        if (!saidTitleLine) {
            saidTitleLine = true
            mcpSays("title")
        }
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
