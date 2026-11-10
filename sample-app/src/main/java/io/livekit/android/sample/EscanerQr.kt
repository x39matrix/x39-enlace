/*
 * Lector de QR de identidad de x39. Desde la 1.0.5 usa Camera2, la cámara del propio Android, en vez de
 * CameraX: el mismo lector que x39 Evidencia (EscanerQrActivity.java), para tener una sola forma de leer
 * en las dos apps. La decodificación es ZXing core (Java puro, Apache 2.0; ni red ni permisos propios).
 *
 * Qué hace, igual que en Evidencia:
 *   - análisis a 1920×1080 (o lo más cercano que admita la cámara) y zoom 2×;
 *   - enfoque y exposición en el centro, y al tocar la pantalla;
 *   - tope de exposición de 1/30 s, solo si la cámara admite ese rango de fotogramas;
 *   - tres intentos por fotograma: completo, reducido 2×2 (quita el moiré de fotografiar otra pantalla)
 *     y reducido con otro binarizador;
 *   - QR por partes (X39QP2): junta las 12 partes y PARA si se mezclan dos identidades;
 *   - avisos en pantalla: «aléjate» (según la distancia mínima real de enfoque) y «hace falta más luz».
 */
package io.livekit.android.sample

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.StreamConfigurationMap
import android.media.ImageReader
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.ChecksumException
import com.google.zxing.DecodeHintType
import com.google.zxing.FormatException
import com.google.zxing.LuminanceSource
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.Result
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class EscanerQr : AppCompatActivity() {
    companion object {
        const val RESULTADO_PK = "pk"
        /** Texto del QR cuando es una identidad de x39 ("X39ID1:…" o "X39PK1:…"). */
        const val RESULTADO_TEXTO = "texto"
        /** Presente si el escaneo se paró por seguridad: "mezcla" = partes de dos identidades o conjunto que no cuadra. */
        const val RESULTADO_ERROR = "error"
        private const val PK_LEN = 2592
        private const val TAG = "X39_QR"
        private const val REQ_CAMARA = 39
    }

    private lateinit var vista: TextureView
    private var avisoPantalla: TextView? = null
    private var hiloCamara: HandlerThread? = null
    private var manejador: Handler? = null
    private var camara: CameraDevice? = null
    private var sesion: CameraCaptureSession? = null
    private var lectorImg: ImageReader? = null
    private var peticion: CaptureRequest.Builder? = null
    private var tam: Size? = null
    private var sensor: Rect? = null
    private var regionesAf = 0
    private var regionesAe = 0
    private var orientacionSensor = 90
    private var modosAf = IntArray(0)

    private val analisis = Executors.newSingleThreadExecutor()
    private val ocupado = AtomicBoolean(false)
    @Volatile private var hecho = false

    private val lector = QRCodeReader()
    private val pistas = mapOf(
        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
        DecodeHintType.CHARACTER_SET to "ISO-8859-1",   // bytes crudos, sin adivinar codificación
        DecodeHintType.TRY_HARDER to true,
    )
    private val partes = QrPartes.Recolector()

    @Volatile private var focoDioptrias = 0f
    @Volatile private var iso = 0
    @Volatile private var exposicionNs = 0L
    private var focoMinimo = 0f
    private var ultimoAvisoPantalla = ""
    private var ultimaEvaluacion = 0L
    private var inicio = 0L
    private var fotogramas = 0
    private var localizadoSinLeer = 0
    private var sinPatron = 0
    private var ultimoResumen = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val raiz = FrameLayout(this).apply { setBackgroundColor(Color.parseColor("#090A0D")) }
        vista = TextureView(this)
        raiz.addView(vista, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        avisoPantalla = TextView(this).apply {
            text = getString(R.string.x39_escaner_apunta)
            setTextColor(Color.WHITE); textSize = 15f; gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#99090A0D")); setPadding(24, 40, 24, 40)
        }
        raiz.addView(avisoPantalla, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        raiz.addView(object : android.view.View(this) {
            private val trazo = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                style = android.graphics.Paint.Style.STROKE
                strokeWidth = 3f * resources.displayMetrics.density
            }
            override fun onDraw(canvas: android.graphics.Canvas) {
                super.onDraw(canvas)
                val lado = minOf(width, height) * 0.72f
                val izquierda = (width - lado) / 2f; val arriba = (height - lado) / 2f
                trazo.color = Color.parseColor("#FF1E1E")
                canvas.drawRoundRect(android.graphics.RectF(izquierda, arriba, izquierda + lado, arriba + lado), 16f, 16f, trazo)
                trazo.color = Color.parseColor("#FFB703")
                canvas.drawLine(width / 2f - 18f, height / 2f, width / 2f + 18f, height / 2f, trazo)
                canvas.drawLine(width / 2f, height / 2f - 18f, width / 2f, height / 2f + 18f, trazo)
            }
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(raiz)
        vista.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> ajustarVista() }
        vista.setOnTouchListener { v, ev ->
            if (ev.action == MotionEvent.ACTION_UP) { enfocar(ev.x / v.width, ev.y / v.height); v.performClick() }
            true
        }
    }

    override fun onResume() {
        super.onResume()
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), REQ_CAMARA)
            return
        }
        arrancar()
    }

    @Suppress("PARAMETER_NAME_CHANGED_ON_OVERRIDE")
    override fun onRequestPermissionsResult(req: Int, permisos: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(req, permisos, res)
        if (req == REQ_CAMARA && (res.isEmpty() || res[0] != PackageManager.PERMISSION_GRANTED)) {
            avisoPantalla?.text = getString(R.string.x39_escaner_sin_camara)
        }
    }

    override fun onPause() { cerrar(); super.onPause() }

    override fun onDestroy() { super.onDestroy(); analisis.shutdown() }

    // ---------------------------------------------------------------- cámara

    private fun arrancar() {
        if (camara != null) return
        hiloCamara = HandlerThread("x39-escaner").also { it.start(); manejador = Handler(it.looper) }
        if (vista.isAvailable) abrirCamara()
        else vista.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(s: SurfaceTexture, w: Int, h: Int) = abrirCamara()
            override fun onSurfaceTextureSizeChanged(s: SurfaceTexture, w: Int, h: Int) = ajustarVista()
            override fun onSurfaceTextureDestroyed(s: SurfaceTexture) = true
            override fun onSurfaceTextureUpdated(s: SurfaceTexture) {}
        }
    }

    private fun cerrar() {
        runCatching { sesion?.close() }; runCatching { camara?.close() }; runCatching { lectorImg?.close() }
        sesion = null; camara = null; lectorImg = null
        hiloCamara?.quitSafely(); hiloCamara = null
    }

    /** La trasera principal; si no hay trasera, la primera que haya. */
    private fun elegirCamara(cm: CameraManager): String? {
        val ids = cm.cameraIdList
        for (id in ids) {
            if (cm.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK) return id
        }
        return ids.firstOrNull()
    }

    /** Un tamaño que ESTA cámara anuncie: se prefiere 16:9 o 4:3 cerca de 1920×1080 y sin pasar de 1920 de ancho. */
    private fun elegirTamano(mapa: StreamConfigurationMap?): Size {
        val tams = mapa?.getOutputSizes(ImageFormat.YUV_420_888) ?: return Size(640, 480)
        if (tams.isEmpty()) return Size(640, 480)
        val objetivo = 1920L * 1080L
        return tams.minByOrNull { s ->
            val area = s.width.toLong() * s.height
            val habitual = s.width * 9 == s.height * 16 || s.width * 3 == s.height * 4
            Math.abs(area - objetivo) + (if (habitual) 0 else objetivo) + (if (s.width > 1920) 2 * objetivo else 0)
        }!!
    }

    private fun abrirCamara() {
        val cm = getSystemService(CAMERA_SERVICE) as CameraManager
        try {
            val id = elegirCamara(cm) ?: run { avisoPantalla?.text = getString(R.string.x39_escaner_sin_camara); return }
            val car = cm.getCameraCharacteristics(id)
            tam = elegirTamano(car.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP))
            sensor = car.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            regionesAf = car.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0
            regionesAe = car.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) ?: 0
            orientacionSensor = car.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
            modosAf = car.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: IntArray(0)
            focoMinimo = car.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
            Log.i(TAG, "cámara $id, análisis a ${tam!!.width}×${tam!!.height}, sensor a $orientacionSensor°, enfoque mínimo " +
                if (focoMinimo > 0f) "${Math.round(100f / focoMinimo)} cm" else "foco fijo")
            ajustarVista()
            lectorImg = ImageReader.newInstance(tam!!.width, tam!!.height, ImageFormat.YUV_420_888, 2).also {
                it.setOnImageAvailableListener({ r -> fotogramaNuevo(r) }, manejador)
            }
            if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
            cm.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(c: CameraDevice) { camara = c; crearSesion(car) }
                override fun onDisconnected(c: CameraDevice) { c.close(); camara = null }
                override fun onError(c: CameraDevice, e: Int) {
                    Log.w(TAG, "error de cámara $e"); c.close(); camara = null
                    runOnUiThread { avisoPantalla?.text = getString(R.string.x39_escaner_sin_camara) }
                }
            }, manejador)
        } catch (e: Exception) {
            Log.w(TAG, "no se pudo abrir la cámara: ${e.message}")
            avisoPantalla?.text = getString(R.string.x39_escaner_sin_camara)
        }
    }

    /**
     * Sin deformar en cualquier pantalla: TextureView estira la imagen hasta sus bordes y la pantalla suele ser
     * más alargada que la imagen. Se escala por igual en los dos ejes hasta llenar la vista y se recorta lo que
     * sobra por los lados, como la cámara del sistema.
     */
    private fun ajustarVista() {
        runOnUiThread {
            val t = tam ?: return@runOnUiThread
            val w = vista.width; val h = vista.height
            if (w == 0 || h == 0) return@runOnUiThread
            val girada = orientacionSensor == 90 || orientacionSensor == 270
            val iw = (if (girada) t.height else t.width).toFloat()
            val ih = (if (girada) t.width else t.height).toFloat()
            val esc = maxOf(w / iw, h / ih)
            vista.setTransform(Matrix().apply { setScale(iw * esc / w, ih * esc / h, w / 2f, h / 2f) })
        }
    }

    private fun crearSesion(car: CameraCharacteristics) {
        try {
            val t = tam ?: return
            val st = vista.surfaceTexture ?: return
            st.setDefaultBufferSize(t.width, t.height)
            val previa = Surface(st)
            val p = camara!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            peticion = p
            p.addTarget(previa)
            p.addTarget(lectorImg!!.surface)
            // Solo un modo de enfoque que la cámara anuncie: continuo, si no automático, si no nada (foco fijo).
            if (tieneAf(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)) p.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            else if (tieneAf(CaptureRequest.CONTROL_AF_MODE_AUTO)) p.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
            // Tope de exposición: solo un rango que ESTA cámara admita (pedir otro rompe la sesión en algunos móviles).
            val tope = car.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                ?.filter { it.lower >= 30 }?.minByOrNull { it.lower * 1000 + it.upper }
            if (tope != null) p.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, tope)
            Log.i(TAG, "tope de exposición: " + (tope?.let { "fotogramas $it → como mucho 1/${it.lower} s" } ?: "no admitido; sin tope"))
            aplicarZoom(car)
            aplicarRegion(0.5f, 0.5f)
            @Suppress("DEPRECATION")
            camara!!.createCaptureSession(listOf(previa, lectorImg!!.surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) { sesion = s; repetir() }
                override fun onConfigureFailed(s: CameraCaptureSession) {
                    Log.w(TAG, "no se pudo configurar la sesión de cámara")
                    runOnUiThread { avisoPantalla?.text = getString(R.string.x39_escaner_sin_camara) }
                }
            }, manejador)
        } catch (e: Exception) {
            Log.w(TAG, "no se pudo crear la sesión: ${e.message}")
        }
    }

    /** Zoom 2×: se aparta el móvil del QR (30-40 cm), enfoca mejor y hay menos moiré de la otra pantalla. */
    private fun aplicarZoom(car: CameraCharacteristics) {
        val p = peticion ?: return
        if (Build.VERSION.SDK_INT >= 30) {
            val r = car.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
            val z = minOf(2f, r?.upper ?: 1f)
            p.set(CaptureRequest.CONTROL_ZOOM_RATIO, z)
            Log.i(TAG, "zoom ${z}× (rango $r)")
        } else {
            val s = sensor ?: return
            val z = minOf(2f, car.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f)
            val w = Math.round(s.width() / z); val h = Math.round(s.height() / z)
            p.set(CaptureRequest.SCALER_CROP_REGION, Rect((s.width() - w) / 2, (s.height() - h) / 2, (s.width() + w) / 2, (s.height() + h) / 2))
            Log.i(TAG, "zoom ${z}× (recorte)")
        }
    }

    /** Enfoque y exposición en un punto de la imagen (0-1), si la cámara admite regiones. */
    private fun aplicarRegion(fx: Float, fy: Float) {
        val p = peticion ?: return
        val s = sensor ?: return
        // La vista está en vertical y el sensor en horizontal: x de la pantalla = y del sensor.
        val cx = Math.round(fy * s.width()); val cy = Math.round((1f - fx) * s.height())
        val m = minOf(s.width(), s.height()) / 8
        val reg = arrayOf(MeteringRectangle(maxOf(0, cx - m), maxOf(0, cy - m), 2 * m, 2 * m, MeteringRectangle.METERING_WEIGHT_MAX))
        if (regionesAf > 0) p.set(CaptureRequest.CONTROL_AF_REGIONS, reg)
        if (regionesAe > 0) p.set(CaptureRequest.CONTROL_AE_REGIONS, reg)
    }

    private fun tieneAf(modo: Int) = modosAf.any { it == modo }

    private fun enfocar(fx: Float, fy: Float) {
        val s = sesion ?: return
        val p = peticion ?: return
        // Cámaras de foco fijo: no hay nada que disparar.
        if (!(tieneAf(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE) || tieneAf(CaptureRequest.CONTROL_AF_MODE_AUTO))) return
        try {
            aplicarRegion(fx, fy)
            p.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
            s.capture(p.build(), null, manejador)
            p.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
            repetir()
            Log.i(TAG, "enfoque tocado en ${Math.round(fx * 100)}%,${Math.round(fy * 100)}%")
        } catch (e: Exception) {
            Log.w(TAG, "no se pudo enfocar: ${e.message}")
        }
    }

    private fun repetir() {
        try {
            sesion?.setRepeatingRequest(peticion!!.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(s: CameraCaptureSession, pe: CaptureRequest, r: TotalCaptureResult) {
                    r.get(CaptureResult.LENS_FOCUS_DISTANCE)?.let { focoDioptrias = it }
                    r.get(CaptureResult.SENSOR_SENSITIVITY)?.let { iso = it }
                    r.get(CaptureResult.SENSOR_EXPOSURE_TIME)?.let { exposicionNs = it }
                }
            }, manejador)
        } catch (e: Exception) {
            Log.w(TAG, "no se pudo iniciar la vista previa: ${e.message}")
        }
    }

    // ---------------------------------------------------------------- análisis

    /** Se copia el plano Y y se analiza en otro hilo; mientras está ocupado, los fotogramas nuevos se descartan. */
    private fun fotogramaNuevo(r: ImageReader) {
        val img = r.acquireLatestImage() ?: return
        try {
            if (hecho || !ocupado.compareAndSet(false, true)) return
            val plano = img.planes[0]
            val buf = plano.buffer
            val datos = ByteArray(buf.remaining()).also { buf.get(it) }
            val paso = plano.rowStride; val ancho = img.width; val alto = img.height
            analisis.execute { try { analizar(datos, paso, ancho, alto) } finally { ocupado.set(false) } }
        } finally {
            img.close()
        }
    }

    private fun analizar(datos: ByteArray, paso: Int, ancho: Int, alto: Int) {
        if (hecho) return
        if (inicio == 0L) { inicio = SystemClock.elapsedRealtime(); Log.i(TAG, "analizando a ${ancho}×$alto") }
        fotogramas++
        evaluarAvisos(brilloMedio(datos, paso, ancho, alto))
        try {
            var texto = decodificar(datos, paso, ancho, alto).text
            if (texto.startsWith(QrPartes.PREFIJO)) {
                val completo = try {
                    partes.anadir(texto)
                } catch (e: IllegalArgumentException) {
                    Log.w(TAG, "parte ${QrPartes.PREFIJO} mal formada, se ignora: ${e.message}"); return
                } catch (e: QrPartes.Mezcla) {
                    hecho = true
                    Log.w(TAG, "PARADA: partes de dos identidades distintas o conjunto que no cuadra")
                    runOnUiThread { setResult(RESULT_OK, Intent().putExtra(RESULTADO_ERROR, "mezcla")); finish() }
                    return
                }
                Log.i(TAG, "partes leídas: ${partes.leidas} de ${partes.totales}")
                ultimaEvaluacion = 0L
                if (completo == null) return
                Log.i(TAG, "QR por partes completo: ${partes.totales} partes")
                texto = completo
            }
            val pk = texto.toByteArray(Charsets.ISO_8859_1)
            // Dos formatos: el texto de París (identidad v2 o clave de cifrar) y, por compatibilidad,
            // la clave de firma cruda de los QR antiguos.
            val esTexto = texto.startsWith("X39ID1:") || texto.startsWith("X39PK1:")
            if (!esTexto && pk.size != PK_LEN) {
                Log.w(TAG, "QR leído pero no es de x39: ${pk.size} bytes, empieza por «${texto.take(8)}»")
                return
            }
            hecho = true
            Log.i(TAG, "leído en ${SystemClock.elapsedRealtime() - inicio} ms, $fotogramas fotogramas, ${ancho}×$alto, ${pk.size} bytes")
            runOnUiThread {
                val datosSalida = Intent()
                if (esTexto) datosSalida.putExtra(RESULTADO_TEXTO, texto) else datosSalida.putExtra(RESULTADO_PK, pk)
                setResult(RESULT_OK, datosSalida)
                finish()
            }
        } catch (e: NotFoundException) {
            sinPatron++
            val ahora = SystemClock.elapsedRealtime()
            if (ahora - ultimoResumen > 3000) {
                ultimoResumen = ahora
                Log.i(TAG, "no localiza el patrón: $sinPatron fotogramas sin QR, $localizadoSinLeer localizados sin leer, ${ahora - inicio} ms")
            }
        } catch (e: ChecksumException) {
            localizadoSinLeer++
        } catch (e: FormatException) {
            localizadoSinLeer++
        } catch (e: Exception) {
            Log.w(TAG, "fallo al analizar el fotograma: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /** Luminancia reducida 2×2 (filtro paso bajo): quita el moiré de fotografiar la rejilla de otra pantalla. */
    private class Reducida(datos: ByteArray, paso: Int, ancho: Int, alto: Int) : LuminanceSource(ancho / 2, alto / 2) {
        private val px = ByteArray((ancho / 2) * (alto / 2)).also { o ->
            val w = ancho / 2
            for (y in 0 until alto / 2) {
                val f0 = 2 * y * paso; val f1 = f0 + paso
                for (x in 0 until w) {
                    val c = 2 * x
                    val suma = (datos[f0 + c].toInt() and 0xff) + (datos[f0 + c + 1].toInt() and 0xff) +
                        (datos[f1 + c].toInt() and 0xff) + (datos[f1 + c + 1].toInt() and 0xff)
                    o[y * w + x] = (suma shr 2).toByte()
                }
            }
        }
        override fun getRow(y: Int, row: ByteArray?): ByteArray {
            val r = if (row != null && row.size >= width) row else ByteArray(width)
            System.arraycopy(px, y * width, r, 0, width); return r
        }
        override fun getMatrix(): ByteArray = px
    }

    /** Tres intentos por fotograma; si ninguno lee, se lanza el fallo más informativo, para el log. */
    private fun decodificar(datos: ByteArray, paso: Int, ancho: Int, alto: Int): Result {
        var peor: ReaderException = NotFoundException.getNotFoundInstance()
        fun probar(nombre: String, b: BinaryBitmap): Result? = try {
            lector.decode(b, pistas).also { if (nombre != "completo") Log.i(TAG, "leído con el intento «$nombre»") }
        } catch (e: ReaderException) {
            if (e !is NotFoundException) peor = e
            null
        } finally { lector.reset() }
        val completa = PlanarYUVLuminanceSource(datos, paso, alto, 0, 0, minOf(paso, ancho), alto, false)
        probar("completo", BinaryBitmap(HybridBinarizer(completa)))?.let { return it }
        // Solo se reduce si la última fila completa cabe en el búfer (algunos móviles recortan la última).
        if (datos.size >= (alto - 1) * paso + ancho) {
            val red = Reducida(datos, paso, ancho, alto)
            probar("reducido 2×2", BinaryBitmap(HybridBinarizer(red)))?.let { return it }
            probar("reducido 2×2, histograma", BinaryBitmap(GlobalHistogramBinarizer(red)))?.let { return it }
        }
        throw peor
    }

    /** Brillo medio del fotograma (0-255), muestreando uno de cada 16 píxeles. */
    private fun brilloMedio(datos: ByteArray, paso: Int, ancho: Int, alto: Int): Int {
        var suma = 0L; var n = 0
        var y = 0
        while (y < alto) {
            var x = 0; val f = y * paso
            while (x < ancho) { if (f + x < datos.size) { suma += datos[f + x].toInt() and 0xff; n++ }; x += 16 }
            y += 16
        }
        return if (n == 0) 0 else (suma / n).toInt()
    }

    /** Aviso en pantalla cada medio segundo: demasiado cerca > poca luz > progreso de partes > «apunta». */
    private fun evaluarAvisos(brillo: Int) {
        val ahora = SystemClock.elapsedRealtime()
        if (ahora - ultimaEvaluacion < 500) return
        ultimaEvaluacion = ahora
        val cerca = focoMinimo > 0f && focoDioptrias >= focoMinimo * 0.97f
        val oscuro = brillo < 60 || (iso >= 1600 && exposicionNs >= 30_000_000L)
        val texto = when {
            cerca -> getString(R.string.x39_escaner_alejate, Math.round(100f / focoMinimo))
            oscuro -> getString(R.string.x39_escaner_mas_luz)
            partes.leidas > 0 -> getString(R.string.x39_escaner_partes, partes.leidas, partes.totales)
            else -> getString(R.string.x39_escaner_apunta)
        }
        if (texto == ultimoAvisoPantalla) return
        ultimoAvisoPantalla = texto
        Log.i(TAG, "aviso: $texto (foco ${"%.1f".format(focoDioptrias)} dp, mínimo ${"%.1f".format(focoMinimo)} dp, " +
            "brillo $brillo, ISO $iso, exposición ${exposicionNs / 1_000_000} ms)")
        runOnUiThread { avisoPantalla?.text = texto }
    }
}
