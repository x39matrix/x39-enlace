/*
 * x39 · limpieza de vídeos antes de enviarlos por el chat.
 *
 * Un vídeo grabado con el móvil lleva dentro dónde se grabó (©xyz), cuándo (las
 * fechas de las cajas mvhd/tkhd/mdhd), el modelo y la versión de Android (udta/meta).
 * Cifrarlo no sirve de nada si el que lo recibe lo abre y lo lee.
 *
 * Cómo se limpia, sin recodificar:
 *   1. Remux: MediaExtractor lee las muestras y MediaMuxer las escribe en un MP4 nuevo,
 *      tal cual, con sus tiempos y banderas. Solo pistas de vídeo y de audio; el resto
 *      (metadatos temporizados, subtítulos, pistas de posición) no pasa.
 *   2. Repaso de cajas sobre el MP4 que sale: MediaMuxer pone por su cuenta la fecha de
 *      ahora en mvhd/tkhd/mdhd y puede añadir "com.android.version" en un meta. Las
 *      fechas se ponen a cero y las cajas udta/meta/uuid se convierten en "free" del
 *      mismo tamaño, así no se mueve ningún desplazamiento y el vídeo sigue valiendo.
 *
 * Este criterio es el de x39 (limpiar SIEMPRE lo que sale por el chat). No tiene nada
 * que ver con x39 Evidencia, que conserva el original byte a byte.
 */
package io.livekit.android.sample

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import java.io.File
import java.nio.ByteBuffer

object Videos {

    private const val TAG = "X39_VIDEO"

    /** Lo que sale de limpiar: los bytes limpios, o el motivo por el que no se envía. */
    sealed class Resultado {
        class Limpio(val datos: ByteArray) : Resultado()
        class Fallo(val motivo: String) : Resultado()
    }

    /**
     * Remuxea el vídeo de `uri` a un MP4 sin metadatos. Si algo falla, devuelve Fallo:
     * el original NUNCA se usa como plan B.
     */
    fun limpiar(ctx: Context, uri: Uri, maxBytes: Int): Resultado {
        val temporal = File(ctx.cacheDir, "x39_video_${System.nanoTime()}.mp4")
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        try {
            extractor.setDataSource(ctx, uri, null)

            // Qué pistas pasan: solo vídeo y audio.
            val destinoDe = HashMap<Int, Int>()
            val pistas = ArrayList<Pair<Int, MediaFormat>>()
            var hayVideo = false
            var rotacion = 0
            var tamMax = 1 shl 20
            for (i in 0 until extractor.trackCount) {
                val formato = extractor.getTrackFormat(i)
                val mime = formato.getString(MediaFormat.KEY_MIME) ?: ""
                if (!mime.startsWith("video/") && !mime.startsWith("audio/")) {
                    android.util.Log.i(TAG, "pista $i ($mime) no pasa")
                    continue
                }
                if (mime.startsWith("video/")) {
                    hayVideo = true
                    if (formato.containsKey(MediaFormat.KEY_ROTATION)) {
                        rotacion = formato.getInteger(MediaFormat.KEY_ROTATION)
                    }
                }
                if (formato.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                    tamMax = maxOf(tamMax, formato.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE))
                }
                pistas.add(i to formato)
            }
            if (!hayVideo) return Resultado.Fallo(ctx.getString(R.string.x39_video_sin_pista))
            if (rotacion == 0) rotacion = rotacionPorRetriever(ctx, uri)

            muxer = MediaMuxer(temporal.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            for ((i, formato) in pistas) {
                // Se copia el formato de la pista (códec, tamaño, csd), no los metadatos
                // del contenedor: MediaMuxer no los tiene por dónde recibir.
                destinoDe[i] = muxer.addTrack(formato)
                extractor.selectTrack(i)
            }
            // Solo la rotación. setLocation NO se llama nunca.
            muxer.setOrientationHint(rotacion)
            muxer.start()

            val buffer = ByteBuffer.allocate(tamMax)
            val info = MediaCodec.BufferInfo()
            var muestras = 0
            while (true) {
                buffer.clear()
                val n = extractor.readSampleData(buffer, 0)
                if (n < 0) break
                val destino = destinoDe[extractor.sampleTrackIndex]
                if (destino != null) {
                    info.offset = 0
                    info.size = n
                    info.presentationTimeUs = extractor.sampleTime
                    info.flags = if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) {
                        MediaCodec.BUFFER_FLAG_KEY_FRAME
                    } else {
                        0
                    }
                    muxer.writeSampleData(destino, buffer, info)
                    muestras++
                }
                extractor.advance()
            }
            muxer.stop()
            muxer.release()
            muxer = null
            if (muestras == 0) return Resultado.Fallo(ctx.getString(R.string.x39_video_sin_muestras))

            if (temporal.length() > maxBytes) return Resultado.Fallo(ctx.getString(R.string.x39_video_pasa_25))
            val datos = temporal.readBytes()
            val cajas = neutralizarCajas(datos)
            android.util.Log.i(
                TAG,
                "vídeo limpio: ${pistas.size} pistas, $muestras muestras, rotación $rotacion, " +
                    "${datos.size} bytes, sha256=${sha256(datos)}; cajas: $cajas",
            )
            return Resultado.Limpio(datos)
        } catch (e: Exception) {
            android.util.Log.w(TAG, "no se pudo limpiar el vídeo", e)
            return Resultado.Fallo(e.message ?: e.javaClass.simpleName)
        } finally {
            runCatching { muxer?.release() }
            runCatching { extractor.release() }
            temporal.delete()
        }
    }

    private fun rotacionPorRetriever(ctx: Context, uri: Uri): Int {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(ctx, uri)
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        } catch (e: Exception) {
            0
        } finally {
            runCatching { r.release() }
        }
    }

    // ===== Repaso de cajas =====================================================

    /** Cajas que se recorren por dentro buscando fechas y metadatos. */
    private val CONTENEDORES = setOf("moov", "trak", "mdia", "edts", "minf")

    /** Cajas que se anulan enteras: pasan a ser relleno ("free") del mismo tamaño. */
    private val ANULAR = setOf("udta", "meta", "uuid")

    /** Cajas con fecha de creación y de modificación, justo detrás de versión+banderas. */
    private val CON_FECHAS = setOf("mvhd", "tkhd", "mdhd")

    /**
     * Pone a cero las fechas y convierte en "free" las cajas de metadatos, sin cambiar
     * ningún tamaño. Devuelve un resumen de lo que ha tocado, para el log.
     */
    fun neutralizarCajas(b: ByteArray): String {
        val hecho = StringBuilder()
        recorrer(b, 0, b.size, hecho)
        return if (hecho.isEmpty()) "nada que tocar" else hecho.toString().trim()
    }

    private fun recorrer(b: ByteArray, desde: Int, hasta: Int, hecho: StringBuilder) {
        var p = desde
        while (p + 8 <= hasta) {
            var tam = u32(b, p)
            val tipo = String(b, p + 4, 4, Charsets.ISO_8859_1)
            var cabecera = 8
            if (tam == 1L) {
                if (p + 16 > hasta) return
                tam = u64(b, p + 8)
                cabecera = 16
            } else if (tam == 0L) {
                tam = (hasta - p).toLong()
            }
            if (tam < cabecera || p + tam > hasta) return
            val fin = (p + tam).toInt()
            when (tipo) {
                in CONTENEDORES -> recorrer(b, p + cabecera, fin, hecho)
                in CON_FECHAS -> { borrarFechas(b, p + cabecera); hecho.append("$tipo:fechas=0 ") }
                in ANULAR -> {
                    "free".toByteArray(Charsets.ISO_8859_1).copyInto(b, p + 4)
                    hecho.append("$tipo→free ")
                }
            }
            p = fin
        }
    }

    /** versión (1 byte) + banderas (3) + creación + modificación (4 u 8 bytes cada una). */
    private fun borrarFechas(b: ByteArray, p: Int) {
        val largo = if (b[p].toInt() == 1) 16 else 8
        java.util.Arrays.fill(b, p + 4, p + 4 + largo, 0)
    }

    private fun sha256(b: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private fun u32(b: ByteArray, p: Int): Long =
        ((b[p].toLong() and 0xFF) shl 24) or ((b[p + 1].toLong() and 0xFF) shl 16) or
            ((b[p + 2].toLong() and 0xFF) shl 8) or (b[p + 3].toLong() and 0xFF)

    private fun u64(b: ByteArray, p: Int): Long = (u32(b, p) shl 32) or u32(b, p + 4)
}
