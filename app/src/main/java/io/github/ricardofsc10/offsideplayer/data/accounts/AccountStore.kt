package io.github.ricardofsc10.offsideplayer.data.accounts

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.ricardofsc10.offsideplayer.data.xtream.XtreamCredentials
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

private val Context.credentialsDataStore by preferencesDataStore(name = "credentials")

/**
 * Guarda a lista de contas em DataStore, encriptada com uma chave que nunca sai do Android
 * Keystore. Na primeira leitura, converte a conta única da versão anterior.
 */
class AccountStore(context: Context) {
    private val dataStore = context.applicationContext.credentialsDataStore
    private val json = Json { ignoreUnknownKeys = true }

    /** [legacyEpgUrl] dá o endereço do guia que as versões anteriores guardavam nas definições. */
    suspend fun load(legacyEpgUrl: suspend () -> String): AccountList {
        val prefs = dataStore.data.first()
        prefs[ACCOUNTS]?.let { stored ->
            return decryptOrNull(stored)?.let { decodeOrNull<AccountList>(it) } ?: AccountList()
        }
        val legacy = prefs[LEGACY]?.let(::decryptOrNull)?.let { decodeOrNull<LegacyCredentials>(it) }
            ?: return AccountList()
        val migrated = Accounts.fromLegacy(
            XtreamCredentials(legacy.server, legacy.username, legacy.password),
            legacyEpgUrl(),
        )
        save(migrated)
        return migrated
    }

    suspend fun save(list: AccountList) {
        val encrypted = encrypt(json.encodeToString(AccountList.serializer(), list))
        dataStore.edit {
            it[ACCOUNTS] = encrypted
            it.remove(LEGACY)
        }
    }

    private fun decryptOrNull(stored: String): String? =
        try {
            decrypt(stored)
        } catch (_: GeneralSecurityException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }

    private inline fun <reified T> decodeOrNull(text: String): T? =
        try {
            json.decodeFromString<T>(text)
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val output = cipher.iv + cipher.doFinal(plain.toByteArray())
        return Base64.encodeToString(output, Base64.NO_WRAP)
    }

    private fun decrypt(stored: String): String {
        val input = Base64.decode(stored, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, input, 0, IV_BYTES))
        return String(cipher.doFinal(input, IV_BYTES, input.size - IV_BYTES))
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
            .apply { init(spec) }
            .generateKey()
    }

    /** O formato da versão com uma só conta. */
    @Serializable
    private class LegacyCredentials(val server: String, val username: String, val password: String)

    private companion object {
        val ACCOUNTS = stringPreferencesKey("accounts")
        val LEGACY = stringPreferencesKey("encrypted")
        const val KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "offside_credentials"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}
