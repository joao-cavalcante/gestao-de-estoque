package br.com.torre.addon.recebimento;

import br.com.sankhya.jape.EntityFacade;
import br.com.sankhya.jape.vo.DynamicVO;
import br.com.sankhya.jape.vo.PrePersistEntityState;
import br.com.sankhya.modelcore.auth.AuthenticationInfo;
import br.com.sankhya.modelcore.comercial.CentralItemNota;
import br.com.sankhya.modelcore.comercial.UnidadeProdutoUtils;
import br.com.sankhya.modelcore.comercial.centrais.CACHelper;
import br.com.sankhya.modelcore.util.EntityFacadeFactory;
import br.com.sankhya.ws.ServiceContext;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Divide o item da nota de COMPRA pendente pelos lotes conferidos na doca (Torre, D3 opção A — 28/11/2026).
 *
 * A conferência de entrada do Sankhya só confere o lote que já está no item da nota (EXPLODIRLOTE é proibido na entrada,
 * COM_E00028). O WMS nativo resolve assim (mgewms-model 5.21.2 `RecebimentoHelper.processaExplosaoLoteRecebimento`):
 * altera o item existente (QTDNEG, CONTROLE) e inclui um item por lote a mais, com o mesmo VLRUNIT/PERCDESC/local,
 * `CentralItemNota.recalcularValores` + `CACHelper.incluirAlterarItem`. Aqui é o mesmo, sem o lote "AJUSTE": a
 * quantidade da nota é repartida na proporção do conferido em cada lote (o total da nota não muda; a divergência de
 * quantidade continua sendo tratada pela conferência, lote a lote).
 *
 * v1: item ligado a pedido (TGFVAR) é recusado — o WMS refaz essas ligações; fica para a próxima versão.
 * Idempotente: produto já dividido (sem item genérico e com os lotes pedidos) é pulado.
 */
public final class LoteNotaHelper {

    public static final class Lote {
        final String lote;
        final BigDecimal qtd;

        public Lote(String lote, BigDecimal qtd) {
            this.lote = lote;
            this.qtd = qtd;
        }
    }

    public Map<String, Object> dividir(BigDecimal nunota, Map<BigDecimal, List<Lote>> lotesPorProduto) throws Exception {
        EntityFacade dwf = EntityFacadeFactory.getDWFFacade();
        Map<String, Object> r = new LinkedHashMap<String, Object>();
        List<Map<String, Object>> cab = DocaHelper.lista("SELECT STATUSNOTA, TIPMOV FROM TGFCAB WHERE NUNOTA = :NUNOTA", "NUNOTA", nunota);
        if (cab.isEmpty()) throw new Exception("Nota " + nunota + " não encontrada.");
        if ("L".equals(String.valueOf(cab.get(0).get("STATUSNOTA")))) throw new Exception("Nota " + nunota + " já confirmada: não dá para dividir por lote.");
        if (!"C".equals(String.valueOf(cab.get(0).get("TIPMOV")).trim())) throw new Exception("Nota " + nunota + " não é compra.");

        CentralItemNota cin = new CentralItemNota();
        CACHelper cac = new CACHelper();
        List<Map<String, Object>> feitos = new ArrayList<Map<String, Object>>();

        for (Map.Entry<BigDecimal, List<Lote>> e : lotesPorProduto.entrySet()) {
            BigDecimal codprod = e.getKey();
            List<Lote> lotes = e.getValue();
            Map<String, Object> res = new LinkedHashMap<String, Object>();
            res.put("CODPROD", codprod);
            List<Map<String, Object>> itens = DocaHelper.lista2(
                "SELECT I.SEQUENCIA, I.QTDNEG, I.VLRUNIT, I.PERCDESC, I.CODLOCALORIG, I.CONTROLE, NVL(P.DECQTD, 3) DECQTD, "
                    + "(SELECT COUNT(*) FROM TGFVAR V WHERE V.NUNOTA = I.NUNOTA AND V.SEQUENCIA = I.SEQUENCIA AND V.NUNOTAORIG <> I.NUNOTA) VARS "
                    + "FROM TGFITE I JOIN TGFPRO P ON P.CODPROD = I.CODPROD WHERE I.NUNOTA = :NUNOTA AND I.CODPROD = :CODPROD AND I.SEQUENCIA > 0 ORDER BY I.SEQUENCIA",
                "NUNOTA", nunota, "CODPROD", codprod);
            List<Map<String, Object>> genericos = new ArrayList<Map<String, Object>>();
            for (Map<String, Object> it : itens) {
                Object c = it.get("CONTROLE");
                if (c == null || String.valueOf(c).trim().isEmpty()) genericos.add(it);
            }
            if (genericos.isEmpty()) {
                res.put("SITUACAO", "JA_DIVIDIDO");
                feitos.add(res);
                continue;
            }
            if (genericos.size() > 1) throw new Exception("Produto " + codprod + " tem " + genericos.size() + " itens sem lote na nota: divisão não suportada.");
            Map<String, Object> g = genericos.get(0);
            if (DocaHelper.dec(g.get("VARS")).signum() > 0) {
                throw new Exception("Produto " + codprod + " está ligado a pedido de compra: divisão por lote com pedido ainda não suportada.");
            }
            BigDecimal seq = DocaHelper.dec(g.get("SEQUENCIA"));
            BigDecimal qtdNota = DocaHelper.dec(g.get("QTDNEG"));
            BigDecimal vlrUnit = DocaHelper.dec(g.get("VLRUNIT"));
            BigDecimal percDesc = DocaHelper.dec(g.get("PERCDESC"));
            if (percDesc == null) percDesc = BigDecimal.ZERO;
            BigDecimal local = DocaHelper.dec(g.get("CODLOCALORIG"));
            int dec = DocaHelper.dec(g.get("DECQTD")).intValue();

            BigDecimal total = BigDecimal.ZERO;
            for (Lote l : lotes) total = total.add(l.qtd);
            if (total.signum() <= 0) throw new Exception("Produto " + codprod + " sem quantidade nos lotes.");

            // Repartição proporcional, a última parte leva o resto (o total da nota não muda).
            List<BigDecimal> partes = new ArrayList<BigDecimal>();
            BigDecimal resto = qtdNota;
            for (int i = 0; i < lotes.size(); i++) {
                BigDecimal q = i == lotes.size() - 1 ? resto
                    : qtdNota.multiply(lotes.get(i).qtd).divide(total, dec, RoundingMode.HALF_UP);
                partes.add(q);
                resto = resto.subtract(q);
            }
            for (BigDecimal q : partes) {
                if (q.signum() <= 0) throw new Exception("Produto " + codprod + ": a divisão daria lote com quantidade zero — confira as quantidades.");
            }

            // 1) o item existente fica com o primeiro lote
            DynamicVO itemVO = (DynamicVO) dwf.findEntityByPrimaryKeyAsVO("ItemNota", new Object[]{nunota, seq});
            itemVO.setProperty("QTDNEG", partes.get(0));
            itemVO.setProperty("CONTROLE", lotes.get(0).lote);
            itemVO.setProperty("VLRTOT", valorTotal(partes.get(0), vlrUnit, percDesc));
            UnidadeProdutoUtils.converterUNPadraoParaUNAlternativa(itemVO);
            cin.recalcularValores("QTDNEG", itemVO.asBigDecimalOrZero("QTDNEG").toString(), itemVO, nunota);
            List<DynamicVO> alterados = new ArrayList<DynamicVO>();
            alterados.add(itemVO);
            cac.incluirAlterarItem(nunota, ServiceContext.getCurrent(), (org.jdom.Element) null, false, alterados);

            // 2) um item novo para cada lote a mais
            if (lotes.size() > 1) {
                List<PrePersistEntityState> novos = new ArrayList<PrePersistEntityState>();
                for (int i = 1; i < lotes.size(); i++) {
                    DynamicVO vo = (DynamicVO) dwf.getDefaultValueObjectInstance("ItemNota");
                    vo.setProperty("CODPROD", codprod);
                    vo.setProperty("QTDNEG", partes.get(i));
                    vo.setProperty("CONTROLE", lotes.get(i).lote);
                    vo.setProperty("VLRTOT", valorTotal(partes.get(i), vlrUnit, percDesc));
                    vo.setProperty("VLRUNIT", vlrUnit);
                    vo.setProperty("PERCDESC", percDesc);
                    vo.setProperty("CODLOCALORIG", local);
                    vo.setProperty("OBSERVACAO", "Lote dividido na doca (Torre)");
                    cin.recalcularValores("QTDNEG", vo.asBigDecimalOrZero("QTDNEG").toString(), vo, nunota);
                    novos.add(PrePersistEntityState.build(dwf, "ItemNota", vo, null, null));
                    vo.setAceptTransientProperties(true);
                    vo.setProperty("__IS_INSERINDO_DESCONTO_PUI", Boolean.TRUE);
                }
                cac.incluirAlterarItem(nunota, AuthenticationInfo.getCurrent(), novos, true);
            }
            res.put("SITUACAO", "DIVIDIDO");
            res.put("LOTES", lotes.size());
            feitos.add(res);
        }
        r.put("PRODUTOS", feitos);
        return r;
    }

    private static BigDecimal valorTotal(BigDecimal qtd, BigDecimal vlrUnit, BigDecimal percDesc) {
        if (vlrUnit == null) return BigDecimal.ZERO;
        BigDecimal bruto = qtd.multiply(vlrUnit);
        BigDecimal desc = bruto.multiply(percDesc).divide(new BigDecimal("100"), 10, RoundingMode.HALF_UP);
        return bruto.subtract(desc).setScale(2, RoundingMode.HALF_UP);
    }
}
