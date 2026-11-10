/*
 * x39 · volcado para VERIFICAR la limpieza de vídeos desde el ordenador.
 *
 * Lo recibido vive sellado en la Bóveda (clave del Keystore, que no sale del móvil), así
 * que para pasarle exiftool al vídeo tal como llegó hace falta que la propia app lo abra.
 *
 * Límites, a propósito:
 *   · Solo lo puede disparar adb: el receptor exige android.permission.DUMP, que tiene
 *     el shell y no tiene ninguna app instalada.
 *   · Solo en compilaciones depurables: la clase y su <receiver> viven en src/debug, así que
 *     en el APK de publicación no existen. La comprobación de FLAG_DEBUGGABLE se queda por si acaso.
 *   · Escribe en la caché PRIVADA de la app (se saca con run-as) y hay orden de borrado.
 *
 *   (applicationId org.x39matrix.llamadas; el paquete Java sigue siendo io.livekit.android.sample)
 *   adb shell am broadcast -a io.livekit.android.X39_VOLCAR_VIDEO -n org.x39matrix.llamadas/io.livekit.android.sample.VolcadoVerificacion
 *   adb exec-out run-as org.x39matrix.llamadas cat cache/volcado_video.mp4 > recibido.mp4
 *   adb shell am broadcast -a io.livekit.android.X39_BORRAR_VOLCADO -n org.x39matrix.llamadas/io.livekit.android.sample.VolcadoVerificacion
 */
package io.livekit.android.sample

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import java.io.File
import java.security.MessageDigest

class VolcadoVerificacion : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return
        val destino = File(context.cacheDir, "volcado_video.mp4")
        when (intent.action) {
            ACCION_BORRAR -> {
                android.util.Log.i(TAG, "volcado borrado: ${destino.delete()}")
            }
            ACCION_VOLCAR -> {
                X39App.asegurar(context)
                Adjuntos.init(context)
                val ultimo = Mensajes.conversaciones()
                    .flatMap { Mensajes.conversacion(it.bh) }
                    .filter { !it.mio && it.adjunto.isNotEmpty() && it.adjMime.startsWith("video/") }
                    .maxByOrNull { it.ts }
                if (ultimo == null) {
                    android.util.Log.i(TAG, "no hay ningún vídeo RECIBIDO que volcar")
                    return
                }
                val datos = Adjuntos.abrir(ultimo.adjunto)
                if (datos == null) {
                    android.util.Log.i(TAG, "el vídeo ${ultimo.adjunto} aún no está completo")
                    return
                }
                destino.writeBytes(datos)
                val huella = MessageDigest.getInstance("SHA-256").digest(datos)
                    .joinToString("") { "%02x".format(it) }
                android.util.Log.i(
                    TAG,
                    "volcado: ${ultimo.adjNombre} · ${datos.size} bytes · sha256=$huella → cache/volcado_video.mp4",
                )
            }
        }
    }

    companion object {
        private const val TAG = "X39_VOLCADO"
        const val ACCION_VOLCAR = "io.livekit.android.X39_VOLCAR_VIDEO"
        const val ACCION_BORRAR = "io.livekit.android.X39_BORRAR_VOLCADO"
    }
}
