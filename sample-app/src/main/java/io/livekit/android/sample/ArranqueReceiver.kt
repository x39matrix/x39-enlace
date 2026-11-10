/*
 * x39 · al encender el móvil, la línea se abre sola.
 *
 * Sin esto, tras un reinicio no llega nada hasta que alguien abre la app a mano.
 * No se abre ninguna pantalla: solo se levanta el ServicioX39.
 */
package io.livekit.android.sample

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class ArranqueReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.intent.action.QUICKBOOT_POWERON",
            -> {
                android.util.Log.i("X39_ARRANQUE", "móvil encendido: levanto la línea")
                ServicioX39.arrancar(ctx.applicationContext)
            }
        }
    }
}
