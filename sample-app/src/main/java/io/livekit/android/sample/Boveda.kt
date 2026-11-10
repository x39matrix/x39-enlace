package io.livekit.android.sample

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.x39.bolsillo.Identidad

/**
 * Bóveda de la semilla ML-DSA-87 (tu identidad). La semilla se sella con una clave
 * AES-GCM guardada en el almacén de claves del sistema (Android Keystore, respaldado
 * por hardware/StrongBox si el aparato lo tiene): la clave que abre la semilla no se
 * puede exportar del aparato. En reposo, en disco, solo hay el criptograma.
 *
 * Si el Keystore no está disponible (aparatos muy viejos o emuladores), cae con
 * cuidado a un fichero privado de la app (solo esta app lo lee) para no bloquear el
 * arranque; el disco privado ya es fuerte en un aparato no rooteado. allowBackup=false
 * en el manifest impide que salga en copias.
 */
object Boveda {
    private const val KS = "AndroidKeyStore"
    private const val ALIAS = "x39-semilla"
    private const val SELLADA = "semilla.bin"
    private const val PLANA = "semilla.raw"
    private const val KEM = "kem"
    private const val KEM_PK = 1568
    private const val KEM_SK = 3168

    /** Carga la identidad; si no existe semilla, la crea y la sella. */
    fun identidad(c: Context): Identidad {
        val seed = cargar(c) ?: crearYGuardar(c)
        return Identidad.desdeSemilla(seed)
    }

    private fun crearYGuardar(c: Context): ByteArray {
        val id = Identidad.crear()               // semilla fresca del motor (X39Core)
        guardar(c, id.seed)
        return id.seed
    }

    private fun cargar(c: Context): ByteArray? {
        val sellada = File(c.filesDir, SELLADA)
        if (sellada.exists()) {
            try {
                val b = sellada.readBytes()
                val ivLen = b[0].toInt() and 0xFF
                val iv = b.copyOfRange(1, 1 + ivLen)
                val ct = b.copyOfRange(1 + ivLen, b.size)
                val ci = Cipher.getInstance("AES/GCM/NoPadding")
                ci.init(Cipher.DECRYPT_MODE, claveAes(), GCMParameterSpec(128, iv))
                return ci.doFinal(ct)
            } catch (e: Exception) {
                // no se pudo abrir la sellada: probamos la plana de respaldo
            }
        }
        val plana = File(c.filesDir, PLANA)
        return if (plana.exists()) plana.readBytes() else null
    }

    private fun guardar(c: Context, seed: ByteArray) {
        try {
            val ci = Cipher.getInstance("AES/GCM/NoPadding")
            ci.init(Cipher.ENCRYPT_MODE, claveAes())
            val ct = ci.doFinal(seed)
            val iv = ci.iv
            File(c.filesDir, SELLADA).outputStream().use {
                it.write(iv.size)
                it.write(iv)
                it.write(ct)
            }
            File(c.filesDir, PLANA).delete()
        } catch (e: Exception) {
            // Keystore no disponible: respaldo en fichero privado (no ideal, pero no bloquea)
            File(c.filesDir, PLANA).writeBytes(seed)
        }
    }

    // ---- Claves de cifrar mensajes (ML-KEM-1024) --------------------------------

    /**
     * Tu par ML-KEM-1024, el que permite que te escriban estando tú desconectado.
     * Se crea la primera vez y se sella igual que la semilla: la parte secreta nunca
     * sale de este aparato. Devuelve (pública 1568, secreta 3168).
     *
     * La identidad de FIRMAR (ML-DSA-87) es otra y no cambia: tu huella y tus llamadas
     * siguen siendo las mismas. Esto solo añade la de CIFRAR.
     */
    @Synchronized
    fun clavesKem(c: Context): Pair<ByteArray, ByteArray> {
        abrir(c, KEM)?.let { par ->
            if (par.size == KEM_PK + KEM_SK) {
                return par.copyOfRange(0, KEM_PK) to par.copyOfRange(KEM_PK, par.size)
            }
        }
        val nuevo = org.x39.bolsillo.Mensajeria.nuevoParCifrado()   // pk(1568) || sk(3168)
        sellar(c, KEM, nuevo)
        return nuevo.copyOfRange(0, KEM_PK) to nuevo.copyOfRange(KEM_PK, nuevo.size)
    }

    // ---- Otros secretos pequeños (clave WireGuard del túnel, PSK): mismo sellado ----

    /** Sella `datos` bajo el nombre dado (fichero privado, cifrado con la clave del Keystore). */
    fun sellar(c: Context, nombre: String, datos: ByteArray) {
        val ci = Cipher.getInstance("AES/GCM/NoPadding")
        ci.init(Cipher.ENCRYPT_MODE, claveAes())
        val ct = ci.doFinal(datos)
        val iv = ci.iv
        File(c.filesDir, "$nombre.bin").outputStream().use {
            it.write(iv.size); it.write(iv); it.write(ct)
        }
    }

    /** Abre lo sellado con [sellar]; null si no existe o no se puede abrir. */
    fun abrir(c: Context, nombre: String): ByteArray? {
        val f = File(c.filesDir, "$nombre.bin")
        if (!f.exists()) return null
        return try {
            val b = f.readBytes()
            val ivLen = b[0].toInt() and 0xFF
            val ci = Cipher.getInstance("AES/GCM/NoPadding")
            ci.init(Cipher.DECRYPT_MODE, claveAes(), GCMParameterSpec(128, b.copyOfRange(1, 1 + ivLen)))
            ci.doFinal(b.copyOfRange(1 + ivLen, b.size))
        } catch (e: Exception) {
            null
        }
    }

    fun borrar(c: Context, nombre: String) { File(c.filesDir, "$nombre.bin").delete() }

    private fun claveAes(): SecretKey {
        val ks = KeyStore.getInstance(KS).apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KS)
        kg.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return kg.generateKey()
    }
}
