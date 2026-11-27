package br.com.torre.addon.enderecamento;

import br.com.sankhya.jape.dao.JdbcWrapper;
import br.com.sankhya.jape.sql.NativeSql;
import br.com.sankhya.modelcore.util.EntityFacadeFactory;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** SQL nativo com parâmetros nomeados, na transação corrente (Oracle — base da Negri). Colunas em MAIÚSCULAS. */
final class Db {

    private Db() {
    }

    static List<Map<String, Object>> lista(String sql, Map<String, Object> params) throws Exception {
        JdbcWrapper jdbc = EntityFacadeFactory.getDWFFacade().getJdbcWrapper();
        jdbc.openSession();
        ResultSet rs = null;
        try {
            NativeSql q = new NativeSql(jdbc);
            if (params != null) for (Map.Entry<String, Object> e : params.entrySet()) q.setNamedParameter(e.getKey(), e.getValue());
            rs = q.executeQuery(sql);
            ResultSetMetaData md = rs.getMetaData();
            List<Map<String, Object>> r = new ArrayList<Map<String, Object>>();
            while (rs.next()) {
                Map<String, Object> l = new LinkedHashMap<String, Object>();
                for (int i = 1; i <= md.getColumnCount(); i++) l.put(md.getColumnLabel(i).toUpperCase(), rs.getObject(i));
                r.add(l);
            }
            return r;
        } finally {
            if (rs != null) rs.close();
            JdbcWrapper.closeSession(jdbc);
        }
    }

    static Map<String, Object> um(String sql, Map<String, Object> params) throws Exception {
        List<Map<String, Object>> l = lista(sql, params);
        return l.isEmpty() ? null : l.get(0);
    }

    static void executar(String sql, Map<String, Object> params) throws Exception {
        JdbcWrapper jdbc = EntityFacadeFactory.getDWFFacade().getJdbcWrapper();
        jdbc.openSession();
        try {
            NativeSql q = new NativeSql(jdbc);
            if (params != null) for (Map.Entry<String, Object> e : params.entrySet()) q.setNamedParameter(e.getKey(), e.getValue());
            q.executeUpdate(sql);
        } finally {
            JdbcWrapper.closeSession(jdbc);
        }
    }

    static Map<String, Object> p(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(String.valueOf(kv[i]), kv[i + 1]);
        return m;
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

    static BigDecimal dec0(Object v) {
        BigDecimal d = dec(v);
        return d == null ? BigDecimal.ZERO : d;
    }

    static String txt(Object v) {
        return v == null ? null : v.toString().trim();
    }
}
