package io.livekit.android.sample

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/** El QR por partes junta UNA identidad íntegra o se para: nunca mezcla partes de dos. */
class QrPartesTest {
    private fun identidad(semilla: Int): String {
        val alfabeto = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
        return "X39ID1:" + (0 until 5552).map { alfabeto[(it * 7919 + semilla * 104729) % 64] }.joinToString("")
    }

    @Test fun juntaEnCualquierOrden() {
        val texto = identidad(1)
        val partes = QrPartes.trocear(texto).shuffled(java.util.Random(39))
        val r = QrPartes.Recolector()
        partes.dropLast(1).forEach { assertNull(r.anadir(it)) }
        assertEquals(texto, r.anadir(partes.last()))
    }

    @Test fun partesRepetidasIgualesNoMolestan() {
        val texto = identidad(2)
        val partes = QrPartes.trocear(texto)
        val r = QrPartes.Recolector()
        partes.dropLast(1).forEach { r.anadir(it); r.anadir(it) }
        assertEquals(texto, r.anadir(partes.last()))
    }

    @Test fun parteDeOtraIdentidadPara() {
        val a = QrPartes.trocear(identidad(1))
        val b = QrPartes.trocear(identidad(2))
        val r = QrPartes.Recolector()
        a.take(5).forEach { r.anadir(it) }
        assertThrows(QrPartes.Mezcla::class.java) { r.anadir(b[5]) }
    }

    @Test fun trozoManipuladoConLaMismaEtiquetaPara() {
        val partes = QrPartes.trocear(identidad(3)).toMutableList()
        // Mismo prefijo, etiqueta y posición; contenido cambiado en un solo carácter.
        val p = partes[4]
        partes[4] = p.dropLast(1) + (if (p.last() == 'A') 'B' else 'A')
        val r = QrPartes.Recolector()
        partes.dropLast(1).forEach { r.anadir(it) }
        assertThrows(QrPartes.Mezcla::class.java) { r.anadir(partes.last()) }
    }

    @Test fun mismaPosicionConOtroContenidoPara() {
        val partes = QrPartes.trocear(identidad(4))
        val r = QrPartes.Recolector()
        r.anadir(partes[0])
        val otra = partes[0].dropLast(1) + (if (partes[0].last() == 'A') 'B' else 'A')
        assertThrows(QrPartes.Mezcla::class.java) { r.anadir(otra) }
    }

    @Test fun totalDistintoPara() {
        val texto = identidad(5)
        val r = QrPartes.Recolector()
        r.anadir(QrPartes.trocear(texto, 12)[0])
        assertThrows(QrPartes.Mezcla::class.java) { r.anadir(QrPartes.trocear(texto, 10)[1]) }
    }

    @Test fun malFormadaSeRechazaSinParar() {
        val r = QrPartes.Recolector()
        assertThrows(IllegalArgumentException::class.java) { r.anadir("X39QP2:corta:1/12:xx") }
        assertThrows(IllegalArgumentException::class.java) { r.anadir("X39QP1:abcd:1/12:xx") }
    }
}
