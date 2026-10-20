package wms.backend.impressao

import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.javatime.timestamp
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import wms.backend.tenancy.TenantTx
import java.time.Instant
import java.util.UUID

object ImpressaoOcTable : Table("app.impressao_oc") {
    val id = uuid("id")
    val tenantId = uuid("tenant_id")
    val ordemCarga = long("ordem_carga")
    val status = text("status")
    val motivo = text("motivo")
    val notas = integer("notas")
    val pdf = binary("pdf").nullable()
    val erro = text("erro").nullable()
    val tentativas = integer("tentativas")
    val agente = text("agente").nullable()
    val solicitadoPor = text("solicitado_por").nullable()
    val criadoEm = timestamp("criado_em")
    val atualizadoEm = timestamp("atualizado_em")
    val entregueEm = timestamp("entregue_em").nullable()
    val impressoEm = timestamp("impresso_em").nullable()
    override val primaryKey = PrimaryKey(id)
}

object StatusImpressao {
    const val AGUARDANDO_SEFAZ = "aguardando_sefaz"
    const val PRONTO = "pronto"
    const val IMPRIMINDO = "imprimindo"
    const val IMPRESSO = "impresso"
    const val ERRO = "erro"
}

@Serializable
data class ImpressaoOcDto(
    val id: String,
    val ordemCarga: Long,
    val status: String,
    val motivo: String,
    val notas: Int,
    val erro: String? = null,
    val agente: String? = null,
    val solicitadoPor: String? = null,
    val criadoEm: String,
    val impressoEm: String? = null,
    val atualizadoEm: String? = null,
)

object ImpressaoOcRepository {
    /** Devolve o pedido de reentrega se o agente não confirmar a impressão nesse tempo. */
    private const val LIMITE_IMPRIMINDO_SEG = 5 * 60L

    fun enfileirar(tenantId: UUID, oc: Long, motivo: String, solicitadoPor: String?): UUID = TenantTx.run(tenantId) {
        val novo = UUID.randomUUID()
        ImpressaoOcTable.insert {
            it[id] = novo
            it[ImpressaoOcTable.tenantId] = tenantId
            it[ordemCarga] = oc
            it[status] = StatusImpressao.AGUARDANDO_SEFAZ
            it[ImpressaoOcTable.motivo] = motivo
            it[notas] = 0
            it[tentativas] = 0
            it[ImpressaoOcTable.solicitadoPor] = solicitadoPor
            it[criadoEm] = Instant.now()
            it[atualizadoEm] = Instant.now()
        }
        novo
    }

    fun aguardandoSefaz(tenantId: UUID): List<ImpressaoOcDto> = TenantTx.run(tenantId) {
        ImpressaoOcTable.selectAll()
            .where { (ImpressaoOcTable.tenantId eq tenantId) and (ImpressaoOcTable.status eq StatusImpressao.AGUARDANDO_SEFAZ) }
            .orderBy(ImpressaoOcTable.criadoEm to SortOrder.ASC)
            .map { it.dto() }
    }

    fun marcarPronto(tenantId: UUID, id: UUID, pdf: ByteArray, notas: Int) = TenantTx.run(tenantId) {
        ImpressaoOcTable.update({ (ImpressaoOcTable.tenantId eq tenantId) and (ImpressaoOcTable.id eq id) }) {
            it[status] = StatusImpressao.PRONTO
            it[ImpressaoOcTable.pdf] = pdf
            it[ImpressaoOcTable.notas] = notas
            it[erro] = null
            it[atualizadoEm] = Instant.now()
        }
    }

    /** Ainda esperando a SEFAZ: só registra a tentativa e o motivo (aparece no card da Fila). */
    fun registrarEspera(tenantId: UUID, id: UUID, notas: Int, motivo: String) = TenantTx.run(tenantId) {
        ImpressaoOcTable.update({ (ImpressaoOcTable.tenantId eq tenantId) and (ImpressaoOcTable.id eq id) }) {
            it[ImpressaoOcTable.notas] = notas
            it[erro] = motivo
            it[tentativas] = with(SqlExpressionBuilder) { ImpressaoOcTable.tentativas + 1 }
            it[atualizadoEm] = Instant.now()
        }
    }

    fun marcarErro(tenantId: UUID, id: UUID, motivo: String) = TenantTx.run(tenantId) {
        ImpressaoOcTable.update({ (ImpressaoOcTable.tenantId eq tenantId) and (ImpressaoOcTable.id eq id) }) {
            it[status] = StatusImpressao.ERRO
            it[erro] = motivo.take(1000)
            it[atualizadoEm] = Instant.now()
        }
    }

    /**
     * Entrega o job pronto mais antigo ao agente (vira 'imprimindo'). Job 'imprimindo' há mais de 5 min
     * sem confirmação (agente caiu no meio) volta pra fila antes da escolha.
     */
    fun proximoParaAgente(tenantId: UUID, agente: String): ImpressaoOcDto? = TenantTx.run(tenantId) {
        val limite = Instant.now().minusSeconds(LIMITE_IMPRIMINDO_SEG)
        ImpressaoOcTable.update({
            (ImpressaoOcTable.tenantId eq tenantId) and (ImpressaoOcTable.status eq StatusImpressao.IMPRIMINDO) and
                (ImpressaoOcTable.entregueEm lessEq limite)
        }) { it[status] = StatusImpressao.PRONTO }
        val linha = ImpressaoOcTable.selectAll()
            .where { (ImpressaoOcTable.tenantId eq tenantId) and (ImpressaoOcTable.status eq StatusImpressao.PRONTO) }
            .orderBy(ImpressaoOcTable.criadoEm to SortOrder.ASC)
            .limit(1)
            .forUpdate()
            .firstOrNull() ?: return@run null
        val id = linha[ImpressaoOcTable.id]
        ImpressaoOcTable.update({ (ImpressaoOcTable.tenantId eq tenantId) and (ImpressaoOcTable.id eq id) }) {
            it[status] = StatusImpressao.IMPRIMINDO
            it[ImpressaoOcTable.agente] = agente
            it[entregueEm] = Instant.now()
            it[atualizadoEm] = Instant.now()
        }
        linha.dto().copy(status = StatusImpressao.IMPRIMINDO, agente = agente)
    }

    fun contarProntos(tenantId: UUID): Long = TenantTx.run(tenantId) {
        ImpressaoOcTable.selectAll()
            .where { (ImpressaoOcTable.tenantId eq tenantId) and (ImpressaoOcTable.status eq StatusImpressao.PRONTO) }
            .count()
    }

    fun pdf(tenantId: UUID, id: UUID): ByteArray? = TenantTx.run(tenantId) {
        ImpressaoOcTable.select(ImpressaoOcTable.pdf)
            .where { (ImpressaoOcTable.tenantId eq tenantId) and (ImpressaoOcTable.id eq id) }
            .firstOrNull()?.get(ImpressaoOcTable.pdf)
    }

    /** Resultado do agente: ok = impresso; senão erro com a mensagem da impressora. */
    fun registrarResultado(tenantId: UUID, id: UUID, ok: Boolean, mensagem: String?): Boolean = TenantTx.run(tenantId) {
        ImpressaoOcTable.update({
            (ImpressaoOcTable.tenantId eq tenantId) and (ImpressaoOcTable.id eq id) and (ImpressaoOcTable.status eq StatusImpressao.IMPRIMINDO)
        }) {
            it[status] = if (ok) StatusImpressao.IMPRESSO else StatusImpressao.ERRO
            it[erro] = if (ok) null else (mensagem ?: "falha na impressora").take(1000)
            if (ok) it[impressoEm] = Instant.now()
            it[atualizadoEm] = Instant.now()
        } > 0
    }

    /** Painel de impressão: jobs das últimas [horas] horas, mais recentes primeiro (sem o PDF). */
    fun recentes(tenantId: UUID, horas: Long, limite: Int): List<ImpressaoOcDto> = TenantTx.run(tenantId) {
        val desde = Instant.now().minusSeconds(horas * 3600)
        ImpressaoOcTable.select(ImpressaoOcTable.columns - ImpressaoOcTable.pdf)
            .where { (ImpressaoOcTable.tenantId eq tenantId) and (ImpressaoOcTable.criadoEm greaterEq desde) }
            .orderBy(ImpressaoOcTable.criadoEm to SortOrder.DESC)
            .limit(limite)
            .map { it.dto() }
    }

    /** Último job de cada OC pedida — pro card da Fila. */
    fun ultimosPorOc(tenantId: UUID, ocs: Collection<Long>): Map<Long, ImpressaoOcDto> {
        if (ocs.isEmpty()) return emptyMap()
        return TenantTx.run(tenantId) {
            ImpressaoOcTable.select(ImpressaoOcTable.columns - ImpressaoOcTable.pdf)
                .where { (ImpressaoOcTable.tenantId eq tenantId) and (ImpressaoOcTable.ordemCarga inList ocs) }
                .orderBy(ImpressaoOcTable.criadoEm to SortOrder.DESC)
                .map { it.dto() }
                .groupBy { it.ordemCarga }
                .mapValues { it.value.first() }
        }
    }

    private fun ResultRow.dto() = ImpressaoOcDto(
        id = this[ImpressaoOcTable.id].toString(),
        ordemCarga = this[ImpressaoOcTable.ordemCarga],
        status = this[ImpressaoOcTable.status],
        motivo = this[ImpressaoOcTable.motivo],
        notas = this[ImpressaoOcTable.notas],
        erro = this[ImpressaoOcTable.erro],
        agente = this[ImpressaoOcTable.agente],
        solicitadoPor = this[ImpressaoOcTable.solicitadoPor],
        criadoEm = this[ImpressaoOcTable.criadoEm].toString(),
        impressoEm = this[ImpressaoOcTable.impressoEm]?.toString(),
        atualizadoEm = this[ImpressaoOcTable.atualizadoEm].toString(),
    )
}

@Serializable
data class AgenteImpressaoDto(val nome: String, val vistoEm: String, val online: Boolean)

@Serializable
data class PainelImpressaoDto(val agentes: List<AgenteImpressaoDto>, val jobs: List<ImpressaoOcDto>)

/**
 * Última vez que cada agente consultou a fila (em memória — some no restart do backend até o agente
 * consultar de novo, ~5 s). O agente pergunta a cada 5 s; sem notícia há 30 s = offline.
 */
object AgentesImpressao {
    private const val ONLINE_SEG = 30L
    private val vistos = java.util.concurrent.ConcurrentHashMap<UUID, java.util.concurrent.ConcurrentHashMap<String, Instant>>()

    fun visto(tenantId: UUID, agente: String) {
        vistos.computeIfAbsent(tenantId) { java.util.concurrent.ConcurrentHashMap() }[agente] = Instant.now()
    }

    fun listar(tenantId: UUID): List<AgenteImpressaoDto> {
        val limite = Instant.now().minusSeconds(ONLINE_SEG)
        return (vistos[tenantId] ?: emptyMap<String, Instant>()).entries
            .sortedByDescending { it.value }
            .map { (nome, em) -> AgenteImpressaoDto(nome, em.toString(), em.isAfter(limite)) }
    }
}
