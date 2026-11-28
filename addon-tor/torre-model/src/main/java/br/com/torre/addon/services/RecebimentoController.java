package br.com.torre.addon.services;

import br.com.sankhya.studio.annotations.Service;
import br.com.sankhya.studio.annotations.enums.EJBTransactionType;
import br.com.sankhya.jape.core.JapeSession;
import br.com.torre.addon.TorreLog;
import br.com.torre.addon.Versao;
import br.com.torre.addon.recebimento.DocaHelper;
import br.com.torre.addon.recebimento.LoteNotaHelper;
import br.com.torre.addon.recebimento.RecebimentoHelper;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
 *
 * {@code RecebimentoSP.doca} (contrato 4, 28/11/2026): corpo {"request": {"NUNOTA"}}. Só leitura: XML da NF-e importado
 * (volumes, pesos, placa, itens e lotes) e itens da nota (pedido, norma do palete, unidade de caixa) para a doca da Torre.
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

    public Map<String, Object> doca(Map<String, Object> request) throws Exception {
        JapeSession.SessionHandle hnd = JapeSession.open();
        hnd.setFindersMaxRows(-1);
        try {
            Map<String, Object> req = Corpo.desembrulhar(request, "NUNOTA");
            Map<String, Object> resposta = new LinkedHashMap<String, Object>();
            resposta.put("CONTRATO", Versao.CONTRATO);
            resposta.putAll(new DocaHelper().doca(Corpo.numero(req.get("NUNOTA"))));
            return resposta;
        } finally {
            JapeSession.close(hnd);
        }
    }

    /**
     * {@code RecebimentoSP.dividirLotes} (contrato 5, 28/11/2026): corpo {"request": {"NUNOTA", "LOTES": [{"CODPROD",
     * "LOTE", "QTD"}]}}. Divide o item da compra pendente pelos lotes da doca antes de a Torre enviar a conferência
     * (ver LoteNotaHelper). Resposta: PRODUTOS [{CODPROD, SITUACAO DIVIDIDO | JA_DIVIDIDO, LOTES}].
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> dividirLotes(Map<String, Object> request) throws Exception {
        JapeSession.SessionHandle hnd = JapeSession.open();
        hnd.setFindersMaxRows(-1);
        try {
            Map<String, Object> req = Corpo.desembrulhar(request, "NUNOTA");
            BigDecimal nunota = Corpo.numero(req.get("NUNOTA"));
            Map<BigDecimal, List<LoteNotaHelper.Lote>> porProduto = new LinkedHashMap<BigDecimal, List<LoteNotaHelper.Lote>>();
            Object lotes = req.get("LOTES");
            if (lotes instanceof List) {
                for (Object o : (List<Object>) lotes) {
                    if (!(o instanceof Map)) continue;
                    Map<String, Object> l = (Map<String, Object>) o;
                    BigDecimal cod = Corpo.numero(l.get("CODPROD"));
                    String lote = l.get("LOTE") == null ? "" : String.valueOf(l.get("LOTE")).trim();
                    BigDecimal qtd = Corpo.numero(l.get("QTD"));
                    if (cod == null || lote.isEmpty() || qtd == null || qtd.signum() <= 0) continue;
                    List<LoteNotaHelper.Lote> ls = porProduto.get(cod);
                    if (ls == null) porProduto.put(cod, ls = new ArrayList<LoteNotaHelper.Lote>());
                    ls.add(new LoteNotaHelper.Lote(lote, qtd));
                }
            }
            if (porProduto.isEmpty()) throw new Exception("Nenhum lote informado.");
            Map<String, Object> resposta = new LinkedHashMap<String, Object>();
            resposta.put("CONTRATO", Versao.CONTRATO);
            resposta.putAll(new LoteNotaHelper().dividir(nunota, porProduto));
            TorreLog.registrar("RecebimentoSP.dividirLotes", nunota, true, String.valueOf(resposta.get("PRODUTOS")));
            return resposta;
        } finally {
            JapeSession.close(hnd);
        }
    }
}
