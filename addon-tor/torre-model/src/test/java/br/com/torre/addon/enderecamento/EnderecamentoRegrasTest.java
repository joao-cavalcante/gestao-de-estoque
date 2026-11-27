package br.com.torre.addon.enderecamento;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class EnderecamentoRegrasTest {

    private static BigDecimal d(String s) {
        return new BigDecimal(s);
    }

    private static EnderecamentoRegras.Endereco e(long cod, String codigo, String tipo, String cap) {
        return new EnderecamentoRegras.Endereco(cod, codigo, tipo, cap == null ? null : d(cap), false, true);
    }

    @Test
    void quantidadeDaTarefaEhOFisicoLimitadoAoQueEntrou() {
        // ajuste a maior (compra 73686): balança 1100, nota 1000 + ajuste 100
        assertEquals(0, d("1100").compareTo(EnderecamentoRegras.quantidadeTarefa(d("1100"), d("1100"))));
        // falta com devolução (73673): conferido 700, nota 1000
        assertEquals(0, d("700").compareTo(EnderecamentoRegras.quantidadeTarefa(d("700"), d("1000"))));
        // sobra que vira complemento (73672): conferido 1300, nota 1000 — a sobra espera na doca
        assertEquals(0, d("1000").compareTo(EnderecamentoRegras.quantidadeTarefa(d("1300"), d("1000"))));
        assertEquals(0, BigDecimal.ZERO.compareTo(EnderecamentoRegras.quantidadeTarefa(null, d("10"))));
    }

    @Test
    void sugereNaOrdemPickingPulmaoDoProdutoPulmaoVazio() {
        String k = EnderecamentoRegras.chave("10", " ");
        List<EnderecamentoRegras.Endereco> l = new ArrayList<EnderecamentoRegras.Endereco>();
        EnderecamentoRegras.Endereco vazio = e(1, "A-01-1", "PULMAO", "500");
        EnderecamentoRegras.Endereco doProduto = e(2, "A-02-1", "PULMAO", "500");
        doProduto.produtos.add(k);
        doProduto.ocupado = d("100");
        EnderecamentoRegras.Endereco picking = e(3, "P-01-1", "PICKING", "50");
        picking.produtos.add(k);
        picking.ocupado = d("20");
        l.add(vazio);
        l.add(doProduto);
        l.add(picking);
        assertEquals("P-01-1", EnderecamentoRegras.sugerir(l, k, d("100")).codigo);
        picking.ocupado = d("50"); // picking cheio
        assertEquals("A-02-1", EnderecamentoRegras.sugerir(l, k, d("100")).codigo);
        doProduto.ocupado = d("500"); // pulmão do produto cheio
        assertEquals("A-01-1", EnderecamentoRegras.sugerir(l, k, d("100")).codigo);
    }

    @Test
    void naoSugereBloqueadoDocaNemPulmaoDeOutroProduto() {
        String k = EnderecamentoRegras.chave("10", "");
        List<EnderecamentoRegras.Endereco> l = new ArrayList<EnderecamentoRegras.Endereco>();
        l.add(new EnderecamentoRegras.Endereco(1, "A-01-1", "PULMAO", null, true, true));
        l.add(e(2, "DOCA-1-101", "DOCA", null));
        EnderecamentoRegras.Endereco outro = e(3, "A-03-1", "PULMAO", null);
        outro.produtos.add(EnderecamentoRegras.chave("99", ""));
        l.add(outro);
        assertNull(EnderecamentoRegras.sugerir(l, k, d("5")));
    }

    @Test
    void pulmaoVazioQueCabeTudoVemAntes() {
        String k = EnderecamentoRegras.chave("10", "");
        List<EnderecamentoRegras.Endereco> l = new ArrayList<EnderecamentoRegras.Endereco>();
        l.add(e(1, "A-01-1", "PULMAO", "50"));
        l.add(e(2, "A-02-1", "PULMAO", "500"));
        assertEquals("A-02-1", EnderecamentoRegras.sugerir(l, k, d("100")).codigo);
    }

    @Test
    void recusaDestino() {
        String k = EnderecamentoRegras.chave("10", "");
        assertNotNull(EnderecamentoRegras.recusaDestino(null, k));
        assertNotNull(EnderecamentoRegras.recusaDestino(e(2, "DOCA-1-101", "DOCA", null), k));
        assertNotNull(EnderecamentoRegras.recusaDestino(new EnderecamentoRegras.Endereco(1, "A", "PULMAO", null, true, true), k));
        EnderecamentoRegras.Endereco outro = e(3, "A-03-1", "PULMAO", null);
        outro.produtos.add(EnderecamentoRegras.chave("99", ""));
        assertNotNull(EnderecamentoRegras.recusaDestino(outro, k));
        EnderecamentoRegras.Endereco picking = e(4, "P-1", "PICKING", null);
        picking.produtos.add(EnderecamentoRegras.chave("99", ""));
        assertNull(EnderecamentoRegras.recusaDestino(picking, k)); // picking pode ter mais de um produto
        assertNull(EnderecamentoRegras.recusaDestino(e(5, "A-05-1", "PULMAO", null), k));
    }

    @Test
    void faltaNuncaNegativa() {
        assertEquals(0, d("30").compareTo(EnderecamentoRegras.falta(d("100"), d("70"))));
        assertEquals(0, BigDecimal.ZERO.compareTo(EnderecamentoRegras.falta(d("100"), d("120"))));
    }

    @Test
    void primeiraEntradaVaiParaPickingVazioDoSetor() {
        String k = EnderecamentoRegras.chave("10", "");
        List<EnderecamentoRegras.Endereco> l = new ArrayList<EnderecamentoRegras.Endereco>();
        EnderecamentoRegras.Endereco pkSeco = e(1, "R01-M01-N01", "PICKING", "500");
        pkSeco.tipoSep = 1;
        EnderecamentoRegras.Endereco pkCong = e(2, "R11-M01-N01", "PICKING", "500");
        pkCong.tipoSep = 3;
        EnderecamentoRegras.Endereco pulCong = e(3, "R11-M01-N02", "PULMAO", "1000");
        pulCong.tipoSep = 3;
        l.add(pkSeco);
        l.add(pkCong);
        l.add(pulCong);
        // congelado sem picking ainda: picking vazio do setor congelado (não o seco, que vem antes no código)
        assertEquals("R11-M01-N01", EnderecamentoRegras.sugerir(l, k, d("100"), 3).codigo);
        // já tem picking em outro lugar: vai para o pulmão
        pkSeco.produtos.add(k);
        pkSeco.ocupado = d("500"); // cheio
        assertEquals("R11-M01-N02", EnderecamentoRegras.sugerir(l, k, d("100"), 3).codigo);
        // não cabe no picking vazio: pulmão
        pkSeco.produtos.clear();
        pkSeco.ocupado = BigDecimal.ZERO;
        assertEquals("R11-M01-N02", EnderecamentoRegras.sugerir(l, k, d("800"), 3).codigo);
    }

    @Test
    void setorDiferenteNuncaESugerido() {
        String k = EnderecamentoRegras.chave("10", "");
        List<EnderecamentoRegras.Endereco> l = new ArrayList<EnderecamentoRegras.Endereco>();
        EnderecamentoRegras.Endereco seco = e(1, "R01-M01-N02", "PULMAO", null);
        seco.tipoSep = 1;
        l.add(seco);
        assertNull(EnderecamentoRegras.sugerir(l, k, d("10"), 2));
        assertEquals("R01-M01-N02", EnderecamentoRegras.sugerir(l, k, d("10"), 1).codigo);
        assertEquals("R01-M01-N02", EnderecamentoRegras.sugerir(l, k, d("10"), null).codigo); // produto sem tipo
    }

    @Test
    void reservaDeOutraTarefaOcupaOPulmao() {
        String a = EnderecamentoRegras.chave("10", "");
        String b = EnderecamentoRegras.chave("20", "");
        List<EnderecamentoRegras.Endereco> l = new ArrayList<EnderecamentoRegras.Endereco>();
        EnderecamentoRegras.Endereco p1 = e(1, "R01-M01-N03", "PULMAO", "1000");
        EnderecamentoRegras.Endereco p2 = e(2, "R01-M01-N04", "PULMAO", "1000");
        l.add(p1);
        l.add(p2);
        EnderecamentoRegras.reservar(EnderecamentoRegras.sugerir(l, a, d("200")), a, d("200"));
        assertEquals("R01-M01-N04", EnderecamentoRegras.sugerir(l, b, d("180")).codigo); // não vai para o reservado
        assertEquals("R01-M01-N03", EnderecamentoRegras.sugerir(l, a, d("50")).codigo); // mesmo produto pode completar
    }
}
