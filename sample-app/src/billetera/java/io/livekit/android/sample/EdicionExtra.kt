package io.livekit.android.sample

import android.content.Intent
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import x39.wallet.android.Ajustes
import x39.wallet.android.AjustesActivity
import x39.wallet.android.BilleteraActivity
import x39.wallet.android.EnviarActivity
import x39.wallet.android.ModoTunel
import x39.wallet.android.NodoX39
import x39.wallet.android.RecibirActivity
import x39.wallet.android.Redes

/**
 * Ediciones CON billetera (`general` = laboratorio `.lab`, y `escritorio`): toda la interfaz, los
 * textos y los recursos de la billetera viven AQUÍ, en el source set `billetera`, que esos dos
 * sabores comparten. La edición `comunicaciones` no incluye este fichero ni estos recursos ni la
 * dependencia `x39.wallet:wallet`, así que no compila ni empaqueta nada de billetera. Lo que solo
 * lleva `escritorio` (telemetría del nodo y CKPool de solo lectura) entra por la costura
 * `ExtraEscritorio.tarjetaExtra`, que en `general` devuelve null.
 *
 * Solo monta: las pantallas son las del módulo público `:wallet` (repo x39-wallet). Antes de
 * abrir nada se comprueba `Redes.androidCompatible()` (la billetera exige Android 6).
 *
 * Usa los helpers y colores de X39CallActivity (marcados `internal`), sin duplicarlos.
 */
internal object EdicionExtra {

    init {
        // El módulo :wallet no ve el túnel de la app: se le dice aquí cómo saber si el X39 Túnel
        // está activo. El «Nodo X-39» de la billetera solo se usa a través de él.
        NodoX39.tunelActivo = { TunelX39.arriba() }
    }

    /** Cuerpo de la pestaña BILLETERA, o null si la pestaña activa no es la billetera. */
    fun cuerpoSi(a: X39CallActivity, p: Pestana): View? =
        if (p == Pestana.BILLETERA) cuerpo(a) else null

    /** Entrada de la billetera en la barra inferior. */
    fun entradaNav(a: X39CallActivity): View? =
        a.navItem(Pestana.BILLETERA, R.drawable.x39_ic_billetera, a.getString(R.string.x39_nav_billetera))

    /** Fila «nodo Bitcoin» de la pantalla de ajustes: abre los ajustes de la billetera (servidor Electrum). */
    fun filaAjustesNodo(a: X39CallActivity): View? =
        a.filaAjuste(
            R.drawable.x39_ic_billetera,
            a.getString(R.string.x39_ajustes_nodo_bitcoin),
            a.getString(R.string.x39_ajustes_nodo_bitcoin_sub),
        ) { abrir(a, AjustesActivity::class.java) }

    /**
     * Fila «Nodo» de la pantalla Seguridad: qué nodo usa la billetera («X-39 por túnel» o «el tuyo
     * (host)») y la frase fija sobre lo que ve el servidor Electrum. Abre Ajustes → Nodo.
     */
    fun filaSeguridadNodo(a: X39CallActivity): View? {
        val cfg = if (Redes.androidCompatible()) Ajustes(a).leer() else null
        val titulo = when {
            cfg == null -> a.getString(R.string.x39_seguridad_nodo_ninguno)
            cfg.tunel == ModoTunel.X39 -> a.getString(R.string.x39_seguridad_nodo_x39)
            else -> a.getString(R.string.x39_seguridad_nodo_propio, cfg.host())
        }
        val fila = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(a.dp(4), a.dp(12), a.dp(4), a.dp(12))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setOnClickListener { abrir(a, AjustesActivity::class.java) }
        }
        fila.addView(a.icono(R.drawable.x39_ic_billetera, a.gris, 22))
        fila.addView(LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = a.dp(13) }
            addView(TextView(a).apply { text = titulo; textSize = 14f; setTextColor(a.claro) })
            addView(TextView(a).apply {
                text = a.getString(R.string.x39_seguridad_nodo_frase); textSize = 11f; setTextColor(a.tenue); setPadding(0, a.dp(2), 0, 0)
            })
        })
        fila.addView(TextView(a).apply { text = "›"; textSize = 20f; setTextColor(a.tenue) })
        return fila
    }

    private fun abrir(a: X39CallActivity, pantalla: Class<*>) {
        NodoX39.tunelActivo = { TunelX39.arriba() }
        if (!Redes.androidCompatible()) {
            a.toast(a.getString(R.string.x39_billetera_android6))
            return
        }
        a.startActivity(Intent(a, pantalla))
    }

    private fun cuerpo(a: X39CallActivity): View {
        val col = a.columnaScroll()
        col.addView(a.cabeceraMatrix())
        col.addView(a.tituloSeccion(a.getString(R.string.x39_billetera_titulo)))
        val card = a.tarjetaCol()
        card.addView(TextView(a).apply {
            text = a.getString(R.string.x39_billetera_ruta); textSize = 11f; setTextColor(a.gris); gravity = Gravity.CENTER
            setPadding(0, a.dp(8), 0, a.dp(14))
        })
        card.addView(a.botonAncho(a.getString(R.string.x39_billetera_abrir), a.claro, a.grafito) { abrir(a, BilleteraActivity::class.java) })
        card.addView(View(a).apply { layoutParams = LinearLayout.LayoutParams(1, a.dp(10)) })
        val accs = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val bRec = a.botonAncho(a.getString(R.string.x39_billetera_recibir), a.sup2, a.claro) { abrir(a, RecibirActivity::class.java) }
        bRec.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        val bEnv = a.botonAncho(a.getString(R.string.x39_enviar), a.sup2, a.claro) { abrir(a, EnviarActivity::class.java) }
        bEnv.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        accs.addView(bRec)
        accs.addView(View(a).apply { layoutParams = LinearLayout.LayoutParams(a.dp(10), 1) })
        accs.addView(bEnv)
        card.addView(accs)
        col.addView(card)
        // Solo en el sabor `escritorio`: tarjeta de telemetría del nodo y CKPool. En `general`, null.
        ExtraEscritorio.tarjetaExtra(a)?.let { col.addView(it) }
        col.addView(TextView(a).apply {
            text = a.getString(R.string.x39_billetera_autocustodia)
            textSize = 10f; setTextColor(a.tenue); gravity = Gravity.CENTER; setTypeface(typeface, Typeface.NORMAL)
            setPadding(a.dp(10), a.dp(14), a.dp(10), 0)
        })
        return a.envolver(col)
    }
}
