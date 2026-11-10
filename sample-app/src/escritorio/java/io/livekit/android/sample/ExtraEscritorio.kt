package io.livekit.android.sample

import android.app.AlertDialog
import android.app.KeyguardManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.util.Locale

internal object ExtraEscritorio {
    private const val PREFS_MI_NODO = "x39_mi_nodo_operator_prefs"
    private const val KEY_READ_TOKEN = "read_only_token_compat"
    private const val KEY_NODE_ENDPOINT = "custom_node_endpoint_url"
    private const val KEY_MINER_ADDRESS = "custom_miner_btc_address"
    private const val KEY_SESION_ACTIVA = "mi_nodo_sesion_activa"
    @Volatile private var ultimoEstadoCache: EstadoMiNodo? = null

    private val COLOR_AMBAR_BTC = Color.parseColor("#F7931A")
    private val COLOR_ORO_ACTIVO = Color.parseColor("#F7931A")
    private val COLOR_ROJO_ALERTA = Color.parseColor("#FF1E1E")
    private val COLOR_AMARILLO_AVISO = Color.parseColor("#FFB703")
    private val COLOR_FONDO_SUBTARJETA = Color.parseColor("#090A0D")
    private val COLOR_BORDE_SUTIL = Color.parseColor("#492327")

    private val fmtEntero = NumberFormat.getIntegerInstance(Locale("es", "ES"))

    fun tarjetaExtra(a: X39CallActivity): View? {
        val raiz = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        raiz.addView(a.tituloSeccion("⚡ NODO BITCOIN & POOL CKPOOL SOLO"))

        val contenedorDinamico = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        raiz.addView(contenedorDinamico)

        fun renderizar() {
            contenedorDinamico.removeAllViews()
            if (!IdentidadDispositivoMiNodo.sesionDesbloqueada()) {
                contenedorDinamico.addView(vistaBloqueada(a) { renderizar() })
            } else {
                contenedorDinamico.addView(vistaDesbloqueada(a) { renderizar() })
            }
        }

        renderizar()
        return raiz
    }

    private fun vistaBloqueada(a: X39CallActivity, alDesbloquear: () -> Unit): View {
        val card = a.tarjetaCol()
        card.addView(TextView(a).apply {
            text = "🔒 PANEL DE NODO Y MINERÍA — SESIÓN BLOQUEADA"
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(COLOR_AMBAR_BTC)
        })
        card.addView(TextView(a).apply {
            text = "Identidad criptográfica del dispositivo (Keystore ECDSA P-256):\n" +
                "ID: ${IdentidadDispositivoMiNodo.deviceId().chunked(8).joinToString("-")}"
            textSize = 11f
            setTextColor(a.gris)
            setPadding(0, a.dp(6), 0, a.dp(12))
        })

        val btnDesbloquear = a.botonAncho("Desbloquear panel (Clave local Keystore)", COLOR_AMBAR_BTC, Color.BLACK) {
            solicitarAutenticacionPropietario(a) {
                a.getSharedPreferences(PREFS_MI_NODO, Context.MODE_PRIVATE).edit().putBoolean(KEY_SESION_ACTIVA, true).apply()
                IdentidadDispositivoMiNodo.registrarDesbloqueoExitoso()
                alDesbloquear()
            }
        }
        card.addView(btnDesbloquear)

        card.addView(View(a).apply { layoutParams = LinearLayout.LayoutParams(1, a.dp(8)) })
        card.addView(a.botonAncho("Configurar nodo / pool o credencial", a.sup2, a.claro) {
            mostrarDialogoIdentidad(a) { alDesbloquear() }
        })
        return card
    }

    private fun vistaDesbloqueada(a: X39CallActivity, alCambiarEstado: () -> Unit): View {
        val col = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val cardCabecera = a.tarjetaCol()
        val txtEstadoEnlace = TextView(a).apply {
            text = "Consultando telemetría de nodo y pool…"
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(a.claro)
        }
        val txtFrescura = TextView(a).apply {
            text = "ID de dispositivo: ${IdentidadDispositivoMiNodo.deviceId().take(8)}… · Configurable en «Credencial»"
            textSize = 11f
            setTextColor(a.gris)
            setPadding(0, a.dp(4), 0, a.dp(8))
        }
        cardCabecera.addView(txtEstadoEnlace)
        cardCabecera.addView(txtFrescura)

        val filaControles = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val contenedorSecciones = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        ultimoEstadoCache?.let { cache ->
            renderizarSecciones(a, contenedorSecciones, txtEstadoEnlace, txtFrescura, cache)
        }

        fun cargarDatos() {
            a.lifecycleScope.launch {
                if (ultimoEstadoCache == null) {
                    txtEstadoEnlace.text = "⏳ Sincronizando telemetría (Solo lectura)…"
                    txtEstadoEnlace.setTextColor(a.claro)
                }
                try {
                    val tokenCompat = obtenerTokenLecturaOpcional(a)
                    val endpointCustom = obtenerEndpointPersonalizado(a).trim()
                    val dirMinero = obtenerDireccionMinero(a)
                    val est = TelemetriaPi5.consultarEstado(tokenCompat, endpointCustom, dirMinero)
                    ultimoEstadoCache = est
                    renderizarSecciones(a, contenedorSecciones, txtEstadoEnlace, txtFrescura, est)
                } catch (e: SecurityException) {
                    if (ultimoEstadoCache == null) contenedorSecciones.removeAllViews()
                    txtEstadoEnlace.text = "⛔ ACCESO DENEGADO POR EL NODO"
                    txtEstadoEnlace.setTextColor(COLOR_ROJO_ALERTA)
                    txtFrescura.text = e.message ?: "Dispositivo o token no autorizado"
                } catch (e: Exception) {
                    if (ultimoEstadoCache == null) contenedorSecciones.removeAllViews()
                    txtEstadoEnlace.text = "⚠️ SIN CONEXIÓN CON EL NODO / POOL"
                    txtEstadoEnlace.setTextColor(COLOR_AMARILLO_AVISO)
                    txtFrescura.text = "${e.message}\nPuedes configurar la URL/IP de tu nodo en el botón «Credencial»."
                }
            }
        }

        val bRefrescar = a.botonAncho("Refrescar", a.sup2, a.claro) { cargarDatos() }.apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val bIdentidad = a.botonAncho("Credencial", a.sup2, a.claro) {
            mostrarDialogoIdentidad(a) { cargarDatos() }
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val bBloquear = a.botonAncho("Bloquear", a.sup2, a.gris) {
            a.getSharedPreferences(PREFS_MI_NODO, Context.MODE_PRIVATE).edit().putBoolean(KEY_SESION_ACTIVA, false).apply()
            IdentidadDispositivoMiNodo.bloquearSesion()
            alCambiarEstado()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        filaControles.addView(bRefrescar)
        filaControles.addView(View(a).apply { layoutParams = LinearLayout.LayoutParams(a.dp(6), 1) })
        filaControles.addView(bIdentidad)
        filaControles.addView(View(a).apply { layoutParams = LinearLayout.LayoutParams(a.dp(6), 1) })
        filaControles.addView(bBloquear)

        cardCabecera.addView(filaControles)
        col.addView(cardCabecera)
        col.addView(contenedorSecciones)

        cargarDatos()
        return col
    }

    private fun renderizarSecciones(
        a: X39CallActivity,
        contenedor: LinearLayout,
        txtEstadoEnlace: TextView,
        txtFrescura: TextView,
        e: EstadoMiNodo
    ) {
        contenedor.removeAllViews()

        txtEstadoEnlace.text = if (e.nodoOnline) {
            "● CONECTADO · ${e.viaConexion} (${e.ipRespondio})"
        } else {
            "🟠 NODO BITCOIN FUERA DE SERVICIO · ${e.viaConexion}"
        }
        txtEstadoEnlace.setTextColor(if (e.nodoOnline) COLOR_ORO_ACTIVO else COLOR_ROJO_ALERTA)

        if (e.edadTelemetriaSeg < 0L) {
            txtFrescura.text = "⚠️ FRESCURA DESCONOCIDA: La respuesta no incluye fecha del servidor"
            txtFrescura.setTextColor(COLOR_AMARILLO_AVISO)
        } else if (e.telemetriaDesactualizada) {
            txtFrescura.text = "⚠️ TELEMETRÍA DESACTUALIZADA: Último dato válido hace ${e.edadTelemetriaSeg} s"
            txtFrescura.setTextColor(COLOR_AMARILLO_AVISO)
        } else {
            txtFrescura.text = "✓ Telemetría en tiempo real (hace ${e.edadTelemetriaSeg} s) · Modo estricto SOLO LECTURA"
            txtFrescura.setTextColor(a.gris)
        }

        val cardNodo = a.tarjetaCol()
        cardNodo.addView(encabezadoTarjeta(a, "1. NODO BITCOIN (BITCOIN CORE)", COLOR_AMBAR_BTC))
        val syncPct = if (e.progresoSync.isFinite()) String.format(Locale.US, "%.2f%%", (e.progresoSync * 100.0).coerceIn(0.0, 100.0)) else "Progreso no informado por la API"
        val syncEstado = when {
            e.ibd == true -> "IBD"
            e.sincronizado == true -> "Sincronizado según la API"
            e.sincronizado == false -> "Sincronizando según la API"
            e.ibd == false -> "Fuera de IBD según Bitcoin Core"
            else -> "Estado de sincronización no informado"
        }
        val tempC = e.tempPiCelsius
        val tempStr = if (tempC != null) String.format(Locale.US, "%.1f °C", tempC) else "N/D"

        cardNodo.addView(filaMetrica(a, "Estado Bitcoin Core", if (e.nodoOnline) "ONLINE (${e.chain})" else "OFFLINE", if (e.nodoOnline) COLOR_ORO_ACTIVO else COLOR_ROJO_ALERTA))
        cardNodo.addView(filaMetrica(a, "Altura / Cabeceras", "${fmtEntero.format(e.blocks)} / ${if (e.headers >= 0) fmtEntero.format(e.headers) else "No informadas"}", a.claro))
        cardNodo.addView(filaMetrica(a, "Sincronización", "$syncPct ($syncEstado)", a.claro))
        cardNodo.addView(filaMetrica(a, "Conexiones de pares", "${e.paresTotales} (${e.paresIn} in / ${e.paresOut} out)", a.claro))
        if (tempC != null) {
            cardNodo.addView(filaMetrica(a, "Temperatura Nodo", tempStr, if (tempC > 75.0) COLOR_ROJO_ALERTA else COLOR_ORO_ACTIVO))
        }
        cardNodo.addView(filaMetrica(a, "Servicios (Electrs / CKPool)", "${e.electrsActivo} / ${e.ckpoolServicioActivo}", a.claro))
        if (e.warnings.isNotBlank() && e.warnings != "[]" && e.warnings != "null") {
            cardNodo.addView(filaMetrica(a, "Alertas del nodo", e.warnings, COLOR_AMARILLO_AVISO))
        }
        contenedor.addView(cardNodo)

        val cardMineria = a.tarjetaCol()
        cardMineria.addView(encabezadoTarjeta(a, "2. X39 MINING — CKPOOL SOLO", COLOR_ORO_ACTIVO))
        val totalShares = e.acceptedShares + e.rejectedShares
        val pctAceptado = if (e.contadoresVerificados && totalShares > 0L) {
            String.format(Locale.US, "%.3f%%", (e.acceptedShares.toDouble() * 100.0) / totalShares.toDouble())
        } else {
            "sin porcentaje: unidades no verificadas"
        }

        cardMineria.addView(filaMetrica(a, "Estado servicio CKPool (según API)", e.poolStatus, if (e.poolStatus == "RUNNING") COLOR_ORO_ACTIVO else COLOR_ROJO_ALERTA))
        cardMineria.addView(filaMetrica(a, "Usuarios / Workers activos", "${e.users} usuario(s) · ${e.workers} ONLINE (${e.idle} idle / ${e.disconnected} disc)", a.claro))
        cardMineria.addView(filaMetrica(a, "Cadencia (SPS 1m)", String.format(Locale.US, "%.2f shares/s", e.sps1m), a.claro))
        cardMineria.addView(filaMetrica(a, "Trabajo aceptado", "${fmtEntero.format(e.acceptedShares)} (${if (e.contadoresVerificados) "shares" else "contador CKPool bruto"}; $pctAceptado)", COLOR_ORO_ACTIVO))
        cardMineria.addView(filaMetrica(a, "Trabajo rechazado", "${fmtEntero.format(e.rejectedShares)} (${if (e.contadoresVerificados) "shares" else "contador CKPool bruto"})", if (e.rejectedShares == 0L) a.gris else COLOR_AMARILLO_AVISO))
        cardMineria.addView(filaMetrica(a, "Mejor share (Best Share)", fmtEntero.format(e.bestShare), COLOR_AMBAR_BTC))
        cardMineria.addView(filaMetrica(a, "Dificultad mínima pool", if (e.poolDiffMin > 0L) "${fmtEntero.format(e.poolDiffMin)} (SOLO)" else "No informada por la API", a.claro))

        cardMineria.addView(subtituloSeccionInterna(a, "CURVA DE HASHRATE POR VENTANA TEMPORAL"))
        cardMineria.addView(construirGraficaHashrate(a, e))

        if (e.mineroIndividualEncontrado) {
            cardMineria.addView(subtituloSeccionInterna(a, "MI MINERO INDIVIDUAL (${e.dirMinero99Pct.take(14)}…)"))
            val cajaMiMinero = cajaSubtarjeta(a)
            cajaMiMinero.addView(filaMetrica(a, "Hashrate (1m / 5m / 1h)", "${e.mineroHr1m} / ${e.mineroHr5m} / ${e.mineroHr1h}", COLOR_ORO_ACTIVO))
            cajaMiMinero.addView(filaMetrica(a, "Hashrate (1d / 7d)", "${e.mineroHr1d} / ${e.mineroHr7d}", a.claro))
            cajaMiMinero.addView(filaMetrica(a, "Workers conectados", "${e.mineroWorkers} ONLINE", COLOR_ORO_ACTIVO))
            cajaMiMinero.addView(filaMetrica(a, "Mejor share individual", fmtEntero.format(e.mineroBestShare), COLOR_AMBAR_BTC))
            cajaMiMinero.addView(filaMetrica(a, "Dificultad aceptada", fmtEntero.format(e.mineroAcceptedDiff), a.claro))
            cardMineria.addView(cajaMiMinero)
        }

        cardMineria.addView(subtituloSeccionInterna(a, "RECOMPENSAS CONFIRMADA VS. POTENCIAL (REPARTO 99% / 1%)"))
        val cajaPremios = cajaSubtarjeta(a)
        cajaPremios.addView(filaMetrica(
            a,
            "Bloques ganados confirmados",
            "${e.bloquesGanadosConfirmados} bloques (${if (e.bloquesGanadosConfirmados == 0) "0 BTC ganados" else "Ver billetera"})",
            if (e.bloquesGanadosConfirmados > 0) COLOR_ORO_ACTIVO else a.gris
        ))
        cajaPremios.addView(filaMetrica(
            a,
            "Premio POTENCIAL bloque actual",
            if (e.premioPotencialTotalSats > 0L) "${fmtEntero.format(e.premioPotencialTotalSats)} sat (${satsABtc(e.premioPotencialTotalSats)} · incluye ${fmtEntero.format(e.premioPotencialFeesSats)} sat fees)" else "No informado por la API",
            COLOR_AMBAR_BTC
        ))
        cajaPremios.addView(filaMetrica(
            a,
            "  ↳ 99% Minero (potencial)",
            if (e.premioPotencialTotalSats > 0L) "${fmtEntero.format(e.premioPotencialMinero99Sats)} sat" else "No informado por la API",
            a.claro
        ))
        cajaPremios.addView(filaMetrica(
            a,
            "  ↳ 1% Comisión Pool (potencial)",
            if (e.premioPotencialTotalSats > 0L) "${fmtEntero.format(e.premioPotencialOperador1Sats)} sat" else "No informado por la API",
            COLOR_ORO_ACTIVO
        ))
        cardMineria.addView(cajaPremios)
        contenedor.addView(cardMineria)

        val cardWallet = a.tarjetaCol()
        cardWallet.addView(encabezadoTarjeta(a, "3. BILLETERA DE NODO ('${e.walletName}') — SOLO LECTURA", COLOR_AMBAR_BTC))
        cardWallet.addView(filaMetrica(
            a,
            "Saldo maduro y disponible",
            "${fmtEntero.format(e.saldoConfirmadoMaduroSats)} sat (${satsABtc(e.saldoConfirmadoMaduroSats)})",
            COLOR_ORO_ACTIVO
        ))
        cardWallet.addView(filaMetrica(
            a,
            "Recompensa coinbase madurando",
            "${fmtEntero.format(e.saldoInmaduroCoinbaseSats)} sat (Exige 100 conf)",
            if (e.saldoInmaduroCoinbaseSats > 0L) COLOR_AMBAR_BTC else a.gris
        ))
        cardWallet.addView(filaMetrica(
            a,
            "Saldo pendiente (0-5 conf)",
            "${fmtEntero.format(e.saldoPendienteSats)} sat",
            if (e.saldoPendienteSats > 0L) COLOR_AMARILLO_AVISO else a.gris
        ))

        cardWallet.addView(subtituloSeccionInterna(a, "DIRECCIONES DE MINERÍA CONFIGURADAS"))
        val cajaDirs = cajaSubtarjeta(a)
        cajaDirs.addView(textoMonoespaciado(a, "Minero (99%): ${e.dirMinero99Pct}", a.claro))
        cajaDirs.addView(textoMonoespaciado(a, "Pool (1%): ${e.dirOperador1Pct}", COLOR_ORO_ACTIVO))
        cardWallet.addView(cajaDirs)

        cardWallet.addView(subtituloSeccionInterna(a, "MOVIMIENTOS Y MADURACIÓN DE RECOMPENSAS"))
        val cajaMovs = cajaSubtarjeta(a)
        if (e.movimientos.isEmpty()) {
            cajaMovs.addView(TextView(a).apply {
                text = "Sin movimientos en modo público o en la cartera '${e.walletName}'."
                textSize = 11f
                setTextColor(a.gris)
            })
        } else {
            for (mov in e.movimientos) {
                cajaMovs.addView(filaMetrica(
                    a,
                    "${mov.categoria.uppercase(Locale.ROOT)} ${if (mov.txidCorto.isNotEmpty()) "(${mov.txidCorto}…)" else ""}",
                    "${fmtEntero.format(mov.montoSats)} sat · ${mov.estadoMadurez}",
                    if (mov.confirmaciones >= 100 || (mov.categoria == "receive" && mov.confirmaciones >= 6)) COLOR_ORO_ACTIVO else COLOR_AMBAR_BTC
                ))
            }
        }
        cardWallet.addView(cajaMovs)

        cardWallet.addView(TextView(a).apply {
            text = "🛡️ Panel blindado en modo SOLO LECTURA: sin claves privadas, sin semillas y sin funciones de firma o gasto."
            textSize = 10f
            setTextColor(a.tenue)
            setPadding(0, a.dp(8), 0, 0)
        })
        contenedor.addView(cardWallet)
    }

    private fun construirGraficaHashrate(a: X39CallActivity, e: EstadoMiNodo): View {
        val caja = cajaSubtarjeta(a)
        val tramos: List<Pair<String, String>> = listOf(
            Pair("1m", e.hr1m),
            Pair("5m", e.hr5m),
            Pair("15m", e.hr15m),
            Pair("1h", e.hr1h),
            Pair("6h", e.hr6h),
            Pair("1d", e.hr1d),
            Pair("7d", e.hr7d)
        )
        val valoresTh: List<Double> = tramos.map { par -> TelemetriaPi5.hashrateATerahashes(par.second) }
        val maxTh = (valoresTh.maxOrNull() ?: 0.0).coerceAtLeast(1.0)

        for (idx in tramos.indices) {
            val etiqueta = tramos[idx].first
            val textoHr = tramos[idx].second
            val valorActual = valoresTh[idx]

            val fila = LinearLayout(a).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, a.dp(3), 0, a.dp(3))
            }
            fila.addView(TextView(a).apply {
                text = etiqueta
                textSize = 11f
                setTextColor(a.gris)
                layoutParams = LinearLayout.LayoutParams(a.dp(34), ViewGroup.LayoutParams.WRAP_CONTENT)
            })
            val barra = ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 1000
                progress = if (valorActual <= 0.0) 0 else ((valorActual / maxTh) * 1000.0).toInt().coerceIn(25, 1000)
                layoutParams = LinearLayout.LayoutParams(0, a.dp(8), 1f).apply {
                    marginStart = a.dp(6)
                    marginEnd = a.dp(8)
                }
            }
            fila.addView(barra)
            fila.addView(TextView(a).apply {
                text = textoHr
                textSize = 11f
                setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
                setTextColor(if (valorActual > 0.0) COLOR_ORO_ACTIVO else a.gris)
                gravity = Gravity.END
                layoutParams = LinearLayout.LayoutParams(a.dp(62), ViewGroup.LayoutParams.WRAP_CONTENT)
            })
            caja.addView(fila)
        }
        return caja
    }

    private fun solicitarAutenticacionPropietario(a: X39CallActivity, onSuccess: () -> Unit) {
        AlertDialog.Builder(a)
            .setTitle("Desbloquear Panel de Nodo")
            .setMessage(
                "Se verificará en vivo la clave local del dispositivo (Android Keystore ECDSA P-256):\n" +
                    "ID: ${IdentidadDispositivoMiNodo.deviceId()}\n\n" +
                    "¿Firmar reto local y abrir sesión de solo lectura (24 h)?"
            )
            .setPositiveButton("Verificar y Desbloquear") { _, _ ->
                try {
                    IdentidadDispositivoMiNodo.cabecerasFirmadas("/auth/local")
                    onSuccess()
                } catch (e: Exception) {
                    a.toast("No se pudo verificar la clave del Keystore: ${e.message}")
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun mostrarDialogoIdentidad(a: X39CallActivity, onSaved: (() -> Unit)? = null) {
        val devId = IdentidadDispositivoMiNodo.deviceId()
        val pubKey = IdentidadDispositivoMiNodo.clavePublicaBase64()

        val inputEndpoint = EditText(a).apply {
            hint = "URL o IP:puerto del nodo (vacío = pool público HTTPS)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setText(obtenerEndpointPersonalizado(a))
        }
        val inputMiner = EditText(a).apply {
            hint = "Tu dirección Bitcoin de minero (bc1q… opcional)"
            inputType = InputType.TYPE_CLASS_TEXT
            setText(obtenerDireccionMinero(a))
        }
        val inputToken = EditText(a).apply {
            hint = "Token de lectura de tu nodo privado (opcional)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(obtenerTokenLecturaOpcional(a))
        }

        val layout = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(a.dp(18), a.dp(12), a.dp(18), a.dp(4))
            addView(TextView(a).apply {
                text = "ID de Dispositivo (SHA-256 Keystore):\n$devId\n\n" +
                    "Clave Pública ECDSA P-256 (Base64):\n${pubKey.take(44)}…\n\n" +
                    "Configura tu propio nodo/API privado o déjalo vacío para consultar el pool público (${TelemetriaPi5.URL_PUBLICA_DEFECTO}):"
                textSize = 11f
            })
            addView(inputEndpoint)
            addView(inputMiner)
            addView(inputToken)
        }

        AlertDialog.Builder(a)
            .setTitle("Configurar Nodo, Minero y Credencial")
            .setView(layout)
            .setPositiveButton("Guardar") { _, _ ->
                guardarConfiguracionPanel(
                    a,
                    inputEndpoint.text.toString().trim(),
                    inputMiner.text.toString().trim(),
                    inputToken.text.toString().trim()
                )
                ultimoEstadoCache = null
                a.toast("Configuración de nodo y minero actualizada")
                onSaved?.invoke()
            }
            .setNeutralButton("Copiar JSON de alta") { _, _ ->
                val jsonAlta = """{"device_id":"$devId","public_key":"$pubKey"}"""
                val cm = a.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("x39_device_auth", jsonAlta))
                a.toast("JSON de autorización copiado al portapapeles")
            }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    private const val BOVEDA_TOKEN_NODO = "mi_nodo_read_token"

    private fun obtenerTokenLecturaOpcional(context: Context): String {
        Boveda.abrir(context, BOVEDA_TOKEN_NODO)?.let {
            return String(it, Charsets.UTF_8)
        }
        val sp = context.getSharedPreferences(PREFS_MI_NODO, Context.MODE_PRIVATE)
        val legado = sp.getString(KEY_READ_TOKEN, "") ?: ""
        if (legado.isNotEmpty()) {
            Boveda.sellar(context, BOVEDA_TOKEN_NODO, legado.toByteArray(Charsets.UTF_8))
            sp.edit().remove(KEY_READ_TOKEN).apply()
        }
        return legado
    }

    private fun obtenerEndpointPersonalizado(context: Context): String =
        context.getSharedPreferences(PREFS_MI_NODO, Context.MODE_PRIVATE)
            .getString(KEY_NODE_ENDPOINT, "") ?: ""

    private fun obtenerDireccionMinero(context: Context): String =
        context.getSharedPreferences(PREFS_MI_NODO, Context.MODE_PRIVATE)
            .getString(KEY_MINER_ADDRESS, "") ?: ""

    private fun guardarConfiguracionPanel(context: Context, endpoint: String, minerAddr: String, token: String) {
        context.getSharedPreferences(PREFS_MI_NODO, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_NODE_ENDPOINT, endpoint)
            .putString(KEY_MINER_ADDRESS, minerAddr)
            .remove(KEY_READ_TOKEN)
            .apply()
        if (token.isEmpty()) {
            Boveda.borrar(context, BOVEDA_TOKEN_NODO)
        } else {
            Boveda.sellar(context, BOVEDA_TOKEN_NODO, token.toByteArray(Charsets.UTF_8))
        }
    }

    private fun encabezadoTarjeta(a: X39CallActivity, titulo: String, color: Int): View =
        TextView(a).apply {
            text = titulo
            textSize = 11f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(color)
            setPadding(0, 0, 0, a.dp(6))
        }

    private fun subtituloSeccionInterna(a: X39CallActivity, texto: String): View =
        TextView(a).apply {
            text = texto
            textSize = 10f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(a.gris)
            setPadding(0, a.dp(10), 0, a.dp(4))
        }

    private fun filaMetrica(a: X39CallActivity, etiqueta: String, valor: String, colorValor: Int): View =
        LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, a.dp(3), 0, a.dp(3))
            addView(TextView(a).apply {
                text = etiqueta
                textSize = 11f
                setTextColor(a.gris)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.46f)
            })
            addView(TextView(a).apply {
                text = valor
                textSize = 11f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(colorValor)
                gravity = Gravity.END
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.54f)
            })
        }

    private fun cajaSubtarjeta(a: X39CallActivity): LinearLayout =
        LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            val pad = a.dp(10)
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                setColor(COLOR_FONDO_SUBTARJETA)
                cornerRadius = a.dp(8).toFloat()
                setStroke(1, COLOR_BORDE_SUTIL)
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

    private fun textoMonoespaciado(a: X39CallActivity, contenido: String, color: Int): View =
        TextView(a).apply {
            text = contenido
            textSize = 10f
            typeface = Typeface.MONOSPACE
            setTextColor(color)
            setPadding(0, a.dp(2), 0, a.dp(2))
        }

    private fun satsABtc(sats: Long): String =
        String.format(Locale.US, "%.8f BTC", sats.toDouble() / 100_000_000.0)
}
