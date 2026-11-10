package io.livekit.android.sample

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import x39.wallet.android.NodoX39
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

internal data class EstadoMiNodo(
    val viaConexion: String,
    val ipRespondio: String,
    val timestampServidorSeg: Long,
    val edadTelemetriaSeg: Long,
    val telemetriaDesactualizada: Boolean,
    val nodoOnline: Boolean,
    val chain: String,
    val blocks: Long,
    val headers: Long,
    val progresoSync: Double,
    val ibd: Boolean?,
    val sincronizado: Boolean?,
    val contadoresVerificados: Boolean,
    val paresTotales: Int,
    val paresIn: Int,
    val paresOut: Int,
    val electrsActivo: String,
    val ckpoolServicioActivo: String,
    val tempPiCelsius: Double?,
    val warnings: String,
    val poolStatus: String,
    val runtimeSeg: Long,
    val lastUpdatePoolSeg: Long,
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
    val poolDiffMin: Long,
    val netDiff: Double,
    val bloquesGanadosConfirmados: Int,
    val premioPotencialTotalSats: Long,
    val premioPotencialFeesSats: Long,
    val premioPotencialMinero99Sats: Long,
    val premioPotencialOperador1Sats: Long,
    val walletName: String,
    val dirOperador1Pct: String,
    val dirMinero99Pct: String,
    val saldoConfirmadoMaduroSats: Long,
    val saldoInmaduroCoinbaseSats: Long,
    val saldoPendienteSats: Long,
    val movimientos: List<MovimientoLectura>,
    val mineroIndividualEncontrado: Boolean = false,
    val mineroHr1m: String = "0",
    val mineroHr5m: String = "0",
    val mineroHr1h: String = "0",
    val mineroHr1d: String = "0",
    val mineroHr7d: String = "0",
    val mineroWorkers: Int = 0,
    val mineroBestShare: Long = 0L,
    val mineroAcceptedDiff: Long = 0L
)

internal data class MovimientoLectura(
    val categoria: String,
    val montoSats: Long,
    val confirmaciones: Int,
    val estadoMadurez: String,
    val txidCorto: String
)

internal object TelemetriaPi5 {
    const val URL_PUBLICA_DEFECTO = "https://mining.x39matrix.org"
    const val IP_VPN = NodoX39.HOST
    private const val PUERTO_API_DEFECTO = 8000
    private const val RUTA_TELEMETRIA_PRIVADA = "/api/v1/telemetry"
    private const val RUTA_STATS_PUBLICA = "/api/public/stats"
    private const val RUTA_MINER_PUBLICA = "/api/public/miner"
    private const val UMBRAL_OBSOLETO_SEG = 90L

    internal fun normalizarBaseUrl(entrada: String): String {
        val limpia = entrada.trim().trimEnd('/')
        if (limpia.isEmpty()) return URL_PUBLICA_DEFECTO
        val conEsquema = when {
            limpia.lowercase(Locale.ROOT).startsWith("http://") -> "http://" + limpia.substring(7)
            limpia.lowercase(Locale.ROOT).startsWith("https://") -> "https://" + limpia.substring(8)
            limpia.lowercase(Locale.ROOT).trimEnd('.').startsWith("mining.x39matrix.org") -> "https://$limpia"
            limpia.contains(":") -> "http://$limpia"
            else -> "http://$limpia:$PUERTO_API_DEFECTO"
        }
        val u = runCatching { java.net.URI(conEsquema) }.getOrNull() ?: return conEsquema
        val hostNorm = u.host?.lowercase(Locale.ROOT)?.trimEnd('.') ?: return conEsquema
        val pathNorm = u.path?.trimEnd('/') ?: ""
        if (hostNorm == "mining.x39matrix.org" && u.port in listOf(-1, 443) && (pathNorm.isEmpty() || pathNorm == RUTA_STATS_PUBLICA)) {
            return if (pathNorm == RUTA_STATS_PUBLICA) "$URL_PUBLICA_DEFECTO$RUTA_STATS_PUBLICA" else URL_PUBLICA_DEFECTO
        }
        return conEsquema
    }

    internal fun esDestinoPublico(base: String): Boolean {
        val u = runCatching { java.net.URI(base.trim()) }.getOrNull() ?: return false
        val scheme = u.scheme?.lowercase(Locale.ROOT) ?: return false
        val host = u.host?.lowercase(Locale.ROOT)?.trimEnd('.') ?: return false
        val path = u.path?.trimEnd('/') ?: ""
        return (scheme == "https" && host == "mining.x39matrix.org" && u.port in listOf(-1, 443) && path != RUTA_TELEMETRIA_PRIVADA) ||
            path.endsWith(RUTA_STATS_PUBLICA)
    }

    internal fun esDestinoPrivadoAutorizado(base: String): Boolean {
        val u = runCatching { java.net.URI(base.trim()) }.getOrNull() ?: return false
        val host = u.host?.lowercase(Locale.ROOT)?.trimEnd('.') ?: return false
        return host == IP_VPN.lowercase(Locale.ROOT)
    }

    private fun httpGetPublicoJson(urlCompleta: String): JSONObject {
        val conn = (URL(urlCompleta).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false
            requestMethod = "GET"
            connectTimeout = 4000
            readTimeout = 5000
        }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val resp = stream?.let { BufferedReader(InputStreamReader(it)).use { r -> r.readText() } } ?: ""
        if (code !in 200..299) {
            throw RuntimeException("HTTP $code: $resp")
        }
        return JSONObject(resp)
    }

    private fun httpGetPrivadoJson(urlCompleta: String, rutaFirma: String, tokenLecturaOpcional: String?): JSONObject {
        if (!TunelX39.arriba()) {
            throw SecurityException("Túnel X39 inactivo: se aborta la consulta privada para no exponer credenciales fuera del túnel.")
        }
        if (!esDestinoPrivadoAutorizado(urlCompleta)) {
            throw SecurityException("Destino privado no autorizado fuera de la IP del nodo del túnel ($IP_VPN).")
        }
        val cabecerasFirma = IdentidadDispositivoMiNodo.cabecerasFirmadas(rutaFirma)
        val conn = (URL(urlCompleta).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false
            requestMethod = "GET"
            connectTimeout = 4000
            readTimeout = 5000
            for ((k, v) in cabecerasFirma) {
                setRequestProperty(k, v)
            }
            if (!tokenLecturaOpcional.isNullOrBlank()) {
                setRequestProperty("Authorization", "Bearer $tokenLecturaOpcional")
            }
        }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val resp = stream?.let { BufferedReader(InputStreamReader(it)).use { r -> r.readText() } } ?: ""
        if (code == 401 || code == 403) {
            throw SecurityException(
                "Acceso denegado por el nodo privado (HTTP $code). Verifica tu token de lectura o autorización."
            )
        }
        if (code !in 200..299) {
            throw RuntimeException("HTTP $code: $resp")
        }
        return JSONObject(resp)
    }

    suspend fun consultarEstado(
        tokenLecturaOpcional: String? = null,
        endpointPersonalizado: String? = null,
        direccionMineroOpcional: String? = null,
        consultarMineroExplicito: Boolean = false
    ): EstadoMiNodo = withContext(Dispatchers.IO) {
        val dirMinero = direccionMineroOpcional?.trim() ?: ""
        val customLimpio = endpointPersonalizado?.trim() ?: ""
        val tokenLimpio = tokenLecturaOpcional?.trim() ?: ""

        val baseElegida = when {
            customLimpio.isNotEmpty() -> normalizarBaseUrl(customLimpio)
            tokenLimpio.isNotEmpty() -> {
                if (!TunelX39.arriba()) {
                    throw SecurityException("Túnel X39 inactivo: se aborta la consulta privada para no exponer credenciales fuera del túnel.")
                }
                "http://$IP_VPN:$PUERTO_API_DEFECTO"
            }
            else -> URL_PUBLICA_DEFECTO
        }

        if (esDestinoPublico(baseElegida)) {
            val urlFinal = if (baseElegida.endsWith(RUTA_STATS_PUBLICA)) baseElegida else "$baseElegida$RUTA_STATS_PUBLICA"
            val root = httpGetPublicoJson(urlFinal)
            var minerJson: JSONObject? = null
            if (consultarMineroExplicito && dirMinero.isNotEmpty()) {
                runCatching {
                    val baseSinRuta = baseElegida.removeSuffix(RUTA_STATS_PUBLICA)
                    val urlMiner = "$baseSinRuta$RUTA_MINER_PUBLICA?address=${URLEncoder.encode(dirMinero, "UTF-8")}"
                    minerJson = httpGetPublicoJson(urlMiner)
                }
            }
            return@withContext parsearRespuesta(baseElegida, root, dirMinero, minerJson)
        } else {
            if (!TunelX39.arriba()) {
                throw SecurityException("Túnel X39 inactivo: no se abre conexión privada.")
            }
            if (!esDestinoPrivadoAutorizado(baseElegida)) {
                throw SecurityException("Destino privado no autorizado: las credenciales privadas solo se envían al nodo del túnel ($IP_VPN).")
            }
            val urlFinal = if (baseElegida.endsWith(RUTA_TELEMETRIA_PRIVADA)) baseElegida else "$baseElegida$RUTA_TELEMETRIA_PRIVADA"
            val root = httpGetPrivadoJson(urlFinal, RUTA_TELEMETRIA_PRIVADA, tokenLimpio)
            return@withContext parsearRespuesta(baseElegida, root, dirMinero, null)
        }
    }

    internal fun parsearRespuesta(
        origen: String,
        root: JSONObject,
        dirMineroConfig: String = "",
        minerRoot: JSONObject? = null
    ): EstadoMiNodo {
        val ahoraSeg = System.currentTimeMillis() / 1000L
        val node = root.optJSONObject("node") ?: JSONObject()
        val pool = root.optJSONObject("mining_pool") ?: root.optJSONObject("pool") ?: JSONObject()
        val wallet = root.optJSONObject("wallet") ?: JSONObject()
        val system = root.optJSONObject("system") ?: JSONObject()
        val template = root.optJSONObject("block_template") ?: JSONObject()
        val meta = root.optJSONObject("meta") ?: JSONObject()

        val blocks = node.optLong("blocks", 0L)
        val headers = node.optLong("headers", -1L)
        val syncProgress = node.optDouble("verificationprogress", Double.NaN)
        val ibd = if (node.isNull("initialblockdownload")) null else node.optBoolean("initialblockdownload")
        val synced = if (node.isNull("synced")) null else node.optBoolean("synced")
        val connections = node.optInt("connections", 0)
        val connIn = node.optInt("connections_in", 0)
        val connOut = node.optInt("connections_out", connections)

        var runtime = pool.optLong("runtimeSeconds", pool.optLong("runtime", 0L))
        var lastUpdatePool = pool.optLong("lastUpdateEpoch", pool.optLong("lastupdate", 0L))
        var users = pool.optInt("users", pool.optInt("Users", 0))
        var workers = pool.optInt("workers", pool.optInt("Workers", 0))
        var idle = pool.optInt("idle", pool.optInt("Idle", 0))
        var disconnected = pool.optInt("disconnected", pool.optInt("Disconnected", 0))
        var hr1m = "0"
        var hr5m = "0"
        var hr15m = "0"
        var hr1h = "0"
        var hr6h = "0"
        var hr1d = "0"
        var hr7d = "0"
        var sps1m = 0.0
        var accepted = 0L
        var rejected = 0L
        var bestShare = 0L
        var poolDiff = 0.0

        val hrObj = pool.optJSONObject("hashrate")
        if (hrObj != null) {
            hr1m = hrObj.optJSONObject("m1")?.optString("display", "0") ?: hrObj.optString("m1", "0")
            hr5m = hrObj.optJSONObject("m5")?.optString("display", "0") ?: hrObj.optString("m5", "0")
            hr15m = hrObj.optJSONObject("m15")?.optString("display", "0") ?: hrObj.optString("m15", "0")
            hr1h = hrObj.optJSONObject("h1")?.optString("display", "0") ?: hrObj.optString("h1", "0")
            hr6h = hrObj.optJSONObject("h6")?.optString("display", "0") ?: hrObj.optString("h6", "0")
            hr1d = hrObj.optJSONObject("d1")?.optString("display", "0") ?: hrObj.optString("d1", "0")
            hr7d = hrObj.optJSONObject("d7")?.optString("display", "0") ?: hrObj.optString("d7", "0")
        }
        val sharesObj = pool.optJSONObject("shares")
        if (sharesObj != null) {
            accepted = sharesObj.optLong("accepted", 0L)
            rejected = sharesObj.optLong("rejected", 0L)
            bestShare = sharesObj.optLong("bestShare", sharesObj.optLong("bestshare", 0L))
            sps1m = sharesObj.optDouble("sps1m", 0.0)
            poolDiff = sharesObj.optDouble("difficulty", 0.0)
        }

        val rawLines = pool.optJSONArray("raw_lines")
        if (rawLines != null) {
            for (i in 0 until rawLines.length()) {
                try {
                    val lineaCruda = rawLines.getString(i)
                    val idxBrace = lineaCruda.indexOf('{')
                    val jsonLimpio = if (idxBrace >= 0) lineaCruda.substring(idxBrace) else lineaCruda
                    val j = JSONObject(jsonLimpio)
                    if (j.has("runtime")) {
                        runtime = j.optLong("runtime", runtime)
                        lastUpdatePool = j.optLong("lastupdate", lastUpdatePool)
                        users = j.optInt("Users", users)
                        workers = j.optInt("Workers", workers)
                        idle = j.optInt("Idle", idle)
                        disconnected = j.optInt("Disconnected", disconnected)
                    }
                    if (j.has("hashrate1m")) {
                        hr1m = j.optString("hashrate1m", hr1m)
                        hr5m = j.optString("hashrate5m", hr5m)
                        hr15m = j.optString("hashrate15m", hr15m)
                        hr1h = j.optString("hashrate1hr", hr1h)
                        hr6h = j.optString("hashrate6hr", hr6h)
                        hr1d = j.optString("hashrate1d", hr1d)
                        hr7d = j.optString("hashrate7d", hr7d)
                    }
                    if (j.has("accepted")) {
                        poolDiff = j.optDouble("diff", poolDiff)
                        accepted = j.optLong("accepted", accepted)
                        rejected = j.optLong("rejected", rejected)
                        bestShare = j.optLong("bestshare", bestShare)
                        sps1m = j.optDouble("SPS1m", sps1m)
                    }
                } catch (_: Exception) {}
            }
        }

        val dataAgeMeta = pool.optLong("dataAgeSeconds", meta.optLong("lastSuccessAgeSeconds", -1L))
        val tsServerExplicito = root.optLong("server_time", 0L)
        val refTimestamp = when {
            lastUpdatePool in 1..(ahoraSeg + 300) -> lastUpdatePool
            tsServerExplicito in 1..(ahoraSeg + 300) -> tsServerExplicito
            else -> 0L
        }
        val tsServer = if (tsServerExplicito > 0L) tsServerExplicito else refTimestamp
        val edadSeg = when {
            dataAgeMeta >= 0L -> dataAgeMeta
            refTimestamp > 0L -> (ahoraSeg - refTimestamp).coerceAtLeast(0L)
            else -> -1L
        }
        val obsoleto = meta.optBoolean("stale", false) || edadSeg < 0L || edadSeg > UMBRAL_OBSOLETO_SEG

        val hayPlantilla = template.has("subsidy_sats") || template.has("total_reward_sats")
        val subsidioSats = if (hayPlantilla) template.optLong("subsidy_sats", 0L) else 0L
        val feesSats = if (hayPlantilla) template.optLong("fees_sats", 0L) else 0L
        val totalPotencialSats = if (hayPlantilla) template.optLong("total_reward_sats", subsidioSats + feesSats) else 0L
        val comisionOperador1Sats = totalPotencialSats / 100L
        val premioMinero99Sats = totalPotencialSats - comisionOperador1Sats

        val listaMovs = mutableListOf<MovimientoLectura>()
        val txsJson = wallet.optJSONArray("transactions")
        if (txsJson != null && txsJson.length() > 0) {
            for (i in 0 until txsJson.length()) {
                val tx = txsJson.optJSONObject(i) ?: continue
                val cat = tx.optString("category", "receive")
                val sats = tx.optLong("amount_sats", 0L)
                val confs = tx.optInt("confirmations", 0)
                val txid = tx.optString("txid", "").take(10)
                val esCoinbase = cat == "generate" || cat == "immature"
                val estadoMadurez = when {
                    confs <= 0 -> "Pendiente en mempool (0 conf)"
                    esCoinbase && confs < 6 -> "Bloque encontrado ($confs/6 conf · Inmaduro)"
                    esCoinbase && confs < 100 -> "Bloque confirmado ($confs/100 conf · Madurando coinbase)"
                    esCoinbase -> "Recompensa madura y disponible ($confs conf)"
                    confs < 6 -> "En confirmación ($confs/6 conf)"
                    else -> "Confirmado ($confs conf)"
                }
                listaMovs.add(MovimientoLectura(cat, sats, confs, estadoMadurez, txid))
            }
        } else {
            val avisosArr = wallet.optJSONArray("avisos")
            if (avisosArr != null) {
                for (i in 0 until avisosArr.length()) {
                    val linea = avisosArr.optString(i, "")
                    if (linea.isNotBlank()) {
                        listaMovs.add(parsearAvisoLegacy(linea))
                    }
                }
            }
        }

        var indEncontrado = false
        var ind1m = "0"
        var ind5m = "0"
        var ind1h = "0"
        var ind1d = "0"
        var ind7d = "0"
        var indWorkers = 0
        var indBest = 0L
        var indAcc = 0L
        if (minerRoot != null && minerRoot.optBoolean("found", false)) {
            indEncontrado = true
            val mObj = minerRoot.optJSONObject("miner") ?: minerRoot.optJSONObject("user") ?: JSONObject()
            val mHr = mObj.optJSONObject("hashrate")
            if (mHr != null) {
                ind1m = mHr.optJSONObject("m1")?.optString("display", "0") ?: "0"
                ind5m = mHr.optJSONObject("m5")?.optString("display", "0") ?: "0"
                ind1h = mHr.optJSONObject("h1")?.optString("display", "0") ?: "0"
                ind1d = mHr.optJSONObject("d1")?.optString("display", "0") ?: "0"
                ind7d = mHr.optJSONObject("d7")?.optString("display", "0") ?: "0"
            } else {
                ind1m = mObj.optString("hashrate1m", "0")
                ind5m = mObj.optString("hashrate5m", "0")
                ind1h = mObj.optString("hashrate1hr", "0")
                ind1d = mObj.optString("hashrate1d", "0")
                ind7d = mObj.optString("hashrate7d", "0")
            }
            indWorkers = mObj.optInt("workersConnected", mObj.optInt("workers", 0))
            indBest = mObj.optLong("bestShareEver", mObj.optLong("bestever", 0L))
            indAcc = mObj.optLong("acceptedDifficulty", mObj.optLong("shares", 0L))
        }

        val tempVal = if (system.has("temp_c")) system.optDouble("temp_c") else null
        val via = when {
            esDestinoPrivadoAutorizado(origen) -> "X39 Túnel (WireGuard)"
            esDestinoPublico(origen) -> "API Pública HTTPS"
            else -> "Nodo Personalizado"
        }

        val rawPoolStatus = pool.optString("status", "offline").uppercase(Locale.ROOT)
        val poolStatusNorm = if (rawPoolStatus == "ONLINE" || rawPoolStatus == "RUNNING") "RUNNING" else rawPoolStatus

        return EstadoMiNodo(
            viaConexion = via,
            ipRespondio = origen.replace("https://", "").replace("http://", ""),
            timestampServidorSeg = tsServer,
            edadTelemetriaSeg = edadSeg,
            telemetriaDesactualizada = obsoleto,
            nodoOnline = node.optBoolean("online", false),
            chain = node.optString("chain", "offline").uppercase(Locale.ROOT),
            blocks = blocks,
            headers = headers,
            progresoSync = syncProgress,
            ibd = ibd,
            sincronizado = synced,
            contadoresVerificados = pool.optJSONObject("shares")?.optString("counterSemantics") == "share_counts",
            paresTotales = connections,
            paresIn = connIn,
            paresOut = connOut,
            electrsActivo = system.optString("electrs", "No informado por la API"),
            ckpoolServicioActivo = poolStatusNorm.lowercase(Locale.ROOT),
            tempPiCelsius = tempVal,
            warnings = node.optString("warnings", ""),
            poolStatus = poolStatusNorm,
            runtimeSeg = runtime,
            lastUpdatePoolSeg = lastUpdatePool,
            users = users,
            workers = workers,
            idle = idle,
            disconnected = disconnected,
            hr1m = hr1m,
            hr5m = hr5m,
            hr15m = hr15m,
            hr1h = hr1h,
            hr6h = hr6h,
            hr1d = hr1d,
            hr7d = hr7d,
            sps1m = sps1m,
            acceptedShares = accepted,
            rejectedShares = rejected,
            bestShare = bestShare,
            poolDiffMin = pool.optLong("mindiff", 0L),
            netDiff = node.optDouble("difficulty", poolDiff),
            bloquesGanadosConfirmados = pool.optInt("blocks_found", pool.optInt("blocksFound", 0)),
            premioPotencialTotalSats = totalPotencialSats,
            premioPotencialFeesSats = feesSats,
            premioPotencialMinero99Sats = premioMinero99Sats,
            premioPotencialOperador1Sats = comisionOperador1Sats,
            walletName = wallet.optString("name", "solo-lectura"),
            dirOperador1Pct = wallet.optString("monitored_address", "No informada por la API"),
            dirMinero99Pct = dirMineroConfig.ifBlank { wallet.optString("miner_address", "Configurable en «Credencial / Nodo»") },
            saldoConfirmadoMaduroSats = wallet.optLong("confirmed_sats", 0L),
            saldoInmaduroCoinbaseSats = wallet.optLong("immature_sats", 0L),
            saldoPendienteSats = wallet.optLong("unconfirmed_sats", 0L),
            movimientos = listaMovs,
            mineroIndividualEncontrado = indEncontrado,
            mineroHr1m = ind1m,
            mineroHr5m = ind5m,
            mineroHr1h = ind1h,
            mineroHr1d = ind1d,
            mineroHr7d = ind7d,
            mineroWorkers = indWorkers,
            mineroBestShare = indBest,
            mineroAcceptedDiff = indAcc
        )
    }

    private fun parsearAvisoLegacy(linea: String): MovimientoLectura {
        val regex = Regex("""^(\w+):\s*(-?\d+)\s*sats\s*\((\d+)\s*conf\)""")
        val m = regex.find(linea.trim())
        if (m != null) {
            val cat = m.groupValues[1]
            val sats = m.groupValues[2].toLongOrNull() ?: 0L
            val confs = m.groupValues[3].toIntOrNull() ?: 0
            val esCoinbase = cat == "generate" || cat == "immature"
            val estado = when {
                confs <= 0 -> "Pendiente (0 conf)"
                esCoinbase && confs < 6 -> "Bloque encontrado ($confs/6 conf)"
                esCoinbase && confs < 100 -> "Madurando coinbase ($confs/100 conf)"
                esCoinbase -> "Recompensa madura ($confs conf)"
                else -> "Confirmado ($confs conf)"
            }
            return MovimientoLectura(cat, sats, confs, estado, "")
        }
        return MovimientoLectura("movimiento", 0L, 0, linea, "")
    }

    fun hashrateATerahashes(hr: String): Double {
        val limpio = hr.trim().uppercase(Locale.ROOT).replace(",", ".")
        if (limpio.isEmpty() || limpio == "0") return 0.0
        val mult = when {
            limpio.endsWith("E") || limpio.endsWith("EH/S") -> 1_000_000.0
            limpio.endsWith("P") || limpio.endsWith("PH/S") -> 1_000.0
            limpio.endsWith("T") || limpio.endsWith("TH/S") -> 1.0
            limpio.endsWith("G") || limpio.endsWith("GH/S") -> 0.001
            limpio.endsWith("M") || limpio.endsWith("MH/S") -> 0.000_001
            limpio.endsWith("K") || limpio.endsWith("KH/S") -> 0.000_000_001
            else -> 0.000_000_000_001
        }
        val numStr = limpio.dropLastWhile { !it.isDigit() && it != '.' }
        return (numStr.toDoubleOrNull() ?: 0.0) * mult
    }
}
