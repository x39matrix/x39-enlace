package org.x39.bolsillo;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import org.json.JSONObject;

/**
 * Cerebro de una llamada x39 (1:1). Orquesta, sobre la señalización soberana:
 *   registrar identidad → llamar/sonar → intercambio de ofertas firmadas y
 *   criptogramas ML-KEM → acuerdo de X39-CALL-KEY → 32 bytes para LiveKit.
 *
 * No toca la red ni LiveKit directamente: recibe/emite líneas JSON por un
 * {@link Transporte} (un WebSocket, arriba) y avisa por una {@link Escucha}.
 * La criptografía la hace el motor nativo ({@link X39Core}); las claves privadas
 * (semilla, dk efímera, secretos) no salen del aparato.
 */
public final class Llamada {

    /** Motivos propios (códigos, no textos): los traduce la pantalla con BusUi.textoFallo. */
    public static final String MOTIVO_NO_DISPONIBLE = "x39:no_disponible";
    public static final String MOTIVO_ERROR_SERVIDOR = "x39:error_servidor";
    public static final String MOTIVO_VERSION = "x39:version_incompatible";

    /** Versión del protocolo de llamada (1.0.6). El servidor y los dos extremos la exigen;
     *  un mensaje con otra versión se rechaza, sin degradar al intercambio antiguo. */
    public static final int PROTO = 2;

    /** Envía una línea JSON al servidor de señalización. */
    public interface Transporte {
        void enviar(String json);
    }

    /** Da la clave de firma (ML-DSA-87) de un contacto por su huella; null si no lo conozco. */
    public interface Contactos {
        byte[] claveFirma(String bh);
    }

    /** Avisos hacia la interfaz. */
    public interface Escucha {
        /** Entra una llamada. `conVideo` viene del campo "media" del ring. */
        void sonando(String deBh, String callHash, boolean conVideo);
        void claveLista(byte[] frameKey, String sas);        // 32 bytes E2EE + 6 palabras
        void token(String jwt, String room);                 // unirse a LiveKit
        void error(String motivo);
        void colgado();
    }

    private static final int PK_LEN = 1568, SK_LEN = 3168, SIG_LEN = 4627;
    private static final SecureRandom RNG = new SecureRandom();

    private final byte[] miSeed;   // ML-DSA-87 (secreto)
    private final byte[] miSigPk;  // ML-DSA-87 pública
    private final String miBh;     // huella = sha256(miSigPk) en hex
    private final Transporte tx;
    private final Contactos contactos;
    private final Escucha esc;

    // Estado de la llamada en curso.
    private boolean soyLlamante;
    private String callHash;
    private byte[] callSalt;
    private String bhOtro;
    private byte[] sigPkOtro;
    private byte[] miEk, miDk;   // par efímero (dk secreto)
    private byte[] ekOtro;
    private String tsOtro;
    private byte[] sigOtro;
    private byte[] ssMio, ssOtro;

    public Llamada(byte[] seed, byte[] sigPk, String bh, Transporte tx, Contactos c, Escucha e) {
        this.miSeed = seed;
        this.miSigPk = sigPk;
        this.miBh = bh;
        this.tx = tx;
        this.contactos = c;
        this.esc = e;
    }

    // ---- API que usa la interfaz ------------------------------------------------

    /** Regístrate en el servidor (firma un reto con tu identidad). */
    public void registrar() {
        try {
            tx.enviar(org.x39.chat.Protocolo.registro(MotorX39.UNICO, miSeed, miSigPk, miBh));
        } catch (Exception e) {
            esc.error(msg(e));
        }
    }

    /** Llama a un contacto por su huella. `sigPkDestino` es su clave de firma (de tu agenda). */
    public void llamar(String bhDestino, byte[] sigPkDestino, boolean conVideo) {
        try {
            reset();
            soyLlamante = true;
            bhOtro = bhDestino;
            sigPkOtro = sigPkDestino;
            callHash = hex(rand(32));
            callSalt = rand(32);
            String ts = ahora();
            byte[] o = X39Core.callOffer(callHash, miBh, bhDestino, 0, callSalt, miSeed, ts);
            miEk = corte(o, 0, PK_LEN);
            miDk = corte(o, PK_LEN, PK_LEN + SK_LEN);
            byte[] sig = corte(o, PK_LEN + SK_LEN, PK_LEN + SK_LEN + SIG_LEN);
            JSONObject m = new JSONObject().put("type", "call").put("v", PROTO).put("to", bhDestino)
                    .put("call_hash", callHash).put("call_salt", b64(callSalt))
                    .put("media", conVideo ? "video" : "audio")
                    .put("offer", ofertaJson(miEk, sig, miBh, ts));
            tx.enviar(m.toString());
        } catch (Exception e) {
            esc.error(msg(e));
        }
    }

    /** El usuario acepta una llamada entrante (tras {@code sonando}). */
    public void aceptar() {
        try {
            String ts = ahora();
            byte[] o = X39Core.callOffer(callHash, miBh, bhOtro, 0, callSalt, miSeed, ts);
            miEk = corte(o, 0, PK_LEN);
            miDk = corte(o, PK_LEN, PK_LEN + SK_LEN);
            byte[] sig = corte(o, PK_LEN + SK_LEN, PK_LEN + SK_LEN + SIG_LEN);
            JSONObject m = new JSONObject().put("type", "accept").put("v", PROTO).put("call_hash", callHash)
                    .put("offer", ofertaJson(miEk, sig, miBh, ts));
            tx.enviar(m.toString());
            encapsularYEnviar();   // encapsulo contra la ek del que llama
        } catch (Exception e) {
            esc.error(msg(e));
        }
    }

    public void rechazar() { enviarSimple("reject"); esc.colgado(); }

    public void colgar() { enviarSimple("hangup"); esc.colgado(); }

    // ---- Entrada de mensajes del servidor --------------------------------------

    /** Procesa una línea JSON recibida del servidor. */
    public void recibir(String json) {
        try {
            JSONObject m = new JSONObject(json);
            switch (m.getString("type")) {
                case "registered": break;
                case "ring":       alSonar(m); break;
                case "accepted":   alAceptar(m); break;
                case "kem":        alKem(m); break;
                case "token":      esc.token(m.getString("jwt"), m.getString("room")); break;
                case "unavailable": esc.error(MOTIVO_NO_DISPONIBLE); break;
                case "reject":
                case "hangup":     esc.colgado(); break;
                case "error":      esc.error(m.optString("motivo", MOTIVO_ERROR_SERVIDOR)); break;
                default: break;
            }
        } catch (Exception e) {
            esc.error(msg(e));
        }
    }

    private void alSonar(JSONObject m) throws Exception {
        exigirVersion(m);
        reset();
        soyLlamante = false;
        callHash = m.getString("call_hash");
        callSalt = unb64(m.getString("call_salt"));
        bhOtro = m.getString("from");
        sigPkOtro = contactos.claveFirma(bhOtro);
        if (sigPkOtro == null) { enviarSimple("reject"); return; }   // desconocido: no suena
        JSONObject of = m.getJSONObject("offer");
        ekOtro = unb64(of.getString("ek"));
        tsOtro = of.getString("ts");
        sigOtro = unb64(of.getString("sig"));
        // verifica la oferta del que llama contra su identidad: cubre emisor (bhOtro),
        // destinatario (yo) y la sal; un relevo no puede redirigir ni cambiar la sal.
        X39Core.callVerifyOffer(callHash, bhOtro, miBh, 0, ekOtro, callSalt, tsOtro, sigOtro, sigPkOtro);
        esc.sonando(bhOtro, callHash, "video".equals(m.optString("media", "audio")));
    }

    private void alAceptar(JSONObject m) throws Exception {
        exigirVersion(m);
        JSONObject of = m.getJSONObject("offer");
        ekOtro = unb64(of.getString("ek"));
        tsOtro = of.getString("ts");
        sigOtro = unb64(of.getString("sig"));
        X39Core.callVerifyOffer(callHash, bhOtro, miBh, 0, ekOtro, callSalt, tsOtro, sigOtro, sigPkOtro);
        encapsularYEnviar();   // encapsulo contra la ek del que acepta
    }

    private void alKem(JSONObject m) throws Exception {
        exigirVersion(m);
        byte[] ct = unb64(m.getString("ct"));
        // el ct llega firmado por el emisor y atado a esta llamada/emisor/destinatario:
        // verificar antes de desencapsular rechaza un ct sustituido o traído de otra llamada.
        String ts = m.getString("ts");
        byte[] sig = unb64(m.getString("sig"));
        if (!bhOtro.equals(m.getString("from")) || !miBh.equals(m.getString("to"))) throw new Exception(MOTIVO_ERROR_SERVIDOR);
        X39Core.callKemVerify(callHash, bhOtro, miBh, 0, ct, ts, sig, sigPkOtro);
        ssOtro = X39Core.callDecapsulate(miDk, ct);
        derivar();
    }

    private void encapsularYEnviar() throws Exception {
        byte[] cs = X39Core.callEncapsulate(ekOtro);   // ct(1568) || ss(32)
        byte[] ct = corte(cs, 0, PK_LEN);
        ssMio = corte(cs, PK_LEN, PK_LEN + 32);
        String ts = ahora();
        byte[] sig = X39Core.callKemSign(callHash, miBh, bhOtro, 0, ct, ts, miSeed);
        JSONObject m = new JSONObject().put("type", "kem").put("v", PROTO).put("call_hash", callHash)
                .put("from", miBh).put("to", bhOtro).put("ts", ts).put("ct", b64(ct)).put("sig", b64(sig));
        tx.enviar(m.toString());
        derivar();
    }

    /** Rechaza un mensaje cuyo campo de versión no sea el del protocolo actual (sin degradar). */
    private void exigirVersion(JSONObject m) throws Exception {
        if (m.optInt("v", -1) != PROTO) throw new Exception(MOTIVO_VERSION);
    }

    private void derivar() {
        if (ssMio == null || ssOtro == null) return;
        byte[] fk = soyLlamante
                ? X39Core.callDerive(miBh, bhOtro, miEk, ekOtro, ssMio, ssOtro, callHash, callSalt, 0)
                : X39Core.callDerive(bhOtro, miBh, ekOtro, miEk, ssOtro, ssMio, callHash, callSalt, 0);
        esc.claveLista(fk, X39Core.callSas(fk, callHash));
    }

    private void enviarSimple(String tipo) {
        if (callHash == null) return;
        try {
            tx.enviar(new JSONObject().put("type", tipo).put("call_hash", callHash).toString());
        } catch (Exception ignored) {
        }
    }

    // ---- Utilidades -------------------------------------------------------------

    private void reset() {
        callHash = null; callSalt = null; bhOtro = null; sigPkOtro = null;
        miEk = null; miDk = null; ekOtro = null; tsOtro = null; sigOtro = null;
        ssMio = null; ssOtro = null; soyLlamante = false;
    }

    private static JSONObject ofertaJson(byte[] ek, byte[] sig, String fromBh, String ts) throws Exception {
        return new JSONObject().put("ek", b64(ek)).put("sig", b64(sig))
                .put("from_bh", fromBh).put("ts", ts);
    }

    private static String ahora() {
        return java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
                .format(java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC));
    }

    private static byte[] rand(int n) { byte[] b = new byte[n]; RNG.nextBytes(b); return b; }

    private static byte[] corte(byte[] a, int desde, int hasta) {
        byte[] r = new byte[hasta - desde];
        System.arraycopy(a, desde, r, 0, r.length);
        return r;
    }

    private static String b64(byte[] b) { return Base64.getEncoder().withoutPadding().encodeToString(b); }

    private static byte[] unb64(String s) { return Base64.getDecoder().decode(s); }

    private static String hex(byte[] b) {
        StringBuilder s = new StringBuilder(b.length * 2);
        for (byte x : b) s.append(Character.forDigit((x >> 4) & 0xF, 16)).append(Character.forDigit(x & 0xF, 16));
        return s.toString();
    }

    private static String msg(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }
}
