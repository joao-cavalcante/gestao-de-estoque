package wms.backend.aguardandonota

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import wms.backend.erp.SankhyaDbExplorerClient
import wms.backend.separacao.NotaStatus
import wms.backend.separacao.SeparacaoRepository
import wms.backend.separacao.SeparacaoSessoesTable
import wms.backend.tenancy.TenantTx
import java.util.UUID

/** Pedido da fila aguardando nota (vai em TarefaApiDto.notaPendente) — o card mostra "Faturar" com esta sessão. */
@Serializable
data class NotaPendenteDto(
    /** Sessão a faturar/confirmar (a conferência concluída mais recente da nota). */
    val sessaoId: String,
    /** Último motivo de recusa do Sankhya (faturar ou confirmar). */
    val erro: String? = null,
)

private data class SessaoAguardando(
    val sessaoId: UUID,
    val nunota: Long,
    val erro: String?,
    val desde: java.time.Instant?,
)

private data class SituacaoNota(val statusConf: String?, val geradas: List<Long>, val semConfirmar: List<Long>)

/**
 * "Aguardando Nota" (V58): pedido conferido cuja CCO pede faturamento (FATAOCONCLUIR='S') e que ainda não
 * tem nota FATURADA E CONFIRMADA — porque o faturar/confirmar falhou ou porque o operador pulou o faturamento.
 * Fluxo (usuário, 08/10/2026): conferir → carregar → NOTA (por pedido, TOP escolhida) → fechar a OC; a OC só
 * fecha com todos os pedidos com nota confirmada.
 *
 * A marca é local (app.separacao_sessoes.nota_status), mas quem decide é o Sankhya: o sync de fundo revalida
 * os pedidos numa consulta só — nota faturada+confirmada por fora sai sozinha,
 * conferência reaberta (recontagem/exclusão) deixa de valer, e conferência ainda em corte ('C') segue marcada.
 * Tudo aparece na Fila de Conferência (status AGUARDANDO NOTA + "Faturar" no card) — sem tela própria.
 */
object AguardandoNotaService {
    private val STATUS_CONF_FINALIZADA = setOf("F", "D", "RF", "RD")

    /** Sessões marcadas 'aguardando' — só a mais recente de cada nota vale (recontagem gera sessão nova). */
    private fun sessoesAguardando(tenantId: UUID, nunotas: List<Long>? = null): List<SessaoAguardando> = TenantTx.run(tenantId) {
        SeparacaoSessoesTable.selectAll()
            .where {
                var filtro = (SeparacaoSessoesTable.tenantId eq tenantId) and (SeparacaoSessoesTable.notaStatus eq NotaStatus.AGUARDANDO)
                if (nunotas != null) filtro = filtro and (SeparacaoSessoesTable.nunota inList nunotas.map { it.toInt() })
                filtro
            }
            .orderBy(SeparacaoSessoesTable.atualizadoEm, SortOrder.DESC)
            .map {
                SessaoAguardando(
                    it[SeparacaoSessoesTable.id], it[SeparacaoSessoesTable.nunota].toLong(),
                    it[SeparacaoSessoesTable.notaErro], it[SeparacaoSessoesTable.notaAtualizadoEm],
                )
            }
    }

    /** Uma consulta pra todas as notas: STATUS da conferência atual, notas geradas e quais delas não estão confirmadas. */
    private suspend fun situacoes(tenantSlug: String, nunotas: Collection<Long>): Map<Long, SituacaoNota> {
        if (nunotas.isEmpty()) return emptyMap()
        val lista = nunotas.joinToString()
        val conf = SankhyaDbExplorerClient.executarQuery(
            tenantSlug,
            "SELECT C.NUNOTA, (SELECT F.STATUS FROM TGFCON2 F WHERE F.NUCONF = C.NUCONFATUAL) AS STATUS_CONF " +
                "FROM TGFCAB C WHERE C.NUNOTA IN ($lista)",
        ).mapNotNull { r -> r["NUNOTA"]?.toBigDecimalOrNull()?.toLong()?.let { it to r["STATUS_CONF"]?.trim()?.takeIf { s -> s.isNotEmpty() } } }
            .toMap()
        val geradas = SankhyaDbExplorerClient.executarQuery(
            tenantSlug,
            "SELECT DISTINCT V.NUNOTAORIG, V.NUNOTA, N.STATUSNOTA FROM TGFVAR V JOIN TGFCAB N ON N.NUNOTA = V.NUNOTA " +
                "WHERE V.NUNOTAORIG IN ($lista) AND V.NUNOTA <> V.NUNOTAORIG",
        ).mapNotNull { r ->
            val orig = r["NUNOTAORIG"]?.toBigDecimalOrNull()?.toLong() ?: return@mapNotNull null
            val nota = r["NUNOTA"]?.toBigDecimalOrNull()?.toLong() ?: return@mapNotNull null
            Triple(orig, nota, r["STATUSNOTA"]?.trim() == "L")
        }.groupBy { it.first }
        return nunotas.associateWith { n ->
            val g = geradas[n].orEmpty()
            SituacaoNota(conf[n], g.map { it.second }.distinct().sorted(), g.filter { !it.third }.map { it.second }.distinct().sorted())
        }
    }

    /**
     * Revalida as sessões aguardando contra o Sankhya e grava o desfecho. Devolve as que continuam
     * aguardando nota (com a situação), por nunota. Falha no Sankhya = mantém tudo como está.
     */
    private suspend fun revalidar(tenantSlug: String, tenantId: UUID, sessoes: List<SessaoAguardando>): Map<SessaoAguardando, SituacaoNota> {
        if (sessoes.isEmpty()) return emptyMap()
        // Recontagem gera sessão nova: das várias 'aguardando' da mesma nota só a mais recente vale.
        val maisRecente = sessoes.groupBy { it.nunota }.mapValues { it.value.first() }
        val antigas = sessoes.filter { maisRecente[it.nunota] != it }
        withContext(Dispatchers.IO) { antigas.forEach { SeparacaoRepository.atualizarNota(tenantId, it.sessaoId, null, null) } }

        val situacao = situacoes(tenantSlug, maisRecente.keys)
        val resultado = mutableMapOf<SessaoAguardando, SituacaoNota>()
        for (s in maisRecente.values) {
            val sit = situacao[s.nunota] ?: continue
            when {
                // Faturada e confirmada (pelo WMS ou direto no Sankhya).
                sit.geradas.isNotEmpty() && sit.semConfirmar.isEmpty() ->
                    withContext(Dispatchers.IO) { SeparacaoRepository.atualizarNota(tenantId, s.sessaoId, NotaStatus.OK, null) }
                // Nota gerada sem confirmar: vale mesmo se a conferência mudou depois.
                sit.geradas.isNotEmpty() -> resultado[s] = sit
                sit.statusConf in STATUS_CONF_FINALIZADA -> resultado[s] = sit
                // Ainda em corte: continua marcada, mas só aparece depois da liberação.
                sit.statusConf == "C" -> Unit
                // Reaberta (recontagem/excluída): a próxima finalização marca de novo.
                else -> withContext(Dispatchers.IO) { SeparacaoRepository.atualizarNota(tenantId, s.sessaoId, null, null) }
            }
        }
        return resultado
    }

    /** Revalida todas as marcadas contra o Sankhya — chamado no ciclo de sync de fundo (~60s), nunca na fila. */
    suspend fun revalidarTodos(tenantSlug: String, tenantId: UUID) {
        val marcadas = withContext(Dispatchers.IO) { sessoesAguardando(tenantId) }
        if (marcadas.isNotEmpty()) revalidar(tenantSlug, tenantId, marcadas)
    }

    /** Leitura só local (a fila roda a cada poucos segundos): nunota -> sessão a faturar + último erro. */
    fun pendentesLocal(tenantId: UUID): Map<Long, NotaPendenteDto> =
        sessoesAguardando(tenantId)
            .groupBy { it.nunota }
            .mapValues { (_, l) -> l.first().let { NotaPendenteDto(it.sessaoId.toString(), it.erro) } }
}
