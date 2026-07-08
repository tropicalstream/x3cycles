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
import com.x3cycles.engine.Game
import com.x3cycles.engine.GameHost
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
    private lateinit var game: Game
    private lateinit var glView: GLSurfaceView
    private lateinit var renderer: GLRenderer

    // De-dupe one physical press that may arrive as both KEY and touch.
    private var lastTurn = 0L
    private var downX = 0f
    private var downT = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = SettingsStore(this)
        sfx = Sfx(this).also { it.loadAsync() }
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
    override fun startDrone() = sfx.startDrone()
    override fun stopDrone() = sfx.stopDrone()

    // --------------------------------------------------------------- input

    // Game.turn(left=true) bends the cycle left of its own heading, left=false
    // bends it right — relative to the cycle, never to the screen.
    private fun turn(left: Boolean) {
        val now = SystemClock.uptimeMillis()
        if (now - lastTurn < 60) return // de-dupe KEY+touch of the same physical press
        lastTurn = now
        glView.queueEvent { game.turn(left) } // run on the GL thread
    }

    private fun turnForward() = turn(false) // forward swipe -> turn right of heading
    private fun turnBack() = turn(true)     // back swipe -> turn left of heading

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_UP) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_RIGHT -> { turnForward(); return true }
                KeyEvent.KEYCODE_DPAD_LEFT -> { turnBack(); return true }
                // A lone click (no direction) still starts the game / retries
                // from the title & game-over screens.
                KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_SPACE -> { turnForward(); return true }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        // Ignore the left temple volume pad.
        if (ev.device?.name?.contains("cyttsp6", ignoreCase = true) == true) return true
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = ev.x; downT = SystemClock.uptimeMillis() }
            MotionEvent.ACTION_UP -> {
                val dx = ev.x - downX
                val thresh = max(48f, 0.09f * resources.displayMetrics.widthPixels)
                if (abs(dx) >= thresh) {
                    // Right temple pad forward/back axis reads as raw dx, sign
                    // inverted vs the physical gesture (same inversion the
                    // x3dflappy horizontal swipe needed on this hardware).
                    if (dx < 0) turnForward() else turnBack()
                } else if (SystemClock.uptimeMillis() - downT <= 320) {
                    turnForward() // plain click: start / retry
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
    }

    override fun onPause() {
        sfx.stopDrone()
        glView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
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
