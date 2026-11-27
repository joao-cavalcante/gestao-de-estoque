package br.com.torre.addon.enderecamento;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Regras puras do endereçamento (etapa 3a, 27/11/2026) — testadas em EnderecamentoRegrasTest.
 * Desenho: sankhya-hub docs/projetos/torre-enderecamento-entrada.md §5 e §11.
 */
public final class EnderecamentoRegras {

    public static final String DOCA = "DOCA";
    public static final String PICKING = "PICKING";
    public static final String PULMAO = "PULMAO";

    /** TORTAR.SITUACAO */
    public static final String ABERTA = "AB";
    public static final String EM_EXECUCAO = "EX";
    public static final String CONCLUIDA = "CO";
    public static final String CANCELADA = "CA";

    /** TORREC.ENDSIT */
    public static final String TAREFAS_GERADAS = "GE";
    public static final String ERRO = "ER";

    private EnderecamentoRegras() {
    }

    private static BigDecimal z(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    /** Chave do produto no endereço: CODPROD|CONTROLE (controle em branco = ""). */
    public static String chave(Object codprod, String controle) {
        String c = controle == null ? "" : controle.trim();
        return String.valueOf(codprod).trim() + "|" + c;
    }

    /**
     * Quantidade da tarefa de um produto da compra: o que está fisicamente na doca ([fisico]: balança quando houve
     * ajuste de peso, senão o conferido) limitado ao que entrou no estoque ([entrou]: nota + ajustes gerados). A sobra
     * que vira pedido complementar fica na doca até o complemento ser confirmado (decisão de 27/11/2026).
     */
    public static BigDecimal quantidadeTarefa(BigDecimal fisico, BigDecimal entrou) {
        BigDecimal q = z(fisico).min(z(entrou));
        return q.signum() > 0 ? q : BigDecimal.ZERO;
    }

    /** Endereço com a ocupação atual. */
    public static final class Endereco {
        public final long codend;
        public final String codigo;
        public final String tipo;
        public final BigDecimal capQtd;
        public final boolean bloqueado;
        public final boolean ativo;
        public BigDecimal ocupado = BigDecimal.ZERO;
        public final Set<String> produtos = new HashSet<String>();
        /** Tipo de separação do setor do endereço (1 Seco, 2 Refrigerado, 3 Congelado); null = setor aceita qualquer. */
        public Integer tipoSep;

        public Endereco(long codend, String codigo, String tipo, BigDecimal capQtd, boolean bloqueado, boolean ativo) {
            this.codend = codend;
            this.codigo = codigo;
            this.tipo = tipo == null ? "" : tipo.trim().toUpperCase();
            this.capQtd = capQtd;
            this.bloqueado = bloqueado;
            this.ativo = ativo;
        }

        /** Espaço livre (null = sem capacidade cadastrada = sem limite). */
        public BigDecimal livre() {
            return capQtd == null || capQtd.signum() <= 0 ? null : capQtd.subtract(z(ocupado));
        }

        boolean temEspaco() {
            BigDecimal l = livre();
            return l == null || l.signum() > 0;
        }

        boolean cabe(BigDecimal qtd) {
            BigDecimal l = livre();
            return l == null || l.compareTo(z(qtd)) >= 0;
        }

        boolean vazio() {
            return produtos.isEmpty();
        }
    }

    private static boolean usavel(Endereco e) {
        return e.ativo && !e.bloqueado && !DOCA.equals(e.tipo);
    }

    /** Setor do endereço aceita o produto? (sem tipo no endereço ou no produto = aceita). */
    static boolean doSetor(Endereco e, Integer tipoSepProduto) {
        return e.tipoSep == null || tipoSepProduto == null || e.tipoSep.intValue() == tipoSepProduto.intValue();
    }

    public static Endereco sugerir(List<Endereco> enderecos, String chave, BigDecimal qtd) {
        return sugerir(enderecos, chave, qtd, null);
    }

    /**
     * Endereço sugerido para guardar [qtd] do produto [chave] (revisão de 27/11/2026, depois da 1ª simulação):
     *  1. picking que já tem o produto, com espaço;
     *  2. produto SEM picking em lugar nenhum: picking vazio do setor em que a quantidade cabe (1ª entrada);
     *  3. pulmão com o mesmo produto, com espaço;
     *  4. pulmão vazio — primeiro os em que a quantidade cabe inteira;
     *  5. nenhum → null ("sem endereço": o operador escolhe e informa o motivo).
     * Só endereços do setor do produto ([tipoSepProduto]: 1 Seco, 2 Refrigerado, 3 Congelado). A ocupação já inclui o
     * que está reservado para outras tarefas abertas (quem chama soma). Bloqueado, inativo e doca nunca. Empate: menor código.
     */
    public static Endereco sugerir(List<Endereco> enderecos, String chave, BigDecimal qtd, Integer tipoSepProduto) {
        List<Endereco> l = new ArrayList<Endereco>(enderecos);
        Collections.sort(l, new Comparator<Endereco>() {
            public int compare(Endereco a, Endereco b) {
                return String.valueOf(a.codigo).compareTo(String.valueOf(b.codigo));
            }
        });
        List<Endereco> ok = new ArrayList<Endereco>();
        for (Endereco e : l) if (usavel(e) && doSetor(e, tipoSepProduto)) ok.add(e);
        boolean temPicking = false;
        for (Endereco e : l) if (PICKING.equals(e.tipo) && e.produtos.contains(chave)) temPicking = true;
        for (Endereco e : ok) if (PICKING.equals(e.tipo) && e.produtos.contains(chave) && e.temEspaco()) return e;
        if (!temPicking) for (Endereco e : ok) if (PICKING.equals(e.tipo) && e.vazio() && e.cabe(qtd)) return e;
        for (Endereco e : ok) if (PULMAO.equals(e.tipo) && e.produtos.contains(chave) && e.temEspaco()) return e;
        for (Endereco e : ok) if (PULMAO.equals(e.tipo) && e.vazio() && e.cabe(qtd)) return e;
        for (Endereco e : ok) if (PULMAO.equals(e.tipo) && e.vazio() && e.temEspaco()) return e;
        return null;
    }

    /** Por que não dá para guardar o produto [chave] em [e] (null = pode). Pulmão não mistura produto/controle. */
    public static String recusaDestino(Endereco e, String chave) {
        if (e == null) return "Endereço não encontrado nesta empresa/local.";
        if (!e.ativo) return "Endereço " + e.codigo + " está inativo.";
        if (e.bloqueado) return "Endereço " + e.codigo + " está bloqueado.";
        if (DOCA.equals(e.tipo)) return "A doca não é destino de armazenagem.";
        if (PULMAO.equals(e.tipo) && !e.vazio() && !e.produtos.contains(chave)) {
            return "Pulmão " + e.codigo + " já tem outro produto — não mistura.";
        }
        return null;
    }

    /** Reserva [qtd] de [chave] em [e] (sugestão de outra tarefa aberta): conta como ocupado na próxima sugestão. */
    public static void reservar(Endereco e, String chave, BigDecimal qtd) {
        if (e == null) return;
        e.ocupado = z(e.ocupado).add(z(qtd));
        e.produtos.add(chave);
    }

    /** Quanto ainda falta guardar da tarefa. */
    public static BigDecimal falta(BigDecimal qtd, BigDecimal feita) {
        BigDecimal f = z(qtd).subtract(z(feita));
        return f.signum() > 0 ? f : BigDecimal.ZERO;
    }
}
