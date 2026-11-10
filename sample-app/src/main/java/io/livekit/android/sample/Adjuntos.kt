/*
 * x39 · ficheros por el chat.
 *
 * Cómo viaja, y por qué así:
 *   · El fichero se parte en trozos y CADA TROZO se sella con París por separado, con
 *     su propio encapsulado ML-KEM y su propia firma. Reventar la clave de un trozo no
 *     abre los demás.
 *   · Van por el mismo relé "msg" que el texto. El servidor NO guarda nada, tampoco los
 *     trozos: si el otro no está en línea, la cola vive en este móvil.
 *   · Lo que se recibe cae en el almacén cifrado de la app, NUNCA en la galería: la
 *     galería sincroniza con la nube y eso tiraría por tierra todo lo demás.
 *
 * El precio, que se dice en pantalla y no se esconde: cada trozo carga con ~6 KB de
 * sobre, así que un fichero de 25 MB mueve del orden de 38 MB por el servidor de quien
 * paga el servidor.
 */
package io.livekit.android.sample

import android.content.Context
import android.net.Uri
import android.util.Base64
import java.io.File
import org.json.JSONObject
import org.x39.bolsillo.Mensajeria

object Adjuntos {

    /** Lo que cabe en un fichero. Se avisa ANTES de enviar. */
    const val MAX_BYTES = 25 * 1024 * 1024

    /** Datos útiles por trozo. Con esto el sobre ronda los 72 KB en base64. */
    private const val TROZO = 48 * 1024

    /** Sobre por trozo: encapsulado ML-KEM + firma ML-DSA-87 + cabecera. */
    private const val SOBRE_POR_TROZO = 6_200

    enum class Clase { FOTO, VIDEO, DOCUMENTO, AUDIO }

    /** Un envío o una recepción en marcha. */
    data class EnCurso(
        val id: String,
        val bh: String,
        val nombre: String,
        val total: Int,
        var hechos: Int = 0,
        var cancelado: Boolean = false,
        val entrante: Boolean = false,
    ) {
        val porCiento: Int get() = if (total == 0) 0 else hechos * 100 / total
    }

    fun interface Oyente { fun progreso(e: EnCurso) }
    var oyente: Oyente? = null

    private lateinit var ctx: Context
    private val enCurso = HashMap<String, EnCurso>()
    private val trozosRecibidos = HashMap<String, MutableMap<Int, ByteArray>>()
    private val cacheMini = object : LinkedHashMap<String, android.graphics.Bitmap>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, android.graphics.Bitmap>?): Boolean = size > 40
    }

    @Synchronized
    fun init(c: Context) {
        if (!::ctx.isInitialized) ctx = c.applicationContext
    }

    /** Cuánto tráfico real va a costar mandar esto. Para decirlo antes, no después. */
    fun traficoEstimado(bytes: Long): Long {
        val trozos = (bytes + TROZO - 1) / TROZO
        return ((bytes + trozos * SOBRE_POR_TROZO) * 4 + 2) / 3   // + base64
    }

    fun trozosDe(bytes: Long): Int = ((bytes + TROZO - 1) / TROZO).toInt()

    // ===== Metadatos ============================================================

    /**
     * Lee el fichero elegido y, si es una foto o un vídeo, le QUITA los metadatos antes
     * de nada (el vídeo, en Videos.kt).
     *
     * Esto es lo más importante de todo el envío: una foto sacada de la galería lleva
     * dentro las coordenadas de dónde se hizo, el modelo del móvil y la hora exacta.
     * Cifrar el fichero no sirve de nada si el que lo recibe abre la foto y ve dónde
     * estabas. Se limpia POR DEFECTO y sin preguntar; conservarlos es una casilla que
     * el usuario tiene que marcar a propósito.
     *
     * La limpieza se hace recodificando los píxeles, no borrando etiquetas una a una:
     * el JPEG que sale nace de cero y no arrastra nada.
     */
    /** Lo que sale de leer un fichero para enviarlo. */
    sealed class Lectura {
        /** `limpio` = se le han quitado los metadatos. */
        class Lista(val datos: ByteArray, val limpio: Boolean) : Lectura()
        class Fallo(val motivo: String) : Lectura()
    }

    fun leerLimpio(uri: Uri, clase: Clase, conservarMetadatos: Boolean): Lectura {
        if (clase == Clase.VIDEO && !conservarMetadatos) {
            // El vídeo se remuxea sin recodificar. Si no se puede limpiar, NO se manda
            // el original: se aborta y se dice.
            return when (val r = Videos.limpiar(ctx, uri, MAX_BYTES)) {
                is Videos.Resultado.Limpio -> Lectura.Lista(r.datos, true)
                is Videos.Resultado.Fallo -> Lectura.Fallo(
                    ctx.getString(R.string.x39_adj_video_no_limpio, r.motivo),
                )
            }
        }

        val crudo = try {
            ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        } catch (e: Exception) {
            null
        } ?: return Lectura.Fallo(ctx.getString(R.string.x39_adj_no_leer))

        if (conservarMetadatos || clase != Clase.FOTO) return Lectura.Lista(crudo, false)
        val limpio = recodificarFoto(crudo) ?: return Lectura.Lista(crudo, false)
        return Lectura.Lista(limpio, true)
    }

    /** ¿De esta clase se pueden quitar los metadatos hoy? */
    fun seLimpia(clase: Clase): Boolean = clase == Clase.FOTO || clase == Clase.VIDEO

    private fun recodificarFoto(crudo: ByteArray): ByteArray? = try {
        val bmp = android.graphics.BitmapFactory.decodeByteArray(crudo, 0, crudo.size)
        if (bmp == null) null else {
            val giro = orientacionDe(crudo)
            val derecha = if (giro == 0f) bmp else {
                android.graphics.Bitmap.createBitmap(
                    bmp, 0, 0, bmp.width, bmp.height,
                    android.graphics.Matrix().apply { postRotate(giro) }, true,
                )
            }
            val out = java.io.ByteArrayOutputStream()
            derecha.compress(android.graphics.Bitmap.CompressFormat.JPEG, 88, out)
            out.toByteArray()
        }
    } catch (e: Exception) {
        null
    }

    /** Solo para enderezarla antes de tirar el dato. */
    private fun orientacionDe(crudo: ByteArray): Float = try {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.N) 0f else {
            java.io.ByteArrayInputStream(crudo).use { e ->
                when (
                    android.media.ExifInterface(e).getAttributeInt(
                        android.media.ExifInterface.TAG_ORIENTATION,
                        android.media.ExifInterface.ORIENTATION_NORMAL,
                    )
                ) {
                    android.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    android.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    android.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            }
        }
    } catch (e: Exception) {
        0f
    }

    /** Nombre del fichero tal y como lo llama el sistema. */
    fun nombreDe(uri: Uri): String = try {
        ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (i >= 0 && c.moveToFirst()) c.getString(i) else null
        } ?: uri.lastPathSegment ?: "fichero"
    } catch (e: Exception) {
        "fichero"
    }

    fun mimeDe(uri: Uri): String =
        ctx.contentResolver.getType(uri) ?: "application/octet-stream"

    // ===== Enviar ===============================================================

    /**
     * Manda un fichero a un contacto. `datos` ya viene limpio de metadatos si era una
     * foto o un vídeo: aquí solo se parte y se sella.
     */
    @Synchronized
    fun enviar(bh: String, nombre: String, mime: String, datos: ByteArray, clase: Clase): String? {
        if (datos.size > MAX_BYTES) return ctx.getString(R.string.x39_adj_pasa_25)
        val kemPk = X39App.agenda.claveCifrado(bh) ?: return ctx.getString(R.string.x39_adj_falta_clave)
        val id = nuevoId()
        val total = ((datos.size + TROZO - 1) / TROZO)
        val e = EnCurso(id, bh, nombre, total)
        enCurso[id] = e
        Mensajes.anotarAdjuntoPropio(bh, id, nombre, datos.size.toLong(), clase)
        guardarLocal(id, datos)

        lanzarEnvio(e, id, bh, nombre, mime, datos, clase, kemPk)
        return null
    }

    /**
     * Vuelve a mandar un fichero que se quedó a medias, partiendo otra vez de lo que
     * hay guardado en el almacén. No se reintenta solo: lo pide el usuario.
     */
    @Synchronized
    fun reenviar(bh: String, idAdj: String, nombre: String, mime: String, clase: Clase): String? {
        val datos = abrir(idAdj) ?: return ctx.getString(R.string.x39_adj_ya_no_esta_movil)
        val kemPk = X39App.agenda.claveCifrado(bh) ?: return ctx.getString(R.string.x39_adj_falta_clave)
        if (enCurso[idAdj] != null) return ctx.getString(R.string.x39_adj_ya_enviando)
        val total = ((datos.size + TROZO - 1) / TROZO)
        val e = EnCurso(idAdj, bh, nombre, total)
        enCurso[idAdj] = e
        lanzarEnvio(e, idAdj, bh, nombre, mime, datos, clase, kemPk)
        return null
    }


    /** El hilo que trocea, sella y manda. Lo usan el envío nuevo y el reenvío. */
    private fun lanzarEnvio(
        e: EnCurso, id: String, bh: String, nombre: String, mime: String,
        datos: ByteArray, clase: Clase, kemPk: ByteArray,
    ) {
        val total = e.total
        Thread {
            var completo = false
            try {
                for (n in 0 until total) {
                    synchronized(this) { if (e.cancelado) return@Thread }
                    if (!BusUi.enLinea || !X39App.nucleo.conectado()) {
                        // Sin línea no se insiste aquí: queda anotado como pendiente.
                        android.util.Log.i(TAG, "sin línea a mitad del envío: queda pendiente")
                        return@Thread
                    }
                    val desde = n * TROZO
                    val hasta = minOf(desde + TROZO, datos.size)
                    val trozo = datos.copyOfRange(desde, hasta)
                    try {
                        val carga = JSONObject()
                            .put("v", 1).put("k", "fich").put("id", id)
                            .put("n", n).put("t", total)
                            .put("nom", nombre).put("mime", mime)
                            .put("tam", datos.size).put("cl", clase.name)
                            .put("d", Base64.encodeToString(trozo, Base64.NO_WRAP))
                            .toString().toByteArray(Charsets.UTF_8)
                        val sobre = Mensajeria.cerrar(kemPk, X39App.identidad.seed, carga)
                        // El id lleva dentro el del fichero: si el relé contesta
                        // "undelivered" sabemos a QUÉ envío se refiere y lo paramos.
                        X39App.nucleo.enviarCrudo(
                            JSONObject().put("type", "msg").put("id", "$id#$n").put("to", bh)
                                .put("sobre", sobre).toString(),
                        )
                    } catch (ex: Exception) {
                        android.util.Log.e(TAG, "no se pudo sellar el trozo $n", ex)
                        return@Thread
                    }
                    synchronized(this) { e.hechos = n + 1 }
                    avisar(e)
                    // Un respiro entre trozos: si no, se satura el socket de la señalización
                    // y la línea de las llamadas se resiente.
                    Thread.sleep(25)
                }
                completo = true
            } finally {
                /*
                 * Salga por donde salga —terminado, sin línea o con error—, la
                 * transferencia deja de estar «en curso». Si no se quita de aquí, la
                 * burbuja sigue pintando la barra y el botón Cancelar aunque ya no haya
                 * nada que cancelar, y solo se arreglaba al recrear la pantalla.
                 */
                synchronized(this) { enCurso.remove(id) }
                if (completo) {
                    Mensajes.adjuntoEnviado(e.bh, id)
                } else {
                    // Queda pendiente, pero hay que repintar para quitar la barra.
                    Mensajes.adjuntoInterrumpido(e.bh, id)
                }
            }
        }.start()
    }

    /**
     * El relé dice que no pudo entregar un trozo: el otro no está en línea.
     *
     * Seguir mandando los 500 trozos restantes al vacío no tiene sentido, así que se
     * corta el envío y el fichero se queda en pendiente, con su botón de reenviar. Sin
     * esto, el emisor ponía «enviado» y el otro no recibía nada nunca.
     */
    @Synchronized
    fun trozoNoEntregado(idAdj: String) {
        val e = enCurso[idAdj] ?: return
        if (e.entrante) return
        android.util.Log.i(TAG, "el destinatario no está en línea: paro el envío de $idAdj")
        e.cancelado = true
        enCurso.remove(idAdj)
        Mensajes.adjuntoInterrumpido(e.bh, idAdj)
    }

    @Synchronized
    fun cancelar(id: String) {
        enCurso[id]?.cancelado = true
        trozosRecibidos.remove(id)
        enCurso.remove(id)
    }

    fun enCurso(id: String): EnCurso? = enCurso[id]

    // ===== Recibir ==============================================================

    /** Llega un trozo. Cuando están todos, se junta y se guarda cifrado. */
    @Synchronized
    fun trozoRecibido(deBh: String, carga: JSONObject) {
        val id = carga.optString("id", "")
        val n = carga.optInt("n", -1)
        val total = carga.optInt("t", 0)
        if (id.isEmpty() || n < 0 || total <= 0) return

        val e = enCurso.getOrPut(id) {
            EnCurso(id, deBh, carga.optString("nom", "fichero"), total, entrante = true)
        }
        if (e.cancelado) return

        val partes = trozosRecibidos.getOrPut(id) { HashMap() }
        val d = carga.optString("d", "")
        partes[n] = try { Base64.decode(d, Base64.NO_WRAP) } catch (ex: Exception) { return }
        e.hechos = partes.size
        avisar(e)

        if (partes.size < total) return

        // Completo: se junta en orden y se guarda sellado.
        val tam = carga.optInt("tam", 0)
        val junto = java.io.ByteArrayOutputStream(maxOf(tam, 1))
        for (i in 0 until total) junto.write(partes[i] ?: return)
        trozosRecibidos.remove(id)
        enCurso.remove(id)
        guardarLocal(id, junto.toByteArray())
        Mensajes.anotarAdjuntoRecibido(
            deBh, id,
            carga.optString("nom", "fichero"),
            junto.size().toLong(),
            runCatching { Clase.valueOf(carga.optString("cl", "DOCUMENTO")) }.getOrDefault(Clase.DOCUMENTO),
            carga.optString("mime", "application/octet-stream"),
        )
    }

    // ===== Almacén cifrado ======================================================

    /** En disco solo hay criptograma: se sella con la clave del Keystore. */
    private fun guardarLocal(id: String, datos: ByteArray) = Boveda.sellar(ctx, "adj_$id", datos)

    /** Devuelve el contenido en claro, solo en memoria. */
    fun abrir(id: String): ByteArray? = Boveda.abrir(ctx, "adj_$id")

    /** Borra el fichero del almacén. Lo llama también el borrado por tiempo. */
    fun borrar(id: String) {
        cacheMini.remove(id)
        Boveda.borrar(ctx, "adj_$id")
    }

    /** ¿Está el fichero entero en el almacén? Mientras falten trozos, no se abre. */
    fun completo(id: String): Boolean = enCurso[id] == null && Boveda.abrir(ctx, "adj_$id") != null

    /** Miniatura para la burbuja, sin cargar la foto entera en memoria. */
    @Synchronized
    fun miniatura(id: String, ladoMax: Int): android.graphics.Bitmap? {
        cacheMini[id]?.let { return it }
        val datos = abrir(id) ?: return null
        return try {
            val medir = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeByteArray(datos, 0, datos.size, medir)
            var paso = 1
            while (medir.outWidth / paso > ladoMax * 2 && medir.outHeight / paso > ladoMax * 2) paso *= 2
            val ops = android.graphics.BitmapFactory.Options().apply { inSampleSize = paso }
            android.graphics.BitmapFactory.decodeByteArray(datos, 0, datos.size, ops)
                ?.also { cacheMini[id] = it }
        } catch (e: Exception) {
            null
        }
    }

    /** La foto entera, para el visor. */
    fun imagen(id: String): android.graphics.Bitmap? = try {
        val datos = abrir(id)
        if (datos == null) null
        else android.graphics.BitmapFactory.decodeByteArray(datos, 0, datos.size)
    } catch (e: Exception) {
        null
    }

    /**
     * Guarda una copia en la galería del móvil. Es LA ÚNICA forma de sacar algo de x39,
     * y la decide el usuario.
     *
     * Lo que se copia ya va limpio de metadatos (se limpiaron antes de sellarlo), pero
     * la galería sincroniza con la nube: a partir de aquí el fichero deja de estar bajo
     * la protección de la app. Eso se dice en pantalla antes de hacerlo.
     */
    fun guardarEnGaleria(id: String, nombre: String, mime: String): String? {
        val datos = abrir(id) ?: return ctx.getString(R.string.x39_adj_ya_no_esta)
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) {
            return ctx.getString(R.string.x39_adj_galeria_permisos)
        }
        return try {
            val valores = android.content.ContentValues().apply {
                put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, nombre)
                put(android.provider.MediaStore.MediaColumns.MIME_TYPE, mime)
                put(
                    android.provider.MediaStore.MediaColumns.RELATIVE_PATH,
                    android.os.Environment.DIRECTORY_PICTURES + "/x39",
                )
                put(android.provider.MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val cr = ctx.contentResolver
            val destino = cr.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, valores)
                ?: return ctx.getString(R.string.x39_adj_galeria_no_acepto)
            cr.openOutputStream(destino)?.use { it.write(datos) } ?: return ctx.getString(R.string.x39_adj_no_escribir)
            valores.clear()
            valores.put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0)
            cr.update(destino, valores, null, null)
            null
        } catch (e: Exception) {
            e.message ?: e.javaClass.simpleName
        }
    }

    // ===== Varios ===============================================================

    private fun avisar(e: EnCurso) {
        val o = oyente ?: return
        android.os.Handler(android.os.Looper.getMainLooper()).post { o.progreso(e) }
    }

    private fun nuevoId(): String {
        val b = ByteArray(12); java.security.SecureRandom().nextBytes(b)
        return b.joinToString("") { "%02x".format(it) }
    }

    private const val TAG = "X39_ADJ"
}
