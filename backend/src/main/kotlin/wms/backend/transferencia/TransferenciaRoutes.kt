package wms.backend.transferencia

import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import wms.backend.auth.exigirAuth
import wms.backend.tenancy.TenantTx
import wms.backend.usuarios.UsersTable
import java.util.UUID

private const val TIPO_TRANSFERENCIA_LOCAIS = "transferencia_locais"

/** Nome do operador — usado só pra denormalizar em app.transferencias.operador_nome (auditoria/exibição na lista, sem join). */
private fun buscarNomeOperador(tenantId: UUID, userId: UUID): String = TenantTx.run(tenantId) {
    UsersTable.selectAll()
        .where { (UsersTable.tenantId eq tenantId) and (UsersTable.id eq userId) }
        .singleOrNull()
        ?.get(UsersTable.nome) ?: "Operador"
}

/**
 * Diferente de tarefas/separacao (que resolvem tenant via `?tenant=<slug>` — anteriores
 * ao login real existir), estas rotas derivam tenant e usuário do JWT (`exigirAuth()`),
 * o que já dá o operador responsável de graça pra auditoria.
 */
fun Route.transferenciaRoutes() {
    route("/api") {

        get("/modelos-nota") {
            val claims = call.exigirAuth() ?: return@get
            val tipo = call.request.queryParameters["tipo"]
            if (tipo.isNullOrBlank()) {
                call.respond(HttpStatusCode.BadRequest, mapOf("erro" to "query param 'tipo' é obrigatório"))
                return@get
            }

            val modelo = ModelosNotaRepository.buscarPorTipo(claims.tenantId, tipo)
            if (modelo == null) {
                call.respond(HttpStatusCode.NotFound, mapOf("erro" to "Modelo de nota não configurado para este tenant e tipo de atividade"))
                return@get
            }
            call.respond(modelo)
        }

        get("/locais/{codigo}") {
            val claims = call.exigirAuth() ?: return@get
            val codigo = call.parameters["codigo"] ?: return@get call.respond(HttpStatusCode.BadRequest)

            val local = LocaisProdutosRepository.validarLocal(claims.tenantId, codigo)
            if (local == null) {
                call.respond(HttpStatusCode.NotFound, mapOf("erro" to "Local não encontrado"))
                return@get
            }
            call.respond(local)
        }

        get("/produtos/{codigo}") {
            val claims = call.exigirAuth() ?: return@get
            val codigo = call.parameters["codigo"] ?: return@get call.respond(HttpStatusCode.BadRequest)
            val local = call.request.queryParameters["local"]

            when (val resultado = LocaisProdutosRepository.validarProduto(claims.tenantId, codigo, local)) {
                is ResultadoValidacaoProduto.Ok -> call.respond(resultado.produto)
                ResultadoValidacaoProduto.NaoEncontrado -> call.respond(HttpStatusCode.NotFound, mapOf("erro" to "Código não reconhecido"))
                ResultadoValidacaoProduto.SemSaldo -> call.respond(HttpStatusCode.Conflict, mapOf("erro" to "Sem saldo na origem"))
            }
        }

        post("/transferencias") {
            val claims = call.exigirAuth() ?: return@post
            val body = call.receive<CriarTransferenciaRequest>()

            if (ModelosNotaRepository.buscarPorTipo(claims.tenantId, TIPO_TRANSFERENCIA_LOCAIS) == null) {
                call.respond(HttpStatusCode.NotFound, mapOf("erro" to "Modelo de nota não configurado para este tenant e tipo de atividade"))
                return@post
            }

            val origem = body.origem.trim().uppercase()
            val destino = body.destino.trim().uppercase()

            if (destino == origem) {
                call.respond(HttpStatusCode.Conflict, mapOf("erro" to "Destino não pode ser igual à origem"))
                return@post
            }

            val localOrigem = LocaisProdutosRepository.validarLocal(claims.tenantId, origem)
            if (localOrigem == null || !localOrigem.ativo) {
                call.respond(HttpStatusCode.NotFound, mapOf("erro" to "Local de origem não encontrado ou inativo"))
                return@post
            }
            val localDestino = LocaisProdutosRepository.validarLocal(claims.tenantId, destino)
            if (localDestino == null || !localDestino.ativo) {
                call.respond(HttpStatusCode.NotFound, mapOf("erro" to "Local de destino não encontrado ou inativo"))
                return@post
            }

            val nome = buscarNomeOperador(claims.tenantId, claims.userId)
            val criada = TransferenciaRepository.criar(claims.tenantId, origem, destino, body.canalOrigem, claims.userId, nome)
            call.respond(HttpStatusCode.Created, criada)
        }

        post("/transferencias/{id}/itens") {
            val claims = call.exigirAuth() ?: return@post
            val transferenciaId = call.parameters["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                ?: return@post call.respond(HttpStatusCode.BadRequest)
            val body = call.receive<AdicionarItemRequest>()

            val origem = TransferenciaRepository.buscarOrigem(claims.tenantId, transferenciaId)
            if (origem == null) {
                call.respond(HttpStatusCode.NotFound, mapOf("erro" to "Transferência não encontrada"))
                return@post
            }

            when (val resultado = LocaisProdutosRepository.validarProduto(claims.tenantId, body.codigoLido, local = origem)) {
                is ResultadoValidacaoProduto.Ok -> {
                    val produto = resultado.produto
                    val quantidadeInformada = body.quantidade?.trim()?.replace(",", ".")?.toBigDecimalOrNull()

                    if (produto.modo == "bulk" && quantidadeInformada == null) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("erro" to "Informe a quantidade — produto a granel"))
                        return@post
                    }
                    val quantidade = quantidadeInformada
                        ?: if (produto.modo == "labelqty") produto.qtdEtiqueta?.toBigDecimalOrNull() ?: java.math.BigDecimal.ONE else java.math.BigDecimal.ONE

                    if (quantidade <= java.math.BigDecimal.ZERO) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("erro" to "'quantidade' precisa ser um número maior que zero"))
                        return@post
                    }

                    val item = TransferenciaRepository.adicionarItem(claims.tenantId, transferenciaId, produto, produto.ctrl, quantidade)
                    if (item == null) {
                        call.respond(HttpStatusCode.NotFound, mapOf("erro" to "Transferência não encontrada ou já confirmada"))
                        return@post
                    }
                    call.respond(item)
                }
                ResultadoValidacaoProduto.NaoEncontrado -> call.respond(HttpStatusCode.NotFound, mapOf("erro" to "Código não reconhecido"))
                ResultadoValidacaoProduto.SemSaldo -> call.respond(HttpStatusCode.Conflict, mapOf("erro" to "Sem saldo na origem"))
            }
        }

        delete("/transferencias/{id}/itens/{itemId}") {
            val claims = call.exigirAuth() ?: return@delete
            val transferenciaId = call.parameters["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                ?: return@delete call.respond(HttpStatusCode.BadRequest)
            val itemId = call.parameters["itemId"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                ?: return@delete call.respond(HttpStatusCode.BadRequest)

            val removido = TransferenciaRepository.removerItem(claims.tenantId, transferenciaId, itemId)
            if (!removido) {
                call.respond(HttpStatusCode.NotFound, mapOf("erro" to "Item não encontrado"))
                return@delete
            }
            call.respond(HttpStatusCode.NoContent)
        }

        post("/transferencias/{id}/confirmar") {
            val claims = call.exigirAuth() ?: return@post
            val transferenciaId = call.parameters["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                ?: return@post call.respond(HttpStatusCode.BadRequest)

            if (ModelosNotaRepository.buscarPorTipo(claims.tenantId, TIPO_TRANSFERENCIA_LOCAIS) == null) {
                call.respond(HttpStatusCode.UnprocessableEntity, mapOf("erro" to "Configuração pendente — contate o administrador."))
                return@post
            }

            val ok = TransferenciaRepository.confirmar(claims.tenantId, transferenciaId)
            if (!ok) {
                call.respond(HttpStatusCode.NotFound, mapOf("erro" to "Transferência não encontrada ou já confirmada"))
                return@post
            }
            TransferenciaWriteBackQueue.enfileirar(claims.tenantId, transferenciaId)
            call.respond(HttpStatusCode.OK, mapOf("ok" to true))
        }

        get("/transferencias") {
            val claims = call.exigirAuth() ?: return@get
            val canal = call.request.queryParameters["canal"]
            val status = call.request.queryParameters["status"]
            val busca = call.request.queryParameters["busca"]
            val pagina = call.request.queryParameters["pagina"]?.toIntOrNull() ?: 1

            val resultado = TransferenciaRepository.listar(claims.tenantId, canal, status, busca, pagina)
            call.respond(
                TransferenciasPaginadasDto(
                    itens = resultado.itens,
                    paginaAtual = resultado.paginaAtual,
                    totalPaginas = resultado.totalPaginas,
                    totalRegistros = resultado.totalRegistros,
                ),
            )
        }
    }
}
