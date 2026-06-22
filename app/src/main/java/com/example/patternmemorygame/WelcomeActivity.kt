package com.example.patternmemorygame

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.example.patternmemorygame.AppConstants.KEY_HIGH_SCORE
import com.example.patternmemorygame.AppConstants.PREFS_NAME
import com.example.patternmemorygame.databinding.ActivityWelcomeBinding

class WelcomeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityWelcomeBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWelcomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Show the logged-in username from the session loaded during login.
        // Falls back to "Player" if the profile was unavailable.
        val displayName = FirebaseManager.currentUsername.ifEmpty { "Player" }
        binding.tvWelcomeUser.text = "Welcome, $displayName"

        binding.btnPlay.setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java))
        }

        binding.btnLeaderboard.setOnClickListener {
            startActivity(Intent(this, LeaderboardActivity::class.java))
        }

        binding.tvLogout.setOnClickListener {
            // Clear locally cached high score so the next user starts fresh
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putInt(KEY_HIGH_SCORE, 0)
                .apply()

            // Clear in-memory session data (username, etc.)
            FirebaseManager.clearSession()
            FirebaseManager.signOut()

            startActivity(Intent(this, LoginActivity::class.java))
            finish()
        }
    }
}
