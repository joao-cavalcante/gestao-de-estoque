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
        val autorizados = BalancaUsuariosTable.selectAll()
            .where { BalancaUsuariosTable.tenantId eq tenantId }
            .groupingBy { it[BalancaUsuariosTable.balancaId] }
            .eachCount()
        BalancasTable.selectAll()
            .where { BalancasTable.tenantId eq tenantId }
            .orderBy(BalancasTable.nome)
            .map { it.toDto().copy(usuariosAutorizados = autorizados[it[BalancasTable.id]] ?: 0) }
    }

    fun listarAtivas(tenantId: UUID): List<BalancaDto> = TenantTx.run(tenantId) {
        BalancasTable.selectAll()
            .where { (BalancasTable.tenantId eq tenantId) and (BalancasTable.ativo eq true) }
            .orderBy(BalancasTable.nome)
            .map { it.toDto() }
    }

    /**
     * Balanças ATIVAS que o usuário pode usar — regra por recurso (PermissoesRecurso): balança sem
     * usuário vinculado é de todos; com vínculo, só dos vinculados. Antes era por usuário (usuário sem
     * vínculo via todas) — a tabela estava vazia, então nada muda pra quem não configurou.
     */
    fun listarParaUsuario(claims: wms.backend.auth.ClaimsToken): List<BalancaDto> {
        val restritas = wms.backend.permissoes.PermissoesRecurso.balancasRestritas(claims.tenantId)
        return listarAtivas(claims.tenantId).filter {
            wms.backend.permissoes.PermissoesRecurso.podeUsarBalanca(claims, restritas, UUID.fromString(it.id))
        }
    }

    /** Substitui os vínculos de uma balança por completo (edição via tela de balanças). */
    fun definirUsuarios(tenantId: UUID, balancaId: UUID, usuarioIds: List<UUID>): Unit = TenantTx.run(tenantId) {
        BalancaUsuariosTable.deleteWhere { (BalancaUsuariosTable.tenantId eq tenantId) and (BalancaUsuariosTable.balancaId eq balancaId) }
        usuarioIds.forEach { usuarioId ->
            BalancaUsuariosTable.insert {
                it[id] = UUID.randomUUID()
                it[BalancaUsuariosTable.tenantId] = tenantId
                it[BalancaUsuariosTable.balancaId] = balancaId
                it[BalancaUsuariosTable.usuarioId] = usuarioId
            }
        }
    }

    fun listarUsuarios(tenantId: UUID, balancaId: UUID): List<String> = TenantTx.run(tenantId) {
        BalancaUsuariosTable.selectAll()
            .where { (BalancaUsuariosTable.tenantId eq tenantId) and (BalancaUsuariosTable.balancaId eq balancaId) }
            .map { it[BalancaUsuariosTable.usuarioId].toString() }
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
