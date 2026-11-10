package io.livekit.android.sample

import java.security.MessageDigest
import java.util.Base64

/**
 * Identidad completa («X39ID1:…», 5.559 caracteres) repartida en varios QR pequeños que se van alternando
 * en pantalla. No necesita servidor: es el mismo texto que se pega a mano, solo que troceado.
 *
 *   parte:  "X39QP2:" + etiqueta + ":" + i + "/" + n + ":" + trozo      (i de 1 a n)
 *   etiqueta = SHA-256(texto completo), los 32 bytes, en base64url sin relleno (43 caracteres).
 *
 * La etiqueta ata cada parte a UNA identidad: si a mitad del escaneo aparece una parte con otra etiqueta
 * (alguien cambia la pantalla), se para y se avisa; no se junta nada. Al completar, se recalcula SHA-256
 * del conjunto y se compara con la etiqueta (256 bits, en tiempo constante).
 *
 * Esto garantiza que lo leído es UNA identidad íntegra, no que sea la de quien dice ser: quien controla la
 * pantalla puede enseñar la identidad que quiera. Eso solo lo descubre la comparación de huellas de viva voz,
 * que la app pide siempre al terminar.
 */
object QrPartes {
    const val PREFIJO = "X39QP2:"
    const val PARTES = 12
    private const val ETIQUETA_LEN = 43

    private fun sha256(texto: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(texto.toByteArray(Charsets.ISO_8859_1))

    private fun etiqueta(texto: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(sha256(texto))

    fun trocear(texto: String, n: Int = PARTES): List<String> {
        val tam = (texto.length + n - 1) / n
        val et = etiqueta(texto)
        return (0 until n).map { i ->
            "$PREFIJO$et:${i + 1}/$n:" + texto.substring(minOf(i * tam, texto.length), minOf((i + 1) * tam, texto.length))
        }
    }

    /** Partes de dos identidades distintas en el mismo escaneo, o un conjunto que no da su etiqueta. */
    class Mezcla : Exception("partes de dos identidades distintas")

    /** Junta partes leídas en cualquier orden. No es para varios hilos: lo usa solo el hilo del análisis. */
    class Recolector {
        private var etiqueta: String? = null
        private var total = 0
        private val trozos = HashMap<Int, String>()

        val leidas get() = trozos.size
        val totales get() = total

        /**
         * Añade una parte. Devuelve null si falta alguna; el texto completo, ya comprobado, cuando están todas.
         * Lanza [Mezcla] si la parte es de otra identidad (otra etiqueta u otro total) o si una posición ya
         * leída llega con otro contenido, o si el conjunto no da la etiqueta anunciada.
         * Lanza IllegalArgumentException si una parte está mal formada (se ignora y se sigue leyendo).
         */
        fun anadir(parte: String): String? {
            require(parte.startsWith(PREFIJO)) { "no es una parte $PREFIJO" }
            val campos = parte.substring(PREFIJO.length).split(":", limit = 3)
            require(campos.size == 3) { "parte mal formada" }
            val (et, pos, trozo) = campos
            require(et.length == ETIQUETA_LEN) { "etiqueta de longitud incorrecta" }
            val nums = pos.split("/")
            require(nums.size == 2) { "posición mal formada: $pos" }
            val i = nums[0].toIntOrNull(); val n = nums[1].toIntOrNull()
            require(i != null && n != null && n in 1..64 && i in 1..n) { "posición fuera de rango: $pos" }
            if (etiqueta == null) { etiqueta = et; total = n }
            if (et != etiqueta || n != total) throw Mezcla()
            val previo = trozos[i]
            if (previo != null && previo != trozo) throw Mezcla()
            trozos[i] = trozo
            if (trozos.size < total) return null
            val texto = (1..total).joinToString("") { trozos.getValue(it) }
            val esperada = Base64.getUrlDecoder().decode(et)
            // Todas las posiciones con la misma etiqueta pero el conjunto no da esa etiqueta: partes manipuladas.
            if (!MessageDigest.isEqual(sha256(texto), esperada)) throw Mezcla()
            return texto
        }
    }
}
