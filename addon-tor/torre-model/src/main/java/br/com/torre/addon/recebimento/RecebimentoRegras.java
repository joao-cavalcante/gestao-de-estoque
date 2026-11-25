package br.com.torre.addon.recebimento;

import java.math.BigDecimal;

/**
 * Regras puras do recebimento (sem Sankhya) - testadas em RecebimentoRegrasTest. Etapa 2 do plano "Entrada com
 * endereçamento" (23/11/2026).
 */
public final class RecebimentoRegras {

    /** TORREC.SITUACAO */
    public static final String APROVADO = "AP";
    public static final String DEVOLVIDO = "RC";
    /** Conferida pela Torre, aguardando a confirmação da compra (plano B, 25/11/2026). */
    public static final String PENDENTE = "PE";

    /** TORREC.ORIGEM: quem confirmou a compra — pelo Aprovar da Torre ou direto no Sankhya (evento). */
    public static final String ORIGEM_TORRE = "T";
    public static final String ORIGEM_SANKHYA = "S";

    private RecebimentoRegras() {
    }

    /**
     * Quem pode aprovar/devolver: lista de CODUSU na TORCFG.USUAPROVREC (separados por vírgula, ponto e vírgula ou
     * espaço). Lista vazia = ninguém (decisão 23/11/2026: só usuários cadastrados).
     */
    public static boolean podeAprovar(String lista, BigDecimal codusu) {
        if (lista == null || codusu == null) return false;
        for (String parte : lista.split("[,;\\s]+")) {
            String p = parte.trim();
            if (p.isEmpty()) continue;
            try {
                if (new BigDecimal(p).compareTo(codusu) == 0) return true;
            } catch (NumberFormatException ignorado) {
                // item inválido na lista: ignora
            }
        }
        return false;
    }

    /** Erro de validação da conferência para aprovar/devolver, ou null se pode seguir. */
    public static String validarConferencia(BigDecimal nunota, BigDecimal nuconf, BigDecimal nuconfAtual, String statusConf) {
        if (nunota == null || nunota.signum() <= 0) return "NUNOTA não informado.";
        if (nuconf == null || nuconf.signum() <= 0) return "NUCONF não informado.";
        if (nuconfAtual == null || nuconfAtual.compareTo(nuconf) != 0) {
            return "Conferência " + nuconf + " não é a atual da compra " + nunota + " (atual: " + nuconfAtual
                + ") - a conferência foi refeita.";
        }
        String s = statusConf == null ? "" : statusConf.trim();
        if ("C".equals(s)) return "A conferência " + nuconf + " está aguardando liberação de corte - libere o corte antes.";
        if (!"F".equals(s) && !"D".equals(s)) {
            return "A conferência " + nuconf + " não está finalizada (status " + (s.isEmpty() ? "vazio" : s) + ").";
        }
        return null;
    }
}
