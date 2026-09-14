package com.rob.speseoffline

import android.content.Context
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

class SecurityManager(context: Context) {
    private val prefs = context.getSharedPreferences("security", Context.MODE_PRIVATE)

    var biometricEnabled: Boolean
        get() = prefs.getBoolean("biometric_enabled", false)
        set(value) = prefs.edit().putBoolean("biometric_enabled", value).apply()

    fun hasPin(): Boolean = prefs.contains("pin_hash")

    fun setPin(pin: String) {
        require(pin.length in 4..12 && pin.all(Char::isDigit))
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val hash = derive(pin, salt)
        prefs.edit()
            .putString("pin_salt", Base64.getEncoder().encodeToString(salt))
            .putString("pin_hash", Base64.getEncoder().encodeToString(hash))
            .apply()
    }

    fun verifyPin(pin: String): Boolean {
        val saltText = prefs.getString("pin_salt", null) ?: return false
        val hashText = prefs.getString("pin_hash", null) ?: return false
        val salt = Base64.getDecoder().decode(saltText)
        val expected = Base64.getDecoder().decode(hashText)
        val actual = derive(pin, salt)
        if (actual.size != expected.size) return false
        var diff = 0
        for (i in actual.indices) diff = diff or (actual[i].toInt() xor expected[i].toInt())
        return diff == 0
    }

    fun clearPin() {
        prefs.edit().remove("pin_salt").remove("pin_hash").putBoolean("biometric_enabled", false).apply()
    }

    private fun derive(pin: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, 120_000, 256)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    }
}

class UiPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("ui", Context.MODE_PRIVATE)
    var darkMode: Boolean
        get() = prefs.getBoolean("dark_mode", false)
        set(value) = prefs.edit().putBoolean("dark_mode", value).apply()
}
