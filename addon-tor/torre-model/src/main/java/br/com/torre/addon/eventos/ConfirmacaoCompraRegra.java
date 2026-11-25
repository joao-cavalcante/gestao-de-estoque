package br.com.torre.addon.eventos;

import br.com.sankhya.jape.dao.JdbcWrapper;
import br.com.sankhya.jape.vo.DynamicVO;
import br.com.sankhya.jape.vo.PrePersistEntityState;
import br.com.sankhya.modelcore.comercial.ContextoRegra;
import br.com.sankhya.modelcore.comercial.Regra;
import br.com.sankhya.modelcore.util.EntityFacadeFactory;
import br.com.sankhya.studio.annotations.hooks.BusinessRule;

import java.util.logging.Logger;

/**
 * Confirmação da COMPRA no Sankhya, por qualquer caminho (Central de Notas ou Aprovar da Torre) — plano B, 25/11/2026.
 *
 * Por que regra de negócio e não listener da entidade: a confirmação grava STATUSNOTA = 'L' pela procedure
 * STP_CONFIRMANOTA2 (JDBC), não pela entidade — o afterUpdate do CabecalhoNota nunca vê a troca de status
 * (CONFIRMADO no bytecode de ConfirmacaoNotaHelper.confirmaNota, mge-modelcore 4.35). Depois da procedure o Sankhya
 * chama BarramentoRegra.executeAfter, que roda as regras personalizadas (parâmetro com.modulo.java.regras.central) em
 * afterUpdate, com a nota já relida em 'L'.
 *
 * Só age na compra que a Torre conferiu e registrou (TORREC 'PE' / TORPPV 'P' da conferência atual); em qualquer outro
 * afterUpdate da Central (nota não confirmada, outra TOP, item) não faz nada. Nunca lança exceção.
 */
@BusinessRule(description = "Torre de Operacao - registra a confirmacao da compra conferida pela Torre")
public class ConfirmacaoCompraRegra implements Regra {

    private static final Logger LOG = Logger.getLogger(ConfirmacaoCompraRegra.class.getName());

    @Override
    public void afterUpdate(ContextoRegra ctx) {
        try {
            PrePersistEntityState st = ctx == null ? null : ctx.getPrePersistEntityState();
            if (st == null || st.getDao() == null || !"CabecalhoNota".equals(st.getDao().getEntityName())) return;
            DynamicVO vo = st.getNewVO();
            if (vo == null) return;
            if (!"L".equals(ConfirmacaoCompra.texto(vo.asString("STATUSNOTA")))) return;
            if (!"C".equals(ConfirmacaoCompra.texto(vo.asString("TIPMOV")))) return;
            JdbcWrapper jdbc = EntityFacadeFactory.getDWFFacade().getJdbcWrapper();
            jdbc.openSession();
            try {
                new ConfirmacaoCompra(jdbc).aoConfirmar(vo.asBigDecimal("NUNOTA"), vo.asBigDecimal("NUCONFATUAL"));
            } finally {
                JdbcWrapper.closeSession(jdbc);
            }
        } catch (Throwable t) {
            LOG.warning("[TORRE] Regra de confirmação da compra não registrou: " + t);
        }
    }

    @Override public void beforeInsert(ContextoRegra ctx) { }
    @Override public void beforeUpdate(ContextoRegra ctx) { }
    @Override public void beforeDelete(ContextoRegra ctx) { }
    @Override public void afterInsert(ContextoRegra ctx) { }
    @Override public void afterDelete(ContextoRegra ctx) { }
}
