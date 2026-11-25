package br.com.torre.addon.ajuste;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Regras puras do ajuste de peso da compra (sem Sankhya) - testadas em AjustePesoRegrasTest.
 *
 * Mesmas regras da Torre (AjustePesoCompra.kt, em produção desde 10/11/2026) e do WMS nativo (regras 1.10 do Hub):
 * diferença > 0 vira nota de ENTRADA, < 0 vira nota de SAÍDA; quantidade = |diferença| arredondada pelas casas
 * decimais do produto (TGFPRO.DECQTD; sem valor = 3, a balança); preço unitário na unidade padrão = VLRTOT do produto
 * na compra / quantidade da nota.
 */
public final class AjustePesoRegras {

    public static final String ENTRADA = "E";
    public static final String SAIDA = "S";

    /**
     * TORPPV.STATUS (plano B, 25/11/2026): P = registrada pela Torre, compra ainda não confirmada; A = compra
     * confirmada, a gerar (job); G = nota de ajuste gerada; E = parou em erro (5 tentativas).
     */
    public static final String STATUS_PENDENTE = "P";
    public static final String STATUS_A_GERAR = "A";
    public static final String STATUS_GERADO = "G";
    public static final String STATUS_ERRO = "E";
    public static final int MAX_TENTATIVAS = 5;

    private AjustePesoRegras() {
    }

    /** Diferença de um item vinda da Torre. */
    public static final class Diferenca {
        public final int codprod;
        public final String controle;
        public final BigDecimal qtdNota;
        public final BigDecimal qtdBalanca;
        public final BigDecimal qtdDif;

        public Diferenca(int codprod, String controle, BigDecimal qtdNota, BigDecimal qtdBalanca, BigDecimal qtdDif) {
            this.codprod = codprod;
            this.controle = normalizarControle(controle);
            this.qtdNota = qtdNota;
            this.qtdBalanca = qtdBalanca;
            this.qtdDif = qtdDif;
        }
    }

    /** Controle vazio/nulo = " " (como o Sankhya grava TGFITE.CONTROLE sem lote). */
    public static String normalizarControle(String controle) {
        if (controle == null) return " ";
        String t = controle.trim();
        return t.isEmpty() ? " " : t;
    }

    /** Valida a entrada do serviço; devolve a mensagem do primeiro problema ou null. */
    public static String validar(BigDecimal nunota, BigDecimal nuconf, List<Diferenca> itens) {
        if (nunota == null || nunota.signum() <= 0) return "NUNOTA obrigatório";
        if (nuconf == null || nuconf.signum() <= 0) return "NUCONF obrigatório";
        if (itens == null || itens.isEmpty()) return "nenhum item com diferença";
        for (Diferenca d : itens) {
            if (d.codprod <= 0) return "item sem CODPROD";
            if (d.qtdDif == null || d.qtdDif.signum() == 0) return "produto " + d.codprod + ": diferença zero ou ausente";
        }
        return null;
    }

    /** Separa os itens por sentido (E a maior, S a menor), mantendo a ordem de chegada. */
    public static Map<String, List<Diferenca>> porSentido(List<Diferenca> itens) {
        Map<String, List<Diferenca>> m = new LinkedHashMap<String, List<Diferenca>>();
        for (Diferenca d : itens) {
            String s = d.qtdDif.signum() > 0 ? ENTRADA : SAIDA;
            List<Diferenca> l = m.get(s);
            if (l == null) {
                l = new ArrayList<Diferenca>();
                m.put(s, l);
            }
            l.add(d);
        }
        return Collections.unmodifiableMap(m);
    }

    /** |diferença| arredondada pelas casas decimais do produto (null = 3; limitado a 0..6). */
    public static BigDecimal quantidade(BigDecimal diferenca, Integer decQtd) {
        int casas = decQtd == null ? 3 : Math.max(0, Math.min(6, decQtd));
        return diferenca.abs().setScale(casas, RoundingMode.HALF_UP);
    }

    /** Preço unitário na unidade padrão: total do produto na compra / quantidade da nota (4 casas). */
    public static BigDecimal precoUnitario(BigDecimal vlrTotProduto, BigDecimal qtdNotaProduto) {
        if (vlrTotProduto == null || qtdNotaProduto == null || qtdNotaProduto.signum() <= 0) return null;
        return vlrTotProduto.divide(qtdNotaProduto, 4, RoundingMode.HALF_UP);
    }

    /** Total do item: quantidade × preço, 2 casas. */
    public static BigDecimal total(BigDecimal quantidade, BigDecimal precoUnitario) {
        return quantidade.multiply(precoUnitario).setScale(2, RoundingMode.HALF_UP);
    }

    /** Observação da nota de ajuste (mesmo sentido do texto da Torre, mais curto). */
    public static String observacao(BigDecimal nunotaCompra, BigDecimal numNota, BigDecimal nuconf, String sentido) {
        String tipo = ENTRADA.equals(sentido) ? "entrada (peso a maior)" : "saída (peso a menor)";
        return "Ajuste de peso da compra " + nunotaCompra
            + (numNota != null && numNota.signum() > 0 ? " (NF " + numNota.toPlainString() + ")" : "")
            + " - " + tipo + " - conferência " + nuconf + " - addon Torre de Operação";
    }
}
