package wms.backend.galpao

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import wms.backend.auth.ClaimsToken
import wms.backend.auth.exigirAuth
import wms.backend.tenancy.Modulos
import wms.backend.tenancy.TenantRepository
import wms.backend.tenancy.TenantTx
import wms.backend.usuarios.UsersTable
import java.util.UUID

/**
 * Planta do Galpão — só com o módulo [Modulos.PLANTA_GALPAO] ligado. Consulta: qualquer usuário do tenant;
 * cadastro, desenho e publicação: ADMINISTRADOR.
 */
fun Route.galpaoRoutes() {
    route("/api/galpao") {

        /** A tela pergunta antes de montar o menu/rotas (403 = módulo desligado). */
        get("/disponivel") {
            val claims = call.exigirAuth() ?: return@get
            call.respond(mapOf("disponivel" to moduloLigado(claims), "podeEditar" to (claims.perfil == "ADMINISTRADOR")))
        }

        get("/plantas") {
            val claims = call.exigirGalpao() ?: return@get
            call.respond(GalpaoRepository.listarPlantas(claims.tenantId))
        }

        post("/plantas") {
            val claims = call.exigirGalpao(admin = true) ?: return@post
            val body = call.receive<SalvarPlantaRequest>()
            validarPlanta(body)?.let { return@post call.respond(HttpStatusCode.BadRequest, mapOf("erro" to it)) }
            if (GalpaoRepository.nomeEmUso(claims.tenantId, body.nome.trim(), null)) {
                return@post call.respond(HttpStatusCode.Conflict, mapOf("erro" to "Já existe uma planta com esse nome"))
            }
            call.respond(HttpStatusCode.Created, GalpaoRepository.criarPlanta(claims.tenantId, body))
        }

        put("/plantas/{id}") {
            val claims = call.exigirGalpao(admin = true) ?: return@put
            val id = call.uuid("id") ?: return@put call.respond(HttpStatusCode.BadRequest)
            val body = call.receive<SalvarPlantaRequest>()
            validarPlanta(body)?.let { return@put call.respond(HttpStatusCode.BadRequest, mapOf("erro" to it)) }
            if (GalpaoRepository.nomeEmUso(claims.tenantId, body.nome.trim(), id)) {
                return@put call.respond(HttpStatusCode.Conflict, mapOf("erro" to "Já existe uma planta com esse nome"))
            }
            val atualizada = GalpaoRepository.atualizarPlanta(claims.tenantId, id, body)
                ?: return@put call.respond(HttpStatusCode.NotFound, mapOf("erro" to "Planta não encontrada"))
            call.respond(atualizada)
        }

        get("/plantas/{id}/visao") {
            val claims = call.exigirGalpao() ?: return@get
            val id = call.uuid("id") ?: return@get call.respond(HttpStatusCode.BadRequest)
            // Peso/volume dos produtos do Sankhya pra ocupação; Sankhya fora = ocupação só pela quantidade.
            val fisicos = runCatching {
                val slug = TenantRepository.buscarPorId(claims.tenantId)?.slug
                val codprods = GalpaoRepository.codprodsNaPlanta(claims.tenantId, id)
                if (slug == null || codprods.isEmpty()) emptyMap() else GalpaoErp.fisicos(slug, codprods)
            }.onFailure { println("AVISO: galpão — peso/volume não lidos do Sankhya: ${it.message}") }.getOrDefault(emptyMap())
            val visao = GalpaoRepository.visao(claims.tenantId, id, fisicos)
                ?: return@get call.respond(HttpStatusCode.NotFound, mapOf("erro" to "Planta não encontrada"))
            call.respond(visao)
        }

        /** Saldo contábil do empresa/local (TGFEST) — à parte da visão pra 3D não esperar o Sankhya. */
        get("/plantas/{id}/indicadores") {
            val claims = call.exigirGalpao() ?: return@get
            val id = call.uuid("id") ?: return@get call.respond(HttpStatusCode.BadRequest)
            val planta = GalpaoRepository.buscarPlanta(claims.tenantId, id)
                ?: return@get call.respond(HttpStatusCode.NotFound, mapOf("erro" to "Planta não encontrada"))
            val agora = java.time.Instant.now().toString()
            val emp = planta.codemp
            val loc = planta.codlocal
            if (emp == null || loc == null) {
                return@get call.respond(IndicadoresPlantaDto(null, null, agora, "Planta sem empresa/local cadastrados"))
            }
            val r = runCatching {
                val slug = TenantRepository.buscarPorId(claims.tenantId)?.slug ?: error("Instalação não encontrada")
                GalpaoErp.estoqueContabil(slug, emp, loc)
            }
            call.respond(
                r.fold(
                    { l -> IndicadoresPlantaDto(l.sumOf { it.estoque }, l.groupBy { it.codprod }.count { (_, v) -> v.sumOf { it.estoque } > 0 }, agora, null) },
                    { e -> IndicadoresPlantaDto(null, null, agora, "Sankhya indisponível: ${e.message}") },
                ),
            )
        }

        /**
         * Análises dos produtos do empresa/local da planta (ABC por valor/quantidade/frequência, cobertura, validade,
         * estoque parado, idade e saúde) — fotografia em memória por 10 min; `?atualizar=true` força nova leitura.
         */
        get("/plantas/{id}/analises") {
            val claims = call.exigirGalpao() ?: return@get
            val id = call.uuid("id") ?: return@get call.respond(HttpStatusCode.BadRequest)
            val planta = GalpaoRepository.buscarPlanta(claims.tenantId, id)
                ?: return@get call.respond(HttpStatusCode.NotFound, mapOf("erro" to "Planta não encontrada"))
            val slug = TenantRepository.buscarPorId(claims.tenantId)?.slug
                ?: return@get call.respond(HttpStatusCode.NotFound, mapOf("erro" to "Instalação não encontrada"))
            val forcar = call.request.queryParameters["atualizar"] == "true"
            runCatching { GalpaoAnalisesService.analises(claims.tenantId, slug, planta, forcar) }
                .onSuccess { call.respond(it) }
                .onFailure { e ->
                    if (e is GalpaoAnalisesService.PlantaSemLocal) {
                        call.respond(HttpStatusCode.UnprocessableEntity, mapOf("erro" to e.message))
                    } else {
                        println("AVISO: galpão — análises não lidas do Sankhya: ${e.message}")
                        call.respond(HttpStatusCode.ServiceUnavailable, mapOf("erro" to "Sankhya indisponível: ${e.message}"))
                    }
                }
        }

        /**
         * Alertas da planta. Cada nova fotografia do Sankhya gera/atualiza os alertas (deduplicados); Sankhya fora =
         * lista o que já existe com aviso. `?atualizar=true` força nova leitura.
         */
        get("/plantas/{id}/alertas") {
            val claims = call.exigirGalpao() ?: return@get
            val id = call.uuid("id") ?: return@get call.respond(HttpStatusCode.BadRequest)
            val planta = GalpaoRepository.buscarPlanta(claims.tenantId, id)
                ?: return@get call.respond(HttpStatusCode.NotFound, mapOf("erro" to "Planta não encontrada"))
            val slug = TenantRepository.buscarPorId(claims.tenantId)?.slug
                ?: return@get call.respond(HttpStatusCode.NotFound, mapOf("erro" to "Instalação não encontrada"))
            val forcar = call.request.queryParameters["atualizar"] == "true"
            val geracao = runCatching {
                val (leitura, analises) = GalpaoAnalisesService.fotografia(claims.tenantId, slug, planta, forcar)
                val fisicos = analises.produtos.associate { it.codprod to FisicoProduto(it.pesoBruto, it.m3) }
                GalpaoAlertas.gerarSeNovo(claims.tenantId, id, leitura.lidoEm, analises.parametros.slaAlertaHoras) {
                    val visao = GalpaoRepository.visao(claims.tenantId, id, fisicos) ?: error("Planta não encontrada")
                    GalpaoAlertas.ocorrencias(leitura, analises, visao)
                }
                leitura.lidoEm.toString()
            }.onFailure { println("AVISO: galpão — alertas não gerados: ${it.message}") }
            call.respond(
                AlertasPlantaDto(
                    alertas = GalpaoAlertas.listar(claims.tenantId, id),
                    geradoEm = geracao.getOrNull(),
                    aviso = geracao.exceptionOrNull()?.let {
                        if (it is GalpaoAnalisesService.PlantaSemLocal) it.message
                        else "Sankhya indisponível — alertas da última leitura: ${it.message}"
                    },
                ),
            )
        }

        /** Reconhecer / atribuir / resolver / reabrir um alerta (TV não trata). Versão velha = 409. */
        post("/plantas/{id}/alertas/{alertaId}") {
            val claims = call.exigirGalpao() ?: return@post
            if (claims.perfil == "TV") return@post call.respond(HttpStatusCode.Forbidden, mapOf("erro" to "Perfil sem permissão para tratar alertas"))
            val id = call.uuid("id") ?: return@post call.respond(HttpStatusCode.BadRequest)
            val alertaId = call.uuid("alertaId") ?: return@post call.respond(HttpStatusCode.BadRequest)
            val body = call.receive<TratarAlertaRequest>()
            when (val r = GalpaoAlertas.tratar(claims.tenantId, id, alertaId, body, nomeUsuario(claims))) {
                is GalpaoAlertas.ResultadoTratamento.Ok -> call.respond(r.alerta)
                is GalpaoAlertas.ResultadoTratamento.Erro ->
                    call.respond(if (r.conflito) HttpStatusCode.Conflict else HttpStatusCode.BadRequest, mapOf("erro" to r.mensagem))
            }
        }

        /** Feed da visão: últimos movimentos físicos da planta (consulta, qualquer usuário). */
        get("/plantas/{id}/feed") {
            val claims = call.exigirGalpao() ?: return@get
            val id = call.uuid("id") ?: return@get call.respond(HttpStatusCode.BadRequest)
            call.respond(GalpaoMovimentacao.listarMovimentos(claims.tenantId, id, null, null, null, 1).itens.take(15))
        }

        /** Busca da visão por documento: NUNOTA ou nº do pedido/nota → produtos dele. 404 = não achou. */
        get("/plantas/{id}/documento/{numero}") {
            val claims = call.exigirGalpao() ?: return@get
            val id = call.uuid("id") ?: return@get call.respond(HttpStatusCode.BadRequest)
            val numero = call.parameters["numero"]?.toIntOrNull()?.takeIf { it > 0 } ?: return@get call.respond(HttpStatusCode.BadRequest)
            val planta = GalpaoRepository.buscarPlanta(claims.tenantId, id)
                ?: return@get call.respond(HttpStatusCode.NotFound, mapOf("erro" to "Planta não encontrada"))
            val slug = TenantRepository.buscarPorId(claims.tenantId)?.slug
                ?: return@get call.respond(HttpStatusCode.NotFound, mapOf("erro" to "Instalação não encontrada"))
            val doc = runCatching { GalpaoErp.documento(slug, numero, planta.codemp) }.getOrElse {
                return@get call.respond(HttpStatusCode.ServiceUnavailable, mapOf("erro" to "Sankhya indisponível para buscar o documento"))
            } ?: return@get call.respond(HttpStatusCode.NotFound, mapOf("erro" to "Documento $numero não encontrado"))
            call.respond(doc)
        }

        galpaoMovimentacaoRoutes()

        get("/plantas/{id}/versoes") {
            val claims = call.exigirGalpao(admin = true) ?: return@get
            val id = call.uuid("id") ?: return@get call.respond(HttpStatusCode.BadRequest)
            call.respond(GalpaoRepository.listarVersoes(claims.tenantId, id))
        }

        /** ?versao=<id> abre uma versão específica; sem ele, o rascunho aberto ou a publicada. 204 = sem desenho. */
        get("/plantas/{id}/desenho") {
            val claims = call.exigirGalpao(admin = true) ?: return@get
            val id = call.uuid("id") ?: return@get call.respond(HttpStatusCode.BadRequest)
            val versaoId = call.request.queryParameters["versao"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            val v = GalpaoRepository.versao(claims.tenantId, id, versaoId) ?: return@get call.respond(HttpStatusCode.NoContent)
            call.respond(v)
        }

        post("/plantas/{id}/validar") {
            call.exigirGalpao(admin = true) ?: return@post
            val g = call.receive<GeometriaPlanta>()
            val erros = ValidadorGeometria.validar(g)
            val total = if (erros.isEmpty()) GeradorEnderecos.gerar(g).size else 0
            call.respond(ValidacaoDto(erros.isEmpty(), erros, total))
        }

        post("/plantas/{id}/rascunho") {
            val claims = call.exigirGalpao(admin = true) ?: return@post
            val id = call.uuid("id") ?: return@post call.respond(HttpStatusCode.BadRequest)
            val body = call.receive<SalvarRascunhoRequest>()
            if (body.geometria.objetos.size > ValidadorGeometria.MAX_OBJETOS) {
                return@post call.respond(HttpStatusCode.BadRequest, mapOf("erro" to "Limite de ${ValidadorGeometria.MAX_OBJETOS} objetos"))
            }
            val v = GalpaoRepository.salvarRascunho(claims.tenantId, id, body.geometria, body.observacao, nomeUsuario(claims))
                ?: return@post call.respond(HttpStatusCode.NotFound, mapOf("erro" to "Planta não encontrada"))
            call.respond(v)
        }

        post("/plantas/{id}/versoes/{versaoId}/restaurar") {
            val claims = call.exigirGalpao(admin = true) ?: return@post
            val id = call.uuid("id") ?: return@post call.respond(HttpStatusCode.BadRequest)
            val versaoId = call.uuid("versaoId") ?: return@post call.respond(HttpStatusCode.BadRequest)
            val v = GalpaoRepository.restaurarComoRascunho(claims.tenantId, id, versaoId, nomeUsuario(claims))
                ?: return@post call.respond(HttpStatusCode.NotFound, mapOf("erro" to "Versão não encontrada"))
            call.respond(v)
        }

        post("/plantas/{id}/versoes/{versaoId}/publicar") {
            val claims = call.exigirGalpao(admin = true) ?: return@post
            val id = call.uuid("id") ?: return@post call.respond(HttpStatusCode.BadRequest)
            val versaoId = call.uuid("versaoId") ?: return@post call.respond(HttpStatusCode.BadRequest)
            val body = call.receive<PublicarRequest>()
            when (val r = GalpaoRepository.publicar(claims.tenantId, id, versaoId, body.confirmacao, nomeUsuario(claims))) {
                is ResultadoPublicacao.Ok -> call.respond(
                    mapOf(
                        "versao" to r.versao.numero.toString(),
                        "enderecosAtivos" to r.enderecosAtivos.toString(),
                        "enderecosDesativados" to r.enderecosDesativados.toString(),
                    ),
                )
                ResultadoPublicacao.NaoEncontrada -> call.respond(HttpStatusCode.NotFound, mapOf("erro" to "Versão não encontrada"))
                ResultadoPublicacao.NaoERascunho -> call.respond(HttpStatusCode.Conflict, mapOf("erro" to "Só o rascunho pode ser publicado"))
                ResultadoPublicacao.ConfirmacaoInvalida -> call.respond(HttpStatusCode.BadRequest, mapOf("erro" to "Digite o nome da planta exatamente como cadastrado para confirmar"))
                is ResultadoPublicacao.Invalida -> call.respond(HttpStatusCode.UnprocessableEntity, ErroValidacaoDto("Desenho com problemas", r.erros))
                is ResultadoPublicacao.EnderecoComSaldo -> call.respond(
                    HttpStatusCode.Conflict,
                    ErroValidacaoDto("Endereços com saldo não podem sair do desenho", r.codigos.take(50)),
                )
            }
        }
    }
}

@kotlinx.serialization.Serializable
data class ErroValidacaoDto(val erro: String, val detalhes: List<String>)

private fun validarPlanta(r: SalvarPlantaRequest): String? = when {
    r.nome.isBlank() -> "Informe o nome da planta"
    r.nome.trim().length > 80 -> "Nome com no máximo 80 caracteres"
    r.largura <= 0 || r.profundidade <= 0 -> "Largura e profundidade devem ser maiores que zero"
    r.largura > 2000 || r.profundidade > 2000 -> "Medidas acima de 2.000 m — confira a unidade (metros)"
    else -> null
}

private fun moduloLigado(claims: ClaimsToken): Boolean =
    TenantRepository.modulosHabilitados(claims.tenantId).contains(Modulos.PLANTA_GALPAO)

internal suspend fun ApplicationCall.exigirGalpao(admin: Boolean = false): ClaimsToken? {
    val claims = exigirAuth() ?: return null
    if (!moduloLigado(claims)) {
        respond(HttpStatusCode.Forbidden, mapOf("erro" to "Planta do Galpão não habilitada para esta instalação", "codigo" to "MODULO_DESATIVADO"))
        return null
    }
    if (admin && claims.perfil != "ADMINISTRADOR") {
        respond(HttpStatusCode.Forbidden, mapOf("erro" to "requer perfil ADMINISTRADOR"))
        return null
    }
    return claims
}

internal fun ApplicationCall.uuid(nome: String): UUID? = parameters[nome]?.let { runCatching { UUID.fromString(it) }.getOrNull() }

internal fun nomeUsuario(claims: ClaimsToken): String = TenantTx.run(claims.tenantId) {
    UsersTable.selectAll()
        .where { (UsersTable.tenantId eq claims.tenantId) and (UsersTable.id eq claims.userId) }
        .singleOrNull()
        ?.get(UsersTable.nome) ?: "Usuário"
}
