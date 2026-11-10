/*
 * x39 · pantalla "En llamada" (la de la maqueta).
 *
 * Una sola pantalla para voz y para vídeo:
 *   · En VOZ: avatar grande con iniciales, nombre, cronómetro, el candado del
 *     cifrado, la tarjeta con las seis palabras y tres botones redondos.
 *   · En VÍDEO: exactamente lo mismo, pero el avatar lo sustituye el vídeo del
 *     otro a pantalla completa, con un velo negro arriba y abajo para que todo
 *     lo demás se siga leyendo, y el propio vídeo en pequeño arriba a la derecha.
 *
 * Esta pantalla NO acuerda ninguna clave: la clave de los fotogramas
 * (X39-CALL-KEY) ya viene acordada por el núcleo, y aquí solo se le entrega a
 * LiveKit. El servidor reparte los paquetes y no puede descifrarlos.
 *
 */
package io.livekit.android.sample

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.twilio.audioswitch.AudioDevice
import io.livekit.android.LiveKit
import io.livekit.android.RoomOptions
import io.livekit.android.audio.AudioSwitchHandler
import io.livekit.android.e2ee.E2EEOptions
import io.livekit.android.e2ee.E2EEState
import io.livekit.android.events.RoomEvent
import io.livekit.android.events.collect
import io.livekit.android.renderer.SurfaceViewRenderer
import io.livekit.android.room.Room
import io.livekit.android.room.participant.VideoTrackPublishDefaults
import io.livekit.android.room.track.CameraPosition
import io.livekit.android.room.track.LocalVideoTrack
import io.livekit.android.room.track.LocalVideoTrackOptions
import io.livekit.android.room.track.Track
import io.livekit.android.room.track.VideoPreset169
import io.livekit.android.room.track.VideoTrack
import io.livekit.android.room.track.video.CameraCapturerUtils
import kotlinx.coroutines.launch
import livekit.org.webrtc.CameraXHelper
import livekit.org.webrtc.RendererCommon.ScalingType
import kotlin.math.abs

class X39EnLlamadaActivity : AppCompatActivity(), BusUi.Oyente {

    // Paleta de x39. Sin verde en ningún sitio.
    private val grafito = Color.parseColor("#090A0D")
    private val sup = Color.parseColor("#12141A")
    private val sup2 = Color.parseColor("#12141A")
    private val linea = Color.parseColor("#492327")
    private val rojo = Color.parseColor("#FF1E1E")
    private val claro = Color.parseColor("#FFFFFF")
    private val gris = Color.parseColor("#A0AAB8")
    private val tenue = Color.parseColor("#A0AAB8")
    private val ambar = Color.parseColor("#F7931A")

    // Datos de la llamada, tal y como los entrega el núcleo.
    private var nombre = ""   // se rellena en onCreate
    private var sas = ""
    private var claveBase64 = ""
    private var token = ""
    private var url = ""
    private var conVideo = false

    private var sala: Room? = null
    private var proveedorCamara: CameraCapturerUtils.CameraProvider? = null

    // Vistas
    private lateinit var raiz: FrameLayout
    private lateinit var videoRemoto: SurfaceViewRenderer
    private lateinit var velo: View
    private lateinit var marcoPrevio: FrameLayout
    private lateinit var videoLocal: SurfaceViewRenderer
    private lateinit var columna: LinearLayout
    private lateinit var avatarGrande: View
    private lateinit var tvEstado: TextView
    private lateinit var btnMic: FrameLayout
    private lateinit var btnCamara: FrameLayout
    private lateinit var icMic: ImageView
    private lateinit var icCamara: ImageView

    // Pistas realmente pegadas a cada renderer (para despegarlas exactamente una vez).
    private var pistaRemota: VideoTrack? = null
    private var pistaLocal: VideoTrack? = null

    private val reloj = Handler(Looper.getMainLooper())
    private var inicio = 0L
    private var corriendo = false
    private var cerrando = false
    private var micEncendido = true
    private var camaraEncendida = false
    private var sinPermisoCamara = false

    // Huecos del sistema, guardados para poder recalcular el relleno de la columna
    // cuando aparece o desaparece el vídeo propio.
    private var hueL = 0
    private var hueT = 0
    private var hueR = 0
    private var hueB = 0

    private val tic = object : Runnable {
        override fun run() {
            val s = (SystemClock.elapsedRealtime() - inicio) / 1000
            tvEstado.text = String.format(java.util.Locale.ROOT, "%02d:%02d", s / 60, s % 60)
            reloj.postDelayed(this, 1000)
        }
    }

    private val permisos =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
            val micOk = res[Manifest.permission.RECORD_AUDIO] != false
            sinPermisoCamara = res[Manifest.permission.CAMERA] == false
            if (!micOk) {
                sinMicrofono()
            } else {
                if (sinPermisoCamara && conVideo) {
                    conVideo = false
                    toast(getString(R.string.x39_llamada_sin_camara_voz))
                }
                conectar()
            }
        }

    private fun antiCaptura(): Boolean =
        getSharedPreferences("x39", MODE_PRIVATE).getBoolean("anticaptura", true)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Anti-captura: mismo interruptor que el resto de la app (Ajustes), encendido por defecto.
        if (antiCaptura()) {
            window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        }
        // Que no se apague la pantalla hablando.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        X39App.asegurar(this)

        nombre = intent.getStringExtra(EXTRA_NOMBRE) ?: getString(R.string.x39_contacto_por_defecto)
        sas = intent.getStringExtra(EXTRA_SAS) ?: ""
        claveBase64 = intent.getStringExtra(EXTRA_CLAVE) ?: ""
        token = intent.getStringExtra(EXTRA_TOKEN) ?: ""
        url = intent.getStringExtra(EXTRA_URL) ?: Config.urlLiveKit(this)
        conVideo = intent.getBooleanExtra(EXTRA_VIDEO, false)
        camaraEncendida = conVideo

        viva = true
        android.util.Log.i("X39_LLAMADA", "Abriendo pantalla de llamada · conVideo=$conVideo · sala=$url")

        setContentView(montarPantalla())
        pedirPermisos()
    }

    // ===== Permisos ============================================================

    private fun pedirPermisos() {
        // Micrófono siempre; cámara también, para poder encenderla a mitad de llamada.
        val necesarios = arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)
        val faltan = necesarios.any {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (faltan) permisos.launch(necesarios) else conectar()
    }

    /** Sin micrófono no hay llamada: se dice en pantalla, no se deja en negro. */
    private fun sinMicrofono() {
        tvEstado.text = getString(R.string.x39_llamada_sin_micro)
        val aviso = TextView(this).apply {
            text = getString(R.string.x39_llamada_sin_micro_texto)
            textSize = 13f; setTextColor(gris); gravity = Gravity.CENTER
            setPadding(dp(32), dp(12), dp(32), 0)
        }
        (avatarGrande.parent as LinearLayout).addView(
            aviso, (avatarGrande.parent as LinearLayout).indexOfChild(tvEstado) + 1,
        )
        X39App.nucleo.colgar()
    }

    // ===== LiveKit =============================================================

    private fun conectar() {
        if (claveBase64.isEmpty() || token.isEmpty()) {
            toast(getString(R.string.x39_llamada_faltan_datos)); finish(); return
        }

        // La cámara de CameraX da mejor imagen que la Camera2 de serie en muchos Xiaomi.
        CameraXHelper.createCameraProvider(ProcessLifecycleOwner.get()).let { p ->
            if (p.isSupported(this)) {
                CameraCapturerUtils.registerCameraProvider(p)
                proveedorCamara = p
            }
        }

        val sala = LiveKit.create(
            appContext = applicationContext,
            options = RoomOptions(
                adaptiveStream = true,
                dynacast = true,
                // E2EE SIEMPRE: los 32 bytes que acordó el núcleo entre los dos móviles.
                e2eeOptions = E2EEOptions(sharedKey = claveBase64),
                // 1 a 1: 720p basta y no quema datos.
                videoTrackCaptureDefaults = LocalVideoTrackOptions(
                    position = CameraPosition.FRONT,
                    captureParams = VideoPreset169.H720.capture,
                ),
                videoTrackPublishDefaults = VideoTrackPublishDefaults(
                    videoEncoding = VideoPreset169.H720.encoding,
                    simulcast = false,
                ),
            ),
        )
        this.sala = sala

        // Orden obligatorio: PRIMERO init del renderer, DESPUÉS se le pega la pista.
        sala.initVideoRenderer(videoRemoto)
        sala.initVideoRenderer(videoLocal)
        videoRemoto.setScalingType(ScalingType.SCALE_ASPECT_FILL)
        videoLocal.setScalingType(ScalingType.SCALE_ASPECT_FILL)
        videoLocal.setMirror(true)

        // La llamada la sostiene el ServicioX39, que ya está en marcha y que al entrar en
        // sala pasa su notificación a "En llamada" y añade micrófono y cámara a su tipo.
        // No se arranca el ForegroundService del ejemplo de LiveKit: sobra y duplicaría el aviso.

        enrutarAudio(conVideo)

        lifecycleScope.launch {
            sala.events.collect { ev -> alEvento(ev) }
        }

        lifecycleScope.launch {
            try {
                sala.connect(url, token)
                sala.localParticipant.setMicrophoneEnabled(true)
                sala.localParticipant.setCameraEnabled(conVideo)
                camaraEncendida = conVideo
                pintarBotones()
                refrescarPistas()
            } catch (e: Throwable) {
                android.util.Log.e("X39_LLAMADA", "No se pudo entrar en la sala", e)
                cerrarCon(getString(R.string.x39_llamada_no_conectar))
            }
        }
    }

    private fun alEvento(ev: RoomEvent) {
        when (ev) {
            is RoomEvent.Connected -> arrancarCronometro()
            is RoomEvent.Disconnected -> cerrarCon(getString(R.string.x39_llamada_finalizada))
            is RoomEvent.ParticipantDisconnected -> cerrarCon(getString(R.string.x39_llamada_ha_colgado, nombre))
            is RoomEvent.FailedToConnect -> cerrarCon(getString(R.string.x39_llamada_no_conectar))
            is RoomEvent.TrackE2EEStateEvent -> avisoE2ee(ev.state)
            is RoomEvent.TrackSubscribed,
            is RoomEvent.TrackUnsubscribed,
            is RoomEvent.TrackPublished,
            is RoomEvent.TrackUnpublished,
            is RoomEvent.LocalTrackSubscribed,
            is RoomEvent.TrackMuted,
            is RoomEvent.TrackUnmuted,
            is RoomEvent.ParticipantConnected,
            -> refrescarPistas()
            else -> Unit
        }
    }

    /**
     * Si el vídeo sale negro o como ruido de colores pero el audio va bien, la causa
     * casi siempre es que las dos puntas NO tienen la misma clave: las seis palabras
     * saldrían distintas en cada móvil. No es un fallo de la cámara, y el log lo dice así.
     */
    private fun avisoE2ee(estado: E2EEState) {
        if (estado == E2EEState.DECRYPTION_FAILED || estado == E2EEState.MISSING_KEY) {
            android.util.Log.e(
                "X39_E2EE",
                "No se descifran los fotogramas: las claves E2EE no coinciden en los dos móviles " +
                    "(las 6 palabras SAS saldrán distintas). NO es un fallo de la cámara.",
            )
        }
    }

    private fun arrancarCronometro() {
        if (corriendo) return
        corriendo = true
        inicio = SystemClock.elapsedRealtime()
        reloj.post(tic)
    }

    /**
     * El vídeo propio flota arriba a la derecha, y el nombre, el cronómetro y el candado
     * iban por debajo: el texto quedaba cortado («Cifrada de extremo a extremo · X39-»).
     * Con el preview a la vista, el bloque de texto arranca por debajo de él.
     */
    private fun ajustarColumna() {
        val hayPreview = marcoPrevio.visibility == View.VISIBLE
        // alto del preview (140) + su margen arriba (16) + un respiro (12)
        val bajar = if (hayPreview) dp(168) else 0
        columna.setPadding(
            dp(20) + hueL,
            dp(24) + hueT + bajar,
            dp(20) + hueR,
            dp(20) + hueB,
        )
    }

    /** Pega/despega las pistas de vídeo a sus renderers, sin repetir ni dejar colgado ninguno. */
    private fun refrescarPistas() {
        val sala = this.sala ?: return
        val otro = sala.remoteParticipants.values.firstOrNull()
        val pubRemota = otro?.getTrackPublication(Track.Source.CAMERA)
        val nuevaRemota = (pubRemota?.track as? VideoTrack)?.takeIf { pubRemota.muted != true }
        if (nuevaRemota !== pistaRemota) {
            pistaRemota?.removeRenderer(videoRemoto)
            pistaRemota = nuevaRemota
            nuevaRemota?.addRenderer(videoRemoto)
        }
        val hayVideoRemoto = nuevaRemota != null
        videoRemoto.visibility = if (hayVideoRemoto) View.VISIBLE else View.GONE
        velo.visibility = if (hayVideoRemoto) View.VISIBLE else View.GONE
        avatarGrande.visibility = if (hayVideoRemoto) View.GONE else View.VISIBLE

        val pubLocal = sala.localParticipant.getTrackPublication(Track.Source.CAMERA)
        val nuevaLocal = (pubLocal?.track as? LocalVideoTrack)?.takeIf { pubLocal.muted != true }
        if (nuevaLocal !== pistaLocal) {
            pistaLocal?.removeRenderer(videoLocal)
            pistaLocal = nuevaLocal
            nuevaLocal?.addRenderer(videoLocal)
        }
        nuevaLocal?.let { videoLocal.setMirror(it.options.position == CameraPosition.FRONT) }
        val previoAntes = marcoPrevio.visibility
        marcoPrevio.visibility = if (nuevaLocal != null) View.VISIBLE else View.GONE
        if (marcoPrevio.visibility != previoAntes) ajustarColumna()
    }

    // ===== Audio ===============================================================

    /**
     * En videollamada el audio sale por el altavoz; en llamada de voz, por el auricular.
     * Se hace con el AudioSwitchHandler de LiveKit (que ya pone el modo de llamada), no
     * tocando el AudioManager por debajo: así no se pelean los dos. Un auricular de cable
     * o un manos libres Bluetooth mandan siempre sobre esta preferencia.
     */
    private fun enrutarAudio(altavoz: Boolean) {
        val audio = sala?.audioHandler as? AudioSwitchHandler ?: return
        audio.preferredDeviceList = if (altavoz) {
            listOf(
                AudioDevice.BluetoothHeadset::class.java,
                AudioDevice.WiredHeadset::class.java,
                AudioDevice.Speakerphone::class.java,
                AudioDevice.Earpiece::class.java,
            )
        } else {
            listOf(
                AudioDevice.BluetoothHeadset::class.java,
                AudioDevice.WiredHeadset::class.java,
                AudioDevice.Earpiece::class.java,
                AudioDevice.Speakerphone::class.java,
            )
        }
        // Si la llamada ya está en marcha (se encendió la cámara a mitad), muévelo ahora.
        val disponibles = audio.availableAudioDevices
        val hayAuriculares = disponibles.any { it is AudioDevice.BluetoothHeadset || it is AudioDevice.WiredHeadset }
        if (!hayAuriculares) {
            val destino = disponibles.firstOrNull {
                if (altavoz) it is AudioDevice.Speakerphone else it is AudioDevice.Earpiece
            }
            if (destino != null) audio.selectDevice(destino)
        }
        android.util.Log.i("X39_AUDIO", "salida " + (if (altavoz) "altavoz" else "auricular"))
    }

    // ===== Botones =============================================================

    private fun alternarMic() {
        val sala = this.sala ?: return
        micEncendido = !micEncendido
        lifecycleScope.launch { sala.localParticipant.setMicrophoneEnabled(micEncendido) }
        pintarBotones()
    }

    /**
     * Pasar de voz a vídeo en mitad de la llamada es solo encender la cámara: no hay
     * que renegociar nada ni volver a acordar clave, porque la X39-CALL-KEY ya cifra
     * también los fotogramas de vídeo.
     */
    private fun alternarCamara() {
        val sala = this.sala ?: return
        if (sinPermisoCamara) { toast(getString(R.string.x39_llamada_sin_camara)); return }
        camaraEncendida = !camaraEncendida
        lifecycleScope.launch {
            sala.localParticipant.setCameraEnabled(camaraEncendida)
            enrutarAudio(camaraEncendida)
            pintarBotones()
            refrescarPistas()
        }
    }

    private fun girarCamara() {
        val pista = sala?.localParticipant?.getTrackPublication(Track.Source.CAMERA)?.track as? LocalVideoTrack ?: return
        val nueva = when (pista.options.position) {
            CameraPosition.FRONT -> CameraPosition.BACK
            CameraPosition.BACK -> CameraPosition.FRONT
            else -> null
        }
        pista.switchCamera(position = nueva)
        videoLocal.setMirror(nueva == CameraPosition.FRONT)
    }

    private fun pintarBotones() {
        icMic.alpha = if (micEncendido) 1f else 0.4f
        icCamara.alpha = if (camaraEncendida) 1f else 0.4f
        btnMic.contentDescription = getString(if (micEncendido) R.string.x39_llamada_silenciar_mic else R.string.x39_llamada_encender_mic)
        btnCamara.contentDescription = getString(if (camaraEncendida) R.string.x39_llamada_apagar_camara else R.string.x39_llamada_encender_camara)
    }

    private fun colgar() {
        X39App.nucleo.colgar()
        cerrarCon(getString(R.string.x39_llamada_finalizada))
    }

    /** Aviso de 1,5 s y fuera; una sola vez aunque lleguen varios avisos de corte. */
    private fun cerrarCon(motivo: String) {
        if (cerrando) return
        cerrando = true
        reloj.removeCallbacks(tic)
        tvEstado.text = motivo
        reloj.postDelayed({ if (!isFinishing) finish() }, 1500)
    }

    // ===== BusUi.Oyente (avisos de la señalización mientras hablamos) ==========

    override fun onResume() {
        super.onResume()
        BusUi.oyente = this
    }

    override fun onPause() {
        super.onPause()
        if (BusUi.oyente === this) BusUi.oyente = null
    }

    override fun enLinea(si: Boolean) = Unit
    override fun sonando(bh: String, nombre: String, callHash: String, conVideo: Boolean) = Unit
    override fun lista(
        claveBase64: String, sas: String, token: String, room: String,
        soyLlamante: Boolean, bhOtro: String, nombreOtro: String, conVideo: Boolean,
    ) = Unit
    override fun colgado() = cerrarCon(getString(R.string.x39_llamada_ha_colgado, nombre))
    override fun fallo(motivo: String) = cerrarCon(BusUi.textoFallo(this, motivo))

    // ===== Cierre limpio =======================================================

    override fun onDestroy() {
        viva = false
        reloj.removeCallbacksAndMessages(null)
        // Despegar ANTES de soltar: si no, la cámara se queda pillada y la segunda
        // llamada seguida ya no enciende vídeo.
        pistaRemota?.removeRenderer(videoRemoto)
        pistaLocal?.removeRenderer(videoLocal)
        pistaRemota = null
        pistaLocal = null
        videoRemoto.release()
        videoLocal.release()
        sala?.disconnect()
        sala?.release()
        sala = null
        proveedorCamara?.let { CameraCapturerUtils.unregisterCameraProvider(it) }
        proveedorCamara = null
        super.onDestroy()
    }

    @Deprecated("Colgar es la única salida de esta pantalla")
    override fun onBackPressed() {
        colgar()
    }

    // ===== La pantalla, montada en código =====================================

    private fun montarPantalla(): View {
        raiz = FrameLayout(this).apply { setBackgroundColor(grafito) }

        // 1) Vídeo del otro, al fondo y a pantalla completa. SurfaceView normal:
        //    va por detrás de la ventana, así todo lo demás se dibuja encima.
        videoRemoto = SurfaceViewRenderer(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
            )
            visibility = View.GONE
        }
        raiz.addView(videoRemoto)

        // 2) Velo negro arriba y abajo, solo cuando hay vídeo.
        velo = View(this).apply {
            setBackgroundResource(R.drawable.x39_degradado_llamada)
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
            )
            visibility = View.GONE
        }
        raiz.addView(velo)

        // 3) La maqueta: avatar / nombre / cronómetro / candado / palabras / botones / pie.
        columna = columna()
        raiz.addView(columna)

        // 4) El propio vídeo, pequeño y ENCIMA del grande.
        raiz.addView(previoLocal())

        // Borde a borde: el vídeo SÍ llena toda la pantalla, por debajo de las barras;
        // lo que se descuenta es el contenido, para que el nombre no se meta bajo el
        // reloj ni los botones bajo la barra gestual.
        ViewCompat.setOnApplyWindowInsetsListener(raiz) { _, insets ->
            val b = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            hueL = b.left; hueT = b.top; hueR = b.right; hueB = b.bottom
            (marcoPrevio.layoutParams as FrameLayout.LayoutParams).apply {
                setMargins(dp(16) + b.left, dp(16) + b.top, dp(16) + b.right, dp(16) + b.bottom)
            }
            marcoPrevio.requestLayout()
            ajustarColumna()
            insets
        }
        ViewCompat.requestApplyInsets(raiz)

        return raiz
    }

    private fun columna(): LinearLayout {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(20), dp(24), dp(20), dp(20))
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }

        avatarGrande = avatar(iniciales(nombre))
        col.addView(avatarGrande)

        col.addView(TextView(this).apply {
            text = nombre; textSize = 20f; setTextColor(claro); gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            layoutParams = anchoTotal(dp(20))
        })

        // Mientras no conecta dice el estado; al conectar se convierte en el cronómetro.
        tvEstado = TextView(this).apply {
            text = getString(R.string.x39_conectando); textSize = 15f; setTextColor(gris); gravity = Gravity.CENTER
            layoutParams = anchoTotal(dp(4))
        }
        col.addView(tvEstado)

        col.addView(chipCifrado())
        col.addView(tarjetaSas())

        col.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(1, 0, 1f)
        })

        col.addView(fila3Botones())
        col.addView(TextView(this).apply {
            text = pieDeRuta()
            textSize = 11f; setTextColor(tenue); gravity = Gravity.CENTER
            layoutParams = anchoTotal(dp(20)).apply { bottomMargin = dp(8) }
        })
        return col
    }

    /**
     * El pie dice la verdad según esté el x39tunel, que es otra capa: el túnel protege
     * la RUTA, no el contenido. El contenido lo protege la clave acordada entre los dos
     * móviles, que el servidor no tiene.
     */
    private fun pieDeRuta(): String =
        if (TunelX39.activado()) {
            getString(R.string.x39_llamada_pie_tunel)
        } else {
            getString(R.string.x39_llamada_pie_sin_tunel)
        }

    private fun chipCifrado(): View {
        val f = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(16); gravity = Gravity.CENTER_HORIZONTAL }
        }
        f.addView(icono(R.drawable.x39_ic_candado, ambar, 14))
        f.addView(TextView(this).apply {
            text = "  " + getString(R.string.x39_cifrada_e2e)   // los 2 espacios separan del candado
            textSize = 12f; setTextColor(gris)
        })
        return f
    }

    /** Las seis palabras: se leen en voz alta y tienen que coincidir en los dos móviles. */
    private fun tarjetaSas(): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = pill(sup, 16, sup)
            setPadding(dp(14), dp(12), dp(14), dp(14))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(20); marginStart = dp(4); marginEnd = dp(4) }
        }
        card.addView(TextView(this).apply {
            text = getString(R.string.x39_llamada_comprueba_palabras)
            textSize = 11f; setTextColor(gris); gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(10))
        })
        val palabras = sas.split(" ").filter { it.isNotEmpty() }
        if (palabras.isEmpty()) {
            card.addView(TextView(this).apply {
                text = getString(R.string.x39_llamada_acordando)
                textSize = 13f; setTextColor(tenue); gravity = Gravity.CENTER
            })
            return card
        }
        // Dos filas de tres.
        for (trozo in palabras.chunked(3)) {
            val fila = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                layoutParams = anchoTotal(dp(4))
            }
            for (p in trozo) {
                fila.addView(TextView(this).apply {
                    text = p; textSize = 16f; setTextColor(claro); typeface = Typeface.MONOSPACE
                    background = pill(sup2, 10, sup2)
                    setPadding(dp(12), dp(6), dp(12), dp(6))
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply { marginStart = dp(4); marginEnd = dp(4) }
                })
            }
            card.addView(fila)
        }
        return card
    }

    private fun fila3Botones(): View {
        val fila = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = anchoTotal(dp(8))
        }
        btnMic = redondo(R.drawable.x39_ic_mic, sup2, claro) { alternarMic() }
        icMic = btnMic.getChildAt(0) as ImageView
        btnCamara = redondo(R.drawable.x39_ic_camara, sup2, claro) { alternarCamara() }
        icCamara = btnCamara.getChildAt(0) as ImageView
        val btnColgar = redondo(R.drawable.x39_ic_colgar, rojo, Color.WHITE) { colgar() }
        btnColgar.contentDescription = getString(R.string.x39_colgar)

        fila.addView(btnMic)
        fila.addView(hueco(20))
        fila.addView(btnCamara)
        fila.addView(hueco(20))
        fila.addView(btnColgar)
        pintarBotones()
        return fila
    }

    /**
     * El propio vídeo, 100x140dp arriba a la derecha.
     *
     * setZOrderMediaOverlay(true) ANTES de mostrarlo: sin esa línea el pequeño no
     * aparece, o se come al grande. Es el fallo clásico número uno de esta pantalla.
     */
    private fun previoLocal(): View {
        videoLocal = SurfaceViewRenderer(this).apply {
            setZOrderMediaOverlay(true)
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
        marcoPrevio = FrameLayout(this).apply {
            background = pill(sup2, 16, linea)
            clipToOutline = true
            layoutParams = FrameLayout.LayoutParams(dp(100), dp(140)).apply {
                gravity = Gravity.TOP or Gravity.END
                setMargins(dp(16), dp(16), dp(16), dp(16))
            }
            visibility = View.GONE
            contentDescription = getString(R.string.x39_llamada_tu_camara_desc)
        }
        marcoPrevio.addView(videoLocal)
        arrastrable(marcoPrevio)
        return marcoPrevio
    }

    /** Tocar = girar la cámara. Arrastrar = imantar a la esquina más cercana. */
    private fun arrastrable(vista: View) {
        val umbral = ViewConfiguration.get(this).scaledTouchSlop
        var x0 = 0f; var y0 = 0f; var tx0 = 0f; var ty0 = 0f; var arrastrando = false
        vista.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    x0 = ev.rawX; y0 = ev.rawY; tx0 = v.translationX; ty0 = v.translationY
                    arrastrando = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - x0; val dy = ev.rawY - y0
                    if (!arrastrando && (abs(dx) > umbral || abs(dy) > umbral)) arrastrando = true
                    if (arrastrando) { v.translationX = tx0 + dx; v.translationY = ty0 + dy }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (arrastrando) imantarAEsquina(v) else if (ev.actionMasked == MotionEvent.ACTION_UP) {
                        v.performClick(); girarCamara()
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun imantarAEsquina(v: View) {
        val margen = dp(16)
        val libreX = (raiz.width - v.width - 2 * margen).toFloat()
        val libreY = (raiz.height - v.height - 2 * margen).toFloat()
        // Posición de reposo = arriba a la derecha (translación 0,0).
        val centroX = v.translationX + libreX / 2f
        val centroY = v.translationY + libreY / 2f
        val destinoX = if (centroX < 0f) -libreX else 0f
        val destinoY = if (centroY > 0f) libreY else 0f
        v.animate().translationX(destinoX).translationY(destinoY).setDuration(160).start()
    }

    // ===== Piezas sueltas ======================================================

    private fun avatar(txt: String): View {
        val lado = dp(140)
        val f = FrameLayout(this).apply {
            background = pill(sup2, 70, sup2)
            layoutParams = LinearLayout.LayoutParams(lado, lado).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            }
        }
        f.addView(TextView(this).apply {
            text = txt; setTextColor(claro); textSize = 44f; setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
            )
        })
        return f
    }

    private fun redondo(res: Int, fondo: Int, tinte: Int, onClick: () -> Unit): FrameLayout {
        val lado = dp(64)
        val f = FrameLayout(this).apply {
            background = pill(fondo, 32, fondo)
            layoutParams = LinearLayout.LayoutParams(lado, lado)
            isClickable = true
            setOnClickListener { onClick() }
        }
        f.addView(icono(res, tinte, 26).apply {
            layoutParams = FrameLayout.LayoutParams(dp(26), dp(26)).apply { gravity = Gravity.CENTER }
        })
        return f
    }

    private fun icono(res: Int, tinte: Int, lado: Int): ImageView = ImageView(this).apply {
        setImageResource(res)
        setColorFilter(tinte, PorterDuff.Mode.SRC_IN)
        layoutParams = LinearLayout.LayoutParams(dp(lado), dp(lado))
    }

    private fun hueco(ancho: Int): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(dp(ancho), 1)
    }

    private fun pill(fondo: Int, radio: Int, borde: Int): GradientDrawable = GradientDrawable().apply {
        setColor(fondo); cornerRadius = dp(radio).toFloat()
        if (borde != fondo) setStroke(1, borde)
    }

    private fun anchoTotal(top: Int) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = top }

    private fun iniciales(n: String): String {
        val p = n.trim().split(" ").filter { it.isNotEmpty() }
        return when {
            p.isEmpty() -> "?"
            p.size == 1 -> p[0].take(2).uppercase()
            else -> (p[0].take(1) + p[1].take(1)).uppercase()
        }
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_LONG).show()

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        /** Si la pantalla de llamada existe ahora mismo: el servicio lo usa para volver a ella. */
        @Volatile
        var viva = false
            private set

        const val EXTRA_NOMBRE = "x39_nombre"
        const val EXTRA_SAS = "x39_sas"
        const val EXTRA_CLAVE = "x39_clave"
        const val EXTRA_TOKEN = "x39_token"
        const val EXTRA_URL = "x39_url"
        const val EXTRA_VIDEO = "x39_video"

        /** Abre la pantalla de llamada con todo lo que ya acordó el núcleo. */
        fun abrir(
            ctx: Context, nombre: String, sas: String, claveBase64: String,
            token: String, url: String, conVideo: Boolean,
        ) {
            ctx.startActivity(
                Intent(ctx, X39EnLlamadaActivity::class.java)
                    // Se abre desde el ServicioX39, que no es una Activity: sin esto
                    // startActivity lanza AndroidRuntimeException.
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(EXTRA_NOMBRE, nombre)
                    .putExtra(EXTRA_SAS, sas)
                    .putExtra(EXTRA_CLAVE, claveBase64)
                    .putExtra(EXTRA_TOKEN, token)
                    .putExtra(EXTRA_URL, url)
                    .putExtra(EXTRA_VIDEO, conVideo),
            )
        }
    }
}
