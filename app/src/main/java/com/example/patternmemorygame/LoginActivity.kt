package com.example.patternmemorygame

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.example.patternmemorygame.databinding.ActivityLoginBinding
import com.google.firebase.auth.FirebaseAuth

class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private lateinit var auth: FirebaseAuth

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        auth = FirebaseAuth.getInstance()

        // If the user is already logged in, load their profile and skip to Welcome
        if (auth.currentUser != null) {
            loadProfileThenNavigate(auth.currentUser!!.uid)
            return
        }

        binding.btnLogin.setOnClickListener {
            val email    = binding.etEmail.text.toString().trim()
            val password = binding.etPassword.text.toString().trim()

            if (email.isEmpty() || password.isEmpty()) {
                showError(binding.tvError, "Please enter your email and password.")
                return@setOnClickListener
            }

            binding.btnLogin.isEnabled = false
            hideError(binding.tvError)

            auth.signInWithEmailAndPassword(email, password)
                .addOnSuccessListener { result ->
                    val uid = result.user?.uid
                    if (uid == null) {
                        // Auth succeeded but UID missing — navigate anyway
                        goToWelcome()
                        return@addOnSuccessListener
                    }
                    // Load the user's profile so username is available for the session
                    loadProfileThenNavigate(uid)
                }
                .addOnFailureListener { exception ->
                    binding.btnLogin.isEnabled = true
                    showError(binding.tvError, getFriendlyError(exception.message))
                }
        }

        binding.tvGoToRegister.setOnClickListener {
            startActivity(Intent(this, RegisterActivity::class.java))
        }
    }

    /**
     * Loads the user's profile from Firestore, then navigates to Welcome.
     * Navigation happens regardless of whether the profile load succeeds —
     * the app should never block on a missing profile document.
     */
    private fun loadProfileThenNavigate(uid: String) {
        FirebaseManager.loadProfile(uid) { _, _ ->
            goToWelcome()
        }
    }

    /** Navigates to Welcome and closes Login so Back doesn't return here. */
    private fun goToWelcome() {
        startActivity(Intent(this, WelcomeActivity::class.java))
        finish()
    }

    /** Maps Firebase error messages to simple user-facing text. */
    private fun getFriendlyError(message: String?): String = when {
        message == null                     -> "Login failed. Please try again."
        message.contains("password")        -> "Incorrect password. Please try again."
        message.contains("no user record")  -> "No account found with this email."
        message.contains("badly formatted") -> "Please enter a valid email address."
        message.contains("network")         -> "No internet connection. Please try again."
        else                                -> "Login failed. Please check your details."
    }
}
