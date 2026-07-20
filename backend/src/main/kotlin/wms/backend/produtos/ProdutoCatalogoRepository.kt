package wms.backend.produtos

import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.upsert
import wms.backend.tenancy.TenantTx
import java.time.Instant
import java.util.UUID

/** Linha crua de produto/código de barras — igual ao shape que já sai de SankhyaLoadRecordsClient.parseRows. */
typealias LinhaCatalogo = Map<String, String?>

object ProdutoCatalogoRepository {

    // ── Produto (TGFPRO) ──────────────────────────────────────────────────

    /** codprod -> DTALTER cru, pra comparação local do sync incremental. */
    fun mapaDtalterProdutos(tenantId: UUID): Map<Int, String?> = TenantTx.run(tenantId) {
        ProdutosCacheTable.selectAll()
            .where { ProdutosCacheTable.tenantId eq tenantId }
            .associate { it[ProdutosCacheTable.codprod] to it[ProdutosCacheTable.dtalterSankhya] }
    }

    /** Grava só quem o sync incremental já identificou como novo/alterado (linha completa). */
    fun upsertProdutos(tenantId: UUID, linhas: List<LinhaCatalogo>): Int = TenantTx.run(tenantId) {
        val agora = Instant.now()
        var total = 0
        linhas.forEach { linha ->
            val codprod = linha["CODPROD"]?.toIntOrNull() ?: return@forEach
            ProdutosCacheTable.upsert(ProdutosCacheTable.tenantId, ProdutosCacheTable.codprod) {
                it[id] = UUID.randomUUID()
                it[ProdutosCacheTable.tenantId] = tenantId
                it[ProdutosCacheTable.codprod] = codprod
                it[descrprod] = linha["DESCRPROD"]?.trim()?.takeIf { d -> d.isNotEmpty() } ?: "Produto $codprod"
                it[compldesc] = linha["COMPLDESC"]
                it[marca] = linha["MARCA"]
                it[referencia] = linha["REFERENCIA"]
                it[tipcontest] = linha["TIPCONTEST"]
                it[liscontest] = linha["LISCONTEST"]
                it[dtalterSankhya] = linha["DTALTER"]
                it[localAtualizadoEm] = agora
            }
            total++
        }
        total
    }

    /** Remove produtos que sumiram do Sankhya (não aparecem mais em nenhuma página da varredura completa). */
    fun removerProdutosPorCodigo(tenantId: UUID, codprods: Set<Int>): Int = TenantTx.run(tenantId) {
        if (codprods.isEmpty()) return@run 0
        ProdutosCacheTable.deleteWhere { (ProdutosCacheTable.tenantId eq tenantId) and (ProdutosCacheTable.codprod inList codprods) }
    }

    // ── Código de Barras (TGFBAR) ─────────────────────────────────────────

    /** (codprod,codvol,codbarra) -> DHALTER cru, pra comparação local do sync incremental. codvol é '' (não null) — ver V20. */
    fun mapaDhalterBar(tenantId: UUID): Map<Triple<Int, String, String>, String?> = TenantTx.run(tenantId) {
        CodigosBarraCacheTable.selectAll()
            .where { CodigosBarraCacheTable.tenantId eq tenantId }
            .associate {
                Triple(it[CodigosBarraCacheTable.codprod], it[CodigosBarraCacheTable.codvol], it[CodigosBarraCacheTable.codbarra]) to
                    it[CodigosBarraCacheTable.dhalterSankhya]
            }
    }

    fun upsertBar(tenantId: UUID, linhas: List<LinhaCatalogo>): Int = TenantTx.run(tenantId) {
        val agora = Instant.now()
        var total = 0
        linhas.forEach { linha ->
            val codprod = linha["CODPROD"]?.toIntOrNull() ?: return@forEach
            val codbarra = linha["CODBARRA"]?.trim()?.takeIf { it.isNotEmpty() } ?: return@forEach
            CodigosBarraCacheTable.upsert(
                CodigosBarraCacheTable.tenantId, CodigosBarraCacheTable.codprod,
                CodigosBarraCacheTable.codvol, CodigosBarraCacheTable.codbarra,
            ) {
                it[id] = UUID.randomUUID()
                it[CodigosBarraCacheTable.tenantId] = tenantId
                it[CodigosBarraCacheTable.codprod] = codprod
                it[codvol] = linha["CODVOL"] ?: ""
                it[CodigosBarraCacheTable.codbarra] = codbarra
                it[dhalterSankhya] = linha["DHALTER"]
                it[localAtualizadoEm] = agora
            }
            total++
        }
        total
    }

    /** Remove códigos de barra que sumiram do Sankhya — mesmo espírito de removerProdutosPorCodigo. */
    fun removerBarPorChave(tenantId: UUID, chaves: Set<Triple<Int, String, String>>): Int = TenantTx.run(tenantId) {
        var total = 0
        chaves.forEach { (codprod, codvol, codbarra) ->
            total += CodigosBarraCacheTable.deleteWhere {
                (CodigosBarraCacheTable.tenantId eq tenantId) and
                    (CodigosBarraCacheTable.codprod eq codprod) and
                    (CodigosBarraCacheTable.codvol eq codvol) and
                    (CodigosBarraCacheTable.codbarra eq codbarra)
            }
        }
        total
    }

    /** Leitura em lote pro cache-first de SeparacaoService — só os CODPROD pedidos. */
    fun buscarBarPorCodprods(tenantId: UUID, codprods: List<Int>): List<LinhaCatalogo> = TenantTx.run(tenantId) {
        if (codprods.isEmpty()) return@run emptyList()
        CodigosBarraCacheTable.selectAll()
            .where { (CodigosBarraCacheTable.tenantId eq tenantId) and (CodigosBarraCacheTable.codprod inList codprods) }
            .map {
                mapOf(
                    "CODPROD" to it[CodigosBarraCacheTable.codprod].toString(),
                    "CODVOL" to it[CodigosBarraCacheTable.codvol],
                    "CODBARRA" to it[CodigosBarraCacheTable.codbarra],
                )
            }
    }

    // ── Volume Alternativo (TGFVOA) — sem auditoria, cache sob demanda ────

    fun buscarVoaPorCodprods(tenantId: UUID, codprods: List<Int>): List<LinhaCatalogo> = TenantTx.run(tenantId) {
        if (codprods.isEmpty()) return@run emptyList()
        VolumesAlternativosCacheTable.selectAll()
            .where { (VolumesAlternativosCacheTable.tenantId eq tenantId) and (VolumesAlternativosCacheTable.codprod inList codprods) }
            .map {
                mapOf(
                    "CODPROD" to it[VolumesAlternativosCacheTable.codprod].toString(),
                    "CODVOL" to it[VolumesAlternativosCacheTable.codvol],
                    "CONTROLE" to it[VolumesAlternativosCacheTable.controle],
                    "DIVIDEMULTIPLICA" to it[VolumesAlternativosCacheTable.divideMultiplica],
                    "QUANTIDADE" to it[VolumesAlternativosCacheTable.quantidade],
                    "CODBARRA" to it[VolumesAlternativosCacheTable.codbarra],
                )
            }
    }

    /** Grava o resultado do fallback ao vivo — pra sempre, sem TTL (mesmo espírito de ProdutoImagemService). */
    fun upsertVoa(tenantId: UUID, linhas: List<LinhaCatalogo>): Int = TenantTx.run(tenantId) {
        val agora = Instant.now()
        var total = 0
        linhas.forEach { linha ->
            val codprod = linha["CODPROD"]?.toIntOrNull() ?: return@forEach
            VolumesAlternativosCacheTable.upsert(
                VolumesAlternativosCacheTable.tenantId, VolumesAlternativosCacheTable.codprod,
                VolumesAlternativosCacheTable.codvol, VolumesAlternativosCacheTable.codbarra,
            ) {
                it[id] = UUID.randomUUID()
                it[VolumesAlternativosCacheTable.tenantId] = tenantId
                it[VolumesAlternativosCacheTable.codprod] = codprod
                it[codvol] = linha["CODVOL"] ?: ""
                it[controle] = linha["CONTROLE"]
                it[divideMultiplica] = linha["DIVIDEMULTIPLICA"]
                it[quantidade] = linha["QUANTIDADE"]
                it[codbarra] = linha["CODBARRA"] ?: ""
                it[localAtualizadoEm] = agora
            }
            total++
        }
        total
    }
}
