package br.com.torre.addon.recebimento;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecebimentoRegrasTest {

    private static BigDecimal n(long v) {
        return BigDecimal.valueOf(v);
    }

    @Test
    void listaDeAprovadores() {
        assertTrue(RecebimentoRegras.podeAprovar("27, 215;210 192", n(210)));
        assertTrue(RecebimentoRegras.podeAprovar("27", new BigDecimal("27.0")));
        assertFalse(RecebimentoRegras.podeAprovar("27,215", n(39)));
        assertFalse(RecebimentoRegras.podeAprovar("", n(27)));
        assertFalse(RecebimentoRegras.podeAprovar(null, n(27)));
        assertFalse(RecebimentoRegras.podeAprovar("27", null));
        assertTrue(RecebimentoRegras.podeAprovar("abc, 27", n(27)));
    }

    @Test
    void conferenciaPrecisaSerAtualEFinalizada() {
        assertNull(RecebimentoRegras.validarConferencia(n(100), n(5), n(5), "F"));
        assertNull(RecebimentoRegras.validarConferencia(n(100), n(5), n(5), "D"));
        assertNotNull(RecebimentoRegras.validarConferencia(n(100), n(5), n(6), "F"));
        assertNotNull(RecebimentoRegras.validarConferencia(n(100), n(5), null, "F"));
        assertNotNull(RecebimentoRegras.validarConferencia(n(100), n(5), n(5), "C"));
        assertNotNull(RecebimentoRegras.validarConferencia(n(100), n(5), n(5), "A"));
        assertNotNull(RecebimentoRegras.validarConferencia(n(100), n(5), n(5), null));
        assertNotNull(RecebimentoRegras.validarConferencia(null, n(5), n(5), "F"));
    }
}
