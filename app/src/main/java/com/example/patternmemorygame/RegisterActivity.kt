package com.example.patternmemorygame

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
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

            // Validate all fields
            when {
                username.isEmpty() || email.isEmpty() ||
                password.isEmpty() || confirmPassword.isEmpty() -> {
                    showError("Please fill in all fields.")
                    return@setOnClickListener
                }
                username.length < 3 -> {
                    showError("Username must be at least 3 characters.")
                    return@setOnClickListener
                }
                password.length < 6 -> {
                    showError("Password must be at least 6 characters.")
                    return@setOnClickListener
                }
                password != confirmPassword -> {
                    showError("Passwords do not match.")
                    return@setOnClickListener
                }
            }

            binding.btnRegister.isEnabled = false
            hideError()

            Log.d(TAG, "Attempting Firebase Auth registration for email: $email")

            // Step 1 — create the Firebase Auth account
            auth.createUserWithEmailAndPassword(email, password)
                .addOnSuccessListener { result ->
                    val uid = result.user?.uid

                    if (uid == null) {
                        Log.e(TAG, "Registration successful but UID was null — cannot create profile")
                        startActivity(Intent(this, WelcomeActivity::class.java))
                        finish()
                        return@addOnSuccessListener
                    }

                    Log.d(TAG, "Registration successful — UID: $uid")
                    Log.d(TAG, "Creating profile for UID: $uid with username: $username")

                    // Step 2 — write the profile to the "profiles" Firestore collection
                    FirebaseManager.createProfile(uid, username, email) { success ->
                        if (success) {
                            Log.d(TAG, "Profile saved successfully for UID: $uid")
                        } else {
                            Log.e(TAG, "Profile save FAILED for UID: $uid — check Firestore security rules")
                        }
                        // Proceed to Welcome screen regardless — Auth account exists
                        startActivity(Intent(this, WelcomeActivity::class.java))
                        finish()
                    }
                }
                .addOnFailureListener { exception ->
                    Log.e(TAG, "Firebase Auth registration failed: ${exception.message}", exception)
                    binding.btnRegister.isEnabled = true
                    showError(getFriendlyError(exception.message))
                }
        }

        binding.tvGoToLogin.setOnClickListener {
            finish()
        }
    }

    private fun showError(message: String) {
        binding.tvError.text = message
        binding.tvError.visibility = View.VISIBLE
    }

    private fun hideError() {
        binding.tvError.text = ""
        binding.tvError.visibility = View.GONE
    }

    private fun getFriendlyError(message: String?): String {
        return when {
            message == null                                      -> "Registration failed. Please try again."
            message.contains("email address is already in use") -> "An account with this email already exists."
            message.contains("badly formatted")                 -> "Please enter a valid email address."
            message.contains("network")                         -> "No internet connection. Please try again."
            else                                                -> "Registration failed. Please try again."
        }
    }
}
