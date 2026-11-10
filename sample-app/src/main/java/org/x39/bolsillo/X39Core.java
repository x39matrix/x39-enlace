package org.x39.bolsillo;

/**
 * Puente a tu motor nativo (libx39jni.so, hecho de x39-jni/src/lib.rs). Los mismos
 * símbolos que ya usa la app de Evidencia: la firma de identidad y, ahora, el acuerdo
 * de clave de las llamadas (X39-CALL-KEY). Las claves privadas viven en Rust y no
 * salen del aparato; aquí solo pasan bytes de ida y vuelta.
 *
 * Copia el .so en sample-app/src/main/jniLibs/arm64-v8a/ (y x86_64 si pruebas en
 * emulador). Es el MISMO .so que compilas para Evidencia.
 */
public final class X39Core {
    static { System.loadLibrary("x39jni"); }

    // Identidad ML-DSA-87
    static native byte[] generateSigningSeed();
    static native byte[] signingPublicKey(byte[] seed);
    static native byte[] signMessage(byte[] seed, byte[] message);

    // --- Mensajes: el sobre X39-MSG-v1 ya viene hecho de París. No se rehace aquí. ---
    // El sobre es: ML-KEM-1024 encapsulado por mensaje + AEAD + firma ML-DSA-87 del que
    // escribe. Nosotros solo llamamos; la criptografía vive en Rust.

    /** Tu clave de cifrar en texto: "X39PK1:…". Esta sí cabe en un QR. */
    static native String publicKeyToText(byte[] publicKey);

    /** Lee una "X39PK1:…" y devuelve la clave de cifrar (1568). Lanza si no es válida. */
    static native byte[] publicKeyFromText(String text);

    /** Par ML-KEM-1024 nuevo: devuelve pública(1568) || secreta(3168). La secreta se sella en el Keystore. */
    static native byte[] generateKeypair();

    /** Identidad v2 en texto: "X39ID1:…" con la clave de cifrar y la de firmar juntas. */
    static native String identityToText(byte[] kemPublicKey, byte[] signingPublicKey);

    /** Lee una "X39ID1:…" y devuelve kemPk(1568) || sigPk(2592). Lanza si no es válida. */
    static native byte[] identityFromText(String text);

    /** Huella de una identidad v2: 12 grupos de 5 dígitos, para leerla en voz alta. */
    static native String identityFingerprint(byte[] kemPublicKey, byte[] signingPublicKey);

    /**
     * Cierra `payload` para el destinatario y lo firma con tu semilla. Devuelve "X39MSG1:…".
     * Cada mensaje lleva su propio encapsulado: abrir uno no abre los demás.
     */
    static native String sealSigned(byte[] recipientKemPk, byte[] seed, byte[] payload, long nowSecs);

    /** Abre una "X39MSG1:…" dirigida a ti. Devuelve cid(16) || cuerpo. El ttl es obligatorio. */
    static native byte[] open(byte[] kemSecretKey, String text, long nowSecs, long ttlSecs);

    /** Mira el cuerpo SIN verificar la firma (solo para saber de quién dice ser). */
    static native byte[] unverifiedPayload(byte[] body);

    /** Verifica la firma del remitente sobre el cuerpo y devuelve el texto. Lanza si no cuadra. */
    static native byte[] verifySigned(byte[] senderSigningPk, byte[] myKemPk, byte[] body);

    // Llamadas: X39-CALL-KEY (x39-core/src/call_key.rs)
    static native byte[] callOffer(String callHash, String fromBh, String toBh, int epoch, byte[] callSalt, byte[] mldsaSeed, String ts);
    static native int callVerifyOffer(String callHash, String fromBh, String toBh, int epoch, byte[] ek, byte[] callSalt, String ts, byte[] signature, byte[] emisorPk);
    static native byte[] callKemSign(String callHash, String fromBh, String toBh, int epoch, byte[] ct, String ts, byte[] mldsaSeed);
    static native int callKemVerify(String callHash, String fromBh, String toBh, int epoch, byte[] ct, String ts, byte[] signature, byte[] emisorPk);
    static native byte[] callEncapsulate(byte[] ekOtro);
    static native byte[] callDecapsulate(byte[] dk, byte[] ct);
    static native byte[] callDerive(String bhA, String bhB, byte[] ekA, byte[] ekB, byte[] ssA, byte[] ssB, String callHash, byte[] callSalt, int epoch);
    static native String callSas(byte[] frameKey, String callHash);

    /** Matriz de un código QR: 2 bytes de ancho (big-endian) y luego ancho×ancho bytes (1 = módulo oscuro). */
    public static native byte[] qrMatrix(byte[] data);

    // x39tunel v2: PSK post-cuántica de WireGuard (x39-core/src/tunel.rs)
    /** Devuelve psk(32) || cápsula. La PSK se pone en el par WireGuard; la cápsula se manda a la señalización. */
    public static native byte[] tunelPedirPsk(byte[] servidorKemPk, byte[] mldsaSeed, byte[] wgPk, long hasta, long ahora);
    public static native String tunelHuellaServidor(byte[] servidorKemPk);
}
