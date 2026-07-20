package wms.backend.downloads

import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class DownloadItem(val id: String, val nome: String, val descricao: String, val versao: String, val arquivo: String)

@Serializable
private data class Manifest(val items: List<DownloadItem>)

/**
 * Catálogo estático de arquivos — mesma ideia do sistema atual: não gera
 * nada dinamicamente, só lista/serve o que está em public/downloads/
 * (manifest.json + binários). Rota PÚBLICA de propósito (sem auth) — é
 * assim hoje porque precisa funcionar antes de existir login na estação
 * (ex: baixar o instalador do Agente de Balanças pela primeira vez).
 */
fun Route.downloadsRoutes() {
    val pastaBase = File("public/downloads")

    route("/api/downloads") {
        get {
            val manifest = File(pastaBase, "manifest.json")
            if (!manifest.exists()) {
                call.respond(emptyList<DownloadItem>())
                return@get
            }
            val itens = Json.decodeFromString<Manifest>(manifest.readText()).items
            call.respond(itens)
        }

        get("/{id}") {
            val id = call.parameters["id"]
            val manifest = File(pastaBase, "manifest.json")
            if (!manifest.exists()) {
                call.respond(HttpStatusCode.NotFound, mapOf("erro" to "catálogo de downloads não configurado"))
                return@get
            }
            val itens = Json.decodeFromString<Manifest>(manifest.readText()).items
            val item = itens.find { it.id == id }
                ?: return@get call.respond(HttpStatusCode.NotFound, mapOf("erro" to "item '$id' não encontrado"))

            val arquivo = File(pastaBase, item.arquivo)
            if (!arquivo.exists()) {
                call.respond(HttpStatusCode.NotFound, mapOf("erro" to "arquivo do item '$id' não está no servidor"))
                return@get
            }

            call.response.header(HttpHeaders.ContentDisposition, ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, item.arquivo).toString())
            call.respondFile(arquivo)
        }
    }
}
