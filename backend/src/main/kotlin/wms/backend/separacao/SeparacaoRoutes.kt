package wms.backend.separacao

import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import wms.backend.produtos.ProdutoImagemService
import wms.backend.tenancy.TenantRepository
import java.util.UUID

/**
 * Fluxo: POST /iniciar responde na hora (cria sessão local, dispara o
 * carregamento em background); o front dá polling em GET /sessoes/{id} até
 * status == "pronta" (mesma ideia do "sessao-pronta" do projeto base, só
 * que aqui a checagem de integridade — ver SeparacaoRepository.revalidar —
 * roda a CADA poll, de graça, sem chamada nova ao Sankhya).
 */
fun Route.separacaoRoutes() {
    route("/api/separacao") {

        post("/iniciar") {
            val slug = call.request.queryParameters["tenant"]
            if (slug.isNullOrBlank()) {
                call.respond(HttpStatusCode.BadRequest, mapOf("erro" to "query param 'tenant' é obrigatório"))
                return@post
            }
            val tenantId = resolverTenantId(slug)
            if (tenantId == null) {
                call.respond(HttpStatusCode.NotFound, mapOf("erro" to "tenant '$slug' não encontrado"))
                return@post
            }

            val body = call.receive<IniciarSeparacaoRequest>()
            val resultado = SeparacaoService.iniciar(slug, tenantId, body.nunota)
            call.respond(HttpStatusCode.Accepted, mapOf("sessaoId" to resultado.sessaoId.toString(), "status" to resultado.status))
        }

        get("/sessoes/{id}") {
            val slug = call.request.queryParameters["tenant"]
            val sessaoId = call.parameters["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            if (slug.isNullOrBlank() || sessaoId == null) {
                call.respond(HttpStatusCode.BadRequest, mapOf("erro" to "'tenant' (query) e id (path) são obrigatórios"))
                return@get
            }
            val tenantId = resolverTenantId(slug)
            if (tenantId == null) {
                call.respond(HttpStatusCode.NotFound, mapOf("erro" to "tenant '$slug' não encontrado"))
                return@get
            }

            // Revalida a cada poll — leitura 100% local (compara contra o que
            // o sync já sabe da tarefa), nunca chama o Sankhya aqui.
            val sessao = SeparacaoRepository.revalidar(tenantId, sessaoId)
            if (sessao == null) {
                call.respond(HttpStatusCode.NotFound, mapOf("erro" to "sessão não encontrada"))
                return@get
            }
            call.respond(sessao)
        }

        get("/sessoes/{id}/itens") {
            val slug = call.request.queryParameters["tenant"]
            val sessaoId = call.parameters["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            if (slug.isNullOrBlank() || sessaoId == null) {
                call.respond(HttpStatusCode.BadRequest, mapOf("erro" to "'tenant' (query) e id (path) são obrigatórios"))
                return@get
            }
            val tenantId = resolverTenantId(slug)
            if (tenantId == null) {
                call.respond(HttpStatusCode.NotFound, mapOf("erro" to "tenant '$slug' não encontrado"))
                return@get
            }

            val sessao = SeparacaoRepository.buscarSessao(tenantId, sessaoId)
            if (sessao == null) {
                call.respond(HttpStatusCode.NotFound, mapOf("erro" to "sessão não encontrada"))
                return@get
            }
            if (sessao.status != SeparacaoStatus.PRONTA) {
                call.respond(HttpStatusCode.Conflict, mapOf("erro" to "sessão ainda não está pronta (status='${sessao.status}')"))
                return@get
            }

            call.respond(SeparacaoRepository.listarItens(tenantId, sessaoId))
        }

        get("/sessoes/{id}/codigos-barra") {
            val slug = call.request.queryParameters["tenant"]
            val sessaoId = call.parameters["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            if (slug.isNullOrBlank() || sessaoId == null) {
                call.respond(HttpStatusCode.BadRequest, mapOf("erro" to "'tenant' (query) e id (path) são obrigatórios"))
                return@get
            }
            val tenantId = resolverTenantId(slug)
            if (tenantId == null) {
                call.respond(HttpStatusCode.NotFound, mapOf("erro" to "tenant '$slug' não encontrado"))
                return@get
            }

            val sessao = SeparacaoRepository.buscarSessao(tenantId, sessaoId)
            if (sessao == null) {
                call.respond(HttpStatusCode.NotFound, mapOf("erro" to "sessão não encontrada"))
                return@get
            }
            if (sessao.status != SeparacaoStatus.PRONTA) {
                call.respond(HttpStatusCode.Conflict, mapOf("erro" to "sessão ainda não está pronta (status='${sessao.status}')"))
                return@get
            }

            call.respond(SeparacaoRepository.listarCodigosBarra(tenantId, sessaoId))
        }

        post("/sessoes/{id}/resolver-codigo-barras") {
            val slug = call.request.queryParameters["tenant"]
            val sessaoId = call.parameters["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            if (slug.isNullOrBlank() || sessaoId == null) {
                call.respond(HttpStatusCode.BadRequest, mapOf("erro" to "'tenant' (query) e id (path) são obrigatórios"))
                return@post
            }
            val tenantId = resolverTenantId(slug)
            if (tenantId == null) {
                call.respond(HttpStatusCode.NotFound, mapOf("erro" to "tenant '$slug' não encontrado"))
                return@post
            }

            val body = call.receive<ResolverCodigoBarraRequest>()
            val resolvido = SeparacaoRepository.resolverCodigoBarras(tenantId, sessaoId, body.codigoBarra)
            if (resolvido == null) {
                call.respond(HttpStatusCode.NotFound, mapOf("erro" to "código de barras não encontrado nos itens desta sessão"))
                return@post
            }
            call.respond(resolvido)
        }

        /**
         * Passo 1 do fluxo por Tab: identifica o produto a partir do que foi
         * bipado/digitado no primeiro campo, e já devolve como o campo de
         * controle deve se comportar (select com opções vs. lote livre) —
         * ver SeparacaoRepository.identificarProduto.
         */
        post("/sessoes/{id}/identificar") {
            val slug = call.request.queryParameters["tenant"]
            val sessaoId = call.parameters["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            if (slug.isNullOrBlank() || sessaoId == null) {
                call.respond(HttpStatusCode.BadRequest, mapOf("erro" to "'tenant' (query) e id (path) são obrigatórios"))
                return@post
            }
            val tenantId = resolverTenantId(slug)
            if (tenantId == null) {
                call.respond(HttpStatusCode.NotFound, mapOf("erro" to "tenant '$slug' não encontrado"))
                return@post
            }

            val body = call.receive<IdentificarProdutoRequest>()
            val resultado = SeparacaoRepository.identificarProduto(tenantId, sessaoId, body.codigoBarra)
            if (resultado == null) {
                call.respond(HttpStatusCode.NotFound, mapOf("erro" to "código não encontrado nos itens desta sessão"))
                return@post
            }

            // Busca (e cacheia) a imagem à parte — é suspend/assíncrona
            // (chama o Sankhya), não dá pra rodar dentro do TenantTx.run
            // síncrono do repositório. Falha aqui não derruba a identificação.
            val imagem = try {
                ProdutoImagemService.buscarOuSincronizar(slug, tenantId, resultado.codprod)
            } catch (e: Exception) {
                null
            }
            call.respond(resultado.copy(imagemBase64 = imagem))
        }

        /**
         * Passo 2 (final) do fluxo por Tab: produto+controle já resolvidos
         * no passo anterior — só confirma a quantidade. Grava a leitura e
         * recalcula numa única transação (rápido: bipagem não pode acumular
         * latência entre um item e o próximo).
         */
        post("/sessoes/{id}/conferir") {
            val slug = call.request.queryParameters["tenant"]
            val sessaoId = call.parameters["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            if (slug.isNullOrBlank() || sessaoId == null) {
                call.respond(HttpStatusCode.BadRequest, mapOf("erro" to "'tenant' (query) e id (path) são obrigatórios"))
                return@post
            }
            val tenantId = resolverTenantId(slug)
            if (tenantId == null) {
                call.respond(HttpStatusCode.NotFound, mapOf("erro" to "tenant '$slug' não encontrado"))
                return@post
            }

            val body = call.receive<ConferirItemRequest>()
            val qtd = body.qtd.trim().replace(",", ".").toBigDecimalOrNull()
            if (qtd == null || qtd <= java.math.BigDecimal.ZERO) {
                call.respond(HttpStatusCode.BadRequest, mapOf("erro" to "'qtd' precisa ser um número maior que zero"))
                return@post
            }

            val resultado = SeparacaoRepository.conferirItem(tenantId, sessaoId, body.codprod, body.controle, qtd)
            if (resultado == null) {
                call.respond(HttpStatusCode.NotFound, mapOf("erro" to "produto não encontrado nos itens desta sessão"))
                return@post
            }
            call.respond(resultado)
        }

        /** Desfaz tudo que foi conferido pra esse produto+controle — volta a pendente do zero (corrige bipe errado). */
        post("/sessoes/{id}/devolver-item") {
            val slug = call.request.queryParameters["tenant"]
            val sessaoId = call.parameters["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            if (slug.isNullOrBlank() || sessaoId == null) {
                call.respond(HttpStatusCode.BadRequest, mapOf("erro" to "'tenant' (query) e id (path) são obrigatórios"))
                return@post
            }
            val tenantId = resolverTenantId(slug)
            if (tenantId == null) {
                call.respond(HttpStatusCode.NotFound, mapOf("erro" to "tenant '$slug' não encontrado"))
                return@post
            }

            val body = call.receive<DevolverItemRequest>()
            val ok = SeparacaoRepository.devolverItem(tenantId, sessaoId, body.codprod, body.controle)
            if (!ok) {
                call.respond(HttpStatusCode.NotFound, mapOf("erro" to "produto não encontrado nos itens desta sessão"))
                return@post
            }
            call.respond(HttpStatusCode.OK, mapOf("ok" to true))
        }
    }
}

private fun resolverTenantId(slug: String): UUID? =
    TenantRepository.buscarPorSlug(slug)?.id?.let { UUID.fromString(it) }
