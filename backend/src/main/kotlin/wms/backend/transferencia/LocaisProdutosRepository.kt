package wms.backend.transferencia

import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import wms.backend.tenancy.TenantTx
import java.math.BigDecimal
import java.util.UUID

sealed class ResultadoValidacaoProduto {
    data class Ok(val produto: ProdutoEstoqueDto) : ResultadoValidacaoProduto()
    object NaoEncontrado : ResultadoValidacaoProduto()
    object SemSaldo : ResultadoValidacaoProduto()
}

/**
 * STUB — valida contra cadastro próprio (app.locais_estoque / app.produtos_estoque /
 * app.saldos_produto_local), não contra o Sankhya (integração real ainda não definida,
 * ver comentário em V12__transferencias.sql).
 */
object LocaisProdutosRepository {
    fun validarLocal(tenantId: UUID, codigo: String): LocalEstoqueDto? = TenantTx.run(tenantId) {
        LocaisEstoqueTable.selectAll()
            .where { (LocaisEstoqueTable.tenantId eq tenantId) and (LocaisEstoqueTable.codigo eq codigo) }
            .singleOrNull()
            ?.let { LocalEstoqueDto(codigo = it[LocaisEstoqueTable.codigo], ativo = it[LocaisEstoqueTable.ativo]) }
    }

    fun validarProduto(tenantId: UUID, codigo: String, local: String?): ResultadoValidacaoProduto = TenantTx.run(tenantId) {
        val row = ProdutosEstoqueTable.selectAll()
            .where { (ProdutosEstoqueTable.tenantId eq tenantId) and (ProdutosEstoqueTable.codigo eq codigo) }
            .singleOrNull() ?: return@run ResultadoValidacaoProduto.NaoEncontrado

        if (local != null) {
            val saldo = SaldosProdutoLocalTable.selectAll()
                .where {
                    (SaldosProdutoLocalTable.tenantId eq tenantId) and
                        (SaldosProdutoLocalTable.codigoProduto eq codigo) and
                        (SaldosProdutoLocalTable.codigoLocal eq local)
                }
                .singleOrNull()
                ?.get(SaldosProdutoLocalTable.saldo) ?: BigDecimal.ZERO

            if (saldo <= BigDecimal.ZERO) return@run ResultadoValidacaoProduto.SemSaldo
        }

        ResultadoValidacaoProduto.Ok(
            ProdutoEstoqueDto(
                codigo = row[ProdutosEstoqueTable.codigo],
                nome = row[ProdutosEstoqueTable.nome],
                ctrl = row[ProdutosEstoqueTable.ctrl],
                modo = row[ProdutosEstoqueTable.modo],
                qtdEtiqueta = row[ProdutosEstoqueTable.qtdEtiqueta]?.toPlainString(),
                unidade = row[ProdutosEstoqueTable.unidade],
            ),
        )
    }
}
