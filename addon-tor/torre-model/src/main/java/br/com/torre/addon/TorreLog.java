package br.com.torre.addon;

import br.com.sankhya.jape.wrapper.JapeFactory;
import br.com.sankhya.modelcore.auth.AuthenticationInfo;
import com.sankhya.util.TimeUtils;

import java.math.BigDecimal;
import java.util.logging.Logger;

/**
 * Log das chamadas na TORLOG. Grava na transação do serviço: o sucesso fica registrado; a falha é desfeita junto com
 * o resto (quem chama — a Torre — registra a falha do lado dela, em Pendências de Integração).
 */
public final class TorreLog {

    private static final Logger LOG = Logger.getLogger(TorreLog.class.getName());

    private TorreLog() {
    }

    public static void registrar(String servico, BigDecimal nunota, boolean sucesso, String mensagem) {
        try {
            AuthenticationInfo auth = AuthenticationInfo.getCurrent();
            JapeFactory.dao("LogTorre").create()
                .set("DHLOG", TimeUtils.getNow())
                .set("SERVICO", servico)
                .set("NUNOTA", nunota)
                .set("SUCESSO", sucesso ? "S" : "N")
                .set("MENSAGEM", mensagem == null ? null : (mensagem.length() > 4000 ? mensagem.substring(0, 4000) : mensagem))
                .set("CODUSU", auth == null ? null : auth.getUserID())
                .save();
        } catch (Exception e) {
            // O log nunca derruba a operação.
            LOG.warning("[TORRE] Falha ao gravar TORLOG: " + e.getMessage());
        }
    }
}
