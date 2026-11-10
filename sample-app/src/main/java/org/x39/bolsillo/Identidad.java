package org.x39.bolsillo;

import java.security.MessageDigest;

/**
 * Identidad soberana de x39: una semilla ML-DSA-87 (secreta) de la que salen la
 * clave pública de firma y la huella (binding hash = sha256 de la pública en hex).
 * La huella ES tu número: es lo que otro guarda en su agenda para llamarte.
 *
 * La semilla nunca sale del aparato; aquí solo se deriva. Quién la guarda (y cómo,
 * envuelta por el almacén de claves del sistema) es cosa de {@link Boveda}.
 */
public final class Identidad {

    public final byte[] seed;    // ML-DSA-87 (secreto)
    public final byte[] sigPk;   // ML-DSA-87 pública
    public final String bh;      // huella = sha256(sigPk) en hex

    private Identidad(byte[] seed, byte[] sigPk, String bh) {
        this.seed = seed;
        this.sigPk = sigPk;
        this.bh = bh;
    }

    /** Crea una identidad nueva con semilla fresca del motor. */
    public static Identidad crear() {
        return desdeSemilla(X39Core.generateSigningSeed());
    }

    /** Reconstruye la identidad desde una semilla ya guardada. */
    public static Identidad desdeSemilla(byte[] seed) {
        byte[] pk = X39Core.signingPublicKey(seed);
        return new Identidad(seed, pk, sha256Hex(pk));
    }

    /** Huella de una clave pública de firma ajena (para guardar un contacto). */
    public static String huellaDe(byte[] sigPk) {
        return sha256Hex(sigPk);
    }

    private static String sha256Hex(byte[] b) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(b);
            StringBuilder s = new StringBuilder(h.length * 2);
            for (byte x : h) {
                s.append(Character.forDigit((x >> 4) & 0xF, 16));
                s.append(Character.forDigit(x & 0xF, 16));
            }
            return s.toString();
        } catch (Exception e) {
            throw new RuntimeException("sin SHA-256", e);
        }
    }
}
