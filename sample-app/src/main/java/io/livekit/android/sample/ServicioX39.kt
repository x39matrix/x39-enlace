/*
 * x39 · ServicioX39 — el que sostiene la línea.
 *
 * Sin esto la app solo suena si está abierta: en cuanto la pantalla deja de estar
 * delante, Android mata el proceso y el WebSocket de la señalización se cae a los
 * pocos segundos (se veía en el log del servidor: "registro OK" y "cierre" 9-13 s
 * después). Este servicio, en primer plano y START_STICKY, mantiene el proceso vivo
 * y con él la conexión.
 *
 * Es el dueño de la línea: es quien llama a X39App.asegurar(), quien vigila la red
 * y quien hace sonar el móvil. Las pantallas se enganchan a él (X39App.nucleo), no
 * al revés, y pueden ir y venir sin que la línea se caiga.
 *
 * NO usa Google: ni Firebase ni FCM. El aviso llega por el propio socket.
 */
package io.livekit.android.sample

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationCompat

class ServicioX39 : Service(), BusUi.Fondo {

    private val principal = Handler(Looper.getMainLooper())
    private var gestorRed: ConnectivityManager? = null
    private var vigilanteRed: ConnectivityManager.NetworkCallback? = null
    private var vigilanteFisico: ConnectivityManager.NetworkCallback? = null

    private var enLlamada = false
    private var nombreEnLlamada = ""
    private var desdeCuando = 0L

    override fun onBind(intent: Intent?): IBinder? = null   // no hace falta atarse: se habla por BusUi

    override fun onCreate() {
        super.onCreate()
        crearCanales()
        // Arrancar en primer plano cuanto antes: Android da unos segundos y luego mata.
        enPrimerPlano(notificacionEnLinea(BusUi.enLinea))
        BusUi.fondo = this
        X39App.asegurar(this)          // levanta identidad, agenda y núcleo; abre el socket
        vigilarRed()
        programarVigilante(this)
        vaciarCola()
        android.util.Log.i(TAG, "ServicioX39 en marcha: la línea la sostiene el servicio")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACCION_CONTESTAR -> {
                X39App.nucleo.aceptar()
                quitarEntrante()
                abrirEntranteONada()
            }
            ACCION_RECHAZAR -> {
                X39App.nucleo.rechazar()
                quitarEntrante()
            }
            ACCION_VIGILAR -> {
                // Vigilante de Doze: si el socket murió, reconecta y vuelve a registrarse.
                if (!X39App.nucleo.conectado()) {
                    android.util.Log.i(TAG, "vigilante: el socket estaba muerto, reconectando")
                    X39App.nucleo.reconectarYa()
                }
                programarVigilante(this)
            }
        }
        // Arranque sin acción (p. ej. tras cambiar de idioma en Ajustes): el aviso fijo y los canales
        // se vuelven a escribir con el idioma actual.
        if (intent?.action == null && !enLlamada) {
            crearCanales()
            enPrimerPlano(notificacionEnLinea(BusUi.enLinea))
        }
        // START_STICKY: si el sistema nos mata por memoria, que nos vuelva a levantar.
        return START_STICKY
    }

    /**
     * Cada minuto se reintenta la cola de mensajes. No sale de la cola nada que no
     * haya acusado el aparato del destinatario, así que esto es lo que hace que un
     * mensaje llegue cuando el otro vuelve a estar en línea.
     */
    private fun vaciarCola() {
        principal.postDelayed({
            runCatching { Mensajes.reintentarTodo() }
            vaciarCola()
        }, 60_000L)
    }

    override fun onDestroy() {
        principal.removeCallbacksAndMessages(null)
        if (BusUi.fondo === this) BusUi.fondo = null
        vigilanteRed?.let { runCatching { gestorRed?.unregisterNetworkCallback(it) } }
        vigilanteRed = null
        vigilanteFisico?.let { runCatching { gestorRed?.unregisterNetworkCallback(it) } }
        vigilanteFisico = null
        super.onDestroy()
    }

    // ===== BusUi.Fondo =========================================================

    override fun enLinea(si: Boolean) {
        if (!enLlamada) enPrimerPlano(notificacionEnLinea(si))
    }

    override fun sonando(bh: String, nombre: String, callHash: String, conVideo: Boolean) {
        avisarEntrante(nombre, conVideo)
        // Pantalla completa: si el móvil está bloqueado, la lanza el propio sistema por
        // el fullScreenIntent; si la app está delante, la abrimos nosotros.
        if (BusUi.oyente != null) abrirEntrante(nombre, conVideo)
    }

    override fun enSala(
        claveBase64: String, sas: String, token: String,
        nombreOtro: String, conVideo: Boolean,
    ) {
        quitarEntrante()
        enLlamada = true
        nombreEnLlamada = nombreOtro
        desdeCuando = System.currentTimeMillis()
        enPrimerPlano(notificacionEnLlamada(), conMedios = true)
        // Un solo camino para abrir la llamada, venga de donde venga la aceptación:
        // desde la pantalla de entrante, desde el menú o desde la notificación.
        if (!X39EnLlamadaActivity.viva) {
            android.util.Log.i(TAG, "abriendo la pantalla de llamada · conVideo=$conVideo")
            X39EnLlamadaActivity.abrir(
                this, nombreOtro, sas, claveBase64, token, Config.urlLiveKit(this), conVideo,
            )
        }
    }

    /**
     * Mensaje nuevo con la app en segundo plano: se dice DE QUIÉN, nunca QUÉ.
     * El texto no aparece en la pantalla de bloqueo.
     */
    fun mensajeDe(bh: String, nombre: String) {
        val abrir = PendingIntent.getActivity(
            this, bh.hashCode(),
            Intent(this, X39ChatActivity::class.java)
                .putExtra(X39ChatActivity.EXTRA_BH, bh)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(this, CANAL_MENSAJES)
            .setSmallIcon(R.drawable.x39_triangulo)
            .setContentTitle(getString(R.string.x39_notif_mensaje_nuevo, nombre))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setContentIntent(abrir)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .build()
        nm().notify(ID_MENSAJE_BASE + (bh.hashCode() and 0xffff), n)
    }

    override fun finLlamada() {
        quitarEntrante()
        if (!enLlamada) return
        enLlamada = false
        enPrimerPlano(notificacionEnLinea(BusUi.enLinea))
    }

    // ===== Notificaciones ======================================================

    /**
     * Notificación permanente, de baja prioridad y sin sonido: es el precio honesto de
     * que la línea siga abierta. MIUI la enseña siempre; no se puede esconder.
     */
    private fun notificacionEnLinea(si: Boolean): Notification =
        NotificationCompat.Builder(this, CANAL_LINEA)
            .setSmallIcon(R.drawable.x39_triangulo)
            .setContentTitle(getString(if (si) R.string.x39_notif_en_linea else R.string.x39_notif_reconectando))
            .setContentText(
                getString(
                    if (TunelX39.arriba()) R.string.x39_notif_linea_tunel
                    else R.string.x39_notif_linea_abierta,
                ),
            )
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setSilent(true)
            .setOngoing(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(aLaApp())
            .build()

    /** Durante la llamada: "En llamada" con el cronómetro que lleva el propio sistema. */
    private fun notificacionEnLlamada(): Notification =
        NotificationCompat.Builder(this, CANAL_LINEA)
            .setSmallIcon(R.drawable.x39_triangulo)
            .setContentTitle(getString(R.string.x39_notif_en_llamada))
            .setContentText(nombreEnLlamada)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .setOngoing(true)
            .setUsesChronometer(true)
            .setWhen(desdeCuando)
            .setShowWhen(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setContentIntent(aLaApp())
            .build()

    /**
     * Llamada entrante: canal de importancia alta, con tono y vibración, categoría de
     * llamada, dos acciones y el fullScreenIntent que la saca a pantalla completa aunque
     * el móvil esté bloqueado.
     */
    private fun avisarEntrante(nombre: String, conVideo: Boolean) {
        val pantalla = PendingIntent.getActivity(
            this, 20,
            X39EntranteActivity.intento(this, nombre, conVideo),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(this, CANAL_ENTRANTE)
            .setSmallIcon(R.drawable.x39_triangulo)
            .setContentTitle(getString(if (conVideo) R.string.x39_notif_videollamada_entrante else R.string.x39_notif_llamada_entrante))
            .setContentText(nombre)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)
            .setAutoCancel(false)
            .setFullScreenIntent(pantalla, true)
            .setContentIntent(pantalla)
            .addAction(0, getString(R.string.x39_rechazar), aServicio(ACCION_RECHAZAR, 21))
            // OJO: «Contestar» tiene que abrir una ACTIVITY, no llamar al servicio.
            // Un servicio en segundo plano no puede lanzar pantallas (Android lo bloquea
            // sin avisar), y entonces se aceptaba la llamada pero no se abría nada.
            // Al pulsar una acción que apunta a una Activity, el sistema sí lo permite.
            .addAction(
                0, getString(R.string.x39_contestar),
                PendingIntent.getActivity(
                    this, 22,
                    X39EntranteActivity.intento(this, nombre, conVideo, aceptarYa = true),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .build()
        nm().notify(ID_ENTRANTE, n)

        if (!puedePantallaCompleta()) {
            android.util.Log.w(
                TAG,
                "sin permiso de notificación a pantalla completa: la llamada entra como aviso " +
                    "normal. Se avisa en Ajustes › Recepción en segundo plano.",
            )
        }
    }

    private fun quitarEntrante() = nm().cancel(ID_ENTRANTE)

    private fun abrirEntrante(nombre: String, conVideo: Boolean) {
        startActivity(
            X39EntranteActivity.intento(this, nombre, conVideo)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    private fun abrirEntranteONada() {
        // Tras contestar desde la notificación, sacar la app delante para ver la llamada.
        startActivity(
            Intent(this, X39CallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    /**
     * Al pulsar la notificación: si la pantalla de llamada sigue viva se vuelve a ella
     * (la trae al frente sin recrearla, por el launchMode singleTask); si no, a la app.
     */
    private fun aLaApp(): PendingIntent {
        val destino = if (enLlamada && X39EnLlamadaActivity.viva) {
            Intent(this, X39EnLlamadaActivity::class.java)
        } else {
            Intent(this, X39CallActivity::class.java)
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(
            this, 10, destino,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun aServicio(accion: String, codigo: Int): PendingIntent = PendingIntent.getService(
        this, codigo,
        Intent(this, ServicioX39::class.java).setAction(accion),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /**
     * En Android 14 hay que declarar de qué tipo es el servicio. Mientras solo sostiene
     * el socket es `dataSync`; durante la llamada añade micrófono y cámara.
     */
    private fun enPrimerPlano(n: Notification, conMedios: Boolean = false) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Sostener el socket: "dataSync" es lo natural, pero Android 15 lo corta a las
            // 6 h al día y una línea de llamadas tiene que estar siempre. Desde Android 14
            // el tipo es "specialUse", que no tiene ese tope.
            val sostener = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            }
            val tipo = if (conMedios && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                sostener or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            } else {
                sostener
            }
            runCatching { startForeground(ID_LINEA, n, tipo) }
                .onFailure { android.util.Log.e(TAG, "no pude ir a primer plano", it) }
        } else {
            startForeground(ID_LINEA, n)
        }
    }

    private fun crearCanales() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val g = nm()
        g.createNotificationChannel(
            NotificationChannel(CANAL_LINEA, getString(R.string.x39_canal_linea), NotificationManager.IMPORTANCE_LOW).apply {
                description = getString(R.string.x39_canal_linea_desc)
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
            },
        )
        g.createNotificationChannel(
            NotificationChannel(CANAL_ENTRANTE, getString(R.string.x39_canal_entrantes), NotificationManager.IMPORTANCE_HIGH).apply {
                description = getString(R.string.x39_canal_entrantes_desc)
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 700, 600, 700, 600)
                setSound(
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            },
        )
        g.createNotificationChannel(
            NotificationChannel(CANAL_MENSAJES, getString(R.string.x39_canal_mensajes), NotificationManager.IMPORTANCE_HIGH).apply {
                description = getString(R.string.x39_canal_mensajes_desc)
                // En la pantalla de bloqueo solo se ve el nombre, nunca el contenido.
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            },
        )
    }

    private fun nm() = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

    private fun puedePantallaCompleta(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE || nm().canUseFullScreenIntent()

    // ===== Red =================================================================

    /** Qué transporte llevaba la red por defecto, para detectar wifi↔datos↔VPN. */
    private var transporteAnterior = -1

    /** Qué redes iban POR DEBAJO de la VPN la última vez (Android 12+). */
    private var subyacenteAnterior = ""

    /** Redes físicas vivas ahora mismo: identificador → nombre del transporte. */
    private val fisicas = HashMap<String, String>()

    /** Último aviso atendido, COMPARTIDO por los dos vigilantes. */
    private var ultimoAviso = 0L

    /**
     * Vigila la red del móvil. Va en el servicio, no en una pantalla: así sigue
     * enterándose con la app en segundo plano, que es cuando más falta hace.
     *
     * Son DOS vigilantes, y hacen falta los dos:
     *
     *  · El de la red POR DEFECTO ve subir y bajar la VPN, pero con el túnel puesto
     *    se queda ciego para lo de debajo: la red por defecto es la VPN y sigue siendo
     *    la misma aunque el wifi caiga y entren los datos. Se comprobó en el corte del
     *    21-sep: el wifi estuvo un minuto apagado y no saltó ni un aviso.
     *  · El de las redes FÍSICAS (INTERNET y NOT_VPN) sí ve ese relevo, porque mira las
     *    redes de verdad, no la que la app tiene delante.
     *
     * Cualquier cambio es información nueva: se reconecta la señalización y se le dice
     * al túnel que anule su espera y se asome ya.
     */
    private fun vigilarRed() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        gestorRed = cm

        // ---- Vigilante 1: la red por defecto (ve la VPN subir y bajar) -----------
        val porDefecto = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(red: Network) = avisar(this@ServicioX39, "red disponible")

            override fun onLost(red: Network) {
                transporteAnterior = -1
                subyacenteAnterior = ""
                avisar(this@ServicioX39, "red perdida")
            }

            override fun onCapabilitiesChanged(red: Network, cap: NetworkCapabilities) {
                /*
                 * El transporte FÍSICO manda, y la VPN va la última. Antes se miraba
                 * TRANSPORT_VPN primero: con el túnel puesto siempre valía "vpn", los
                 * demás casos no se llegaban a mirar nunca y el `return` de abajo se
                 * tragaba todos los cambios. Una red de VPN suele traer también los
                 * transportes de debajo, así que mirándolos en este orden el relevo
                 * wifi→datos se ve aunque el túnel esté puesto.
                 */
                val t = when {
                    cap.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> 1
                    cap.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> 2
                    cap.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> 4
                    cap.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> 3
                    else -> 0
                }
                /*
                 * Qué lleva la VPN POR DEBAJO. getUnderlyingNetworks() sería lo suyo,
                 * pero no está en el SDK público (es API oculta, no aparece en el
                 * android.jar de la 35) y tirar de reflexión aquí no es de fiar.
                 * La vía pública que sirve igual: una red de VPN declara también los
                 * transportes de las redes que lleva debajo, así que la FIRMA de sus
                 * transportes cambia cuando el relevo pasa de wifi a datos, aunque
                 * arriba siga siendo la misma VPN.
                 */
                val debajo = firmaTransportes(cap)
                val cambioDebajo = debajo != subyacenteAnterior
                val antesDebajo = subyacenteAnterior
                subyacenteAnterior = debajo

                if (t != transporteAnterior) {
                    val antes = transporteAnterior
                    transporteAnterior = t
                    if (antes != -1) {
                        avisar(
                            this@ServicioX39,
                            "transporte ${nombreTransporte(antes)} → ${nombreTransporte(t)}",
                        )
                        return
                    }
                }
                // Primera lectura del relevo: no es un cambio, solo el punto de partida.
                if (cambioDebajo && antesDebajo.isNotEmpty() && debajo.isNotEmpty()) {
                    avisar(this@ServicioX39, "la VPN cambió por debajo ($antesDebajo → $debajo)")
                }
            }
        }

        // ---- Vigilante 2: las redes físicas (ve el wifi↔datos bajo el túnel) -----
        val fisico = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(red: Network) {
                val cap = runCatching { cm.getNetworkCapabilities(red) }.getOrNull()
                val nombre = nombreTransporte(transporteDe(cap))
                fisicas[red.toString()] = nombre
                avisar(this@ServicioX39, "red física disponible ($nombre)")
            }

            override fun onLost(red: Network) {
                val nombre = fisicas.remove(red.toString()) ?: "desconocida"
                avisar(this@ServicioX39, "red física perdida ($nombre)")
            }
        }

        vigilanteRed = porDefecto
        vigilanteFisico = fisico
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                cm.registerDefaultNetworkCallback(porDefecto)
            } else {
                cm.registerNetworkCallback(
                    NetworkRequest.Builder()
                        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        .build(),
                    porDefecto,
                )
            }
            android.util.Log.i(TAG, "vigilancia de red registrada (red por defecto)")
        }.onFailure {
            android.util.Log.e(TAG, "no se pudo registrar la vigilancia de red por defecto", it)
        }
        runCatching {
            cm.registerNetworkCallback(
                NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                    .build(),
                fisico,
            )
            android.util.Log.i(TAG, "vigilancia de red registrada (redes físicas)")
        }.onFailure {
            android.util.Log.e(TAG, "no se pudo registrar la vigilancia de redes físicas", it)
        }
    }

    /**
     * Un aviso de cambio de red, venga del vigilante que venga.
     *
     * La ventana de 3 s es COMÚN a los dos: un solo apagón de wifi dispara el "red
     * física perdida" de uno y el "transporte wifi → datos" del otro casi a la vez, y
     * eso es un cambio, no dos.
     */
    private fun avisar(servicio: ServicioX39, motivo: String) {
        val ahora = SystemClock.elapsedRealtime()
        if (ahora - ultimoAviso < COLAPSA_AVISOS_MS) {
            android.util.Log.i(TAG, "cambio de red: $motivo (colapsado, ya hubo uno hace poco)")
            return
        }
        ultimoAviso = ahora
        servicio.principal.post {
            android.util.Log.i(TAG, "cambio de red: $motivo")
            runCatching { X39App.nucleo.reconectarYa() }
            runCatching { TunelX39.cambioDeRed(motivo) }
        }
    }

    /** Todos los transportes que declara una red, en orden fijo: "wifi+vpn", "datos+vpn"… */
    private fun firmaTransportes(cap: NetworkCapabilities?): String {
        if (cap == null) return ""
        val partes = ArrayList<String>(4)
        if (cap.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) partes.add("wifi")
        if (cap.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) partes.add("datos")
        if (cap.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) partes.add("cable")
        if (cap.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) partes.add("vpn")
        return partes.joinToString("+")
    }

    private fun transporteDe(cap: NetworkCapabilities?): Int = when {
        cap == null -> 0
        cap.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> 1
        cap.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> 2
        cap.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> 4
        cap.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> 3
        else -> 0
    }

    private fun nombreTransporte(t: Int): String = when (t) {
        1 -> "wifi"; 2 -> "datos"; 3 -> "vpn"; 4 -> "cable"; 0 -> "otro"; else -> "ninguno"
    }

    companion object {
        private const val TAG = "X39_SERVICIO"

        /** Ventana común a los dos vigilantes: un cambio que los dispara a la vez es uno. */
        private const val COLAPSA_AVISOS_MS = 3_000L

        const val CANAL_LINEA = "x39_linea"
        const val CANAL_ENTRANTE = "x39_entrante"
        const val CANAL_MENSAJES = "x39_mensajes"

        private const val ID_LINEA = 3901
        private const val ID_ENTRANTE = 3902
        private const val ID_MENSAJE_BASE = 39100

        const val ACCION_CONTESTAR = "x39.CONTESTAR"
        const val ACCION_RECHAZAR = "x39.RECHAZAR"
        const val ACCION_VIGILAR = "x39.VIGILAR"

        private const val CADA_MS = 10 * 60 * 1000L

        /** Arranca el servicio (desde la app, desde el arranque del móvil o desde el vigilante). */
        fun arrancar(ctx: Context) {
            val i = Intent(ctx, ServicioX39::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(i)
            } else {
                ctx.startService(i)
            }
        }

        /**
         * Vigilante para Doze: cada 10 minutos comprueba el socket y se vuelve a registrar
         * si murió. setExactAndAllowWhileIdle es el único que despierta al móvil dormido.
         */
        fun programarVigilante(ctx: Context) {
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pi = PendingIntent.getService(
                ctx, 30,
                Intent(ctx, ServicioX39::class.java).setAction(ACCION_VIGILAR),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val cuando = SystemClock.elapsedRealtime() + CADA_MS
            runCatching {
                am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, cuando, pi)
            }.onFailure {
                // Sin permiso de alarma exacta: al menos que despierte aproximado.
                runCatching { am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, cuando, pi) }
            }
        }
    }
}
