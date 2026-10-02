package wms.backend.consultaprodutos

import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.like
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.lowerCase
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import wms.backend.produtos.CodigosBarraCacheTable
import wms.backend.produtos.ProdutosCacheTable
import wms.backend.tenancy.TenantTx
import java.util.UUID

/**
 * Busca de produto no catálogo LOCAL (app.produtos_cache / app.codigos_barra_cache, mantidos pelo
 * ProdutoCatalogoSyncWorker) — sem ida ao Sankhya só pra achar o produto. O saldo vem depois, ao vivo.
 */
object ConsultaProdutosRepository {

    data class ProdutoCatalogo(
        val codprod: Int,
        val descricao: String,
        val complemento: String?,
        val marca: String?,
        val referencia: String?,
    )

    /**
     * Texto livre: cada palavra precisa aparecer na descrição, complemento, marca ou referência
     * (ordem livre, sem diferenciar maiúscula). Termo inteiro igual a um código de barras ou ao
     * CODPROD também acha o produto. Devolve até [limite] + 1 linhas (a extra indica "tem mais").
     */
    fun buscar(tenantId: UUID, termo: String, limite: Int): List<ProdutoCatalogo> = TenantTx.run(tenantId) {
        val porCodigo = mutableSetOf<Int>()
        termo.toIntOrNull()?.let { porCodigo += it }
        CodigosBarraCacheTable.selectAll()
            .where { (CodigosBarraCacheTable.tenantId eq tenantId) and (CodigosBarraCacheTable.codbarra eq termo) }
            .forEach { porCodigo += it[CodigosBarraCacheTable.codprod] }

        val palavras = termo.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val porTexto: Op<Boolean> = palavras.fold(Op.TRUE as Op<Boolean>) { acc, p ->
            val padrao = "%$p%"
            acc and (
                (ProdutosCacheTable.descrprod.lowerCase() like padrao) or
                    (ProdutosCacheTable.compldesc.lowerCase() like padrao) or
                    (ProdutosCacheTable.marca.lowerCase() like padrao) or
                    (ProdutosCacheTable.referencia.lowerCase() like padrao)
                )
        }
        val filtro = if (porCodigo.isEmpty()) porTexto else (porTexto or (ProdutosCacheTable.codprod inList porCodigo))

        ProdutosCacheTable.selectAll()
            .where { (ProdutosCacheTable.tenantId eq tenantId) and filtro }
            .orderBy(ProdutosCacheTable.descrprod to SortOrder.ASC)
            .limit(limite + 1)
            .map {
                ProdutoCatalogo(
                    codprod = it[ProdutosCacheTable.codprod],
                    descricao = it[ProdutosCacheTable.descrprod],
                    complemento = it[ProdutosCacheTable.compldesc],
                    marca = it[ProdutosCacheTable.marca],
                    referencia = it[ProdutosCacheTable.referencia],
                )
            }
            // Match exato por código (CODPROD/código de barras) primeiro.
            .sortedBy { if (it.codprod in porCodigo) 0 else 1 }
    }
}
