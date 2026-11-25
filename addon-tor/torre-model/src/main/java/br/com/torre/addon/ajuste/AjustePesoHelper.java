package br.com.torre.addon.ajuste;

import br.com.sankhya.jape.EntityFacade;
import br.com.sankhya.jape.dao.JdbcWrapper;
import br.com.sankhya.jape.sql.NativeSql;
import br.com.sankhya.jape.vo.DynamicVO;
import br.com.sankhya.jape.vo.PrePersistEntityState;
import br.com.sankhya.jape.wrapper.JapeFactory;
import br.com.sankhya.modelcore.auth.AuthenticationInfo;
import br.com.sankhya.modelcore.comercial.BarramentoRegra;
import br.com.sankhya.modelcore.comercial.ConfirmacaoNotaHelper;
import br.com.sankhya.modelcore.comercial.centrais.CACHelper;
import br.com.sankhya.modelcore.util.EntityFacadeFactory;
import br.com.sankhya.ws.ServiceContext;
import com.sankhya.util.TimeUtils;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Gera as notas de ajuste de peso de uma compra conferida, NUMA transação (a do serviço AjustePesoSP): grava a
 * diferença em TORPPV, inclui cabeçalho e itens pelo CACHelper, confirma (ConfirmacaoNotaHelper), liga cada item na
 * TGFVAR (CompraVendavariosPedido) e marca TORPPV como gerado. Qualquer falha lança exceção e o Sankhya desfaz tudo —
 * substitui as 4 chamadas separadas da Torre (cabeçalho, itens, confirmar, vínculo) com recuperação por marcador.
 *
 * Caminho do nativo (WMS, regras 1.10 do Hub): cabeçalho = clone da nota do fornecedor com a TOP de ajuste; item na
 * unidade padrão com o preço da compra (decisão da Torre validada em produção, porque a balança pesa em KG).
 * Escrito do zero (risco A4: sem copiar código do WMS e sem usar classes/tabelas do mgewms).
 */
public class AjustePesoHelper {

    /** Nota de ajuste gerada (ou já existente) por sentido. */
    public static final class Resultado {
        public final String sentido;
        public final BigDecimal nunotaAjuste;
        public final boolean jaExistia;

        Resultado(String sentido, BigDecimal nunotaAjuste, boolean jaExistia) {
            this.sentido = sentido;
            this.nunotaAjuste = nunotaAjuste;
            this.jaExistia = jaExistia;
        }
    }

    private final EntityFacade dwf;
    private final AuthenticationInfo auth;

    public AjustePesoHelper() {
        this.dwf = EntityFacadeFactory.getDWFFacade();
        this.auth = AuthenticationInfo.getCurrent();
    }

    public List<Resultado> gerar(BigDecimal nunota, BigDecimal nuconf, List<AjustePesoRegras.Diferenca> itens) throws Exception {
        String erro = AjustePesoRegras.validar(nunota, nuconf, itens);
        if (erro != null) throw new IllegalArgumentException(erro);

        DynamicVO compra = (DynamicVO) dwf.findEntityByPrimaryKeyAsVO("CabecalhoNota", nunota);
        if (compra == null) throw new IllegalStateException("Compra " + nunota + " não encontrada.");
        validarCompra(compra, nunota, nuconf);

        // Trava as diferenças desta conferência (job e Torre ao mesmo tempo esperam um pelo outro).
        travar(nunota, nuconf);
        // Idempotência: a mesma conferência já gerou ajuste (por este serviço) -> devolve o que existe, não gera de novo.
        List<Resultado> existentes = jaGerados(nunota, nuconf);
        if (!existentes.isEmpty()) return existentes;
        // Troca de caminho (Torre -> addon): ajuste já gerado pela Torre para esta compra também bloqueia.
        BigDecimal codemp = compra.asBigDecimal("CODEMP");
        Config cfg = config(codemp);
        BigDecimal geradoPelaTorre = ajusteJaLigado(nunota, cfg);
        if (geradoPelaTorre != null) {
            throw new IllegalStateException("A compra " + nunota + " já tem nota de ajuste (" + geradoPelaTorre
                + ") ligada a ela - não gera outra.");
        }

        Map<String, ItemOrigem> origem = itensDaCompra(nunota);
        List<Resultado> resultado = new ArrayList<Resultado>();
        for (Map.Entry<String, List<AjustePesoRegras.Diferenca>> e : AjustePesoRegras.porSentido(itens).entrySet()) {
            String sentido = e.getKey();
            BigDecimal top = AjustePesoRegras.ENTRADA.equals(sentido) ? cfg.topEntrada : cfg.topSaida;
            if (top == null) {
                throw new IllegalStateException("TOP do ajuste de " + (AjustePesoRegras.ENTRADA.equals(sentido) ? "entrada" : "saída")
                    + " não configurada (TORCFG ou TGFEMP da empresa " + codemp + ").");
            }
            String serie = AjustePesoRegras.ENTRADA.equals(sentido) ? cfg.serieEntrada : cfg.serieSaida;
            BigDecimal nAj = gerarNota(compra, nuconf, sentido, top, serie, cfg, e.getValue(), origem);
            resultado.add(new Resultado(sentido, nAj, false));
        }
        return resultado;
    }

    // ─── Plano B (25/11/2026): registrar na conferência, gerar depois da confirmação ─────────────────

    /**
     * A Torre registra as diferenças ao finalizar a conferência da compra (antes da confirmação). Substitui o que havia
     * de outra conferência ainda não gerado. Compra já confirmada: entra direto como 'A' (o job gera).
     * Devolve true se gravou; false se esta conferência já tinha ajuste gerado (nada muda).
     */
    public boolean registrar(BigDecimal nunota, BigDecimal nuconf, List<AjustePesoRegras.Diferenca> itens) throws Exception {
        if (nunota == null || nuconf == null) throw new IllegalArgumentException("NUNOTA e NUCONF são obrigatórios.");
        DynamicVO compra = (DynamicVO) dwf.findEntityByPrimaryKeyAsVO("CabecalhoNota", nunota);
        if (compra == null) throw new IllegalStateException("Compra " + nunota + " não encontrada.");
        BigDecimal atual = compra.asBigDecimal("NUCONFATUAL");
        if (atual == null || atual.compareTo(nuconf) != 0) {
            throw new IllegalStateException("Conferência " + nuconf + " não é a atual da compra " + nunota + " (atual: " + atual + ").");
        }
        if (!jaGerados(nunota, nuconf).isEmpty()) return false;
        executar("DELETE FROM TORPPV WHERE NUNOTA = :N AND STATUS IN ('P','A','E')", nunota, null);
        if (itens == null || itens.isEmpty()) return true;
        String erro = AjustePesoRegras.validar(nunota, nuconf, itens);
        if (erro != null) throw new IllegalArgumentException(erro);
        String status = "L".equals(compra.asString("STATUSNOTA")) ? AjustePesoRegras.STATUS_A_GERAR : AjustePesoRegras.STATUS_PENDENTE;
        Map<String, ItemOrigem> origem = itensDaCompra(nunota);
        Map<BigDecimal, AjustePesoRegras.Diferenca> porSeq = new LinkedHashMap<BigDecimal, AjustePesoRegras.Diferenca>();
        for (AjustePesoRegras.Diferenca d : itens) {
            ItemOrigem o = origem.get(d.codprod + "|" + d.controle);
            if (o == null) throw new IllegalStateException("Produto " + d.codprod + " (controle '" + d.controle.trim() + "') não está na compra " + nunota + ".");
            porSeq.put(o.sequencia, d);
        }
        for (Map.Entry<BigDecimal, AjustePesoRegras.Diferenca> e : porSeq.entrySet()) {
            AjustePesoRegras.Diferenca d = e.getValue();
            JapeFactory.dao("PesoVariavelTorre").create()
                .set("NUNOTA", nunota)
                .set("NUCONF", nuconf)
                .set("SEQUENCIA", e.getKey())
                .set("CODPROD", BigDecimal.valueOf(d.codprod))
                .set("CONTROLE", d.controle)
                .set("QTDNOTA", d.qtdNota)
                .set("QTDBALANCA", d.qtdBalanca)
                .set("QTDDIF", d.qtdDif)
                .set("STATUS", status)
                .set("TENTATIVAS", BigDecimal.ZERO)
                .set("DHPROC", TimeUtils.getNow())
                .set("CODUSU", auth == null ? null : auth.getUserID())
                .save();
        }
        return true;
    }

    /** Gera o ajuste das diferenças 'A' da conferência (job ou Torre). Nada a gerar = lista vazia. */
    public List<Resultado> gerarPendente(BigDecimal nunota, BigDecimal nuconf) throws Exception {
        travar(nunota, nuconf);
        List<Resultado> existentes = jaGerados(nunota, nuconf);
        if (!existentes.isEmpty()) return existentes;
        List<AjustePesoRegras.Diferenca> itens = new ArrayList<AjustePesoRegras.Diferenca>();
        Collection<DynamicVO> linhas = JapeFactory.dao("PesoVariavelTorre")
            .find("this.NUNOTA = ? AND this.NUCONF = ? AND this.STATUS = 'A'", nunota, nuconf);
        for (DynamicVO l : linhas) {
            itens.add(new AjustePesoRegras.Diferenca(l.asBigDecimal("CODPROD").intValue(), l.asString("CONTROLE"),
                l.asBigDecimal("QTDNOTA"), l.asBigDecimal("QTDBALANCA"), l.asBigDecimal("QTDDIF")));
        }
        if (itens.isEmpty()) return new ArrayList<Resultado>();
        return gerar(nunota, nuconf, itens);
    }

    /** Conferências com diferença 'A' (compra confirmada) ainda não geradas, mais antigas primeiro. */
    public static List<BigDecimal[]> aGerar(int limite) throws Exception {
        List<BigDecimal[]> r = new ArrayList<BigDecimal[]>();
        JdbcWrapper jdbc = EntityFacadeFactory.getDWFFacade().getJdbcWrapper();
        jdbc.openSession();
        ResultSet rs = null;
        try {
            NativeSql sql = new NativeSql(jdbc);
            sql.setNamedParameter("MAX", BigDecimal.valueOf(AjustePesoRegras.MAX_TENTATIVAS));
            rs = sql.executeQuery("SELECT NUNOTA, NUCONF, MIN(DHPROC) DH FROM TORPPV WHERE STATUS = 'A' "
                + "AND NVL(TENTATIVAS, 0) < :MAX GROUP BY NUNOTA, NUCONF ORDER BY 3");
            while (rs.next() && r.size() < limite) r.add(new BigDecimal[]{rs.getBigDecimal(1), rs.getBigDecimal(2)});
        } finally {
            if (rs != null) rs.close();
            JdbcWrapper.closeSession(jdbc);
        }
        return r;
    }

    /** Conta a tentativa que falhou; na última, a diferença vira 'E'. */
    public static void registrarFalha(BigDecimal nunota, BigDecimal nuconf, String erro) throws Exception {
        String msg = erro == null ? "falha" : (erro.length() > 400 ? erro.substring(0, 400) : erro);
        Collection<DynamicVO> linhas = JapeFactory.dao("PesoVariavelTorre")
            .find("this.NUNOTA = ? AND this.NUCONF = ? AND this.STATUS = 'A'", nunota, nuconf);
        for (DynamicVO l : linhas) {
            int t = (l.asBigDecimal("TENTATIVAS") == null ? 0 : l.asBigDecimal("TENTATIVAS").intValue()) + 1;
            JapeFactory.dao("PesoVariavelTorre").prepareToUpdate(l)
                .set("TENTATIVAS", BigDecimal.valueOf(t))
                .set("ERRO", msg)
                .set("STATUS", t >= AjustePesoRegras.MAX_TENTATIVAS ? AjustePesoRegras.STATUS_ERRO : AjustePesoRegras.STATUS_A_GERAR)
                .update();
        }
    }

    private void travar(BigDecimal nunota, BigDecimal nuconf) throws Exception {
        JdbcWrapper jdbc = dwf.getJdbcWrapper();
        jdbc.openSession();
        ResultSet rs = null;
        try {
            NativeSql sql = new NativeSql(jdbc);
            sql.setNamedParameter("N", nunota);
            sql.setNamedParameter("C", nuconf);
            rs = sql.executeQuery("SELECT SEQUENCIA FROM TORPPV WHERE NUNOTA = :N AND NUCONF = :C FOR UPDATE");
            while (rs.next()) {
                // só trava
            }
        } finally {
            if (rs != null) rs.close();
            JdbcWrapper.closeSession(jdbc);
        }
    }

    private void executar(String sqlTexto, BigDecimal nunota, BigDecimal nuconf) throws Exception {
        JdbcWrapper jdbc = dwf.getJdbcWrapper();
        jdbc.openSession();
        try {
            NativeSql sql = new NativeSql(jdbc);
            sql.setNamedParameter("N", nunota);
            if (nuconf != null) sql.setNamedParameter("C", nuconf);
            sql.executeUpdate(sqlTexto);
        } finally {
            JdbcWrapper.closeSession(jdbc);
        }
    }

    // ─── Validações ─────────────────────────────────────────────────────────────────────────────────

    private void validarCompra(DynamicVO compra, BigDecimal nunota, BigDecimal nuconf) throws Exception {
        if (!"L".equals(compra.asString("STATUSNOTA"))) {
            throw new IllegalStateException("A compra " + nunota + " ainda não está confirmada - o ajuste sai depois da confirmação.");
        }
        BigDecimal atual = compra.asBigDecimal("NUCONFATUAL");
        if (atual == null || atual.compareTo(nuconf) != 0) {
            throw new IllegalStateException("Conferência " + nuconf + " não é a atual da compra " + nunota
                + " (atual: " + atual + ") - a conferência foi refeita.");
        }
        String status = umValor("SELECT STATUS FROM TGFCON2 WHERE NUCONF = :NUCONF", "NUCONF", nuconf);
        if (!"F".equals(status) && !"D".equals(status)) {
            throw new IllegalStateException("Conferência " + nuconf + " não está finalizada (status " + status + ").");
        }
    }

    private List<Resultado> jaGerados(BigDecimal nunota, BigDecimal nuconf) throws Exception {
        Map<String, BigDecimal> porSentido = new LinkedHashMap<String, BigDecimal>();
        Collection<DynamicVO> linhas = JapeFactory.dao("PesoVariavelTorre")
            .find("this.NUNOTA = ? AND this.NUCONF = ? AND this.STATUS = 'G'", nunota, nuconf);
        for (DynamicVO l : linhas) {
            String s = l.asBigDecimalOrZero("QTDDIF").signum() > 0 ? AjustePesoRegras.ENTRADA : AjustePesoRegras.SAIDA;
            porSentido.put(s, l.asBigDecimal("NUNOTAAJ"));
        }
        List<Resultado> r = new ArrayList<Resultado>();
        for (Map.Entry<String, BigDecimal> e : porSentido.entrySet()) r.add(new Resultado(e.getKey(), e.getValue(), true));
        return r;
    }

    /** Nota de ajuste (TOP de entrada ou saída) já ligada à compra pela TGFVAR — gerada pela Torre antes da troca. */
    private BigDecimal ajusteJaLigado(BigDecimal nunota, Config cfg) throws Exception {
        if (cfg.topEntrada == null && cfg.topSaida == null) return null;
        String tops = (cfg.topEntrada != null ? cfg.topEntrada.toPlainString() : "-1") + ","
            + (cfg.topSaida != null ? cfg.topSaida.toPlainString() : "-1");
        String v = umValor("SELECT MAX(C.NUNOTA) FROM TGFVAR V JOIN TGFCAB C ON C.NUNOTA = V.NUNOTA "
            + "WHERE V.NUNOTAORIG = :NUNOTA AND V.NUNOTA <> V.NUNOTAORIG AND C.CODTIPOPER IN (" + tops + ")", "NUNOTA", nunota);
        return v == null ? null : new BigDecimal(v);
    }

    // ─── Configuração (TORCFG, senão os campos do WMS nativo na TGFEMP) ─────────────────────────────

    static final class Config {
        BigDecimal topEntrada;
        BigDecimal topSaida;
        String serieEntrada;
        String serieSaida;
        BigDecimal codParc;
        BigDecimal codTipVenda;
    }

    private Config config(BigDecimal codemp) throws Exception {
        Config c = new Config();
        DynamicVO cfg = JapeFactory.dao("ConfiguracaoTorre").findByPK(codemp);
        DynamicVO emp = (DynamicVO) dwf.findEntityByPrimaryKeyAsVO("Empresa", codemp);
        c.topEntrada = primeiro(cfg == null ? null : cfg.asBigDecimal("TOPENTAJ"), campoEmpresa(emp, "TOPENTDIFPESOWMS"));
        c.topSaida = primeiro(cfg == null ? null : cfg.asBigDecimal("TOPSAIAJ"), campoEmpresa(emp, "TOPSAIDADIFPESOWMS"));
        c.serieEntrada = primeiroTexto(cfg == null ? null : cfg.asString("SERIEENTAJ"), textoEmpresa(emp, "SERIETOPDIFMAIOR"));
        c.serieSaida = primeiroTexto(cfg == null ? null : cfg.asString("SERIESAIAJ"), textoEmpresa(emp, "SERIETOPDIFMENOR"));
        c.codParc = cfg == null ? null : positivo(cfg.asBigDecimal("CODPARCAJ"));
        c.codTipVenda = cfg == null ? null : positivo(cfg.asBigDecimal("CODTIPVENDAAJ"));
        return c;
    }

    private static BigDecimal campoEmpresa(DynamicVO emp, String campo) {
        try {
            return emp == null ? null : positivo(emp.asBigDecimal(campo));
        } catch (Exception e) {
            return null; // campo do WMS ausente na versão do cliente
        }
    }

    private static String textoEmpresa(DynamicVO emp, String campo) {
        try {
            return emp == null ? null : emp.asString(campo);
        } catch (Exception e) {
            return null;
        }
    }

    // ─── Geração ────────────────────────────────────────────────────────────────────────────────────

    private static final class ItemOrigem {
        BigDecimal sequencia;
        BigDecimal vlrTot = BigDecimal.ZERO;
        BigDecimal qtdNeg = BigDecimal.ZERO;
        BigDecimal codLocal;
        String codVolPadrao;
        Integer decQtd;
    }

    /** Itens da compra por produto+controle: primeira sequência, soma do valor e da quantidade, unidade padrão. */
    private Map<String, ItemOrigem> itensDaCompra(BigDecimal nunota) throws Exception {
        Map<String, ItemOrigem> m = new HashMap<String, ItemOrigem>();
        JdbcWrapper jdbc = dwf.getJdbcWrapper();
        jdbc.openSession();
        ResultSet rs = null;
        try {
            NativeSql sql = new NativeSql(jdbc);
            sql.setNamedParameter("NUNOTA", nunota);
            rs = sql.executeQuery("SELECT I.SEQUENCIA, I.CODPROD, I.CONTROLE, I.VLRTOT, I.QTDNEG, I.CODLOCALORIG, P.CODVOL, P.DECQTD "
                + "FROM TGFITE I JOIN TGFPRO P ON P.CODPROD = I.CODPROD WHERE I.NUNOTA = :NUNOTA AND I.SEQUENCIA > 0 ORDER BY I.SEQUENCIA");
            while (rs.next()) {
                String chave = rs.getBigDecimal("CODPROD").intValue() + "|" + AjustePesoRegras.normalizarControle(rs.getString("CONTROLE"));
                ItemOrigem o = m.get(chave);
                if (o == null) {
                    o = new ItemOrigem();
                    o.sequencia = rs.getBigDecimal("SEQUENCIA");
                    o.codLocal = rs.getBigDecimal("CODLOCALORIG");
                    o.codVolPadrao = rs.getString("CODVOL");
                    BigDecimal dec = rs.getBigDecimal("DECQTD");
                    o.decQtd = dec == null ? null : dec.intValue();
                    m.put(chave, o);
                }
                if (rs.getBigDecimal("VLRTOT") != null) o.vlrTot = o.vlrTot.add(rs.getBigDecimal("VLRTOT"));
                if (rs.getBigDecimal("QTDNEG") != null) o.qtdNeg = o.qtdNeg.add(rs.getBigDecimal("QTDNEG"));
            }
        } finally {
            if (rs != null) rs.close();
            JdbcWrapper.closeSession(jdbc);
        }
        return m;
    }

    private BigDecimal gerarNota(DynamicVO compra, BigDecimal nuconf, String sentido, BigDecimal top, String serie, Config cfg,
                                 List<AjustePesoRegras.Diferenca> difs, Map<String, ItemOrigem> origem) throws Exception {
        BigDecimal nunotaCompra = compra.asBigDecimal("NUNOTA");
        Timestamp agora = TimeUtils.getNow();

        // Cabeçalho: clone da compra com a TOP de ajuste (como o nativo), zerando o que é da nota original.
        DynamicVO cab = compra.buildClone();
        cab.setProperty("NUNOTA", null);
        cab.setProperty("NUMNOTA", BigDecimal.ZERO);
        cab.setProperty("VLRNOTA", BigDecimal.ZERO);
        cab.setProperty("VLRFRETE", BigDecimal.ZERO);
        cab.setProperty("CHAVENFE", null);
        cab.setProperty("DTFATUR", null);
        cab.setProperty("HRENTSAI", null);
        cab.setProperty("HRMOV", null);
        cab.setProperty("NUCONFATUAL", null);
        cab.setProperty("LIBCONF", null);
        cab.setProperty("STATUSNOTA", "A");
        cab.setProperty("PENDENTE", "S");
        cab.setProperty("DTNEG", TimeUtils.clearTime(agora));
        cab.setProperty("DTMOV", TimeUtils.clearTime(agora));
        cab.setProperty("DTENTSAI", agora);
        cab.setProperty("DTALTER", agora);
        cab.setProperty("CODTIPOPER", top);
        cab.setProperty("DHTIPOPER", versaoTop(top));
        cab.setProperty("TIPMOV", tipMovTop(top));
        if (serie != null && !serie.trim().isEmpty()) cab.setProperty("SERIENOTA", serie.trim());
        if (cfg.codParc != null) cab.setProperty("CODPARC", cfg.codParc);
        if (cfg.codTipVenda != null) {
            cab.setProperty("CODTIPVENDA", cfg.codTipVenda);
            cab.setProperty("DHTIPVENDA", versaoTipoNegociacao(cfg.codTipVenda));
        }
        cab.setProperty("OBSERVACAO", AjustePesoRegras.observacao(nunotaCompra, compra.asBigDecimal("NUMNOTA"), nuconf, sentido));

        CACHelper cac = new CACHelper();
        BarramentoRegra regras = cac.incluirAlterarCabecalho(ServiceContext.getCurrent(), PrePersistEntityState.build(dwf, "CabecalhoNota", cab));
        BigDecimal nAj = cab.asBigDecimal("NUNOTA");
        if (nAj == null || nAj.signum() <= 0) throw new IllegalStateException("O Sankhya não devolveu o número da nota de ajuste.");

        // Itens: unidade padrão, preço da compra; guarda a origem de cada um para a TGFVAR e a TORPPV.
        List<PrePersistEntityState> estados = new ArrayList<PrePersistEntityState>();
        List<Object[]> ligacoes = new ArrayList<Object[]>(); // {diferenca, origem}
        for (AjustePesoRegras.Diferenca d : difs) {
            ItemOrigem o = origem.get(d.codprod + "|" + d.controle);
            if (o == null) throw new IllegalStateException("Produto " + d.codprod + " (controle '" + d.controle.trim() + "') não está na compra " + nunotaCompra + ".");
            BigDecimal qtd = AjustePesoRegras.quantidade(d.qtdDif, o.decQtd);
            if (qtd.signum() == 0) continue;
            BigDecimal preco = AjustePesoRegras.precoUnitario(o.vlrTot, d.qtdNota != null && d.qtdNota.signum() > 0 ? d.qtdNota : o.qtdNeg);
            if (preco == null) throw new IllegalStateException("Sem preço para o produto " + d.codprod + " na compra " + nunotaCompra + ".");
            DynamicVO item = (DynamicVO) dwf.getDefaultValueObjectInstance("ItemNota");
            item.setProperty("NUNOTA", nAj);
            item.setProperty("SEQUENCIA", null);
            item.setProperty("CODEMP", compra.asBigDecimal("CODEMP"));
            item.setProperty("CODPROD", BigDecimal.valueOf(d.codprod));
            item.setProperty("CONTROLE", d.controle);
            item.setProperty("CODVOL", o.codVolPadrao);
            item.setProperty("QTDNEG", qtd);
            item.setProperty("VLRUNIT", preco);
            item.setProperty("VLRTOT", AjustePesoRegras.total(qtd, preco));
            item.setProperty("PERCDESC", BigDecimal.ZERO);
            item.setProperty("VLRDESC", BigDecimal.ZERO);
            if (o.codLocal != null) item.setProperty("CODLOCALORIG", o.codLocal);
            estados.add(PrePersistEntityState.build(dwf, "ItemNota", item));
            ligacoes.add(new Object[]{d, o, item});
        }
        if (estados.isEmpty()) {
            throw new IllegalStateException("A diferença arredondada pelas casas decimais do produto ficou zero - nada a ajustar.");
        }
        cac.incluirAlterarItem(nAj, auth, estados, true);

        ConfirmacaoNotaHelper.confirmarNota(nAj, regras);
        String status = umValor("SELECT STATUSNOTA FROM TGFCAB WHERE NUNOTA = :NUNOTA", "NUNOTA", nAj);
        if (!"L".equals(status)) {
            // Confirmação que pede liberação: o Sankhya não confirma e a nota fica aberta - desfaz tudo (liberar é do cliente).
            throw new IllegalStateException("A nota de ajuste não foi confirmada (status " + status + ") - verifique liberações pendentes da TOP " + top + ".");
        }

        for (Object[] l : ligacoes) {
            AjustePesoRegras.Diferenca d = (AjustePesoRegras.Diferenca) l[0];
            ItemOrigem o = (ItemOrigem) l[1];
            DynamicVO item = (DynamicVO) l[2];
            JapeFactory.dao("CompraVendavariosPedido").create()
                .set("NUNOTA", nAj)
                .set("SEQUENCIA", item.asBigDecimal("SEQUENCIA"))
                .set("NUNOTAORIG", nunotaCompra)
                .set("SEQUENCIAORIG", o.sequencia)
                .set("STATUSNOTA", "L")
                .save();
            DynamicVO linha = JapeFactory.dao("PesoVariavelTorre").findByPK(nunotaCompra, nuconf, o.sequencia);
            if (linha != null) {
                JapeFactory.dao("PesoVariavelTorre").prepareToUpdate(linha)
                    .set("QTDNOTA", d.qtdNota)
                    .set("QTDBALANCA", d.qtdBalanca)
                    .set("QTDDIF", d.qtdDif)
                    .set("NUNOTAAJ", nAj)
                    .set("STATUS", AjustePesoRegras.STATUS_GERADO)
                    .set("ERRO", null)
                    .set("DHPROC", agora)
                    .set("CODUSU", auth == null ? null : auth.getUserID())
                    .update();
            } else {
                JapeFactory.dao("PesoVariavelTorre").create()
                    .set("NUNOTA", nunotaCompra)
                    .set("NUCONF", nuconf)
                    .set("SEQUENCIA", o.sequencia)
                    .set("CODPROD", BigDecimal.valueOf(d.codprod))
                    .set("CONTROLE", d.controle)
                    .set("QTDNOTA", d.qtdNota)
                    .set("QTDBALANCA", d.qtdBalanca)
                    .set("QTDDIF", d.qtdDif)
                    .set("NUNOTAAJ", nAj)
                    .set("STATUS", AjustePesoRegras.STATUS_GERADO)
                    .set("DHPROC", agora)
                    .set("CODUSU", auth == null ? null : auth.getUserID())
                    .save();
            }
        }
        return nAj;
    }

    // ─── Leituras auxiliares ────────────────────────────────────────────────────────────────────────

    private Timestamp versaoTop(BigDecimal top) throws Exception {
        Timestamp t = umTimestamp("SELECT MAX(DHALTER) FROM TGFTOP WHERE CODTIPOPER = :COD", "COD", top);
        if (t == null) throw new IllegalStateException("TOP " + top + " não existe.");
        return t;
    }

    private String tipMovTop(BigDecimal top) throws Exception {
        String t = umValor("SELECT T.TIPMOV FROM TGFTOP T WHERE T.CODTIPOPER = :COD "
            + "AND T.DHALTER = (SELECT MAX(T2.DHALTER) FROM TGFTOP T2 WHERE T2.CODTIPOPER = :COD)", "COD", top);
        if (t == null || t.trim().isEmpty()) throw new IllegalStateException("TOP " + top + " sem tipo de movimento.");
        return t.trim();
    }

    private Timestamp versaoTipoNegociacao(BigDecimal cod) throws Exception {
        Timestamp t = umTimestamp("SELECT MAX(DHALTER) FROM TGFTPV WHERE CODTIPVENDA = :COD", "COD", cod);
        if (t == null) throw new IllegalStateException("Tipo de negociação " + cod + " (ajuste de peso) não existe.");
        return t;
    }

    private String umValor(String sqlTexto, String param, Object valor) throws Exception {
        JdbcWrapper jdbc = dwf.getJdbcWrapper();
        jdbc.openSession();
        ResultSet rs = null;
        try {
            NativeSql sql = new NativeSql(jdbc);
            sql.setNamedParameter(param, valor);
            rs = sql.executeQuery(sqlTexto);
            if (!rs.next()) return null;
            String v = rs.getString(1);
            return v == null ? null : v.trim();
        } finally {
            if (rs != null) rs.close();
            JdbcWrapper.closeSession(jdbc);
        }
    }

    private Timestamp umTimestamp(String sqlTexto, String param, Object valor) throws Exception {
        JdbcWrapper jdbc = dwf.getJdbcWrapper();
        jdbc.openSession();
        ResultSet rs = null;
        try {
            NativeSql sql = new NativeSql(jdbc);
            sql.setNamedParameter(param, valor);
            rs = sql.executeQuery(sqlTexto);
            return rs.next() ? rs.getTimestamp(1) : null;
        } finally {
            if (rs != null) rs.close();
            JdbcWrapper.closeSession(jdbc);
        }
    }

    private static BigDecimal positivo(BigDecimal v) {
        return v != null && v.signum() > 0 ? v : null;
    }

    private static BigDecimal primeiro(BigDecimal a, BigDecimal b) {
        return positivo(a) != null ? a : positivo(b);
    }

    private static String primeiroTexto(String a, String b) {
        if (a != null && !a.trim().isEmpty()) return a.trim();
        return b != null && !b.trim().isEmpty() ? b.trim() : null;
    }
}
