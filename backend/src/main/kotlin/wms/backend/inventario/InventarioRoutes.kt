package wms.backend.inventario

import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import wms.backend.auth.exigirAuth
import wms.backend.tenancy.TenantTx
import wms.backend.transferencia.ModelosNotaRepository
import wms.backend.usuarios.UsersTable
import java.util.UUID

private const val TIPO_AJUSTE_INVENTARIO = "ajuste_inventario"

/** Mesma razão de wms.backend.transferencia.TransferenciaRoutes: só denormaliza o nome pra exibição/auditoria. */
private fun buscarNomeOperador(tenantId: UUID, userId: UUID): String = TenantTx.run(tenantId) {
    UsersTable.selectAll()
        .where { (UsersTable.tenantId eq tenantId) and (UsersTable.id eq userId) }
        .singleOrNull()
        ?.get(UsersTable.nome) ?: "Operador"
}

/** Mesmo padrão de tenant/usuário via JWT já usado em transferenciaRoutes(). */
fun Route.inventarioRoutes() {
    route("/api/inventarios") {

        post {
            val claims = call.exigirAuth() ?: return@post
            val body = call.receive<AbrirInventarioRequest>()

            if (body.escopoValores.isEmpty()) {
                call.respond(HttpStatusCode.BadRequest, mapOf("erro" to "'escopoValores' não pode ser vazio"))
                return@post
            }

            val nome = buscarNomeOperador(claims.tenantId, claims.userId)
            val criado = InventarioRepository.abrir(claims.tenantId, body.descricao, body.escopoTipo, body.escopoValores, claims.userId, nome)
            call.respond(HttpStatusCode.Created, criado)
        }

        get {
            val claims = call.exigirAuth() ?: return@get
            val status = call.request.queryParameters["status"]
            val busca = call.request.queryParameters["busca"]
            val pagina = call.request.queryParameters["pagina"]?.toIntOrNull() ?: 1

            val resultado = InventarioRepository.listar(claims.tenantId, status, busca, pagina)
            call.respond(
                InventariosPaginadosDto(
                    itens = resultado.itens,
                    paginaAtual = resultado.paginaAtual,
                    totalPaginas = resultado.totalPaginas,
                    totalRegistros = resultado.totalRegistros,
                ),
            )
        }

        get("/{id}") {
            val claims = call.exigirAuth() ?: return@get
            val id = call.parameters["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                ?: return@get call.respond(HttpStatusCode.BadRequest)

            val detalhe = InventarioRepository.detalhe(claims.tenantId, id)
            if (detalhe == null) {
                call.respond(HttpStatusCode.NotFound, mapOf("erro" to "Inventário não encontrado"))
                return@get
            }
            call.respond(detalhe)
        }

        patch("/{id}/status") {
            val claims = call.exigirAuth() ?: return@patch
            val id = call.parameters["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                ?: return@patch call.respond(HttpStatusCode.BadRequest)
            val body = call.receive<MudarStatusRequest>()

            val ok = InventarioRepository.mudarStatus(claims.tenantId, id, body.status)
            if (!ok) {
                call.respond(HttpStatusCode.NotFound, mapOf("erro" to "Inventário não encontrado"))
                return@patch
            }
            call.respond(HttpStatusCode.OK, mapOf("ok" to true))
        }

        post("/{id}/contagem") {
            val claims = call.exigirAuth() ?: return@post
            val id = call.parameters["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                ?: return@post call.respond(HttpStatusCode.BadRequest)
            val body = call.receive<RegistrarContagemRequest>()
            val nome = buscarNomeOperador(claims.tenantId, claims.userId)

            // Contagem é sempre feita pelo coletor — o desktop só abre/acompanha/aprova (ver seção 4/5 do escopo).
            when (val resultado = InventarioRepository.registrarContagem(claims.tenantId, id, body.local, body.codigoLido, body.quantidade, claims.userId, nome, "coletor")) {
                is ResultadoContagem.Ok -> call.respond(resultado.item)
                ResultadoContagem.InventarioNaoEncontrado -> call.respond(HttpStatusCode.NotFound, mapOf("erro" to "Inventário não encontrado"))
                ResultadoContagem.InventarioEncerrado -> call.respond(HttpStatusCode.Conflict, mapOf("erro" to "Inventário já foi encerrado"))
                ResultadoContagem.ProdutoNaoReconhecido -> call.respond(HttpStatusCode.NotFound, mapOf("erro" to "Código não reconhecido"))
                ResultadoContagem.QuantidadeInvalida -> call.respond(HttpStatusCode.BadRequest, mapOf("erro" to "Informe a quantidade — produto a granel"))
            }
        }

        get("/{id}/divergencias") {
            val claims = call.exigirAuth() ?: return@get
            val id = call.parameters["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                ?: return@get call.respond(HttpStatusCode.BadRequest)
            call.respond(InventarioRepository.divergencias(claims.tenantId, id))
        }

        post("/{id}/aprovar") {
            val claims = call.exigirAuth() ?: return@post
            val id = call.parameters["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                ?: return@post call.respond(HttpStatusCode.BadRequest)

            if (ModelosNotaRepository.buscarPorTipo(claims.tenantId, TIPO_AJUSTE_INVENTARIO) == null) {
                call.respond(HttpStatusCode.UnprocessableEntity, mapOf("erro" to "Configuração pendente — contate o administrador."))
                return@post
            }

            val nome = buscarNomeOperador(claims.tenantId, claims.userId)
            when (val resultado = InventarioRepository.aprovar(claims.tenantId, id, claims.userId, nome)) {
                is ResultadoAprovacao.Ok -> {
                    InventarioWriteBackQueue.enfileirar(claims.tenantId, id)
                    call.respond(AprovarResponse(ok = true, avisoRecontagem = resultado.avisoRecontagem))
                }
                ResultadoAprovacao.NaoEncontrado -> call.respond(HttpStatusCode.NotFound, mapOf("erro" to "Inventário não encontrado"))
                ResultadoAprovacao.ItensPendentes -> call.respond(HttpStatusCode.Conflict, mapOf("erro" to "Existem itens pendentes de contagem"))
            }
        }

        post("/{id}/cancelar") {
            val claims = call.exigirAuth() ?: return@post
            val id = call.parameters["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                ?: return@post call.respond(HttpStatusCode.BadRequest)

            val ok = InventarioRepository.mudarStatus(claims.tenantId, id, InventarioStatus.CANCELADO)
            if (!ok) {
                call.respond(HttpStatusCode.NotFound, mapOf("erro" to "Inventário não encontrado"))
                return@post
            }
            call.respond(HttpStatusCode.OK, mapOf("ok" to true))
        }
    }
}
