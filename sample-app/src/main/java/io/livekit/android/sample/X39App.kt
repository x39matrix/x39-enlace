package io.livekit.android.sample

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.File
import org.x39.bolsillo.Agenda
import org.x39.bolsillo.Identidad
import org.x39.bolsillo.NucleoLlamadas

/**
 * Cerebro de x39 a nivel de proceso, SIN necesitar una Application propia (así no
 * choca con la que ya trae el proyecto de LiveKit). Es un singleton perezoso: la
 * primera pantalla llama a asegurar() y a partir de ahí sostiene la identidad, la
 * agenda y el núcleo de llamadas (que mantiene el WebSocket abierto y te deja
 * "en línea"). Vive mientras vive el proceso; las pantallas van y vienen.
 *
 * Los eventos del núcleo llegan en su hilo; aquí se llevan al hilo principal y se
 * reparten por BusUi a la pantalla que esté delante.
 */
object X39App {

    lateinit var identidad: Identidad
        private set
    lateinit var agenda: Agenda
        private set
    lateinit var nucleo: NucleoLlamadas
        private set

    @Volatile private var listo = false
    private val principal = Handler(Looper.getMainLooper())

    @Synchronized
    fun asegurar(ctx: Context) {
        if (listo) return
        val app = ctx.applicationContext

        identidad = Boveda.identidad(app)
        agenda = Agenda(
            File(app.filesDir, "agenda.txt"),
            object : Agenda.Almacen {
                override fun leer(): ByteArray? = Boveda.abrir(app, "agenda")
                override fun escribir(datos: ByteArray) = Boveda.sellar(app, "agenda", datos)
            }
        )

        val eventos = object : NucleoLlamadas.Eventos {
            override fun enLinea(si: Boolean) {
                principal.post {
                    BusUi.enLinea = si
                    BusUi.fondo?.enLinea(si)
                    BusUi.oyente?.enLinea(si)
                    // Al volver la línea, sale lo que estaba esperando en la cola del móvil.
                    if (si) Mensajes.reintentarTodo()
                }
            }
            override fun sonando(bhOtro: String, nombre: String, callHash: String, conVideo: Boolean) {
                principal.post {
                    BusUi.entrante = BusUi.Entrante(bhOtro, nombre, callHash, conVideo)
                    // El servicio primero: es quien hace sonar el móvil aunque no haya pantalla delante.
                    BusUi.fondo?.sonando(bhOtro, nombre, callHash, conVideo)
                    BusUi.oyente?.sonando(bhOtro, nombre, callHash, conVideo)
                }
            }
            override fun listaParaLiveKit(
                claveBase64: String, sas: String, token: String, room: String,
                soyLlamante: Boolean, bhOtro: String, nombreOtro: String, conVideo: Boolean
            ) {
                principal.post {
                    BusUi.entrante = null
                    BusUi.fondo?.enSala(claveBase64, sas, token, nombreOtro, conVideo)
                    BusUi.oyente?.lista(claveBase64, sas, token, room, soyLlamante, bhOtro, nombreOtro, conVideo)
                }
            }
            override fun colgado() {
                principal.post { BusUi.entrante = null; BusUi.fondo?.finLlamada(); BusUi.oyente?.colgado() }
            }
            override fun fallo(motivo: String) {
                principal.post { BusUi.entrante = null; BusUi.fondo?.finLlamada(); BusUi.oyente?.fallo(motivo) }
            }
        }

        nucleo = NucleoLlamadas(
            identidad, agenda,
            Config.urlSenal(app), Config.pinSenal(app),
            eventos
        )
        Mensajes.init(app)
        Fotos.init(app)
        Adjuntos.init(app)
        nucleo.escuchaMensajes(object : NucleoLlamadas.EscuchaMensajes {
            override fun sobreRecibido(deBh: String, id: String, sobre: String) {
                principal.post { Mensajes.recibido(deBh, id, sobre) }
            }
            override fun acuseRecibo(id: String, entregado: Boolean) {
                principal.post { Mensajes.acuse(id, entregado) }
            }
        })

        // Aviso de que la señalización no tira por la dirección del túnel. Va aquí, con
        // el resto de escuchas y sin runCatching: si esto falla hay que enterarse, no
        // tragárselo — es lo que decide cuándo salir de la ruta interna.
        nucleo.avisoRutaSenal(object : org.x39.chat.TransporteWs.AvisoRuta {
            override fun rutaInternaFalla(fallosSeguidos: Int) {
                principal.post { TunelX39.senalNoTiraPorElTunel(fallosSeguidos) }
            }
            override fun rutaInternaAbre() {
                principal.post { TunelX39.senalAbrePorElTunel() }
            }
        })
        android.util.Log.i("X39_TUNEL", "aviso de ruta de señalización enganchado")

        // x39tunel: escucha los mensajes de alta/PSK de la señalización; solo actúa si está encendido.
        TunelX39.asegurar(app)
        nucleo.escuchaTunel(TunelX39)
        nucleo.arrancar()   // te pone en línea (registro firmado) al abrir la app
        listo = true
    }
}

/** Reparte los eventos del núcleo a la pantalla visible, y recuerda el último estado. */
object BusUi {
    data class Entrante(val bh: String, val nombre: String, val callHash: String, val conVideo: Boolean)

    /** La pantalla que está delante (puede no haber ninguna). */
    var oyente: Oyente? = null

    /**
     * El ServicioX39, que está SIEMPRE: es quien hace sonar el móvil y quien mantiene
     * la notificación permanente. Recibe todo, haya pantalla delante o no.
     */
    var fondo: Fondo? = null

    var enLinea: Boolean = false
    var entrante: Entrante? = null   // llamada entrante aún sin atender

    /** Lo que necesita saber el servicio en primer plano. */
    interface Fondo {
        fun enLinea(si: Boolean)
        fun sonando(bh: String, nombre: String, callHash: String, conVideo: Boolean)
        /**
         * La llamada está acordada y lista. El servicio es quien ABRE la pantalla: es lo
         * único que está siempre. Antes lo hacía la pantalla que tuviera el foco, y si
         * no había ninguna (el otro estaba en Mensajes, o aceptó desde la notificación)
         * el aviso se perdía y la llamada no llegaba a establecerse.
         */
        fun enSala(
            claveBase64: String, sas: String, token: String,
            nombreOtro: String, conVideo: Boolean,
        )
        fun finLlamada()
    }

    interface Oyente {
        fun enLinea(si: Boolean)
        fun sonando(bh: String, nombre: String, callHash: String, conVideo: Boolean)
        fun lista(
            claveBase64: String, sas: String, token: String, room: String,
            soyLlamante: Boolean, bhOtro: String, nombreOtro: String, conVideo: Boolean
        )
        fun colgado()
        fun fallo(motivo: String)
    }

    /**
     * Texto que se enseña para un motivo de fallo. Los del núcleo llegan como código
     * y aquí se traducen al idioma de la app en ese momento; cualquier otro (lo que
     * cuenta el servidor, el mensaje de una excepción) se enseña tal cual.
     */
    fun textoFallo(c: android.content.Context, motivo: String): String = when (motivo) {
        org.x39.bolsillo.NucleoLlamadas.MOTIVO_NO_EN_AGENDA -> c.getString(R.string.x39_fallo_no_en_agenda)
        org.x39.bolsillo.Llamada.MOTIVO_NO_DISPONIBLE -> c.getString(R.string.x39_fallo_no_disponible)
        org.x39.bolsillo.Llamada.MOTIVO_ERROR_SERVIDOR -> c.getString(R.string.x39_fallo_error_servidor)
        else -> motivo
    }
}
