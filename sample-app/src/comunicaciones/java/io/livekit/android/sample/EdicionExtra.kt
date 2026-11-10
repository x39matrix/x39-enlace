package io.livekit.android.sample

import android.view.View

/**
 * Edición comunicaciones (X-39 Comunicaciones): SIN billetera. Las cuatro costuras devuelven
 * null, así que esta variante no compila ninguna vista, texto ni recurso de billetera.
 */
internal object EdicionExtra {
    fun cuerpoSi(a: X39CallActivity, p: Pestana): View? = null
    fun entradaNav(a: X39CallActivity): View? = null
    fun filaAjustesNodo(a: X39CallActivity): View? = null
    fun filaSeguridadNodo(a: X39CallActivity): View? = null
}
