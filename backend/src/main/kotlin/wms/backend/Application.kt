package wms.backend

import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.calllogging.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.Json
import wms.backend.auth.authRoutes
import wms.backend.balancas.balancasRoutes
import wms.backend.configconferencia.ConfigConferenciaSyncWorker
import wms.backend.configconferencia.configConferenciaRoutes
import wms.backend.downloads.downloadsRoutes
import wms.backend.inventario.inventarioRoutes
import wms.backend.liberacaocorte.liberacaoCorteRoutes
import wms.backend.produtos.ProdutoCatalogoSyncWorker
import wms.backend.separacao.separacaoRoutes
import wms.backend.tarefas.SyncWorkerPool
import wms.backend.tarefas.tarefasRoutes
import wms.backend.tenancy.tenantRoutes
import wms.backend.tipooperacao.TipoOperacaoSyncWorker
import wms.backend.tipooperacao.tipoOperacaoRoutes
import wms.backend.transferencia.TransferenciaAbandonoWorker
import wms.backend.transferencia.transferenciaRoutes
import wms.backend.usuarios.usuariosRoutes

fun main() {
    val port = System.getenv("WMS_PORT")?.toIntOrNull() ?: 8080
    embeddedServer(Netty, port = port, host = "0.0.0.0", module = Application::module)
        .start(wait = true)
}

fun Application.module() {
    install(CallLogging)

    install(ContentNegotiation) {
        json(Json {
            prettyPrint = true
            ignoreUnknownKeys = true
            encodeDefaults = true // tier/status/ativo etc. têm default no Kotlin
                                  // mas o frontend precisa vê-los sempre, não só
                                  // quando divergem do valor default do DTO.
        })
    }

    install(CORS) {
        anyHost() // ambiente de desenvolvimento apenas — restringir em produção
        allowHeader(HttpHeaders.ContentType)
        allowHeader(HttpHeaders.Authorization)
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Post)
        allowMethod(HttpMethod.Patch)
        allowMethod(HttpMethod.Delete)
    }

    install(StatusPages) {
        exception<Throwable> { call, cause ->
            call.application.environment.log.error("Erro não tratado", cause)
            call.respond(HttpStatusCode.InternalServerError, mapOf("erro" to (cause.message ?: "erro interno")))
        }
    }

    Database.init()
    SyncWorkerPool.iniciar()
    TransferenciaAbandonoWorker.iniciar()
    ConfigConferenciaSyncWorker.iniciar()
    TipoOperacaoSyncWorker.iniciar()
    ProdutoCatalogoSyncWorker.iniciar()

    routing {
        get("/health") {
            call.respond(mapOf("status" to "ok"))
        }
        tenantRoutes()
        tarefasRoutes()
        authRoutes()
        usuariosRoutes()
        balancasRoutes()
        downloadsRoutes()
        separacaoRoutes()
        transferenciaRoutes()
        inventarioRoutes()
        configConferenciaRoutes()
        tipoOperacaoRoutes()
        liberacaoCorteRoutes()
    }
}
