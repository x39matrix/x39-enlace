package io.livekit.android.sample

import android.content.Context

/**
 * Ajustes de x39, guardados en el propio aparato (no en la nube):
 *   · senal   — tu servidor de señalización  (wss://…, por el x39tunel/París)
 *   · livekit — tu servidor de medios LiveKit (wss://…)
 *   · pin     — pin del certificado de TU señalización ("sha256/…"); vacío = TLS normal
 *
 * Todo apunta a TU infraestructura. Se edita en la pantalla de Ajustes.
 */
object Config {
    private const val PREF = "x39"
    // La señalización va en el MISMO dominio que LiveKit, en otro puerto: así reutiliza
    // el certificado que Caddy ya mantiene y no hace falta DNS nuevo ni tocar el proxy.
    private const val DEF_SENAL = "wss://livekit.x39matrix.org:8443"
    private const val DEF_LIVEKIT = "wss://livekit.x39matrix.org"

    private fun p(c: Context) = c.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun urlSenal(c: Context): String = p(c).getString("senal", DEF_SENAL) ?: DEF_SENAL
    fun urlLiveKit(c: Context): String = p(c).getString("livekit", DEF_LIVEKIT) ?: DEF_LIVEKIT
    fun pinSenal(c: Context): String = p(c).getString("pin", "") ?: ""

    fun guardar(c: Context, senal: String, livekit: String, pin: String) {
        p(c).edit()
            .putString("senal", senal.trim())
            .putString("livekit", livekit.trim())
            .putString("pin", pin.trim())
            .apply()
    }
}
