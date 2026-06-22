package com.example.patternmemorygame

import android.content.Intent
import android.os.Bundle
import android.view.View
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

        // If the user is already logged in, load their profile then go to Welcome
        if (auth.currentUser != null) {
            val uid = auth.currentUser!!.uid
            FirebaseManager.loadProfile(uid) { _, _ ->
                // Profile loaded (or failed gracefully) — proceed either way
                goToWelcome()
            }
            return
        }

        // Login button — validate fields then sign in with Firebase
        binding.btnLogin.setOnClickListener {
            val email    = binding.etEmail.text.toString().trim()
            val password = binding.etPassword.text.toString().trim()

            // Basic validation — check for empty fields
            if (email.isEmpty() || password.isEmpty()) {
                showError("Please enter your email and password.")
                return@setOnClickListener
            }

            // Disable button while request is in progress
            binding.btnLogin.isEnabled = false
            hideError()

            auth.signInWithEmailAndPassword(email, password)
                .addOnSuccessListener { result ->
                    val uid = result.user?.uid

                    if (uid == null) {
                        // Should never happen, but handle defensively
                        goToWelcome()
                        return@addOnSuccessListener
                    }

                    // Login succeeded — load the user's profile from Firestore
                    // before navigating so currentUsername is ready for the session.
                    // If the profile doesn't exist or the load fails, we still
                    // proceed to Welcome — the app never blocks on this.
                    FirebaseManager.loadProfile(uid) { username, _ ->
                        if (username.isEmpty()) {
                            // Profile missing or load failed — continue gracefully
                            // currentUsername will be an empty string for this session
                        }
                        goToWelcome()
                    }
                }
                .addOnFailureListener { exception ->
                    // Login failed — show the error message
                    binding.btnLogin.isEnabled = true
                    showError(getFriendlyError(exception.message))
                }
        }

        // "Don't have an account?" — open Register screen
        binding.tvGoToRegister.setOnClickListener {
            startActivity(Intent(this, RegisterActivity::class.java))
        }
    }

    // Navigate to Welcome screen and close Login so Back doesn't return here
    private fun goToWelcome() {
        startActivity(Intent(this, WelcomeActivity::class.java))
        finish()
    }

    private fun showError(message: String) {
        binding.tvError.text = message
        binding.tvError.visibility = View.VISIBLE
    }

    private fun hideError() {
        binding.tvError.text = ""
        binding.tvError.visibility = View.GONE
    }

    // Converts Firebase error messages into simple readable text
    private fun getFriendlyError(message: String?): String {
        return when {
            message == null                          -> "Login failed. Please try again."
            message.contains("password")             -> "Incorrect password. Please try again."
            message.contains("no user record")       -> "No account found with this email."
            message.contains("badly formatted")      -> "Please enter a valid email address."
            message.contains("network")              -> "No internet connection. Please try again."
            else                                     -> "Login failed. Please check your details."
        }
    }
}
