package com.example.patternmemorygame

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.SetOptions
import android.util.Log

/**
 * FirebaseManager
 *
 * Central helper for Firebase Authentication and Firestore.
 * All functions use callbacks (lambdas) so they are easy to use
 * from any Activity without needing coroutines or complex setup.
 */
object FirebaseManager {

    private val auth: FirebaseAuth = FirebaseAuth.getInstance()
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance()

    private const val SCORES_COLLECTION = "scores"

    // ── Session data ──────────────────────────────────────────────────────────
    // Holds the current user's username for the duration of the session.
    // Set after a successful login or registration.
    // Cleared on logout so the next user starts with a clean state.
    var currentUsername: String = ""
        private set

    /** Clears all session-level user data. Called on logout. */
    fun clearSession() {
        currentUsername = ""
    }

    // ── Authentication ────────────────────────────────────────────────────────

    /** Returns the currently signed-in user, or null if nobody is signed in. */
    val currentUser: FirebaseUser?
        get() = auth.currentUser

    /** Signs the current user out. */
    fun signOut() {
        auth.signOut()
    }

    // ── User profiles — Firestore ─────────────────────────────────────────────

    /**
     * Loads the user's profile from Firestore after login.
     *
     * Flow:
     *   1. Read the document at profiles/{uid}
     *   2. Extract "username" and "email" fields
     *   3. Store username in currentUsername for the session
     *   4. Return both values via onResult callback
     *
     * Handles gracefully if:
     *   - The document does not exist (new user or profile creation failed)
     *   - The network is unavailable
     *   - Any Firestore exception occurs
     * In all failure cases, currentUsername is set to an empty string
     * and the app continues normally without crashing.
     *
     * @param uid      the Firebase UID of the logged-in user
     * @param onResult called with (username, email) — both empty strings on failure
     */
    fun loadProfile(uid: String, onResult: (username: String, email: String) -> Unit) {
        Log.d("FirebaseManager", "Loading profile for UID: $uid")

        db.collection("profiles")
            .document(uid)
            .get()
            .addOnSuccessListener { document ->
                if (document != null && document.exists()) {
                    // Profile document found — extract fields
                    val username = document.getString("username") ?: ""
                    val email    = document.getString("email")    ?: ""

                    // Store username in session so any screen can access it
                    currentUsername = username

                    Log.d("FirebaseManager", "Profile loaded — username: $username")
                    onResult(username, email)
                } else {
                    // Document does not exist — profile was never created
                    // (e.g. user registered before username feature was added)
                    Log.w("FirebaseManager", "No profile document found for UID: $uid")
                    currentUsername = ""
                    onResult("", "")
                }
            }
            .addOnFailureListener { exception ->
                // Network error or permission denied — continue without profile
                Log.e("FirebaseManager", "Failed to load profile for UID: $uid — ${exception.message}")
                currentUsername = ""
                onResult("", "")
            }
    }

    /**
     * Creates a user profile document in the "profiles" collection.
     * Called once after a new account is successfully registered.
     *
     * Firestore path: profiles/{uid}
     * Document fields:
     *   username  : the player's chosen display name
     *   email     : their email address
     *   createdAt : server timestamp
     *
     * @param uid      the Firebase UID of the newly created user
     * @param username the username entered on the Register screen
     * @param email    the email address used to register
     * @param onResult called with true on success, false on failure
     */
    fun createProfile(uid: String, username: String, email: String, onResult: (Boolean) -> Unit) {
        val profile = hashMapOf(
            "username"  to username,
            "email"     to email,
            "createdAt" to FieldValue.serverTimestamp()
        )

        Log.d("FirebaseManager", "Writing profile to: profiles/$uid")

        db.collection("profiles")
            .document(uid)
            .set(profile)
            .addOnSuccessListener {
                Log.d("FirebaseManager", "Profile saved successfully for UID: $uid")
                // Store username in session immediately after creation
                currentUsername = username
                onResult(true)
            }
            .addOnFailureListener { exception ->
                Log.e("FirebaseManager", "Profile save FAILED for UID: $uid — ${exception.message}", exception)
                onResult(false)
            }
    }

    // ── Leaderboard — Firestore ───────────────────────────────────────────────

    /**
     * Fetches the top 10 leaderboard entries by combining the scores and
     * profiles collections.
     *
     * Flow:
     *   1. Read all documents from the "scores" collection
     *   2. Sort by score descending, take top 10
     *   3. For each score entry, read the matching profiles/{uid} document
     *      to get the username
     *   4. Assemble LeaderboardEntry list and return via callback
     *
     * Uses a completion counter so the callback fires only after ALL
     * profile lookups have finished (or failed gracefully).
     *
     * @param onResult called with the assembled list, or empty list on failure
     */
    fun fetchLeaderboard(onResult: (List<LeaderboardEntry>) -> Unit) {
        Log.d("FirebaseManager", "Fetching leaderboard scores...")

        db.collection(SCORES_COLLECTION)
            .get()
            .addOnSuccessListener { scoresSnapshot ->
                // Step 1 — collect and sort all score documents
                val scoreDocs = scoresSnapshot.documents
                    .mapNotNull { doc ->
                        val uid   = doc.id
                        val score = doc.getLong("score")?.toInt() ?: return@mapNotNull null
                        Pair(uid, score)
                    }
                    .sortedByDescending { it.second } // highest score first
                    .take(10)                          // top 10 only

                if (scoreDocs.isEmpty()) {
                    Log.d("FirebaseManager", "No scores found in Firestore")
                    onResult(emptyList())
                    return@addOnSuccessListener
                }

                Log.d("FirebaseManager", "Found ${scoreDocs.size} score entries — fetching usernames...")

                // Step 2 — fetch each user's profile to get their username.
                // We use a mutable list and a completion counter so we know
                // when all async profile reads have finished.
                val results = MutableList<LeaderboardEntry?>(scoreDocs.size) { null }
                var remaining = scoreDocs.size

                scoreDocs.forEachIndexed { index, (uid, score) ->
                    db.collection("profiles")
                        .document(uid)
                        .get()
                        .addOnSuccessListener { profileDoc ->
                            val username = profileDoc.getString("username")
                                ?: "Unknown Player" // profile missing or field absent

                            // Rank is 1-based
                            results[index] = LeaderboardEntry(index + 1, username, score)
                            Log.d("FirebaseManager", "Fetched: #${index + 1} $username — $score")
                        }
                        .addOnFailureListener {
                            // Profile read failed — show "Unknown Player" so the row
                            // still appears rather than silently disappearing
                            results[index] = LeaderboardEntry(index + 1, "Unknown Player", score)
                            Log.w("FirebaseManager", "Profile fetch failed for UID: $uid")
                        }
                        .addOnCompleteListener {
                            // This fires regardless of success or failure
                            remaining--
                            if (remaining == 0) {
                                // All profile lookups done — return the complete list
                                val finalList = results.filterNotNull()
                                onResult(finalList)
                            }
                        }
                }
            }
            .addOnFailureListener { exception ->
                Log.e("FirebaseManager", "Failed to fetch leaderboard: ${exception.message}")
                onResult(emptyList())
            }
    }

    // ── High score — Firestore ────────────────────────────────────────────────

    /**
     * Loads the player's personal best score from Firestore.
     *
     * @param userId   the player's Firebase UID
     * @param onResult called with the saved score (Int), or 0 if none exists
     */
    fun loadPersonalBest(userId: String, onResult: (Int) -> Unit) {
        db.collection(SCORES_COLLECTION)
            .document(userId)
            .get()
            .addOnSuccessListener { document ->
                // getLong returns null if the field doesn't exist yet
                val score = document.getLong("score")?.toInt() ?: 0
                onResult(score)
            }
            .addOnFailureListener {
                // Network error or permission denied — return 0 so the app still works
                onResult(0)
            }
    }

    /**
     * Saves the player's personal best score to Firestore.
     * Only updates the document if [score] is higher than the stored value.
     * Uses a Firestore transaction to prevent race conditions.
     *
     * Document structure:
     * {
     *   "userId"    : "abc123",
     *   "score"     : 7,
     *   "timestamp" : <server time>
     * }
     *
     * @param userId   the player's Firebase UID
     * @param score    the new score to potentially save
     * @param onResult called with true if saved, false if not (lower score or error)
     */
    fun savePersonalBest(userId: String, score: Int, onResult: (Boolean) -> Unit) {
        val docRef = db.collection(SCORES_COLLECTION).document(userId)

        db.runTransaction { transaction ->
            val snapshot = transaction.get(docRef)
            val existingScore = snapshot.getLong("score")?.toInt() ?: 0

            if (score > existingScore) {
                // New score is higher — write it to Firestore
                transaction.set(
                    docRef,
                    hashMapOf(
                        "userId"    to userId,
                        "score"     to score,
                        "timestamp" to FieldValue.serverTimestamp()
                    ),
                    SetOptions.merge() // merge so we don't wipe other fields
                )
                true // signal that we wrote
            } else {
                false // signal that we skipped
            }
        }
            .addOnSuccessListener { wrote -> onResult(wrote == true) }
            .addOnFailureListener { onResult(false) }
    }
}
