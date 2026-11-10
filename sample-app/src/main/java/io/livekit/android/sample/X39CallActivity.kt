/*
 * x39 · pantalla principal (fiel a la maqueta acordada).
 * Grafito, rojo solo de marca y de la acción de llamar, sin verde.
 * Barra inferior: Contactos · Llamar · Mensajes · Billetera. ⚙ arriba a la derecha.
 *
 * Las llamadas son reales: al pulsar el botón rojo se acuerda la clave post-cuántica
 * (X39-CALL-KEY) y se abre la sala LiveKit cifrada de extremo a extremo. El servidor
 * nunca ve la clave.
 */
package io.livekit.android.sample

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
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
import androidx.core.app.NotificationManagerCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.content.ContextCompat
import android.app.Dialog
import android.graphics.drawable.ColorDrawable
import org.x39.bolsillo.X39Core

class X39CallActivity : AppCompatActivity(), BusUi.Oyente {

    internal val grafito = Color.parseColor("#090A0D")
    private val sup = Color.parseColor("#12141A")
    internal val sup2 = Color.parseColor("#12141A")
    private val linea = Color.parseColor("#492327")
    private val rojo get() = ContextCompat.getColor(this, R.color.x39_edicion_acento)
    internal val claro = Color.parseColor("#FFFFFF")
    internal val gris = Color.parseColor("#A0AAB8")
    internal val tenue = Color.parseColor("#A0AAB8")
    private val ambar = Color.parseColor("#F7931A")

    /** Lector de QR: devuelve la clave pública ML-DSA-87 del otro; se guarda como contacto. */
    private val escaner =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            if (r.resultCode != RESULT_OK) return@registerForActivityResult
            if (r.data?.getStringExtra(EscanerQr.RESULTADO_ERROR) != null) {
                contactoParaCifrar = null
                AlertDialog.Builder(this)
                    .setTitle(getString(R.string.x39_qr_mezcla_titulo))
                    .setMessage(getString(R.string.x39_qr_mezcla_texto))
                    .setPositiveButton(getString(R.string.x39_entendido), null)
                    .show()
                return@registerForActivityResult
            }
            val texto = r.data?.getStringExtra(EscanerQr.RESULTADO_TEXTO)
            // Si se estaba cifrando un contacto concreto, el QR es SU clave de cifrar.
            val paraCifrar = contactoParaCifrar
            contactoParaCifrar = null
            if (texto != null && paraCifrar != null) {
                if (aplicarIdentidad(paraCifrar, texto, desdeQr = true)) {
                    X39App.agenda.contacto(paraCifrar)?.let { confirmarHuellas(it) }
                }
                return@registerForActivityResult
            }
            if (texto != null) { guardarDesdeTexto(texto); return@registerForActivityResult }
            val pk = r.data?.getByteArrayExtra(EscanerQr.RESULTADO_PK)
                ?: return@registerForActivityResult
            if (esPropia(pk)) { toast(getString(R.string.x39_qr_propio)); return@registerForActivityResult }
            val campo = campo(getString(R.string.x39_nombre_persona_hint))
            AlertDialog.Builder(this).setTitle(getString(R.string.x39_identidad_leida, huellaCorta(org.x39.bolsillo.Identidad.huellaDe(pk))))
                .setView(FrameLayout(this).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(campo) })
                .setPositiveButton(getString(R.string.x39_guardar_contacto)) { _, _ ->
                    val n = campo.text.toString().trim()
                    if (n.isEmpty()) { toast(getString(R.string.x39_ponle_nombre)); return@setPositiveButton }
                    X39App.agenda.guardar(n, pk); toast(getString(R.string.x39_contacto_guardado))
                    pestana = Pestana.CONTACTOS; pintar(marco())
                }
                .setNegativeButton(getString(R.string.x39_cancelar), null).show()
        }

    companion object {
        /** Huella (bh) del contacto cuya comparación de huellas se abre directamente (p. ej. desde el aviso del chat). */
        const val EXTRA_COMPARAR = "comparar_huella"
    }

    /** Abierta solo para comparar huellas (desde el chat): al terminar se cierra y se vuelve a donde se estaba. */
    private var soloComparar = false

    private var pestana = Pestana.CONTACTOS
    private var estadoTv: TextView? = null

    private var accionTrasPermiso: (() -> Unit)? = null
    private val permisos =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
            val ok = res.isNotEmpty() && res.values.all { it }
            if (ok) accionTrasPermiso?.invoke() else toast(getString(R.string.x39_sin_micro_camara))
            accionTrasPermiso = null
        }

    /**
     * Importar un .conf de WireGuard. La clave privada que traiga se sella en el
     * Keystore; no se queda en el fichero de preferencias ni en el código.
     */
    private val abrirConf =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@registerForActivityResult
            val texto = try {
                contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            } catch (e: Exception) {
                null
            }
            if (texto.isNullOrBlank()) { toast(getString(R.string.x39_conf_ilegible)); return@registerForActivityResult }
            val fallo = TunelX39.importarConf(texto)
            toast(fallo?.let { getString(R.string.x39_conf_no_vale, it) } ?: getString(R.string.x39_conf_importada))
            refrescarAjustes()
        }

    /** Elegir foto de perfil. Se le quitan los metadatos antes de guardarla. */
    private val elegirFoto =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri == null) return@registerForActivityResult
            val fallo = Fotos.ponerMia(uri)
            if (fallo != null) { toast(getString(R.string.x39_foto_no_usable, fallo)); return@registerForActivityResult }
            // Solo sale hacia los verificados, y solo si el usuario lo confirma.
            confirmarReparto()
        }

    /** Permiso de VPN de Android para el x39tunel: al concederlo se enciende de verdad. */
    private val permisoVpn =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            if (r.resultCode == RESULT_OK) TunelX39.encender(X39App.nucleo) else toast(getString(R.string.x39_sin_permiso_vpn))
            refrescarAjustes()
        }

    private fun antiCaptura(): Boolean = getSharedPreferences("x39", MODE_PRIVATE).getBoolean("anticaptura", true)

    /**
     * Android 15 obliga al modo borde a borde: si nadie descuenta los huecos del sistema,
     * el título se mete debajo del reloj y la barra de estado se come los toques de esa
     * zona (por eso el engranaje de arriba no respondía).
     *
     * Esta pantalla se repinta muchas veces con setContentView, así que el descuento se
     * hace aquí, en un envoltorio común: así vale para TODAS las vistas de la Activity
     * sin tocar el relleno propio de cada una.
     */
    private fun pintar(vista: View) {
        val marco = FrameLayout(this).apply {
            setBackgroundColor(grafito)
            addView(vista, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
            ))
        }
        ViewCompat.setOnApplyWindowInsetsListener(marco) { v, insets ->
            val b = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            v.setPadding(b.left, b.top, b.right, b.bottom)
            insets
        }
        super.setContentView(marco)
        ViewCompat.requestApplyInsets(marco)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // Al cambiar de idioma la pantalla se recrea: se vuelve a la misma pestaña.
        outState.putString("pestana", pestana.name)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        savedInstanceState?.getString("pestana")?.let { p -> Pestana.values().firstOrNull { it.name == p }?.let { pestana = it } }
        android.util.Log.i("X39_ACTIVITY", "X39CallActivity onCreate")
        // Anti-captura: por defecto encendido; se puede apagar en Ajustes (queda apuntado y se aplica al reabrir).
        if (antiCaptura()) window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        // El dueño de la línea es el servicio: la pantalla solo lo arranca y se engancha.
        ServicioX39.arrancar(this)
        X39App.asegurar(this)
        pedirAvisos()
        TunelX39.oyente = object : TunelX39.Oyente {
            // El indicador de la barra de arriba depende del túnel: hay que repintar
            // siempre, no solo cuando se está mirando Ajustes.
            override fun tunelCambio() { runOnUiThread { if (!isDestroyed) pintar(marco()) } }
        }
        pintar(marco())
        abrirComparacionPedida(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        abrirComparacionPedida(intent)
    }

    private fun abrirComparacionPedida(i: Intent?) {
        val bh = i?.getStringExtra(EXTRA_COMPARAR) ?: return
        i.removeExtra(EXTRA_COMPARAR)
        val c = X39App.agenda.contacto(bh) ?: return
        soloComparar = true
        confirmarHuellas(c)
    }

    /** Tras la comparación: si se vino del chat, se vuelve a él; si no, a la pantalla principal. */
    private fun trasComparar() {
        if (soloComparar) { soloComparar = false; finish() } else pintar(marco())
    }

    override fun onResume() {
        super.onResume()
        BusUi.oyente = this
        pintarEstado(BusUi.enLinea)
        BusUi.entrante?.let { startActivity(X39EntranteActivity.intento(this, it.nombre, it.conVideo)) }
    }

    override fun onPause() {
        super.onPause()
        if (BusUi.oyente === this) BusUi.oyente = null
    }

    // ===== Marco: top + cuerpo + barra inferior ================================

    private fun marco(): View {
        val raiz = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(grafito)
        }
        raiz.addView(topBar())
        val cont = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        cont.addView(cuerpo())
        raiz.addView(cont)
        raiz.addView(navBar())
        return raiz
    }

    private fun cuerpo(): View {
        // La billetera, si la hay, la aporta la edición (source set general). main no la conoce.
        EdicionExtra.cuerpoSi(this, pestana)?.let { return it }
        return when (pestana) {
            Pestana.MENSAJES -> cuerpoMensajes()
            Pestana.AJUSTES -> ajustesView()
            else -> cuerpoContactos()   // CONTACTOS y LLAMAR comparten lista
        }
    }

    private fun irA(p: Pestana) { pestana = p; pintar(marco()) }

    /** Vuelve a pintar Ajustes (tras encender el túnel, guardar un servidor, etc.). */
    private fun refrescarAjustes() = irA(Pestana.AJUSTES)

    private fun topBar(): View {
        val t = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(10))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        t.addView(ImageView(this).apply {
            setImageResource(R.drawable.x39_matrix_logo); contentDescription = "X39 MATRIX"
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = LinearLayout.LayoutParams(dp(34), dp(34)).apply { marginEnd = dp(8) }
        })
        t.addView(TextView(this).apply {
            text = getString(R.string.x39_edicion_cabecera); textSize = 22f; setTextColor(claro); setTypeface(typeface, Typeface.BOLD)
        })
        t.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(0, 1, 1f) })
        // Indicador del x39tunel: solo cuando está ARRIBA de verdad, no cuando está pedido.
        if (TunelX39.arriba()) {
            t.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = pill(sup2, 11, linea)
                setPaddingRelative(dp(8), dp(4), dp(9), dp(4))
                isClickable = true
                contentDescription = getString(R.string.x39_tunel_activo_desc)
                setOnClickListener { irA(Pestana.AJUSTES) }
                addView(TextView(this@X39CallActivity).apply {
                    text = "▲"; textSize = 10f; setTextColor(rojo)
                })
                addView(TextView(this@X39CallActivity).apply {
                    text = " x39tunel"; textSize = 10.5f; setTextColor(claro)
                })
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { marginEnd = dp(10) }
            })
        }
        // Candado de estado: al pulsarlo dice qué va cifrado, con qué, y qué NO tapa.
        // Nada mudo: si no explicara nada, sobraría.
        t.addView(FrameLayout(this).apply {
            // Área de toque cómoda (el icono son 20dp; el dedo necesita más).
            setPadding(dp(10), dp(10), dp(10), dp(10))
            isClickable = true
            contentDescription = getString(R.string.x39_candado_desc)
            setOnClickListener { queVaCifrado() }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) foreground = fondoPulsado()
            addView(icono(R.drawable.x39_ic_candado, ambar, 20).apply {
                layoutParams = FrameLayout.LayoutParams(dp(20), dp(20))
            })
        })
        return t
    }

    private fun navBar(): View {
        val barra = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = pill(sup2, 26, linea)
            setPadding(dp(6), dp(8), dp(6), dp(8))
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.setMargins(dp(12), dp(6), dp(12), dp(12))
            layoutParams = lp
        }
        barra.addView(navItem(Pestana.CONTACTOS, R.drawable.x39_ic_contactos, getString(R.string.x39_nav_contactos)))
        barra.addView(navItem(Pestana.LLAMAR, R.drawable.x39_ic_llamar, getString(R.string.x39_llamar)))
        barra.addView(navItem(Pestana.MENSAJES, R.drawable.x39_ic_mensajes, getString(R.string.x39_nav_mensajes)))
        // Entrada de billetera en la barra: solo si la edición la trae (general). sevilla no la ve.
        EdicionExtra.entradaNav(this)?.let { barra.addView(it) }
        barra.addView(navItem(Pestana.AJUSTES, R.drawable.x39_ic_ajustes, getString(R.string.x39_nav_ajustes)))
        return barra
    }

    internal fun navItem(p: Pestana, res: Int, etiqueta: String): View {
        val activo = pestana == p
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setPadding(0, dp(2), 0, 0)
            setOnClickListener { irA(p) }
        }
        val cont = FrameLayout(this).apply {
            if (activo) background = pill(grafito, 12, grafito)
            setPadding(dp(14), dp(5), dp(14), dp(5))
        }
        cont.addView(icono(res, if (activo) claro else gris, 20))
        col.addView(cont)
        col.addView(TextView(this).apply {
            text = etiqueta; textSize = 9.5f; setTextColor(if (activo) claro else gris)
            setPadding(0, dp(3), 0, 0)
        })
        return col
    }

    // ===== Cuerpo: CONTACTOS ===================================================

    private fun cuerpoContactos(): View {
        val col = columnaScroll()
        col.addView(cabeceraMatrix())
        estadoTv = TextView(this).apply {
            textSize = 12f; setPaddingRelative(dp(2), 0, 0, dp(6))
        }
        pintarEstado(BusUi.enLinea)
        col.addView(estadoTv)

        col.addView(tituloSeccion(getString(R.string.x39_contactos_titulo)))
        val tarjeta = tarjetaCol()
        val todos = X39App.agenda.todos()
        if (todos.isEmpty()) {
            tarjeta.addView(TextView(this).apply {
                text = getString(R.string.x39_contactos_vacio)
                textSize = 13f; setTextColor(gris); setPadding(dp(4), dp(6), dp(4), dp(6))
            })
        } else {
            for ((i, c) in todos.withIndex()) {
                if (i > 0) tarjeta.addView(separador())
                tarjeta.addView(filaContacto(c))
            }
        }
        col.addView(tarjeta)

        col.addView(tituloSeccion(getString(R.string.x39_contactos_mi_identidad)))
        col.addView(tarjetaIdentidad())
        return envolver(col)
    }

    private fun filaContacto(c: org.x39.bolsillo.Agenda.Contacto): View {
        val fila = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(10), dp(4), dp(10))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        fila.addView(avatar(iniciales(c.nombre), c.bh, 40))
        fila.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(12); marginEnd = dp(10)
            }
            addView(TextView(this@X39CallActivity).apply {
                text = c.nombre; textSize = 15f; setTextColor(claro); setTypeface(typeface, Typeface.BOLD)
            })
            addView(TextView(this@X39CallActivity).apply {
                if (c.verificado) {
                    text = getString(R.string.x39_contacto_verificado)
                    textSize = 11f; setTextColor(claro); setPadding(0, dp(1), 0, 0)
                } else {
                    // Sin verificar: que se vea de un vistazo, no en letra pequeña.
                    text = getString(R.string.x39_contacto_sin_verificar)
                    textSize = 12f; setTextColor(Color.BLACK); setTypeface(typeface, Typeface.BOLD)
                    background = pill(ambar, 8, ambar)
                    setPadding(dp(8), dp(2), dp(8), dp(2))
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(3) }
                }
            })
            addView(TextView(this@X39CallActivity).apply {
                text = getString(R.string.x39_contacto_huella); textSize = 10f; setTextColor(tenue); typeface = Typeface.MONOSPACE; setPadding(0, dp(3), 0, 0)
            })
            addView(TextView(this@X39CallActivity).apply {
                text = huellaGrupos(c.bh, 3) + "…"; textSize = 10f; setTextColor(tenue)
                typeface = Typeface.MONOSPACE
            })
        })
        // El botón redondo rojo se queda como atajo directo a llamada de voz.
        fila.addView(botonLlamar { conPermisos { iniciarLlamada(c.bh, c.nombre, false) } })
        // Toda la fila abre el menú, y la pulsación larga también (es lo que se espera en Android).
        fila.isClickable = true
        fila.setOnClickListener { menuContacto(c) }
        fila.setOnLongClickListener { menuContacto(c); true }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) fila.foreground = fondoPulsado()
        return fila
    }

    private fun tarjetaIdentidad(): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = tarjeta()
            setPadding(dp(14), dp(14), dp(14), dp(14))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) }
            setOnClickListener { pantallaIdentidad() }
        }
        card.addView(FrameLayout(this).apply {
            background = pill(sup2, 14, sup2)
            val s = dp(52)
            layoutParams = LinearLayout.LayoutParams(s, s)
            addView(icono(R.drawable.x39_ic_qr, claro, 24).apply {
                layoutParams = FrameLayout.LayoutParams(dp(24), dp(24)).apply { gravity = Gravity.CENTER }
            })
        })
        card.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(12)
            }
            addView(TextView(this@X39CallActivity).apply {
                text = getString(R.string.x39_identidad_comparte); textSize = 14f; setTextColor(claro)
                setTypeface(typeface, Typeface.BOLD)
            })
            addView(TextView(this@X39CallActivity).apply {
                text = getString(R.string.x39_identidad_escanea); textSize = 11f; setTextColor(tenue)
                typeface = Typeface.MONOSPACE; setPadding(0, dp(2), 0, 0)
            })
        })
        return card
    }

    // ===== Cuerpo: MENSAJES ====================================================

    /**
     * Pestaña «Mensajes»: la lista de conversaciones con historial, la más reciente
     * arriba. No es un índice de contactos: solo sale quien tiene mensajes.
     */
    private fun cuerpoMensajes(): View {
        val col = columnaScroll()
        val convs = Mensajes.conversaciones()
        if (convs.isEmpty()) return envolver(col.apply { addView(sinConversaciones()) })

        col.addView(tituloSeccion(getString(R.string.x39_conversaciones_titulo)))
        val tarjeta = tarjetaCol()
        for ((i, c) in convs.withIndex()) {
            if (i > 0) tarjeta.addView(separador())
            tarjeta.addView(filaConversacion(c))
        }
        col.addView(tarjeta)
        return envolver(col)
    }

    private fun sinConversaciones(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(dp(28), dp(80), dp(28), dp(28))
        addView(icono(R.drawable.x39_ic_mensajes, tenue, 44).apply {
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            }
        })
        addView(TextView(this@X39CallActivity).apply {
            text = getString(R.string.x39_conversaciones_vacio_titulo)
            textSize = 17f; setTextColor(claro); gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD); setPadding(0, dp(16), 0, dp(6))
        })
        addView(TextView(this@X39CallActivity).apply {
            text = getString(R.string.x39_conversaciones_vacio_texto)
            textSize = 13f; setTextColor(gris); gravity = Gravity.CENTER
        })
        addView(botonAncho(getString(R.string.x39_conversaciones_ir_contactos), sup2, claro) { irA(Pestana.CONTACTOS) }.apply {
            (layoutParams as LinearLayout.LayoutParams).topMargin = dp(22)
        })
    }

    private fun filaConversacion(c: Mensajes.Resumen): View {
        val fila = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(10), dp(4), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            isClickable = true
            setOnClickListener { X39ChatActivity.abrir(this@X39CallActivity, c.bh) }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) foreground = fondoPulsado()
        }
        fila.addView(avatar(iniciales(c.nombre), c.bh, 40))
        fila.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginStart = dp(12); marginEnd = dp(10) }
            addView(TextView(this@X39CallActivity).apply {
                text = c.nombre; textSize = 15f; setTextColor(claro)
                setTypeface(typeface, Typeface.BOLD)
            })
            addView(TextView(this@X39CallActivity).apply {
                text = if (c.ultimoMio) getString(R.string.x39_conversacion_ultimo_mio, c.ultimo) else c.ultimo
                textSize = 13f
                setTextColor(if (c.sinLeer > 0) claro else gris)
                maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(0, dp(3), 0, 0)
            })
        })
        fila.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
            addView(TextView(this@X39CallActivity).apply {
                text = hora(c.cuando); textSize = 11f; setTextColor(tenue)
                gravity = Gravity.END
            })
            // Punto rojo solo si de verdad hay algo sin leer.
            if (c.sinLeer > 0) {
                addView(View(this@X39CallActivity).apply {
                    background = pill(rojo, 5, rojo)
                    layoutParams = LinearLayout.LayoutParams(dp(10), dp(10)).apply {
                        topMargin = dp(6); gravity = Gravity.END
                    }
                })
            }
        })
        return fila
    }

    private fun hora(segundos: Long): String {
        val ahora = System.currentTimeMillis() / 1000L
        val f = if (ahora - segundos < 24 * 3600) "HH:mm" else "dd/MM"
        return java.text.SimpleDateFormat(f, java.util.Locale.getDefault())
            .format(java.util.Date(segundos * 1000))
    }

    // ===== Ajustes =============================================================

    /** Cuerpo de la pestaña Ajustes (ya no es una pantalla aparte: tiene su sitio abajo). */
    private fun ajustesView(): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(6), dp(16), dp(16))
        }
        col.addView(tituloSeccion(getString(R.string.x39_ajustes_identidad_red)))
        val g1 = tarjetaCol()
        g1.addView(filaAjuste(R.drawable.x39_ic_contactos, getString(R.string.x39_ajustes_mi_identidad), getString(R.string.x39_ajustes_mi_identidad_sub)) { pantallaIdentidad() })
        g1.addView(separador())
        g1.addView(filaToggle(R.drawable.x39_ic_escudo, "x39tunel",
            if (TunelX39.activado()) TunelX39.estado(this) else getString(R.string.x39_ajustes_tunel_sub_apagado),
            TunelX39.activado()) {
            if (TunelX39.activado()) {
                TunelX39.apagar(); refrescarAjustes()
            } else {
                val pide = TunelX39.permisoVpn()
                if (pide != null) permisoVpn.launch(pide) else { TunelX39.encender(X39App.nucleo); refrescarAjustes() }
            }
        })
        if (TunelX39.activado()) {
            g1.addView(separador())
            g1.addView(filaToggle(R.drawable.x39_ic_escudo, getString(R.string.x39_ajustes_enrutar_todo),
                if (TunelX39.rutaTodo()) getString(R.string.x39_ajustes_enrutar_todo_si)
                else getString(R.string.x39_ajustes_enrutar_todo_no),
                TunelX39.rutaTodo()) {
                TunelX39.rutaTodo(!TunelX39.rutaTodo()); refrescarAjustes()
            })
            g1.addView(separador())
            g1.addView(filaAjuste(R.drawable.x39_ic_ajustes, getString(R.string.x39_ajustes_importar_conf),
                getString(R.string.x39_ajustes_importar_conf_sub)) {
                abrirConf.launch(arrayOf("*/*"))
            })
            g1.addView(separador())
            g1.addView(filaAjuste(R.drawable.x39_ic_escudo, getString(R.string.x39_ajustes_clave_tunel),
                getString(R.string.x39_ajustes_clave_tunel_sub)) { claveDelTunel() })
        }
        g1.addView(separador())
        g1.addView(filaAjuste(R.drawable.x39_ic_ajustes, getString(R.string.x39_ajustes_servidor_senal), Config.urlSenal(this)) { editorSenal() })
        g1.addView(separador())
        g1.addView(filaAjuste(R.drawable.x39_ic_ajustes, getString(R.string.x39_ajustes_servidor_llamadas), Config.urlLiveKit(this)) { editorLiveKit() })
        // Fila «nodo Bitcoin»: solo en la edición con billetera (general). sevilla no la compila.
        EdicionExtra.filaAjustesNodo(this)?.let { g1.addView(separador()); g1.addView(it) }
        col.addView(g1)

        col.addView(tituloSeccion(getString(R.string.x39_ajustes_recepcion)))
        val g3 = tarjetaCol()
        g3.addView(filaAjuste(R.drawable.x39_ic_escudo, getString(R.string.x39_ajustes_segundo_plano),
            getString(if (todoListoParaRecibir()) R.string.x39_ajustes_segundo_plano_ok else R.string.x39_ajustes_segundo_plano_falta)) { pantallaFondo() })
        col.addView(g3)

        col.addView(tituloSeccion(getString(R.string.x39_ajustes_mensajes)))
        val g4 = tarjetaCol()
        g4.addView(filaAjuste(R.drawable.x39_ic_mensajes, getString(R.string.x39_ajustes_borrado_defecto),
            getString(Mensajes.borradoPorDefecto().frase)) { elegirBorradoPorDefecto() })
        col.addView(g4)

        col.addView(tituloSeccion(getString(R.string.x39_idioma)))
        val gI = tarjetaCol()
        gI.addView(filaAjuste(R.drawable.x39_ic_ajustes, getString(R.string.x39_idioma), Idioma.nombreActual(this)) { elegirIdioma() })
        col.addView(gI)

        col.addView(tituloSeccion(getString(R.string.x39_ajustes_seguridad)))
        val g2 = tarjetaCol()
        g2.addView(filaToggle(R.drawable.x39_ic_escudo, getString(R.string.x39_ajustes_anticaptura),
            getString(if (antiCaptura()) R.string.x39_ajustes_anticaptura_si else R.string.x39_ajustes_anticaptura_no), antiCaptura()) {
            getSharedPreferences("x39", MODE_PRIVATE).edit().putBoolean("anticaptura", !antiCaptura()).apply()
            toast(getString(if (antiCaptura()) R.string.x39_ajustes_anticaptura_encendido else R.string.x39_ajustes_anticaptura_apagado))
            recreate()
        })
        // Fila «Nodo»: solo en la edición con billetera (general): qué nodo usa y qué ve ese servidor.
        EdicionExtra.filaSeguridadNodo(this)?.let { g2.addView(separador()); g2.addView(it) }
        g2.addView(separador())
        g2.addView(filaAjuste(R.drawable.x39_ic_escudo, getString(R.string.x39_ajustes_acerca), getString(R.string.x39_ajustes_acerca_sub)) { acercaDe() })
        col.addView(g2)

        return envolver(col)
    }

    /** Sistema + cinco idiomas, cada uno escrito en su lengua. Cambia al instante y se recuerda. */
    private fun elegirIdioma() {
        val actual = Idioma.actual()
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.x39_idioma))
            .setSingleChoiceItems(Idioma.nombres(this), Idioma.ETIQUETAS.indexOf(actual).coerceAtLeast(0)) { d, i ->
                d.dismiss()
                if (Idioma.ETIQUETAS[i] != actual) Idioma.poner(this, Idioma.ETIQUETAS[i])
            }
            .setNegativeButton(getString(R.string.x39_cancelar), null)
            .show()
    }

    internal fun filaAjuste(res: Int, titulo: String, sub: String, onClick: () -> Unit): View {
        val fila = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(12), dp(4), dp(12))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setOnClickListener { onClick() }
        }
        fila.addView(icono(res, gris, 22))
        fila.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(13) }
            addView(TextView(this@X39CallActivity).apply { text = titulo; textSize = 14f; setTextColor(claro) })
            addView(TextView(this@X39CallActivity).apply {
                text = sub; textSize = 11f; setTextColor(tenue); setPadding(0, dp(2), 0, 0); maxLines = 1
            })
        })
        fila.addView(TextView(this).apply { text = "›"; textSize = 20f; setTextColor(tenue) })
        return fila
    }

    private fun filaToggle(res: Int, titulo: String, sub: String, activo: Boolean, onClick: () -> Unit): View {
        val fila = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(12), dp(4), dp(12))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setOnClickListener { onClick() }
        }
        fila.addView(icono(res, gris, 22))
        fila.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(13) }
            addView(TextView(this@X39CallActivity).apply { text = titulo; textSize = 14f; setTextColor(claro) })
            addView(TextView(this@X39CallActivity).apply { text = sub; textSize = 11f; setTextColor(tenue); setPadding(0, dp(2), 0, 0) })
        })
        fila.addView(toggle(activo))
        return fila
    }

    private fun editorSenal() = editorDoble(getString(R.string.x39_ajustes_servidor_senal), getString(R.string.x39_editor_senal_hint),
        Config.urlSenal(this), getString(R.string.x39_editor_pin_hint), Config.pinSenal(this)) { url, pin ->
        Config.guardar(this, url, Config.urlLiveKit(this), pin); reinicioAviso()
    }

    private fun editorLiveKit() = editorDoble(getString(R.string.x39_ajustes_servidor_llamadas), getString(R.string.x39_editor_livekit_hint),
        Config.urlLiveKit(this), null, null) { url, _ ->
        Config.guardar(this, Config.urlSenal(this), url, Config.pinSenal(this)); reinicioAviso()
    }

    private fun editorDoble(titulo: String, h1: String, v1: String, h2: String?, v2: String?, onGuardar: (String, String) -> Unit) {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(28), dp(20), dp(20))
        }
        col.addView(tit(titulo))
        val c1 = campo(h1).apply { setText(v1) }
        col.addView(c1)
        var c2: EditText? = null
        if (h2 != null) {
            col.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(10)) })
            c2 = campo(h2).apply { setText(v2 ?: "") }
            col.addView(c2)
        }
        col.addView(Button(this).apply {
            text = getString(R.string.x39_guardar); setTextColor(Color.WHITE); setBackgroundColor(rojo)
            layoutParams = anchoTotal(dp(18))
            setOnClickListener { onGuardar(c1.text.toString(), c2?.text?.toString() ?: ""); refrescarAjustes() }
        })
        col.addView(volver { refrescarAjustes() })
        pintar(envolver(col))
    }

    private fun reinicioAviso() { toast(getString(R.string.x39_guardado_reinicia)) }

    /**
     * La clave pública WireGuard de este móvil. Hace falta cuando el servidor todavía
     * no da de alta los pares solo: se copia y se da de alta a mano en el VPS. La
     * privada no se enseña nunca: vive sellada en el Keystore.
     */
    private fun claveDelTunel() {
        val pub = TunelX39.miClaveWg()
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.x39_ajustes_clave_tunel))
            .setMessage(getString(R.string.x39_clave_tunel_texto, pub))
            .setPositiveButton(getString(R.string.x39_copiar)) { _, _ -> copiar("clave wg x39", pub); toast(getString(R.string.x39_clave_copiada)) }
            .setNegativeButton(getString(R.string.x39_cerrar), null)
            .show()
    }

    /** Cuánto tardan en borrarse los mensajes de las conversaciones nuevas. */
    private fun elegirBorradoPorDefecto() {
        val ops = Mensajes.Borrado.entries.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.x39_ajustes_borrado_defecto))
            .setSingleChoiceItems(
                ops.map { getString(it.opcion) }.toTypedArray(),
                ops.indexOf(Mensajes.borradoPorDefecto()),
            ) { d, i ->
                Mensajes.borradoPorDefecto(ops[i]); d.dismiss(); refrescarAjustes()
            }
            .setMessage(getString(R.string.x39_borrado_defecto_texto))
            .setNegativeButton(getString(R.string.x39_cancelar), null)
            .show()
    }

    // ===== El candado de la barra de arriba ===================================

    /**
     * Qué hay detrás del candado. Dos reglas al escribir esto:
     *   · Solo se afirma lo que se puede comprobar.
     *   · Se dice también lo que el cifrado NO tapa. El servidor ve quién habla con
     *     quién, cuándo y cuánto, y eso no lo esconde ninguna clave.
     * París (el núcleo) protege el CONTENIDO; el x39tunel protege la RUTA. Son capas
     * distintas y ninguna sustituye a la otra, así que aquí van separadas.
     */
    private fun queVaCifrado() {
        val tunel = if (TunelX39.activado()) {
            getString(R.string.x39_cifrado_ruta_tunel, TunelX39.estado(this))
        } else {
            getString(R.string.x39_cifrado_ruta_directa)
        }
        val texto = getString(R.string.x39_edicion_cifrado_texto, tunel)

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.x39_cifrado_titulo))
            .setMessage(texto)
            .setPositiveButton(getString(R.string.x39_entendido), null)
            .setNeutralButton(getString(R.string.x39_ver_mi_identidad)) { _, _ -> pantallaIdentidad() }
            .show()
    }

    // ===== Recepción en segundo plano (el muro de MIUI) =======================

    /** Permiso de notificaciones: sin él no hay aviso de llamada entrante en Android 13+. */
    private val permisoAvisos =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* se refleja en Ajustes */ }

    private fun pedirAvisos() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            permisoAvisos.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun avisosOk(): Boolean = NotificationManagerCompat.from(this).areNotificationsEnabled()

    private fun bateriaLibre(): Boolean =
        (getSystemService(POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName)

    /** En Android 14+ la notificación a pantalla completa es un permiso aparte. */
    private fun pantallaCompletaOk(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE ||
            (getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager).canUseFullScreenIntent()

    private fun alarmaExactaOk(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            (getSystemService(ALARM_SERVICE) as android.app.AlarmManager).canScheduleExactAlarms()

    private fun todoListoParaRecibir(): Boolean =
        avisosOk() && bateriaLibre() && pantallaCompletaOk() && alarmaExactaOk()

    /**
     * MIUI mata todo lo que no le pide permiso a mano. Aquí está la lista, con lo que
     * la app PUEDE comprobar marcado de verdad y lo que no, dicho tal cual: el autoinicio
     * y el bloqueo en recientes no se pueden leer desde fuera, así que los comprueba la
     * persona. No se inventa ninguna marca verde.
     */
    private fun pantallaFondo() {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(24), dp(18), dp(20))
        }
        col.addView(tit(getString(R.string.x39_ajustes_segundo_plano)))
        col.addView(TextView(this).apply {
            text = getString(R.string.x39_fondo_intro)
            textSize = 12f; setTextColor(gris); setPadding(0, 0, 0, dp(6))
        })

        col.addView(tituloSeccion(getString(R.string.x39_fondo_comprobable)))
        val g1 = tarjetaCol()
        g1.addView(filaFondo(getString(R.string.x39_fondo_avisos), avisosOk(),
            getString(R.string.x39_fondo_avisos_sub)) {
            abrirAjuste(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
        })
        g1.addView(separador())
        g1.addView(filaFondo(getString(R.string.x39_fondo_bateria), bateriaLibre(),
            getString(R.string.x39_fondo_bateria_sub)) {
            abrirAjuste(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:$packageName")),
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
            )
        })
        g1.addView(separador())
        g1.addView(filaFondo(getString(R.string.x39_fondo_pantalla_completa), pantallaCompletaOk(),
            getString(R.string.x39_fondo_pantalla_completa_sub)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                abrirAjuste(
                    Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT)
                        .setData(Uri.parse("package:$packageName")),
                )
            } else {
                toast(getString(R.string.x39_fondo_no_hace_falta))
            }
        })
        g1.addView(separador())
        g1.addView(filaFondo(getString(R.string.x39_fondo_alarma), alarmaExactaOk(),
            getString(R.string.x39_fondo_alarma_sub)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                abrirAjuste(
                    Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                        .setData(Uri.parse("package:$packageName")),
                )
            } else {
                toast(getString(R.string.x39_fondo_no_hace_falta))
            }
        })
        col.addView(g1)

        col.addView(tituloSeccion(getString(R.string.x39_fondo_manual)))
        val g2 = tarjetaCol()
        g2.addView(filaManual(getString(R.string.x39_fondo_autoinicio),
            getString(R.string.x39_fondo_autoinicio_sub)) {
            abrirAjuste(
                Intent().setClassName("com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity"),
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.parse("package:$packageName")),
            )
        })
        g2.addView(separador())
        g2.addView(filaManual(getString(R.string.x39_fondo_recientes),
            getString(R.string.x39_fondo_recientes_sub)) {
            toast(getString(R.string.x39_fondo_recientes_aviso))
        })
        col.addView(g2)

        col.addView(TextView(this).apply {
            text = getString(R.string.x39_fondo_pie)
            textSize = 10f; setTextColor(tenue); gravity = Gravity.CENTER
            setPadding(dp(8), dp(18), dp(8), 0)
        })
        col.addView(volver { refrescarAjustes() })
        pintar(envolver(col))
    }

    /** Fila con marca real: ✓ si la app lo ha podido comprobar, ! si falta. */
    private fun filaFondo(titulo: String, ok: Boolean, sub: String, onClick: () -> Unit): View {
        val fila = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(12), dp(4), dp(12))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            isClickable = true
            setOnClickListener { onClick() }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) foreground = fondoPulsado()
        }
        fila.addView(TextView(this).apply {
            text = if (ok) "✓" else "!"
            textSize = 16f; setTextColor(if (ok) claro else ambar)
            layoutParams = LinearLayout.LayoutParams(dp(22), ViewGroup.LayoutParams.WRAP_CONTENT)
        })
        fila.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginStart = dp(8) }
            addView(TextView(this@X39CallActivity).apply {
                text = titulo; textSize = 14f; setTextColor(claro)
            })
            addView(TextView(this@X39CallActivity).apply {
                text = if (ok) getString(R.string.x39_fondo_concedido) else sub
                textSize = 11f; setTextColor(if (ok) tenue else gris); setPadding(0, dp(2), 0, 0)
            })
        })
        fila.addView(TextView(this).apply { text = "›"; textSize = 20f; setTextColor(tenue) })
        return fila
    }

    /** Fila sin marca: MIUI no deja leer el estado, así que no se finge saberlo. */
    private fun filaManual(titulo: String, sub: String, onClick: () -> Unit): View {
        val fila = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(12), dp(4), dp(12))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            isClickable = true
            setOnClickListener { onClick() }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) foreground = fondoPulsado()
        }
        fila.addView(TextView(this).apply {
            text = "?"; textSize = 16f; setTextColor(gris)
            layoutParams = LinearLayout.LayoutParams(dp(22), ViewGroup.LayoutParams.WRAP_CONTENT)
        })
        fila.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginStart = dp(8) }
            addView(TextView(this@X39CallActivity).apply {
                text = titulo; textSize = 14f; setTextColor(claro)
            })
            addView(TextView(this@X39CallActivity).apply {
                text = sub; textSize = 11f; setTextColor(gris); setPadding(0, dp(2), 0, 0)
            })
        })
        fila.addView(TextView(this).apply { text = "›"; textSize = 20f; setTextColor(tenue) })
        return fila
    }

    /** Abre el primer ajuste del sistema que exista; si ninguno, lo dice. */
    private fun abrirAjuste(vararg intentos: Intent) {
        for (i in intentos) {
            try { startActivity(i); return } catch (e: Exception) { /* siguiente */ }
        }
        toast(getString(R.string.x39_fondo_sin_pantalla_ajustes))
    }

    // ===== Identidad (compartir + añadir) ======================================

    private fun pantallaIdentidad() {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(28), dp(20), dp(20))
        }
        col.addView(tit(getString(R.string.x39_ajustes_mi_identidad)))
        // QR con la clave pública ML-DSA-87 (2.592 bytes): el otro lo lee con «Escanear QR».
        val qr = ImageView(this).apply {
            adjustViewBounds = true; scaleType = ImageView.ScaleType.FIT_CENTER
            background = pill(Color.WHITE, 14, Color.WHITE); setPadding(dp(10), dp(10), dp(10), dp(10))
            contentDescription = getString(R.string.x39_identidad_qr_desc)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) }
            setOnClickListener { qrPantallaCompleta(this) }
        }
        col.addView(qr)
        col.addView(botonAncho(getString(R.string.x39_qr_por_partes), rojo, Color.WHITE) { qrPorPartes() })
        col.addView(botonAncho(getString(R.string.x39_qr_pantalla_completa), sup2, claro) { qrPantallaCompleta(qr) })

        /*
         * DOS códigos, y no es capricho: tu identidad son dos claves (la de FIRMAR, con
         * la que te llaman, y la de CIFRAR, con la que te escriben) y juntas ocupan 5.559
         * caracteres. En un QR caben 2.953. Así que va una en cada código.
         *
         * Quien te añade escanea el primero para darte de alta, y el segundo desde
         * «Cifrar» para poder escribirte.
         */
        val (miKem, _) = Boveda.clavesKem(this)
        val pie = TextView(this).apply {
            textSize = 11.5f; setTextColor(gris); gravity = Gravity.CENTER
            setPadding(dp(6), dp(10), dp(6), 0)
        }
        var mostrandoFirma = true
        fun pintarQr() {
            if (mostrandoFirma) {
                qr.contentDescription = getString(R.string.x39_identidad_qr_firma_desc)
                dibujarQr(qr, X39App.nucleo.miClaveFirma())
                pie.text = getString(R.string.x39_identidad_qr_firma_pie)
            } else {
                qr.contentDescription = getString(R.string.x39_identidad_qr_cifrar_desc)
                dibujarQr(qr, org.x39.bolsillo.Mensajeria.claveCifradoTexto(miKem).toByteArray(Charsets.ISO_8859_1))
                pie.text = getString(R.string.x39_identidad_qr_cifrar_pie)
            }
        }
        pintarQr()
        col.addView(pie)
        col.addView(botonAncho(getString(R.string.x39_identidad_ver_otro), sup2, claro) {
            mostrandoFirma = !mostrandoFirma; pintarQr()
        })
        col.addView(tituloSeccion(getString(R.string.x39_identidad_tu_foto)))
        col.addView(bloqueFoto())

        col.addView(tituloSeccion(getString(R.string.x39_identidad_tu_huella)))
        col.addView(TextView(this).apply {
            text = huellaGrupos(X39App.identidad.bh, 16); textSize = 13f; setTextColor(claro); typeface = Typeface.MONOSPACE
            setPadding(0, dp(4), 0, dp(8))
        })
        col.addView(botonAncho(getString(R.string.x39_identidad_copiar), rojo, Color.WHITE) {
            val (kem, _) = Boveda.clavesKem(this)
            val id = org.x39.bolsillo.Mensajeria.identidadTexto(kem, X39App.identidad.sigPk)
            copiar("identidad x39", id)
            toast(getString(R.string.x39_identidad_copiada))
        })
        col.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(8)) })
        col.addView(botonAncho(getString(R.string.x39_identidad_escanear), sup2, claro) {
            conPermisos { escaner.launch(Intent(this, EscanerQr::class.java)) }
        })
        col.addView(tituloSeccion(getString(R.string.x39_identidad_o_pega)))
        val nombre = campo(getString(R.string.x39_identidad_nombre_hint)); col.addView(nombre)
        col.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(8)) })
        val clave = campo(getString(R.string.x39_identidad_pega_hint)); col.addView(clave)
        col.addView(botonAncho(getString(R.string.x39_guardar_contacto), sup2, claro) {
            val n = nombre.text.toString().trim(); val k = clave.text.toString().trim()
            if (n.isEmpty() || k.isEmpty()) { toast(getString(R.string.x39_nombre_e_identidad)); return@botonAncho }
            try {
                if (k.startsWith("X39ID1:")) {
                    val par = org.x39.bolsillo.Mensajeria.identidadDeTexto(k)
                    val kem = par.copyOfRange(0, org.x39.bolsillo.Mensajeria.KEM_PK)
                    val sig = par.copyOfRange(org.x39.bolsillo.Mensajeria.KEM_PK, par.size)
                    if (esPropia(sig, kem)) { toast(getString(R.string.x39_identidad_propia)); return@botonAncho }
                    val bh = X39App.agenda.guardar(n, sig, kem)
                    toast(getString(R.string.x39_contacto_guardado_sin_verificar))
                    pestana = Pestana.CONTACTOS
                    X39App.agenda.contacto(bh)?.let { confirmarHuellas(it) }
                    return@botonAncho
                } else {
                    // Identidad antigua: solo la clave de firmar. Se puede llamar, no escribir.
                    val sig = Base64.decode(k, Base64.NO_WRAP)
                    if (esPropia(sig)) { toast(getString(R.string.x39_identidad_propia)); return@botonAncho }
                    X39App.agenda.guardar(n, sig)
                }
                toast(getString(R.string.x39_contacto_guardado))
                pestana = Pestana.CONTACTOS; pintar(marco())
            } catch (e: Exception) { toast(getString(R.string.x39_identidad_no_valida)) }
        })
        col.addView(volver { pintar(marco()) })
        pintar(envolver(col))
    }

    // ===== Entrante + lanzar llamada ===========================================

    /**
     * Menú del contacto: hoja inferior de Material, no un AlertDialog (en MIUI el diálogo
     * quedaba feo y a veces ni se veía). Fondo #12141A, esquinas de arriba de 20dp,
     * separadores de 1px y filas de 56dp.
     */
    private fun menuContacto(c: org.x39.bolsillo.Agenda.Contacto) {
        val hoja = Dialog(this, R.style.X39HojaInferior)

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = fondoHoja()
            setPadding(0, dp(10), 0, dp(14))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }

        // Tirador
        col.addView(View(this).apply {
            background = pill(linea, 2, linea)
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(4)).apply {
                gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(8)
            }
        })

        // Cabecera: avatar 48dp + nombre + huella corta
        val cab = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(10), dp(20), dp(16))
        }
        cab.addView(FrameLayout(this).apply {
            background = pill(sup2, 24, sup2)
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
            addView(TextView(this@X39CallActivity).apply {
                text = iniciales(c.nombre); setTextColor(claro); textSize = 16f
                setTypeface(typeface, Typeface.BOLD); gravity = Gravity.CENTER
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                )
            })
        })
        cab.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginStart = dp(14) }
            addView(TextView(this@X39CallActivity).apply {
                text = c.nombre; textSize = 17f; setTextColor(claro)
                setTypeface(typeface, Typeface.BOLD)
            })
            addView(TextView(this@X39CallActivity).apply {
                text = huellaCorta(c.bh); textSize = 12f; setTextColor(gris)
                typeface = Typeface.MONOSPACE; setPadding(0, dp(3), 0, 0)
            })
        })
        col.addView(cab)
        col.addView(separador())

        fun cerrarY(accion: () -> Unit) { hoja.dismiss(); accion() }

        col.addView(filaHoja(R.drawable.x39_ic_llamar, getString(R.string.x39_llamar), claro) {
            cerrarY { conPermisos { iniciarLlamada(c.bh, c.nombre, false) } }
        })
        col.addView(separador())
        col.addView(filaHoja(R.drawable.x39_ic_camara, getString(R.string.x39_videollamada), claro) {
            cerrarY { conPermisos { iniciarLlamada(c.bh, c.nombre, true) } }
        })
        col.addView(separador())
        col.addView(filaHoja(R.drawable.x39_ic_mensajes, getString(R.string.x39_menu_mensaje), claro) {
            cerrarY { X39ChatActivity.abrir(this, c.bh) }
        })
        col.addView(separador())
        col.addView(filaHoja(R.drawable.x39_ic_candado, getString(R.string.x39_menu_cifrar), claro) {
            cerrarY { cifrarContacto(c) }
        })
        col.addView(separador())
        col.addView(filaHoja(R.drawable.x39_ic_qr, getString(R.string.x39_menu_compartir_identidad), claro) {
            cerrarY { pantallaIdentidad() }
        })
        col.addView(separador())
        col.addView(filaHoja(R.drawable.x39_ic_borrar, getString(R.string.x39_menu_borrar_contacto), rojo) {
            cerrarY { confirmarBorrado(c) }
        })

        hoja.setContentView(col)
        hoja.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.BOTTOM)
        }
        hoja.show()
    }

    /** Fila de 56dp de la hoja inferior. */
    private fun filaHoja(res: Int, texto: String, color: Int, onClick: () -> Unit): View {
        val fila = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), 0, dp(20), 0)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56))
            isClickable = true
            setOnClickListener { onClick() }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) foreground = fondoPulsado()
        }
        fila.addView(icono(res, color, 20))
        fila.addView(TextView(this).apply {
            text = texto; textSize = 15f; setTextColor(color)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { marginStart = dp(16) }
        })
        return fila
    }

    /** Fondo de la hoja: grafito de superficie con las dos esquinas de arriba redondeadas. */
    private fun fondoHoja(): GradientDrawable = GradientDrawable().apply {
        setColor(sup)
        val r = dp(20).toFloat()
        cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
    }

    /** Resalte al pulsar (?attr/selectableItemBackground del tema). */
    private fun fondoPulsado(): android.graphics.drawable.Drawable? {
        val ta = obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
        val d = ta.getDrawable(0)
        ta.recycle()
        return d
    }

    /** Borrar toca la Agenda: se pregunta antes. */
    private fun confirmarBorrado(c: org.x39.bolsillo.Agenda.Contacto) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.x39_borrar_contacto_titulo, c.nombre))
            .setMessage(getString(R.string.x39_borrar_contacto_texto))
            .setPositiveButton(getString(R.string.x39_borrar)) { _, _ ->
                X39App.agenda.borrar(c.bh); Fotos.borrarDe(c.bh)
                toast(getString(R.string.x39_contacto_borrado)); pintar(marco())
            }
            .setNegativeButton(getString(R.string.x39_cancelar), null)
            .show()
    }

    /**
     * «Cifrar»: el paso que consigue la clave ML-KEM-1024 del otro para poder mandarle
     * mensajes aunque esté desconectado. Hoy un contacto guarda solo su clave de firma
     * ML-DSA-87, que sirve para llamar pero no para cerrarle un sobre.
     *
     * Ese intercambio (identidad v2) todavía no está montado: aquí se dice tal cual, no
     * se simula. Lo que sí se puede hacer ya es lo de siempre: marcar al contacto como
     * verificado cuando las seis palabras han coincidido en voz alta. Eso lo decide la
     * persona, nunca la app.
     */
    /**
     * «Cifrar»: conseguir la clave de CIFRAR del contacto (ML-KEM-1024).
     *
     * Hoy de un contacto guardas su clave de FIRMAR, que basta para llamarle. Para
     * cerrarle un mensaje hace falta también la de cifrar. Este es el paso que la trae,
     * y el que convierte «pendiente de verificar» en «✓ verificado» — pero solo cuando
     * la persona confirma que las huellas coinciden. Nunca automático.
     */
    private fun cifrarContacto(c: org.x39.bolsillo.Agenda.Contacto) {
        if (c.puedeRecibirMensajes()) { confirmarHuellas(c); return }

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(24), dp(20), dp(20))
        }
        col.addView(tit(getString(R.string.x39_cifrar_titulo, c.nombre)))
        col.addView(TextView(this).apply {
            text = getString(R.string.x39_cifrar_texto, c.nombre)
            textSize = 13f; setTextColor(gris); setPadding(0, 0, 0, dp(6))
        })
        col.addView(botonAncho(getString(R.string.x39_cifrar_escanear), rojo, Color.WHITE) {
            contactoParaCifrar = c.bh
            conPermisos { escaner.launch(Intent(this, EscanerQr::class.java)) }
        })
        col.addView(tituloSeccion(getString(R.string.x39_identidad_o_pega)))
        val campo = campo(getString(R.string.x39_cifrar_hint))
        col.addView(campo)
        col.addView(botonAncho(getString(R.string.x39_cifrar_guardar), sup2, claro) {
            val t = campo.text.toString().trim()
            if (t.isEmpty()) { toast(getString(R.string.x39_cifrar_pega)); return@botonAncho }
            if (aplicarIdentidad(c.bh, t, desdeQr = false)) {
                X39App.agenda.contacto(c.bh)?.let { confirmarHuellas(it) }
            }
        })
        col.addView(volver { pintar(marco()) })
        pintar(envolver(col))
    }

    /**
     * Foto de perfil. Por defecto no hay ninguna: se ven las iniciales. Ponerla es una
     * decisión del usuario, y solo viaja a los contactos verificados.
     */
    private fun bloqueFoto(): View {
        val mia = Fotos.mia()
        val fila = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = tarjeta()
            setPadding(dp(14), dp(14), dp(14), dp(14))
            layoutParams = anchoTotal(dp(4))
        }
        val redonda = FrameLayout(this).apply {
            background = pill(sup2, 32, sup2)
            clipToOutline = true
            layoutParams = LinearLayout.LayoutParams(dp(64), dp(64))
        }
        if (mia != null) {
            redonda.addView(ImageView(this).apply {
                setImageBitmap(mia); scaleType = ImageView.ScaleType.CENTER_CROP
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                )
            })
        } else {
            redonda.addView(TextView(this).apply {
                text = iniciales("x39"); setTextColor(claro); textSize = 20f
                setTypeface(typeface, Typeface.BOLD); gravity = Gravity.CENTER
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                )
            })
        }
        fila.addView(redonda)
        fila.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginStart = dp(14) }
            addView(TextView(this@X39CallActivity).apply {
                text = getString(if (mia != null) R.string.x39_foto_tienes else R.string.x39_foto_sin)
                textSize = 14f; setTextColor(claro); setTypeface(typeface, Typeface.BOLD)
            })
            addView(TextView(this@X39CallActivity).apply {
                text = if (mia != null) {
                    val n = Fotos.cuantosVerificados()
                    resources.getQuantityString(R.plurals.x39_foto_la_ven, n, n)
                } else {
                    getString(R.string.x39_foto_iniciales)
                }
                textSize = 11f; setTextColor(gris); setPadding(0, dp(3), 0, 0)
            })
        })
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        col.addView(fila)
        col.addView(botonAncho(getString(if (mia != null) R.string.x39_foto_cambiar else R.string.x39_foto_poner), sup2, claro) {
            elegirFoto.launch("image/*")
        }.apply { (layoutParams as LinearLayout.LayoutParams).topMargin = dp(8) })
        if (mia != null) {
            col.addView(botonAncho(getString(R.string.x39_foto_quitar), sup2, rojo) { quitarFoto() }
                .apply { (layoutParams as LinearLayout.LayoutParams).topMargin = dp(8) })
        }
        col.addView(TextView(this).apply {
            // Texto acordado, literal.
            text = getString(R.string.x39_foto_aviso)
            textSize = 11f; setTextColor(tenue); gravity = Gravity.CENTER
            setPadding(dp(8), dp(12), dp(8), 0)
        })
        col.addView(TextView(this).apply {
            text = getString(R.string.x39_foto_sin_datos)
            textSize = 11f; setTextColor(tenue); gravity = Gravity.CENTER
            setPadding(dp(8), dp(6), dp(8), 0)
        })
        return col
    }

    private fun confirmarReparto() {
        val n = Fotos.cuantosVerificados()
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.x39_foto_guardada))
            .setMessage(
                if (n == 0) {
                    getString(R.string.x39_foto_reparto_nadie)
                } else {
                    resources.getQuantityString(R.plurals.x39_foto_reparto, n, n)
                },
            )
            .setPositiveButton(getString(if (n == 0) R.string.x39_entendido else R.string.x39_enviar)) { _, _ ->
                if (n > 0) { Fotos.repartir(); toast(resources.getQuantityString(R.plurals.x39_foto_enviada, n, n)) }
                pantallaIdentidad()
            }
            .setNegativeButton(getString(R.string.x39_ahora_no)) { _, _ -> pantallaIdentidad() }
            .show()
    }

    private fun quitarFoto() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.x39_quitar_foto_titulo))
            .setMessage(getString(R.string.x39_quitar_foto_texto))
            .setPositiveButton(getString(R.string.x39_quitar)) { _, _ ->
                Fotos.quitarMia(); toast(getString(R.string.x39_foto_quitada)); pantallaIdentidad()
            }
            .setNegativeButton(getString(R.string.x39_cancelar), null)
            .show()
    }

    /** Guarda un contacto nuevo a partir de un texto de París leído por QR. */
    private fun guardarDesdeTexto(texto: String) {
        if (!texto.startsWith("X39ID1:")) {
            toast(getString(R.string.x39_qr_solo_cifrar))
            return
        }
        try {
            val par = org.x39.bolsillo.Mensajeria.identidadDeTexto(texto)
            val kem = par.copyOfRange(0, org.x39.bolsillo.Mensajeria.KEM_PK)
            val sig = par.copyOfRange(org.x39.bolsillo.Mensajeria.KEM_PK, par.size)
            if (esPropia(sig, kem)) { toast(getString(R.string.x39_qr_propio)); return }
            val campo = campo(getString(R.string.x39_nombre_persona_hint))
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.x39_identidad_leida, huellaCorta(org.x39.bolsillo.Identidad.huellaDe(sig))))
                .setView(FrameLayout(this).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(campo) })
                .setPositiveButton(getString(R.string.x39_guardar_contacto)) { _, _ ->
                    val n = campo.text.toString().trim()
                    if (n.isEmpty()) { toast(getString(R.string.x39_ponle_nombre)); return@setPositiveButton }
                    // Se guarda NO verificado y se pasa siempre a comparar las huellas de viva voz.
                    val bh = X39App.agenda.guardar(n, sig, kem)
                    toast(getString(R.string.x39_contacto_guardado_sin_verificar))
                    pestana = Pestana.CONTACTOS
                    X39App.agenda.contacto(bh)?.let { confirmarHuellas(it) } ?: pintar(marco())
                }
                .setNegativeButton(getString(R.string.x39_cancelar), null).show()
        } catch (e: Exception) {
            toast(getString(R.string.x39_identidad_no_valida))
        }
    }

    /**
     * true si la clave leída es la de este mismo móvil (la de firmar o la de cifrar).
     * Nadie se añade a sí mismo como contacto: se avisa y no se guarda nada.
     */
    private fun esPropia(sig: ByteArray?, kem: ByteArray? = null): Boolean {
        if (sig != null && sig.contentEquals(X39App.identidad.sigPk)) return true
        if (kem != null && kem.contentEquals(Boveda.clavesKem(this).first)) return true
        return false
    }

    /** Huella a la que aplicar la identidad que se lea a continuación. */
    private var contactoParaCifrar: String? = null

    /**
     * Guarda la clave de cifrar de un contacto a partir de un texto de París.
     * Acepta la identidad entera ("X39ID1:") o solo la clave de cifrar ("X39PK1:").
     * Si viene la identidad entera, la clave de firmar TIENE que ser la misma que ya
     * tenías: si no, alguien está intentando colarte otra persona con el mismo nombre.
     */
    private fun aplicarIdentidad(bh: String, texto: String, desdeQr: Boolean): Boolean {
        val c = X39App.agenda.contacto(bh) ?: return false
        val propia = getString(if (desdeQr) R.string.x39_qr_propio else R.string.x39_identidad_propia)
        try {
            when {
                texto.startsWith("X39ID1:") -> {
                    val par = org.x39.bolsillo.Mensajeria.identidadDeTexto(texto)
                    val kem = par.copyOfRange(0, org.x39.bolsillo.Mensajeria.KEM_PK)
                    val sig = par.copyOfRange(org.x39.bolsillo.Mensajeria.KEM_PK, par.size)
                    if (esPropia(sig, kem)) { toast(propia); return false }
                    if (!sig.contentEquals(c.sigPk)) {
                        toast(getString(R.string.x39_identidad_no_coincide, c.nombre))
                        return false
                    }
                    X39App.agenda.guardar(c.nombre, c.sigPk, kem)
                }
                texto.startsWith("X39PK1:") -> {
                    val kem = org.x39.bolsillo.Mensajeria.claveCifradoDeTexto(texto)
                    if (esPropia(null, kem)) { toast(propia); return false }
                    X39App.agenda.guardar(c.nombre, c.sigPk, kem)
                }
                else -> { toast(getString(R.string.x39_no_es_identidad)); return false }
            }
            return true
        } catch (e: Exception) {
            toast(getString(R.string.x39_identidad_no_valida))
            return false
        }
    }

    /**
     * Las DOS huellas en pantalla para leerlas en voz alta. Solo cuando la persona
     * confirma que coinciden, el contacto pasa a «✓ verificado». Lo decide ella.
     */
    private fun confirmarHuellas(c: org.x39.bolsillo.Agenda.Contacto) {
        val kem = c.kemPk
        if (kem == null) { toast(getString(R.string.x39_huellas_sin_clave)); return }
        val suya = org.x39.bolsillo.Mensajeria.huellaIdentidad(kem, c.sigPk)
        val (miKem, _) = Boveda.clavesKem(this)
        val mia = org.x39.bolsillo.Mensajeria.huellaIdentidad(miKem, X39App.identidad.sigPk)

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(24), dp(20), dp(20))
        }
        col.addView(tit(getString(R.string.x39_huellas_titulo)))
        col.addView(TextView(this).apply {
            text = getString(R.string.x39_huellas_texto)
            textSize = 13f; setTextColor(gris); setPadding(0, 0, 0, dp(4))
        })
        col.addView(tituloSeccion(getString(R.string.x39_huellas_la_tuya)))
        col.addView(huellaCaja(mia))
        col.addView(tituloSeccion(getString(R.string.x39_huellas_la_de, c.nombre.uppercase())))
        col.addView(huellaCaja(suya))
        col.addView(botonAncho(getString(R.string.x39_huellas_coinciden), rojo, Color.WHITE) {
            X39App.agenda.marcarVerificado(c.bh, true)
            // Acaba de entrar en el círculo de los verificados: si tengo foto, ahora le toca.
            Boveda.abrir(this, "foto_mia")?.let { Mensajes.enviarFoto(c.bh, it) }
            toast(getString(R.string.x39_huellas_verificado, c.nombre))
            trasComparar()
        })
        col.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(8)) })
        col.addView(botonAncho(getString(R.string.x39_huellas_no_coinciden), sup2, ambar) { huellasNoCoinciden(c) })
        col.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(8)) })
        col.addView(botonAncho(getString(R.string.x39_ahora_no), sup2, claro) {
            // Se empareja ahora y se comprueba luego (por teléfono): queda guardado, pero NO verificado.
            if (X39App.agenda.contacto(c.bh)?.verificado != true) toast(getString(R.string.x39_contacto_guardado_sin_verificar))
            trasComparar()
        })
        col.addView(TextView(this).apply {
            text = getString(R.string.x39_huellas_pie)
            textSize = 10f; setTextColor(tenue); gravity = Gravity.CENTER
            setPadding(dp(8), dp(16), dp(8), 0)
        })
        pintar(envolver(col))
    }

    /**
     * Las huellas no coinciden: o se ha leído a otra persona o hay alguien en medio. No se deja a medias:
     * aviso serio y se borra el contacto (y su foto). Si se vino desde un chat, ese chat se cierra.
     */
    private fun huellasNoCoinciden(c: org.x39.bolsillo.Agenda.Contacto) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.x39_huellas_no_coinciden_titulo))
            .setMessage(getString(R.string.x39_huellas_no_coinciden_texto, c.nombre))
            .setCancelable(false)
            .setPositiveButton(getString(R.string.x39_menu_borrar_contacto)) { _, _ ->
                X39App.agenda.borrar(c.bh); Fotos.borrarDe(c.bh)
                toast(getString(R.string.x39_contacto_borrado))
                if (soloComparar) {
                    soloComparar = false
                    startActivity(Intent(this, X39CallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
                    finish()
                } else {
                    pestana = Pestana.CONTACTOS; pintar(marco())
                }
            }
            .setNegativeButton(getString(R.string.x39_cancelar), null)
            .show()
    }

    private fun huellaCaja(h: String): View = TextView(this).apply {
        text = h.split(" ").chunked(4).joinToString("\n") { it.joinToString("  ") }
        textSize = 17f; setTextColor(claro); typeface = Typeface.MONOSPACE
        gravity = Gravity.CENTER
        background = pill(sup, 14, linea)
        setPadding(dp(12), dp(14), dp(12), dp(14))
        layoutParams = anchoTotal(dp(4))
    }

    private fun iniciarLlamada(bh: String, nombre: String, conVideo: Boolean) {
        X39App.nucleo.llamar(bh, conVideo)
        mostrarLlamando(nombre, conVideo)
    }

    /** Pantalla saliente mientras se acuerda la clave y suena al otro; Colgar cancela. */
    private fun mostrarLlamando(nombre: String, video: Boolean) {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setBackgroundColor(grafito); setPadding(dp(28), dp(80), dp(28), dp(40))
        }
        col.addView(TextView(this).apply { text = "▲"; textSize = 44f; setTextColor(rojo); gravity = Gravity.CENTER })
        col.addView(TextView(this).apply {
            text = getString(R.string.x39_llamando_a); textSize = 14f; setTextColor(gris); gravity = Gravity.CENTER; setPadding(0, dp(24), 0, dp(4))
        })
        col.addView(TextView(this).apply {
            text = nombre; textSize = 26f; setTextColor(claro); gravity = Gravity.CENTER; setTypeface(typeface, Typeface.BOLD)
        })
        col.addView(TextView(this).apply {
            text = getString(if (video) R.string.x39_videollamada_cifrada_e2e else R.string.x39_llamada_cifrada_e2e)
            textSize = 12f; setTextColor(gris); gravity = Gravity.CENTER; setPadding(0, dp(14), 0, dp(2))
        })
        col.addView(TextView(this).apply {
            text = getString(R.string.x39_estableciendo_clave); textSize = 11f; setTextColor(tenue); gravity = Gravity.CENTER
        })
        col.addView(Button(this).apply {
            text = getString(R.string.x39_colgar); setTextColor(Color.WHITE); setBackgroundColor(rojo)
            layoutParams = LinearLayout.LayoutParams(dp(160), dp(54)).apply { topMargin = dp(48) }
            setOnClickListener { X39App.nucleo.colgar(); pintar(marco()) }
        })
        pintar(col)
    }

    /** «Acerca de x39»: versión, firma del APK y huella del fichero instalado, medidas en el propio móvil. */
    private fun acercaDe() {
        val espera = AlertDialog.Builder(this).setMessage(getString(R.string.x39_acerca_midiendo)).setCancelable(false).show()
        Thread {
            val sb = StringBuilder()
            try {
                val pi = packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                sb.append(getString(R.string.x39_acerca_version, pi.versionName, pi.longVersionCode, packageName)).append("\n\n")
                val firmas = pi.signingInfo?.apkContentsSigners
                if (firmas != null && firmas.isNotEmpty()) {
                    val h = java.security.MessageDigest.getInstance("SHA-256").digest(firmas[0].toByteArray())
                    sb.append(getString(R.string.x39_acerca_certificado, huellaGrupos(h.joinToString("") { "%02x".format(it) }, 8))).append("\n\n")
                }
                val apk = java.io.File(applicationInfo.sourceDir)
                val md = java.security.MessageDigest.getInstance("SHA-256")
                apk.inputStream().use { i -> val b = ByteArray(65536); var n: Int; while (i.read(b).also { n = it } > 0) md.update(b, 0, n) }
                sb.append(getString(R.string.x39_acerca_apk, apk.length(), huellaGrupos(md.digest().joinToString("") { "%02x".format(it) }, 8))).append("\n\n")
                sb.append(getString(R.string.x39_acerca_motor))
            } catch (e: Exception) {
                sb.append(getString(R.string.x39_acerca_error, e.message))
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                espera.dismiss()
                AlertDialog.Builder(this).setTitle(getString(R.string.x39_ajustes_acerca)).setMessage(sb.toString()).setPositiveButton(getString(R.string.x39_entendido), null).show()
            }
        }.start()
    }

    /** Huella en grupos de 4, `porLinea` grupos por línea (0 = una sola línea). */
    private fun huellaGrupos(hex: String, grupos: Int): String {
        val g = hex.chunked(4)
        val usados = if (grupos > 0) g.take(grupos) else g
        return usados.chunked(4).joinToString("\n") { it.joinToString(" ") }
    }

    /**
     * El QR a pantalla completa, sobre blanco, con el brillo al máximo y la pantalla encendida mientras
     * se ve: al fotografiar la pantalla de otro móvil, el brillo y el tamaño deciden si se lee.
     * Al cerrarlo (tocar o Atrás) el brillo vuelve a lo que tuviera el sistema.
     */
    private fun qrPantallaCompleta(origen: ImageView) {
        val bmp = (origen.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap ?: return
        val dialogo = android.app.Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val img = ImageView(this).apply {
            // Sin filtrado: cada módulo con bordes nítidos al ampliarlo.
            setImageDrawable(android.graphics.drawable.BitmapDrawable(resources, bmp).apply { isFilterBitmap = false })
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.WHITE)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            contentDescription = origen.contentDescription
            setOnClickListener { dialogo.dismiss() }
        }
        dialogo.setContentView(img)
        dialogo.window?.let { w ->
            if (antiCaptura()) w.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            w.attributes = w.attributes.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL }
        }
        dialogo.show()
    }

    /** Bitmap nítido de un QR (margen de 4 módulos, escala entera hasta ~1080 px). */
    private fun bitmapQr(datos: ByteArray): android.graphics.Bitmap {
        val m = X39Core.qrMatrix(datos)
        val ancho = ((m[0].toInt() and 0xff) shl 8) or (m[1].toInt() and 0xff)
        val margen = 4
        val lado = ancho + 2 * margen
        val px = IntArray(lado * lado) { Color.WHITE }
        for (y in 0 until ancho) for (x in 0 until ancho) {
            if (m[2 + y * ancho + x].toInt() != 0) px[(y + margen) * lado + x + margen] = Color.BLACK
        }
        val base = android.graphics.Bitmap.createBitmap(px, lado, lado, android.graphics.Bitmap.Config.ARGB_8888)
        val escala = maxOf(1, 1080 / lado)
        return android.graphics.Bitmap.createScaledBitmap(base, lado * escala, lado * escala, false)
    }

    /**
     * Identidad COMPLETA (las dos claves, X39ID1) en 12 QR pequeños que se alternan a pantalla completa
     * con el brillo al máximo, 1,2 s cada una. Sin servidor: el escáner los junta en cualquier orden. Tocar para cerrar.
     */
    private fun qrPorPartes() {
        val (miKem, _) = Boveda.clavesKem(this)
        val texto = org.x39.bolsillo.Mensajeria.identidadTexto(miKem, X39App.identidad.sigPk)
        val partes = QrPartes.trocear(texto)
        val dialogo = android.app.Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val img = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER; setBackgroundColor(Color.WHITE)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            contentDescription = getString(R.string.x39_qr_por_partes)
        }
        val cuenta = TextView(this).apply {
            setTextColor(Color.BLACK); textSize = 14f; gravity = Gravity.CENTER; setBackgroundColor(Color.WHITE)
            setPadding(0, dp(8), 0, dp(16))
        }
        val raiz = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.WHITE)
            addView(img, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(cuenta)
            setOnClickListener { dialogo.dismiss() }
        }
        img.setOnClickListener { dialogo.dismiss() }
        dialogo.setContentView(raiz)
        dialogo.window?.let { w ->
            if (antiCaptura()) w.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            w.attributes = w.attributes.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL }
        }
        val mapas = arrayOfNulls<android.graphics.drawable.BitmapDrawable>(partes.size)
        val bucle = android.os.Handler(mainLooper)
        var i = 0
        val paso = object : Runnable {
            override fun run() {
                if (!dialogo.isShowing) return
                val d = mapas[i] ?: android.graphics.drawable.BitmapDrawable(resources,
                    bitmapQr(partes[i].toByteArray(Charsets.ISO_8859_1))).apply { isFilterBitmap = false }.also { mapas[i] = it }
                img.setImageDrawable(d)
                cuenta.text = getString(R.string.x39_qr_parte_de, i + 1, partes.size)
                i = (i + 1) % partes.size
                bucle.postDelayed(this, 1200)
            }
        }
        dialogo.setOnDismissListener { bucle.removeCallbacks(paso) }
        dialogo.show()
        paso.run()
    }

    private fun dibujarQr(destino: ImageView, datos: ByteArray) {
        Thread {
            try {
                val grande = bitmapQr(datos)
                runOnUiThread { if (!isDestroyed) destino.setImageBitmap(grande) }
            } catch (e: Exception) {
                runOnUiThread { destino.visibility = View.GONE }
            }
        }.start()
    }

    // ===== BusUi.Oyente ========================================================

    override fun enLinea(si: Boolean) { pintarEstado(si) }
    override fun sonando(bh: String, nombre: String, callHash: String, conVideo: Boolean) {
        // La entrante es SIEMPRE X39EntranteActivity: es la única que sale con el móvil
        // bloqueado, y así no hay dos pantallas de llamada compitiendo. Es singleInstance,
        // de modo que da igual si el servicio también la lanza.
        startActivity(X39EntranteActivity.intento(this, nombre, conVideo))
    }
    override fun lista(claveBase64: String, sas: String, token: String, room: String,
                       soyLlamante: Boolean, bhOtro: String, nombreOtro: String, conVideo: Boolean) {
        // La pantalla de llamada la abre el ServicioX39: es el único que está siempre,
        // así que da igual dónde estuviera el usuario al aceptar. Aquí solo se vuelve
        // a la lista.
        pintar(marco())
    }
    override fun colgado() { toast(getString(R.string.x39_llamada_finalizada)); pintar(marco()) }
    override fun fallo(motivo: String) { toast(BusUi.textoFallo(this, motivo)); pintar(marco()) }

    private fun pintarEstado(si: Boolean) {
        estadoTv?.apply {
            text = getString(if (si) R.string.x39_estado_en_linea else R.string.x39_estado_conectando)
            setTextColor(if (si) claro else gris)
        }
    }

    // ===== Helpers de interfaz =================================================

    private fun conPermisos(accion: () -> Unit) {
        val nec = arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)
        val faltan = nec.any { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (faltan) { accionTrasPermiso = accion; permisos.launch(nec) } else accion()
    }

    internal fun icono(res: Int, tint: Int, size: Int): ImageView = ImageView(this).apply {
        setImageResource(res)
        setColorFilter(tint, PorterDuff.Mode.SRC_IN)
        layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
    }

    private fun avatar(txt: String): View = avatar(txt, null, 40)

    /**
     * Avatar de 40dp: la foto del contacto si la tiene, y si no las iniciales sobre el
     * círculo gris. Por defecto NADIE tiene foto: ponerla es decisión de cada persona.
     */
    private fun avatar(txt: String, bh: String?, lado: Int): View {
        val s = dp(lado)
        val f = FrameLayout(this).apply {
            background = pill(sup2, lado / 2, sup2)
            clipToOutline = true
            layoutParams = LinearLayout.LayoutParams(s, s)
        }
        val foto = if (bh == null) null else Fotos.de(bh)
        if (foto != null) {
            f.addView(ImageView(this).apply {
                setImageBitmap(foto)
                scaleType = ImageView.ScaleType.CENTER_CROP
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                )
            })
        } else {
            f.addView(TextView(this).apply {
                text = txt; setTextColor(claro); textSize = lado * 0.35f
                setTypeface(typeface, Typeface.BOLD)
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                )
                gravity = Gravity.CENTER
            })
        }
        return f
    }

    private fun botonLlamar(onClick: () -> Unit): View {
        val f = FrameLayout(this).apply {
            background = pill(rojo, 19, rojo)
            val s = dp(38); layoutParams = LinearLayout.LayoutParams(s, s)
            setOnClickListener { onClick() }
        }
        f.addView(icono(R.drawable.x39_ic_llamar, Color.WHITE, 18).apply {
            layoutParams = FrameLayout.LayoutParams(dp(18), dp(18)).apply { gravity = Gravity.CENTER }
        })
        return f
    }

    internal fun botonAncho(texto: String, fondo: Int, textoColor: Int, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = texto; setTextColor(textoColor); gravity = Gravity.CENTER; textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            background = pill(fondo, 14, fondo)
            setPadding(0, dp(14), 0, dp(14))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setOnClickListener { onClick() }
        }

    /** Interruptor de la maqueta: pista clara con bola grafito a la derecha (encendido), pista gris con bola a la izquierda (apagado). */
    private fun toggle(activo: Boolean): View {
        val pistaColor = if (activo) rojo else sup2
        val pista = FrameLayout(this).apply {
            background = pill(pistaColor, 12, if (activo) pistaColor else linea)
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(23))
        }
        pista.addView(View(this).apply {
            background = pill(if (activo) grafito else gris, 9, if (activo) grafito else gris)
            layoutParams = FrameLayout.LayoutParams(dp(18), dp(18)).apply {
                gravity = (if (activo) Gravity.END else Gravity.START) or Gravity.CENTER_VERTICAL
                marginEnd = dp(3); marginStart = dp(3)
            }
        })
        return pista
    }

    private fun tarjeta(): GradientDrawable = pill(sup, 18, linea)

    internal fun tarjetaCol(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = tarjeta()
        setPadding(dp(14), dp(10), dp(14), dp(10))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(6) }
    }

    internal fun separador(): View = View(this).apply {
        setBackgroundColor(linea)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
    }

    private fun pill(fondo: Int, radio: Int, borde: Int): GradientDrawable = GradientDrawable().apply {
        setColor(fondo); cornerRadius = dp(radio).toFloat()
        if (borde != fondo) setStroke(1, borde)
    }

    internal fun cabeceraMatrix(): View = ImageView(this).apply {
        setImageResource(R.drawable.x39_matrix_logo)
        contentDescription = "X39 MATRIX · logo oficial"
        scaleType = ImageView.ScaleType.FIT_CENTER
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(220)).apply { bottomMargin = dp(12) }
    }

    internal fun columnaScroll(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(6), dp(16), dp(16))
    }

    internal fun envolver(v: View): View = ScrollView(this).apply {
        setBackgroundColor(grafito)
        layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        addView(v, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    internal fun tituloSeccion(t: String): TextView = TextView(this).apply {
        text = t; textSize = 11f; setTextColor(gris); setPaddingRelative(dp(2), dp(16), 0, dp(6))
    }

    private fun tit(t: String): TextView = TextView(this).apply {
        text = t; textSize = 21f; setTextColor(claro); setTypeface(typeface, Typeface.BOLD); setPadding(0, 0, 0, dp(10))
    }

    private fun campo(hint: String): EditText = EditText(this).apply {
        setHint(hint); setTextColor(claro); setHintTextColor(gris); background = pill(sup, 12, linea)
        setPadding(dp(12), dp(12), dp(12), dp(12))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun volver(onClick: () -> Unit): View = TextView(this).apply {
        text = getString(R.string.x39_volver); setTextColor(gris); gravity = Gravity.CENTER; textSize = 14f
        setPadding(0, dp(16), 0, dp(8)); setOnClickListener { onClick() }
        layoutParams = anchoTotal(dp(8))
    }

    private fun anchoTotal(top: Int) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = top }

    private fun copiar(etq: String, txt: String) =
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(etq, txt))

    private fun iniciales(nombre: String): String {
        val p = nombre.trim().split(" ").filter { it.isNotEmpty() }
        return when {
            p.isEmpty() -> "?"
            p.size == 1 -> p[0].take(2).uppercase()
            else -> (p[0].take(1) + p[1].take(1)).uppercase()
        }
    }

    private fun huellaCorta(bh: String): String =
        if (bh.length <= 16) bh else "${bh.substring(0, 8)}…${bh.substring(bh.length - 4)}"

    internal fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_LONG).show()

    internal fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
