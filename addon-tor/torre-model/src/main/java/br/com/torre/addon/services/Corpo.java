package br.com.torre.addon.services;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Leitura do corpo das chamadas do gateway (requestBody / body / request, em qualquer nível até 4). */
final class Corpo {

    private Corpo() {
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> desembrulhar(Map<String, Object> body, String campoEsperado) {
        if (body == null) return Collections.emptyMap();
        Map<String, Object> atual = body;
        for (int i = 0; i < 4; i++) {
            if (atual.containsKey(campoEsperado)) return atual;
            Object prox = atual.get("requestBody");
            if (!(prox instanceof Map)) prox = atual.get("body");
            if (!(prox instanceof Map)) prox = atual.get("request");
            if (!(prox instanceof Map)) return atual;
            atual = (Map<String, Object>) prox;
        }
        return atual;
    }

    static BigDecimal numero(Object v) {
        if (v == null) return null;
        if (v instanceof BigDecimal) return (BigDecimal) v;
        if (v instanceof Double || v instanceof Float) {
            // O bean do Studio usa Gson: número JSON chega como Double (88093 -> 88093.0). Volta ao valor exato curto.
            BigDecimal d = BigDecimal.valueOf(((Number) v).doubleValue()).stripTrailingZeros();
            return d.scale() < 0 ? d.setScale(0) : d;
        }
        String s = v.toString().trim().replace(',', '.');
        if (s.isEmpty()) return null;
        try {
            return new BigDecimal(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> lista(Object v) {
        List<Map<String, Object>> r = new ArrayList<Map<String, Object>>();
        if (v instanceof List) {
            for (Object o : (List<Object>) v) if (o instanceof Map) r.add((Map<String, Object>) o);
        } else if (v instanceof Map) {
            r.add((Map<String, Object>) v);
        }
        return r;
    }
}
