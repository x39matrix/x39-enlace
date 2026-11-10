/*
 * x39tunel v2 · WireGuard dentro de la app, con PSK post-cuántica.
 *
 * Qué hace:
 *   1. Genera la pareja de claves WireGuard del móvil (una vez; la privada sellada con el Keystore).
 *   2. Al registrarse en la señalización pide el alta del par (`wg_alta`): el servidor asigna
 *      la dirección 10.39.0.N y contesta con su clave WireGuard y su endpoint.
 *   3. Pide la PSK: 32 bytes frescos, firmados con la identidad ML-DSA-87 y sellados en una
 *      cápsula Paris hacia la clave ML-KEM-1024 del servidor (fijada aquí por su huella). El
 *      servidor la aplica a este par; el túnel queda híbrido Curve25519 + ML-KEM-1024.
 *   4. Levanta el túnel con la biblioteca de WireGuard (misma que la app oficial).
 *
 * Honestidad en pantalla: `estado()` solo dice «protegida» cuando el túnel está UP y la PSK
 * post-cuántica está aplicada y vigente. Si algo falla, lo dice; no se finge.
 */
package io.livekit.android.sample

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import com.wireguard.config.InetEndpoint
import com.wireguard.config.InetNetwork
import com.wireguard.config.Interface
import com.wireguard.config.Peer
import com.wireguard.crypto.Key
import com.wireguard.crypto.KeyPair
import org.json.JSONObject
import org.x39.bolsillo.NucleoLlamadas
import org.x39.bolsillo.X39Core

object TunelX39 : NucleoLlamadas.EscuchaTunel {
    /** Huella SHA-256 de la clave ML-KEM-1024 del servidor (x39-tunel huella). Fijada. */
    const val HUELLA_SERVIDOR = "ea49d777d751d3281bfe7e6ad2d832d1a650e82bbe4da42bca3423b0e42fd863"
    /** Clave ML-KEM-1024 pública del servidor (1568 bytes, base64). Fijada: se comprueba contra la huella al arrancar. */
    private const val SERVIDOR_KEM_PK_B64 =
        "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"

    /*
     * Configuración FIJA del túnel de Sr. X. Es el respaldo para cuando el servidor de
     * señalización todavía no ofrece el alta automática (wg_alta) ni la PSK
     * post-cuántica: con estos datos el túnel levanta igual, como WireGuard normal.
     *
     * Se dice en pantalla cuál de los dos modos está en uso. Un túnel sin PSK protege
     * la ruta igual; lo que NO tiene es la capa post-cuántica añadida. No se finge.
     */
    private const val FIJO_DIRECCION = "10.39.0.2/32"
    private const val FIJO_SERVIDOR_WG = "4f4NuEiaBsQ6V0D0/2nfvbT3d1XMh/dK87BS/P4vpQ0="
    private const val FIJO_ENDPOINT = "159.198.37.137:51820"

    /**
     * Dirección del servidor DENTRO del túnel. Solo se usa con la ruta completa: si no,
     * el paquete a la señalización sale del VPS y vuelve a entrar por su propia IP
     * pública (hairpin con NAT), y el socket no para quieto.
     */
    private const val IP_SENAL_INTERNA = "10.39.0.1"

    private const val PREF = "x39tunel"
    private const val SELLADO_WG = "wg-privada"
    private const val SELLADO_PSK = "wg-psk"
    private const val VIDA_PSK_S = 24L * 3600      // se pide una nueva cada día
    private const val RENOVAR_ANTES_S = 3600L      // y si a la vigente le queda menos de una hora

    enum class Fase { APAGADO, SIN_VPN_PERMISO, ESPERANDO_ALTA, ESPERANDO_PSK, LEVANTANDO, PROTEGIDO, PROTEGIDO_SIN_PSK, FALLO }

    interface Oyente { fun tunelCambio() }

    @Volatile var oyente: Oyente? = null
    @Volatile var fase: Fase = Fase.APAGADO; private set
    /**
     * Por qué está en FALLO. Se guarda el caso, no el texto: el texto se resuelve al
     * pintarlo (estado()), en el idioma que tenga la app en ese momento.
     */
    private enum class Motivo { NINGUNO, CAYO, ALTA, PSK, SIN_RESPUESTA_ALTA, SIN_RESPUESTA_PSK, OTRO }
    @Volatile private var motivo = Motivo.NINGUNO
    /** Lo que acompaña al motivo: el texto del servidor o el de la excepción (no se traduce). */
    @Volatile private var motivoDetalle = ""

    private lateinit var ctx: Context
    private var backend: GoBackend? = null
    private var pskPendiente: ByteArray? = null

    /** Cuánto se espera una respuesta del servidor antes de rendirse y decirlo. */
    private const val ESPERA_MAX_MS = 15_000L
    private val relojEspera = Handler(Looper.getMainLooper())

    /**
     * Reloj aparte para el sondeo de tráfico: `cambiar()` vacía el de la espera, y si
     * compartieran Handler un cambio de fase mataría el sondeo a media cuenta.
     */
    private val relojTrafico = Handler(Looper.getMainLooper())
    private val tunel = object : Tunnel {
        override fun getName() = "x39tunel"
        override fun onStateChange(newState: Tunnel.State) {
            if (newState == Tunnel.State.DOWN && arriba()) cambiar(Fase.FALLO, Motivo.CAYO)
            // Cada cambio real del túnel decide por dónde va la señalización.
            sincronizarSenalizacion()
        }
    }

    /** Lo último que se le pidió al núcleo, para no reconectar sin motivo. */
    private var senalInternaPuesta: String? = null

    /**
     * ¿Ha llegado tráfico del otro extremo del túnel?
     *
     * `Tunnel.State.UP` solo dice que la interfaz está levantada: puede estarlo sin que
     * haya comunicación con el servidor. Lo que sí la demuestra es haber RECIBIDO bytes
     * de ese par. La librería no expone el apretón de manos, pero sí los contadores.
     *
     * Con el keepalive de 25 s, esto pasa a true al poco de levantar si el otro extremo
     * responde; si no responde, se queda en false y la señalización no se desvía.
     */
    /**
     * Estado REAL de la interfaz, preguntado al backend. No vale mirar `fase`: la fase
     * pasa por ESPERANDO_ALTA cada vez que se reabre el socket de señalización, y usarla
     * aquí montaba un bucle — conmutar la señalización reconectaba, reconectar reenviaba
     * el alta, el alta cambiaba la fase, y la fase volvía a conmutar la señalización.
     */
    private fun tunelRealmenteArriba(): Boolean = try {
        backend?.getState(tunel) == Tunnel.State.UP
    } catch (e: Exception) {
        false
    }

    private var rxUltimo = -1L
    private var rxCuando = 0L
    /** Último valor leído del contador, solo para poder registrarlo y diagnosticar. */
    @Volatile
    var rxLeido = -1L
        private set

    /**
     * ¿Está ENTRANDO tráfico del otro extremo ahora mismo?
     *
     * `peerRx > 0` no vale: es un acumulado, así que una vez positivo se queda positivo
     * para siempre. Si la red se cae con la interfaz todavía en UP, el contador se
     * congela pero no baja, y la señalización se quedaría desviada a una dirección
     * muerta. Lo que se mira es si el contador ha CRECIDO hace poco.
     *
     * Con el keepalive de 25 s debería entrar algo a menudo; se da por muerto a los 75.
     */
    private fun tunelComunica(): Boolean {
        val rx = try {
            val st = backend?.getStatistics(tunel) ?: return false
            val srv = servidorWg()
            if (srv.isEmpty()) return false
            st.peerRx(Key.fromBase64(srv))
        } catch (e: Exception) {
            return false
        }
        val ahora = SystemClock.elapsedRealtime()
        rxLeido = rx
        // Al reiniciarse el túnel, WireGuard crea un dispositivo NUEVO y su contador vuelve a cero. Si no se
        // detecta, `rxUltimo` se queda con el valor viejo (millones de bytes) y el contador nuevo tarda
        // muchísimo en superarlo: la app daría el túnel por muerto estando vivo, mandaría la señalización por
        // la red normal y el móvil se quedaría en «Conectando…» sin poder recibir llamadas (visto el 22-09-2026
        // en un móvil que cambiaba de wifi a datos). Un contador que BAJA solo puede ser un dispositivo nuevo.
        if (rx < rxUltimo) { rxUltimo = -1L; rxCuando = 0L }
        if (rx > rxUltimo) { rxUltimo = rx; rxCuando = ahora }
        return rxCuando > 0L && ahora - rxCuando < 75_000L
    }

    /**
     * ¿Está el SISTEMA enrutando ya por la VPN?
     *
     * Que la interfaz esté UP y pase tráfico todavía no basta: durante unos segundos
     * Android sigue sacando los sockets nuevos por el wifi, y entonces la dirección del
     * túnel es inalcanzable. Se vio en el log: «failed to connect to /10.39.0.1 ...
     * from /<IP de la LAN> after 15000ms», o sea saliendo por la IP equivocada.
     */
    private fun sistemaEnrutaPorVpn(): Boolean = try {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val red = cm.activeNetwork
        val cap = if (red == null) null else cm.getNetworkCapabilities(red)
        cap != null && cap.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
    } catch (e: Exception) {
        false
    }

    /**
     * Decide por dónde habla la señalización, según lo que HAY, no según lo que se ha
     * pedido: hace falta la ruta completa, que el túnel pase tráfico de verdad Y que el
     * sistema ya saque los sockets por la VPN.
     * Mientras levanta, si se cae, o con las rutas acotadas, se vuelve a la red normal —
     * si no, la señalización se quedaría hablándole a una dirección inalcanzable.
     */
    /** Comprobaciones seguidas en las que el túnel ha ido bien. Para no ir y venir. */
    private var buenasSeguidas = 0

    /**
     * Estado de la ruta de señalización.
     *
     * La diferencia entre PRUEBA y ASENTADO es lo que hace barato asomarse: volver al
     * túnel cuesta UN fallo si aún no se ha demostrado que va; solo cuando el socket
     * ha aguantado un minuto entero pasa a costar tres, que es lo que impide que un
     * bache normal eche a la señalización de una ruta que funciona.
     */
    private enum class RutaSenal { FUERA, EN_PRUEBA, ASENTADO }

    private var rutaSenal = RutaSenal.FUERA

    /** Fallos que se toleran según el estado. */
    private const val FALLOS_EN_PRUEBA = 1
    private const val FALLOS_ASENTADO = 3

    /** Lo que tiene que aguantar el socket, abierto y sin caerse, para asentarse. */
    private const val PARA_ASENTARSE_MS = 60_000L

    /** Cuándo abrió el socket por la ruta interna. 0 = todavía no ha abierto. */
    private var abiertoDesde = 0L

    /**
     * Ha cambiado la red del móvil (wifi↔datos, una VPN que sube o baja, la red que
     * vuelve tras una avería).
     *
     * Eso es información NUEVA, y la espera creciente no debe castigarla: esa espera
     * existe para no insistir contra un túnel que va y viene, no para dejar fuera a uno
     * que acaba de volver. Si la red cambia, la espera se anula y se prueba en el acto.
     *
     * Lo que NO se toca: las reglas de salida (3 fallos asentado, 1 a prueba) ni el
     * minuto para asentarse. Si al asomarse sigue sin ir, sale igual de rápido.
     */
    @Synchronized
    fun cambioDeRed(motivo: String) {
        val ahora = SystemClock.elapsedRealtime()
        // Un solo cambio de red dispara varios callbacks; se atiende uno.
        if (ahora - ultimoCambioRed < COLAPSA_CAMBIOS_MS) return
        ultimoCambioRed = ahora
        /*
         * Solo hay algo que anular si hay una espera pendiente: o se está fuera del
         * túnel esperando para entrar, o se está en cuarentena. Si la señalización ya
         * va por dentro y nadie la está frenando, un cambio de red no tiene nada que
         * arreglar aquí: tocarlo solo serviría para tirar un socket que funciona.
         */
        val enCuarentena = ahora < vetadoHasta
        if (senalInternaPuesta != null && !enCuarentena) {
            android.util.Log.i(
                "X39_TUNEL",
                "cambio de red ($motivo): no hay espera pendiente (estado=$rutaSenal), no toco nada",
            )
            return
        }
        android.util.Log.i("X39_TUNEL", "cambio de red ($motivo): espera a 0, me asomo")
        vetadoHasta = 0L
        salidasSeguidas = 0
        relojEspera.removeCallbacksAndMessages(null)
        // La histéresis de entrada pide dos comprobaciones buenas; un cambio de red
        // vale como la primera, así que se entra ya. Si no va, un fallo basta para salir.
        buenasSeguidas = 1
        arrancarVigilancia()
        sincronizarSenalizacion()
    }

    /** El socket ha abierto por la dirección del túnel: empieza la cuenta del minuto. */
    @Synchronized
    fun senalAbrePorElTunel() {
        if (rutaSenal == RutaSenal.EN_PRUEBA && abiertoDesde == 0L) {
            abiertoDesde = SystemClock.elapsedRealtime()
            android.util.Log.i("X39_TUNEL", "la señalización abrió por el túnel: a prueba un minuto")
        } else {
            // Si se llega aquí, el aviso llega pero el estado no es el que se espera.
            android.util.Log.i(
                "X39_TUNEL",
                "aviso de apertura por el túnel ignorado · estado=$rutaSenal abiertoDesde=$abiertoDesde",
            )
        }
    }

    /** Si lleva el minuto entero abierta sin caerse, deja de estar a prueba. */
    @Synchronized
    private fun revisarAsentamiento() {
        if (rutaSenal != RutaSenal.EN_PRUEBA || abiertoDesde == 0L) return
        if (SystemClock.elapsedRealtime() - abiertoDesde < PARA_ASENTARSE_MS) return
        rutaSenal = RutaSenal.ASENTADO
        salidasSeguidas = 0
        android.util.Log.i("X39_TUNEL", "la ruta por el túnel queda asentada (aguantó 60 s)")
    }

    /** Primera espera tras salir por fallos; se dobla si al volver sigue sin ir. */
    private const val CUARENTENA_MS = 30_000L
    private const val CUARENTENA_MAX_MS = 5 * 60_000L
    /** Si aguanta este rato dentro del túnel, se olvida de las salidas anteriores. */
    private const val OLVIDO_MS = 2 * 60_000L

    /** Hasta cuándo está vetada la vuelta al túnel (reloj monótono). */
    private var vetadoHasta = 0L

    /** Último cambio de red atendido: Android dispara varios seguidos por uno solo. */
    private var ultimoCambioRed = 0L
    private const val COLAPSA_CAMBIOS_MS = 3_000L
    private var salidasSeguidas = 0
    private var ultimaSalida = 0L

    /**
     * La señalización no consigue hablar por la dirección del túnel.
     *
     * Este es el síntoma bueno. El contador de bytes de WireGuard NO sirve para esto:
     * si el otro extremo deja de responder pero sigue mandando sus reintentos, el
     * contador crece igual y el túnel parece sano estando muerto. Se comprobó midiendo
     * las dos puntas a la vez: 120 s con el servidor sin recibir un byte, y el móvil
     * sumando 33 KB en ese mismo rato.
     */
    @Synchronized
    fun senalNoTiraPorElTunel(fallosSeguidos: Int) {
        if (senalInternaPuesta == null) return
        val tope = if (rutaSenal == RutaSenal.ASENTADO) FALLOS_ASENTADO else FALLOS_EN_PRUEBA
        if (fallosSeguidos < tope) return
        val ahora = SystemClock.elapsedRealtime()
        // Si llevaba un buen rato dentro sin problemas, esto es un incidente nuevo:
        // no se arrastra la espera de averías viejas.
        if (ultimaSalida == 0L || ahora - ultimaSalida > OLVIDO_MS) salidasSeguidas = 0
        salidasSeguidas++
        ultimaSalida = ahora
        // Espera creciente: si al volver sigue roto, no se insiste cada 30 s.
        val espera = minOf(CUARENTENA_MS shl (salidasSeguidas - 1), CUARENTENA_MAX_MS)
        android.util.Log.w(
            "X39_TUNEL",
            "la señalización no tira por el túnel ($fallosSeguidos de $tope fallos, estado " +
                "$rutaSenal): vuelvo a la red normal y no reintento en ${espera / 1000}s " +
                "(salida nº $salidasSeguidas)",
        )
        rutaSenal = RutaSenal.FUERA
        abiertoDesde = 0L
        vetadoHasta = ahora + espera
        buenasSeguidas = 0
        sincronizarSenalizacion()
    }

    @Synchronized
    private fun sincronizarSenalizacion() {
        val enCuarentena = SystemClock.elapsedRealtime() < vetadoHasta
        val bien = tunelRealmenteArriba() && rutaTodo() && tunelComunica() &&
            sistemaEnrutaPorVpn() && !enCuarentena
        buenasSeguidas = if (bien) buenasSeguidas + 1 else 0

        /*
         * Asimétrico a propósito:
         *   · SALIR del túnel (volver a la red normal) en cuanto algo falla. Tardar
         *     aquí deja la señalización hablándole a una dirección muerta.
         *   · VOLVER al túnel solo tras DOS comprobaciones seguidas buenas (~20 s).
         *     Si se volviera al primer acierto, un túnel que va y viene haría que la
         *     señalización fuese y viniese con él, reconectando el socket cada vez.
         */
        val yaDentro = senalInternaPuesta != null
        val quiere = when {
            !bien -> null
            yaDentro -> IP_SENAL_INTERNA
            buenasSeguidas >= 2 -> IP_SENAL_INTERNA
            else -> null
        }
        if (quiere == senalInternaPuesta) return
        if (quiere != null && senalInternaPuesta == null) {
            // Entra A PRUEBA: un solo fallo la vuelve a sacar.
            rutaSenal = RutaSenal.EN_PRUEBA
            abiertoDesde = 0L
        } else if (quiere == null) {
            rutaSenal = RutaSenal.FUERA
            abiertoDesde = 0L
        }
        senalInternaPuesta = quiere
        android.util.Log.i(
            "X39_TUNEL",
            "señalización por " + (quiere ?: "la red normal") + " · interfaz=" +
                (if (tunelRealmenteArriba()) "UP" else "no") + " rutaTodo=" + rutaTodo() +
                " comunica=" + tunelComunica() + " vpnActiva=" + sistemaEnrutaPorVpn() +
                " cuarentena=" + enCuarentena + " fase=" + fase,
        )
        runCatching { X39App.nucleo.senalPorIpInterna(quiere) }
    }

    fun asegurar(c: Context) {
        ctx = c.applicationContext
        val pk = servidorKemPk()
        // La clave fijada tiene que cuadrar con la huella fijada: si no, alguien tocó el APK.
        check(X39Core.tunelHuellaServidor(pk) == HUELLA_SERVIDOR) { "la clave del servidor del túnel no cuadra con su huella" }
        if (backend == null) backend = GoBackend(ctx)
        // La app puede arrancar con el túnel ya levantado por una sesión anterior:
        // entonces nadie pasa por levantar(), así que la vigilancia se arranca aquí.
        if (activado() && tunelRealmenteArriba()) {
            fase = if (Boveda.abrir(ctx, SELLADO_PSK) != null) Fase.PROTEGIDO else Fase.PROTEGIDO_SIN_PSK
            arrancarVigilancia()
        }
    }

    private fun prefs() = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
    fun activado(): Boolean = prefs().getBoolean("activado", false)
    fun direccion(): String = prefs().getString("direccion", "") ?: ""
    fun servidorWg(): String = prefs().getString("servidor_wg", "") ?: ""
    fun endpoint(): String = prefs().getString("endpoint", "") ?: ""
    fun pskHasta(): Long = prefs().getLong("psk_hasta", 0)

    /**
     * Por defecto solo se enruta por el túnel la red del túnel y el servidor de
     * señalización: lo justo para proteger la ruta de las llamadas. Con esto en true
     * va TODO el tráfico del móvil por el túnel.
     */
    fun rutaTodo(): Boolean = prefs().getBoolean("ruta_todo", false)

    fun rutaTodo(si: Boolean) {
        prefs().edit().putBoolean("ruta_todo", si).apply()
        // Si está arriba, se vuelve a levantar con las rutas nuevas y se revisa por
        // dónde tiene que hablar la señalización.
        if (arriba()) {
            levantar(Boveda.abrir(ctx, SELLADO_PSK))
            sincronizarSenalizacion()
        }
    }

    /** ¿Está el túnel realmente arriba? Para el indicador de la barra de arriba. */
    fun arriba(): Boolean = fase == Fase.PROTEGIDO || fase == Fase.PROTEGIDO_SIN_PSK
    private fun servidorKemPk(): ByteArray = Base64.decode(SERVIDOR_KEM_PK_B64, Base64.NO_WRAP)

    /** Pareja WireGuard del móvil; se crea la primera vez y la privada queda sellada. */
    private fun parWg(): KeyPair {
        Boveda.abrir(ctx, SELLADO_WG)?.let { return KeyPair(Key.fromBytes(it)) }
        val kp = KeyPair()
        Boveda.sellar(ctx, SELLADO_WG, kp.privateKey.bytes)
        return kp
    }

    /**
     * Clave pública WireGuard de ESTE móvil. Mientras el servidor no dé de alta los
     * pares solo, hay que darla de alta a mano en el VPS; por eso se puede ver y copiar
     * desde Ajustes. La privada nunca sale: vive sellada en el Keystore.
     */
    fun miClaveWg(): String = parWg().publicKey.toBase64()

    /** Intent de permiso de VPN si Android aún no lo dio; null si ya está concedido. */
    fun permisoVpn(): Intent? = VpnService.prepare(ctx)

    /** El usuario enciende el interruptor. Si falta el permiso de VPN, la pantalla lo pide y vuelve a llamar. */
    fun encender(nucleo: NucleoLlamadas) {
        prefs().edit().putBoolean("activado", true).apply()
        if (permisoVpn() != null) { cambiar(Fase.SIN_VPN_PERMISO); return }   // Android tiene que autorizar la VPN
        nucleo.escuchaTunel(this)
        if (nucleo.conectado()) registrados(true) else cambiar(Fase.ESPERANDO_ALTA)   // esperando a la señalización
    }

    @Synchronized
    fun apagar() {
        relojEspera.removeCallbacksAndMessages(null)
        relojTrafico.removeCallbacksAndMessages(null)
        // Cambiar de generación apaga cualquier ciclo de vigilancia que siguiera vivo.
        generacionVigilancia++
        vigilanciaViva = false
        buenasSeguidas = 0
        vetadoHasta = 0L
        salidasSeguidas = 0
        ultimaSalida = 0L
        rutaSenal = RutaSenal.FUERA
        abiertoDesde = 0L
        rxUltimo = -1L
        rxCuando = 0L
        prefs().edit().putBoolean("activado", false).apply()
        try { backend?.setState(tunel, Tunnel.State.DOWN, null) } catch (e: Exception) { /* ya estaba abajo */ }
        cambiar(Fase.APAGADO)
    }

    // ---- NucleoLlamadas.EscuchaTunel (hilo-llamada) -------------------------------

    override fun registrados(servidorConTunel: Boolean) {
        if (!activado()) return
        if (tunelRealmenteArriba() && tunelComunica()) {
            android.util.Log.i("X39_TUNEL", "socket reabierto con el túnel ya arriba: no lo toco")
            arrancarVigilancia()
            sincronizarSenalizacion()
            return
        }
        if (!servidorConTunel) {
            // El servidor todavía no da de alta pares ni reparte PSK: se tira de la
            // configuración fija. El túnel sube, pero sin la capa post-cuántica.
            android.util.Log.i("X39_TUNEL", "el servidor no ofrece alta de túnel: uso la configuración fija")
            prefs().edit()
                .putString("direccion", FIJO_DIRECCION)
                .putString("servidor_wg", FIJO_SERVIDOR_WG)
                .putString("endpoint", FIJO_ENDPOINT)
                .apply()
            levantar(null)
            return
        }
        // Si el túnel ya está arriba y pasando tráfico, no hay nada que dar de alta.
        // Reabrir el socket de señalización no es motivo para rehacer el túnel.
        if (tunelRealmenteArriba() && tunelComunica()) {
            android.util.Log.i("X39_TUNEL", "socket reabierto con el túnel ya arriba: no repito el alta")
            // Aquí estaba el fallo: se salía sin arrancar la vigilancia, así que cuando
            // el túnel se caía nadie lo notaba y la señalización se quedaba apuntando
            // a una dirección muerta. Es el caso NORMAL: app reiniciada con túnel previo.
            arrancarVigilancia()
            sincronizarSenalizacion()
            return
        }
        cambiar(Fase.ESPERANDO_ALTA)
        X39App.nucleo.enviarCrudo(JSONObject().put("type", "wg_alta").put("wg", miClaveWg()).toString())
        esperarRespuesta(Fase.ESPERANDO_ALTA, Motivo.SIN_RESPUESTA_ALTA)
    }

    override fun mensajeTunel(tipo: String, json: String): Boolean {
        val m = JSONObject(json)
        when (tipo) {
            "wg_ok" -> {
                prefs().edit()
                    .putString("direccion", m.getString("direccion"))
                    .putString("servidor_wg", m.getString("servidor_wg"))
                    .putString("endpoint", m.getString("endpoint"))
                    .apply()
                pedirPsk()
            }
            "wg_error" -> cambiar(Fase.FALLO, Motivo.ALTA, m.optString("motivo"))
            "psk_ok" -> {
                val psk = pskPendiente ?: return true
                pskPendiente = null
                Boveda.sellar(ctx, SELLADO_PSK, psk)
                prefs().edit().putLong("psk_hasta", m.getLong("hasta")).apply()
                levantar(psk)
            }
            "psk_error" -> { pskPendiente = null; cambiar(Fase.FALLO, Motivo.PSK, m.optString("motivo")) }
            else -> return false
        }
        return true
    }

    private fun pedirPsk() {
        val ahora = System.currentTimeMillis() / 1000
        val vigente = Boveda.abrir(ctx, SELLADO_PSK)
        if (vigente != null && pskHasta() - ahora > RENOVAR_ANTES_S) { levantar(vigente); return }
        cambiar(Fase.ESPERANDO_PSK)
        esperarRespuesta(Fase.ESPERANDO_PSK, Motivo.SIN_RESPUESTA_PSK)
        val out = X39Core.tunelPedirPsk(servidorKemPk(), X39App.identidad.seed, parWg().publicKey.bytes, ahora + VIDA_PSK_S, ahora)
        pskPendiente = out.copyOfRange(0, 32)
        val capsula = Base64.encodeToString(out.copyOfRange(32, out.size), Base64.NO_WRAP)
        out.fill(0)
        X39App.nucleo.enviarCrudo(JSONObject().put("type", "psk").put("capsula", capsula).toString())
    }

    private fun levantar(psk: ByteArray?) {
        try {
            cambiar(Fase.LEVANTANDO)
            val kp = parWg()
            val cfg = Config.Builder()
                .setInterface(
                    Interface.Builder()
                        .setKeyPair(kp)
                        .addAddress(InetNetwork.parse(direccion()))
                        .build()
                )
                .addPeer(
                    Peer.Builder()
                        .setPublicKey(Key.fromBase64(servidorWg()))
                        .apply { if (psk != null) setPreSharedKey(Key.fromBytes(psk)) }
                        .setEndpoint(InetEndpoint.parse(endpoint()))
                        .apply { rutas().forEach { addAllowedIp(InetNetwork.parse(it)) } }
                        // Imprescindible en datos móviles: sin esto el NAT cierra el agujero.
                        .setPersistentKeepalive(25)
                        .build()
                )
                .build()
            // Dispositivo nuevo: su contador de bytes empieza en cero, así que la cuenta se reinicia aquí también.
            rxUltimo = -1L
            rxCuando = 0L
            backend!!.setState(tunel, Tunnel.State.UP, cfg)
            cambiar(if (psk != null) Fase.PROTEGIDO else Fase.PROTEGIDO_SIN_PSK)
            arrancarVigilancia()
        } catch (e: Exception) {
            cambiar(Fase.FALLO, Motivo.OTRO, e.message ?: e.javaClass.simpleName)
        }
    }

    /**
     * Qué va por el túnel. Por defecto, la red del túnel y el servidor de señalización:
     * así se protege la ruta de las llamadas sin desviar todo lo demás. Con «enrutar
     * todo el tráfico», 0.0.0.0/0.
     *
     * OJO: el endpoint del propio túnel nunca entra aquí; si entrase, el túnel se
     * mordería la cola y no levantaría.
     */
    private fun rutas(): List<String> {
        if (rutaTodo()) return listOf("0.0.0.0/0", "::/0")
        val lista = mutableListOf("10.39.0.0/24")
        ipSenalizacion()?.let { lista.add("$it/32") }
        return lista
    }

    /**
     * IP del servidor de señalización. Se resuelve aquí, que esto corre en el
     * hilo-llamada y no en el principal. Si no se puede resolver, se sigue sin ella:
     * el túnel levanta igual y las llamadas funcionan.
     */
    private fun ipSenalizacion(): String? = try {
        // Ojo: aquí `Config` es el de WireGuard; el de x39 va con nombre completo.
        val host = io.livekit.android.sample.Config.urlSenal(ctx).substringAfter("://").substringBefore("/").substringBefore(":")
        java.net.InetAddress.getByName(host).hostAddress
    } catch (e: Exception) {
        null
    }

    /**
     * Importa un fichero .conf de WireGuard: evita teclear claves a mano. La privada
     * que traiga se sella en el Keystore como la nuestra; NO queda en SharedPreferences
     * ni en el fichero.
     */
    fun importarConf(texto: String): String? = try {
        val cfg = Config.parse(java.io.BufferedReader(java.io.StringReader(texto)))
        val iface = cfg.getInterface()
        val peer = cfg.peers.firstOrNull() ?: throw IllegalStateException(ctx.getString(R.string.x39_conf_sin_peer))
        val dir = iface.addresses.firstOrNull()?.toString()
            ?: throw IllegalStateException(ctx.getString(R.string.x39_conf_sin_direccion))
        val ep = peer.endpoint.orElse(null)?.toString()
            ?: throw IllegalStateException(ctx.getString(R.string.x39_conf_sin_endpoint))
        Boveda.sellar(ctx, SELLADO_WG, iface.keyPair.privateKey.bytes)
        prefs().edit()
            .putString("direccion", dir)
            .putString("servidor_wg", peer.publicKey.toBase64())
            .putString("endpoint", ep)
            .apply()
        null
    } catch (e: Exception) {
        e.message ?: e.javaClass.simpleName
    }

    /** Generación del bucle de vigilancia: un ciclo de otra generación se apaga solo. */
    private var generacionVigilancia = 0
    private var vigilanciaViva = false

    /**
     * Arranca la vigilancia si no la hay. Se llama desde varios sitios —al levantar el
     * túnel, al encontrarlo ya levantado y al arrancar la app en frío— y ninguno puede
     * dar lugar a dos bucles corriendo a la vez: el contador de generación apaga
     * cualquier ciclo anterior.
     */
    @Synchronized
    fun arrancarVigilancia() {
        if (vigilanciaViva) return
        vigilanciaViva = true
        generacionVigilancia++
        android.util.Log.i("X39_TUNEL", "vigilancia del túnel en marcha (gen $generacionVigilancia)")
        vigilarTrafico(generacionVigilancia)
    }

    /**
     * Vigilancia continua mientras el túnel esté levantado. No basta con mirar una vez
     * al subir: hay que enterarse también de cuando DEJA de comunicar con la interfaz
     * todavía en UP, para devolver la señalización a la red normal antes de que se
     * quede hablándole a nadie.
     */
    private fun vigilarTrafico(gen: Int) {
        if (gen != generacionVigilancia) return   // ciclo de una generación vieja
        if (!tunelRealmenteArriba()) {
            synchronized(this) { vigilanciaViva = false }
            android.util.Log.i("X39_TUNEL", "túnel abajo: paro la vigilancia (gen $gen)")
            return
        }
        val comunica = tunelComunica()
        val quietoDesde = if (rxCuando == 0L) -1 else (SystemClock.elapsedRealtime() - rxCuando) / 1000
        android.util.Log.i(
            "X39_TUNEL_RX",
            "peerRx=$rxLeido comunica=$comunica sinCrecer=${quietoDesde}s senal=" +
                (senalInternaPuesta ?: "red-normal"),
        )
        revisarAsentamiento()
        sincronizarSenalizacion()
        relojTrafico.postDelayed({ vigilarTrafico(gen) }, 10_000L)
    }

    private fun cambiar(f: Fase, m: Motivo = Motivo.NINGUNO, detalle: String = "") {
        // Cualquier cambio de fase cancela la espera anterior: ya sabemos en qué estamos.
        relojEspera.removeCallbacksAndMessages(null)
        fase = f
        motivo = m
        motivoDetalle = detalle
        sincronizarSenalizacion()
        oyente?.tunelCambio()
    }

    /**
     * El servidor puede no contestar nunca (hoy mismo: si no tiene el bloque del túnel,
     * el `wg_alta` cae en saco roto). Sin esto el interruptor se quedaba «dando de alta…»
     * para siempre y el usuario no sabía qué pasaba. Pasado el plazo, el túnel se apaga
     * solo y dice por qué.
     */
    private fun esperarRespuesta(faseEsperada: Fase, queFalto: Motivo) {
        relojEspera.removeCallbacksAndMessages(null)
        relojEspera.postDelayed({
            if (fase == faseEsperada) {
                prefs().edit().putBoolean("activado", false).apply()
                runCatching { backend?.setState(tunel, Tunnel.State.DOWN, null) }
                fase = Fase.FALLO
                motivo = queFalto
                motivoDetalle = ""
                sincronizarSenalizacion()
                oyente?.tunelCambio()
                android.util.Log.w("X39_TUNEL", "sin respuesta del servidor: $queFalto")
            }
        }, ESPERA_MAX_MS)
    }

    /** Texto para Ajustes: qué pasa de verdad. Se resuelve con el contexto de la pantalla que lo pinta. */
    fun estado(c: Context): String = when (fase) {
        Fase.APAGADO -> c.getString(R.string.x39_tunel_estado_apagado)
        Fase.SIN_VPN_PERMISO -> c.getString(R.string.x39_tunel_estado_sin_permiso)
        Fase.ESPERANDO_ALTA -> c.getString(R.string.x39_tunel_estado_alta)
        Fase.ESPERANDO_PSK -> c.getString(R.string.x39_tunel_estado_psk)
        Fase.LEVANTANDO -> c.getString(R.string.x39_tunel_estado_levantando)
        Fase.PROTEGIDO -> c.getString(R.string.x39_tunel_estado_protegido, direccion(), hora(c, pskHasta()))
        Fase.PROTEGIDO_SIN_PSK -> c.getString(R.string.x39_tunel_estado_protegido_sin_psk, direccion())
        Fase.FALLO -> when (motivo) {
            Motivo.CAYO -> c.getString(R.string.x39_tunel_fallo_cayo)
            Motivo.ALTA -> c.getString(R.string.x39_tunel_fallo_alta, motivoDetalle)
            Motivo.PSK -> c.getString(R.string.x39_tunel_fallo_psk, motivoDetalle)
            Motivo.SIN_RESPUESTA_ALTA -> c.getString(R.string.x39_tunel_fallo_sin_respuesta_alta)
            Motivo.SIN_RESPUESTA_PSK -> c.getString(R.string.x39_tunel_fallo_sin_respuesta_psk)
            Motivo.OTRO, Motivo.NINGUNO -> c.getString(R.string.x39_tunel_fallo_otro, motivoDetalle)
        }
    }

    /** Fecha y hora cortas, con el idioma de la configuración (los dígitos no cambian). */
    private fun hora(c: Context, unix: Long): String =
        java.text.SimpleDateFormat(
            "dd/MM HH:mm",
            androidx.core.os.ConfigurationCompat.getLocales(c.resources.configuration)[0] ?: java.util.Locale.getDefault(),
        ).format(java.util.Date(unix * 1000))
}
