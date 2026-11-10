package org.x39.bolsillo;

/**
 * El sobre de los mensajes de x39 (X39-MSG-v1).
 *
 * Aquí NO hay criptografía: está toda en el motor nativo (París). Esta clase solo
 * llama, y el sobre en sí vive en el chat común (org.x39.chat.Sobres), el mismo que
 * usa x39 Evidencia. El sobre que hace el motor es, por cada mensaje:
 *   · un encapsulado ML-KEM-1024 nuevo contra la clave de cifrar del destinatario,
 *   · cifrado autenticado de la carga con la clave que sale de ese encapsulado,
 *   · y el conjunto firmado con tu ML-DSA-87.
 *
 * Que cada mensaje lleve su propio encapsulado significa una cosa concreta: reventar
 * la clave de un mensaje no abre los demás.
 *
 * El servidor solo mueve el sobre. No puede leerlo. Sí ve, como cualquier servidor,
 * quién escribe a quién, cuándo y cuánto ocupa; eso no lo tapa el cifrado.
 */
public final class Mensajeria {

    private Mensajeria() { }

    public static final int KEM_PK = 1568;
    public static final int KEM_SK = 3168;
    public static final int SIG_PK = 2592;
    /** Los sobres del chat común (x39-chat), con la ventana de entrega de x39. */
    private static final org.x39.chat.Sobres SOBRES =
            new org.x39.chat.Sobres(MotorX39.UNICO, Mensajeria.VENTANA_ENTREGA_S);

    /**
     * RELOJ 1 — VENTANA DE ENTREGA. Cuánto tiempo se acepta abrir una cápsula, contado
     * desde que se selló.
     *
     * OJO con lo que esto es y lo que no: la hora de sellado va autenticada dentro de la
     * cápsula y no se puede tocar, pero el PLAZO lo aplica quien abre, no viaja sellado.
     * Sirve para que una cápsula vieja no se cuele en un receptor honesto; no impide que
     * alguien con una versión modificada de la app abra una cápsula antigua.
     *
     * París no impone ningún máximo: este número lo elige la app. 30 días cubre un móvil
     * apagado o sin cobertura sin dejar sobres dando vueltas para siempre.
     */
    public static final long VENTANA_ENTREGA_S = 30L * 24 * 3600;

    /**
     * Margen de reloj adelantado que admite el núcleo (MAX_FUTURE_SKEW en x39-core).
     * Si los dos móviles llevan horas que difieren más de esto, los mensajes se rechazan.
     */
    public static final long MARGEN_RELOJ_S = 300;

    /** Par ML-KEM-1024 nuevo: pública(1568) || secreta(3168). */
    public static byte[] nuevoParCifrado() {
        return X39Core.generateKeypair();
    }

    /** Tu identidad v2 en texto compartible: "X39ID1:…" (clave de cifrar + clave de firmar). */
    public static String identidadTexto(byte[] kemPk, byte[] sigPk) {
        return X39Core.identityToText(kemPk, sigPk);
    }

    /** Lee una "X39ID1:…". Devuelve kemPk(1568) || sigPk(2592). Lanza si no vale. */
    public static byte[] identidadDeTexto(String texto) {
        return X39Core.identityFromText(texto);
    }

    /** Lee una "X39PK1:…": solo la clave de cifrar. Lanza si no vale. */
    public static byte[] claveCifradoDeTexto(String texto) {
        return X39Core.publicKeyFromText(texto);
    }

    /** Tu clave de cifrar en texto, "X39PK1:…". Cabe en un QR; la identidad entera no. */
    public static String claveCifradoTexto(byte[] kemPk) {
        return X39Core.publicKeyToText(kemPk);
    }

    /** Huella de una identidad v2: 12 grupos de 5 dígitos, para leerla en voz alta. */
    public static String huellaIdentidad(byte[] kemPk, byte[] sigPk) {
        return X39Core.identityFingerprint(kemPk, sigPk);
    }

    /**
     * Cierra un mensaje para el destinatario y lo firma. Devuelve el texto "X39MSG1:…"
     * que viaja por la señalización.
     *
     * Se llama en el momento de enviar de verdad (también en cada reintento), no al
     * escribir: así en el móvil solo se guarda el texto, nunca un sobre caducando.
     */
    public static String cerrar(byte[] kemPkDestino, byte[] miSemilla, byte[] texto) {
        return SOBRES.cerrar(kemPkDestino, miSemilla, texto);
    }

    /** ¿Ya no se puede entregar? Se compara con la hora de envío, en segundos. */
    public static boolean caducado(long selladoEnSegundos) {
        return SOBRES.caducado(selladoEnSegundos);
    }

    /** Lo que sale de abrir un sobre: el identificador anti-repetición y el texto. */
    public static final class Abierto {
        public final byte[] cid;     // 16 bytes: si se repite, el mensaje es un reenvío
        public final byte[] texto;
        Abierto(byte[] cid, byte[] texto) { this.cid = cid; this.texto = texto; }
    }

    /**
     * Abre un sobre dirigido a ti y comprueba que lo firmó de verdad ese contacto.
     * Lanza si la firma no cuadra, si no es para ti o si ha caducado: en ese caso el
     * mensaje se tira, no se enseña a medias.
     */
    public static Abierto abrir(byte[] miKemSk, byte[] miKemPk, byte[] sigPkRemitente, String sobre) {
        org.x39.chat.Sobres.Abierto a = SOBRES.abrir(miKemSk, miKemPk, sigPkRemitente, sobre);
        return new Abierto(a.cid, a.carga);
    }
}
