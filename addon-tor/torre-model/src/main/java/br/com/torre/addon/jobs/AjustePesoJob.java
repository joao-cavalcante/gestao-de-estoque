package br.com.torre.addon.jobs;

import br.com.sankhya.jape.core.JapeSession;
import br.com.sankhya.modelcore.auth.AuthenticationInfo;
import br.com.sankhya.studio.annotations.Job;
import br.com.sankhya.studio.annotations.enums.EJBTransactionType;
import br.com.sankhya.studio.stereotypes.IJob;
import br.com.torre.addon.TorreLog;
import br.com.torre.addon.ajuste.AjustePesoHelper;

import java.math.BigDecimal;
import java.util.List;
import java.util.logging.Logger;

/**
 * Gera as notas de ajuste de peso liberadas pela confirmação da compra (TORPPV 'A'), a cada minuto — plano B de
 * 25/11/2026: o ajuste sai mesmo que a compra seja confirmada pela Central de Notas e mesmo com a Torre fora do ar.
 *
 * Uma transação por compra (execWithTX): falha numa não desfaz as outras; a falha conta tentativa (outra transação) e,
 * na 5ª, a linha vira 'E' (a Torre mostra em Pendências). A geração é a mesma do serviço AjustePesoSP.gerar.
 */
@Job(serviceName = "AjustePesoJobSP", transactionType = EJBTransactionType.NotSupported, frequency = "&60000")
public class AjustePesoJob extends IJob {

    private static final Logger LOG = Logger.getLogger(AjustePesoJob.class.getName());
    private static final int POR_RODADA = 10;

    @Override
    public void onSchedule() {
        JapeSession.SessionHandle hnd = null;
        AuthenticationInfo antes = AuthenticationInfo.getCurrentOrNull();
        try {
            hnd = JapeSession.open();
            hnd.setFindersMaxRows(-1);
            // Sem usuário na tarefa agendada: roda como o usuário do sistema (0) para CACHelper/confirmação.
            if (antes == null) new AuthenticationInfo("SUP", BigDecimal.ZERO, BigDecimal.ZERO, Integer.valueOf(0)).makeCurrent();
            List<BigDecimal[]> fila = AjustePesoHelper.aGerar(POR_RODADA);
            for (final BigDecimal[] nc : fila) {
                try {
                    hnd.execWithTX(new JapeSession.TXBlock() {
                        public void doWithTx() throws Exception {
                            new AjustePesoHelper().gerarPendente(nc[0], nc[1]);
                        }
                    });
                } catch (final Exception e) {
                    final String msg = String.valueOf(e.getMessage());
                    LOG.warning("[TORRE] Ajuste de peso da compra " + nc[0] + " falhou: " + msg);
                    try {
                        hnd.execWithTX(new JapeSession.TXBlock() {
                            public void doWithTx() throws Exception {
                                AjustePesoHelper.registrarFalha(nc[0], nc[1], msg);
                                TorreLog.registrar("AjustePesoJob", nc[0], false, "conferência " + nc[1] + ": " + msg);
                            }
                        });
                    } catch (Exception e2) {
                        LOG.warning("[TORRE] Falha ao registrar o erro do ajuste da compra " + nc[0] + ": " + e2.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            LOG.warning("[TORRE] AjustePesoJob: " + e.getMessage());
        } finally {
            if (antes == null) AuthenticationInfo.unregistry();
            if (hnd != null) JapeSession.close(hnd);
        }
    }
}
