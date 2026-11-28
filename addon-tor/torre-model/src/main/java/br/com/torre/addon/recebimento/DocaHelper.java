package br.com.torre.addon.recebimento;

import br.com.sankhya.jape.dao.JdbcWrapper;
import br.com.sankhya.jape.sql.NativeSql;
import br.com.sankhya.modelcore.util.EntityFacadeFactory;

import java.io.Reader;
import java.math.BigDecimal;
import java.sql.Clob;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Recebimento na doca (Torre, etapa D3, 28/11/2026): tudo o que a tela da doca precisa do Sankhya numa chamada —
 * o XML da NF-e importado (TGFIXN: volumes, espécie, pesos, placa, itens com lotes do grupo {@code rastro}) e os itens
 * da nota (pedido de origem pela TGFVAR, norma do palete TGFPRO.LASTRO/CAMADAS e a unidade de caixa da TGFVOA).
 * Só leitura. Antes a Torre fazia 5 consultas pelo DbExplorer, com o XML recortado em pedaços de 1.800 caracteres.
 */
public final class DocaHelper {

    /** Unidades de embalagem, na ordem de preferência para "peso médio da caixa". */
    private static final String[] CAIXAS = {"CX", "CXA", "FD", "PCT", "PT", "PC"};

    public Map<String, Object> doca(BigDecimal nunota) throws Exception {
        Map<String, Object> r = new LinkedHashMap<String, Object>();
        r.put("XML", xml(nunota));
        r.put("ITENS", itens(nunota));
        return r;
    }

    // ─── XML ─────────────────────────────────────────────────────────────────────────────────────────

    private Map<String, Object> xml(BigDecimal nunota) throws Exception {
        Map<String, Object> x = new LinkedHashMap<String, Object>();
        List<Map<String, Object>> arq = lista(
            "SELECT * FROM (SELECT NUARQUIVO, CODPARC, XML FROM TGFIXN WHERE NUNOTA = :NUNOTA AND TIPO = 'N' ORDER BY NUARQUIVO DESC) WHERE ROWNUM = 1",
            "NUNOTA", nunota);
        if (arq.isEmpty() || arq.get(0).get("XML") == null) {
            x.put("ENCONTRADO", "N");
            x.put("ITENS", new ArrayList<Object>());
            return x;
        }
        String texto = String.valueOf(arq.get(0).get("XML"));
        BigDecimal codparc = dec(arq.get(0).get("CODPARC"));
        x.put("ENCONTRADO", "S");

        String transp = trecho(texto, "transp");
        if (transp != null) {
            List<String> vols = todos(transp, "vol");
            x.put("VOLUMES", soma(vols, "qVol"));
            String esp = null;
            for (String v : vols) if (esp == null) esp = tag(v, "esp");
            x.put("ESPECIE", esp);
            x.put("PESOB", soma(vols, "pesoB"));
            x.put("PESOL", soma(vols, "pesoL"));
            x.put("PLACA", tag(transp, "placa"));
        }

        // Código do fornecedor → produto (TGFPAP); sem cadastro, pela sequência da nota.
        Map<String, BigDecimal> porCodigo = new HashMap<String, BigDecimal>();
        if (codparc != null) {
            for (Map<String, Object> l : lista("SELECT CODPROPARC, MIN(CODPROD) CODPROD FROM TGFPAP WHERE CODPARC = :CODPARC GROUP BY CODPROPARC",
                "CODPARC", codparc)) {
                if (l.get("CODPROPARC") != null) porCodigo.put(String.valueOf(l.get("CODPROPARC")).trim(), dec(l.get("CODPROD")));
            }
        }
        Map<Integer, BigDecimal> porSeq = new HashMap<Integer, BigDecimal>();
        for (Map<String, Object> l : lista("SELECT SEQUENCIA, CODPROD FROM TGFITE WHERE NUNOTA = :NUNOTA AND SEQUENCIA > 0", "NUNOTA", nunota)) {
            BigDecimal s = dec(l.get("SEQUENCIA"));
            if (s != null) porSeq.put(s.intValue(), dec(l.get("CODPROD")));
        }

        List<Map<String, Object>> itens = new ArrayList<Map<String, Object>>();
        Matcher m = Pattern.compile("<det\\s+nItem=\"(\\d+)\"[^>]*>(.*?)</det>", Pattern.DOTALL).matcher(texto);
        while (m.find()) {
            int nItem = Integer.parseInt(m.group(1));
            String prod = trecho(m.group(2), "prod");
            if (prod == null) prod = m.group(2);
            String cProd = tag(prod, "cProd");
            List<Map<String, Object>> lotes = new ArrayList<Map<String, Object>>();
            for (String ra : todos(prod, "rastro")) {
                String lote = tag(ra, "nLote");
                if (lote == null || lote.isEmpty()) continue;
                Map<String, Object> l = new LinkedHashMap<String, Object>();
                l.put("LOTE", lote);
                l.put("QTD", dec(tag(ra, "qLote")));
                l.put("DFAB", tag(ra, "dFab"));
                l.put("DVAL", tag(ra, "dVal"));
                lotes.add(l);
            }
            Map<String, Object> it = new LinkedHashMap<String, Object>();
            it.put("NITEM", nItem);
            BigDecimal cod = cProd != null ? porCodigo.get(cProd) : null;
            it.put("CODPROD", cod != null ? cod : porSeq.get(nItem));
            it.put("CPROD", cProd);
            it.put("XPROD", tag(prod, "xProd"));
            it.put("LOTES", lotes);
            itens.add(it);
        }
        x.put("ITENS", itens);
        return x;
    }

    // ─── Itens da nota ───────────────────────────────────────────────────────────────────────────────

    private List<Map<String, Object>> itens(BigDecimal nunota) throws Exception {
        List<Map<String, Object>> linhas = lista(
            "SELECT I.SEQUENCIA, I.CODPROD, P.DESCRPROD, I.CODVOL, P.CODVOL CODVOLPADRAO, I.QTDNEG, I.VLRUNIT, V.NUNOTAORIG, "
                + "PI.QTDNEG QTDPED, PI.VLRUNIT VLRPED, P.LASTRO, P.CAMADAS "
                + "FROM TGFITE I JOIN TGFPRO P ON P.CODPROD = I.CODPROD "
                + "LEFT JOIN TGFVAR V ON V.NUNOTA = I.NUNOTA AND V.SEQUENCIA = I.SEQUENCIA AND V.NUNOTAORIG <> I.NUNOTA "
                + "LEFT JOIN TGFITE PI ON PI.NUNOTA = V.NUNOTAORIG AND PI.SEQUENCIA = V.SEQUENCIAORIG "
                + "WHERE I.NUNOTA = :NUNOTA AND I.SEQUENCIA > 0 ORDER BY I.SEQUENCIA",
            "NUNOTA", nunota);
        // Unidade de caixa de cada produto (multiplica: 1 CX = QUANTIDADE na unidade padrão).
        Map<String, Map<String, BigDecimal>> voa = new HashMap<String, Map<String, BigDecimal>>();
        for (Map<String, Object> l : lista(
            "SELECT V.CODPROD, V.CODVOL, V.QUANTIDADE FROM TGFVOA V WHERE V.DIVIDEMULTIPLICA = 'M' AND V.QUANTIDADE > 0 "
                + "AND V.CODPROD IN (SELECT CODPROD FROM TGFITE WHERE NUNOTA = :NUNOTA)",
            "NUNOTA", nunota)) {
            String cod = String.valueOf(dec(l.get("CODPROD")));
            Map<String, BigDecimal> u = voa.get(cod);
            if (u == null) voa.put(cod, u = new HashMap<String, BigDecimal>());
            u.put(String.valueOf(l.get("CODVOL")).trim(), dec(l.get("QUANTIDADE")));
        }
        for (Map<String, Object> l : linhas) {
            Map<String, BigDecimal> u = voa.get(String.valueOf(dec(l.get("CODPROD"))));
            if (u == null) continue;
            for (String c : CAIXAS) {
                BigDecimal f = u.get(c);
                if (f != null && f.compareTo(BigDecimal.ONE) > 0) {
                    l.put("UNCAIXA", c);
                    l.put("FATORCAIXA", f);
                    break;
                }
            }
        }
        return linhas;
    }

    // ─── Apoio ───────────────────────────────────────────────────────────────────────────────────────

    private static List<Map<String, Object>> lista(String sql, String nome, Object valor) throws Exception {
        JdbcWrapper jdbc = EntityFacadeFactory.getDWFFacade().getJdbcWrapper();
        jdbc.openSession();
        ResultSet rs = null;
        try {
            NativeSql q = new NativeSql(jdbc);
            q.setNamedParameter(nome, valor);
            rs = q.executeQuery(sql);
            ResultSetMetaData md = rs.getMetaData();
            List<Map<String, Object>> r = new ArrayList<Map<String, Object>>();
            while (rs.next()) {
                Map<String, Object> l = new LinkedHashMap<String, Object>();
                for (int i = 1; i <= md.getColumnCount(); i++) {
                    Object v = rs.getObject(i);
                    if (v instanceof Clob) v = texto((Clob) v);
                    l.put(md.getColumnLabel(i).toUpperCase(), v);
                }
                r.add(l);
            }
            return r;
        } finally {
            if (rs != null) rs.close();
            JdbcWrapper.closeSession(jdbc);
        }
    }

    private static String texto(Clob c) throws Exception {
        StringBuilder sb = new StringBuilder();
        Reader rd = c.getCharacterStream();
        try {
            char[] buf = new char[8192];
            int n;
            while ((n = rd.read(buf)) > 0) sb.append(buf, 0, n);
        } finally {
            rd.close();
        }
        return sb.toString();
    }

    static String trecho(String t, String nome) {
        Matcher m = Pattern.compile("<" + nome + "(?:\\s[^>]*)?>(.*?)</" + nome + ">", Pattern.DOTALL).matcher(t);
        return m.find() ? m.group(1) : null;
    }

    static List<String> todos(String t, String nome) {
        List<String> r = new ArrayList<String>();
        Matcher m = Pattern.compile("<" + nome + "(?:\\s[^>]*)?>(.*?)</" + nome + ">", Pattern.DOTALL).matcher(t);
        while (m.find()) r.add(m.group(1));
        return r;
    }

    static String tag(String t, String nome) {
        if (t == null) return null;
        Matcher m = Pattern.compile("<" + nome + ">([^<]*)</" + nome + ">").matcher(t);
        return m.find() ? m.group(1).trim() : null;
    }

    private static BigDecimal soma(List<String> vols, String nome) {
        BigDecimal s = null;
        for (String v : vols) {
            BigDecimal d = dec(tag(v, nome));
            if (d != null) s = s == null ? d : s.add(d);
        }
        return s;
    }

    static BigDecimal dec(Object v) {
        if (v == null) return null;
        if (v instanceof BigDecimal) return (BigDecimal) v;
        try {
            return new BigDecimal(v.toString().trim().replace(',', '.'));
        } catch (Exception e) {
            return null;
        }
    }
}
