package com.example.patternmemorygame

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.CountDownTimer
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.patternmemorygame.AppConstants.KEY_HIGH_SCORE
import com.example.patternmemorygame.AppConstants.PREFS_NAME
import com.example.patternmemorygame.databinding.ActivityMainBinding
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    // The 9 grid tiles mapped in order: top-left → bottom-right
    private lateinit var tiles: List<View>

    // The sequence of tile indices the player must memorise
    private val pattern = mutableListOf<Int>()

    // The player's input so far for the current level
    private val playerInput = mutableListOf<Int>()

    private var currentLevel = 1
    private var isShowingPattern = false

    // Blocks tile input during game over state (timeout or wrong tap)
    private var isGameOver = false

    // Active countdown timer — replaced each level, cancelled on game over
    private var countDownTimer: CountDownTimer? = null

    // True when the timer expired naturally — used to show "Time's Up!"
    // instead of "Pattern Broken!" in gameOver()
    private var timedOut = false

    // ── SharedPreferences (local high score cache) ────────────────────────────
    private val prefs by lazy { getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

    // ── Tile colors ───────────────────────────────────────────────────────────
    private val colorTileDefault   = Color.parseColor("#37474F") // idle — dark slate
    private val colorTileHighlight = Color.parseColor("#FFD600") // pattern flash — amber
    private val colorTileCorrect   = Color.parseColor("#00C853") // correct tap — green
    private val colorTileWrong     = Color.parseColor("#FF1744") // wrong tap — red

    // ── UI text colors ────────────────────────────────────────────────────────
    private val colorYellow  = Color.parseColor("#FFD600") // watching / paused
    private val colorGreen   = Color.parseColor("#00C853") // player's turn / success
    private val colorRed     = Color.parseColor("#FF1744") // urgent / loss
    private val colorNeutral = Color.parseColor("#90A4AE") // idle — matches text_secondary

    // ─────────────────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Build the tile list in grid order (tile0 … tile8)
        tiles = listOf(
            binding.tile0, binding.tile1, binding.tile2,
            binding.tile3, binding.tile4, binding.tile5,
            binding.tile6, binding.tile7, binding.tile8
        )

        tiles.forEachIndexed { index, view ->
            view.setOnClickListener { onTileTapped(index) }
        }

        binding.btnStart.setOnClickListener {
            lifecycleScope.launch { startWithCountdown() }
        }

        setTilesEnabled(false)
        binding.tvStatus.text = "Press Start to Begin!"
        binding.tvLevel.text = "Level 1"

        // Show the local cached score immediately, then sync from Firestore
        loadHighScoreFromFirestore()
    }

    // ── Countdown ─────────────────────────────────────────────────────────────

    /**
     * Shows the full-screen READY / 3 / 2 / 1 overlay, then starts the game.
     * The Start button is hidden for the entire duration of gameplay.
     */
    private suspend fun startWithCountdown() {
        binding.btnStart.visibility = View.GONE
        binding.tvStatus.text = ""
        setStatusColor(colorNeutral)
        binding.tvLevel.text = ""
        binding.tvTimer.text = " "
        binding.tvTimer.setTextColor(colorYellow)

        binding.tvCountdown.visibility = View.VISIBLE
        binding.tvCountdown.text = "READY"
        delay(900)
        binding.tvCountdown.text = "3"
        delay(700)
        binding.tvCountdown.text = "2"
        delay(700)
        binding.tvCountdown.text = "1"
        delay(700)
        binding.tvCountdown.visibility = View.GONE

        startNewGame()
    }

    // ── Game flow ─────────────────────────────────────────────────────────────

    private fun startNewGame() {
        currentLevel = 1
        timedOut = false
        isGameOver = false
        pattern.clear()
        playerInput.clear()
        nextLevel()
    }

    private fun nextLevel() {
        playerInput.clear()
        binding.tvLevel.text = "Level $currentLevel"
        binding.tvStatus.text = "Watch the Pattern!"
        setStatusColor(colorYellow)

        // Show the time budget so the player knows how long they will have
        val totalSeconds = 3 + currentLevel
        binding.tvTimer.text = "⏱ $totalSeconds s"
        binding.tvTimer.setTextColor(colorYellow)

        // Add one random tile to grow the pattern each level (0–8 = tile indices)
        pattern.add((0 until 9).random())

        lifecycleScope.launch {
            delay(600)
            showPattern()
        }
    }

    /**
     * Flash timing shrinks by 30 ms per level, floored at 150 ms.
     * Level 1: highlight 500 ms, gap 300 ms
     * Level 6: highlight 350 ms, gap 150 ms (floor reached)
     */
    private fun highlightDuration(): Long = maxOf(500L - (currentLevel - 1) * 30L, 150L)
    private fun gapDuration(): Long       = maxOf(300L - (currentLevel - 1) * 30L, 150L)

    /**
     * Flashes each tile in the pattern one by one using coroutine delays.
     * Once all tiles have flashed, hands control to the player and starts the timer.
     */
    private suspend fun showPattern() {
        isShowingPattern = true
        setTilesEnabled(false)

        for (tileIndex in pattern) {
            setTileColor(tiles[tileIndex], colorTileHighlight)
            delay(highlightDuration())
            setTileColor(tiles[tileIndex], colorTileDefault)
            delay(gapDuration())
        }

        isShowingPattern = false
        setTilesEnabled(true)
        binding.tvStatus.text = "Repeat the Pattern!"
        setStatusColor(colorGreen)
        binding.tvTimer.setTextColor(colorGreen) // onTick switches to red at ≤ 3 s
        startTimer()
    }

    // ── Player input ──────────────────────────────────────────────────────────

    private fun onTileTapped(index: Int) {
        // Ignore taps during pattern display, game over state, or countdown
        if (isShowingPattern || isGameOver) return

        playerInput.add(index)
        val step = playerInput.size - 1

        if (playerInput[step] == pattern[step]) {
            flashTile(index, colorTileCorrect)

            if (playerInput.size == pattern.size) {
                // Full pattern entered correctly — advance to next level
                stopTimer()
                setTilesEnabled(false)
                lifecycleScope.launch {
                    delay(800)
                    currentLevel++
                    nextLevel()
                }
            }
        } else {
            // Wrong tap — block further input immediately, then trigger game over
            isGameOver = true
            stopTimer()
            flashTile(index, colorTileWrong)
            setTilesEnabled(false)
            lifecycleScope.launch {
                delay(700)
                gameOver()
            }
        }
    }

    /** Briefly flashes a tile to [color] then returns it to the default tile color. */
    private fun flashTile(index: Int, color: Int) {
        lifecycleScope.launch {
            setTileColor(tiles[index], color)
            delay(300)
            setTileColor(tiles[index], colorTileDefault)
        }
    }

    // ── Game over ─────────────────────────────────────────────────────────────

    private fun gameOver() {
        stopTimer()
        binding.tvTimer.setTextColor(colorRed) // freeze timer in red

        val score = currentLevel - 1
        val previousBest = prefs.getInt(KEY_HIGH_SCORE, 0)

        when {
            score > previousBest -> {
                // New high score — save locally and to Firestore
                prefs.edit().putInt(KEY_HIGH_SCORE, score).apply()
                updateHighScoreDisplay()
                binding.tvStatus.text = "New High Score! 🎉"
                setStatusColor(colorGreen)
                Toast.makeText(this, "New high score: $score levels!", Toast.LENGTH_SHORT).show()

                FirebaseAuth.getInstance().currentUser?.uid?.let { uid ->
                    FirebaseManager.savePersonalBest(uid, score) { /* fire and forget */ }
                }
            }
            timedOut -> {
                binding.tvStatus.text = "Time's Up!"
                setStatusColor(colorRed)
                Toast.makeText(this, "Ran out of time on Level $currentLevel.", Toast.LENGTH_SHORT).show()
            }
            else -> {
                binding.tvStatus.text = "Pattern Broken!"
                setStatusColor(colorRed)
                Toast.makeText(this, "Nice try! Level $currentLevel reached.", Toast.LENGTH_SHORT).show()
            }
        }

        timedOut = false
        resetTileColors()

        binding.btnStart.text = "Try Again"
        binding.btnStart.visibility = View.VISIBLE
    }

    // ── Timer ─────────────────────────────────────────────────────────────────

    /**
     * Starts a countdown for the current level.
     * Time budget = (3 + currentLevel) seconds — grows with pattern length.
     * Colour: green while time is comfortable, red at 3 s or below.
     */
    private fun startTimer() {
        stopTimer() // cancel any leftover timer first

        val totalMs = (3 + currentLevel) * 1000L

        countDownTimer = object : CountDownTimer(totalMs, 1000) {

            override fun onTick(millisUntilFinished: Long) {
                val secondsLeft = millisUntilFinished / 1000 + 1
                binding.tvTimer.text = "⏱ $secondsLeft s"
                binding.tvTimer.setTextColor(if (secondsLeft <= 3) colorRed else colorGreen)
            }

            override fun onFinish() {
                // Block tile input immediately before any async work
                isGameOver = true
                binding.tvTimer.text = "⏱ 0 s"
                binding.tvTimer.setTextColor(colorRed)
                timedOut = true
                gameOver()
            }
        }.start()
    }

    /** Cancels the active timer. Timer text stays visible between levels. */
    private fun stopTimer() {
        countDownTimer?.cancel()
        countDownTimer = null
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun setTilesEnabled(enabled: Boolean) {
        tiles.forEach { it.isEnabled = enabled }
    }

    private fun resetTileColors() {
        tiles.forEach { setTileColor(it, colorTileDefault) }
    }

    private fun setStatusColor(color: Int) {
        binding.tvStatus.setTextColor(color)
    }

    /**
     * Mutates the tile's GradientDrawable color to preserve rounded corners.
     * Using setBackgroundColor() would replace the drawable entirely and
     * lose the corner radius defined in tile_background.xml.
     */
    private fun setTileColor(tile: View, color: Int) {
        (tile.background as? GradientDrawable)?.setColor(color)
    }

    private fun updateHighScoreDisplay() {
        binding.tvHighScore.text = "High Score: ${prefs.getInt(KEY_HIGH_SCORE, 0)}"
    }

    /**
     * Loads the high score from Firestore and syncs it into SharedPreferences.
     * Shows the locally cached value immediately (instant, works offline),
     * then updates the display if Firestore has a higher value.
     */
    private fun loadHighScoreFromFirestore() {
        updateHighScoreDisplay() // show cached value immediately

        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return

        FirebaseManager.loadPersonalBest(uid) { firestoreScore ->
            val localScore = prefs.getInt(KEY_HIGH_SCORE, 0)
            if (firestoreScore > localScore) {
                prefs.edit().putInt(KEY_HIGH_SCORE, firestoreScore).apply()
                updateHighScoreDisplay()
            }
        }
    }
}
