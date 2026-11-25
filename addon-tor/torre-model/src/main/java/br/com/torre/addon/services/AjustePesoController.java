package br.com.torre.addon.services;

import br.com.sankhya.studio.annotations.Service;
import br.com.sankhya.studio.annotations.enums.EJBTransactionType;
import br.com.sankhya.jape.core.JapeSession;
import br.com.torre.addon.TorreLog;
import br.com.torre.addon.Versao;
import br.com.torre.addon.ajuste.AjustePesoHelper;
import br.com.torre.addon.ajuste.AjustePesoRegras;
import br.com.torre.addon.recebimento.RecebimentoHelper;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Ajuste de peso da compra (etapa 1 do plano "Entrada com endereçamento", 19/11/2026).
 *
 * Chamada pela Torre no gateway: {@code ?serviceName=AjustePesoSP.gerar&outputType=json} (Bearer), corpo
 * {@code {"requestBody": {"NUNOTA": 88093, "NUCONF": 5501, "ITENS": [{"CODPROD": 3243, "CONTROLE": "", "QTDNOTA": 7463.14,
 * "QTDBALANCA": 7437.84, "QTDDIF": -25.3}]}}}. A Torre continua decidindo a faixa (tolerância) na conferência; aqui só
 * se gera o que ela mandou.
 *
 * Uma transação por chamada (EJB Required): falhou em qualquer ponto, lança a exceção e o Sankhya desfaz tudo — a nota
 * de compra nunca fica com ajuste pela metade. Repetir a chamada da mesma conferência devolve as notas já geradas.
 */
@Service(serviceName = "AjustePesoSP", transactionType = EJBTransactionType.Required)
public class AjustePesoController {

    public Map<String, Object> gerar(Map<String, Object> request) throws Exception {
        JapeSession.SessionHandle hnd = JapeSession.open();
        hnd.setFindersMaxRows(-1);
        try {
        Map<String, Object> req = Corpo.desembrulhar(request, "NUNOTA");
        BigDecimal nunota = Corpo.numero(req.get("NUNOTA"));
        BigDecimal nuconf = Corpo.numero(req.get("NUCONF"));
        List<AjustePesoRegras.Diferenca> itens = new ArrayList<AjustePesoRegras.Diferenca>();
        for (Map<String, Object> i : Corpo.lista(req.get("ITENS"))) {
            BigDecimal codprod = Corpo.numero(i.get("CODPROD"));
            itens.add(new AjustePesoRegras.Diferenca(
                codprod == null ? 0 : codprod.intValue(),
                i.get("CONTROLE") == null ? null : i.get("CONTROLE").toString(),
                Corpo.numero(i.get("QTDNOTA")),
                Corpo.numero(i.get("QTDBALANCA")),
                Corpo.numero(i.get("QTDDIF"))));
        }

        List<AjustePesoHelper.Resultado> notas = new AjustePesoHelper().gerar(nunota, nuconf, itens);

        List<Map<String, Object>> saida = new ArrayList<Map<String, Object>>();
        StringBuilder resumo = new StringBuilder();
        boolean novo = false;
        for (AjustePesoHelper.Resultado r : notas) {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("SENTIDO", r.sentido);
            m.put("NUNOTAAJ", r.nunotaAjuste);
            m.put("JAEXISTIA", r.jaExistia ? "S" : "N");
            saida.add(m);
            resumo.append(r.sentido).append('=').append(r.nunotaAjuste).append(' ');
            novo |= !r.jaExistia;
        }
        if (novo) TorreLog.registrar("AjustePesoSP.gerar", nunota, true, "conferência " + nuconf + ": " + resumo.toString().trim());

        Map<String, Object> resposta = new LinkedHashMap<String, Object>();
        resposta.put("CONTRATO", Versao.CONTRATO);
        resposta.put("NOTAS", saida);
        return resposta;
        } finally {
            JapeSession.close(hnd);
        }
    }

    /**
     * Plano B (25/11/2026): a Torre chama ao finalizar a conferência da compra — mesmo corpo do gerar (ITENS pode vir
     * vazio). Grava as diferenças como pendentes (TORPPV 'P') e o recebimento como pendente (TORREC 'PE'); a confirmação
     * da compra (evento) libera e o job AjustePesoJob gera.
     */
    public Map<String, Object> registrar(Map<String, Object> request) throws Exception {
        JapeSession.SessionHandle hnd = JapeSession.open();
        hnd.setFindersMaxRows(-1);
        try {
            Map<String, Object> req = Corpo.desembrulhar(request, "NUNOTA");
            BigDecimal nunota = Corpo.numero(req.get("NUNOTA"));
            BigDecimal nuconf = Corpo.numero(req.get("NUCONF"));
            boolean gravou = new AjustePesoHelper().registrar(nunota, nuconf, itens(req));
            new RecebimentoHelper().registrarPendente(nunota, nuconf);
            Map<String, Object> resposta = new LinkedHashMap<String, Object>();
            resposta.put("CONTRATO", Versao.CONTRATO);
            resposta.put("REGISTRADO", gravou ? "S" : "N");
            return resposta;
        } finally {
            JapeSession.close(hnd);
        }
    }

    /** Gera o ajuste já liberado ('A') de uma conferência — o mesmo que o job faz; a Torre usa como reserva. */
    public Map<String, Object> gerarPendente(Map<String, Object> request) throws Exception {
        JapeSession.SessionHandle hnd = JapeSession.open();
        hnd.setFindersMaxRows(-1);
        try {
            Map<String, Object> req = Corpo.desembrulhar(request, "NUNOTA");
            List<AjustePesoHelper.Resultado> notas = new AjustePesoHelper().gerarPendente(Corpo.numero(req.get("NUNOTA")), Corpo.numero(req.get("NUCONF")));
            List<Map<String, Object>> saida = new ArrayList<Map<String, Object>>();
            for (AjustePesoHelper.Resultado r : notas) {
                Map<String, Object> m = new LinkedHashMap<String, Object>();
                m.put("SENTIDO", r.sentido);
                m.put("NUNOTAAJ", r.nunotaAjuste);
                m.put("JAEXISTIA", r.jaExistia ? "S" : "N");
                saida.add(m);
            }
            Map<String, Object> resposta = new LinkedHashMap<String, Object>();
            resposta.put("CONTRATO", Versao.CONTRATO);
            resposta.put("NOTAS", saida);
            return resposta;
        } finally {
            JapeSession.close(hnd);
        }
    }

    private static List<AjustePesoRegras.Diferenca> itens(Map<String, Object> req) {
        List<AjustePesoRegras.Diferenca> itens = new ArrayList<AjustePesoRegras.Diferenca>();
        for (Map<String, Object> i : Corpo.lista(req.get("ITENS"))) {
            BigDecimal codprod = Corpo.numero(i.get("CODPROD"));
            itens.add(new AjustePesoRegras.Diferenca(
                codprod == null ? 0 : codprod.intValue(),
                i.get("CONTROLE") == null ? null : i.get("CONTROLE").toString(),
                Corpo.numero(i.get("QTDNOTA")),
                Corpo.numero(i.get("QTDBALANCA")),
                Corpo.numero(i.get("QTDDIF"))));
        }
        return itens;
    }

    /** A Torre confere se o addon está instalado e a versão do contrato antes de trocar de caminho. */
    public Map<String, Object> versao(Map<String, Object> request) {
        Map<String, Object> resposta = new LinkedHashMap<String, Object>();
        resposta.put("CONTRATO", Versao.CONTRATO);
        return resposta;
    }
}
