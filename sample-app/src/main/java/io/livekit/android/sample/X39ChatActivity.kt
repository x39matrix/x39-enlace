/*
 * x39 · la sala privada de UN contacto.
 *
 * Se abre con su huella. Lo que se escribe aquí va cerrado a SU clave de cifrar:
 * no hay sala general ni mezcla con nadie más. El servidor mueve los sobres y no
 * puede abrirlos; sí ve, como cualquier servidor, quién escribe a quién y cuándo.
 */
package io.livekit.android.sample

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.util.Locale
import org.x39.bolsillo.Agenda

class X39ChatActivity : AppCompatActivity(), Mensajes.Oyente {

    private val grafito = Color.parseColor("#090A0D")
    private val sup = Color.parseColor("#12141A")
    private val sup2 = Color.parseColor("#12141A")
    private val linea = Color.parseColor("#492327")
    private val rojo = Color.parseColor("#FF1E1E")
    private val claro = Color.parseColor("#FFFFFF")
    private val gris = Color.parseColor("#A0AAB8")
    private val tenue = Color.parseColor("#A0AAB8")
    private val ambar = Color.parseColor("#F7931A")

    private lateinit var bh: String
    private var contacto: Agenda.Contacto? = null

    private lateinit var hilo: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var campo: EditText
    private lateinit var subtitulo: TextView

    private var claseElegida = Adjuntos.Clase.DOCUMENTO

    // --- Nota de voz ---
    private var grabadora: android.media.MediaRecorder? = null
    private var ficheroVoz: java.io.File? = null
    private var grabandoDesde = 0L
    private val relojVoz = android.os.Handler(android.os.Looper.getMainLooper())
    private var tvGrabando: TextView? = null

    // --- Escuchar notas de voz ---
    private var reproductor: android.media.MediaPlayer? = null
    private var reproduciendo: String? = null

    /** Tope de una nota de voz. A 64 kbps son unos 2,4 MB: cabe de sobra. */
    private val MAX_VOZ_S = 5 * 60

    private val permisoMicro =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
            if (ok) empezarAGrabar() else toast(getString(R.string.x39_chat_sin_micro))
        }

    /** Elegir el fichero a enviar. Lo que llega se limpia antes de sellarse. */
    private val elegirFichero =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) prepararEnvio(uri, claseElegida)
        }

    private fun antiCaptura(): Boolean =
        getSharedPreferences("x39", MODE_PRIVATE).getBoolean("anticaptura", true)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (antiCaptura()) {
            window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        }
        X39App.asegurar(this)
        Mensajes.init(this)

        bh = intent.getStringExtra(EXTRA_BH) ?: run { finish(); return }
        contacto = X39App.agenda.contacto(bh)
        if (contacto == null) { toast(getString(R.string.x39_chat_contacto_no_esta)); finish(); return }

        pintar(pantalla())
        refrescar()
    }

    override fun onResume() {
        super.onResume()
        Mensajes.oyente = this
        Mensajes.salaAbierta = bh
        Adjuntos.oyente = Adjuntos.Oyente { if (it.bh == bh) refrescar() }
        contacto = X39App.agenda.contacto(bh)
        // Al volver de comparar huellas, el aviso desaparece si ya está verificado.
        avisoHuella?.visibility = if (contacto?.verificado == false) View.VISIBLE else View.GONE
        pintarSubtitulo()
        refrescar()
        // Verlos es leerlos: arranca el reloj de borrado y se avisa al otro extremo.
        Mensajes.marcarLeidos(bh)
    }

    override fun onPause() {
        super.onPause()
        if (Mensajes.oyente === this) Mensajes.oyente = null
        Adjuntos.oyente = null
        // Salir de la sala a media grabación no puede dejar el micro abierto.
        if (grabadora != null) cancelarGrabacion()
        if (reproductor != null) pararAudio()
        if (Mensajes.salaAbierta == bh) Mensajes.salaAbierta = null
    }

    override fun conversacionCambio(bh: String) {
        if (bh == this.bh) refrescar()
    }

    // ===== Pantalla ============================================================

    /** Franja arriba del chat mientras no se hayan comparado las huellas completas. */
    private var avisoHuella: View? = null

    private fun avisoSinVerificar(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundColor(Color.parseColor("#492327"))
        setPadding(dp(14), dp(10), dp(10), dp(10))
        visibility = if (contacto?.verificado == false) View.VISIBLE else View.GONE
        addView(TextView(this@X39ChatActivity).apply {
            text = getString(R.string.x39_chat_aviso_sin_verificar)
            setTextColor(ambar); textSize = 13f; setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        addView(TextView(this@X39ChatActivity).apply {
            text = getString(R.string.x39_chat_aviso_comparar)
            setTextColor(Color.BLACK); textSize = 13f; setTypeface(typeface, android.graphics.Typeface.BOLD)
            background = pill(ambar, 16, ambar)
            setPadding(dp(14), dp(8), dp(14), dp(8))
            isClickable = true
            setOnClickListener {
                startActivity(Intent(this@X39ChatActivity, X39CallActivity::class.java)
                    .putExtra(X39CallActivity.EXTRA_COMPARAR, bh))
            }
        })
    }

    private fun pantalla(): View {
        val raiz = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(grafito)
        }
        raiz.addView(cabecera())
        raiz.addView(View(this).apply {
            setBackgroundColor(linea)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
        })
        raiz.addView(avisoSinVerificar().also { avisoHuella = it })

        hilo = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            isFillViewport = true
            addView(hilo)
        }
        raiz.addView(scroll)
        raiz.addView(barraEscritura())
        return raiz
    }

    private fun cabecera(): View {
        val c = contacto!!
        val fila = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPaddingRelative(dp(8), dp(12), dp(14), dp(12))
        }
        fila.addView(TextView(this).apply {
            text = "‹"; textSize = 26f; setTextColor(claro)
            setPadding(dp(10), 0, dp(10), 0)
            isClickable = true
            contentDescription = getString(R.string.x39_volver)
            setOnClickListener { finish() }
        })
        // La foto del contacto si la tiene; si no, sus iniciales.
        fila.addView(FrameLayout(this).apply {
            background = pill(sup2, 20, sup2)
            clipToOutline = true
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
            val foto = Fotos.de(c.bh)
            if (foto != null) {
                addView(ImageView(this@X39ChatActivity).apply {
                    setImageBitmap(foto); scaleType = ImageView.ScaleType.CENTER_CROP
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                })
            } else {
                addView(TextView(this@X39ChatActivity).apply {
                    text = iniciales(c.nombre); setTextColor(claro); textSize = 14f
                    setTypeface(typeface, Typeface.BOLD); gravity = Gravity.CENTER
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                })
            }
        })
        subtitulo = TextView(this).apply {
            textSize = 11f; setTextColor(gris); typeface = Typeface.MONOSPACE
            setPadding(0, dp(2), 0, 0)
        }
        fila.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginStart = dp(10) }
            isClickable = true
            setOnClickListener { elegirBorrado() }
            addView(TextView(this@X39ChatActivity).apply {
                text = c.nombre; textSize = 16f; setTextColor(claro)
                setTypeface(typeface, Typeface.BOLD)
            })
            addView(subtitulo)
        })
        fila.addView(FrameLayout(this).apply {
            background = pill(rojo, 18, rojo)
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36))
            isClickable = true
            contentDescription = getString(R.string.x39_chat_llamar_a, c.nombre)
            setOnClickListener {
                X39App.nucleo.llamar(bh, false)
                startActivity(Intent(this@X39ChatActivity, X39CallActivity::class.java))
            }
            addView(icono(R.drawable.x39_ic_llamar, Color.WHITE, 17).apply {
                layoutParams = FrameLayout.LayoutParams(dp(17), dp(17)).apply { gravity = Gravity.CENTER }
            })
        })
        return fila
    }

    /** Estado de cifrado + el reloj de borrado elegido, siempre a la vista. */
    private fun pintarSubtitulo() {
        val c = contacto ?: return
        val estado = when {
            !c.puedeRecibirMensajes() -> getString(R.string.x39_chat_estado_sin_clave)
            c.verificado -> getString(R.string.x39_contacto_verificado)
            else -> getString(R.string.x39_contacto_pendiente)
        }
        subtitulo.text = "$estado · ${huellaCorta(c.bh)}\n${getString(Mensajes.borradoDe(bh).frase)}"
    }

    private fun barraEscritura(): View {
        val fila = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            setPadding(dp(10), dp(8), dp(10), dp(10))
        }
        campo = EditText(this).apply {
            setHint(getString(R.string.x39_chat_escribe))
            setTextColor(claro); setHintTextColor(tenue); textSize = 16f
            background = pill(sup, 22, sup)
            setPadding(dp(18), dp(12), dp(18), dp(12))
            maxLines = 5
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        if (grabadora != null) return barraGrabando(fila)
        // Clip de adjuntar, a la izquierda del campo.
        fila.addView(FrameLayout(this).apply {
            background = pill(sup, 22, sup)
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(8) }
            isClickable = true
            contentDescription = getString(R.string.x39_chat_adjuntar)
            setOnClickListener { menuAdjuntar() }
            addView(TextView(this@X39ChatActivity).apply {
                text = "📎"; textSize = 18f; gravity = Gravity.CENTER
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                )
            })
        })
        // Micrófono: graba una nota de voz en directo (no adjunta un fichero).
        fila.addView(FrameLayout(this).apply {
            background = pill(sup, 22, sup)
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(8) }
            isClickable = true
            contentDescription = getString(R.string.x39_chat_grabar_nota)
            setOnClickListener { tocarMicrofono() }
            addView(icono(R.drawable.x39_ic_mic, claro, 20).apply {
                layoutParams = FrameLayout.LayoutParams(dp(20), dp(20)).apply {
                    gravity = Gravity.CENTER
                }
            })
        })
        fila.addView(campo)
        fila.addView(FrameLayout(this).apply {
            background = pill(rojo, 22, rojo)
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginStart = dp(8) }
            isClickable = true
            contentDescription = getString(R.string.x39_enviar)
            setOnClickListener { enviar() }
            addView(TextView(this@X39ChatActivity).apply {
                text = "➤"; setTextColor(Color.WHITE); textSize = 17f; gravity = Gravity.CENTER
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                )
            })
        })
        return fila
    }

    /** Barra de abajo mientras se graba: descartar · tiempo · enviar. */
    private fun barraGrabando(fila: LinearLayout): View {
        fila.addView(FrameLayout(this).apply {
            background = pill(sup, 22, sup)
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(8) }
            isClickable = true
            contentDescription = getString(R.string.x39_chat_descartar_nota)
            setOnClickListener { cancelarGrabacion() }
            addView(TextView(this@X39ChatActivity).apply {
                text = "✕"; textSize = 18f; setTextColor(gris); gravity = Gravity.CENTER
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                )
            })
        })
        tvGrabando = TextView(this).apply {
            text = getString(R.string.x39_chat_grabando, 0, 0)
            textSize = 15f; setTextColor(rojo); gravity = Gravity.CENTER_VERTICAL
            background = pill(sup, 22, sup)
            setPadding(dp(18), dp(12), dp(18), dp(12))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        fila.addView(tvGrabando)
        fila.addView(FrameLayout(this).apply {
            background = pill(rojo, 22, rojo)
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginStart = dp(8) }
            isClickable = true
            contentDescription = getString(R.string.x39_chat_enviar_nota)
            setOnClickListener { pararYEnviar() }
            addView(TextView(this@X39ChatActivity).apply {
                text = "➤"; setTextColor(Color.WHITE); textSize = 17f; gravity = Gravity.CENTER
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                )
            })
        })
        return fila
    }

    private fun enviar() {
        val texto = campo.text.toString().trim()
        if (texto.isEmpty()) return
        val c = contacto ?: return
        if (!c.puedeRecibirMensajes()) {
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.x39_chat_no_puedes_titulo))
                .setMessage(getString(R.string.x39_chat_no_puedes_texto, c.nombre))
                .setPositiveButton(getString(R.string.x39_entendido), null)
                .show()
            return
        }
        campo.setText("")
        Mensajes.enviar(bh, texto)
    }

    // ===== Nota de voz =========================================================

    /**
     * Grabar y mandar una nota de voz. No es adjuntar un fichero de audio: se graba
     * aquí mismo. El audio va al mismo sobre sellado que todo lo demás.
     *
     * El fichero temporal vive en la caché de la app el tiempo justo de leerlo y se
     * borra en cuanto se ha metido en el almacén cifrado: no se deja audio en claro.
     */
    private fun tocarMicrofono() {
        if (grabadora != null) { pararYEnviar(); return }
        val c = contacto ?: return
        if (!c.puedeRecibirMensajes()) { enviar(); return }   // reutiliza el aviso de «Cifrar»
        if (androidx.core.content.ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.RECORD_AUDIO,
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            permisoMicro.launch(android.Manifest.permission.RECORD_AUDIO)
            return
        }
        empezarAGrabar()
    }

    private fun empezarAGrabar() {
        val destino = java.io.File(cacheDir, "voz_${System.currentTimeMillis()}.m4a")
        val r = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            android.media.MediaRecorder(this)
        } else {
            @Suppress("DEPRECATION") android.media.MediaRecorder()
        }
        try {
            r.setAudioSource(android.media.MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(android.media.MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(android.media.MediaRecorder.AudioEncoder.AAC)
            r.setAudioSamplingRate(44100)
            r.setAudioEncodingBitRate(64000)
            r.setMaxDuration(MAX_VOZ_S * 1000)
            r.setOutputFile(destino.absolutePath)
            r.prepare()
            r.start()
        } catch (e: Exception) {
            runCatching { r.release() }
            destino.delete()
            toast(getString(R.string.x39_chat_no_grabar, e.message ?: getString(R.string.x39_chat_error)))
            return
        }
        grabadora = r
        ficheroVoz = destino
        grabandoDesde = android.os.SystemClock.elapsedRealtime()
        pintar(pantalla())
        contarTiempo()
    }

    private fun contarTiempo() {
        relojVoz.removeCallbacksAndMessages(null)
        if (grabadora == null) return
        val s = ((android.os.SystemClock.elapsedRealtime() - grabandoDesde) / 1000).toInt()
        tvGrabando?.text = getString(R.string.x39_chat_grabando, s / 60, s % 60)
        if (s >= MAX_VOZ_S) { pararYEnviar(); return }
        relojVoz.postDelayed({ contarTiempo() }, 500)
    }

    /** Corta la grabación y la manda. */
    private fun pararYEnviar() {
        if (grabadora == null) return
        val f = ficheroVoz
        val segundos = ((android.os.SystemClock.elapsedRealtime() - grabandoDesde) / 1000).toInt()
        soltarGrabadora()
        if (f == null || !f.exists()) { toast(getString(R.string.x39_chat_no_grabo)); return }
        if (segundos < 1) { f.delete(); toast(getString(R.string.x39_chat_corta)); pintar(pantalla()); return }
        val datos = try { f.readBytes() } catch (e: Exception) { null }
        f.delete()   // fuera el temporal en claro, pase lo que pase
        if (datos == null || datos.isEmpty()) { toast(getString(R.string.x39_chat_no_leer_grabacion)); return }
        val nombre = "nota-de-voz-" +
            java.text.SimpleDateFormat("HHmmss", Locale.ROOT).format(java.util.Date()) + ".m4a"
        val fallo = Adjuntos.enviar(bh, nombre, "audio/mp4", datos, Adjuntos.Clase.AUDIO)
        if (fallo != null) toast(getString(R.string.x39_chat_no_enviar, fallo))
    }

    /** Descarta la grabación sin mandar nada. */
    private fun cancelarGrabacion() {
        val f = ficheroVoz
        soltarGrabadora()
        f?.delete()
        toast(getString(R.string.x39_chat_nota_descartada))
    }

    private fun soltarGrabadora() {
        relojVoz.removeCallbacksAndMessages(null)
        val r = grabadora
        grabadora = null
        tvGrabando = null
        runCatching { r?.stop() }
        runCatching { r?.release() }
        ficheroVoz = null
        pintar(pantalla())
    }

    // ===== Adjuntar ============================================================

    private fun menuAdjuntar() {
        val c = contacto ?: return
        if (!c.puedeRecibirMensajes()) { enviar(); return }   // reutiliza el aviso de «Cifrar»
        // El audio no se adjunta: se graba en directo con el botón de al lado.
        val opciones = arrayOf(
            getString(R.string.x39_chat_adj_foto),
            getString(R.string.x39_chat_adj_video),
            getString(R.string.x39_chat_adj_documento),
        )
        val clases = arrayOf(
            Adjuntos.Clase.FOTO, Adjuntos.Clase.VIDEO, Adjuntos.Clase.DOCUMENTO,
        )
        val filtros = arrayOf("image/*", "video/*", "*/*")
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.x39_chat_adjuntar))
            .setItems(opciones) { _, i ->
                claseElegida = clases[i]
                elegirFichero.launch(filtros[i])
            }
            .show()
    }

    /**
     * Pantalla previa al envío: qué pesa, cuánto tráfico cuesta de verdad y qué pasa con
     * los metadatos. La casilla está al revés a propósito: limpiar es lo de serie, y
     * conservar los datos de ubicación y cámara hay que marcarlo a mano.
     */
    private fun prepararEnvio(uri: Uri, clase: Adjuntos.Clase) {
        val nombre = Adjuntos.nombreDe(uri)
        val mime = Adjuntos.mimeDe(uri)
        val bytes = try {
            contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
        } catch (e: Exception) { -1L }

        if (bytes > Adjuntos.MAX_BYTES) {
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.x39_chat_demasiado_grande))
                .setMessage(getString(R.string.x39_chat_demasiado_grande_texto, nombre, humano(bytes)))
                .setPositiveButton(getString(R.string.x39_entendido), null).show()
            return
        }

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(12), dp(24), 0)
        }
        val limpiable = Adjuntos.seLimpia(clase)
        val casilla = android.widget.CheckBox(this).apply {
            text = getString(R.string.x39_chat_enviar_metadatos)
            setTextColor(claro); textSize = 13f
            isChecked = false
            visibility = if (limpiable) View.VISIBLE else View.GONE
        }
        col.addView(TextView(this).apply {
            text = if (clase == Adjuntos.Clase.VIDEO) {
                // Ámbar hasta que la limpieza del vídeo pase la verificación con exiftool.
                getString(R.string.x39_chat_aviso_video)
            } else if (limpiable) {
                getString(R.string.x39_chat_limpio)
            } else {
                getString(R.string.x39_chat_documento_metadatos)
            }
            textSize = 13f
            setTextColor(if (limpiable && clase != Adjuntos.Clase.VIDEO) gris else ambar)
            setPadding(0, 0, 0, dp(10))
        })
        col.addView(casilla)
        col.addView(TextView(this).apply {
            val traf = Adjuntos.traficoEstimado(maxOf(bytes, 0))
            val trozos = Adjuntos.trozosDe(maxOf(bytes, 0))
            text = resources.getQuantityString(R.plurals.x39_chat_trafico, trozos, humano(bytes), trozos, humano(traf))
            textSize = 11f; setTextColor(tenue); setPadding(0, dp(12), 0, 0)
        })

        AlertDialog.Builder(this)
            .setTitle(nombre)
            .setView(col)
            .setPositiveButton(getString(R.string.x39_enviar)) { _, _ ->
                val conservar = casilla.isChecked
                if (clase == Adjuntos.Clase.VIDEO && !conservar) toast(getString(R.string.x39_chat_limpiando_video))
                // Leer y limpiar (un vídeo de 25 MB se remuxea) fuera del hilo de pantalla.
                Thread {
                    val lectura = Adjuntos.leerLimpio(uri, clase, conservar)
                    runOnUiThread {
                        when (lectura) {
                            is Adjuntos.Lectura.Fallo -> toast(lectura.motivo)
                            is Adjuntos.Lectura.Lista -> {
                                // Un vídeo limpio sale siempre como MP4, venga de .mp4, .3gp o .mov.
                                val tipo = if (clase == Adjuntos.Clase.VIDEO && lectura.limpio) "video/mp4" else mime
                                val fallo = Adjuntos.enviar(bh, nombre, tipo, lectura.datos, clase)
                                if (fallo != null) toast(getString(R.string.x39_chat_no_se_puede_enviar, fallo))
                                else if (lectura.limpio) toast(getString(R.string.x39_chat_enviando_limpio))
                            }
                        }
                    }
                }.start()
            }
            .setNegativeButton(getString(R.string.x39_cancelar), null)
            .show()
    }

    private fun humano(b: Long): String = when {
        b < 0 -> getString(R.string.x39_chat_tamano_desconocido)
        b >= 1024 * 1024 -> String.format(Locale.ROOT, "%.1f MB", b / 1048576.0)
        b >= 1024 -> "${b / 1024} KB"
        else -> "$b B"
    }

    // ===== El hilo =============================================================

    private fun refrescar() {
        pintarSubtitulo()
        hilo.removeAllViews()
        val lista = Mensajes.conversacion(bh)
        if (lista.isEmpty()) {
            hilo.addView(vacio())
        } else {
            for (m in lista) hilo.addView(burbuja(m))
        }
        hilo.addView(aviso())
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun vacio(): View = TextView(this).apply {
        val c = contacto
        text = if (c != null && !c.puedeRecibirMensajes()) {
            getString(R.string.x39_chat_vacio_sin_clave, c.nombre)
        } else {
            getString(R.string.x39_chat_vacio, c?.nombre)
        }
        textSize = 13f; setTextColor(tenue); gravity = Gravity.CENTER
        setPadding(dp(24), dp(60), dp(24), dp(20))
    }

    private fun burbuja(m: Mensajes.Msg): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(10) }   // aire entre mensajes: que el estado no parezca del siguiente
            gravity = if (m.mio) Gravity.END else Gravity.START
        }
        val cuerpo = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = pill(if (m.mio) sup2 else sup, 18, if (m.mio) sup2 else sup)
            setPadding(dp(14), dp(10), dp(14), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        if (m.adjunto.isNotEmpty() && m.adjClase == "FOTO" && Adjuntos.completo(m.adjunto)) {
            cuerpo.addView(miniaturaFoto(m))
        } else if (m.adjunto.isNotEmpty()) {
            cuerpo.addView(fichero(m))
        } else {
            cuerpo.addView(TextView(this).apply {
                text = m.texto; textSize = 16f; setTextColor(claro)
                maxWidth = (resources.displayMetrics.widthPixels * 0.76f).toInt()
            })
        }
        cuerpo.addView(TextView(this).apply {
            text = hora(m.ts); textSize = 11f; setTextColor(tenue)
            gravity = Gravity.END
            setPadding(0, dp(4), 0, 0)
        })
        cuerpo.isLongClickable = true
        cuerpo.setOnLongClickListener { menuMensaje(m); true }
        col.addView(cuerpo)
        // El estado va DEBAJO de su burbuja y pegado a la derecha, como ella.
        if (m.mio) col.addView(estadoDe(m))
        return col
    }

    /**
     * Foto ya completa: se ve la imagen, no un icono de fichero. Sale del almacén
     * cifrado de la app y se pinta aquí; al tocarla se abre el visor de x39, sin pasar
     * por ninguna aplicación de fuera.
     */
    private fun miniaturaFoto(m: Mensajes.Msg): View {
        val ancho = (resources.displayMetrics.widthPixels * 0.62f).toInt()
        val mini = Adjuntos.miniatura(m.adjunto, ancho)
            ?: return fichero(m)   // si no se puede decodificar, al menos el nombre
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(ancho, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val alto = (ancho.toFloat() * mini.height / mini.width).toInt()
        col.addView(FrameLayout(this).apply {
            background = pill(sup2, 14, sup2)
            clipToOutline = true
            layoutParams = LinearLayout.LayoutParams(ancho, minOf(alto, dp(320)))
            isClickable = true
            contentDescription = getString(R.string.x39_chat_ver_foto)
            setOnClickListener {
                X39VisorActivity.abrir(this@X39ChatActivity, m.adjunto, m.adjNombre, m.adjMime)
            }
            addView(ImageView(this@X39ChatActivity).apply {
                setImageBitmap(mini)
                scaleType = ImageView.ScaleType.CENTER_CROP
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                )
            })
        })
        col.addView(TextView(this).apply {
            text = humano(m.adjBytes); textSize = 11f; setTextColor(tenue)
            setPadding(0, dp(5), 0, 0)
        })
        return col
    }

    /**
     * Un fichero dentro de la burbuja: icono, nombre, tamaño y, mientras viaja, la barra
     * de progreso real con su botón de cancelar. Lo recibido vive en el almacén cifrado
     * de la app; para sacarlo fuera hay que pedirlo a propósito.
     */
    private fun fichero(m: Mensajes.Msg): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                (resources.displayMetrics.widthPixels * 0.68f).toInt(),
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        val cab = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        }
        cab.addView(TextView(this).apply {
            text = when (m.adjClase) {
                "FOTO" -> "🖼"; "VIDEO" -> "🎞"; "AUDIO" -> "🎵"; else -> "📄"
            }
            textSize = 22f
            layoutParams = LinearLayout.LayoutParams(dp(34), ViewGroup.LayoutParams.WRAP_CONTENT)
        })
        cab.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addView(TextView(this@X39ChatActivity).apply {
                text = m.adjNombre; textSize = 15f; setTextColor(claro)
                maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            })
            addView(TextView(this@X39ChatActivity).apply {
                text = humano(m.adjBytes); textSize = 11f; setTextColor(tenue)
                setPadding(0, dp(2), 0, 0)
            })
        })
        col.addView(cab)

        val curso = Adjuntos.enCurso(m.adjunto)
        if (curso != null) {
            col.addView(android.widget.ProgressBar(
                this, null, android.R.attr.progressBarStyleHorizontal,
            ).apply {
                max = 100
                progress = curso.porCiento
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(8) }
            })
            col.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                addView(TextView(this@X39ChatActivity).apply {
                    text = resources.getQuantityString(R.plurals.x39_chat_trozos, curso.total, curso.hechos, curso.total)
                    textSize = 11f; setTextColor(gris)
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                })
                addView(TextView(this@X39ChatActivity).apply {
                    text = getString(R.string.x39_cancelar); textSize = 12f; setTextColor(rojo)
                    setTypeface(typeface, Typeface.BOLD)
                    isClickable = true
                    setOnClickListener { Adjuntos.cancelar(m.adjunto); refrescar() }
                })
            })
        } else if (m.adjClase == "AUDIO") {
            col.addView(botonEscuchar(m))
        }
        // Un fichero propio que se quedó a medias no se reintenta solo: lo manda el
        // usuario. La cola de mensajes no lo toca, porque ahí `texto` es su nombre.
        if (m.mio && m.estado == Mensajes.Estado.PENDIENTE && Adjuntos.enCurso(m.adjunto) == null) {
            col.addView(TextView(this).apply {
                text = getString(R.string.x39_chat_reenviar); textSize = 13f; setTextColor(rojo)
                setTypeface(typeface, Typeface.BOLD)
                gravity = Gravity.CENTER
                background = pill(sup2, 12, linea)
                setPadding(dp(12), dp(8), dp(12), dp(8))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(10) }
                isClickable = true
                setOnClickListener {
                    val clase = runCatching { Adjuntos.Clase.valueOf(m.adjClase) }
                        .getOrDefault(Adjuntos.Clase.DOCUMENTO)
                    val fallo = Adjuntos.reenviar(bh, m.adjunto, m.adjNombre, m.adjMime, clase)
                    if (fallo != null) toast(getString(R.string.x39_chat_no_reenviar, fallo)) else refrescar()
                }
            })
        }
        return col
    }

    /**
     * Escuchar una nota de voz SIN dejarla en claro en disco: los bytes salen del
     * almacén cifrado y se le sirven al reproductor desde memoria.
     */
    private fun botonEscuchar(m: Mensajes.Msg): View = TextView(this).apply {
        val sonando = reproduciendo == m.adjunto
        text = getString(if (sonando) R.string.x39_chat_parar else R.string.x39_chat_escuchar)
        textSize = 13f; setTextColor(claro); setTypeface(typeface, Typeface.BOLD)
        background = pill(sup2, 12, linea)
        gravity = Gravity.CENTER
        setPadding(dp(12), dp(8), dp(12), dp(8))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(10) }
        isClickable = true
        setOnClickListener { if (sonando) pararAudio() else escuchar(m.adjunto) }
    }

    private fun escuchar(idAdj: String) {
        pararAudio()
        val datos = Adjuntos.abrir(idAdj) ?: run { toast(getString(R.string.x39_chat_no_abrir_grabacion)); return }
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.M) {
            toast(getString(R.string.x39_chat_no_reproducir_sin_disco))
            return
        }
        val mp = android.media.MediaPlayer()
        try {
            // Los bytes se sirven desde memoria: no se escribe ningún fichero temporal.
            mp.setDataSource(object : android.media.MediaDataSource() {
                override fun readAt(pos: Long, buf: ByteArray, off: Int, size: Int): Int {
                    if (pos >= datos.size) return -1
                    val n = minOf(size.toLong(), datos.size - pos).toInt()
                    System.arraycopy(datos, pos.toInt(), buf, off, n)
                    return n
                }
                override fun getSize(): Long = datos.size.toLong()
                override fun close() {}
            })
            mp.setOnCompletionListener { pararAudio() }
            mp.prepare()
            mp.start()
        } catch (e: Exception) {
            runCatching { mp.release() }
            toast(getString(R.string.x39_chat_no_reproducir))
            return
        }
        reproductor = mp
        reproduciendo = idAdj
        refrescar()
    }

    private fun pararAudio() {
        val mp = reproductor
        reproductor = null
        reproduciendo = null
        runCatching { mp?.stop() }
        runCatching { mp?.release() }
        refrescar()
    }

    /** Estado bajo la burbuja propia. Nada de marcas que no sean ciertas. */
    private fun estadoDe(m: Mensajes.Msg): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
            setPaddingRelative(0, dp(4), dp(6), 0)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        // Cada marca dice exactamente lo que se sabe, ni una palabra más:
        //   ⏳ está aquí · ✓ salió del servidor · ✓✓ lo tiene su móvil (él lo ha firmado)
        val (texto, color) = when (m.estado) {
            Mensajes.Estado.PENDIENTE -> getString(R.string.x39_chat_estado_pendiente) to gris
            Mensajes.Estado.ENVIADO -> getString(R.string.x39_chat_estado_enviado) to tenue
            Mensajes.Estado.ENTREGADO -> getString(R.string.x39_chat_estado_entregado) to tenue
            Mensajes.Estado.CADUCADO -> getString(R.string.x39_chat_estado_caducado) to ambar
            Mensajes.Estado.RECIBIDO -> "" to tenue
        }
        if (texto.isNotEmpty()) {
            col.addView(TextView(this).apply {
                this.text = texto; textSize = 11f; setTextColor(color)
            })
        }
        // Mientras está en cola y vivo, se dice cuánto le queda de ventana de entrega.
        val sigueEnCola = m.estado == Mensajes.Estado.PENDIENTE || m.estado == Mensajes.Estado.ENVIADO
        if (sigueEnCola && m.selladoEn > 0) {
            val quedan = org.x39.bolsillo.Mensajeria.VENTANA_ENTREGA_S -
                (System.currentTimeMillis() / 1000L - m.selladoEn)
            if (quedan > 0) {
                col.addView(TextView(this).apply {
                    text = caducaEn(quedan); textSize = 11f; setTextColor(tenue)
                })
            }
        }
        if (m.estado == Mensajes.Estado.CADUCADO) {
            col.addView(TextView(this).apply {
                text = getString(R.string.x39_chat_reenviar); textSize = 12f; setTextColor(rojo)
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, dp(2), 0, 0)
                isClickable = true
                setOnClickListener { Mensajes.reenviar(bh, m.id) }
            })
        }
        return col
    }

    private fun menuMensaje(m: Mensajes.Msg) {
        AlertDialog.Builder(this)
            .setItems(arrayOf(getString(R.string.x39_copiar), getString(R.string.x39_chat_borrar_aparato))) { _, i ->
                if (i == 0) {
                    val cb = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    cb.setPrimaryClip(android.content.ClipData.newPlainText("x39", m.texto))
                    toast(getString(R.string.x39_chat_copiado))
                } else {
                    Mensajes.borrar(bh, m.id)
                }
            }
            .show()
    }

    /**
     * El aviso va al pie del hilo, con las palabras exactas: el borrado automático se
     * apoya en que la app del otro lo cumpla.
     */
    private fun aviso(): View = TextView(this).apply {
        text = getString(R.string.x39_chat_aviso_borrado)
        textSize = 10f; setTextColor(tenue); gravity = Gravity.CENTER
        setPadding(dp(18), dp(22), dp(18), dp(8))
    }

    // ===== Reloj de borrado ====================================================

    private fun elegirBorrado() {
        val ops = Mensajes.Borrado.entries.toTypedArray()
        val actual = Mensajes.borradoDe(bh)
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.x39_chat_borrar_mensajes))
            .setSingleChoiceItems(
                ops.map { getString(it.opcion) }.toTypedArray(),
                ops.indexOf(actual),
            ) { d, i ->
                Mensajes.borradoDe(bh, ops[i])
                pintarSubtitulo()
                d.dismiss()
            }
            .setMessage(getString(R.string.x39_chat_borrado_texto))
            .setNegativeButton(getString(R.string.x39_cancelar), null)
            .show()
    }

    // ===== Utilidades ==========================================================

    private fun pintar(vista: View) {
        val marco = FrameLayout(this).apply {
            setBackgroundColor(grafito)
            addView(vista, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
            ))
        }
        ViewCompat.setOnApplyWindowInsetsListener(marco) { v, insets ->
            val b = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime() or
                    WindowInsetsCompat.Type.displayCutout(),
            )
            v.setPadding(b.left, b.top, b.right, b.bottom)
            insets
        }
        setContentView(marco)
        ViewCompat.requestApplyInsets(marco)
    }

    private fun hora(segundos: Long): String =
        java.text.SimpleDateFormat("HH:mm", Locale.getDefault()).format(java.util.Date(segundos * 1000))

    /** «caduca en …»: una frase entera por unidad, para que cada idioma la diga a su manera. */
    private fun caducaEn(segundos: Long): String = when {
        segundos >= 86400 -> (segundos / 86400).toInt().let { resources.getQuantityString(R.plurals.x39_chat_caduca_dias, it, it) }
        segundos >= 3600 -> (segundos / 3600).toInt().let { resources.getQuantityString(R.plurals.x39_chat_caduca_horas, it, it) }
        segundos >= 60 -> (segundos / 60).toInt().let { resources.getQuantityString(R.plurals.x39_chat_caduca_minutos, it, it) }
        else -> getString(R.string.x39_chat_caduca_menos_minuto)
    }

    private fun icono(res: Int, tinte: Int, lado: Int): ImageView = ImageView(this).apply {
        setImageResource(res)
        setColorFilter(tinte, android.graphics.PorterDuff.Mode.SRC_IN)
        layoutParams = LinearLayout.LayoutParams(dp(lado), dp(lado))
    }

    private fun pill(fondo: Int, radio: Int, borde: Int): GradientDrawable = GradientDrawable().apply {
        setColor(fondo); cornerRadius = dp(radio).toFloat()
        if (borde != fondo) setStroke(1, borde)
    }

    private fun iniciales(n: String): String {
        val p = n.trim().split(" ").filter { it.isNotEmpty() }
        return when {
            p.isEmpty() -> "?"
            p.size == 1 -> p[0].take(2).uppercase()
            else -> (p[0].take(1) + p[1].take(1)).uppercase()
        }
    }

    private fun huellaCorta(h: String): String =
        if (h.length <= 16) h else "${h.substring(0, 8)}…${h.substring(h.length - 4)}"

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_LONG).show()

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_BH = "x39_bh"

        /** Abre la sala de ESE contacto y de ningún otro. */
        fun abrir(ctx: Context, bh: String) {
            ctx.startActivity(
                Intent(ctx, X39ChatActivity::class.java).putExtra(EXTRA_BH, bh),
            )
        }
    }
}
