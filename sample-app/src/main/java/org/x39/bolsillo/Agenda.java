package org.x39.bolsillo;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Agenda local y soberana de x39: contactos {huella, clave de firma, nombre}
 * guardados en un fichero privado del aparato. Ningún servidor sabe con quién
 * hablas: la agenda vive solo aquí.
 *
 * Implementa {@link Llamada.Contactos}: dada una huella entrante, devuelve la
 * clave de firma con la que verificar que quien llama es de verdad ese contacto
 * (anti-intermediario). Guardar a alguien es guardar su clave de firma; la huella
 * se deriva de ella, así que no se puede falsear el par nombre↔huella.
 *
 * Formato del fichero, una línea por contacto:
 *     base64(sigPk) \t nombre [\t verificado] [\t kem:base64(kemPk)]
 * Las líneas antiguas (solo sigPk y nombre) se siguen leyendo igual: un contacto sin
 * `kem:` es un contacto v1, al que se puede LLAMAR pero no escribir.
 */
public final class Agenda implements Llamada.Contactos {

    /** Un contacto de la agenda. */
    public static final class Contacto {
        public final String bh;
        public final byte[] sigPk;
        public final String nombre;
        /** Verificado = las seis palabras (o el QR) coincidieron con la persona; lo marca quien usa la app. */
        public final boolean verificado;
        /** Clave pública ML-KEM-1024 del contacto, o null si solo tienes su clave de firma (v1). */
        public final byte[] kemPk;
        Contacto(String bh, byte[] sigPk, String nombre, boolean verificado, byte[] kemPk) {
            this.bh = bh; this.sigPk = sigPk; this.nombre = nombre;
            this.verificado = verificado; this.kemPk = kemPk;
        }
        /** ¿Se le puede escribir? Hace falta su clave de cifrar, no basta la de firmar. */
        public boolean puedeRecibirMensajes() { return kemPk != null; }
    }

    /** Almacén cifrado opcional (Boveda en Android); si es null usa el fichero directo. */
    public interface Almacen {
        byte[] leer();
        void escribir(byte[] datos);
    }

    private final File fichero;
    private final Almacen almacen;
    private final Map<String, Contacto> porHuella = new LinkedHashMap<>();

    public Agenda(File fichero) {
        this(fichero, null);
    }

    public Agenda(File fichero, Almacen almacen) {
        this.fichero = fichero;
        this.almacen = almacen;
        cargar();
    }

    private synchronized void cargar() {
        porHuella.clear();
        try {
            byte[] crudo = (almacen != null) ? almacen.leer() : null;
            List<String> lineas;
            if (crudo != null) {
                lineas = java.util.Arrays.asList(new String(crudo, StandardCharsets.UTF_8).split("\r?\n"));
            } else if (fichero != null && fichero.exists()) {
                lineas = Files.readAllLines(fichero.toPath(), StandardCharsets.UTF_8);
                if (almacen != null) {
                    almacen.escribir(Files.readAllBytes(fichero.toPath()));
                    fichero.delete();
                }
            } else {
                return;
            }
            for (String linea : lineas) {
                if (linea.isEmpty()) continue;
                int t = linea.indexOf('\t');
                if (t < 0) continue;
                String[] campos = linea.split("\t", -1);
                if (campos.length < 2) continue;
                byte[] pk = Base64.getDecoder().decode(campos[0]);
                String nombre = campos[1];
                boolean verificado = false;
                byte[] kemPk = null;
                for (int i = 2; i < campos.length; i++) {
                    if ("verificado".equals(campos[i])) verificado = true;
                    else if (campos[i].startsWith("kem:")) {
                        try { kemPk = Base64.getDecoder().decode(campos[i].substring(4)); }
                        catch (Exception ignored) { }
                    }
                }
                String bh = Identidad.huellaDe(pk);
                porHuella.put(bh, new Contacto(bh, pk, nombre, verificado, kemPk));
            }
        } catch (Exception e) {
            // fichero corrupto: no reventamos la app, arrancamos con agenda vacía
            porHuella.clear();
        }
    }

    /** Guarda (o actualiza el nombre de) un contacto por su clave de firma. Devuelve su huella. */
    public synchronized String guardar(String nombre, byte[] sigPk) {
        return guardar(nombre, sigPk, null);
    }

    /**
     * Guarda (o actualiza) un contacto. Si `kemPk` es null se conserva la que ya hubiera:
     * añadir un contacto por su identidad v1 no debe borrarle la clave de cifrar.
     */
    public synchronized String guardar(String nombre, byte[] sigPk, byte[] kemPk) {
        String bh = Identidad.huellaDe(sigPk);
        Contacto previo = porHuella.get(bh);
        byte[] kem = kemPk != null ? kemPk : (previo == null ? null : previo.kemPk);
        // «Verificado» solo se conserva si las claves son las MISMAS que se compararon: la huella que se
        // leyó en voz alta cubre la de cifrar, así que una clave de cifrar nueva obliga a comprobar otra vez.
        boolean mismaKem = previo != null && java.util.Arrays.equals(previo.kemPk, kem);
        porHuella.put(bh, new Contacto(bh, sigPk, nombre, previo != null && previo.verificado && mismaKem, kem));
        reescribir();
        return bh;
    }

    /** Marca (o desmarca) un contacto como verificado en persona. */
    public synchronized void marcarVerificado(String bh, boolean si) {
        Contacto c = porHuella.get(bh);
        if (c == null) return;
        porHuella.put(bh, new Contacto(bh, c.sigPk, c.nombre, si, c.kemPk));
        reescribir();
    }

    /** Borra un contacto por su huella. */
    public synchronized void borrar(String bh) {
        if (porHuella.remove(bh) != null) reescribir();
    }

    /** Contacto por huella, o null. */
    public synchronized Contacto contacto(String bh) {
        return porHuella.get(bh);
    }

    /** Todos los contactos, en el orden en que se guardaron. */
    public synchronized List<Contacto> todos() {
        return Collections.unmodifiableList(new ArrayList<>(porHuella.values()));
    }

    // ---- Llamada.Contactos -----------------------------------------------------

    /** Clave de cifrar de un contacto (ML-KEM-1024), o null si aún no la tienes. */
    public synchronized byte[] claveCifrado(String bh) {
        Contacto c = porHuella.get(bh);
        return c == null ? null : c.kemPk;
    }

    @Override
    public synchronized byte[] claveFirma(String bh) {
        Contacto c = porHuella.get(bh);
        return c == null ? null : c.sigPk;
    }

    // ---- interno ---------------------------------------------------------------

    private void reescribir() {
        StringBuilder sb = new StringBuilder();
        for (Contacto c : porHuella.values()) {
            sb.append(Base64.getEncoder().withoutPadding().encodeToString(c.sigPk));
            sb.append('\t').append(c.nombre);
            if (c.verificado) sb.append("\tverificado");
            if (c.kemPk != null) {
                sb.append("\tkem:").append(Base64.getEncoder().withoutPadding().encodeToString(c.kemPk));
            }
            sb.append('\n');
        }
        byte[] bytes = sb.toString().getBytes(StandardCharsets.UTF_8);
        if (almacen != null) {
            try {
                almacen.escribir(bytes);
                if (fichero != null && fichero.exists()) fichero.delete();
                return;
            } catch (Exception ignored) {
                // respaldo a fichero privado si el Keystore fallase
            }
        }
        if (fichero == null) return;
        try {
            File tmp = new File(fichero.getParentFile(), fichero.getName() + ".tmp");
            Files.write(tmp.toPath(), bytes);
            if (!tmp.renameTo(fichero)) {
                Files.write(fichero.toPath(), bytes);
                tmp.delete();
            }
        } catch (IOException e) {
            // mejor esfuerzo: si no se puede escribir, la copia en memoria sigue válida
        }
    }
}
