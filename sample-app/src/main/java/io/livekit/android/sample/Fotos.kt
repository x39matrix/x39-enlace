/*
 * x39 · foto de perfil.
 *
 * Reglas, que no son un detalle:
 *   · La foto vive en ESTE móvil. No se sube a ningún servidor, ni cifrada.
 *   · Se manda sellada con París, UNO A UNO, y SOLO a los contactos marcados como
 *     verificados. A quien no lo esté no se le manda nada.
 *   · Antes de salir se le quitan los metadatos: una foto de la galería lleva dentro
 *     dónde y cuándo se hizo y con qué móvil. Cifrar el fichero no sirve de nada si
 *     el que la abre ve las coordenadas.
 *   · Por defecto NO hay foto. Ponerla la decide la persona.
 *
 * En disco, tanto la propia como las recibidas se sellan con la clave del Keystore,
 * igual que el historial de mensajes. Nunca van a la galería.
 */
package io.livekit.android.sample

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import java.io.ByteArrayOutputStream

object Fotos {

    /** Lado máximo en píxeles. Suficiente para un avatar y ligero de mandar. */
    private const val LADO = 384
    private const val CALIDAD = 75

    /** Tope de seguridad: si una foto se pasa de aquí, no se manda. */
    const val MAX_BYTES = 120 * 1024

    private const val MIA = "foto_mia"

    private lateinit var ctx: Context
    private val enMemoria = HashMap<String, Bitmap?>()

    fun interface Oyente { fun fotoCambio(bh: String?) }
    var oyente: Oyente? = null

    @Synchronized
    fun init(c: Context) {
        if (!::ctx.isInitialized) ctx = c.applicationContext
    }

    // ===== Leer =================================================================

    /** Mi foto, o null si no he puesto ninguna (que es lo normal). */
    fun mia(): Bitmap? = cargar(MIA)

    /** La foto de un contacto, o null. */
    fun de(bh: String): Bitmap? = cargar("foto_$bh")

    @Synchronized
    private fun cargar(nombre: String): Bitmap? {
        if (!::ctx.isInitialized) return null
        if (enMemoria.containsKey(nombre)) return enMemoria[nombre]
        val bytes = Boveda.abrir(ctx, nombre)
        val bmp = if (bytes == null) null else {
            try { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) } catch (e: Exception) { null }
        }
        enMemoria[nombre] = bmp
        return bmp
    }

    @Synchronized
    private fun guardar(nombre: String, jpeg: ByteArray?) {
        if (jpeg == null) {
            Boveda.borrar(ctx, nombre)
            enMemoria[nombre] = null
        } else {
            Boveda.sellar(ctx, nombre, jpeg)
            enMemoria.remove(nombre)
        }
    }

    // ===== Poner y quitar la mía ================================================

    /**
     * Toma la foto elegida, le quita los metadatos y la guarda sellada.
     * Devuelve null si va bien, o el motivo si no.
     */
    fun ponerMia(uri: Uri): String? {
        return try {
            val jpeg = limpiarYReducir(uri) ?: return ctx.getString(R.string.x39_fotos_no_leer)
            if (jpeg.size > MAX_BYTES) return ctx.getString(R.string.x39_fotos_grande)
            guardar(MIA, jpeg)
            oyente?.fotoCambio(null)
            null
        } catch (e: Exception) {
            e.message ?: e.javaClass.simpleName
        }
    }

    /** Quita mi foto de este móvil y avisa a los contactos para que borren su copia. */
    fun quitarMia() {
        guardar(MIA, null)
        oyente?.fotoCambio(null)
        repartir(null)
    }

    /**
     * Quita TODOS los metadatos y reduce la foto.
     *
     * La limpieza no consiste en borrar etiquetas una a una —ahí es fácil dejarse
     * alguna—, sino en decodificar los píxeles y volver a comprimir: el JPEG que sale
     * se crea de cero, así que no arrastra EXIF, ni GPS, ni modelo de cámara, ni fecha.
     * Lo único que se conserva a propósito es la ORIENTACIÓN, aplicándola a los píxeles
     * antes de tirar el dato; si no, la foto saldría girada.
     */
    private fun limpiarYReducir(uri: Uri): ByteArray? {
        val original = ctx.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it)
        } ?: return null

        val giro = orientacion(uri)
        val derecha = if (giro == 0f) original else {
            Bitmap.createBitmap(
                original, 0, 0, original.width, original.height,
                Matrix().apply { postRotate(giro) }, true,
            )
        }

        val lado = minOf(derecha.width, derecha.height)
        val cuadrada = Bitmap.createBitmap(
            derecha,
            (derecha.width - lado) / 2,
            (derecha.height - lado) / 2,
            lado, lado,
        )
        val pequena = Bitmap.createScaledBitmap(cuadrada, LADO, LADO, true)

        val out = ByteArrayOutputStream()
        pequena.compress(Bitmap.CompressFormat.JPEG, CALIDAD, out)
        return out.toByteArray()
    }

    /** Solo para enderezarla; el dato en sí no viaja. */
    private fun orientacion(uri: Uri): Float = try {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            0f
        } else {
            ctx.contentResolver.openInputStream(uri)?.use { entrada ->
                when (
                    android.media.ExifInterface(entrada)
                        .getAttributeInt(
                            android.media.ExifInterface.TAG_ORIENTATION,
                            android.media.ExifInterface.ORIENTATION_NORMAL,
                        )
                ) {
                    android.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    android.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    android.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            } ?: 0f
        }
    } catch (e: Exception) {
        0f
    }

    // ===== Repartir a los contactos verificados =================================

    /**
     * Manda la foto (o el aviso de que se ha quitado) a cada contacto verificado, uno
     * a uno y con su propio sobre. A los que no están verificados NO se les manda nada:
     * la foto es un dato personal y solo va a quien se ha comprobado que es quien dice.
     */
    fun repartir(jpeg: ByteArray? = Boveda.abrir(ctx, MIA)) {
        for (c in X39App.agenda.todos()) {
            if (!c.verificado) continue
            if (!c.puedeRecibirMensajes()) continue
            Mensajes.enviarFoto(c.bh, jpeg)
        }
    }

    /** A quién se le mandaría la foto ahora mismo. Para decirlo en pantalla sin mentir. */
    fun cuantosVerificados(): Int =
        X39App.agenda.todos().count { it.verificado && it.puedeRecibirMensajes() }

    // ===== Recibir ==============================================================

    /** Llega la foto de un contacto (o el aviso de que la ha quitado). */
    @Synchronized
    fun recibida(bh: String, jpeg: ByteArray?) {
        guardar("foto_$bh", jpeg)
        oyente?.fotoCambio(bh)
    }

    /** Al borrar un contacto se va también su foto. */
    fun borrarDe(bh: String) = guardar("foto_$bh", null)
}
