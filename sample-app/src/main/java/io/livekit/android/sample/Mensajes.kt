/*
 * x39 · mensajes cifrados (X39-MSG-v1).
 *
 * Una sala por contacto, aislada: lo que se escribe ahí va cerrado a SU clave de
 * cifrar y a ninguna otra. El sobre lo hace París (Mensajeria/X39Core); aquí solo
 * se guarda, se encola y se reparte.
 *
 * DOS RELOJES, que no son lo mismo:
 *   · Ventana de entrega (Mensajeria.VENTANA_ENTREGA_S): cuánto tiempo se acepta
 *     abrir una cápsula, contado desde que se selló. Si un mensaje se queda en la
 *     cola más que eso, NO se borra en silencio: se marca caducado y se ofrece
 *     reenviarlo, lo que hace una cápsula nueva con el mismo texto.
 *   · Borrado tras lectura: cuenta desde que el otro lo ABRE, no desde que se
 *     envía. Viaja dentro de la carga sellada, así que el servidor ni lo ve ni lo
 *     puede cambiar.
 *
 * En disco no hay nada en claro: el historial de cada contacto se sella con la
 * clave del Keystore, igual que la semilla.
 */
package io.livekit.android.sample

import android.content.Context
import androidx.annotation.StringRes
import java.security.SecureRandom
import org.json.JSONArray
import org.json.JSONObject
import org.x39.bolsillo.Mensajeria

object Mensajes {

    /** Cuánto tarda en borrarse un mensaje DESPUÉS de que el otro lo lea. */
    enum class Borrado(
        val segundos: Long,
        /** Texto para la cabecera de la conversación («Se borra 7 días después de leerlo»). */
        @StringRes val frase: Int,
        /** Texto de la opción en el selector («7 días después de leerlos»). */
        @StringRes val opcion: Int,
    ) {
        MIN5(300, R.string.x39_borrado_frase_min5, R.string.x39_borrado_opcion_min5),
        H24(24 * 3600, R.string.x39_borrado_frase_h24, R.string.x39_borrado_opcion_h24),
        D7(7 * 24 * 3600, R.string.x39_borrado_frase_d7, R.string.x39_borrado_opcion_d7),
        SIEMPRE(0, R.string.x39_borrado_frase_siempre, R.string.x39_borrado_opcion_siempre),
        ;

        companion object {
            fun de(segundos: Long): Borrado = entries.firstOrNull { it.segundos == segundos } ?: D7
            /** De fábrica: 7 días. Se cambia en Ajustes. */
            val DE_FABRICA = D7
        }
    }

    /**
     * Estados de un mensaje propio. La regla es una y no admite atajos: cada marca la
     * pone UN evento concreto, y ninguna se adelanta.
     *
     *   PENDIENTE (⏳) — está en la cola de ESTE móvil. O no ha salido (sin socket), o
     *                    el relé contestó "undelivered". Es el estado de partida y el
     *                    de vuelta ante cualquier duda.
     *   ENVIADO   (✓)  — el relé contestó "delivered": el sobre llegó AL SERVIDOR y lo
     *                    escupió por el socket del otro. NO dice que el otro lo tenga:
     *                    si su socket estaba muerto, el servidor contesta "delivered"
     *                    igual. Por eso un mensaje en ✓ SIGUE EN LA COLA y se reintenta.
     *   ENTREGADO (✓✓) — el aparato del destinatario mandó su propio acuse, cerrado y
     *                    firmado. Es la ÚNICA forma de llegar aquí. El "delivered" del
     *                    servidor nunca basta: el servidor no sabe si el otro lo recibió.
     *   CADUCADO  (⚠)  — se acabó la ventana de entrega estando en la cola.
     *   RECIBIDO       — mensaje del otro (no lleva marca de estado).
     */
    enum class Estado { PENDIENTE, ENVIADO, ENTREGADO, CADUCADO, RECIBIDO }

    /**
     * ¿Sigue esperando confirmación del aparato del otro? Un "delivered" del servidor
     * NO saca un mensaje de la cola: solo lo hace el acuse sellado del destinatario.
     */
    private fun enCola(m: Msg) = m.mio &&
        (m.estado == Estado.PENDIENTE || m.estado == Estado.ENVIADO) &&
        // Los ficheros NO entran en esta cola. En un adjunto, `texto` es el NOMBRE del
        // fichero, así que reintentarlo por aquí mandaba el nombre como si fuera un
        // mensaje escrito. Los ficheros se reenvían con su botón, trozo a trozo.
        m.adjunto.isEmpty()

    data class Msg(
        val id: String,
        val mio: Boolean,
        val texto: String,
        /** Cuándo se escribió (el mío) o cuándo llegó (el suyo), en segundos. */
        val ts: Long,
        var estado: Estado,
        /** Cuándo se selló la última cápsula: de ahí sale la caducidad de entrega. */
        var selladoEn: Long = 0,
        /** Reloj 2, en segundos. 0 = no se borra solo. */
        var borrarTras: Long = Borrado.DE_FABRICA.segundos,
        /** Cuándo se leyó (segundos). 0 = todavía no. Arranca el reloj 2. */
        var leidoEn: Long = 0,
        /** Si el mensaje es un fichero: su id en el almacén. Vacío = mensaje de texto. */
        val adjunto: String = "",
        val adjNombre: String = "",
        val adjBytes: Long = 0,
        val adjClase: String = "",
        val adjMime: String = "",
        /**
         * En un mensaje recibido, el id que le puso el emisor. Sirve para reconocer sus
         * reintentos y no pintar el mismo mensaje dos veces (cada reintento sella una
         * cápsula nueva, así que el cid no vale para esto).
         */
        val idEmisor: String = "",
    ) {
        /** Momento en que le toca desaparecer, o 0 si no aplica. */
        fun venceEn(): Long = if (borrarTras <= 0L || leidoEn <= 0L) 0 else leidoEn + borrarTras
    }

    /** Huella de la conversación que está delante ahora mismo; null si ninguna. */
    @Volatile
    var salaAbierta: String? = null

    private lateinit var ctx: Context
    private val rnd = SecureRandom()
    private val cache = HashMap<String, MutableList<Msg>>()
    private val cidsVistos = HashSet<String>()

    /** Quien quiera enterarse de que una conversación cambió (la sala abierta). */
    fun interface Oyente { fun conversacionCambio(bh: String) }
    var oyente: Oyente? = null

    @Synchronized
    fun init(c: Context) {
        if (!::ctx.isInitialized) ctx = c.applicationContext
    }

    // ===== Preferencias ========================================================

    /** Borrado por defecto para conversaciones nuevas (Ajustes). */
    fun borradoPorDefecto(): Borrado =
        Borrado.de(prefs().getLong("borrado_defecto", Borrado.DE_FABRICA.segundos))

    fun borradoPorDefecto(b: Borrado) {
        prefs().edit().putLong("borrado_defecto", b.segundos).apply()
    }

    /** Borrado elegido para ESTA conversación. */
    fun borradoDe(bh: String): Borrado =
        Borrado.de(prefs().getLong("borrado_$bh", borradoPorDefecto().segundos))

    fun borradoDe(bh: String, b: Borrado) {
        prefs().edit().putLong("borrado_$bh", b.segundos).apply()
    }

    private fun prefs() = ctx.getSharedPreferences("x39", Context.MODE_PRIVATE)

    // ===== Historial (cifrado en disco) ========================================

    @Synchronized
    fun conversacion(bh: String): List<Msg> {
        val lista = cargar(bh)
        podar(bh, lista)
        return lista.toList()
    }

    private fun cargar(bh: String): MutableList<Msg> {
        cache[bh]?.let { return it }
        val lista = mutableListOf<Msg>()
        Boveda.abrir(ctx, fichero(bh))?.let { claro ->
            try {
                val arr = JSONArray(String(claro, Charsets.UTF_8))
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    lista.add(
                        Msg(
                            id = o.getString("id"),
                            mio = o.getBoolean("mio"),
                            texto = o.getString("txt"),
                            ts = o.getLong("ts"),
                            estado = Estado.valueOf(o.getString("est")),
                            selladoEn = o.optLong("sell", 0),
                            borrarTras = o.optLong("bor", Borrado.DE_FABRICA.segundos),
                            leidoEn = o.optLong("leido", 0),
                            idEmisor = o.optString("ide", ""),
                            adjunto = o.optString("adj", ""),
                            adjNombre = o.optString("adjn", ""),
                            adjBytes = o.optLong("adjb", 0),
                            adjClase = o.optString("adjc", ""),
                            adjMime = o.optString("adjm", ""),
                        ),
                    )
                }
            } catch (e: Exception) {
                android.util.Log.e(TAG, "historial ilegible de $bh; se empieza vacío", e)
            }
        }
        cache[bh] = lista
        return lista
    }

    private fun guardar(bh: String, lista: List<Msg>) {
        val arr = JSONArray()
        for (m in lista) {
            arr.put(
                JSONObject()
                    .put("id", m.id).put("mio", m.mio).put("txt", m.texto).put("ts", m.ts)
                    .put("est", m.estado.name).put("sell", m.selladoEn)
                    .put("bor", m.borrarTras).put("leido", m.leidoEn)
                    .put("ide", m.idEmisor)
                    .put("adj", m.adjunto).put("adjn", m.adjNombre)
                    .put("adjb", m.adjBytes).put("adjc", m.adjClase).put("adjm", m.adjMime),
            )
        }
        // Sellado con la clave del Keystore: en disco no queda texto en claro.
        Boveda.sellar(ctx, fichero(bh), arr.toString().toByteArray(Charsets.UTF_8))
    }

    private fun fichero(bh: String) = "msg_$bh"

    /**
     * Quita los que ya han vencido y marca los que se quedaron sin ventana de entrega.
     * Los vencidos desaparecen de verdad: no se deja "mensaje eliminado" en su sitio.
     */
    private fun podar(bh: String, lista: MutableList<Msg>) {
        val ahora = System.currentTimeMillis() / 1000L
        var cambio = false
        val it = lista.iterator()
        while (it.hasNext()) {
            val m = it.next()
            val vence = m.venceEn()
            if (vence in 1..ahora) {
                // Al vencer se borra el FICHERO del almacén, no solo la burbuja.
                if (m.adjunto.isNotEmpty()) Adjuntos.borrar(m.adjunto)
                it.remove(); cambio = true; continue
            }
            if (enCola(m) && m.selladoEn > 0 && Mensajeria.caducado(m.selladoEn)) {
                m.estado = Estado.CADUCADO; cambio = true
            }
        }
        if (cambio) guardar(bh, lista)
    }

    /** Una fila de la lista de conversaciones. */
    data class Resumen(
        val bh: String,
        val nombre: String,
        /** Texto del último mensaje, sin prefijo: el «Tú: » lo pone la pantalla. */
        val ultimo: String,
        val ultimoMio: Boolean,
        val cuando: Long,
        val sinLeer: Int,
    )

    /** Conversaciones con historial, la más reciente arriba. */
    @Synchronized
    fun conversaciones(): List<Resumen> {
        if (!::ctx.isInitialized) return emptyList()
        val out = mutableListOf<Resumen>()
        for (c in X39App.agenda.todos()) {
            val lista = cargar(c.bh)
            podar(c.bh, lista)
            val ultimo = lista.lastOrNull() ?: continue
            out.add(
                Resumen(
                    bh = c.bh,
                    nombre = c.nombre,
                    ultimo = ultimo.texto,
                    ultimoMio = ultimo.mio,
                    cuando = ultimo.ts,
                    sinLeer = lista.count { !it.mio && it.leidoEn == 0L },
                ),
            )
        }
        return out.sortedByDescending { it.cuando }
    }

    // ===== Escribir y enviar ===================================================

    /** Pone un mensaje en la conversación y lo intenta mandar ya. */
    @Synchronized
    fun enviar(bh: String, texto: String): Msg {
        val lista = cargar(bh)
        val m = Msg(
            id = nuevoId(), mio = true, texto = texto,
            ts = System.currentTimeMillis() / 1000L,
            estado = Estado.PENDIENTE,
            borrarTras = borradoDe(bh).segundos,
        )
        lista.add(m)
        guardar(bh, lista)
        avisar(bh)
        intentar(bh, m)
        return m
    }

    /** Vuelve a mandar uno caducado: cápsula NUEVA con el mismo texto. */
    @Synchronized
    fun reenviar(bh: String, id: String) {
        val lista = cargar(bh)
        val m = lista.firstOrNull { it.id == id } ?: return
        m.estado = Estado.PENDIENTE
        m.selladoEn = 0
        guardar(bh, lista)
        avisar(bh)
        intentar(bh, m)
    }

    /**
     * Reintenta TODO lo que sigue en la cola: lo pendiente Y lo que el servidor dijo
     * haber entregado pero que el otro aparato aún no ha acusado. Un mensaje solo sale
     * de aquí cuando llega su acuse sellado, o cuando se acaba la ventana de entrega.
     *
     * Se llama al ponerse en línea y cada minuto desde el ServicioX39.
     */
    @Synchronized
    fun reintentarTodo() {
        if (!::ctx.isInitialized) return
        for (c in X39App.agenda.todos()) {
            val lista = cache[c.bh] ?: cargar(c.bh)
            val cola = lista.filter { enCola(it) }
            if (cola.isNotEmpty()) {
                android.util.Log.i(TAG, "cola de ${c.nombre}: ${cola.size} sin confirmar")
                for (m in cola) intentar(c.bh, m)
            }
            // Los ficheros no van por la cola de texto (ahí `texto` es su nombre), pero
            // sí se reintentan: se vuelven a trocear desde el almacén.
            for (m in lista.filter { it.mio && it.estado == Estado.PENDIENTE && it.adjunto.isNotEmpty() }) {
                val clase = runCatching { Adjuntos.Clase.valueOf(m.adjClase) }
                    .getOrDefault(Adjuntos.Clase.DOCUMENTO)
                Adjuntos.reenviar(c.bh, m.adjunto, m.adjNombre, m.adjMime, clase)
            }
        }
    }

    private fun intentar(bh: String, m: Msg) {
        // Cinturón: un adjunto jamás se manda por aquí (ver enCola).
        if (m.adjunto.isNotEmpty()) return
        // Sin socket abierto el sobre NI SIQUIERA sale: enviarCrudo lo tiraría en
        // silencio. Se queda en la cola, que es la verdad.
        if (!BusUi.enLinea || !X39App.nucleo.conectado()) {
            android.util.Log.i(TAG, "sin línea: el mensaje se queda en la cola del móvil")
            return
        }
        val kemPk = X39App.agenda.claveCifrado(bh)
        if (kemPk == null) {
            // Contacto v1: se puede llamar, pero no escribir. Se dice en la sala.
            return
        }
        try {
            val carga = JSONObject()
                .put("v", 1).put("k", "txt").put("t", m.texto)
                .put("b", m.borrarTras)   // el reloj 2 va DENTRO del sobre: el servidor no lo ve
                .toString().toByteArray(Charsets.UTF_8)
            val sobre = Mensajeria.cerrar(kemPk, X39App.identidad.seed, carga)
            m.selladoEn = System.currentTimeMillis() / 1000L
            // OJO: aquí NO se toca el estado. Sigue PENDIENTE hasta que el relé diga
            // "delivered" (-> ENVIADO) y hasta que el otro aparato acuse (-> ENTREGADO).
            X39App.nucleo.enviarCrudo(
                JSONObject()
                    .put("type", "msg").put("id", m.id).put("to", bh)
                    .put("ts", m.selladoEn)
                    // El relé copia "sobre" tal cual y no lo puede leer.
                    .put("sobre", sobre)
                    .toString(),
            )
            guardar(bh, cargar(bh))
            avisar(bh)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "no se pudo cerrar el sobre para $bh", e)
        }
    }

    /** Anota en la conversación un fichero que estoy mandando. */
    @Synchronized
    fun anotarAdjuntoPropio(bh: String, idAdj: String, nombre: String, bytes: Long, clase: Adjuntos.Clase) {
        val lista = cargar(bh)
        lista.add(
            Msg(
                id = nuevoId(), mio = true, texto = nombre,
                ts = System.currentTimeMillis() / 1000L,
                estado = Estado.PENDIENTE,
                borrarTras = borradoDe(bh).segundos,
                adjunto = idAdj, adjNombre = nombre, adjBytes = bytes, adjClase = clase.name,
            ),
        )
        guardar(bh, lista)
        avisar(bh)
    }

    /** Todos los trozos salieron. */
    @Synchronized
    fun adjuntoEnviado(bh: String, idAdj: String) {
        val lista = cargar(bh)
        lista.firstOrNull { it.adjunto == idAdj }?.estado = Estado.ENVIADO
        guardar(bh, lista)
        avisar(bh)
    }

    /**
     * El envío se cortó (sin línea o error). El mensaje se queda pendiente, pero hay
     * que avisar para que la burbuja deje de enseñar la barra de progreso.
     */
    @Synchronized
    fun adjuntoInterrumpido(bh: String, idAdj: String) {
        val lista = cargar(bh)
        lista.firstOrNull { it.adjunto == idAdj }?.estado = Estado.PENDIENTE
        guardar(bh, lista)
        avisar(bh)
    }

    /** Fichero completo recibido y ya guardado cifrado. */
    @Synchronized
    fun anotarAdjuntoRecibido(
        bh: String, idAdj: String, nombre: String, bytes: Long,
        clase: Adjuntos.Clase, mime: String,
    ) {
        val lista = cargar(bh)
        lista.add(
            Msg(
                id = nuevoId(), mio = false, texto = nombre,
                ts = System.currentTimeMillis() / 1000L,
                estado = Estado.RECIBIDO,
                borrarTras = borradoDe(bh).segundos,
                adjunto = idAdj, adjNombre = nombre, adjBytes = bytes,
                adjClase = clase.name, adjMime = mime,
            ),
        )
        guardar(bh, lista)
        avisar(bh)
        // Ya está el fichero entero y se pudo abrir: se lo digo con mi acuse sellado,
        // que es lo único que sube su burbuja a ✓✓.
        acusarRecibo(bh, idAdj)
        if (salaAbierta != bh) {
            val c = X39App.agenda.contacto(bh)
            if (c != null) (BusUi.fondo as? ServicioX39)?.mensajeDe(bh, c.nombre)
        }
    }

    // ===== Recibir =============================================================

    /** Llega un sobre. Si no cuadra la firma o ha caducado, se tira: nada a medias. */
    @Synchronized
    fun recibido(deBh: String, idDelEmisor: String, sobre: String) {
        val c = X39App.agenda.contacto(deBh)
        if (c == null) {
            android.util.Log.w(TAG, "sobre de una huella que no está en la agenda: se ignora")
            return
        }
        val (miKemPk, miKemSk) = Boveda.clavesKem(ctx)
        val abierto = try {
            Mensajeria.abrir(miKemSk, miKemPk, c.sigPk, sobre)
        } catch (e: Exception) {
            android.util.Log.e(
                TAG,
                "no se pudo abrir el sobre de ${c.nombre}. Si el motivo es el plazo, comprueba " +
                    "que los dos móviles tengan la misma hora: se rechaza todo lo que venga con " +
                    "más de ${Mensajeria.MARGEN_RELOJ_S} s de adelanto.",
                e,
            )
            return
        }
        val cid = abierto.cid.joinToString("") { "%02x".format(it) }
        if (!cidsVistos.add(cid)) return   // repetido: ya lo teníamos

        val carga = try { JSONObject(String(abierto.texto, Charsets.UTF_8)) } catch (e: Exception) { return }
        when (carga.optString("k", "txt")) {
            // Acuse del aparato del otro: es lo único que sube un mensaje mío a ✓✓.
            "recibido" -> acuseDelOtro(deBh, carga.optJSONArray("r"))
            // Foto de perfil: viene dentro del sobre, como todo lo demás. Si no trae
            // imagen es que la ha quitado, y aquí se borra su copia.
            // Un trozo de fichero. Adjuntos junta las piezas y avisa cuando están todas.
            "fich" -> Adjuntos.trozoRecibido(deBh, carga)
            "foto" -> {
                val b64 = carga.optString("d", "")
                val jpeg = if (b64.isEmpty()) null else {
                    try { android.util.Base64.decode(b64, android.util.Base64.NO_WRAP) }
                    catch (e: Exception) { null }
                }
                if (b64.isNotEmpty() && jpeg == null) {
                    android.util.Log.w(TAG, "foto ilegible de ${c.nombre}: se ignora")
                } else {
                    Fotos.recibida(deBh, jpeg)
                }
            }
            "leido" -> marcarLeidosPorElOtro(deBh, carga.optJSONArray("r"))
            else -> {
                val lista = cargar(deBh)
                // Si el emisor reintenta es porque no le llegó mi acuse. El mensaje ya lo
                // tengo: no lo pinto dos veces, pero SÍ le vuelvo a acusar, que es lo que
                // le falta para sacarlo de su cola.
                val repetido = idDelEmisor.isNotEmpty() &&
                    lista.any { !it.mio && it.idEmisor == idDelEmisor }
                if (repetido) {
                    android.util.Log.i(TAG, "reintento de un mensaje que ya tengo: repito el acuse")
                    acusarRecibo(deBh, idDelEmisor)
                    return
                }
                lista.add(
                    Msg(
                        id = nuevoId(), mio = false, texto = carga.optString("t", ""),
                        ts = System.currentTimeMillis() / 1000L,
                        estado = Estado.RECIBIDO,
                        borrarTras = carga.optLong("b", Borrado.DE_FABRICA.segundos),
                        idEmisor = idDelEmisor,
                    ),
                )
                guardar(deBh, lista)
                avisar(deBh)
                // Ya lo tengo y lo he podido abrir: se lo digo con mi propio acuse cerrado.
                // Hasta que le llegue esto, en su móvil sigue en ✓, no en ✓✓.
                if (idDelEmisor.isNotEmpty()) acusarRecibo(deBh, idDelEmisor)
                // Si la sala de ese contacto no está delante, se avisa: nombre, nunca texto.
                if (salaAbierta != deBh) {
                    (BusUi.fondo as? ServicioX39)?.mensajeDe(deBh, c.nombre)
                }
            }
        }
    }

    /**
     * Acuse DEL SERVIDOR. "delivered" solo significa que el relé lo pasó al socket del
     * otro: sube a ✓ enviado, nunca a ✓✓. "undelivered" lo devuelve a la cola.
     */
    @Synchronized
    fun acuse(id: String, entregado: Boolean) {
        // Los trozos de un fichero se identifican como "<idFichero>#<n>".
        if (id.contains('#')) {
            if (!entregado) Adjuntos.trozoNoEntregado(id.substringBefore('#'))
            return
        }
        for ((bh, lista) in cache) {
            val m = lista.firstOrNull { it.id == id } ?: continue
            // Si el aparato del otro ya acusó, no se baja de ✓✓ por un acuse del servidor.
            if (m.estado == Estado.ENTREGADO) return
            m.estado = if (entregado) Estado.ENVIADO else Estado.PENDIENTE
            guardar(bh, lista)
            avisar(bh)
            return
        }
    }

    /**
     * Acuse DEL APARATO del destinatario, cerrado y firmado por él. Esta es la única
     * puerta a ✓✓: prueba que el sobre llegó al móvil del otro y se pudo abrir.
     */
    @Synchronized
    private fun acuseDelOtro(bh: String, ids: JSONArray?) {
        if (ids == null) return
        val lista = cargar(bh)
        val set = (0 until ids.length()).map { ids.optString(it) }.toSet()
        var cambio = false
        for (m in lista) {
            // Un acuse puede venir por el id del mensaje o por el del fichero: los
            // trozos viajan con ids sueltos, así que el fichero se acusa por el suyo.
            val esMio = m.mio && (m.id in set || (m.adjunto.isNotEmpty() && m.adjunto in set))
            if (esMio && m.estado != Estado.ENTREGADO) {
                m.estado = Estado.ENTREGADO
                cambio = true
            }
        }
        if (cambio) { guardar(bh, lista); avisar(bh) }
    }

    // ===== Lectura y borrado ===================================================

    /**
     * El destinatario ha abierto la conversación y los ha visto: arranca el reloj 2 de
     * los suyos y se avisa al otro extremo para que las dos copias venzan a la vez.
     */
    @Synchronized
    fun marcarLeidos(bh: String) {
        val lista = cargar(bh)
        val ahora = System.currentTimeMillis() / 1000L
        val nuevos = lista.filter { !it.mio && it.leidoEn == 0L }
        if (nuevos.isEmpty()) return
        for (m in nuevos) m.leidoEn = ahora
        guardar(bh, lista)
        avisarLeidoAlOtro(bh, nuevos.map { it.id })
    }

    /**
     * Manda (o retira) la foto de perfil a UN contacto. Va cerrada a su clave como
     * cualquier otra cosa: el servidor solo ve que se mueve algo, no qué es.
     *
     * No se encola: si el otro no está en línea, no se insiste. Una foto de perfil no
     * merece ocupar la cola de los mensajes, y se reparte otra vez cuando haga falta.
     */
    @Synchronized
    fun enviarFoto(bh: String, jpeg: ByteArray?) {
        val kemPk = X39App.agenda.claveCifrado(bh) ?: return
        if (!BusUi.enLinea || !X39App.nucleo.conectado()) return
        try {
            val carga = JSONObject().put("v", 1).put("k", "foto")
            if (jpeg != null) {
                carga.put("d", android.util.Base64.encodeToString(jpeg, android.util.Base64.NO_WRAP))
            }
            val sobre = Mensajeria.cerrar(kemPk, X39App.identidad.seed, carga.toString().toByteArray(Charsets.UTF_8))
            X39App.nucleo.enviarCrudo(
                JSONObject().put("type", "msg").put("id", nuevoId()).put("to", bh)
                    .put("sobre", sobre).toString(),
            )
        } catch (e: Exception) {
            android.util.Log.e(TAG, "no se pudo mandar la foto", e)
        }
    }

    /** Acuse de recepción, cerrado y firmado: prueba que el sobre llegó a este aparato. */
    private fun acusarRecibo(bh: String, id: String) {
        enviarAviso(bh, "recibido", listOf(id))
    }

    /** Aviso de lectura: va cerrado igual que un mensaje, no como campo suelto. */
    private fun avisarLeidoAlOtro(bh: String, ids: List<String>) = enviarAviso(bh, "leido", ids)

    /** Manda un aviso (acuse de recibo o de lectura) cerrado a la clave del otro. */
    private fun enviarAviso(bh: String, clase: String, ids: List<String>) {
        val kemPk = X39App.agenda.claveCifrado(bh) ?: return
        if (!BusUi.enLinea || !X39App.nucleo.conectado()) return
        try {
            val arr = JSONArray().apply { ids.forEach { put(it) } }
            val carga = JSONObject().put("v", 1).put("k", clase).put("r", arr)
                .toString().toByteArray(Charsets.UTF_8)
            val sobre = Mensajeria.cerrar(kemPk, X39App.identidad.seed, carga)
            X39App.nucleo.enviarCrudo(
                JSONObject().put("type", "msg").put("id", nuevoId()).put("to", bh)
                    .put("sobre", sobre).toString(),
            )
        } catch (e: Exception) {
            android.util.Log.e(TAG, "no se pudo mandar el aviso «$clase»", e)
        }
    }

    /** El otro dice que ya los leyó: arranca el reloj 2 también en mi copia. */
    private fun marcarLeidosPorElOtro(bh: String, ids: JSONArray?) {
        if (ids == null) return
        val lista = cargar(bh)
        val ahora = System.currentTimeMillis() / 1000L
        val set = (0 until ids.length()).map { ids.optString(it) }.toSet()
        var cambio = false
        for (m in lista) {
            if (m.mio && m.id in set && m.leidoEn == 0L) {
                m.leidoEn = ahora
                // Si lo ha leído es que lo tiene: el aviso de lectura también va cerrado
                // y firmado por él, así que vale como acuse de entrega.
                m.estado = Estado.ENTREGADO
                cambio = true
            }
        }
        if (cambio) { guardar(bh, lista); avisar(bh) }
    }

    /** Borrado a mano de un mensaje (solo de este aparato). */
    @Synchronized
    fun borrar(bh: String, id: String) {
        val lista = cargar(bh)
        lista.firstOrNull { it.id == id }?.adjunto?.takeIf { it.isNotEmpty() }?.let { Adjuntos.borrar(it) }
        if (lista.removeAll { it.id == id }) { guardar(bh, lista); avisar(bh) }
    }

    // ===== Varios ==============================================================

    private fun avisar(bh: String) {
        val o = oyente ?: return
        android.os.Handler(android.os.Looper.getMainLooper()).post { o.conversacionCambio(bh) }
    }

    private fun nuevoId(): String {
        val b = ByteArray(12); rnd.nextBytes(b)
        return b.joinToString("") { "%02x".format(it) }
    }

    private const val TAG = "X39_MSG"
}
