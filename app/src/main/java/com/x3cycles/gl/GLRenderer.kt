package com.x3cycles.gl

import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.util.Log
import com.x3cycles.engine.DX
import com.x3cycles.engine.DZ
import com.x3cycles.engine.Game
import com.x3cycles.engine.GameState
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * OpenGL ES 3.0 renderer for X3 Cycles — an ISOMETRIC view (orthographic
 * projection from a fixed 45-degree angle), additive neon lines on black. On
 * the X3 the frame renders once per eye into side-by-side viewports.
 */
class GLRenderer(private val game: Game) : GLSurfaceView.Renderer {

    var sbs = false

    private var program = 0
    private var aPos = 0; private var aColor = 0
    private var uMVP = 0; private var uPointSize = 0; private var uPoint = 0
    private var width = 1; private var height = 1
    private var lastNanos = 0L

    private val proj = FloatArray(16)
    private val view = FloatArray(16)
    private val mvp = FloatArray(16)
    private val ortho = FloatArray(16)
    private val rgb = FloatArray(3)

    private val lines = Batch(20000)
    private val fx = Batch(4000)
    private val hud = Batch(4000)

    private val G = Game.GRID.toFloat()
    private val WH = Game.WALL_H

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        program = buildProgram(VERT, FRAG)
        aPos = GLES30.glGetAttribLocation(program, "aPos")
        aColor = GLES30.glGetAttribLocation(program, "aColor")
        uMVP = GLES30.glGetUniformLocation(program, "uMVP")
        uPointSize = GLES30.glGetUniformLocation(program, "uPointSize")
        uPoint = GLES30.glGetUniformLocation(program, "uPoint")
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE)
        lastNanos = 0L
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
        width = w; height = h
        Matrix.orthoM(ortho, 0, 0f, 640f, 480f, 0f, -1f, 1f)
    }

    override fun onDrawFrame(gl: GL10?) {
        val now = System.nanoTime()
        val dt = if (lastNanos == 0L) 0.016f else ((now - lastNanos) / 1e9f).coerceIn(0f, 0.05f)
        lastNanos = now
        game.update(dt)

        buildScene(); buildHud()

        GLES30.glViewport(0, 0, width, height)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        GLES30.glUseProgram(program)

        // Isometric camera, following the player (or the arena centre).
        val p = game.player
        var cx = if (p != null) p.headX() else G / 2f
        var cz = if (p != null) p.headZ() else G / 2f
        if (game.shake > 0.1f) { cx += (sin(game.time * 53f)) * game.shake * 0.02f; cz += (sin(game.time * 61f)) * game.shake * 0.02f }
        Matrix.setLookAtM(view, 0, cx + 26f, 32f, cz + 26f, cx, 0f, cz, 0f, 1f, 0f)

        val eyes = if (sbs) 2 else 1
        val vw = if (sbs) width / 2 else width
        val aspect = vw.toFloat() / height.toFloat()
        val v = 15f
        Matrix.orthoM(proj, 0, -v * aspect, v * aspect, -v, v, 1f, 300f)
        Matrix.multiplyMM(mvp, 0, proj, 0, view, 0)

        for (e in 0 until eyes) {
            GLES30.glViewport(e * vw, 0, vw, height)
            GLES30.glUniformMatrix4fv(uMVP, 1, false, mvp, 0)
            GLES30.glUniform1f(uPoint, 0f)
            lines.draw(GLES30.GL_LINES)
            GLES30.glUniform1f(uPoint, 1f)
            GLES30.glUniform1f(uPointSize, 12f); fx.draw(GLES30.GL_POINTS)
            GLES30.glUniformMatrix4fv(uMVP, 1, false, ortho, 0)
            GLES30.glUniform1f(uPoint, 0f)
            hud.draw(GLES30.GL_LINES)
        }
    }

    // ------------------------------------------------------- scene build

    private fun buildScene() {
        lines.reset(); fx.reset()
        val h = game.time * 0.05f
        buildGrid(h)
        buildBorder(h)
        if (game.state == GameState.TITLE || game.state == GameState.GAME_OVER) {
            buildMcpTower()
            if (game.state == GameState.TITLE) buildTitleRider()
        }
        if (game.powerActive) buildPowerUp(game.powerX.toFloat(), game.powerZ.toFloat())
        for (c in game.cycles) if (c.derezT > 0f) buildDerezTrail(c) else buildTrail(c)
        for (c in game.cycles) if (c.alive) buildCycleHead(c)
        buildJumpCharge()
        for (r in game.recognizers) buildRecognizer(r.x, r.z)
        for (b in game.bolts) {
            fx.v(b.x, 0.5f, b.z, 1f, 0.3f, 0.2f, 1f)
            lines.line(b.x, 0.5f, b.z, b.x - b.vx * 0.05f, 0.5f, b.z - b.vz * 0.05f, 1f, 0.4f, 0.2f, 0.8f)
        }
        for (pt in game.particles) {
            val k = (pt.life / pt.maxLife).coerceIn(0f, 1f)
            hsv(pt.hue, 1f, 1f)
            fx.v(pt.x, pt.y, pt.z, rgb[0], rgb[1], rgb[2], k)
        }
    }

    private fun buildGrid(h: Float) {
        hsv((h + 0.55f) % 1f, 0.8f, 0.35f)
        val r = rgb[0]; val g = rgb[1]; val b = rgb[2]
        var i = 0
        while (i <= Game.GRID) {
            val a = 0.12f
            lines.line(i.toFloat(), 0f, 0f, i.toFloat(), 0f, G, r, g, b, a)
            lines.line(0f, 0f, i.toFloat(), G, 0f, i.toFloat(), r, g, b, a)
            i += 2
        }
    }

    private fun buildBorder(h: Float) {
        hsv((h + 0.85f) % 1f, 0.9f, 1f)
        val r = rgb[0]; val g = rgb[1]; val b = rgb[2]
        // bottom + top rectangle + corner posts
        for (yy in floatArrayOf(0f, WH * 1.4f)) {
            lines.line(0f, yy, 0f, G, yy, 0f, r, g, b, 0.9f)
            lines.line(0f, yy, G, G, yy, G, r, g, b, 0.9f)
            lines.line(0f, yy, 0f, 0f, yy, G, r, g, b, 0.9f)
            lines.line(G, yy, 0f, G, yy, G, r, g, b, 0.9f)
        }
        for (px in floatArrayOf(0f, G)) for (pz in floatArrayOf(0f, G))
            lines.line(px, 0f, pz, px, WH * 1.4f, pz, r, g, b, 0.9f)
    }

    private fun buildTrail(c: com.x3cycles.engine.Cycle) {
        hsv(c.hue, 0.9f, if (c.alive) 1f else 0.5f)
        val r = rgb[0]; val g = rgb[1]; val b = rgb[2]
        val pts = c.trail
        // Wall along each straight segment; posts at each corner.
        for (i in 0 until pts.size) {
            val ax = pts[i][0].toFloat() + 0.5f; val az = pts[i][1].toFloat() + 0.5f
            lines.line(ax, 0f, az, ax, WH, az, r, g, b, 0.6f)
            if (i > 0) {
                val bx = pts[i - 1][0].toFloat() + 0.5f; val bz = pts[i - 1][1].toFloat() + 0.5f
                lines.line(ax, 0.02f, az, bx, 0.02f, bz, r, g, b, 0.6f)
                lines.line(ax, WH, az, bx, WH, bz, r, g, b, 0.95f)
            }
        }
        // last corner -> head
        if (pts.isNotEmpty()) {
            val lx = pts.last()[0].toFloat() + 0.5f; val lz = pts.last()[1].toFloat() + 0.5f
            val hx = c.headX() + 0.5f; val hz = c.headZ() + 0.5f
            lines.line(lx, 0.02f, lz, hx, 0.02f, hz, r, g, b, 0.6f)
            lines.line(lx, WH, lz, hx, WH, hz, r, g, b, 0.95f)
        }
    }

    /** A downed rival's wall: sinking into the floor, flickering and whitening. */
    private fun buildDerezTrail(c: com.x3cycles.engine.Cycle) {
        val frac = (c.derezT / Game.BEAM_DEREZ_DUR).coerceIn(0f, 1f)
        val flick = 0.45f + 0.55f * sin(game.time * 55f + c.hue * 40f)
        hsv(c.hue, 0.45f * frac, 1f) // desaturate toward white as it dies
        val r = rgb[0]; val g = rgb[1]; val b = rgb[2]
        val topY = WH * frac
        val pts = c.trail
        for (i in 0 until pts.size) {
            val ax = pts[i][0].toFloat() + 0.5f; val az = pts[i][1].toFloat() + 0.5f
            lines.line(ax, 0f, az, ax, topY, az, r, g, b, frac * flick)
            if (i > 0) {
                val bx = pts[i - 1][0].toFloat() + 0.5f; val bz = pts[i - 1][1].toFloat() + 0.5f
                lines.line(ax, 0.02f, az, bx, 0.02f, bz, r, g, b, frac * 0.6f)
                lines.line(ax, topY, az, bx, topY, bz, r, g, b, frac * flick)
            }
        }
        if (pts.isNotEmpty()) {
            val lx = pts.last()[0].toFloat() + 0.5f; val lz = pts.last()[1].toFloat() + 0.5f
            val hx = c.headX() + 0.5f; val hz = c.headZ() + 0.5f
            lines.line(lx, 0.02f, lz, hx, 0.02f, hz, r, g, b, frac * 0.6f)
            lines.line(lx, topY, lz, hx, topY, hz, r, g, b, frac * flick)
        }
    }

    private fun buildCycleHead(c: com.x3cycles.engine.Cycle) {
        val hx = c.headX() + 0.5f; val hz = c.headZ() + 0.5f
        hsv(c.hue, 0.6f, 1f)
        val r = rgb[0]; val g = rgb[1]; val b = rgb[2]
        val fx0 = DX[c.dir] * 0.5f; val fz0 = DZ[c.dir] * 0.5f
        // a little bright bike: nose + tail + a cross-bar
        lines.line(hx - fx0, WH * 0.5f, hz - fz0, hx + fx0, WH * 0.5f, hz + fz0, r, g, b, 1f)
        lines.line(hx - fz0 * 0.5f, WH * 0.6f, hz + fx0 * 0.5f, hx + fz0 * 0.5f, WH * 0.6f, hz - fx0 * 0.5f, r, g, b, 0.9f)
        fx.v(hx, WH * 0.6f, hz, 1f, 1f, 1f, 1f)
    }

    /** The level's jump pickup: a bobbing spring-green beacon with an up-chevron. */
    private fun buildPowerUp(gx: Float, gz: Float) {
        val x = gx + 0.5f; val z = gz + 0.5f
        val pulse = 0.55f + 0.45f * sin(game.time * 5f)
        hsv(0.42f, 0.75f, 1f)
        val r = rgb[0]; val g = rgb[1]; val b = rgb[2]
        // beacon column so it reads from across the grid
        lines.line(x, 0f, z, x, 1.6f, z, r, g, b, 0.3f * pulse)
        val y = 0.6f + 0.12f * sin(game.time * 4f)
        val s = 0.42f
        val a = game.time * 2.5f
        val ca = cos(a) * s; val sa = sin(a) * s
        // spinning cross
        lines.line(x - ca, y, z - sa, x + ca, y, z + sa, r, g, b, pulse)
        lines.line(x - sa, y, z + ca, x + sa, y, z - ca, r, g, b, pulse)
        // up-chevron hinting "jump"
        lines.line(x - s * 0.6f, y, z, x, y + s * 0.8f, z, r, g, b, pulse)
        lines.line(x + s * 0.6f, y, z, x, y + s * 0.8f, z, r, g, b, pulse)
        fx.v(x, y, z, r, g, b, 1f)
    }

    /** Orbiting sparks around the player's head while a jump is charged. */
    private fun buildJumpCharge() {
        if (!game.jumpArmed) return
        val p = game.player ?: return
        if (!p.alive) return
        val hx = p.headX() + 0.5f; val hz = p.headZ() + 0.5f
        hsv(0.42f, 0.7f, 1f)
        val r = rgb[0]; val g = rgb[1]; val b = rgb[2]
        val rad = 0.55f
        for (k in 0 until 3) {
            val a = game.time * 3f + k * 2.094f
            fx.v(hx + cos(a) * rad, WH * 0.7f, hz + sin(a) * rad, r, g, b, 1f)
        }
    }

    /**
     * The MONOPOLY CONTROL PROTOCOL, as presented in x3breakout's McpCore —
     * corporate cyan-violet drifting hostile red, smug pulse, glitch
     * flicker — rebuilt as a wireframe citadel: tapering rotating rings,
     * ribs, a scanning eye with spokes, ground halo. Looms at grid centre
     * over the title (and gloats over the game-over screen).
     */
    private fun buildMcpTower() {
        val t = game.time
        val x = G / 2f; val z = G / 2f
        // authority pulse + palette drift (McpCore's language)
        val pulse = 0.85f + 0.15f * sin(t * 2.2f)
        val harsh = 0.5f + 0.5f * sin(t * 0.13f)
        val r = (0.45f + 0.45f * harsh) * pulse
        val g = (0.22f + 0.28f * (1f - harsh)) * pulse
        val b = (0.95f - 0.5f * harsh) * pulse
        // glitch: rare whole-frame flicker + jitter
        if (sin(t * 43f) > 0.985f) return
        val jx = sin(t * 31.7f) * 0.06f
        val jz = cos(t * 27.3f) * 0.05f
        val cx = x + jx; val cz = z + jz

        // ground halo rings
        ringY(cx, cz, 5.2f, 0.02f, 20, t * 0.3f, r, g, b, 0.35f)
        ringY(cx, cz, 6.1f, 0.02f, 20, -t * 0.2f, r, g, b, 0.18f)

        // the citadel: stacked rings tapering up, alternating spin
        val heights = floatArrayOf(1.6f, 3.6f, 5.6f, 7.4f, 8.8f)
        val radii = floatArrayOf(3.6f, 3.1f, 2.5f, 1.9f, 1.2f)
        for (k in heights.indices) {
            val spin = t * (if (k % 2 == 0) 0.35f else -0.5f)
            ringY(cx, cz, radii[k], heights[k], 16, spin, r, g, b, 0.75f)
        }
        // ribs base ring -> crown ring
        for (i in 0 until 6) {
            val a0 = i / 6f * 6.2832f + t * 0.35f
            val a1 = i / 6f * 6.2832f - t * 0.5f
            lines.line(
                cx + cos(a0) * radii[0], heights[0], cz + sin(a0) * radii[0],
                cx + cos(a1) * radii[4], heights[4], cz + sin(a1) * radii[4],
                r, g, b, 0.4f
            )
        }
        // the EYE: white-hot scanning point + spokes, mid-tower
        val eyeY = 6.4f
        val eyeA = t * 0.8f
        val ex = cx + cos(eyeA) * 2.2f; val ez = cz + sin(eyeA) * 2.2f
        fx.v(ex, eyeY, ez, 1f, 0.9f * pulse, 0.85f * pulse, 1f)
        for (i in 0 until 8) {
            val a = i / 8f * 6.2832f + t
            lines.line(
                ex, eyeY, ez,
                ex + cos(a) * 0.7f, eyeY + sin(a * 1.7f) * 0.3f, ez + sin(a) * 0.7f,
                1f, 0.6f, 0.55f, 0.55f * pulse
            )
        }
    }

    /** A horizontal ring of line segments at height y, phase-rotated. */
    private fun ringY(cx: Float, cz: Float, rad: Float, y: Float, segs: Int, phase: Float, r: Float, g: Float, b: Float, a: Float) {
        var pa = phase
        val step = 6.2832f / segs
        for (i in 0 until segs) {
            val na = pa + step
            lines.line(
                cx + cos(pa) * rad, y, cz + sin(pa) * rad,
                cx + cos(na) * rad, y, cz + sin(na) * rad,
                r, g, b, a
            )
            pa = na
        }
    }

    /** The attract-mode cycle: rides the grid, wall glowing behind it. */
    private fun buildTitleRider() {
        val hue = 0.5f
        hsv(hue, 0.9f, 1f)
        val r = rgb[0]; val g = rgb[1]; val b = rgb[2]
        val pts = game.titleTrail
        for (i in 0 until pts.size) {
            val ax = pts[i][0] + 0.5f; val az = pts[i][1] + 0.5f
            // older corners fade — the beam has a memory, like a real ride
            val age = (i + 1f) / (pts.size + 1f)
            lines.line(ax, 0f, az, ax, WH, az, r, g, b, 0.5f * age)
            if (i > 0) {
                val bx = pts[i - 1][0] + 0.5f; val bz = pts[i - 1][1] + 0.5f
                lines.line(ax, 0.02f, az, bx, 0.02f, bz, r, g, b, 0.5f * age)
                lines.line(ax, WH, az, bx, WH, bz, r, g, b, 0.85f * age)
            }
        }
        val hx = game.titleX + 0.5f; val hz = game.titleZ + 0.5f
        if (pts.isNotEmpty()) {
            val lx = pts.last()[0] + 0.5f; val lz = pts.last()[1] + 0.5f
            lines.line(lx, 0.02f, lz, hx, 0.02f, hz, r, g, b, 0.6f)
            lines.line(lx, WH, lz, hx, WH, hz, r, g, b, 0.95f)
        }
        // the bike itself, nose lit
        val fx0 = DX[game.titleDir] * 0.5f; val fz0 = DZ[game.titleDir] * 0.5f
        lines.line(hx - fx0, WH * 0.5f, hz - fz0, hx + fx0, WH * 0.5f, hz + fz0, r, g, b, 1f)
        lines.line(hx - fz0 * 0.5f, WH * 0.6f, hz + fx0 * 0.5f, hx + fz0 * 0.5f, WH * 0.6f, hz - fx0 * 0.5f, r, g, b, 0.9f)
        fx.v(hx, WH * 0.6f, hz, 1f, 1f, 1f, 1f)
    }

    private fun buildRecognizer(x: Float, z: Float) {
        val r = 1f; val g = 0.35f; val b = 0.15f
        val s = 0.95f; val ht = 1.8f
        // four legs
        for (sx in floatArrayOf(-s, s)) for (sz in floatArrayOf(-s, s))
            lines.line(x + sx, 0f, z + sz, x + sx, ht, z + sz, r, g, b, 0.85f)
        // top rectangle
        lines.line(x - s, ht, z - s, x + s, ht, z - s, r, g, b, 0.95f)
        lines.line(x - s, ht, z + s, x + s, ht, z + s, r, g, b, 0.95f)
        lines.line(x - s, ht, z - s, x - s, ht, z + s, r, g, b, 0.95f)
        lines.line(x + s, ht, z - s, x + s, ht, z + s, r, g, b, 0.95f)
        // menacing eye
        fx.v(x, ht * 0.85f, z, 1f, 0.2f, 0.1f, 1f)
    }

    // -------------------------------------------------------------- hud

    private val sink = object : StrokeFont.LineSink {
        var cr = 1f; var cg = 1f; var cb = 1f; var ca = 1f
        override fun line(x0: Float, y0: Float, x1: Float, y1: Float) { hud.line(x0, y0, 0f, x1, y1, 0f, cr, cg, cb, ca) }
    }

    private fun text(s: String, cx: Float, y: Float, scale: Float, r: Float, g: Float, b: Float, a: Float = 1f, center: Boolean = true) {
        val x = if (center) cx - StrokeFont.width(s, scale) / 2f else cx
        sink.cr = r; sink.cg = g; sink.cb = b; sink.ca = a
        StrokeFont.draw(s, x, y, scale, sink)
    }

    private fun buildHud() {
        hud.reset()
        val pulse = 0.55f + 0.45f * sin(game.time * 4f)
        hsv(game.time * 0.05f, 0.8f, 1f)
        val hr = rgb[0]; val hg = rgb[1]; val hb = rgb[2]
        when (game.state) {
            GameState.TITLE -> {
                text("X3 CYCLES", 320f, 120f, 4.6f, hr, hg, hb)
                text("THE MONOPOLY CONTROL PROTOCOL HOLDS THE GRID", 320f, 168f, 1.3f, 1f, 0.45f, 0.4f, 0.85f)
                text("TURN LEFT OR RIGHT TO RIDE", 320f, 396f, 1.7f, 1f, 1f, 1f, pulse)
                if (game.bestLevel > 1) text("BEST LEVEL ${game.bestLevel}", 320f, 436f, 1.5f, 0.6f, 1f, 0.7f)
            }
            GameState.COUNTDOWN -> {
                bar()
                val c = game.countInt
                if (c > 0) text("$c", 320f, 260f, 9f, 1f, 1f, 0.4f) else text("GO", 320f, 240f, 6f, 0.5f, 1f, 0.6f, pulse)
            }
            GameState.RACING -> {
                bar()
                if (game.jumpArmed) text("JUMP READY", 320f, 66f, 1.6f, 0.5f, 1f, 0.7f, pulse)
                else if (game.powerActive) text("GRAB THE JUMP", 320f, 66f, 1.5f, 0.5f, 1f, 0.7f, pulse * 0.8f)
                if (game.recognizerCount > 0) text("RECOGNIZERS INBOUND", 320f, 452f, 1.5f, 1f, 0.4f, 0.2f, pulse)
            }
            GameState.LIFE_LOST -> {
                bar()
                text("CYCLE DEREZZED", 320f, 200f, 3f, 1f, 0.45f, 0.35f)
                val msg = if (game.lives == 1) "1 CYCLE LEFT" else "${game.lives} CYCLES LEFT"
                text(msg, 320f, 262f, 2f, 1f, 1f, 1f, pulse)
                text("REGENERATING", 320f, 312f, 1.7f, 0.6f, 0.9f, 1f, pulse)
            }
            GameState.LEVEL_CLEAR -> {
                bar()
                text("LEVEL CLEAR", 320f, 230f, 3.4f, 0.5f, 1f, 0.6f, pulse)
            }
            GameState.GAME_OVER -> {
                text("DEREZZED", 320f, 180f, 3.6f, 1f, 0.35f, 0.3f)
                text("REACHED LEVEL ${game.level}", 320f, 240f, 2f, 0.8f, 0.9f, 1f)
                text("BEST ${game.bestLevel}", 320f, 285f, 1.8f, 0.6f, 1f, 0.7f)
                text("TURN TO RETRY", 320f, 360f, 2f, 1f, 1f, 1f, pulse)
            }
        }
        // ---- the MCP's caption: whatever it just said, vector-set and
        //      auto-shrunk to fit, in its alarm-red register ----
        if (game.captionT > 0f) {
            val s = game.caption
            if (s.isNotEmpty()) {
                val fade = (game.captionT / 0.6f).coerceAtMost(1f)
                val scale = minOf(1.6f, 600f / StrokeFont.width(s, 1f))
                val y = if (game.state == GameState.TITLE) 210f else 424f
                text("MCP", 320f, y - 16f * scale, scale * 0.75f, 1f, 0.3f, 0.25f, 0.8f * fade)
                text(s, 320f, y, scale, 1f, 0.55f, 0.5f, fade)
            }
        }
    }

    private fun bar() {
        text("LV ${game.level}", 18f, 42f, 2f, 0.7f, 0.85f, 1f, 1f, center = false)
        val opp = "RIVALS ${game.opponentsAlive}"
        text(opp, 320f - StrokeFont.width(opp, 1.8f) / 2f, 42f, 1.8f, 1f, 0.8f, 0.4f, 1f, center = false)
        val sc = "${game.score}"
        text(sc, 624f - StrokeFont.width(sc, 2f), 42f, 2f, 1f, 1f, 1f, 1f, center = false)
        livesPips()
    }

    /** Remaining lives as a row of little up-triangle cycle pips, bottom-left. */
    private fun livesPips() {
        val n = game.lives.coerceIn(0, Game.MAX_LIVES)
        val w = 7f; val h = 13f; val gap = 22f; val py = 458f
        for (i in 0 until n) {
            val px = 26f + i * gap
            hud.line(px, py, 0f, px - w, py + h, 0f, 0.4f, 0.9f, 1f, 1f)
            hud.line(px, py, 0f, px + w, py + h, 0f, 0.4f, 0.9f, 1f, 1f)
            hud.line(px - w, py + h, 0f, px + w, py + h, 0f, 0.4f, 0.9f, 1f, 0.9f)
        }
    }

    // ------------------------------------------------------- gl helpers

    private fun hsv(hh: Float, s: Float, v: Float) {
        val h6 = ((hh % 1f + 1f) % 1f) * 6f
        val i = h6.toInt(); val f = h6 - i
        val p = v * (1 - s); val q = v * (1 - s * f); val t = v * (1 - s * (1 - f))
        when (i % 6) {
            0 -> { rgb[0] = v; rgb[1] = t; rgb[2] = p }
            1 -> { rgb[0] = q; rgb[1] = v; rgb[2] = p }
            2 -> { rgb[0] = p; rgb[1] = v; rgb[2] = t }
            3 -> { rgb[0] = p; rgb[1] = q; rgb[2] = v }
            4 -> { rgb[0] = t; rgb[1] = p; rgb[2] = v }
            else -> { rgb[0] = v; rgb[1] = p; rgb[2] = q }
        }
    }

    private fun buildProgram(vs: String, fs: String): Int {
        val v = compile(GLES30.GL_VERTEX_SHADER, vs)
        val f = compile(GLES30.GL_FRAGMENT_SHADER, fs)
        val p = GLES30.glCreateProgram()
        GLES30.glAttachShader(p, v); GLES30.glAttachShader(p, f); GLES30.glLinkProgram(p)
        val ok = IntArray(1); GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) Log.e("X3Cycles", "link: " + GLES30.glGetProgramInfoLog(p))
        return p
    }

    private fun compile(type: Int, src: String): Int {
        val s = GLES30.glCreateShader(type)
        GLES30.glShaderSource(s, src); GLES30.glCompileShader(s)
        val ok = IntArray(1); GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) Log.e("X3Cycles", "compile: " + GLES30.glGetShaderInfoLog(s))
        return s
    }

    inner class Batch(maxVerts: Int) {
        private val fb: FloatBuffer = ByteBuffer.allocateDirect(maxVerts * 7 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        private val cap = maxVerts
        var count = 0; private set
        fun reset() { fb.position(0); count = 0 }
        fun v(x: Float, y: Float, z: Float, r: Float, g: Float, b: Float, a: Float) {
            if (count >= cap) return
            fb.put(x); fb.put(y); fb.put(z); fb.put(r); fb.put(g); fb.put(b); fb.put(a); count++
        }
        fun line(x0: Float, y0: Float, z0: Float, x1: Float, y1: Float, z1: Float, r: Float, g: Float, b: Float, a: Float) {
            v(x0, y0, z0, r, g, b, a); v(x1, y1, z1, r, g, b, a)
        }
        fun draw(mode: Int) {
            if (count == 0) return
            fb.position(0); GLES30.glVertexAttribPointer(aPos, 3, GLES30.GL_FLOAT, false, 28, fb); GLES30.glEnableVertexAttribArray(aPos)
            fb.position(3); GLES30.glVertexAttribPointer(aColor, 4, GLES30.GL_FLOAT, false, 28, fb); GLES30.glEnableVertexAttribArray(aColor)
            GLES30.glDrawArrays(mode, 0, count)
        }
    }

    companion object {
        private const val VERT = """#version 300 es
        in vec3 aPos; in vec4 aColor; uniform mat4 uMVP; uniform float uPointSize; out vec4 vColor;
        void main() { gl_Position = uMVP * vec4(aPos, 1.0); gl_PointSize = uPointSize; vColor = aColor; }"""
        private const val FRAG = """#version 300 es
        precision mediump float; in vec4 vColor; uniform float uPoint; out vec4 fragColor;
        void main() {
            if (uPoint > 0.5) { vec2 d = gl_PointCoord - vec2(0.5); float r2 = dot(d, d); if (r2 > 0.25) discard; fragColor = vec4(vColor.rgb, vColor.a * (1.0 - r2 * 4.0)); }
            else { fragColor = vColor; }
        }"""
    }
}
