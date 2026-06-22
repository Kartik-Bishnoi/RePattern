package com.example.patternmemorygame

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.example.patternmemorygame.databinding.ActivityWelcomeBinding
import com.google.firebase.auth.FirebaseAuth

class WelcomeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityWelcomeBinding
    private lateinit var auth: FirebaseAuth

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWelcomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        auth = FirebaseAuth.getInstance()

        // Show the logged-in user's username.
        // FirebaseManager.currentUsername is populated by loadProfile() in LoginActivity.
        // Fall back to "Player" if the profile wasn't loaded or username is empty.
        val displayName = FirebaseManager.currentUsername
            .takeIf { it.isNotEmpty() } ?: "Player"
        binding.tvWelcomeUser.text = "Welcome, $displayName"

        // Play button — open the game screen
        binding.btnPlay.setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java))
        }

        // Leaderboard button — open the leaderboard screen
        binding.btnLeaderboard.setOnClickListener {
            startActivity(Intent(this, LeaderboardActivity::class.java))
        }

        // Logout button — clear local cache and session, sign out, return to Login
        binding.tvLogout.setOnClickListener {
            // Reset the locally cached high score so the next user
            // starts fresh instead of seeing the previous user's score.
            getSharedPreferences("pattern_game_prefs", Context.MODE_PRIVATE)
                .edit()
                .putInt("high_score", 0)
                .apply()

            // Clear the in-memory session data (username, etc.)
            FirebaseManager.clearSession()

            auth.signOut()
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
        }
    }
}
