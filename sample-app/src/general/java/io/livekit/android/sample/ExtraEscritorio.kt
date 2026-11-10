package io.livekit.android.sample

import android.app.AlertDialog
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.NumberFormat
import java.util.Locale

internal data class EstadoPanelPublico(
    val origenRespondio: String,
    val edadSeg: Long,
    val desactualizado: Boolean,
    val nodoOnline: Boolean,
    val chain: String,
    val blocks: Long,
    val paresTotales: Int,
    val warnings: String,
    val poolOnline: Boolean,
    val users: Int,
    val workers: Int,
    val idle: Int,
    val disconnected: Int,
    val hr1m: String,
    val hr5m: String,
    val hr15m: String,
    val hr1h: String,
    val hr6h: String,
    val hr1d: String,
    val hr7d: String,
    val sps1m: Double,
    val acceptedShares: Long,
    val rejectedShares: Long,
    val bestShare: Long,
    val feeVerified: Boolean,
    val feeReason: String,
    val poolFeePct: Int,
    val stratumUrl: String,
    val stratumVerified: Boolean,
    val dirMineroConfig: String,
    val mineroEncontrado: Boolean,
    val mineroHr1m: String,
    val mineroHr5m: String,
    val mineroHr1h: String,
    val mineroHr1d: String,
    val mineroHr7d: String,
    val mineroWorkers: Int,
    val mineroBestShare: Long,
    val mineroAcceptedDiff: Long
)

internal object ExtraEscritorio {
    private const val PREFS_PANEL = "x39_panel_mineria_prefs"
    private const val KEY_ENDPOINT_HTTPS = "custom_https_pool_url"
    private const val KEY_MINER_ADDR = "custom_miner_btc_address"
    private const val URL_DEFECTO = "https://mining.x39matrix.org"
    private const val RUTA_STATS = "/api/public/stats"
    private const val RUTA_MINER = "/api/public/miner"

    @Volatile private var cache: EstadoPanelPublico? = null

    private val COLOR_AMBAR_BTC = Color.parseColor("#F7931A")
    private val COLOR_ORO_ACTIVO = Color.parseColor("#F7931A")
    private val COLOR_ROJO_ALERTA = Color.parseColor("#FF1E1E")
    private val COLOR_AMARILLO_AVISO = Color.parseColor("#FFB703")
    private val COLOR_FONDO_SUBTARJETA = Color.parseColor("#090A0D")
    private val COLOR_BORDE_SUTIL = Color.parseColor("#492327")

    private val fmtEntero = NumberFormat.getIntegerInstance(Locale("es", "ES"))

    fun tarjetaExtra(a: X39CallActivity): View? {
        val col = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        col.addView(a.tituloSeccion("⚡ NODO BITCOIN & POOL DE MINERÍA SOLO"))

        val cardCabecera = a.tarjetaCol()
        val txtEstado = TextView(a).apply {
            text = "Consultando estado del nodo y pool por HTTPS…"
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(a.claro)
        }
        val txtSub = TextView(a).apply {
            text = "Modo público de solo lectura · Configurable por el usuario"
            textSize = 11f
            setTextColor(a.gris)
            setPadding(0, a.dp(4), 0, a.dp(8))
        }
        cardCabecera.addView(txtEstado)
        cardCabecera.addView(txtSub)

        val filaBotones = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val contenedorDatos = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        cache?.let { pintarSecciones(a, contenedorDatos, txtEstado, txtSub, it) }

        fun recargar() {
            a.lifecycleScope.launch {
                if (cache == null) {
                    txtEstado.text = "⏳ Sincronizando métricas de nodo y minería…"
                    txtEstado.setTextColor(a.claro)
                }
                try {
                    val base = leerEndpoint(a)
                    val minero = leerMinero(a)
                    val est = consultarHttps(base, minero)
                    cache = est
                    pintarSecciones(a, contenedorDatos, txtEstado, txtSub, est)
                } catch (e: Exception) {
                    if (cache == null) contenedorDatos.removeAllViews()
                    txtEstado.text = "⚠️ SIN CONEXIÓN CON EL SERVIDOR DE ESTADÍSTICAS"
                    txtEstado.setTextColor(COLOR_AMARILLO_AVISO)
                    txtSub.text = "${e.message ?: "Error de red"}\nRevisa la URL HTTPS en «Configurar»."
                }
            }
        }

        val bRefrescar = a.botonAncho("Refrescar", a.sup2, a.claro) { recargar() }.apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val bConfigurar = a.botonAncho("Configurar", a.sup2, a.claro) {
            mostrarDialogoConfig(a) { recargar() }
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        filaBotones.addView(bRefrescar)
        filaBotones.addView(View(a).apply { layoutParams = LinearLayout.LayoutParams(a.dp(8), 1) })
        filaBotones.addView(bConfigurar)

        cardCabecera.addView(filaBotones)
        col.addView(cardCabecera)
        col.addView(contenedorDatos)

        recargar()
        return col
    }

    private suspend fun consultarHttps(endpointUsuario: String, dirMinero: String): EstadoPanelPublico =
        withContext(Dispatchers.IO) {
            val limpia = endpointUsuario.trim().trimEnd('/')
            val base = when {
                limpia.isEmpty() -> URL_DEFECTO
                limpia.startsWith("https://") -> limpia
                else -> "https://$limpia"
            }
            val urlStats = if (base.endsWith(RUTA_STATS)) base else "$base$RUTA_STATS"
            val root = getJsonHttps(urlStats)

            var minerRoot: JSONObject? = null
            val mineroLimpio = dirMinero.trim()
            if (mineroLimpio.isNotEmpty()) {
                try {
                    val baseSinStats = base.removeSuffix(RUTA_STATS)
                    val urlMiner = "$baseSinStats$RUTA_MINER?address=${URLEncoder.encode(mineroLimpio, "UTF-8")}"
                    minerRoot = getJsonHttps(urlMiner)
                } catch (_: Exception) {}
            }

            val meta = root.optJSONObject("meta") ?: JSONObject()
            val node = root.optJSONObject("node") ?: JSONObject()
            val pool = root.optJSONObject("pool") ?: JSONObject()
            val conn = root.optJSONObject("connection") ?: JSONObject()
            val hrObj = pool.optJSONObject("hashrate") ?: JSONObject()
            val shObj = pool.optJSONObject("shares") ?: JSONObject()

            var mEncontrado = false
            var m1m = "0"
            var m5m = "0"
            var m1h = "0"
            var m1d = "0"
            var m7d = "0"
            var mWorkers = 0
            var mBest = 0L
            var mAcc = 0L

            if (minerRoot != null && minerRoot.optBoolean("found", false)) {
                mEncontrado = true
                val mObj = minerRoot.optJSONObject("miner") ?: JSONObject()
                val mHr = mObj.optJSONObject("hashrate") ?: JSONObject()
                m1m = mHr.optJSONObject("m1")?.optString("display", "0") ?: "0"
                m5m = mHr.optJSONObject("m5")?.optString("display", "0") ?: "0"
                m1h = mHr.optJSONObject("h1")?.optString("display", "0") ?: "0"
                m1d = mHr.optJSONObject("d1")?.optString("display", "0") ?: "0"
                m7d = mHr.optJSONObject("d7")?.optString("display", "0") ?: "0"
                mWorkers = mObj.optInt("workersConnected", 0)
                mBest = mObj.optLong("bestShareEver", 0L)
                mAcc = mObj.optLong("acceptedDifficulty", 0L)
            }

            EstadoPanelPublico(
                origenRespondio = base.removePrefix("https://"),
                edadSeg = pool.optLong("dataAgeSeconds", meta.optLong("lastSuccessAgeSeconds", 0L)),
                desactualizado = meta.optBoolean("stale", false),
                nodoOnline = node.optBoolean("online", false),
                chain = node.optString("chain", "main").uppercase(Locale.ROOT),
                blocks = node.optLong("blocks", 0L),
                paresTotales = node.optInt("connections", 0),
                warnings = node.optString("warnings", ""),
                poolOnline = pool.optString("status", "").equals("online", ignoreCase = true),
                users = pool.optInt("users", 0),
                workers = pool.optInt("workers", 0),
                idle = pool.optInt("idle", 0),
                disconnected = pool.optInt("disconnected", 0),
                hr1m = hrObj.optJSONObject("m1")?.optString("display", "0") ?: "0",
                hr5m = hrObj.optJSONObject("m5")?.optString("display", "0") ?: "0",
                hr15m = hrObj.optJSONObject("m15")?.optString("display", "0") ?: "0",
                hr1h = hrObj.optJSONObject("h1")?.optString("display", "0") ?: "0",
                hr6h = hrObj.optJSONObject("h6")?.optString("display", "0") ?: "0",
                hr1d = hrObj.optJSONObject("d1")?.optString("display", "0") ?: "0",
                hr7d = hrObj.optJSONObject("d7")?.optString("display", "0") ?: "0",
                sps1m = shObj.optDouble("sps1m", 0.0),
                acceptedShares = shObj.optLong("accepted", 0L),
                rejectedShares = shObj.optLong("rejected", 0L),
                bestShare = shObj.optLong("bestShare", 0L),
                feeVerified = conn.optBoolean("feeVerified", true),
                feeReason = conn.optJSONObject("feeVerification")?.optString("reason", "") ?: "",
                poolFeePct = conn.optInt("poolFeePercent", 1),
                stratumUrl = conn.optString("stratum", "").takeIf { it != "null" } ?: "",
                stratumVerified = conn.optBoolean("stratumVerified", false),
                dirMineroConfig = mineroLimpio,
                mineroEncontrado = mEncontrado,
                mineroHr1m = m1m,
                mineroHr5m = m5m,
                mineroHr1h = m1h,
                mineroHr1d = m1d,
                mineroHr7d = m7d,
                mineroWorkers = mWorkers,
                mineroBestShare = mBest,
                mineroAcceptedDiff = mAcc
            )
        }

    private fun getJsonHttps(urlStr: String): JSONObject {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 4500
            readTimeout = 5000
        }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val texto = stream?.let { BufferedReader(InputStreamReader(it)).use { r -> r.readText() } } ?: ""
        if (code !in 200..299) throw RuntimeException("HTTP $code")
        return JSONObject(texto)
    }

    private fun pintarSecciones(
        a: X39CallActivity,
        contenedor: LinearLayout,
        txtEstado: TextView,
        txtSub: TextView,
        e: EstadoPanelPublico
    ) {
        contenedor.removeAllViews()

        txtEstado.text = if (e.nodoOnline) {
            "● CONECTADO · API HTTPS (${e.origenRespondio})"
        } else {
            "🟠 NODO FUERA DE SERVICIO · ${e.origenRespondio}"
        }
        txtEstado.setTextColor(if (e.nodoOnline) COLOR_ORO_ACTIVO else COLOR_ROJO_ALERTA)

        txtSub.text = if (e.desactualizado) {
            "⚠️ Datos con ${e.edadSeg} s de antigüedad"
        } else {
            "✓ Estadísticas en tiempo real (hace ${e.edadSeg} s) · SOLO LECTURA"
        }

        val cardNodo = a.tarjetaCol()
        cardNodo.addView(encabezado(a, "1. NODO BITCOIN (BITCOIN CORE)", COLOR_AMBAR_BTC))
        cardNodo.addView(fila(a, "Estado Bitcoin Core", if (e.nodoOnline) "ONLINE (${e.chain})" else "OFFLINE", if (e.nodoOnline) COLOR_ORO_ACTIVO else COLOR_ROJO_ALERTA))
        cardNodo.addView(fila(a, "Altura de bloques", fmtEntero.format(e.blocks), a.claro))
        cardNodo.addView(fila(a, "Conexiones de pares", "${e.paresTotales} pares activos", a.claro))
        if (e.warnings.isNotBlank() && e.warnings != "null") {
            cardNodo.addView(fila(a, "Avisos", e.warnings, COLOR_AMARILLO_AVISO))
        }
        contenedor.addView(cardNodo)

        val cardPool = a.tarjetaCol()
        cardPool.addView(encabezado(a, "2. POOL DE MINERÍA BITCOIN SOLO", COLOR_ORO_ACTIVO))
        val totalSh = e.acceptedShares + e.rejectedShares
        val pctOk = if (totalSh > 0L) String.format(Locale.US, "%.3f%%", (e.acceptedShares * 100.0) / totalSh.toDouble()) else "100.0%"

        cardPool.addView(fila(a, "Estado del Pool", if (e.poolOnline) "ONLINE" else "OFFLINE", if (e.poolOnline) COLOR_ORO_ACTIVO else COLOR_ROJO_ALERTA))
        cardPool.addView(fila(a, "Usuarios / Workers", "${e.users} usuario(s) · ${e.workers} ONLINE (${e.idle} idle / ${e.disconnected} disc)", a.claro))
        cardPool.addView(fila(a, "Cadencia (SPS 1m)", String.format(Locale.US, "%.2f shares/s", e.sps1m), a.claro))
        cardPool.addView(fila(a, "Trabajo aceptado", "${fmtEntero.format(e.acceptedShares)} ($pctOk)", COLOR_ORO_ACTIVO))
        cardPool.addView(fila(a, "Trabajo rechazado", fmtEntero.format(e.rejectedShares), if (e.rejectedShares == 0L) a.gris else COLOR_AMARILLO_AVISO))
        cardPool.addView(fila(a, "Mejor share (Best Share)", fmtEntero.format(e.bestShare), COLOR_AMBAR_BTC))
        cardPool.addView(fila(a, "Comisión del pool", "${e.poolFeePct}% SOLO (${if (e.feeVerified) "✓ Verificada en bloque" else "Sin verificar"})", if (e.feeVerified) COLOR_ORO_ACTIVO else COLOR_AMARILLO_AVISO))
        if (e.stratumUrl.isNotEmpty()) {
            cardPool.addView(fila(a, "Puerto Stratum", e.stratumUrl.removePrefix("stratum+tcp://"), if (e.stratumVerified) COLOR_ORO_ACTIVO else a.claro))
            cardPool.addView(a.botonAncho("Copiar Stratum (${e.stratumUrl.removePrefix("stratum+tcp://")})", a.sup2, a.claro) {
                val cm = a.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("x39_stratum", e.stratumUrl))
                a.toast("Stratum copiado: ${e.stratumUrl}")
            }.apply {
                (layoutParams as LinearLayout.LayoutParams).topMargin = a.dp(6)
            })
        }

        cardPool.addView(subtitulo(a, "CURVA DE HASHRATE POR VENTANA TEMPORAL"))
        cardPool.addView(graficaHashrate(a, e))

        if (e.mineroEncontrado) {
            cardPool.addView(subtitulo(a, "MI MINERO INDIVIDUAL (${e.dirMineroConfig.take(14)}…)"))
            val cajaMinero = subtarjeta(a)
            cajaMinero.addView(fila(a, "Hashrate (1m / 5m / 1h)", "${e.mineroHr1m} / ${e.mineroHr5m} / ${e.mineroHr1h}", COLOR_ORO_ACTIVO))
            cajaMinero.addView(fila(a, "Hashrate (1d / 7d)", "${e.mineroHr1d} / ${e.mineroHr7d}", a.claro))
            cajaMinero.addView(fila(a, "Workers activos", "${e.mineroWorkers} ONLINE", COLOR_ORO_ACTIVO))
            cajaMinero.addView(fila(a, "Mejor share individual", fmtEntero.format(e.mineroBestShare), COLOR_AMBAR_BTC))
            cajaMinero.addView(fila(a, "Dificultad aceptada", fmtEntero.format(e.mineroAcceptedDiff), a.claro))
            cardPool.addView(cajaMinero)
        } else if (e.dirMineroConfig.isNotEmpty()) {
            cardPool.addView(subtitulo(a, "MI MINERO INDIVIDUAL"))
            val cajaAviso = subtarjeta(a)
            cajaAviso.addView(TextView(a).apply {
                text = "Sin actividad reciente para ${e.dirMineroConfig.take(18)}…"
                textSize = 11f
                setTextColor(a.gris)
            })
            cardPool.addView(cajaAviso)
        }
        contenedor.addView(cardPool)
    }

    private fun graficaHashrate(a: X39CallActivity, e: EstadoPanelPublico): View {
        val caja = subtarjeta(a)
        val tramos = listOf(
            "1m" to e.hr1m,
            "5m" to e.hr5m,
            "15m" to e.hr15m,
            "1h" to e.hr1h,
            "6h" to e.hr6h,
            "1d" to e.hr1d,
            "7d" to e.hr7d
        )
        val valoresTh = tramos.map { aTerahashes(it.second) }
        val maxTh = (valoresTh.maxOrNull() ?: 0.0).coerceAtLeast(1.0)

        for (i in tramos.indices) {
            val (etiqueta, textoHr) = tramos[i]
            val valor = valoresTh[i]
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
            fila.addView(ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 1000
                progress = if (valor <= 0.0) 0 else ((valor / maxTh) * 1000.0).toInt().coerceIn(25, 1000)
                layoutParams = LinearLayout.LayoutParams(0, a.dp(8), 1f).apply {
                    marginStart = a.dp(6)
                    marginEnd = a.dp(8)
                }
            })
            fila.addView(TextView(a).apply {
                text = textoHr
                textSize = 11f
                setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
                setTextColor(if (valor > 0.0) COLOR_ORO_ACTIVO else a.gris)
                gravity = Gravity.END
                layoutParams = LinearLayout.LayoutParams(a.dp(62), ViewGroup.LayoutParams.WRAP_CONTENT)
            })
            caja.addView(fila)
        }
        return caja
    }

    private fun mostrarDialogoConfig(a: X39CallActivity, alGuardar: () -> Unit) {
        val inputUrl = EditText(a).apply {
            hint = "Servidor HTTPS (vacío = $URL_DEFECTO)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setText(leerEndpoint(a))
        }
        val inputMiner = EditText(a).apply {
            hint = "Dirección Bitcoin de minero (bc1q… opcional)"
            inputType = InputType.TYPE_CLASS_TEXT
            setText(leerMinero(a))
        }
        val layout = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(a.dp(18), a.dp(12), a.dp(18), a.dp(4))
            addView(TextView(a).apply {
                text = "Introduce la URL HTTPS del pool de minería (déjalo vacío para usar $URL_DEFECTO) y opcionalmente tu dirección Bitcoin para consultar tus workers:"
                textSize = 11f
            })
            addView(inputUrl)
            addView(inputMiner)
        }
        AlertDialog.Builder(a)
            .setTitle("Configurar Pool y Dirección de Minero")
            .setView(layout)
            .setPositiveButton("Guardar") { _, _ ->
                a.getSharedPreferences(PREFS_PANEL, Context.MODE_PRIVATE).edit()
                    .putString(KEY_ENDPOINT_HTTPS, inputUrl.text.toString().trim())
                    .putString(KEY_MINER_ADDR, inputMiner.text.toString().trim())
                    .apply()
                cache = null
                a.toast("Configuración guardada")
                alGuardar()
            }
            .setNeutralButton("Restablecer") { _, _ ->
                a.getSharedPreferences(PREFS_PANEL, Context.MODE_PRIVATE).edit().clear().apply()
                cache = null
                alGuardar()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun leerEndpoint(c: Context): String =
        c.getSharedPreferences(PREFS_PANEL, Context.MODE_PRIVATE).getString(KEY_ENDPOINT_HTTPS, "") ?: ""

    private fun leerMinero(c: Context): String =
        c.getSharedPreferences(PREFS_PANEL, Context.MODE_PRIVATE).getString(KEY_MINER_ADDR, "") ?: ""

    private fun encabezado(a: X39CallActivity, t: String, color: Int): View =
        TextView(a).apply {
            text = t; textSize = 11f; setTypeface(typeface, Typeface.BOLD); setTextColor(color)
            setPadding(0, 0, 0, a.dp(6))
        }

    private fun subtitulo(a: X39CallActivity, t: String): View =
        TextView(a).apply {
            text = t; textSize = 10f; setTypeface(typeface, Typeface.BOLD); setTextColor(a.gris)
            setPadding(0, a.dp(10), 0, a.dp(4))
        }

    private fun fila(a: X39CallActivity, etq: String, valor: String, colorValor: Int): View =
        LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, a.dp(3), 0, a.dp(3))
            addView(TextView(a).apply {
                text = etq; textSize = 11f; setTextColor(a.gris)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.46f)
            })
            addView(TextView(a).apply {
                text = valor; textSize = 11f; setTypeface(typeface, Typeface.BOLD); setTextColor(colorValor)
                gravity = Gravity.END
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.54f)
            })
        }

    private fun subtarjeta(a: X39CallActivity): LinearLayout =
        LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            val pad = a.dp(10)
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                setColor(COLOR_FONDO_SUBTARJETA)
                cornerRadius = a.dp(8).toFloat()
                setStroke(1, COLOR_BORDE_SUTIL)
            }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

    private fun aTerahashes(hr: String): Double {
        val limpio = hr.trim().uppercase(Locale.ROOT).replace(",", ".")
        if (limpio.isEmpty() || limpio == "0") return 0.0
        val mult = when {
            limpio.endsWith("E") -> 1_000_000.0
            limpio.endsWith("P") -> 1_000.0
            limpio.endsWith("T") -> 1.0
            limpio.endsWith("G") -> 0.001
            limpio.endsWith("M") -> 0.000_001
            limpio.endsWith("K") -> 0.000_000_001
            else -> 0.000_000_000_001
        }
        val numStr = limpio.dropLastWhile { !it.isDigit() && it != '.' }
        return (numStr.toDoubleOrNull() ?: 0.0) * mult
    }
}
