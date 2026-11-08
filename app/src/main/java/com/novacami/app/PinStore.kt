package com.novacami.app

import android.content.Context
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * The PIN that protects the stream and the controls. Created randomly on first launch
 * (6 digits), can be changed or re-rolled from the app. Stored privately on the phone.
 */
class PinStore(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("novacami", Context.MODE_PRIVATE)

    var pin: String
        get() = prefs.getString(KEY, null) ?: regenerate()
        set(v) { prefs.edit().putString(KEY, v).apply() }

    fun regenerate(): String {
        val r = SecureRandom()
        val v = (0 until 6).map { r.nextInt(10) }.joinToString("")
        pin = v
        return v
    }

    /** Constant-time comparison so the PIN can't be guessed from response timing. */
    fun check(candidate: String?): Boolean {
        if (candidate == null) return false
        return MessageDigest.isEqual(candidate.toByteArray(), pin.toByteArray())
    }

    companion object {
        private const val KEY = "stream_pin"
        const val MIN_LEN = 6
        const val MAX_LEN = 8
    }
}
