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

    // True during game over state (timeout or wrong tap).
    // Blocks all tile input until the player starts a new game.
    private var isGameOver = false

    // Active countdown timer — replaced each level, cancelled on game over
    private var countDownTimer: CountDownTimer? = null

    // True when the timer expired naturally; used to show "Time's Up!"
    // instead of "Pattern Broken!" in gameOver()
    private var timedOut = false

    // ── Shared preferences (high score) ──────────────────────────────────────
    private val prefs by lazy { getSharedPreferences("pattern_game_prefs", Context.MODE_PRIVATE) }
    private val KEY_HIGH_SCORE = "high_score"

    // ── Tile colors ───────────────────────────────────────────────────────────
    // Applied to the tile GradientDrawable via setTileColor()
    private val colorTileDefault   = Color.parseColor("#37474F") // idle — dark slate
    private val colorTileHighlight = Color.parseColor("#FFD600") // pattern flash — amber
    private val colorTileCorrect   = Color.parseColor("#00C853") // correct tap — green
    private val colorTileWrong     = Color.parseColor("#FF1744") // wrong tap — red

    // ── UI text colors ────────────────────────────────────────────────────────
    // Applied to tvStatus and tvTimer to reflect the current game state
    private val colorYellow  = Color.parseColor("#FFD600") // watching / paused
    private val colorGreen   = Color.parseColor("#00C853") // player's turn / success
    private val colorRed     = Color.parseColor("#FF1744") // urgent / loss
    private val colorNeutral = Color.parseColor("#90A4AE") // idle (matches text_secondary)

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

        // Wire each tile to the input handler
        tiles.forEachIndexed { index, view ->
            view.setOnClickListener { onTileTapped(index) }
        }

        // Start / Try Again — runs the READY countdown then begins the game
        binding.btnStart.setOnClickListener {
            lifecycleScope.launch { startWithCountdown() }
        }

        // Initial UI state
        setTilesEnabled(false)
        binding.tvStatus.text = "Press Start to Begin!"
        binding.tvLevel.text = "Level 1"

        // Load high score: Firestore is the source of truth.
        // We sync it into SharedPreferences so it's available instantly
        // on future launches even without a network connection.
        loadHighScoreFromFirestore()
    }

    // ── Countdown ─────────────────────────────────────────────────────────────

    /**
     * Shows a full-screen READY / 3 / 2 / 1 overlay, then starts the game.
     * The button is hidden for the entire duration of gameplay.
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

        // Show the time budget upfront so the player knows what to expect
        val totalSeconds = 3 + currentLevel
        binding.tvTimer.text = "⏱ $totalSeconds s"
        binding.tvTimer.setTextColor(colorYellow)

        // Grow the pattern by one random tile each level
        pattern.add((0 until 9).random())

        lifecycleScope.launch {
            delay(600)
            showPattern()
        }
    }

    /**
     * Flash timing per level — both values shrink by 30 ms each level,
     * floored at 150 ms so the game stays readable at high levels.
     *
     * Level 1 → highlight 500 ms, gap 300 ms
     * Level 6 → highlight 350 ms, gap 150 ms  (floor reached)
     */
    private fun highlightDuration(): Long = maxOf(500L - (currentLevel - 1) * 30L, 150L)
    private fun gapDuration(): Long       = maxOf(300L - (currentLevel - 1) * 30L, 150L)

    /**
     * Flashes each tile in the pattern one by one using coroutine delays,
     * then hands control to the player and starts the countdown timer.
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
        binding.tvTimer.setTextColor(colorGreen) // onTick will switch to red at ≤ 3 s
        startTimer()
    }

    // ── Player input ──────────────────────────────────────────────────────────

    private fun onTileTapped(index: Int) {
        // Ignore taps during pattern display, game over, or countdown
        if (isShowingPattern || isGameOver) return

        playerInput.add(index)
        val step = playerInput.size - 1

        if (playerInput[step] == pattern[step]) {
            // Correct tap
            flashTile(index, colorTileCorrect)

            if (playerInput.size == pattern.size) {
                // Full pattern completed — advance to next level
                stopTimer()
                setTilesEnabled(false)
                lifecycleScope.launch {
                    delay(800)
                    currentLevel++
                    nextLevel()
                }
            }
        } else {
            // Wrong tap — block input immediately then end the game
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

    /**
     * Briefly flashes a tile to [color] then returns it to the default color.
     */
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

        // Freeze the timer in red — loss state always shows red
        binding.tvTimer.setTextColor(colorRed)

        val score = currentLevel - 1
        val previousBest = prefs.getInt(KEY_HIGH_SCORE, 0)

        if (score > previousBest) {
            // Save locally first so the display updates immediately
            prefs.edit().putInt(KEY_HIGH_SCORE, score).apply()
            updateHighScoreDisplay()
            binding.tvStatus.text = "New High Score! 🎉"
            setStatusColor(colorGreen)
            Toast.makeText(this, "New high score: $score levels!", Toast.LENGTH_SHORT).show()

            // Also save to Firestore so the score persists across devices/logins
            val userId = FirebaseAuth.getInstance().currentUser?.uid
            if (userId != null) {
                FirebaseManager.savePersonalBest(userId, score) { saved ->
                    if (saved) {
                        // Firestore confirmed the save — nothing extra needed
                    }
                }
            }
        } else if (timedOut) {
            binding.tvStatus.text = "Time's Up!"
            setStatusColor(colorRed)
            Toast.makeText(this, "Ran out of time on Level $currentLevel.", Toast.LENGTH_SHORT).show()
        } else {
            binding.tvStatus.text = "Pattern Broken!"
            setStatusColor(colorRed)
            Toast.makeText(this, "Nice try! Level $currentLevel reached.", Toast.LENGTH_SHORT).show()
        }

        timedOut = false
        resetTileColors()

        // Show the button with "Try Again" label
        binding.btnStart.text = "Try Again"
        binding.btnStart.visibility = View.VISIBLE
    }

    // ── Timer ─────────────────────────────────────────────────────────────────

    /**
     * Starts a fresh countdown for the current level.
     * Time budget = (3 + currentLevel) seconds, so it grows with pattern length.
     */
    private fun startTimer() {
        stopTimer() // cancel any leftover timer before creating a new one

        val totalMs = (3 + currentLevel) * 1000L

        countDownTimer = object : CountDownTimer(totalMs, 1000) {

            override fun onTick(millisUntilFinished: Long) {
                val secondsLeft = millisUntilFinished / 1000 + 1
                binding.tvTimer.text = "⏱ $secondsLeft s"
                // Green while comfortable, switches to red at 3 s or below
                binding.tvTimer.setTextColor(if (secondsLeft <= 3) colorRed else colorGreen)
            }

            override fun onFinish() {
                isGameOver = true          // block tile input immediately
                binding.tvTimer.text = "⏱ 0 s"
                binding.tvTimer.setTextColor(colorRed)
                timedOut = true
                gameOver()
            }
        }.start()
    }

    /**
     * Cancels the active timer without clearing the display.
     * The timer text stays visible between levels.
     */
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

    /** Updates tvStatus text color to reflect the current game state. */
    private fun setStatusColor(color: Int) {
        binding.tvStatus.setTextColor(color)
    }

    /**
     * Changes a tile's fill color while preserving its rounded corners.
     * Calling setBackgroundColor() would replace the GradientDrawable entirely
     * and lose the corner radius, so we mutate the drawable's color instead.
     */
    private fun setTileColor(tile: View, color: Int) {
        (tile.background as? GradientDrawable)?.setColor(color)
    }

    /** Reads the saved high score and updates the display label. */
    private fun updateHighScoreDisplay() {
        binding.tvHighScore.text = "High Score: ${prefs.getInt(KEY_HIGH_SCORE, 0)}"
    }

    /**
     * Loads the player's personal best from Firestore and syncs it into
     * SharedPreferences. Shows the local value immediately, then updates
     * the display once Firestore responds.
     *
     * Strategy:
     *  1. Show the locally cached score right away (instant, works offline)
     *  2. Fetch from Firestore in the background
     *  3. If Firestore has a higher value, update local cache and display
     */
    private fun loadHighScoreFromFirestore() {
        // Step 1 — show cached value immediately so the screen isn't blank
        updateHighScoreDisplay()

        // Step 2 — fetch from Firestore
        val userId = FirebaseAuth.getInstance().currentUser?.uid ?: return

        FirebaseManager.loadPersonalBest(userId) { firestoreScore ->
            val localScore = prefs.getInt(KEY_HIGH_SCORE, 0)

            if (firestoreScore > localScore) {
                // Step 3 — Firestore has a better score (e.g. from another device)
                // Update local cache and refresh the display
                prefs.edit().putInt(KEY_HIGH_SCORE, firestoreScore).apply()
                updateHighScoreDisplay()
            }
        }
    }
}
