package wms.backend.tenancy

import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import wms.backend.erp.SankhyaAuthException
import wms.backend.erp.SankhyaAuthService

/**
 * Administração de tenants — a tela de "cadastro de novo tenant" do
 * frontend consome exatamente estas rotas. Onboarding no modelo
 * shared-schema é isso: inserir uma linha, sem criar banco/schema/rodar
 * migração por tenant (ver db/README.md).
 */
fun Route.tenantRoutes() {
    route("/api/tenants") {

        get {
            call.respond(TenantRepository.listar())
        }

        get("/{slug}") {
            val slug = call.parameters["slug"]!!
            val tenant = TenantRepository.buscarPorSlug(slug)
            if (tenant == null) {
                call.respond(HttpStatusCode.NotFound, mapOf("erro" to "tenant '$slug' não encontrado"))
            } else {
                call.respond(tenant)
            }
        }

        post {
            val req = call.receive<CriarTenantRequest>()
            if (req.tier == "dedicated" && req.dedicatedDbUrl.isNullOrBlank()) {
                call.respond(HttpStatusCode.BadRequest, mapOf("erro" to "tier 'dedicated' exige dedicatedDbUrl"))
                return@post
            }
            val criado = try {
                TenantRepository.criar(req)
            } catch (e: Exception) {
                call.respond(HttpStatusCode.Conflict, mapOf("erro" to "slug '${req.slug}' já existe ou dados inválidos: ${e.message}"))
                return@post
            }
            call.respond(HttpStatusCode.Created, criado)
        }

        patch("/{slug}") {
            val slug = call.parameters["slug"]!!
            val req = call.receive<AtualizarTenantRequest>()
            val atualizado = TenantRepository.atualizar(slug, req)
            if (atualizado == null) {
                call.respond(HttpStatusCode.NotFound, mapOf("erro" to "tenant '$slug' não encontrado"))
            } else {
                call.respond(atualizado)
            }
        }

        post("/{slug}/erp-connections") {
            val slug = call.parameters["slug"]!!
            val conn = call.receive<ErpConnectionInput>()
            val atualizado = TenantRepository.adicionarErpConnection(slug, conn)
            if (atualizado == null) {
                call.respond(HttpStatusCode.NotFound, mapOf("erro" to "tenant '$slug' não encontrado"))
            } else {
                call.respond(HttpStatusCode.Created, atualizado)
            }
        }

        /**
         * Testa a autenticação Sankhya de verdade pro tenant (chama
         * SankhyaAuthService, que decifra as credenciais e autentica no
         * ERP). Não devolve o token — só confirma que autenticou.
         */
        post("/{slug}/erp-connections/{erpType}/testar-autenticacao") {
            val slug = call.parameters["slug"]!!
            val erpType = call.parameters["erpType"]!!
            if (erpType != "sankhya") {
                call.respond(HttpStatusCode.BadRequest, mapOf("erro" to "só 'sankhya' é suportado por enquanto"))
                return@post
            }
            try {
                SankhyaAuthService.obterTokenValido(slug)
                call.respond(TesteAutenticacaoResponse(autenticado = true))
            } catch (e: SankhyaAuthException) {
                call.respond(HttpStatusCode.BadGateway, TesteAutenticacaoResponse(autenticado = false, erro = e.message))
            }
        }

        delete("/{slug}") {
            val slug = call.parameters["slug"]!!
            val removido = TenantRepository.remover(slug)
            if (removido) {
                call.respond(HttpStatusCode.NoContent)
            } else {
                call.respond(HttpStatusCode.NotFound, mapOf("erro" to "tenant '$slug' não encontrado"))
            }
        }
    }
}
