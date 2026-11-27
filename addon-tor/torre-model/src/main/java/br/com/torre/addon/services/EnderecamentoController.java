package br.com.torre.addon.services;

import br.com.sankhya.jape.core.JapeSession;
import br.com.sankhya.studio.annotations.Service;
import br.com.sankhya.studio.annotations.enums.EJBTransactionType;
import br.com.torre.addon.TorreLog;
import br.com.torre.addon.Versao;
import br.com.torre.addon.enderecamento.EnderecamentoHelper;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Endereçamento (etapa 3a, 27/11/2026) — chamado pela Torre no gateway, módulo "torre", corpo {"request": {...}}.
 *
 *  - publicarEnderecos {CODEMP, CODLOCAL, ENDERECOS: [{ID, CODIGO, TIPO, SETOR, ESTRUTURA, MODULO, NIVEL, CAPQTD,
 *    CAPPESO, BLOQUEADO}]} — ao publicar a Planta. Resposta: NOVOS, ALTERADOS, DESATIVADOS, COMSALDO.
 *  - tarefas {EMPRESAS: "1,2"} — abertas/em execução. Resposta: TAREFAS.
 *  - iniciar {NUTAR, CODUSU, EXCLUIR?: "R01-M01-N01,..."} — operador começou (ou achou o sugerido cheio); recalcula a
 *    sugestão sem os EXCLUIR. Resposta: TAREFA.
 *  - armazenar {NUTAR, ENDERECO, QTD, MOTIVO, CODUSU} — doca → endereço, numa transação. Resposta: TAREFA.
 *  - gerarTarefas {NUNOTA, NUCONF} — reserva do job (botão "gerar agora"). Resposta: CRIADAS.
 *  - situacaoCompras {NUNOTAS: "1,2"} — aba Armazenagem. Resposta: COMPRAS.
 *  - saldos {CODEMP, CODLOCAL} — saldo por endereço (Planta). Resposta: SALDOS.
 * Uma transação por chamada (Required): falhou, o Sankhya desfaz tudo.
 */
@Service(serviceName = "EnderecamentoSP", transactionType = EJBTransactionType.Required)
public class EnderecamentoController {

    public Map<String, Object> publicarEnderecos(Map<String, Object> request) throws Exception {
        JapeSession.SessionHandle hnd = JapeSession.open();
        hnd.setFindersMaxRows(-1);
        try {
            Map<String, Object> req = Corpo.desembrulhar(request, "CODEMP");
            List<EnderecamentoHelper.EnderecoPlanta> lista = new ArrayList<EnderecamentoHelper.EnderecoPlanta>();
            for (Map<String, Object> e : Corpo.lista(req.get("ENDERECOS"))) {
                EnderecamentoHelper.EnderecoPlanta p = new EnderecamentoHelper.EnderecoPlanta();
                p.id = texto(e.get("ID"));
                p.codigo = texto(e.get("CODIGO"));
                p.tipo = texto(e.get("TIPO"));
                p.setor = texto(e.get("SETOR"));
                p.estrutura = texto(e.get("ESTRUTURA"));
                p.modulo = Corpo.numero(e.get("MODULO"));
                p.nivel = Corpo.numero(e.get("NIVEL"));
                p.capQtd = tresCasas(Corpo.numero(e.get("CAPQTD")));
                p.capPeso = tresCasas(Corpo.numero(e.get("CAPPESO")));
                p.bloqueado = "S".equals(texto(e.get("BLOQUEADO")));
                p.tipoSep = Corpo.numero(e.get("TIPOSEP"));
                lista.add(p);
            }
            BigDecimal codemp = Corpo.numero(req.get("CODEMP"));
            Map<String, Object> r = new EnderecamentoHelper().publicar(codemp, Corpo.numero(req.get("CODLOCAL")), lista);
            TorreLog.registrar("EnderecamentoSP.publicarEnderecos", null, true, "empresa " + codemp + " local " + req.get("CODLOCAL") + ": " + r);
            r.put("CONTRATO", Versao.CONTRATO);
            return r;
        } finally {
            JapeSession.close(hnd);
        }
    }

    public Map<String, Object> tarefas(Map<String, Object> request) throws Exception {
        JapeSession.SessionHandle hnd = JapeSession.open();
        hnd.setFindersMaxRows(-1);
        try {
            Map<String, Object> req = Corpo.desembrulhar(request, "EMPRESAS");
            Map<String, Object> r = resposta();
            r.put("TAREFAS", new EnderecamentoHelper().tarefasAbertas(numeros(req.get("EMPRESAS"))));
            return r;
        } finally {
            JapeSession.close(hnd);
        }
    }

    public Map<String, Object> iniciar(Map<String, Object> request) throws Exception {
        JapeSession.SessionHandle hnd = JapeSession.open();
        hnd.setFindersMaxRows(-1);
        try {
            Map<String, Object> req = Corpo.desembrulhar(request, "NUTAR");
            Map<String, Object> r = resposta();
            List<String> excluir = new ArrayList<String>();
            if (req.get("EXCLUIR") != null) for (String x : req.get("EXCLUIR").toString().split("[,; ]+")) if (!x.trim().isEmpty()) excluir.add(x.trim());
            r.put("TAREFA", new EnderecamentoHelper().iniciar(Corpo.numero(req.get("NUTAR")), Corpo.numero(req.get("CODUSU")), excluir));
            return r;
        } finally {
            JapeSession.close(hnd);
        }
    }

    public Map<String, Object> armazenar(Map<String, Object> request) throws Exception {
        JapeSession.SessionHandle hnd = JapeSession.open();
        hnd.setFindersMaxRows(-1);
        try {
            Map<String, Object> req = Corpo.desembrulhar(request, "NUTAR");
            BigDecimal nutar = Corpo.numero(req.get("NUTAR"));
            Map<String, Object> t = new EnderecamentoHelper().armazenar(nutar, texto(req.get("ENDERECO")), Corpo.numero(req.get("QTD")),
                texto(req.get("MOTIVO")), Corpo.numero(req.get("CODUSU")));
            Map<String, Object> r = resposta();
            r.put("TAREFA", t);
            return r;
        } finally {
            JapeSession.close(hnd);
        }
    }

    public Map<String, Object> gerarTarefas(Map<String, Object> request) throws Exception {
        JapeSession.SessionHandle hnd = JapeSession.open();
        hnd.setFindersMaxRows(-1);
        try {
            Map<String, Object> req = Corpo.desembrulhar(request, "NUNOTA");
            BigDecimal nunota = Corpo.numero(req.get("NUNOTA"));
            int n = new EnderecamentoHelper().gerarTarefas(nunota, Corpo.numero(req.get("NUCONF")), Corpo.numero(req.get("CODUSU")));
            if (n > 0) TorreLog.registrar("EnderecamentoSP.gerarTarefas", nunota, true, n + " tarefa(s)");
            Map<String, Object> r = resposta();
            r.put("CRIADAS", n);
            return r;
        } finally {
            JapeSession.close(hnd);
        }
    }

    public Map<String, Object> situacaoCompras(Map<String, Object> request) throws Exception {
        JapeSession.SessionHandle hnd = JapeSession.open();
        hnd.setFindersMaxRows(-1);
        try {
            Map<String, Object> req = Corpo.desembrulhar(request, "NUNOTAS");
            Map<String, Object> r = resposta();
            r.put("COMPRAS", new EnderecamentoHelper().situacaoCompras(numeros(req.get("NUNOTAS"))));
            return r;
        } finally {
            JapeSession.close(hnd);
        }
    }

    public Map<String, Object> saldos(Map<String, Object> request) throws Exception {
        JapeSession.SessionHandle hnd = JapeSession.open();
        hnd.setFindersMaxRows(-1);
        try {
            Map<String, Object> req = Corpo.desembrulhar(request, "CODEMP");
            Map<String, Object> r = resposta();
            r.put("SALDOS", new EnderecamentoHelper().saldos(Corpo.numero(req.get("CODEMP")), Corpo.numero(req.get("CODLOCAL"))));
            return r;
        } finally {
            JapeSession.close(hnd);
        }
    }

    private static Map<String, Object> resposta() {
        Map<String, Object> r = new LinkedHashMap<String, Object>();
        r.put("CONTRATO", Versao.CONTRATO);
        return r;
    }

    /** TOREND.CAPQTD/CAPPESO têm 3 casas; a Torre manda numeric(15,5) ("800.00000") — o Sankhya recusaria a lista. */
    private static BigDecimal tresCasas(BigDecimal v) {
        return v == null ? null : v.setScale(3, java.math.RoundingMode.HALF_UP);
    }

    private static String texto(Object v) {
        return v == null ? null : v.toString().trim();
    }

    private static List<BigDecimal> numeros(Object v) {
        List<BigDecimal> r = new ArrayList<BigDecimal>();
        if (v == null) return r;
        for (String s : v.toString().split("[,; ]+")) {
            BigDecimal b = Corpo.numero(s);
            if (b != null) r.add(b);
        }
        return r;
    }
}
