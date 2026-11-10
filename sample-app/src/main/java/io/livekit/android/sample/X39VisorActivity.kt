/*
 * x39 · visor de fotos.
 *
 * La foto se ve DENTRO de la app: se descifra del almacén y se pinta aquí. No se abre
 * ninguna aplicación de fuera, ni se le pasa el fichero a nadie. La única salida es
 * «Guardar en galería», y la decide el usuario.
 *
 * Anti-captura igual que el resto de pantallas.
 */
package io.livekit.android.sample

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.PointF
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlin.math.max
import kotlin.math.min

class X39VisorActivity : AppCompatActivity() {

    private val claro = Color.parseColor("#FFFFFF")
    private val tenue = Color.parseColor("#A0AAB8")

    private lateinit var idAdj: String
    private var nombre = "foto"
    private var mime = "image/jpeg"

    private lateinit var vista: ImageView
    private val matriz = Matrix()
    private var escala = 1f
    private val arrastre = PointF()
    private var ultimo = PointF()

    private fun antiCaptura(): Boolean =
        getSharedPreferences("x39", MODE_PRIVATE).getBoolean("anticaptura", true)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (antiCaptura()) {
            window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        }
        Adjuntos.init(this)

        idAdj = intent.getStringExtra(EXTRA_ID) ?: run { finish(); return }
        nombre = intent.getStringExtra(EXTRA_NOMBRE) ?: "foto"
        mime = intent.getStringExtra(EXTRA_MIME) ?: "image/jpeg"

        val bmp = Adjuntos.imagen(idAdj)
        if (bmp == null) {
            Toast.makeText(this, getString(R.string.x39_visor_ya_no_esta), Toast.LENGTH_LONG).show()
            finish(); return
        }

        val raiz = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        vista = ImageView(this).apply {
            setImageBitmap(bmp)
            scaleType = ImageView.ScaleType.MATRIX
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
        raiz.addView(vista)
        raiz.addView(barraSuperior())

        vista.post { encajar(bmp.width, bmp.height) }
        zoomPorPellizco()

        // El fondo negro llega hasta los bordes; los controles respetan los huecos.
        ViewCompat.setOnApplyWindowInsetsListener(raiz) { _, insets ->
            val b = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            (raiz.getChildAt(1) as LinearLayout).setPadding(b.left, b.top, b.right, 0)
            insets
        }
        setContentView(raiz)
        ViewCompat.requestApplyInsets(raiz)
    }

    private fun barraSuperior(): View {
        val barra = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#99000000"))
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        barra.addView(TextView(this).apply {
            text = "‹"; textSize = 26f; setTextColor(claro)
            setPadding(dp(16), dp(10), dp(16), dp(10))
            isClickable = true
            contentDescription = getString(R.string.x39_cerrar)
            setOnClickListener { finish() }
        })
        barra.addView(TextView(this).apply {
            text = nombre; textSize = 14f; setTextColor(claro)
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        barra.addView(TextView(this).apply {
            text = "⋮"; textSize = 22f; setTextColor(claro)
            setPaddingRelative(dp(16), dp(10), dp(18), dp(10))
            isClickable = true
            contentDescription = getString(R.string.x39_visor_mas_opciones)
            setOnClickListener { menu() }
        })
        return barra
    }

    /** Una sola salida: la galería. La foto no se pasa a ninguna otra aplicación. */
    private fun menu() {
        AlertDialog.Builder(this)
            .setItems(arrayOf(getString(R.string.x39_visor_guardar_galeria))) { _, _ -> confirmarGuardar() }
            .show()
    }

    private fun confirmarGuardar() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.x39_visor_guardar_titulo))
            .setMessage(getString(R.string.x39_visor_guardar_texto))
            .setPositiveButton(getString(R.string.x39_guardar)) { _, _ ->
                val fallo = Adjuntos.guardarEnGaleria(idAdj, nombre, mime)
                Toast.makeText(
                    this,
                    fallo?.let { getString(R.string.x39_visor_no_guardar, it) } ?: getString(R.string.x39_visor_guardada),
                    Toast.LENGTH_LONG,
                ).show()
            }
            .setNegativeButton(getString(R.string.x39_cancelar), null)
            .show()
    }

    // ===== Zoom y arrastre =====================================================

    /** Encaja la foto entera al abrir, sin recortarla. */
    private fun encajar(ancho: Int, alto: Int) {
        if (ancho == 0 || alto == 0) return
        val f = min(vista.width.toFloat() / ancho, vista.height.toFloat() / alto)
        escala = f
        matriz.setScale(f, f)
        matriz.postTranslate(
            (vista.width - ancho * f) / 2f,
            (vista.height - alto * f) / 2f,
        )
        vista.imageMatrix = matriz
    }

    private fun zoomPorPellizco() {
        val pellizco = ScaleGestureDetector(
            this,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(d: ScaleGestureDetector): Boolean {
                    val antes = escala
                    escala = max(0.3f, min(escala * d.scaleFactor, 8f))
                    val f = escala / antes
                    matriz.postScale(f, f, d.focusX, d.focusY)
                    vista.imageMatrix = matriz
                    return true
                }
            },
        )
        vista.setOnTouchListener { v, ev ->
            pellizco.onTouchEvent(ev)
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    ultimo.set(ev.x, ev.y); arrastre.set(ultimo)
                }
                MotionEvent.ACTION_MOVE -> if (ev.pointerCount == 1 && !pellizco.isInProgress) {
                    matriz.postTranslate(ev.x - ultimo.x, ev.y - ultimo.y)
                    ultimo.set(ev.x, ev.y)
                    vista.imageMatrix = matriz
                }
                MotionEvent.ACTION_UP -> {
                    // Un toque limpio cierra el visor.
                    if (kotlin.math.abs(ev.x - arrastre.x) < 12 &&
                        kotlin.math.abs(ev.y - arrastre.y) < 12
                    ) {
                        v.performClick()
                    }
                }
            }
            true
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_ID = "x39_adj"
        const val EXTRA_NOMBRE = "x39_nombre"
        const val EXTRA_MIME = "x39_mime"

        fun abrir(ctx: Context, idAdj: String, nombre: String, mime: String) {
            ctx.startActivity(
                Intent(ctx, X39VisorActivity::class.java)
                    .putExtra(EXTRA_ID, idAdj)
                    .putExtra(EXTRA_NOMBRE, nombre)
                    .putExtra(EXTRA_MIME, mime),
            )
        }
    }
}
