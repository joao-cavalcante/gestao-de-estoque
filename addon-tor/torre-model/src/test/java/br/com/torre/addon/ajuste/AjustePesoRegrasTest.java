package br.com.torre.addon.ajuste;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AjustePesoRegrasTest {

    private static BigDecimal bd(String s) {
        return new BigDecimal(s);
    }

    private static AjustePesoRegras.Diferenca dif(int codprod, String dif) {
        return new AjustePesoRegras.Diferenca(codprod, null, bd("10"), bd("10").add(bd(dif)), bd(dif));
    }

    @Test
    void separaPorSentido() {
        List<AjustePesoRegras.Diferenca> itens = Arrays.asList(dif(1, "0.250"), dif(2, "-1.5"), dif(3, "0.1"));
        Map<String, List<AjustePesoRegras.Diferenca>> m = AjustePesoRegras.porSentido(itens);
        assertEquals(2, m.get("E").size());
        assertEquals(1, m.get("S").size());
        assertEquals(2, m.get("S").get(0).codprod);
    }

    @Test
    void quantidadePelasCasasDoProduto() {
        assertEquals(bd("25.300"), AjustePesoRegras.quantidade(bd("-25.3"), null));
        assertEquals(bd("2"), AjustePesoRegras.quantidade(bd("1.6"), 0));
        assertEquals(bd("0.25"), AjustePesoRegras.quantidade(bd("0.2549"), 2));
        assertEquals(bd("1.000000"), AjustePesoRegras.quantidade(bd("1"), 9));
    }

    @Test
    void precoNaUnidadePadrao() {
        // Compra 88093: queijo 7.463,14 kg; R$ 186.578,50 no total do produto (exemplo) -> 25,0000/kg.
        assertEquals(bd("25.0000"), AjustePesoRegras.precoUnitario(bd("186578.50"), bd("7463.14")));
        assertNull(AjustePesoRegras.precoUnitario(bd("10"), BigDecimal.ZERO));
        assertEquals(bd("632.50"), AjustePesoRegras.total(bd("25.300"), bd("25.0000")));
    }

    @Test
    void validaEntrada() {
        assertEquals("NUNOTA obrigatório", AjustePesoRegras.validar(null, bd("1"), Collections.singletonList(dif(1, "1"))));
        assertEquals("NUCONF obrigatório", AjustePesoRegras.validar(bd("1"), null, Collections.singletonList(dif(1, "1"))));
        assertEquals("nenhum item com diferença", AjustePesoRegras.validar(bd("1"), bd("1"), Collections.<AjustePesoRegras.Diferenca>emptyList()));
        assertTrue(AjustePesoRegras.validar(bd("1"), bd("1"), Collections.singletonList(dif(7, "0"))).contains("produto 7"));
        assertNull(AjustePesoRegras.validar(bd("1"), bd("1"), Collections.singletonList(dif(7, "0.5"))));
    }

    @Test
    void controleVazioViraEspaco() {
        assertEquals(" ", AjustePesoRegras.normalizarControle(null));
        assertEquals(" ", AjustePesoRegras.normalizarControle("   "));
        assertEquals("L01", AjustePesoRegras.normalizarControle(" L01 "));
    }

    @Test
    void observacaoDaNota() {
        String obs = AjustePesoRegras.observacao(bd("88093"), bd("83277"), bd("5501"), "S");
        assertTrue(obs.contains("compra 88093 (NF 83277)"));
        assertTrue(obs.contains("saída"));
        assertTrue(obs.contains("conferência 5501"));
    }
}
