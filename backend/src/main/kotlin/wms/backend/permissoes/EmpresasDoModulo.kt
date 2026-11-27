package wms.backend.permissoes

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import wms.backend.auth.ClaimsToken
import wms.backend.auth.exigirAuth
import wms.backend.tenancy.TenantRepository
import wms.backend.transferencia.ModelosNotaRepository
import wms.backend.usuarios.EmpresaDto
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 27/11/2026 — acesso por empresa (V80) nas telas antigas de Transferência de locais e Inventário: os registros delas
 * não têm empresa; a empresa é a do modelo de nota do módulo (app.modelos_nota: transferencia_locais /
 * ajuste_inventario), que é onde as notas são lançadas. Usuário com restrição só usa o módulo se essa empresa estiver
 * nas dele. Sem modelo cadastrado = barrado para quem tem restrição.
 */
suspend fun ApplicationCall.exigirAuthDoModulo(tipoModelo: String): ClaimsToken? {
    val claims = exigirAuth() ?: return null
    val permitidas = EmpresasAcesso.permitidas(claims) ?: return claims
    val codemp = ModelosNotaRepository.buscarPorTipo(claims.tenantId, tipoModelo)?.codemp
    if (codemp != null && codemp in permitidas) return claims
    respond(
        HttpStatusCode.Forbidden,
        mapOf("erro" to (if (codemp == null) "Esta tela não tem empresa definida; seu usuário só vê as empresas dele."
        else "Esta tela lança notas na empresa $codemp; seu usuário não tem acesso a ela.")),
    )
    return null
}

@Serializable
data class MinhasEmpresasDto(val todas: Boolean, val empresas: List<EmpresaDto>)

/** Nomes das empresas (TSIEMP) por tenant, lidos no Sankhya no máximo a cada hora. */
private object NomesEmpresas {
    private const val TTL_MS = 3_600_000L
    private data class Cache(val nomes: Map<Int, String>, val lidoEm: Long)
    private val cache = ConcurrentHashMap<UUID, Cache>()

    suspend fun de(tenantId: UUID): Map<Int, String> {
        cache[tenantId]?.takeIf { System.currentTimeMillis() - it.lidoEm < TTL_MS }?.let { return it.nomes }
        val slug = TenantRepository.buscarPorId(tenantId)?.slug ?: return emptyMap()
        val nomes = runCatching {
            wms.backend.erp.SankhyaDbExplorerClient.executarQuery(slug, "SELECT CODEMP, NOMEFANTASIA FROM TSIEMP ORDER BY CODEMP")
                .mapNotNull { l ->
                    val cod = EmpresasAcesso.codemp(l["CODEMP"]) ?: return@mapNotNull null
                    cod to (l["NOMEFANTASIA"]?.trim()?.takeIf { it.isNotEmpty() } ?: "Empresa $cod")
                }.toMap()
        }.getOrElse {
            println("AVISO: nomes das empresas não lidos no Sankhya: ${it.message}")
            return cache[tenantId]?.nomes ?: emptyMap()
        }
        cache[tenantId] = Cache(nomes, System.currentTimeMillis())
        return nomes
    }
}

/** GET /api/auth/empresas — empresas do usuário logado, para o cabeçalho ("todas" = sem restrição). */
fun Route.minhasEmpresasRoutes() {
    get("/api/auth/empresas") {
        val claims = call.exigirAuth() ?: return@get
        val permitidas = EmpresasAcesso.permitidas(claims)
        if (permitidas == null) {
            call.respond(MinhasEmpresasDto(todas = true, empresas = emptyList()))
            return@get
        }
        val nomes = NomesEmpresas.de(claims.tenantId)
        call.respond(MinhasEmpresasDto(todas = false, empresas = permitidas.sorted().map { EmpresaDto(it, nomes[it] ?: "Empresa $it") }))
    }
}
