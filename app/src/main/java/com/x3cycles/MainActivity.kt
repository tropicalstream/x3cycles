package com.x3cycles

import android.app.Activity
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import kotlin.math.abs
import kotlin.math.max
import com.x3cycles.audio.Sfx
import com.x3cycles.audio.Voice
import com.x3cycles.engine.Game
import com.x3cycles.engine.GameHost
import com.x3cycles.engine.GameState
import com.x3cycles.gl.GLRenderer

/**
 * X3 Cycles. TWO controls only, read off the right temple pad as a single
 * swipe gesture relative to the cycle's own heading (never absolute
 * left/right, per the vendor's Forward/Backward gesture model): swipe
 * FORWARD turns the cycle right of its current orientation, swipe BACK
 * turns it left. DPAD left/right keys mirror the same forward/back gesture
 * for testing. No settings menu / double-tap.
 */
class MainActivity : Activity(), GameHost {

    private lateinit var store: SettingsStore
    private lateinit var sfx: Sfx
    private lateinit var voice: Voice
    private lateinit var game: Game
    private lateinit var glView: GLSurfaceView
    private lateinit var renderer: GLRenderer

    // De-dupe one physical press that may arrive as both KEY and touch.
    private var lastTurn = 0L
    private var downX = 0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = SettingsStore(this)
        sfx = Sfx(this).also { it.loadAsync() }
        voice = Voice(this).also { it.load() }
        game = Game(store, this)
        renderer = GLRenderer(game).also { it.sbs = store.sbs }

        glView = object : GLSurfaceView(this) {}.apply {
            setEGLContextClientVersion(3)
            preserveEGLContextOnPause = true
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }
        setContentView(glView)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemBars()
        game.boot()
    }

    // ------------------------------------------------------------ GameHost

    override fun sfx(id: Int, pitch: Float, vol: Float) = sfx.play(id, pitch, vol)
    override fun startDrone() = sfx.startDrone(game.remixMode)
    override fun stopDrone() = sfx.stopDrone()
    override fun voice(priority: Int, vararg events: String): String? = voice.play(priority, *events)
    override fun titleMusic(on: Boolean) = voice.titleMusic(on)

    // --------------------------------------------------------------- input

    // Game.turn(left=true) bends the cycle left of its own heading, left=false
    // bends it right — relative to the cycle, never to the screen.
    private fun turn(left: Boolean) {
        val now = SystemClock.uptimeMillis()
        // Only meant to swallow a KEY+touch pair from ONE physical press (they
        // arrive within a few ms). Kept short so two genuinely quick flicks
        // aren't merged into one.
        if (now - lastTurn < 25) return
        lastTurn = now
        glView.queueEvent {
            when (game.state) {
                GameState.TITLE -> game.toggleMode()
                GameState.GAME_OVER -> game.toTitle()
                else -> game.turn(left)
            }
        }
    }

    private fun confirmOrSpecial() {
        val now = SystemClock.uptimeMillis()
        if (now - lastTurn < 25) return
        lastTurn = now
        glView.queueEvent {
            when (game.state) {
                GameState.TITLE, GameState.GAME_OVER -> game.startGame()
                GameState.RACING -> game.triggerRemixAction()
                else -> {}
            }
        }
    }

    private fun turnForward() = turn(false) // forward swipe -> turn right of heading
    private fun turnBack() = turn(true)     // back swipe -> turn left of heading

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_UP) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_RIGHT -> { turnForward(); return true }
                KeyEvent.KEYCODE_DPAD_LEFT -> { turnBack(); return true }
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (game.state == GameState.TITLE) { turnForward(); return true }
                }
                // A tap / center click launches the selected mode from the title screen,
                // retries on game over, or fires Disc/Jump in Remix 2000 mode.
                KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_SPACE -> { confirmOrSpecial(); return true }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        // Ignore the left temple volume pad.
        if (ev.device?.name?.contains("cyttsp6", ignoreCase = true) == true) return true
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = ev.x }
            MotionEvent.ACTION_UP -> {
                val dx = ev.x - downX
                val dead = max(16f, 0.02f * resources.displayMetrics.widthPixels)
                val playing = game.state == GameState.RACING || game.state == GameState.COUNTDOWN
                if (playing) {
                    if (abs(dx) >= dead) {
                        if (dx < 0) turnForward() else turnBack()
                    } else {
                        confirmOrSpecial()
                    }
                } else {
                    // On Title: swipe toggles mode (CLASSIC <-> REMIX 2000), tap starts game!
                    // On Game Over: tap retries, swipe returns to Title menu!
                    if (abs(dx) >= dead) {
                        turnForward()
                    } else {
                        confirmOrSpecial()
                    }
                }
            }
        }
        return true
    }

    // ------------------------------------------------------------ lifecycle

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        glView.onResume()
        // returning to a title screen restarts the IO Tower theme
        if (game.state == GameState.TITLE) voice.titleMusic(true)
    }

    override fun onPause() {
        sfx.stopDrone()
        voice.pauseAll()
        glView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        voice.release()
        sfx.release()
        super.onDestroy()
    }

    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        }
    }
}
