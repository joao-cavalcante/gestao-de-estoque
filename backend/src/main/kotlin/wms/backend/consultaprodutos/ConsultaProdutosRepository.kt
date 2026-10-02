package wms.backend.consultaprodutos

import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.selectAll
import wms.backend.produtos.CodigosBarraCacheTable
import wms.backend.produtos.ProdutosCacheTable
import wms.backend.tenancy.TenantTx
import java.util.UUID

/**
 * Catálogo LOCAL (app.produtos_cache / app.codigos_barra_cache, mantidos pelo
 * ProdutoCatalogoSyncWorker) — a lista de produtos sai daqui, sem ida ao Sankhya; o saldo vem
 * depois, da instância Estoque.
 */
object ConsultaProdutosRepository {

    data class ProdutoCatalogo(
        val codprod: Int,
        val descricao: String,
        val complemento: String?,
        val marca: String?,
        val referencia: String?,
        val unidade: String?,
    )

    fun listarTodos(tenantId: UUID): List<ProdutoCatalogo> = TenantTx.run(tenantId) {
        ProdutosCacheTable.selectAll()
            .where { ProdutosCacheTable.tenantId eq tenantId }
            .orderBy(ProdutosCacheTable.descrprod to SortOrder.ASC)
            .map {
                ProdutoCatalogo(
                    codprod = it[ProdutosCacheTable.codprod],
                    descricao = it[ProdutosCacheTable.descrprod],
                    complemento = it[ProdutosCacheTable.compldesc],
                    marca = it[ProdutosCacheTable.marca],
                    referencia = it[ProdutosCacheTable.referencia],
                    unidade = it[ProdutosCacheTable.codvol],
                )
            }
    }

    /** codprod -> códigos de barra (pro filtro de texto achar o produto bipando o código). */
    fun codigosBarraPorProduto(tenantId: UUID): Map<Int, List<String>> = TenantTx.run(tenantId) {
        CodigosBarraCacheTable.selectAll()
            .where { CodigosBarraCacheTable.tenantId eq tenantId }
            .groupBy({ it[CodigosBarraCacheTable.codprod] }, { it[CodigosBarraCacheTable.codbarra].trim() })
            .mapValues { (_, v) -> v.filter { it.isNotEmpty() }.distinct() }
    }
}
