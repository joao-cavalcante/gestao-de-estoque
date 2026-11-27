package br.com.torre.addon.jobs;

import br.com.sankhya.jape.core.JapeSession;
import br.com.sankhya.modelcore.auth.AuthenticationInfo;
import br.com.sankhya.studio.annotations.Job;
import br.com.sankhya.studio.annotations.enums.EJBTransactionType;
import br.com.sankhya.studio.stereotypes.IJob;
import br.com.torre.addon.TorreLog;
import br.com.torre.addon.enderecamento.EnderecamentoHelper;

import java.math.BigDecimal;
import java.util.List;
import java.util.logging.Logger;

/**
 * A cada minuto gera as tarefas de armazenagem das compras aprovadas (TORREC 'AP') da empresa com endereçamento
 * ligado (TORCFG.ENDATIVO = 'S'), depois que o ajuste de peso foi gerado. Uma transação por compra; falha vira
 * TORREC.ENDSIT 'ER' e é tentada de novo em 30 min. Etapa 3a, 27/11/2026.
 */
@Job(serviceName = "EnderecamentoJobSP", transactionType = EJBTransactionType.NotSupported, frequency = "&60000")
public class EnderecamentoJob extends IJob {

    private static final Logger LOG = Logger.getLogger(EnderecamentoJob.class.getName());
    private static final int POR_RODADA = 10;

    @Override
    public void onSchedule() {
        JapeSession.SessionHandle hnd = null;
        AuthenticationInfo antes = AuthenticationInfo.getCurrentOrNull();
        try {
            hnd = JapeSession.open();
            hnd.setFindersMaxRows(-1);
            if (antes == null) new AuthenticationInfo("SUP", BigDecimal.ZERO, BigDecimal.ZERO, Integer.valueOf(0)).makeCurrent();
            final List<BigDecimal[]> fila = EnderecamentoHelper.aGerar(POR_RODADA);
            for (final BigDecimal[] nc : fila) {
                try {
                    hnd.execWithTX(new JapeSession.TXBlock() {
                        public void doWithTx() throws Exception {
                            int n = new EnderecamentoHelper().gerarTarefas(nc[0], nc[1], null);
                            if (n > 0) TorreLog.registrar("EnderecamentoJob", nc[0], true, "conferência " + nc[1] + ": " + n + " tarefa(s)");
                        }
                    });
                } catch (final Exception e) {
                    final String msg = String.valueOf(e.getMessage());
                    LOG.warning("[TORRE] Tarefas de armazenagem da compra " + nc[0] + " falharam: " + msg);
                    try {
                        hnd.execWithTX(new JapeSession.TXBlock() {
                            public void doWithTx() throws Exception {
                                EnderecamentoHelper.registrarFalha(nc[0], nc[1], msg);
                                TorreLog.registrar("EnderecamentoJob", nc[0], false, "conferência " + nc[1] + ": " + msg);
                            }
                        });
                    } catch (Exception e2) {
                        LOG.warning("[TORRE] Falha ao registrar o erro do endereçamento da compra " + nc[0] + ": " + e2.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            LOG.warning("[TORRE] EnderecamentoJob: " + e.getMessage());
        } finally {
            if (antes == null) AuthenticationInfo.unregistry();
            if (hnd != null) JapeSession.close(hnd);
        }
    }
}
