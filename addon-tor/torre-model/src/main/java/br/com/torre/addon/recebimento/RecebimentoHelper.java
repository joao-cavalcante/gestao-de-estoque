package br.com.torre.addon.recebimento;

import br.com.sankhya.jape.EntityFacade;
import br.com.sankhya.jape.dao.JdbcWrapper;
import br.com.sankhya.jape.sql.NativeSql;
import br.com.sankhya.jape.vo.DynamicVO;
import br.com.sankhya.jape.wrapper.JapeFactory;
import br.com.sankhya.modelcore.auth.AuthenticationInfo;
import br.com.sankhya.modelcore.comercial.BarramentoRegra;
import br.com.sankhya.modelcore.comercial.CentralFaturamento;
import br.com.sankhya.modelcore.comercial.ConfirmacaoNotaHelper;
import br.com.sankhya.modelcore.comercial.LiberacaoSolicitada;
import br.com.sankhya.modelcore.util.EntityFacadeFactory;
import com.sankhya.util.TimeUtils;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Aprovação e devolução do recebimento da compra (mesa de liberação da Torre). Etapa 2, 23/11/2026.
 *
 * Aprovar = confirmar a compra EM NOME DO APROVADOR (decisão 23/11/2026), como a tela de confirmação do Sankhya faria
 * com o usuário dela, e registrar em TORREC. O ajuste de peso NÃO entra aqui: a Torre chama AjustePesoSP.gerar
 * depois, em outra transação (B4: a compra confirmada nunca fica presa ao ajuste).
 *
 * Confirmação silenciosa no padrão nativo (CONFIRMADO no bytecode do ArmazensGeraisHelper, modelcore 4.35):
 * BarramentoRegra.build(CentralFaturamento.class, "regrasConfirmacaoSilenciosa.xml", auth) + ConfirmacaoNotaHelper.
 * Se a confirmação pedir liberação, a nota fica 'A' com a solicitação gravada na TSILIB (como na tela nativa) e o
 * serviço responde LIBERACAO_PENDENTE - liberar é do cliente; depois de liberado, aprova de novo.
 */
public class RecebimentoHelper {

    /** Resultado da aprovação. */
    public static final class Aprovacao {
        public final String situacao; // APROVADO | LIBERACAO_PENDENTE
        public final boolean jaExistia;
        public final BigDecimal nurec;
        public final List<String> liberacoes;

        Aprovacao(String situacao, boolean jaExistia, BigDecimal nurec, List<String> liberacoes) {
            this.situacao = situacao;
            this.jaExistia = jaExistia;
            this.nurec = nurec;
            this.liberacoes = liberacoes;
        }
    }

    private final EntityFacade dwf = EntityFacadeFactory.getDWFFacade();

    public Aprovacao aprovar(BigDecimal nunota, BigDecimal nuconf, BigDecimal codusu) throws Exception {
        DynamicVO compra = compraValidada(nunota, nuconf, codusu);

        DynamicVO rec = recebimento(nunota, nuconf);
        if (rec != null && RecebimentoRegras.APROVADO.equals(rec.asString("SITUACAO"))) {
            return new Aprovacao("APROVADO", true, rec.asBigDecimal("NUREC"), new ArrayList<String>());
        }
        if (rec != null && RecebimentoRegras.DEVOLVIDO.equals(rec.asString("SITUACAO"))) {
            throw new IllegalStateException("O recebimento da compra " + nunota + " (conferência " + nuconf
                + ") foi devolvido para recontagem - aprove a nova conferência.");
        }

        if (!"L".equals(compra.asString("STATUSNOTA"))) {
            List<String> pendentes = confirmarComo(nunota, codusu);
            String status = umValor("SELECT STATUSNOTA FROM TGFCAB WHERE NUNOTA = :P", nunota);
            if (!"L".equals(status)) {
                // Fica como a tela nativa deixa: nota aberta + liberação solicitada. Nada em TORREC.
                if (pendentes.isEmpty()) pendentes.add("O Sankhya não confirmou a nota (status " + status + ").");
                return new Aprovacao("LIBERACAO_PENDENTE", false, null, pendentes);
            }
        }

        // A confirmação dispara o evento (ConfirmacaoCompraListener), que já pode ter marcado o recebimento 'PE' -> 'AP'.
        // O Aprovar registra o aprovador e a origem Torre por cima.
        DynamicVO atual = recebimento(nunota, nuconf);
        if (atual != null) {
            JapeFactory.dao("RecebimentoTorre").prepareToUpdate(atual)
                .set("SITUACAO", RecebimentoRegras.APROVADO)
                .set("CODUSUAPROV", codusu)
                .set("DHAPROV", TimeUtils.getNow())
                .set("ORIGEM", RecebimentoRegras.ORIGEM_TORRE)
                .update();
            return new Aprovacao("APROVADO", false, atual.asBigDecimal("NUREC"), new ArrayList<String>());
        }
        DynamicVO novo = JapeFactory.dao("RecebimentoTorre").create()
            .set("CODEMP", compra.asBigDecimal("CODEMP"))
            .set("NUNOTA", nunota)
            .set("NUCONF", nuconf)
            .set("SITUACAO", RecebimentoRegras.APROVADO)
            .set("CODUSUAPROV", codusu)
            .set("DHAPROV", TimeUtils.getNow())
            .set("ORIGEM", RecebimentoRegras.ORIGEM_TORRE)
            .save();
        return new Aprovacao("APROVADO", false, novo.asBigDecimal("NUREC"), new ArrayList<String>());
    }

    /**
     * Registra a devolução para recontagem (a Torre já pediu a recontagem pelo serviço nativo
     * ConferenciaSP.solicitarRecontagem). Recebimento já aprovado não pode ser devolvido.
     */
    public BigDecimal registrarDevolucao(BigDecimal nunota, BigDecimal nuconf, BigDecimal codusu, String motivo) throws Exception {
        if (motivo == null || motivo.trim().isEmpty()) throw new IllegalArgumentException("Informe o motivo da devolução.");
        DynamicVO compra = (DynamicVO) dwf.findEntityByPrimaryKeyAsVO("CabecalhoNota", nunota);
        if (compra == null) throw new IllegalStateException("Compra " + nunota + " não encontrada.");
        exigirAprovador(compra.asBigDecimal("CODEMP"), codusu);
        DynamicVO rec = recebimento(nunota, nuconf);
        if (rec != null && RecebimentoRegras.APROVADO.equals(rec.asString("SITUACAO"))) {
            throw new IllegalStateException("O recebimento da compra " + nunota + " já foi aprovado - não pode ser devolvido.");
        }
        String m = motivo.trim();
        if (rec != null && RecebimentoRegras.PENDENTE.equals(rec.asString("SITUACAO"))) {
            JapeFactory.dao("RecebimentoTorre").prepareToUpdate(rec)
                .set("SITUACAO", RecebimentoRegras.DEVOLVIDO)
                .set("CODUSUDEV", codusu)
                .set("DHDEV", TimeUtils.getNow())
                .set("MOTIVODEV", m.length() > 400 ? m.substring(0, 400) : m)
                .update();
            return rec.asBigDecimal("NUREC");
        }
        if (rec != null) return rec.asBigDecimal("NUREC"); // já registrado: idempotente
        DynamicVO novo = JapeFactory.dao("RecebimentoTorre").create()
            .set("CODEMP", compra.asBigDecimal("CODEMP"))
            .set("NUNOTA", nunota)
            .set("NUCONF", nuconf)
            .set("SITUACAO", RecebimentoRegras.DEVOLVIDO)
            .set("CODUSUDEV", codusu)
            .set("DHDEV", TimeUtils.getNow())
            .set("MOTIVODEV", m.length() > 400 ? m.substring(0, 400) : m)
            .save();
        return novo.asBigDecimal("NUREC");
    }

    /**
     * Plano B (25/11/2026): a Torre finalizou a conferência da compra — marca o recebimento como pendente ('PE'), para o
     * evento de confirmação saber que esta compra é da Torre. Já existe para esta conferência: não mexe.
     */
    public void registrarPendente(BigDecimal nunota, BigDecimal nuconf) throws Exception {
        if (recebimento(nunota, nuconf) != null) return;
        DynamicVO compra = (DynamicVO) dwf.findEntityByPrimaryKeyAsVO("CabecalhoNota", nunota);
        if (compra == null) throw new IllegalStateException("Compra " + nunota + " não encontrada.");
        if ("L".equals(compra.asString("STATUSNOTA"))) return; // já confirmada antes do registro: nada a esperar
        JapeFactory.dao("RecebimentoTorre").create()
            .set("CODEMP", compra.asBigDecimal("CODEMP"))
            .set("NUNOTA", nunota)
            .set("NUCONF", nuconf)
            .set("SITUACAO", RecebimentoRegras.PENDENTE)
            .save();
    }

    // ─── Internos ───────────────────────────────────────────────────────────────────────────────────

    private DynamicVO compraValidada(BigDecimal nunota, BigDecimal nuconf, BigDecimal codusu) throws Exception {
        if (codusu == null || codusu.signum() <= 0) throw new IllegalArgumentException("Usuário aprovador não informado.");
        DynamicVO compra = nunota == null ? null : (DynamicVO) dwf.findEntityByPrimaryKeyAsVO("CabecalhoNota", nunota);
        if (compra == null) throw new IllegalStateException("Compra " + nunota + " não encontrada.");
        exigirAprovador(compra.asBigDecimal("CODEMP"), codusu);
        String status = nuconf == null ? null : umValor("SELECT STATUS FROM TGFCON2 WHERE NUCONF = :P", nuconf);
        String erro = RecebimentoRegras.validarConferencia(nunota, nuconf, compra.asBigDecimal("NUCONFATUAL"), status);
        if (erro != null) throw new IllegalStateException(erro);
        return compra;
    }

    private void exigirAprovador(BigDecimal codemp, BigDecimal codusu) throws Exception {
        DynamicVO cfg = JapeFactory.dao("ConfiguracaoTorre").findByPK(codemp);
        String lista = cfg == null ? null : cfg.asString("USUAPROVREC");
        if (!RecebimentoRegras.podeAprovar(lista, codusu)) {
            throw new IllegalStateException("O usuário " + codusu + " não está autorizado a aprovar recebimentos da empresa "
                + codemp + " (Torre de Operacao > Configuracao > Usuarios que aprovam recebimento).");
        }
    }

    private DynamicVO recebimento(BigDecimal nunota, BigDecimal nuconf) throws Exception {
        Collection<DynamicVO> l = JapeFactory.dao("RecebimentoTorre").find("this.NUNOTA = ? AND this.NUCONF = ?", nunota, nuconf);
        return l.isEmpty() ? null : l.iterator().next();
    }

    /**
     * Confirma a nota com o usuário do aprovador como usuário corrente e devolve o texto das liberações solicitadas.
     * O usuário corrente da chamada (integração) volta SEMPRE no finally.
     */
    private List<String> confirmarComo(BigDecimal nunota, BigDecimal codusu) throws Exception {
        String nomeUsu = umValor("SELECT NOMEUSU FROM TSIUSU WHERE CODUSU = :P", codusu);
        if (nomeUsu == null) throw new IllegalStateException("Usuário " + codusu + " não existe no Sankhya.");
        String grupo = umValor("SELECT CODGRUPO FROM TSIUSU WHERE CODUSU = :P", codusu);

        AuthenticationInfo original = AuthenticationInfo.getCurrentOrNull();
        AuthenticationInfo aprovador = new AuthenticationInfo(nomeUsu, codusu,
            grupo == null ? BigDecimal.ZERO : new BigDecimal(grupo), Integer.valueOf(0));
        List<String> pendentes = new ArrayList<String>();
        aprovador.makeCurrent();
        try {
            BarramentoRegra regras = BarramentoRegra.build(CentralFaturamento.class, "regrasConfirmacaoSilenciosa.xml", aprovador);
            ConfirmacaoNotaHelper.confirmarNota(nunota, regras);
            Collection<LiberacaoSolicitada> libs = regras.getLiberacoesSolicitadas();
            if (libs != null) {
                for (LiberacaoSolicitada l : libs) {
                    pendentes.add("Evento " + l.getEvento() + (l.getDescricao() == null ? "" : ": " + l.getDescricao()));
                }
            }
        } finally {
            if (original != null) original.makeCurrent();
            else AuthenticationInfo.unregistry();
        }
        return pendentes;
    }

    private String umValor(String sqlTexto, BigDecimal valor) throws Exception {
        JdbcWrapper jdbc = dwf.getJdbcWrapper();
        jdbc.openSession();
        ResultSet rs = null;
        try {
            NativeSql sql = new NativeSql(jdbc);
            sql.setNamedParameter("P", valor);
            rs = sql.executeQuery(sqlTexto);
            if (!rs.next()) return null;
            String v = rs.getString(1);
            return v == null ? null : v.trim();
        } finally {
            if (rs != null) rs.close();
            JdbcWrapper.closeSession(jdbc);
        }
    }
}
