package br.com.torre.addon.enderecamento;

import br.com.sankhya.jape.vo.DynamicVO;
import br.com.sankhya.jape.wrapper.JapeFactory;
import br.com.sankhya.jape.wrapper.JapeWrapper;
import com.sankhya.util.TimeUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Endereçamento (etapa 3a, 27/11/2026): doca, tarefas de armazenagem, publicação dos endereços da Planta e o movimento
 * doca → endereço. Tudo na transação de quem chama (serviço EnderecamentoSP ou job). A TGFEST não é tocada.
 */
public final class EnderecamentoHelper {

    static final String I_END = "EnderecoTorre";
    static final String I_EST = "EstoqueEnderecoTorre";
    static final String I_TAR = "TarefaTorre";
    static final String I_MOV = "MovimentoEnderecoTorre";

    // ─── Doca ────────────────────────────────────────────────────────────────────────────────────────

    /** A doca da empresa/local (criada na primeira vez: código DOCA-<emp>-<local>). */
    public BigDecimal doca(BigDecimal codemp, BigDecimal codlocal) throws Exception {
        Map<String, Object> r = Db.um("SELECT MIN(CODEND) AS CODEND FROM TOREND WHERE TIPO = 'DOCA' AND CODEMP = :E AND CODLOCAL = :L "
            + "AND NVL(ATIVO, 'S') = 'S'", Db.p("E", codemp, "L", codlocal));
        BigDecimal cod = r == null ? null : Db.dec(r.get("CODEND"));
        if (cod != null) return cod;
        return JapeFactory.dao(I_END).create()
            .set("CODEMP", codemp).set("CODLOCAL", codlocal)
            .set("CODIGO", "DOCA-" + codemp.toPlainString() + "-" + codlocal.toPlainString())
            .set("TIPO", EnderecamentoRegras.DOCA).set("SETOR", "DOCA")
            .set("BLOQUEADO", "N").set("ATIVO", "S").set("DHALTER", TimeUtils.getNow())
            .save().asBigDecimal("CODEND");
    }

    // ─── Saldo por endereço ──────────────────────────────────────────────────────────────────────────

    /** Soma [delta] ao saldo do produto no endereço (cria a linha; nunca deixa negativo). */
    void somar(BigDecimal codend, BigDecimal codprod, String controle, BigDecimal delta) throws Exception {
        String ctl = controle == null || controle.trim().isEmpty() ? " " : controle;
        JapeWrapper dao = JapeFactory.dao(I_EST);
        DynamicVO vo = dao.findByPK(codend, codprod, ctl);
        if (vo == null) {
            if (delta.signum() < 0) throw new IllegalStateException("Sem saldo do produto " + codprod + " no endereço de origem.");
            dao.create().set("CODEND", codend).set("CODPROD", codprod).set("CONTROLE", ctl).set("QTD", delta)
                .set("DHALTER", TimeUtils.getNow()).save();
            return;
        }
        BigDecimal novo = Db.dec0(vo.asBigDecimal("QTD")).add(delta);
        if (novo.signum() < 0) {
            throw new IllegalStateException("Saldo insuficiente do produto " + codprod + " no endereço de origem ("
                + Db.dec0(vo.asBigDecimal("QTD")).stripTrailingZeros().toPlainString() + ").");
        }
        dao.prepareToUpdate(vo).set("QTD", novo).set("DHALTER", TimeUtils.getNow()).update();
    }

    private void movimento(String tipo, BigDecimal nutar, BigDecimal nunota, BigDecimal codprod, String controle, BigDecimal qtd,
                           BigDecimal origem, BigDecimal destino, String motivo, BigDecimal codusu) throws Exception {
        JapeFactory.dao(I_MOV).create()
            .set("TIPO", tipo).set("NUTAR", nutar).set("NUNOTA", nunota).set("CODPROD", codprod)
            .set("CONTROLE", controle == null || controle.trim().isEmpty() ? " " : controle).set("QTD", qtd)
            .set("CODENDORIG", origem).set("CODENDDEST", destino)
            .set("MOTIVO", motivo == null ? null : (motivo.length() > 200 ? motivo.substring(0, 200) : motivo))
            .set("CODUSU", codusu).set("DHMOV", TimeUtils.getNow()).save();
    }

    // ─── Endereços com ocupação (para a sugestão) ────────────────────────────────────────────────────

    List<EnderecamentoRegras.Endereco> enderecos(BigDecimal codemp, BigDecimal codlocal) throws Exception {
        return enderecos(codemp, codlocal, null);
    }

    /**
     * Endereços da empresa/local com a ocupação: saldo (TOREST) + o que está RESERVADO para as tarefas abertas
     * (sugestão de AB/EX, menos a tarefa [excetoNutar]) — sem isso várias tarefas recebiam o mesmo pulmão vazio
     * (1ª simulação, 27/11/2026).
     */
    List<EnderecamentoRegras.Endereco> enderecos(BigDecimal codemp, BigDecimal codlocal, BigDecimal excetoNutar) throws Exception {
        Map<Long, EnderecamentoRegras.Endereco> por = new LinkedHashMap<Long, EnderecamentoRegras.Endereco>();
        for (Map<String, Object> r : Db.lista("SELECT CODEND, CODIGO, TIPO, CAPQTD, BLOQUEADO, ATIVO, TIPOSEP FROM TOREND "
            + "WHERE CODEMP = :E AND CODLOCAL = :L", Db.p("E", codemp, "L", codlocal))) {
            long cod = Db.dec0(r.get("CODEND")).longValue();
            EnderecamentoRegras.Endereco e = new EnderecamentoRegras.Endereco(cod, Db.txt(r.get("CODIGO")), Db.txt(r.get("TIPO")),
                Db.dec(r.get("CAPQTD")), "S".equals(Db.txt(r.get("BLOQUEADO"))), !"N".equals(Db.txt(r.get("ATIVO"))));
            BigDecimal ts = Db.dec(r.get("TIPOSEP"));
            e.tipoSep = ts == null || ts.signum() <= 0 ? null : Integer.valueOf(ts.intValue());
            por.put(cod, e);
        }
        for (Map<String, Object> r : Db.lista("SELECT T.CODENDSUG, T.CODPROD, T.CONTROLE, T.QTD - NVL(T.QTDFEITA, 0) AS RESTA FROM TORTAR T "
            + "WHERE T.CODEMP = :E AND T.CODLOCAL = :L AND T.SITUACAO IN ('AB', 'EX') AND T.CODENDSUG IS NOT NULL AND T.NUTAR <> :T",
            Db.p("E", codemp, "L", codlocal, "T", excetoNutar == null ? BigDecimal.valueOf(-1) : excetoNutar))) {
            EnderecamentoRegras.reservar(por.get(Db.dec0(r.get("CODENDSUG")).longValue()),
                EnderecamentoRegras.chave(Db.dec0(r.get("CODPROD")).toPlainString(), Db.txt(r.get("CONTROLE"))), Db.dec0(r.get("RESTA")));
        }
        for (Map<String, Object> r : Db.lista("SELECT S.CODEND, S.CODPROD, S.CONTROLE, S.QTD FROM TOREST S JOIN TOREND E ON E.CODEND = S.CODEND "
            + "WHERE E.CODEMP = :E AND E.CODLOCAL = :L AND S.QTD > 0", Db.p("E", codemp, "L", codlocal))) {
            EnderecamentoRegras.Endereco e = por.get(Db.dec0(r.get("CODEND")).longValue());
            if (e == null) continue;
            e.ocupado = e.ocupado.add(Db.dec0(r.get("QTD")));
            e.produtos.add(EnderecamentoRegras.chave(Db.dec0(r.get("CODPROD")).toPlainString(), Db.txt(r.get("CONTROLE"))));
        }
        return new ArrayList<EnderecamentoRegras.Endereco>(por.values());
    }

    private static Boolean temTipoSep;

    /**
     * Tipo de separação do produto (TGFPRO.AD_TIPOSEPARACAO — campo da Negri: 1 Seco, 2 Refrigerado, 3 Congelado);
     * base sem o campo = null (sugestão sem setor).
     */
    static Integer tipoSeparacao(BigDecimal codprod) throws Exception {
        if (temTipoSep == null) {
            Map<String, Object> c = Db.um("SELECT COUNT(*) AS N FROM USER_TAB_COLUMNS WHERE TABLE_NAME = 'TGFPRO' AND COLUMN_NAME = 'AD_TIPOSEPARACAO'", null);
            temTipoSep = Boolean.valueOf(c != null && Db.dec0(c.get("N")).signum() > 0);
        }
        if (!temTipoSep.booleanValue() || codprod == null) return null;
        Map<String, Object> r = Db.um("SELECT AD_TIPOSEPARACAO AS T FROM TGFPRO WHERE CODPROD = :P", Db.p("P", codprod));
        BigDecimal t = r == null ? null : Db.dec(r.get("T"));
        return t == null || t.signum() <= 0 ? null : Integer.valueOf(t.intValue());
    }

    // ─── Tarefas da compra ───────────────────────────────────────────────────────────────────────────

    /**
     * Gera as tarefas de armazenagem da compra aprovada (TORREC 'AP'): por produto/controle/local, quantidade =
     * EnderecamentoRegras.quantidadeTarefa; põe o saldo na doca (TOREST + TORMOV 'ENT') e sugere o destino.
     * Idempotente: TORREC.ENDSIT = 'GE' devolve sem fazer nada. Devolve quantas tarefas criou.
     */
    public int gerarTarefas(BigDecimal nunota, BigDecimal nuconf, BigDecimal codusu) throws Exception {
        Map<String, Object> rec = Db.um("SELECT NUREC, CODEMP, SITUACAO, ENDSIT FROM TORREC WHERE NUNOTA = :N AND NUCONF = :C FOR UPDATE",
            Db.p("N", nunota, "C", nuconf));
        if (rec == null) throw new IllegalStateException("Compra " + nunota + " (conferência " + nuconf + ") não foi registrada pela Torre.");
        if (EnderecamentoRegras.TAREFAS_GERADAS.equals(Db.txt(rec.get("ENDSIT")))) return 0;
        if (!"AP".equals(Db.txt(rec.get("SITUACAO")))) throw new IllegalStateException("A compra " + nunota + " ainda não foi aprovada.");
        Map<String, Object> pend = Db.um("SELECT COUNT(*) AS N FROM TORPPV WHERE NUNOTA = :N AND NUCONF = :C AND STATUS IN ('P', 'A', 'E')",
            Db.p("N", nunota, "C", nuconf));
        if (Db.dec0(pend.get("N")).signum() > 0) throw new IllegalStateException("Ajuste de peso da compra " + nunota + " ainda não foi gerado.");
        Map<String, Object> cab = Db.um("SELECT CODEMP, STATUSNOTA FROM TGFCAB WHERE NUNOTA = :N", Db.p("N", nunota));
        if (cab == null || !"L".equals(Db.txt(cab.get("STATUSNOTA")))) throw new IllegalStateException("A compra " + nunota + " não está confirmada.");
        BigDecimal codemp = Db.dec(cab.get("CODEMP"));

        // Produto/controle/local: o que entrou (nota + ajustes gerados) e o físico (balança do ajuste, senão o conferido).
        List<Map<String, Object>> linhas = Db.lista(
            "SELECT I.CODPROD, NVL(I.CONTROLE, ' ') AS CONTROLE, I.CODLOCALORIG AS CODLOCAL, P.CODVOL, SUM(I.QTDNEG) AS NOTA, "
                + "(SELECT SUM(A.QTDDIF) FROM TORPPV A WHERE A.NUNOTA = :N AND A.NUCONF = :C AND A.STATUS = 'G' AND A.CODPROD = I.CODPROD "
                + "  AND NVL(A.CONTROLE, ' ') = NVL(I.CONTROLE, ' ')) AS AJUSTE, "
                + "(SELECT SUM(A.QTDBALANCA) FROM TORPPV A WHERE A.NUNOTA = :N AND A.NUCONF = :C AND A.STATUS = 'G' AND A.CODPROD = I.CODPROD "
                + "  AND NVL(A.CONTROLE, ' ') = NVL(I.CONTROLE, ' ')) AS BALANCA, "
                + "(SELECT SUM(X.QTDCONFVOLPAD) FROM TGFCOI2 X WHERE X.NUCONF = :C AND X.CODPROD = I.CODPROD "
                + "  AND NVL(X.CONTROLE, ' ') = NVL(I.CONTROLE, ' ') AND NVL(X.COPIA, 'N') <> 'S') AS CONFERIDO "
                + "FROM TGFITE I JOIN TGFPRO P ON P.CODPROD = I.CODPROD WHERE I.NUNOTA = :N AND I.SEQUENCIA > 0 AND NVL(I.ATUALESTOQUE, 0) <> 0 "
                + "GROUP BY I.CODPROD, NVL(I.CONTROLE, ' '), I.CODLOCALORIG, P.CODVOL ORDER BY I.CODPROD",
            Db.p("N", nunota, "C", nuconf));
        int criadas = 0;
        Map<String, List<EnderecamentoRegras.Endereco>> porLocal = new LinkedHashMap<String, List<EnderecamentoRegras.Endereco>>();
        for (Map<String, Object> l : linhas) {
            BigDecimal codprod = Db.dec(l.get("CODPROD"));
            String ctl = Db.txt(l.get("CONTROLE"));
            BigDecimal codlocal = Db.dec(l.get("CODLOCAL"));
            BigDecimal entrou = Db.dec0(l.get("NOTA")).add(Db.dec0(l.get("AJUSTE")));
            BigDecimal fisico = l.get("BALANCA") != null ? Db.dec0(l.get("BALANCA")) : Db.dec0(l.get("CONFERIDO"));
            BigDecimal qtd = EnderecamentoRegras.quantidadeTarefa(fisico, entrou);
            if (qtd.signum() <= 0 || codlocal == null) continue;
            BigDecimal doca = doca(codemp, codlocal);
            somar(doca, codprod, ctl, qtd);
            String k = codemp.toPlainString() + "|" + codlocal.toPlainString();
            List<EnderecamentoRegras.Endereco> ends = porLocal.get(k);
            if (ends == null) {
                ends = enderecos(codemp, codlocal);
                porLocal.put(k, ends);
            }
            EnderecamentoRegras.Endereco sug = EnderecamentoRegras.sugerir(ends, EnderecamentoRegras.chave(codprod.toPlainString(), ctl), qtd,
                tipoSeparacao(codprod));
            BigDecimal nutar = JapeFactory.dao(I_TAR).create()
                .set("TIPO", "ARM").set("NUNOTA", nunota).set("NUCONF", nuconf).set("CODEMP", codemp).set("CODLOCAL", codlocal)
                .set("CODPROD", codprod).set("CONTROLE", ctl == null || ctl.isEmpty() ? " " : ctl).set("CODVOL", Db.txt(l.get("CODVOL")))
                .set("QTD", qtd).set("QTDFEITA", BigDecimal.ZERO).set("CODENDORIG", doca)
                .set("CODENDSUG", sug == null ? null : BigDecimal.valueOf(sug.codend))
                .set("SITUACAO", EnderecamentoRegras.ABERTA).set("DHCRIACAO", TimeUtils.getNow())
                .save().asBigDecimal("NUTAR");
            movimento("ENT", nutar, nunota, codprod, ctl, qtd, null, doca, null, codusu);
            // A sugestão seguinte já considera esta (o mesmo pulmão vazio não vai para dois produtos).
            EnderecamentoRegras.reservar(sug, EnderecamentoRegras.chave(codprod.toPlainString(), ctl), qtd);
            criadas++;
        }
        Db.executar("UPDATE TORREC SET ENDSIT = 'GE', ENDERRO = NULL, DHENDER = SYSDATE WHERE NUREC = :R", Db.p("R", rec.get("NUREC")));
        return criadas;
    }

    /** Falha na geração: TORREC fica 'ER' com o motivo (o job tenta de novo em 30 min). */
    public static void registrarFalha(BigDecimal nunota, BigDecimal nuconf, String erro) throws Exception {
        Db.executar("UPDATE TORREC SET ENDSIT = 'ER', ENDERRO = SUBSTR(:M, 1, 400), DHENDER = SYSDATE WHERE NUNOTA = :N AND NUCONF = :C",
            Db.p("M", erro, "N", nunota, "C", nuconf));
    }

    /**
     * Compras aprovadas com endereçamento ligado na empresa e sem tarefas (ou com erro há mais de 30 min). Só as
     * aprovadas a partir de TORCFG.DHINIEND (vazio = de hoje): estoque antigo fica "a localizar" (decisão 27/11/2026).
     * A mesma consulta está na Torre (Armazenagem.gerarPendentes), reserva do job.
     */
    public static List<BigDecimal[]> aGerar(int limite) throws Exception {
        List<BigDecimal[]> r = new ArrayList<BigDecimal[]>();
        for (Map<String, Object> l : Db.lista("SELECT NUNOTA, NUCONF FROM (SELECT R.NUNOTA, R.NUCONF FROM TORREC R "
            + "JOIN TGFCAB C ON C.NUNOTA = R.NUNOTA JOIN TORCFG G ON G.CODEMP = C.CODEMP "
            + "WHERE R.SITUACAO = 'AP' AND NVL(G.ENDATIVO, 'N') = 'S' AND C.STATUSNOTA = 'L' "
            + "AND R.DHAPROV >= NVL(G.DHINIEND, TRUNC(SYSDATE)) "
            + "AND (R.ENDSIT IS NULL OR (R.ENDSIT = 'ER' AND R.DHENDER < SYSDATE - 30 / 1440)) "
            + "AND NOT EXISTS (SELECT 1 FROM TORPPV A WHERE A.NUNOTA = R.NUNOTA AND A.NUCONF = R.NUCONF AND A.STATUS IN ('P', 'A', 'E')) "
            + "ORDER BY R.DHAPROV) WHERE ROWNUM <= " + limite, null)) {
            r.add(new BigDecimal[]{Db.dec(l.get("NUNOTA")), Db.dec(l.get("NUCONF"))});
        }
        return r;
    }

    // ─── Execução da tarefa ──────────────────────────────────────────────────────────────────────────

    private Map<String, Object> travarTarefa(BigDecimal nutar) throws Exception {
        Map<String, Object> t = Db.um("SELECT NUTAR, NUNOTA, CODEMP, CODLOCAL, CODPROD, CONTROLE, QTD, QTDFEITA, CODENDORIG, CODENDSUG, SITUACAO "
            + "FROM TORTAR WHERE NUTAR = :T FOR UPDATE", Db.p("T", nutar));
        if (t == null) throw new IllegalStateException("Tarefa " + nutar + " não encontrada.");
        String sit = Db.txt(t.get("SITUACAO"));
        if (EnderecamentoRegras.CONCLUIDA.equals(sit)) throw new IllegalStateException("A tarefa " + nutar + " já foi concluída.");
        if (EnderecamentoRegras.CANCELADA.equals(sit)) throw new IllegalStateException("A tarefa " + nutar + " foi cancelada.");
        return t;
    }

    /** Sugestão para o que falta da tarefa [t], com o setor do produto e as reservas das OUTRAS tarefas abertas. */
    private EnderecamentoRegras.Endereco sugestao(Map<String, Object> t, BigDecimal nutar, BigDecimal falta) throws Exception {
        return sugestao(t, nutar, falta, null);
    }

    /** Idem, sem os endereços de código em [excluir] (o operador achou cheios — "Endereço cheio" no coletor). */
    private EnderecamentoRegras.Endereco sugestao(Map<String, Object> t, BigDecimal nutar, BigDecimal falta, List<String> excluir) throws Exception {
        BigDecimal codprod = Db.dec0(t.get("CODPROD"));
        List<EnderecamentoRegras.Endereco> ends = enderecos(Db.dec(t.get("CODEMP")), Db.dec(t.get("CODLOCAL")), nutar);
        if (excluir != null && !excluir.isEmpty()) {
            List<EnderecamentoRegras.Endereco> f = new ArrayList<EnderecamentoRegras.Endereco>();
            for (EnderecamentoRegras.Endereco e : ends) {
                boolean fora = false;
                for (String x : excluir) if (x != null && x.trim().equalsIgnoreCase(e.codigo)) fora = true;
                if (!fora) f.add(e);
            }
            ends = f;
        }
        return EnderecamentoRegras.sugerir(ends, EnderecamentoRegras.chave(codprod.toPlainString(), Db.txt(t.get("CONTROLE"))), falta,
            tipoSeparacao(codprod));
    }

    /** Operador começa a tarefa: marca EX e recalcula a sugestão (o galpão mudou desde a criação). */
    public Map<String, Object> iniciar(BigDecimal nutar, BigDecimal codusu) throws Exception {
        return iniciar(nutar, codusu, null);
    }

    /** [excluir]: endereços que o operador encontrou cheios — a sugestão nova pula esses (padrão de mercado, 27/11/2026). */
    public Map<String, Object> iniciar(BigDecimal nutar, BigDecimal codusu, List<String> excluir) throws Exception {
        Map<String, Object> t = travarTarefa(nutar);
        BigDecimal falta = EnderecamentoRegras.falta(Db.dec(t.get("QTD")), Db.dec(t.get("QTDFEITA")));
        EnderecamentoRegras.Endereco sug = sugestao(t, nutar, falta, excluir);
        Db.executar("UPDATE TORTAR SET SITUACAO = 'EX', CODUSU = NVL(:U, CODUSU), DHINICIO = NVL(DHINICIO, SYSDATE), CODENDSUG = :S WHERE NUTAR = :T",
            Db.p("U", codusu, "S", sug == null ? null : BigDecimal.valueOf(sug.codend), "T", nutar));
        return tarefa(nutar);
    }

    /**
     * Guarda [qtd] da tarefa no endereço de código [codigoDestino] (etiqueta bipada). Destino diferente do sugerido
     * exige [motivo]. Movimento atômico: doca −qtd, destino +qtd, TORMOV 'ARM', QTDFEITA; completa → 'CO'.
     */
    public Map<String, Object> armazenar(BigDecimal nutar, String codigoDestino, BigDecimal qtd, String motivo, BigDecimal codusu) throws Exception {
        Map<String, Object> t = travarTarefa(nutar);
        if (qtd == null || qtd.signum() <= 0) throw new IllegalArgumentException("Informe a quantidade guardada.");
        BigDecimal falta = EnderecamentoRegras.falta(Db.dec(t.get("QTD")), Db.dec(t.get("QTDFEITA")));
        if (qtd.compareTo(falta) > 0) {
            throw new IllegalArgumentException("Quantidade maior que o que falta guardar (" + falta.stripTrailingZeros().toPlainString() + ").");
        }
        BigDecimal codemp = Db.dec(t.get("CODEMP"));
        BigDecimal codlocal = Db.dec(t.get("CODLOCAL"));
        BigDecimal codprod = Db.dec(t.get("CODPROD"));
        String ctl = Db.txt(t.get("CONTROLE"));
        String chave = EnderecamentoRegras.chave(codprod.toPlainString(), ctl);
        String cod = codigoDestino == null ? "" : codigoDestino.trim();
        EnderecamentoRegras.Endereco dest = null;
        for (EnderecamentoRegras.Endereco e : enderecos(codemp, codlocal)) if (cod.equalsIgnoreCase(e.codigo)) dest = e;
        String recusa = EnderecamentoRegras.recusaDestino(dest, chave);
        if (recusa != null) throw new IllegalArgumentException(recusa);
        BigDecimal sug = Db.dec(t.get("CODENDSUG"));
        boolean trocou = sug == null || sug.longValue() != dest.codend;
        if (trocou && (motivo == null || motivo.trim().isEmpty())) {
            throw new IllegalArgumentException(sug == null ? "Sem endereço sugerido: informe o motivo da escolha."
                : "Endereço diferente do sugerido: informe o motivo.");
        }
        BigDecimal origem = Db.dec(t.get("CODENDORIG"));
        somar(origem, codprod, ctl, qtd.negate());
        somar(BigDecimal.valueOf(dest.codend), codprod, ctl, qtd);
        movimento("ARM", nutar, Db.dec(t.get("NUNOTA")), codprod, ctl, qtd, origem, BigDecimal.valueOf(dest.codend), trocou ? motivo : null, codusu);
        BigDecimal feita = Db.dec0(t.get("QTDFEITA")).add(qtd);
        boolean fim = EnderecamentoRegras.falta(Db.dec(t.get("QTD")), feita).signum() == 0;
        Db.executar("UPDATE TORTAR SET QTDFEITA = :F, SITUACAO = :S, CODUSU = NVL(:U, CODUSU), DHINICIO = NVL(DHINICIO, SYSDATE), "
            + "DHCONCLUSAO = :D WHERE NUTAR = :T",
            Db.p("F", feita, "S", fim ? EnderecamentoRegras.CONCLUIDA : EnderecamentoRegras.EM_EXECUCAO, "U", codusu,
                "D", fim ? TimeUtils.getNow() : null, "T", nutar));
        if (!fim) {
            // Guardou parte: o resto ganha sugestão nova (o endereço usado pode ter enchido).
            EnderecamentoRegras.Endereco prox = sugestao(t, nutar, EnderecamentoRegras.falta(Db.dec(t.get("QTD")), feita));
            Db.executar("UPDATE TORTAR SET CODENDSUG = :S WHERE NUTAR = :T",
                Db.p("S", prox == null ? null : BigDecimal.valueOf(prox.codend), "T", nutar));
        }
        return tarefa(nutar);
    }

    // ─── Consultas ───────────────────────────────────────────────────────────────────────────────────

    private static final String SELECT_TAREFA = "SELECT T.NUTAR, T.SITUACAO, T.NUNOTA, C.NUMNOTA, PA.NOMEPARC, T.CODEMP, T.CODLOCAL, "
        + "T.CODPROD, P.DESCRPROD, P.REFERENCIA, T.CONTROLE, T.CODVOL, T.QTD, T.QTDFEITA, O.CODIGO AS ORIGEM, S.CODIGO AS SUGERIDO, "
        + "S.TIPO AS TIPOSUGERIDO, T.CODUSU, TO_CHAR(T.DHCRIACAO, 'YYYY-MM-DD\"T\"HH24:MI:SS') AS CRIADA, "
        + "(SELECT LISTAGG(B.CODBARRA, ',') WITHIN GROUP (ORDER BY B.CODBARRA) FROM TGFBAR B WHERE B.CODPROD = T.CODPROD) AS BARRAS "
        + "FROM TORTAR T JOIN TGFPRO P ON P.CODPROD = T.CODPROD LEFT JOIN TGFCAB C ON C.NUNOTA = T.NUNOTA "
        + "LEFT JOIN TGFPAR PA ON PA.CODPARC = C.CODPARC LEFT JOIN TOREND O ON O.CODEND = T.CODENDORIG LEFT JOIN TOREND S ON S.CODEND = T.CODENDSUG ";

    public Map<String, Object> tarefa(BigDecimal nutar) throws Exception {
        return Db.um(SELECT_TAREFA + "WHERE T.NUTAR = :T", Db.p("T", nutar));
    }

    /** Tarefas abertas/em execução das empresas [empresas] (vazio = todas). */
    public List<Map<String, Object>> tarefasAbertas(List<BigDecimal> empresas) throws Exception {
        StringBuilder w = new StringBuilder("WHERE T.SITUACAO IN ('AB', 'EX') ");
        if (empresas != null && !empresas.isEmpty()) w.append("AND T.CODEMP IN (").append(lista(empresas)).append(") ");
        return Db.lista(SELECT_TAREFA + w + "ORDER BY T.DHCRIACAO, T.NUTAR", null);
    }

    /** Situação do endereçamento das compras [nunotas] — para a aba Armazenagem do Recebimento. */
    public List<Map<String, Object>> situacaoCompras(List<BigDecimal> nunotas) throws Exception {
        if (nunotas == null || nunotas.isEmpty()) return new ArrayList<Map<String, Object>>();
        return Db.lista("SELECT R.NUNOTA, R.NUCONF, R.ENDSIT, R.ENDERRO, TO_CHAR(R.DHENDER, 'YYYY-MM-DD\"T\"HH24:MI:SS') AS DHENDER, "
            + "(SELECT COUNT(*) FROM TORTAR T WHERE T.NUNOTA = R.NUNOTA AND T.NUCONF = R.NUCONF) AS TAREFAS, "
            + "(SELECT COUNT(*) FROM TORTAR T WHERE T.NUNOTA = R.NUNOTA AND T.NUCONF = R.NUCONF AND T.SITUACAO = 'CO') AS CONCLUIDAS, "
            + "(SELECT COUNT(*) FROM TORTAR T WHERE T.NUNOTA = R.NUNOTA AND T.NUCONF = R.NUCONF AND T.SITUACAO IN ('AB', 'EX') "
            + "  AND T.CODENDSUG IS NULL) AS SEMENDERECO "
            + "FROM TORREC R WHERE R.NUNOTA IN (" + lista(nunotas) + ")", null);
    }

    /** Saldo por endereço da empresa/local (Planta). */
    public List<Map<String, Object>> saldos(BigDecimal codemp, BigDecimal codlocal) throws Exception {
        return Db.lista("SELECT E.CODEND, E.CODIGO, E.TIPO, E.IDTORRE, S.CODPROD, P.DESCRPROD, S.CONTROLE, S.QTD FROM TOREST S "
            + "JOIN TOREND E ON E.CODEND = S.CODEND JOIN TGFPRO P ON P.CODPROD = S.CODPROD "
            + "WHERE E.CODEMP = :E AND E.CODLOCAL = :L AND S.QTD > 0 ORDER BY E.CODIGO, S.CODPROD", Db.p("E", codemp, "L", codlocal));
    }

    // ─── Publicação dos endereços da Planta ──────────────────────────────────────────────────────────

    /** Endereço vindo da Planta da Torre. */
    public static final class EnderecoPlanta {
        public String id;
        public String codigo;
        public String tipo;
        public String setor;
        public String estrutura;
        public BigDecimal modulo;
        public BigDecimal nivel;
        public BigDecimal capQtd;
        public BigDecimal capPeso;
        public boolean bloqueado;
        /** Tipo de separação do setor na Planta (1 Seco, 2 Refrigerado, 3 Congelado); null = qualquer. */
        public BigDecimal tipoSep;
    }

    /**
     * Grava os endereços publicados da Planta da empresa/local (chave = IDTORRE; código atualizado se mudou). Os que
     * saíram do desenho ficam inativos — os com saldo ficam ativos e voltam na lista "comSaldo". A doca não é mexida.
     */
    public Map<String, Object> publicar(BigDecimal codemp, BigDecimal codlocal, List<EnderecoPlanta> lista) throws Exception {
        if (codemp == null || codlocal == null) throw new IllegalArgumentException("A planta precisa ter empresa e local.");
        Map<String, BigDecimal> existentes = new LinkedHashMap<String, BigDecimal>();
        for (Map<String, Object> r : Db.lista("SELECT CODEND, IDTORRE FROM TOREND WHERE CODEMP = :E AND CODLOCAL = :L AND IDTORRE IS NOT NULL",
            Db.p("E", codemp, "L", codlocal))) existentes.put(Db.txt(r.get("IDTORRE")), Db.dec(r.get("CODEND")));
        int novos = 0, alterados = 0;
        java.util.Set<String> vistos = new java.util.HashSet<String>();
        JapeWrapper dao = JapeFactory.dao(I_END);
        for (EnderecoPlanta e : lista) {
            if (e.id == null || e.codigo == null) continue;
            String tipo = e.tipo == null ? EnderecamentoRegras.PULMAO : e.tipo.trim().toUpperCase();
            if (EnderecamentoRegras.DOCA.equals(tipo)) continue;
            vistos.add(e.id);
            BigDecimal cod = existentes.get(e.id);
            if (cod == null) {
                dao.create().set("CODEMP", codemp).set("CODLOCAL", codlocal).set("IDTORRE", e.id).set("CODIGO", e.codigo.trim())
                    .set("TIPO", tipo).set("SETOR", e.setor).set("ESTRUTURA", e.estrutura).set("MODULO", e.modulo).set("NIVEL", e.nivel)
                    .set("CAPQTD", e.capQtd).set("CAPPESO", e.capPeso).set("BLOQUEADO", e.bloqueado ? "S" : "N").set("ATIVO", "S").set("TIPOSEP", e.tipoSep)
                    .set("DHALTER", TimeUtils.getNow()).save();
                novos++;
            } else {
                dao.prepareToUpdate(dao.findByPK(cod)).set("CODIGO", e.codigo.trim()).set("TIPO", tipo).set("SETOR", e.setor)
                    .set("ESTRUTURA", e.estrutura).set("MODULO", e.modulo).set("NIVEL", e.nivel).set("CAPQTD", e.capQtd)
                    .set("CAPPESO", e.capPeso).set("BLOQUEADO", e.bloqueado ? "S" : "N").set("ATIVO", "S").set("TIPOSEP", e.tipoSep)
                    .set("DHALTER", TimeUtils.getNow()).update();
                alterados++;
            }
        }
        List<String> comSaldo = new ArrayList<String>();
        int desativados = 0;
        for (Map.Entry<String, BigDecimal> ex : existentes.entrySet()) {
            if (vistos.contains(ex.getKey())) continue;
            Map<String, Object> s = Db.um("SELECT E.CODIGO, NVL(SUM(S.QTD), 0) AS QTD FROM TOREND E LEFT JOIN TOREST S ON S.CODEND = E.CODEND "
                + "WHERE E.CODEND = :C GROUP BY E.CODIGO", Db.p("C", ex.getValue()));
            if (s != null && Db.dec0(s.get("QTD")).signum() > 0) {
                comSaldo.add(Db.txt(s.get("CODIGO")));
                continue;
            }
            Db.executar("UPDATE TOREND SET ATIVO = 'N', DHALTER = SYSDATE WHERE CODEND = :C", Db.p("C", ex.getValue()));
            desativados++;
        }
        doca(codemp, codlocal);
        Map<String, Object> r = new LinkedHashMap<String, Object>();
        r.put("NOVOS", novos);
        r.put("ALTERADOS", alterados);
        r.put("DESATIVADOS", desativados);
        r.put("COMSALDO", comSaldo);
        return r;
    }

    static String lista(List<BigDecimal> v) {
        StringBuilder s = new StringBuilder();
        for (BigDecimal b : v) {
            if (b == null) continue;
            if (s.length() > 0) s.append(',');
            s.append(b.stripTrailingZeros().toPlainString());
        }
        return s.length() == 0 ? "-1" : s.toString();
    }
}
