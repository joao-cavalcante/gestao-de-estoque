package wms.backend.master

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.add
import wms.backend.auth.ClaimsMaster
import wms.backend.auth.JwtService
import wms.backend.erp.SankhyaAuthService
import wms.backend.liberacaocorte.LiberacaoCorteService
import wms.backend.tenancy.CriarTenantRequest
import wms.backend.tenancy.AtualizarTenantRequest
import wms.backend.tenancy.Modulos
import wms.backend.tenancy.ParametrosTenant
import wms.backend.tenancy.SyncEstadoTable
import wms.backend.tenancy.TenantDto
import wms.backend.tenancy.TenantRepository
import wms.backend.usuarios.UsuariosRepository
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.UUID

@Serializable
data class LoginMasterRequest(val email: String, val senha: String) {
    override fun toString() = "LoginMasterRequest(email=$email)"
}

@Serializable
data class LoginMasterResponse(val token: String, val master: MasterDto)

@Serializable
data class ModuloDto(val id: String, val descricao: String)

@Serializable
data class InstalacaoDto(
    /** Versão desta instância do backend (APP_BUILD/APP_COMMIT do deploy). Todos os tenants deste banco rodam nela. */
    val build: String?,
    val commit: String?,
    /** compartilhada = todos os tenants neste banco e neste processo (modelo atual). */
    val modelo: String,
    val modulos: List<ModuloDto>,
)

@Serializable
data class SituacaoSyncDto(val ultimoSucessoEm: String?, val falhasConsecutivas: Int, val ultimoErro: String?)

@Serializable
data class TenantMasterDto(val tenant: TenantDto, val sync: SituacaoSyncDto?)

@Serializable
data class CriarTenantMasterRequest(val slug: String, val nome: String)

@Serializable
data class DadosGeraisRequest(val nome: String, val confirmacao: String? = null)

@Serializable
data class StatusRequest(val status: String, val motivo: String? = null, val confirmacao: String? = null)

@Serializable
data class IntegracaoRequest(
    val baseUrl: String,
    val gatewayPath: String? = null,
    val dialect: String? = null,
    val ativo: Boolean = true,
    /** Só quando for trocar: clientId/clientSecret/xToken. null = mantém o que está gravado. */
    val clientId: String? = null,
    val clientSecret: String? = null,
    val xToken: String? = null,
    val confirmacao: String? = null,
) {
    override fun toString() = "IntegracaoRequest(baseUrl=$baseUrl, credenciais=${if (clientSecret != null || xToken != null) "informadas" else "mantidas"})"
}

@Serializable
data class ModulosRequest(val modulos: List<String>, val confirmacao: String? = null)

@Serializable
data class ParametrosRequest(val parametros: ParametrosTenant, val confirmacao: String? = null)

@Serializable
data class LiberadorRequest(val usuario: String? = null, val senha: String? = null, val confirmacao: String? = null) {
    override fun toString() = "LiberadorRequest(usuario=$usuario)"
}

@Serializable
data class UsuarioTenantMasterDto(
    val nome: String,
    val email: String,
    val perfil: String,
    /** ADMIN_TENANT (perfil ADMINISTRADOR) | TV | USUARIO_OPERACIONAL — nível de administração do produto. */
    val nivel: String,
    val ativo: Boolean,
    val codusuSankhya: Int? = null,
)

/** Status que o Master pode aplicar pela tela. 'cancelled' e exclusão física não são ações comuns da interface. */
private val STATUS_PERMITIDOS = setOf("trial", "active", "suspended")

private val json = Json { encodeDefaults = true }

fun Route.masterRoutes() {
    route("/api/master") {

        post("/auth/login") {
            val req = runCatching { call.receive<LoginMasterRequest>() }.getOrNull()
                ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("erro" to "informe e-mail e senha"))
            val master = withContext(Dispatchers.IO) { MasterRepository.buscarParaLogin(req.email) }
            val ator = AtorAdmin("master", master?.id, req.email.trim().lowercase(), call.request.origin.remoteHost)
            if (master == null || !master.ativo || !MasterRepository.validarSenha(req.senha, master.senhaHash)) {
                withContext(Dispatchers.IO) { AuditoriaAdmin.registrar(ator, null, null, "login", JsonObject(emptyMap()), resultado = "negado") }
                return@post call.respond(HttpStatusCode.Unauthorized, mapOf("erro" to "e-mail ou senha inválidos"))
            }
            withContext(Dispatchers.IO) { AuditoriaAdmin.registrar(ator, null, null, "login", JsonObject(emptyMap())) }
            call.respond(LoginMasterResponse(JwtService.gerarMaster(master.id, master.email), MasterDto(master.id.toString(), master.nome, master.email)))
        }

        get("/auth/eu") {
            val claims = call.exigirMaster() ?: return@get
            call.respond(MasterRepository.ativo(claims.masterId)!!)
        }

        get("/instalacao") {
            call.exigirMaster() ?: return@get
            call.respond(
                InstalacaoDto(
                    build = System.getenv("APP_BUILD")?.takeIf { it.isNotBlank() },
                    commit = System.getenv("APP_COMMIT")?.takeIf { it.isNotBlank() },
                    modelo = "compartilhada",
                    modulos = Modulos.TODOS.sorted().map { ModuloDto(it, Modulos.descricao(it)) },
                ),
            )
        }

        get("/campos-personalizados") {
            call.exigirMaster() ?: return@get
            call.respond(CamposPersonalizados.CATALOGO)
        }

        get("/auditoria") {
            call.exigirMaster() ?: return@get
            call.respond(withContext(Dispatchers.IO) { AuditoriaAdmin.listar(null) })
        }

        route("/tenants") {
            get {
                call.exigirMaster() ?: return@get
                val tenants = withContext(Dispatchers.IO) { TenantRepository.listar() }
                val sync = withContext(Dispatchers.IO) { situacoesSync() }
                call.respond(tenants.map { TenantMasterDto(it, sync[it.id]) })
            }

            post {
                val claims = call.exigirMaster() ?: return@post
                val req = runCatching { call.receive<CriarTenantMasterRequest>() }.getOrNull()
                    ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("erro" to "informe slug e nome"))
                val slug = req.slug.trim().lowercase()
                if (!Regex("^[a-z0-9][a-z0-9-]{1,39}$").matches(slug) || req.nome.isBlank()) {
                    return@post call.respond(HttpStatusCode.BadRequest, mapOf("erro" to "slug: 2 a 40 letras minúsculas, números ou hífen; nome obrigatório"))
                }
                val criado = try {
                    withContext(Dispatchers.IO) { TenantRepository.criar(CriarTenantRequest(slug = slug, nome = req.nome.trim())) }
                } catch (e: Exception) {
                    return@post call.respond(HttpStatusCode.Conflict, mapOf("erro" to "slug '$slug' já existe"))
                }
                call.auditar(claims, criado, "tenant.criar", buildJsonObject { put("slug", slug); put("nome", req.nome.trim()) })
                call.respond(HttpStatusCode.Created, criado)
            }

            route("/{id}") {
                get {
                    call.exigirMaster() ?: return@get
                    val tenant = call.tenantDaRota() ?: return@get
                    call.respond(TenantMasterDto(tenant, withContext(Dispatchers.IO) { situacoesSync() }[tenant.id]))
                }

                put("/dados-gerais") {
                    val claims = call.exigirMaster() ?: return@put
                    val tenant = call.tenantDaRota() ?: return@put
                    val req = call.receive<DadosGeraisRequest>()
                    if (req.nome.isBlank()) return@put call.respond(HttpStatusCode.BadRequest, mapOf("erro" to "nome obrigatório"))
                    val atualizado = withContext(Dispatchers.IO) { TenantRepository.atualizar(tenant.slug, AtualizarTenantRequest(nome = req.nome.trim())) }
                    call.auditar(claims, tenant, "tenant.dados_gerais", buildJsonObject { put("nomeAntes", tenant.nome); put("nomeDepois", req.nome.trim()) })
                    call.respond(atualizado!!)
                }

                put("/status") {
                    val claims = call.exigirMaster() ?: return@put
                    val tenant = call.tenantDaRota() ?: return@put
                    val req = call.receive<StatusRequest>()
                    if (req.status !in STATUS_PERMITIDOS) {
                        return@put call.respond(HttpStatusCode.BadRequest, mapOf("erro" to "status permitido: ${STATUS_PERMITIDOS.joinToString()}"))
                    }
                    if (!call.confirmado(claims, tenant, "tenant.status", req.confirmacao)) return@put
                    val atualizado = withContext(Dispatchers.IO) { TenantRepository.atualizar(tenant.slug, AtualizarTenantRequest(status = req.status)) }
                    call.auditar(claims, tenant, "tenant.status", buildJsonObject {
                        put("antes", tenant.status); put("depois", req.status); req.motivo?.let { put("motivo", it.take(500)) }
                    })
                    call.respond(atualizado!!)
                }

                put("/integracao") {
                    val claims = call.exigirMaster() ?: return@put
                    val tenant = call.tenantDaRota() ?: return@put
                    val req = call.receive<IntegracaoRequest>()
                    if (!req.baseUrl.trim().startsWith("https://") && !req.baseUrl.trim().startsWith("http://")) {
                        return@put call.respond(HttpStatusCode.BadRequest, mapOf("erro" to "URL base inválida"))
                    }
                    if (req.dialect != null && req.dialect !in setOf("ORACLE", "SQLSERVER", "POSTGRES", "MYSQL")) {
                        return@put call.respond(HttpStatusCode.BadRequest, mapOf("erro" to "dialeto inválido"))
                    }
                    if (!call.confirmado(claims, tenant, "tenant.integracao", req.confirmacao)) return@put
                    val trocaCredencial = listOf(req.clientId, req.clientSecret, req.xToken).any { !it.isNullOrBlank() }
                    val credenciais = if (!trocaCredencial) null else buildJsonObject {
                        put("clientId", req.clientId.orEmpty()); put("clientSecret", req.clientSecret.orEmpty()); put("xToken", req.xToken.orEmpty())
                    }.toString()
                    val antes = tenant.erpConnections.firstOrNull { it.erpType == "sankhya" }
                    withContext(Dispatchers.IO) {
                        TenantRepository.salvarIntegracao(UUID.fromString(tenant.id), req.baseUrl.trim().trimEnd('/'), req.gatewayPath?.trim()?.takeIf { it.isNotEmpty() }, req.dialect, req.ativo, credenciais)
                    }
                    SankhyaAuthService.invalidar(tenant.slug)
                    call.auditar(claims, tenant, "tenant.integracao", buildJsonObject {
                        put("baseUrlAntes", antes?.baseUrl); put("baseUrlDepois", req.baseUrl.trim())
                        put("ativoAntes", antes?.ativo); put("ativoDepois", req.ativo)
                        put("dialetoDepois", req.dialect); put("credenciaisTrocadas", trocaCredencial)
                    })
                    call.respond(recarregar(tenant))
                }

                post("/integracao/testar") {
                    val claims = call.exigirMaster() ?: return@post
                    val tenant = call.tenantDaRota() ?: return@post
                    SankhyaAuthService.invalidar(tenant.slug)
                    val erro = runCatching { SankhyaAuthService.obterTokenValido(tenant.slug) }.exceptionOrNull()
                    call.auditar(claims, tenant, "tenant.integracao.testar", buildJsonObject { put("autenticado", erro == null) }, if (erro == null) "ok" else "erro")
                    call.respond(mapOf("autenticado" to (erro == null).toString(), "erro" to erro?.message))
                }

                put("/modulos") {
                    val claims = call.exigirMaster() ?: return@put
                    val tenant = call.tenantDaRota() ?: return@put
                    val req = call.receive<ModulosRequest>()
                    val desconhecidos = req.modulos.filter { it !in Modulos.TODOS }
                    if (desconhecidos.isNotEmpty()) {
                        return@put call.respond(HttpStatusCode.BadRequest, mapOf("erro" to "módulo desconhecido: ${desconhecidos.joinToString()}"))
                    }
                    if (!call.confirmado(claims, tenant, "tenant.modulos", req.confirmacao)) return@put
                    val antes = tenant.erpConnections.flatMap { it.modulos }.distinct().sorted()
                    val ok = withContext(Dispatchers.IO) { TenantRepository.atualizarModulos(UUID.fromString(tenant.id), req.modulos) }
                    if (!ok) return@put call.respond(HttpStatusCode.Conflict, mapOf("erro" to "cadastre a integração Sankhya antes dos módulos"))
                    call.auditar(claims, tenant, "tenant.modulos", buildJsonObject {
                        putJsonArray("antes") { antes.forEach { add(it) } }
                        putJsonArray("depois") { req.modulos.distinct().sorted().forEach { add(it) } }
                    })
                    call.respond(recarregar(tenant))
                }

                put("/parametros") {
                    val claims = call.exigirMaster() ?: return@put
                    val tenant = call.tenantDaRota() ?: return@put
                    val req = try {
                        call.receive<ParametrosRequest>()
                    } catch (e: Exception) {
                        return@put call.respond(HttpStatusCode.BadRequest, mapOf("erro" to "parâmetros inválidos: ${e.message}"))
                    }
                    val erros = req.parametros.validar()
                    if (erros.isNotEmpty()) return@put call.respond(HttpStatusCode.BadRequest, mapOf("erro" to erros.joinToString("; ")))
                    if (!call.confirmado(claims, tenant, "tenant.parametros", req.confirmacao)) return@put
                    val id = UUID.fromString(tenant.id)
                    val antes = withContext(Dispatchers.IO) { TenantRepository.parametros(id) }
                    val ok = withContext(Dispatchers.IO) { TenantRepository.salvarParametros(id, req.parametros) }
                    if (!ok) return@put call.respond(HttpStatusCode.Conflict, mapOf("erro" to "cadastre a integração Sankhya antes dos parâmetros"))
                    call.auditar(claims, tenant, "tenant.parametros", buildJsonObject {
                        put("antes", json.encodeToJsonElement(ParametrosTenant.serializer(), antes))
                        put("depois", json.encodeToJsonElement(ParametrosTenant.serializer(), req.parametros))
                    })
                    call.respond(withContext(Dispatchers.IO) { TenantRepository.parametros(id) })
                }

                /** Grava (usuario+senha) ou remove (sem usuario) a credencial do liberador. Validada no Sankhya DESTE tenant. */
                put("/liberador") {
                    val claims = call.exigirMaster() ?: return@put
                    val tenant = call.tenantDaRota() ?: return@put
                    val req = call.receive<LiberadorRequest>()
                    if (!call.confirmado(claims, tenant, "tenant.liberador", req.confirmacao)) return@put
                    val id = UUID.fromString(tenant.id)
                    if (req.usuario.isNullOrBlank()) {
                        withContext(Dispatchers.IO) { TenantRepository.salvarCredencialLiberador(id, null, null) }
                        call.auditar(claims, tenant, "tenant.liberador.remover", JsonObject(emptyMap()))
                        return@put call.respond(mapOf("configurado" to "false"))
                    }
                    if (req.senha.isNullOrBlank()) return@put call.respond(HttpStatusCode.BadRequest, mapOf("erro" to "informe a senha do liberador"))
                    val codusu = try {
                        LiberacaoCorteService.validarLiberador(tenant.slug, req.usuario.trim(), req.senha)
                    } catch (e: Exception) {
                        call.auditar(claims, tenant, "tenant.liberador.gravar", buildJsonObject { put("usuario", req.usuario.trim()) }, "negado")
                        return@put call.respond(HttpStatusCode.BadRequest, mapOf("erro" to "o Sankhya de ${tenant.slug} não validou o liberador: ${e.message}"))
                    }
                    if (!withContext(Dispatchers.IO) { TenantRepository.salvarCredencialLiberador(id, req.usuario.trim(), req.senha) }) {
                        return@put call.respond(HttpStatusCode.Conflict, mapOf("erro" to "cadastre a integração Sankhya antes"))
                    }
                    call.auditar(claims, tenant, "tenant.liberador.gravar", buildJsonObject { put("usuario", req.usuario.trim()); put("codusu", codusu) })
                    call.respond(mapOf("configurado" to "true", "codusu" to codusu))
                }

                get("/usuarios") {
                    call.exigirMaster() ?: return@get
                    val tenant = call.tenantDaRota() ?: return@get
                    // Contexto RLS explícito do tenant selecionado (TenantTx dentro do repositório) — só leitura.
                    val usuarios = withContext(Dispatchers.IO) { UsuariosRepository.listar(UUID.fromString(tenant.id)) }
                    call.respond(usuarios.map {
                        UsuarioTenantMasterDto(it.nome, it.email, it.perfil, nivelDoPerfil(it.perfil), it.ativo, it.codusuSankhya)
                    })
                }

                get("/diagnostico") {
                    val claims = call.exigirMaster() ?: return@get
                    val tenant = call.tenantDaRota() ?: return@get
                    val resultado = DiagnosticoInstalacao.executar(tenant)
                    call.auditar(claims, tenant, "tenant.diagnostico", buildJsonObject {
                        put("erros", resultado.itens.count { it.situacao == "ERRO" }); put("alertas", resultado.itens.count { it.situacao == "ALERTA" })
                    })
                    call.respond(resultado)
                }

                get("/auditoria") {
                    call.exigirMaster() ?: return@get
                    val tenant = call.tenantDaRota() ?: return@get
                    call.respond(withContext(Dispatchers.IO) { AuditoriaAdmin.listar(UUID.fromString(tenant.id)) })
                }
            }
        }
    }
}

/** ADMIN_TENANT / TV / USUARIO_OPERACIONAL a partir do perfil do usuário do tenant (os perfis não mudam de nome). */
fun nivelDoPerfil(perfil: String): String = when (perfil) {
    "ADMINISTRADOR" -> "ADMIN_TENANT"
    wms.backend.auth.PERFIL_TV -> "TV"
    else -> "USUARIO_OPERACIONAL"
}

/** Tenant pelo ID (UUID) da rota, resolvido no servidor. Slug na URL não é aceito pra nada. */
private suspend fun ApplicationCall.tenantDaRota(): TenantDto? {
    val id = parameters["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
    val tenant = id?.let { withContext(Dispatchers.IO) { TenantRepository.buscarPorId(it) } }
    if (tenant == null) respond(HttpStatusCode.NotFound, mapOf("erro" to "tenant não encontrado"))
    return tenant
}

private suspend fun ApplicationCall.confirmado(claims: ClaimsMaster, tenant: TenantDto, acao: String, informado: String?): Boolean {
    if (ConfirmacaoCritica.valida(tenant.slug, informado)) return true
    withContext(Dispatchers.IO) {
        AuditoriaAdmin.registrar(atorMaster(claims), UUID.fromString(tenant.id), tenant.slug, acao, buildJsonObject { put("motivo", "confirmação não confere") }, "negado")
    }
    respond(HttpStatusCode.PreconditionFailed, mapOf("erro" to "confirmação não confere: digite o identificador do cliente (${tenant.slug})"))
    return false
}

private suspend fun ApplicationCall.auditar(claims: ClaimsMaster, tenant: TenantDto, acao: String, detalhe: JsonObject, resultado: String = "ok") {
    withContext(Dispatchers.IO) { AuditoriaAdmin.registrar(atorMaster(claims), tenant.id?.let(UUID::fromString), tenant.slug, acao, detalhe, resultado) }
}

private suspend fun recarregar(tenant: TenantDto): TenantDto =
    withContext(Dispatchers.IO) { TenantRepository.buscarPorId(UUID.fromString(tenant.id))!! }

private fun situacoesSync(): Map<String?, SituacaoSyncDto> = transaction {
    SyncEstadoTable.selectAll().associate {
        it[SyncEstadoTable.tenantId].toString() to SituacaoSyncDto(
            it[SyncEstadoTable.ultimoSyncSucessoEm]?.toString(), it[SyncEstadoTable.falhasConsecutivas], it[SyncEstadoTable.ultimoErro]?.take(300),
        )
    }
}
