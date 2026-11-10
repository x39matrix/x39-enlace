package org.x39.bolsillo;

import org.x39.chat.TransporteWs;

import java.util.Base64;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Núcleo de las llamadas de x39, sin nada de Android: junta tu identidad, la
 * agenda, el transporte (WebSocket) y el cerebro ({@link Llamada}), y saca hacia
 * la interfaz UN solo evento cuando la llamada está lista para LiveKit —con la
 * clave de fotograma ya derivada— en vez de dos sueltos.
 *
 * Todo el trato con {@link Llamada} (que no es seguro entre hilos) ocurre en UN
 * único hilo, el "hilo-llamada": tanto lo que llega del socket como lo que pide la
 * interfaz se encolan ahí. Los avisos a la interfaz ({@link Eventos}) salen en ese
 * mismo hilo; la capa de Android los lleva al hilo principal antes de tocar vistas.
 *
 * Este núcleo NO habla con LiveKit: solo acuerda los 32 bytes y el token, y se los
 * entrega a la pantalla, que abre la sala cifrada. Los medios (voz, vídeo, datos)
 * van cifrados con esa clave; el servidor jamás la ve.
 */
public final class NucleoLlamadas implements TransporteWs.Escucha, Llamada.Escucha {

    /**
     * Motivo de fallo propio del núcleo. Es un código, no un texto: la pantalla lo
     * traduce al idioma de la app (BusUi.textoFallo). Los motivos que manda el
     * servidor sí llegan como texto y se enseñan tal cual.
     */
    public static final String MOTIVO_NO_EN_AGENDA = "x39:no_en_agenda";

    /** Avisos hacia la interfaz (llegan en el hilo-llamada). */
    public interface Eventos {
        void enLinea(boolean si);
        /** Entra una llamada. `conVideo` = el otro la pidió con cámara. */
        void sonando(String bhOtro, String nombre, String callHash, boolean conVideo);
        /** Llamada lista: abre LiveKit con esta clave (Base64 de los 32 bytes) y este token. */
        void listaParaLiveKit(String claveBase64, String sas, String token, String room,
                              boolean soyLlamante, String bhOtro, String nombreOtro, boolean conVideo);
        void colgado();
        void fallo(String motivo);
    }

    /**
     * Mensajes de la señalización que NO son de llamada (x39tunel: alta del par WireGuard y
     * PSK). Llegan en el hilo-llamada. Quien los atienda devuelve true; si nadie, se ignoran.
     */
    public interface EscuchaTunel {
        boolean mensajeTunel(String tipo, String json);
        /** Ya registrados en la señalización: momento de pedir alta y PSK del túnel. */
        void registrados(boolean servidorConTunel);
    }

    /**
     * Mensajes de texto (X39-MSG-v1). Van por la MISMA señalización que las llamadas,
     * pero no son asunto de {@link Llamada}: se apartan antes, igual que los del túnel.
     * El servidor solo los releva: no los guarda ni los puede leer.
     */
    /** Quién atiende los mensajes de texto. Es la escucha del chat común. */
    public interface EscuchaMensajes extends org.x39.chat.Protocolo.Escucha { }

    private final Identidad yo;
    private final Agenda agenda;
    private final TransporteWs transporte;
    private final Llamada llamada;
    private final Eventos eventos;
    private volatile EscuchaTunel escuchaTunel;
    private volatile EscuchaMensajes escuchaMensajes;
    private final ExecutorService hilo = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "x39-hilo-llamada");
        t.setDaemon(true);
        return t;
    });

    // buffer de la llamada en curso, tocado solo en el hilo-llamada
    private boolean soyLlamante;
    private String bhOtro;
    private String claveBase64;
    private String sas;
    private String token;
    private String room;
    /** Si esta llamada es de vídeo. Se fija al llamar (lo pide quien llama) o al sonar
     *  (viene en el ring). Es solo un aviso para la interfaz: no entra en la clave. */
    private boolean conVideo;

    public NucleoLlamadas(Identidad yo, Agenda agenda, String urlSenal, String pinSha256, Eventos eventos) {
        this.yo = yo;
        this.agenda = agenda;
        this.eventos = eventos;
        this.transporte = new TransporteWs(urlSenal, pinSha256, this);
        this.llamada = new Llamada(yo.seed, yo.sigPk, yo.bh, transporte::enviar, agenda, this);
    }

    /** Tu propia huella (tu "número"). */
    public String miHuella() { return yo.bh; }

    /** Tu clave de firma pública (para que otro te guarde). */
    public byte[] miClaveFirma() { return yo.sigPk; }

    // ---- API para la interfaz (se encola en el hilo-llamada) -------------------

    public void arrancar() { transporte.conectar(); }

    public void parar() { transporte.cerrar(); hilo.shutdownNow(); }

    /** Reconecta y vuelve a registrarse ya: cambio de red, o vigilante que vio el socket muerto. */
    public void reconectarYa() { transporte.reconectarYa(); }

    /**
     * Habla con la señalización por su dirección dentro del túnel (o vuelve a la normal
     * con null). Rehace la conexión si cambia.
     */
    public boolean senalPorIpInterna(String ip) { return transporte.ipInterna(ip); }

    /** Quién se entera de que la señalización no tira por la dirección del túnel. */
    public void avisoRutaSenal(TransporteWs.AvisoRuta a) { transporte.avisoRuta(a); }

    /** Llama a un contacto. `conVideo` viaja en el mensaje `call` y vuelve en el `ring` del otro. */
    public void llamar(String bhDestino, boolean conVideo) {
        hilo.execute(() -> {
            byte[] pk = agenda.claveFirma(bhDestino);
            if (pk == null) { eventos.fallo(MOTIVO_NO_EN_AGENDA); return; }
            reset();
            soyLlamante = true;
            bhOtro = bhDestino;
            this.conVideo = conVideo;
            llamada.llamar(bhDestino, pk, conVideo);
        });
    }

    /** ¿La llamada en curso es de vídeo? (para la pantalla de entrante y la de llamada). */
    public boolean conVideo() { return conVideo; }

    public void aceptar()  { hilo.execute(llamada::aceptar); }
    public void rechazar() { hilo.execute(llamada::rechazar); }
    public void colgar()   { hilo.execute(llamada::colgar); }

    /** Quién atiende los mensajes del túnel (alta del par, PSK). */
    public void escuchaTunel(EscuchaTunel e) { this.escuchaTunel = e; }

    /** Quién atiende los mensajes de texto. */
    public void escuchaMensajes(EscuchaMensajes e) { this.escuchaMensajes = e; }

    /** Manda un mensaje propio (JSON en una línea) por la señalización, si está abierta. */
    public void enviarCrudo(String json) { hilo.execute(() -> transporte.enviar(json)); }

    public boolean conectado() { return transporte.conectado(); }

    // ---- TransporteWs.Escucha (hilo de OkHttp → lo paso al hilo-llamada) --------

    /**
     * Socket abierto NO es estar en línea: todavía no nos conoce el servidor. Aquí solo
     * se manda el registro firmado. La línea se da por buena cuando llega `registered`,
     * y no antes: si no, se enviarían mensajes por una conexión sin registrar y el
     * servidor contestaría «regístrate primero».
     */
    @Override public void alAbrir() {
        hilo.execute(llamada::registrar);
    }

    @Override public void alRecibir(String json) {
        hilo.execute(() -> {
            // Los mensajes del túnel no son de llamada: se apartan antes de que Llamada los vea.
            String tipo = tipoDe(json);
            // El servidor nos reconoce: AHORA sí estamos en línea. De esto cuelga el
            // vaciado de la cola de mensajes, que antes salía con el socket recién
            // abierto y sin registro hecho.
            if ("registered".equals(tipo)) {
                eventos.enLinea(true);
            }
            EscuchaTunel et = escuchaTunel;
            if (et != null) {
                if ("registered".equals(tipo)) {
                    et.registrados(json.contains("\"tunel\": true") || json.contains("\"tunel\":true"));
                } else if (tipo.startsWith("psk") || tipo.startsWith("wg_")) {
                    if (et.mensajeTunel(tipo, json)) return;
                }
            }
            // Los mensajes de texto tampoco son de llamada: se apartan aquí.
            EscuchaMensajes em = escuchaMensajes;
            if (em != null && esDeMensajes(tipo)) {
                repartirMensaje(em, tipo, json);
                return;
            }
            llamada.recibir(json);
        });
    }

    private static boolean esDeMensajes(String tipo) {
        return org.x39.chat.Protocolo.esDelChat(tipo);
    }

    private void repartirMensaje(EscuchaMensajes em, String tipo, String json) {
        org.x39.chat.Protocolo.repartir(tipo, json, em);
    }

    /** Valor de "type" de un JSON de una línea, sin parsear entero (rápido y sin dependencias). */
    private static String tipoDe(String json) {
        int i = json.indexOf("\"type\"");
        if (i < 0) return "";
        int a = json.indexOf('"', json.indexOf(':', i) + 1);
        int b = json.indexOf('"', a + 1);
        return (a < 0 || b < 0) ? "" : json.substring(a + 1, b);
    }

    @Override public void alCerrar(String motivo) {
        hilo.execute(() -> eventos.enLinea(false));
    }

    // ---- Llamada.Escucha (ya vienen en el hilo-llamada) ------------------------

    @Override public void sonando(String deBh, String callHash, boolean conVideo) {
        soyLlamante = false;
        bhOtro = deBh;
        this.conVideo = conVideo;
        Agenda.Contacto c = agenda.contacto(deBh);
        eventos.sonando(deBh, c == null ? deBh : c.nombre, callHash, conVideo);
    }

    @Override public void claveLista(byte[] frameKey, String sasPalabras) {
        this.claveBase64 = Base64.getEncoder().withoutPadding().encodeToString(frameKey);
        this.sas = sasPalabras;
        intentarLanzar();
    }

    @Override public void token(String jwt, String sala) {
        this.token = jwt;
        this.room = sala;
        intentarLanzar();
    }

    @Override public void error(String motivo) { eventos.fallo(motivo); }

    @Override public void colgado() { reset(); eventos.colgado(); }

    // ---- interno ---------------------------------------------------------------

    /** Cuando tengo la clave Y el token, aviso una sola vez para abrir LiveKit. */
    private void intentarLanzar() {
        if (claveBase64 == null || token == null) return;
        Agenda.Contacto c = agenda.contacto(bhOtro);
        eventos.listaParaLiveKit(claveBase64, sas, token, room, soyLlamante, bhOtro,
                c == null ? bhOtro : c.nombre, conVideo);
    }

    private void reset() {
        soyLlamante = false; bhOtro = null; conVideo = false;
        claveBase64 = null; sas = null; token = null; room = null;
    }
}
