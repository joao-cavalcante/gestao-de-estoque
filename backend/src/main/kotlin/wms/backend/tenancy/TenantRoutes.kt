package wms.backend.tenancy

import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import wms.backend.auth.exigirAdmin
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
            call.exigirAdmin() ?: return@get
            call.respond(TenantRepository.listar())
        }

        get("/{slug}") {
            call.exigirAdmin() ?: return@get
            val slug = call.parameters["slug"]!!
            val tenant = TenantRepository.buscarPorSlug(slug)
            if (tenant == null) {
                call.respond(HttpStatusCode.NotFound, mapOf("erro" to "tenant '$slug' não encontrado"))
            } else {
                call.respond(tenant)
            }
        }

        post {
            call.exigirAdmin() ?: return@post
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
            call.exigirAdmin() ?: return@patch
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
            call.exigirAdmin() ?: return@post
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
            call.exigirAdmin() ?: return@post
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

        /**
         * Parâmetros de operação (V62, ParametrosTenant) — substitui o JSON inteiro, validado antes de gravar.
         * Só o ADMINISTRADOR do PRÓPRIO tenant: política de liberação/faturamento de um cliente nunca é editada
         * por login de outro tenant.
         */
        put("/{slug}/parametros") {
            val claims = call.exigirAdmin() ?: return@put
            val tenant = TenantRepository.buscarPorSlug(call.parameters["slug"]!!)
            if (tenant == null || tenant.id != claims.tenantId.toString()) {
                call.respond(HttpStatusCode.NotFound, mapOf("erro" to "tenant não encontrado"))
                return@put
            }
            val parametros = try {
                call.receive<ParametrosTenant>()
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadRequest, mapOf("erro" to "parâmetros inválidos: ${e.message}"))
                return@put
            }
            val erros = parametros.validar()
            if (erros.isNotEmpty()) {
                call.respond(HttpStatusCode.BadRequest, mapOf("erro" to erros.joinToString("; ")))
                return@put
            }
            if (!TenantRepository.salvarParametros(claims.tenantId, parametros)) {
                call.respond(HttpStatusCode.Conflict, mapOf("erro" to "tenant sem conexão Sankhya"))
                return@put
            }
            call.respond(TenantRepository.parametros(claims.tenantId))
        }

        /**
         * Credencial do liberador de serviço do tenant (V63) — só escrita: {usuario, senha}. Validada no Sankhya
         * (LiberacaoLimitesSP.validarSenhaUsuario, leitura) ANTES de gravar; inválida não grava. Resposta nunca traz
         * a senha. Só o ADMINISTRADOR do PRÓPRIO tenant. DELETE remove (liberações automáticas ficam manuais).
         */
        put("/{slug}/liberador") {
            val claims = call.exigirAdmin() ?: return@put
            val tenant = TenantRepository.buscarPorSlug(call.parameters["slug"]!!)
            if (tenant == null || tenant.id != claims.tenantId.toString()) {
                call.respond(HttpStatusCode.NotFound, mapOf("erro" to "tenant não encontrado"))
                return@put
            }
            val req = runCatching { call.receive<CredencialLiberadorRequest>() }.getOrNull()
            if (req == null || req.usuario.isBlank() || req.senha.isBlank()) {
                call.respond(HttpStatusCode.BadRequest, mapOf("erro" to "informe usuario e senha do liberador"))
                return@put
            }
            val codusu = try {
                wms.backend.liberacaocorte.LiberacaoCorteService.validarLiberador(tenant.slug, req.usuario.trim(), req.senha)
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadRequest, mapOf("erro" to "o Sankhya não validou o liberador: ${e.message}"))
                return@put
            }
            if (!TenantRepository.salvarCredencialLiberador(claims.tenantId, req.usuario.trim(), req.senha)) {
                call.respond(HttpStatusCode.Conflict, mapOf("erro" to "tenant sem conexão Sankhya"))
                return@put
            }
            println("INFO: credencial do liberador de serviço gravada (tenant ${tenant.slug}, CODUSU $codusu)")
            call.respond(mapOf("configurado" to "true", "codusu" to codusu))
        }

        delete("/{slug}/liberador") {
            val claims = call.exigirAdmin() ?: return@delete
            val tenant = TenantRepository.buscarPorSlug(call.parameters["slug"]!!)
            if (tenant == null || tenant.id != claims.tenantId.toString()) {
                call.respond(HttpStatusCode.NotFound, mapOf("erro" to "tenant não encontrado"))
                return@delete
            }
            TenantRepository.salvarCredencialLiberador(claims.tenantId, null, null)
            println("INFO: credencial do liberador de serviço removida (tenant ${tenant.slug})")
            call.respond(HttpStatusCode.NoContent)
        }

        delete("/{slug}") {
            call.exigirAdmin() ?: return@delete
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
