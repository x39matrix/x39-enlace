/*
 * x39 · llamada entrante a pantalla completa.
 *
 * Es una Activity propia y no un trozo de la pantalla principal porque tiene que
 * poder salir CON EL MÓVIL BLOQUEADO: eso lo dan setShowWhenLocked(true) y
 * setTurnScreenOn(true), que solo valen para la ventana de una Activity.
 *
 * La lanza el ServicioX39, o directamente el sistema por el fullScreenIntent de la
 * notificación cuando la app está cerrada.
 */
package io.livekit.android.sample

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class X39EntranteActivity : AppCompatActivity(), BusUi.Oyente {

    private val grafito = Color.parseColor("#090A0D")
    private val sup2 = Color.parseColor("#12141A")
    private val rojo = Color.parseColor("#FF1E1E")
    private val claro = Color.parseColor("#FFFFFF")
    private val gris = Color.parseColor("#A0AAB8")
    private val tenue = Color.parseColor("#A0AAB8")

    private var nombre = ""   // se rellena en leerDatos()
    private var conVideo = false

    private fun antiCaptura(): Boolean =
        getSharedPreferences("x39", MODE_PRIVATE).getBoolean("anticaptura", true)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (antiCaptura()) {
            window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        }
        // Salir con la pantalla bloqueada y encenderla.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            (getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager)
                .requestDismissKeyguard(this, null)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD,
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        X39App.asegurar(this)
        leerDatos(intent)
        pintar(pantalla())
        aceptarSiVieneDeLaNotificacion(intent)
    }

    /** El botón «Contestar» de la notificación acepta en cuanto se abre la pantalla. */
    private fun aceptarSiVieneDeLaNotificacion(i: Intent) {
        if (!i.getBooleanExtra(EXTRA_ACEPTAR, false)) return
        i.removeExtra(EXTRA_ACEPTAR)
        if (BusUi.entrante == null) return
        X39App.nucleo.aceptar()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        leerDatos(intent)
        pintar(pantalla())
        aceptarSiVieneDeLaNotificacion(intent)
    }

    private fun leerDatos(i: Intent) {
        // Lo que manda es lo que sabe el núcleo; el intent es solo el aviso.
        val entrante = BusUi.entrante
        nombre = entrante?.nombre ?: i.getStringExtra(EXTRA_NOMBRE) ?: getString(R.string.x39_contacto_por_defecto)
        conVideo = entrante?.conVideo ?: i.getBooleanExtra(EXTRA_VIDEO, false)
    }

    override fun onResume() {
        super.onResume()
        BusUi.oyente = this
        // Si ya no hay nadie llamando (colgó, o se contestó en otro sitio), fuera.
        if (BusUi.entrante == null) finish()
    }

    override fun onPause() {
        super.onPause()
        if (BusUi.oyente === this) BusUi.oyente = null
    }

    // ===== BusUi.Oyente ========================================================

    override fun enLinea(si: Boolean) = Unit
    override fun sonando(bh: String, nombre: String, callHash: String, conVideo: Boolean) {
        this.nombre = nombre; this.conVideo = conVideo; pintar(pantalla())
    }

    /** Aceptada y con clave acordada: se abre la pantalla de llamada y esta se cierra. */
    override fun lista(
        claveBase64: String, sas: String, token: String, room: String,
        soyLlamante: Boolean, bhOtro: String, nombreOtro: String, conVideo: Boolean,
    ) {
        // Esta pantalla está visible, así que abrir desde aquí SIEMPRE está permitido;
        // desde el servicio, con la app en segundo plano, Android lo bloquea. La guarda
        // evita abrirla dos veces si el servicio se adelantó.
        if (!X39EnLlamadaActivity.viva) {
            X39EnLlamadaActivity.abrir(
                this, nombreOtro, sas, claveBase64, token, Config.urlLiveKit(this), conVideo,
            )
        }
        finish()
    }

    override fun colgado() { finish() }
    override fun fallo(motivo: String) {
        android.widget.Toast.makeText(this, BusUi.textoFallo(this, motivo), android.widget.Toast.LENGTH_LONG).show()
        finish()
    }

    // ===== Pantalla ============================================================

    private fun pantalla(): LinearLayout {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(grafito)
            setPadding(dp(28), dp(60), dp(28), dp(40))
        }
        col.addView(TextView(this).apply {
            text = "▲"; textSize = 40f; setTextColor(rojo); gravity = Gravity.CENTER
        })
        col.addView(TextView(this).apply {
            text = getString(if (conVideo) R.string.x39_entrante_video_de else R.string.x39_entrante_de)
            textSize = 14f; setTextColor(gris); gravity = Gravity.CENTER
            setPadding(0, dp(24), 0, dp(4))
        })
        col.addView(TextView(this).apply {
            text = nombre; textSize = 26f; setTextColor(claro); gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
        })
        col.addView(TextView(this).apply {
            text = getString(R.string.x39_cifrada_e2e)
            textSize = 11f; setTextColor(tenue); gravity = Gravity.CENTER
            setPadding(0, dp(14), 0, 0)
        })

        val fila = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            setPadding(0, dp(50), 0, 0)
        }
        fila.addView(Button(this).apply {
            text = getString(R.string.x39_rechazar); setTextColor(claro); background = pill(sup2)
            layoutParams = LinearLayout.LayoutParams(dp(140), dp(54))
            setOnClickListener { X39App.nucleo.rechazar(); finish() }
        })
        fila.addView(android.view.View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(12), 1)
        })
        fila.addView(Button(this).apply {
            text = getString(R.string.x39_contestar); setTextColor(Color.WHITE); background = pill(rojo)
            layoutParams = LinearLayout.LayoutParams(dp(140), dp(54))
            setOnClickListener {
                X39App.nucleo.aceptar()
                isEnabled = false
                text = getString(if (conVideo) R.string.x39_entrante_conectando_camara else R.string.x39_conectando)
            }
        })
        col.addView(fila)
        return col
    }

    /** Android 15 va borde a borde: se descuentan los huecos del sistema o el texto se mete bajo el reloj. */
    private fun pintar(vista: android.view.View) {
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
        setContentView(marco)
        ViewCompat.requestApplyInsets(marco)
    }

    private fun pill(color: Int): GradientDrawable = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(14).toFloat()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    @Deprecated("Una llamada entrante se contesta o se rechaza, no se esquiva con Atrás")
    override fun onBackPressed() {
        // Atrás no cuelga por accidente: se queda sonando.
    }

    companion object {
        const val EXTRA_NOMBRE = "x39_nombre"
        const val EXTRA_VIDEO = "x39_video"
        /** Viene del botón «Contestar» de la notificación: acepta sin tocar nada más. */
        const val EXTRA_ACEPTAR = "x39_aceptar_ya"

        fun intento(
            ctx: Context,
            nombre: String,
            conVideo: Boolean,
            aceptarYa: Boolean = false,
        ): Intent =
            Intent(ctx, X39EntranteActivity::class.java)
                .putExtra(EXTRA_NOMBRE, nombre)
                .putExtra(EXTRA_VIDEO, conVideo)
                .putExtra(EXTRA_ACEPTAR, aceptarYa)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }
}
