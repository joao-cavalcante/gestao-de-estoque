package br.com.torre.addon;

/** Versão do contrato entre a Torre e o addon TOR (a Torre confere antes de chamar os serviços). */
public final class Versao {
    public static final String CONTRATO = "4"; // 4 = RecebimentoSP.doca (leitura da doca), 28/11/2026; 3 = endereçamento (EnderecamentoSP + job), 27/11/2026; 2 = plano B, 25/11/2026

    private Versao() {
    }
}
