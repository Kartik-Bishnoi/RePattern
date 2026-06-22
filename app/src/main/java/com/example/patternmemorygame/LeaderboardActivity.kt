package com.example.patternmemorygame

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.patternmemorygame.databinding.ActivityLeaderboardBinding

// ── Data model ────────────────────────────────────────────────────────────────

/** Represents one entry in the leaderboard list. */
data class LeaderboardEntry(
    val rank: Int,
    val username: String,
    val score: Int
)

// ── RecyclerView Adapter ──────────────────────────────────────────────────────

/**
 * Binds a list of LeaderboardEntry items to the RecyclerView.
 * Each item maps to item_leaderboard.xml.
 */
class LeaderboardAdapter(
    private val entries: List<LeaderboardEntry>
) : RecyclerView.Adapter<LeaderboardAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvRank: TextView     = view.findViewById(R.id.tvRank)
        val tvUsername: TextView = view.findViewById(R.id.tvUsername)
        val tvScore: TextView    = view.findViewById(R.id.tvScore)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_leaderboard, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val entry = entries[position]
        holder.tvRank.text     = "#${entry.rank}"
        holder.tvUsername.text = entry.username
        holder.tvScore.text    = entry.score.toString()
    }

    override fun getItemCount(): Int = entries.size
}

// ── Activity ──────────────────────────────────────────────────────────────────

class LeaderboardActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLeaderboardBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLeaderboardBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Back button — returns to the Welcome screen
        binding.tvBack.setOnClickListener {
            finish()
        }

        // Set up the RecyclerView layout manager upfront
        binding.recyclerLeaderboard.layoutManager = LinearLayoutManager(this)

        // Load leaderboard data from Firestore every time the screen opens
        loadLeaderboard()
    }

    /**
     * Fetches the top 10 scores from Firestore and displays them.
     *
     * States:
     *   Loading  → show spinner, hide list and empty message
     *   Success  → hide spinner, show list (or empty message if no data)
     *   Failure  → hide spinner, show empty message
     */
    private fun loadLeaderboard() {
        // Show spinner while loading
        binding.progressBar.visibility          = View.VISIBLE
        binding.recyclerLeaderboard.visibility  = View.GONE
        binding.tvEmpty.visibility              = View.GONE

        FirebaseManager.fetchLeaderboard { entries ->
            // Hide spinner — Firestore call is complete
            binding.progressBar.visibility = View.GONE

            if (entries.isEmpty()) {
                // No scores in Firestore — show the empty state message
                binding.tvEmpty.visibility = View.VISIBLE
            } else {
                // Scores found — populate the RecyclerView
                binding.recyclerLeaderboard.visibility = View.VISIBLE
                binding.recyclerLeaderboard.adapter = LeaderboardAdapter(entries)
            }
        }
    }
}
