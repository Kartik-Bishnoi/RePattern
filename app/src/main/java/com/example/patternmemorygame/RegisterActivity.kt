package com.example.patternmemorygame

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import com.example.patternmemorygame.databinding.ActivityRegisterBinding
import com.google.firebase.auth.FirebaseAuth

class RegisterActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRegisterBinding
    private lateinit var auth: FirebaseAuth

    companion object {
        private const val TAG = "RegisterActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRegisterBinding.inflate(layoutInflater)
        setContentView(binding.root)

        auth = FirebaseAuth.getInstance()

        binding.btnRegister.setOnClickListener {
            val username        = binding.etUsername.text.toString().trim()
            val email           = binding.etEmail.text.toString().trim()
            val password        = binding.etPassword.text.toString().trim()
            val confirmPassword = binding.etConfirmPassword.text.toString().trim()

            // Validate all fields before attempting registration
            when {
                username.isEmpty() || email.isEmpty() ||
                password.isEmpty() || confirmPassword.isEmpty() -> {
                    showError(binding.tvError, "Please fill in all fields.")
                    return@setOnClickListener
                }
                username.length < 3 -> {
                    showError(binding.tvError, "Username must be at least 3 characters.")
                    return@setOnClickListener
                }
                password.length < 6 -> {
                    showError(binding.tvError, "Password must be at least 6 characters.")
                    return@setOnClickListener
                }
                password != confirmPassword -> {
                    showError(binding.tvError, "Passwords do not match.")
                    return@setOnClickListener
                }
            }

            binding.btnRegister.isEnabled = false
            hideError(binding.tvError)

            Log.d(TAG, "Attempting registration for: $email")

            // Step 1 — create the Firebase Auth account
            auth.createUserWithEmailAndPassword(email, password)
                .addOnSuccessListener { result ->
                    val uid = result.user?.uid
                    if (uid == null) {
                        Log.e(TAG, "Registration succeeded but UID was null")
                        goToWelcome()
                        return@addOnSuccessListener
                    }

                    Log.d(TAG, "Registration successful — UID: $uid")
                    Log.d(TAG, "Creating profile for UID: $uid")

                    // Step 2 — create the user's profile document in Firestore
                    FirebaseManager.createProfile(uid, username, email) { success ->
                        if (success) {
                            Log.d(TAG, "Profile saved successfully for UID: $uid")
                        } else {
                            Log.e(TAG, "Profile save failed for UID: $uid — check Firestore rules")
                        }
                        // Always proceed to Welcome — Auth account exists regardless
                        goToWelcome()
                    }
                }
                .addOnFailureListener { exception ->
                    Log.e(TAG, "Registration failed: ${exception.message}", exception)
                    binding.btnRegister.isEnabled = true
                    showError(binding.tvError, getFriendlyError(exception.message))
                }
        }

        // "Already have an account?" — pop back to LoginActivity
        binding.tvGoToLogin.setOnClickListener { finish() }
    }

    private fun goToWelcome() {
        startActivity(Intent(this, WelcomeActivity::class.java))
        finish()
    }

    /** Maps Firebase error messages to simple user-facing text. */
    private fun getFriendlyError(message: String?): String = when {
        message == null                                      -> "Registration failed. Please try again."
        message.contains("email address is already in use") -> "An account with this email already exists."
        message.contains("badly formatted")                 -> "Please enter a valid email address."
        message.contains("network")                         -> "No internet connection. Please try again."
        else                                                -> "Registration failed. Please try again."
    }
}
