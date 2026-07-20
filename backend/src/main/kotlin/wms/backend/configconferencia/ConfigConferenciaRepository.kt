package wms.backend.configconferencia

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import wms.backend.tenancy.TenantTx
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.UUID

object ConfigConferenciaRepository {

    fun listar(tenantId: UUID): List<ConfigConferenciaListDto> = TenantTx.run(tenantId) {
        ConfigConferenciaTable.selectAll()
            .where { ConfigConferenciaTable.tenantId eq tenantId }
            .orderBy(ConfigConferenciaTable.nucco, SortOrder.ASC)
            .map {
                ConfigConferenciaListDto(
                    id = it[ConfigConferenciaTable.id].toString(),
                    nucco = it[ConfigConferenciaTable.nucco],
                    descricao = it[ConfigConferenciaTable.descricao],
                    sankhyaAtualizadoEm = it[ConfigConferenciaTable.sankhyaAtualizadoEm]?.let(DateTimeFormatter.ISO_INSTANT::format),
                )
            }
    }

    fun buscarPorNucco(tenantId: UUID, nucco: Int): ConfigConferenciaDetalheDto? = TenantTx.run(tenantId) {
        ConfigConferenciaTable.selectAll()
            .where { (ConfigConferenciaTable.tenantId eq tenantId) and (ConfigConferenciaTable.nucco eq nucco) }
            .singleOrNull()
            ?.let {
                ConfigConferenciaDetalheDto(
                    id = it[ConfigConferenciaTable.id].toString(),
                    nucco = it[ConfigConferenciaTable.nucco],
                    descricao = it[ConfigConferenciaTable.descricao],
                    campos = Json.decodeFromString(it[ConfigConferenciaTable.campos]),
                    sankhyaAtualizadoEm = it[ConfigConferenciaTable.sankhyaAtualizadoEm]?.let(DateTimeFormatter.ISO_INSTANT::format),
                    localAtualizadoEm = DateTimeFormatter.ISO_INSTANT.format(it[ConfigConferenciaTable.localAtualizadoEm]),
                )
            }
    }

    /**
     * Edição LOCAL — mescla os campos alterados no `campos` já sincronizado e marca
     * `local_atualizado_em`. NÃO escreve no Sankhya (Fase 2, fora de escopo aqui) — a
     * próxima sincronização (`upsertDoSankhya`) sobrescreve tudo de novo, então isto é
     * deliberadamente "vivo só até o próximo ciclo de sync", não uma fonte de verdade.
     */
    fun atualizarLocal(tenantId: UUID, nucco: Int, camposAlterados: Map<String, String?>): Boolean = TenantTx.run(tenantId) {
        val row = ConfigConferenciaTable.selectAll()
            .where { (ConfigConferenciaTable.tenantId eq tenantId) and (ConfigConferenciaTable.nucco eq nucco) }
            .singleOrNull() ?: return@run false

        val atuais: Map<String, String?> = Json.decodeFromString(row[ConfigConferenciaTable.campos])
        val mesclado = atuais + camposAlterados

        ConfigConferenciaTable.update({ (ConfigConferenciaTable.tenantId eq tenantId) and (ConfigConferenciaTable.nucco eq nucco) }) {
            it[campos] = Json.encodeToString(mesclado)
            it[localAtualizadoEm] = Instant.now()
        }
        true
    }

    /** Full refresh — apaga e regrava todas as configurações do tenant a cada sincronização (volume esperado é baixo, poucas TGFCCO por tenant). */
    fun upsertDoSankhya(tenantId: UUID, linhas: List<Map<String, String?>>): Int = TenantTx.run(tenantId) {
        val agora = Instant.now()

        ConfigConferenciaTable.deleteWhere { ConfigConferenciaTable.tenantId eq tenantId }

        var total = 0
        linhas.forEach { linha ->
            val nucco = linha["NUCCO"]?.toIntOrNull() ?: return@forEach
            val descricao = linha["DESCRICAO"] ?: "Configuração $nucco"

            ConfigConferenciaTable.insert {
                it[id] = UUID.randomUUID()
                it[ConfigConferenciaTable.tenantId] = tenantId
                it[ConfigConferenciaTable.nucco] = nucco
                it[ConfigConferenciaTable.descricao] = descricao
                it[campos] = Json.encodeToString(linha)
                it[sankhyaAtualizadoEm] = agora
                it[localAtualizadoEm] = agora
            }
            total++
        }
        total
    }
}
