package io.livekit.android.sample

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TelemetriaPi5Test {
    @Test fun alturaNoDemuestraSincronizacionNiElectrsActivo() {
        val estado = TelemetriaPi5.parsearRespuesta("https://mining.x39matrix.org", JSONObject("""{"node":{"online":true,"chain":"main","blocks":974891,"synced":null},"pool":{"shares":{"accepted":100,"rejected":2,"counterSemantics":"unverified_raw_counters"},"dataAgeSeconds":12},"meta":{"stale":false}}"""))
        assertEquals(-1L, estado.headers)
        assertTrue(estado.progresoSync.isNaN())
        assertNull(estado.ibd)
        assertNull(estado.sincronizado)
        assertFalse(estado.contadoresVerificados)
        assertFalse(estado.telemetriaDesactualizada)
        assertEquals("No informado por la API", estado.electrsActivo)
        assertEquals(100L, estado.acceptedShares)
        assertEquals(2L, estado.rejectedShares)
    }

    @Test fun conservaEstadoExplicitoYDatosObsoletos() {
        val estado = TelemetriaPi5.parsearRespuesta("https://mining.x39matrix.org", JSONObject("""{"node":{"online":true,"blocks":100,"headers":101,"verificationprogress":0.95,"initialblockdownload":true,"synced":false},"pool":{"status":"online","shares":{"counterSemantics":"share_counts"}},"meta":{"stale":true}}"""))
        assertEquals(101L, estado.headers)
        assertEquals(0.95, estado.progresoSync, 0.00001)
        assertEquals(true, estado.ibd)
        assertEquals(false, estado.sincronizado)
        assertTrue(estado.contadoresVerificados)
        assertTrue(estado.telemetriaDesactualizada)
        assertEquals("RUNNING", estado.poolStatus)
    }

    @Test fun jsonVacioNoSeMarcaFrescoNiInventaDificultadOCarteraOPremio() {
        val vacio = TelemetriaPi5.parsearRespuesta("https://mining.x39matrix.org", JSONObject("{}"))
        assertEquals(-1L, vacio.edadTelemetriaSeg)
        assertTrue(vacio.telemetriaDesactualizada)
        assertEquals(0L, vacio.poolDiffMin)
        assertEquals(0L, vacio.premioPotencialTotalSats)
        assertEquals(0L, vacio.premioPotencialMinero99Sats)
        assertEquals(0L, vacio.premioPotencialOperador1Sats)
        assertEquals("No informada por la API", vacio.dirOperador1Pct)
    }

    @Test fun edadAltaMarcaDesactualizadaAunqueMetaStaleSeaFalse() {
        val viejo = TelemetriaPi5.parsearRespuesta("https://mining.x39matrix.org", JSONObject("""{"meta":{"stale":false,"lastSuccessAgeSeconds":3600}}"""))
        assertEquals(3600L, viejo.edadTelemetriaSeg)
        assertTrue(viejo.telemetriaDesactualizada)
    }

    @Test fun clasificacionPublicaNormalizaMayusculasYPuntoFinal() {
        assertTrue(TelemetriaPi5.esDestinoPublico("https://mining.x39matrix.org"))
        assertTrue(TelemetriaPi5.esDestinoPublico("https://MINING.X39MATRIX.ORG"))
        assertTrue(TelemetriaPi5.esDestinoPublico("https://mining.x39matrix.org."))
        assertFalse(TelemetriaPi5.esDestinoPublico("https://operator.invalid"))
        assertTrue(TelemetriaPi5.esDestinoPrivadoAutorizado("http://10.39.0.3:8000"))
        assertFalse(TelemetriaPi5.esDestinoPrivadoAutorizado("https://operator.invalid"))
    }
}
