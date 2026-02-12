package com.tsubuzaki.circlesgo.auth

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.tsubuzaki.circlesgo.api.auth.OpenIDToken
import kotlinx.serialization.json.Json

class TokenStore(context: Context) {

    companion object {
        private const val PREFS_NAME = "com.tsubuzaki.circlesgo.auth"
        private const val KEY_AUTH_TOKEN = "CircleMsAuthToken"
        private const val KEY_TOKEN_EXPIRY = "Auth.TokenExpiryDate"
    }

    private val json = Json { ignoreUnknownKeys = true }

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val encryptedPrefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        PREFS_NAME,
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    private val regularPrefs: SharedPreferences =
        context.getSharedPreferences("circles_prefs", Context.MODE_PRIVATE)

    fun saveToken(token: OpenIDToken) {
        val tokenJson = json.encodeToString(OpenIDToken.serializer(), token)
        encryptedPrefs.edit().putString(KEY_AUTH_TOKEN, tokenJson).apply()
    }

    fun getToken(): OpenIDToken? {
        val tokenJson = encryptedPrefs.getString(KEY_AUTH_TOKEN, null) ?: return null
        return try {
            json.decodeFromString(OpenIDToken.serializer(), tokenJson)
        } catch (e: Exception) {
            null
        }
    }

    fun clearToken() {
        encryptedPrefs.edit().remove(KEY_AUTH_TOKEN).apply()
    }

    fun saveTokenExpiryDate(expiryMillis: Long) {
        regularPrefs.edit().putLong(KEY_TOKEN_EXPIRY, expiryMillis).apply()
    }

    fun getTokenExpiryDate(): Long {
        return regularPrefs.getLong(KEY_TOKEN_EXPIRY, Long.MAX_VALUE)
    }

    fun clearTokenExpiryDate() {
        regularPrefs.edit().remove(KEY_TOKEN_EXPIRY).apply()
    }

    fun clearAll() {
        clearToken()
        clearTokenExpiryDate()
    }
}
