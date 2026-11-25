package br.com.torre.addon.services;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Leitura do corpo que o bean do Addon Studio entrega (Gson: número JSON vira Double). */
class CorpoTest {

    @Test
    void numeroDoGsonVoltaExato() {
        assertEquals(new BigDecimal("88093"), Corpo.numero(88093.0d));
        assertEquals(new BigDecimal("-25.3"), Corpo.numero(-25.3d));
        assertEquals(new BigDecimal("7437.84"), Corpo.numero(7437.84d));
        assertEquals(new BigDecimal("7437.84"), Corpo.numero("7437,84"));
        assertNull(Corpo.numero(" "));
        assertNull(Corpo.numero("abc"));
    }

    @Test
    void desembrulhaAteOCampo() {
        Map<String, Object> dentro = new HashMap<String, Object>();
        dentro.put("NUNOTA", "1");
        Map<String, Object> fora = new HashMap<String, Object>();
        fora.put("request", dentro);
        assertEquals("1", Corpo.desembrulhar(fora, "NUNOTA").get("NUNOTA"));
        assertEquals("1", Corpo.desembrulhar(dentro, "NUNOTA").get("NUNOTA"));
    }

    @Test
    void listaAceitaObjetoUnico() {
        Map<String, Object> item = new HashMap<String, Object>();
        item.put("CODPROD", "3243");
        assertEquals(1, Corpo.lista(item).size());
        assertEquals(2, Corpo.lista(Arrays.asList(item, item)).size());
        assertEquals(0, Corpo.lista(null).size());
    }
}
