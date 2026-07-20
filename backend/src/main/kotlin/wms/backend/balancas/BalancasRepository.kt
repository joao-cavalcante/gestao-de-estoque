package wms.backend.balancas

import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import wms.backend.tenancy.TenantTx
import java.time.Instant
import java.util.UUID

object BalancasRepository {

    fun listar(tenantId: UUID): List<BalancaDto> = TenantTx.run(tenantId) {
        BalancasTable.selectAll()
            .where { BalancasTable.tenantId eq tenantId }
            .orderBy(BalancasTable.nome)
            .map { it.toDto() }
    }

    fun listarAtivas(tenantId: UUID): List<BalancaDto> = TenantTx.run(tenantId) {
        BalancasTable.selectAll()
            .where { (BalancasTable.tenantId eq tenantId) and (BalancasTable.ativo eq true) }
            .orderBy(BalancasTable.nome)
            .map { it.toDto() }
    }

    fun buscarPorId(tenantId: UUID, id: UUID): BalancaDto? = TenantTx.run(tenantId) {
        BalancasTable.selectAll()
            .where { (BalancasTable.tenantId eq tenantId) and (BalancasTable.id eq id) }
            .singleOrNull()
            ?.toDto()
    }

    fun criar(tenantId: UUID, req: SalvarBalancaRequest): BalancaDto {
        val id = UUID.randomUUID()
        val agora = Instant.now()
        TenantTx.run(tenantId) {
            BalancasTable.insert {
                it[BalancasTable.id] = id
                it[BalancasTable.tenantId] = tenantId
                it[nome] = req.nome
                it[fabricante] = req.fabricante
                it[tipoComunicacao] = req.tipoComunicacao
                it[portaCom] = req.portaCom
                it[baudRate] = req.baudRate
                it[dataBits] = req.dataBits
                it[paridade] = req.paridade
                it[stopBits] = req.stopBits
                it[protocoloSerial] = req.protocoloSerial
                it[ip] = req.ip
                it[porta] = req.porta
                it[rota] = req.rota
                it[ativo] = req.ativo
                it[criadoEm] = agora
                it[atualizadoEm] = agora
            }
        }
        return BalancaDto(
            id.toString(), req.nome, req.fabricante, req.tipoComunicacao, req.portaCom, req.baudRate,
            req.dataBits, req.paridade, req.stopBits, req.protocoloSerial, req.ip, req.porta, req.rota, req.ativo,
        )
    }

    fun atualizar(tenantId: UUID, id: UUID, req: SalvarBalancaRequest): Boolean = TenantTx.run(tenantId) {
        val linhas = BalancasTable.update({ (BalancasTable.tenantId eq tenantId) and (BalancasTable.id eq id) }) {
            it[nome] = req.nome
            it[fabricante] = req.fabricante
            it[tipoComunicacao] = req.tipoComunicacao
            it[portaCom] = req.portaCom
            it[baudRate] = req.baudRate
            it[dataBits] = req.dataBits
            it[paridade] = req.paridade
            it[stopBits] = req.stopBits
            it[protocoloSerial] = req.protocoloSerial
            it[ip] = req.ip
            it[porta] = req.porta
            it[rota] = req.rota
            it[ativo] = req.ativo
            it[atualizadoEm] = Instant.now()
        }
        linhas > 0
    }

    fun remover(tenantId: UUID, id: UUID): Boolean = TenantTx.run(tenantId) {
        BalancasTable.deleteWhere { (BalancasTable.tenantId eq tenantId) and (BalancasTable.id eq id) } > 0
    }

    private fun ResultRow.toDto() = BalancaDto(
        id = this[BalancasTable.id].toString(),
        nome = this[BalancasTable.nome],
        fabricante = this[BalancasTable.fabricante],
        tipoComunicacao = this[BalancasTable.tipoComunicacao],
        portaCom = this[BalancasTable.portaCom],
        baudRate = this[BalancasTable.baudRate],
        dataBits = this[BalancasTable.dataBits],
        paridade = this[BalancasTable.paridade],
        stopBits = this[BalancasTable.stopBits],
        protocoloSerial = this[BalancasTable.protocoloSerial],
        ip = this[BalancasTable.ip],
        porta = this[BalancasTable.porta],
        rota = this[BalancasTable.rota],
        ativo = this[BalancasTable.ativo],
    )
}
