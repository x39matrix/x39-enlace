package io.livekit.android.sample

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

/**
 * Idioma de la app elegido en Ajustes. AppCompatDelegate.setApplicationLocales:
 * en Android 13+ lo guarda el sistema (Ajustes › Idioma de la app); en 7-12 lo guarda AppCompat
 * (AppLocalesMetadataHolderService con autoStoreLocales en el manifiesto). Cambia al instante, sin reiniciar.
 */
object Idioma {
    /** "" = el del sistema. Mismo orden que NOMBRES. */
    val ETIQUETAS = arrayOf("", "es", "en", "zh-CN", "ja", "ar")
    /** Cada idioma escrito en su propia lengua; el primero ("Sistema") se pone traducido desde strings.xml. */
    private val NOMBRES = arrayOf("", "Español", "English", "中文", "日本語", "العربية")

    fun actual(): String {
        val l = AppCompatDelegate.getApplicationLocales()
        return if (l.isEmpty) "" else ETIQUETAS[indice(l[0]!!.language)]
    }

    /** Posición en ETIQUETAS según el idioma (zh-CN se reconoce por "zh"); 0 = sistema. */
    private fun indice(idioma: String): Int =
        ETIQUETAS.indexOfFirst { it.isNotEmpty() && it.substringBefore('-') == idioma }.coerceAtLeast(0)

    fun nombres(c: Context): Array<String> = NOMBRES.copyOf().also { it[0] = c.getString(R.string.x39_idioma_sistema) }

    fun nombreActual(c: Context): String = nombres(c)[ETIQUETAS.indexOf(actual()).coerceAtLeast(0)]

    fun poner(c: Context, etiqueta: String) {
        AppCompatDelegate.setApplicationLocales(
            if (etiqueta.isEmpty()) LocaleListCompat.getEmptyLocaleList() else LocaleListCompat.forLanguageTags(etiqueta)
        )
        // El aviso fijo de la línea y los canales se vuelven a escribir en el idioma nuevo.
        ServicioX39.arrancar(c.applicationContext)
    }
}
