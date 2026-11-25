package br.com.torre.addon.services;

import br.com.sankhya.studio.annotations.Service;
import br.com.sankhya.studio.annotations.enums.EJBTransactionType;
import br.com.sankhya.jape.core.JapeSession;
import br.com.torre.addon.TorreLog;
import br.com.torre.addon.Versao;
import br.com.torre.addon.recebimento.RecebimentoHelper;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Recebimento da compra na mesa de liberação da Torre (etapa 2, 23/11/2026).
 *
 * {@code RecebimentoSP.aprovar}: corpo {"request": {"NUNOTA", "NUCONF", "CODUSU"}} (CODUSU = aprovador, validado pela
 * Torre com usuário e senha do Sankhya). Confirma a compra em nome dele e grava TORREC. Resposta: SITUACAO
 * (APROVADO | LIBERACAO_PENDENTE), JAEXISTIA, NUREC, LIBERACOES. O ajuste de peso é outra chamada (AjustePesoSP.gerar).
 *
 * {@code RecebimentoSP.registrarDevolucao}: corpo {"request": {"NUNOTA", "NUCONF", "CODUSU", "MOTIVO"}}, depois que a
 * Torre pediu a recontagem pelo serviço nativo.
 */
@Service(serviceName = "RecebimentoSP", transactionType = EJBTransactionType.Required)
public class RecebimentoController {

    public Map<String, Object> aprovar(Map<String, Object> request) throws Exception {
        JapeSession.SessionHandle hnd = JapeSession.open();
        hnd.setFindersMaxRows(-1);
        try {
        Map<String, Object> req = Corpo.desembrulhar(request, "NUNOTA");
        BigDecimal nunota = Corpo.numero(req.get("NUNOTA"));
        BigDecimal nuconf = Corpo.numero(req.get("NUCONF"));
        BigDecimal codusu = Corpo.numero(req.get("CODUSU"));

        RecebimentoHelper.Aprovacao a = new RecebimentoHelper().aprovar(nunota, nuconf, codusu);
        if (!a.jaExistia) {
            TorreLog.registrar("RecebimentoSP.aprovar", nunota, "APROVADO".equals(a.situacao),
                "conferência " + nuconf + ", aprovador " + codusu + ": " + a.situacao
                    + (a.liberacoes.isEmpty() ? "" : " " + a.liberacoes));
        }

        Map<String, Object> resposta = new LinkedHashMap<String, Object>();
        resposta.put("CONTRATO", Versao.CONTRATO);
        resposta.put("SITUACAO", a.situacao);
        resposta.put("JAEXISTIA", a.jaExistia ? "S" : "N");
        resposta.put("NUREC", a.nurec);
        resposta.put("LIBERACOES", a.liberacoes);
        return resposta;
        } finally {
            JapeSession.close(hnd);
        }
    }

    public Map<String, Object> registrarDevolucao(Map<String, Object> request) throws Exception {
        JapeSession.SessionHandle hnd = JapeSession.open();
        hnd.setFindersMaxRows(-1);
        try {
        Map<String, Object> req = Corpo.desembrulhar(request, "NUNOTA");
        BigDecimal nunota = Corpo.numero(req.get("NUNOTA"));
        BigDecimal nuconf = Corpo.numero(req.get("NUCONF"));
        BigDecimal codusu = Corpo.numero(req.get("CODUSU"));
        String motivo = req.get("MOTIVO") == null ? null : req.get("MOTIVO").toString();

        BigDecimal nurec = new RecebimentoHelper().registrarDevolucao(nunota, nuconf, codusu, motivo);
        TorreLog.registrar("RecebimentoSP.registrarDevolucao", nunota, true,
            "conferência " + nuconf + " devolvida para recontagem por " + codusu + ": " + motivo);

        Map<String, Object> resposta = new LinkedHashMap<String, Object>();
        resposta.put("CONTRATO", Versao.CONTRATO);
        resposta.put("NUREC", nurec);
        return resposta;
        } finally {
            JapeSession.close(hnd);
        }
    }
}
