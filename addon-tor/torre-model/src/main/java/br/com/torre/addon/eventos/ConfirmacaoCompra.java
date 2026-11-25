package br.com.torre.addon.eventos;

import br.com.sankhya.jape.dao.JdbcWrapper;
import br.com.sankhya.jape.sql.NativeSql;
import br.com.sankhya.modelcore.auth.AuthenticationInfo;
import br.com.torre.addon.ajuste.AjustePesoRegras;
import br.com.torre.addon.recebimento.RecebimentoRegras;
import com.sankhya.util.TimeUtils;

import java.math.BigDecimal;

/**
 * O que a regra (ConfirmacaoCompraRegra) faz na confirmação da compra, na mesma transação da confirmação. Só UPDATEs
 * em linhas que a Torre registrou: compra que a Torre não conferiu não tem TORREC 'PE' nem TORPPV 'P' e nada acontece.
 */
final class ConfirmacaoCompra {

    private final JdbcWrapper jdbc;

    ConfirmacaoCompra(JdbcWrapper jdbc) {
        this.jdbc = jdbc;
    }

    static String texto(Object v) {
        return v == null ? null : v.toString().trim();
    }

    /** STATUSNOTA passou de outro valor para 'L' nesta alteração. */
    static boolean virouConfirmada(Object antigo, Object novo) {
        return "L".equals(texto(novo)) && !"L".equals(texto(antigo));
    }

    void aoConfirmar(BigDecimal nunota, BigDecimal nuconf) throws Exception {
        if (nunota == null || nuconf == null) return;
        AuthenticationInfo auth = AuthenticationInfo.getCurrentOrNull();
        BigDecimal codusu = auth == null ? null : auth.getUserID();

        // Recebimento: quem confirmou (o Aprovar sobrescreve depois com o aprovador e a origem 'T').
        NativeSql rec = new NativeSql(jdbc);
        rec.setNamedParameter("U", codusu);
        rec.setNamedParameter("DH", TimeUtils.getNow());
        rec.setNamedParameter("O", RecebimentoRegras.ORIGEM_SANKHYA);
        rec.setNamedParameter("N", nunota);
        rec.setNamedParameter("C", nuconf);
        rec.executeUpdate("UPDATE TORREC SET SITUACAO = '" + RecebimentoRegras.APROVADO + "', CODUSUAPROV = :U, DHAPROV = :DH, "
            + "ORIGEM = :O WHERE NUNOTA = :N AND NUCONF = :C AND SITUACAO = '" + RecebimentoRegras.PENDENTE + "'");

        // Ajuste de peso: libera para o job gerar (fora desta transação).
        NativeSql ppv = new NativeSql(jdbc);
        ppv.setNamedParameter("N", nunota);
        ppv.setNamedParameter("C", nuconf);
        ppv.executeUpdate("UPDATE TORPPV SET STATUS = '" + AjustePesoRegras.STATUS_A_GERAR + "' WHERE NUNOTA = :N AND NUCONF = :C "
            + "AND STATUS = '" + AjustePesoRegras.STATUS_PENDENTE + "'");
    }
}
