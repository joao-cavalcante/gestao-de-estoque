package wms.backend.transferencia

import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import wms.backend.tenancy.TenantTx
import java.util.UUID

/** Modelo de nota (TOP/empresa/natureza) configurado por tenant e tipo de atividade — obrigatório antes de qualquer lançamento. */
object ModelosNotaRepository {
    fun buscarPorTipo(tenantId: UUID, tipo: String): ModeloNotaDto? = TenantTx.run(tenantId) {
        ModelosNotaTable.selectAll()
            .where { (ModelosNotaTable.tenantId eq tenantId) and (ModelosNotaTable.tipo eq tipo) and (ModelosNotaTable.ativo eq true) }
            .singleOrNull()
            ?.let {
                ModeloNotaDto(
                    codtop = it[ModelosNotaTable.codtop],
                    codemp = it[ModelosNotaTable.codemp],
                    codnat = it[ModelosNotaTable.codnat],
                    ativo = it[ModelosNotaTable.ativo],
                )
            }
    }
}
