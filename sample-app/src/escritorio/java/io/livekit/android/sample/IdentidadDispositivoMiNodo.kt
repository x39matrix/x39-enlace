package io.livekit.android.sample

import android.app.KeyguardManager
import android.content.Context
import android.os.SystemClock
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.UUID

internal object IdentidadDispositivoMiNodo {
    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
    private const val KEY_ALIAS = "x39_mi_nodo_operator_ecdsa_v1"
    private const val VENTANA_SESION_MS = 24 * 60 * 60 * 1000L

    @Volatile
    private var ultimoDesbloqueoRealtimeMs: Long = 0L

    fun sesionDesbloqueada(): Boolean {
        val ahora = SystemClock.elapsedRealtime()
        return ultimoDesbloqueoRealtimeMs > 0L && (ahora - ultimoDesbloqueoRealtimeMs) < VENTANA_SESION_MS
    }

    fun registrarDesbloqueoExitoso() {
        ultimoDesbloqueoRealtimeMs = SystemClock.elapsedRealtime()
    }

    fun bloquearSesion() {
        ultimoDesbloqueoRealtimeMs = 0L
    }

    fun dispositivoTienePinOBiometria(context: Context): Boolean {
        val km = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        return km?.isDeviceSecure == true
    }

    @Synchronized
    private fun asegurarParClaves() {
        val ks = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        if (ks.containsAlias(KEY_ALIAS)) return

        val kpg = KeyPairGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_EC,
            KEYSTORE_PROVIDER
        )
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
        )
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .build()
        kpg.initialize(spec)
        kpg.generateKeyPair()
    }

    fun clavePublicaBase64(): String {
        asegurarParClaves()
        val ks = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        val pubKey = ks.getCertificate(KEY_ALIAS).publicKey.encoded
        return Base64.encodeToString(pubKey, Base64.NO_WRAP)
    }

    fun deviceId(): String {
        asegurarParClaves()
        val ks = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        val pubKey = ks.getCertificate(KEY_ALIAS).publicKey.encoded
        val digest = MessageDigest.getInstance("SHA-256").digest(pubKey)
        return digest.take(16).joinToString("") { "%02x".format(it) }
    }

    fun cabecerasFirmadas(ruta: String): Map<String, String> {
        asegurarParClaves()
        val timestamp = (System.currentTimeMillis() / 1000L).toString()
        val nonce = UUID.randomUUID().toString().replace("-", "").take(16)
        val payload = "GET:$ruta:$timestamp:$nonce"

        val ks = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        val entry = ks.getEntry(KEY_ALIAS, null) as KeyStore.PrivateKeyEntry
        val sig = Signature.getInstance("SHA256withECDSA").apply {
            initSign(entry.privateKey)
            update(payload.toByteArray(Charsets.UTF_8))
        }.sign()

        val firmaB64 = Base64.encodeToString(sig, Base64.NO_WRAP)
        return mapOf(
            "X-X39-Device-Id" to deviceId(),
            "X-X39-Timestamp" to timestamp,
            "X-X39-Nonce" to nonce,
            "X-X39-Signature" to firmaB64,
            "X-X39-Public-Key" to clavePublicaBase64()
        )
    }
}
