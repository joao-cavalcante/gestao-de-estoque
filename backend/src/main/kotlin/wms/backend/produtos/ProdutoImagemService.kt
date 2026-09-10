package wms.backend.produtos

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import wms.backend.erp.SankhyaDbExplorerClient
import wms.backend.tenancy.TenantRepository
import wms.backend.tenancy.TenantTx
import java.time.Instant
import java.util.Base64
import java.util.UUID

/**
 * Imagem de produto (TGFPRO.IMAGEM, BLOB) — busca LAZY do Sankhya (só na
 * primeira vez que o produto aparece numa separação, não um pré-sync do
 * catálogo inteiro) e cacheia pra sempre em app.produto_imagem_cache (fotos
 * de produto raramente mudam, sem TTL — contraste com dados que PRECISAM
 * expirar, tipo estoque, buscados sempre ao vivo em SeparacaoService).
 *
 * Extração hex→base64 e detecção de dialeto (Oracle vs SQL Server) portadas
 * do `SincronizacaoService.sqlImgHex` do projeto base — mesma query, só que
 * disparada por produto sob demanda em vez de em lote agendado.
 */
object ProdutoImagemService {

    private val escopo = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Aquece o cache da imagem sem bloquear o chamador (fire-and-forget). */
    fun prefetchEmBackground(tenantSlug: String, tenantId: UUID, codprod: Int) {
        escopo.launch {
            runCatching { buscarOuSincronizar(tenantSlug, tenantId, codprod) }
        }
    }

    /**
     * Só o cache LOCAL — leitura rápida, nunca toca o Sankhya. Usado no caminho
     * da bipagem (POST /identificar), onde a latência de uma ida ao Sankhya
     * atrasaria o primeiro Tab. Retorna Pair(achou, imagem): achou=false
     * significa "nunca foi buscado" (vale disparar o sync em background).
     */
    fun buscarCacheado(tenantId: UUID, codprod: Int): Pair<Boolean, String?> = TenantTx.run(tenantId) {
        val row = ProdutoImagemCacheTable.selectAll()
            .where { (ProdutoImagemCacheTable.tenantId eq tenantId) and (ProdutoImagemCacheTable.codprod eq codprod) }
            .singleOrNull()
        if (row == null) false to null else true to row[ProdutoImagemCacheTable.imagem]
    }

    suspend fun buscarOuSincronizar(tenantSlug: String, tenantId: UUID, codprod: Int): String? {
        val cacheado = withContext(Dispatchers.IO) {
            TenantTx.run(tenantId) {
                ProdutoImagemCacheTable.selectAll()
                    .where { (ProdutoImagemCacheTable.tenantId eq tenantId) and (ProdutoImagemCacheTable.codprod eq codprod) }
                    .singleOrNull()
            }
        }
        if (cacheado != null) return cacheado[ProdutoImagemCacheTable.imagem]

        val imagem = try {
            buscarDoSankhya(tenantSlug, codprod)
        } catch (e: Exception) {
            null
        }

        withContext(Dispatchers.IO) {
            TenantTx.run(tenantId) {
                ProdutoImagemCacheTable.deleteWhere {
                    (ProdutoImagemCacheTable.tenantId eq tenantId) and (ProdutoImagemCacheTable.codprod eq codprod)
                }
                ProdutoImagemCacheTable.insert {
                    it[ProdutoImagemCacheTable.tenantId] = tenantId
                    it[ProdutoImagemCacheTable.codprod] = codprod
                    it[ProdutoImagemCacheTable.imagem] = imagem
                    it[atualizadoEm] = Instant.now()
                }
            }
        }
        return imagem
    }

    private suspend fun buscarDoSankhya(tenantSlug: String, codprod: Int): String? {
        val dialect = withContext(Dispatchers.IO) {
            TenantRepository.obterCredenciaisErp(tenantSlug, "sankhya")?.dialect
        }
        val hexExpr = if (dialect == "ORACLE") {
            "RAWTOHEX(DBMS_LOB.SUBSTR(IMAGEM, 32767, 1))"
        } else {
            "CONVERT(NVARCHAR(MAX), CAST(IMAGEM AS VARBINARY(MAX)), 2)"
        }
        val sql = "SELECT $hexExpr AS IMAGEM_HEX FROM TGFPRO WHERE CODPROD = $codprod"

        val rows = SankhyaDbExplorerClient.executarQuery(tenantSlug, sql)
        val hex = rows.firstOrNull()?.get("IMAGEM_HEX")?.trim()?.takeIf { it.isNotEmpty() } ?: return null

        val mime = when {
            hex.startsWith("89504E47", ignoreCase = true) -> "image/png"
            hex.startsWith("FFD8FF", ignoreCase = true) -> "image/jpeg"
            else -> "image/jpeg"
        }
        val bytes = hexParaBytes(hex)
        return "data:$mime;base64,${Base64.getEncoder().encodeToString(bytes)}"
    }

    private fun hexParaBytes(hex: String): ByteArray {
        val limpo = if (hex.length % 2 == 0) hex else hex.dropLast(1)
        val out = ByteArray(limpo.length / 2)
        for (i in out.indices) {
            val idx = i * 2
            out[i] = ((Character.digit(limpo[idx], 16) shl 4) + Character.digit(limpo[idx + 1], 16)).toByte()
        }
        return out
    }
}
