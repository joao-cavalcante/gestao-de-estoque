package wms.backend.tools

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import wms.backend.Database
import wms.backend.erp.SankhyaDbExplorerClient
import kotlin.system.exitProcess

/**
 * Diagnóstico: roda um SELECT no banco do Sankhya de um tenant via DbExplorerSP.executeQuery,
 * com as credenciais do próprio tenant (cifradas no banco — por isso roda dentro do backend).
 * Sem rota HTTP de propósito: só quem tem acesso ao container consegue usar.
 *
 *   docker exec -i wms-backend-prod sh -c 'java -cp "/app/lib/[todos os jars]" wms.backend.tools.SankhyaQueryKt negri' < consulta.sql
 *   (classpath = diretório /app/lib com curinga de jars — o curinga não cabe neste comentário)
 *
 * Só SELECT/WITH (uma instrução). Saída: uma linha JSON por registro.
 */
fun main(args: Array<String>) {
    val tenant = args.getOrNull(0) ?: run {
        System.err.println("uso: SankhyaQueryKt <tenant> [sql]  (sem sql = lê do stdin)")
        exitProcess(2)
    }
    val sql = (args.getOrNull(1) ?: generateSequence(::readLine).joinToString("\n")).trim().trimEnd(';').trim()
    val inicio = sql.trimStart().take(6).uppercase()
    if (!(inicio.startsWith("SELECT") || inicio.startsWith("WITH")) || sql.contains(';')) {
        System.err.println("só é permitido um SELECT (ou WITH ... SELECT) por execução")
        exitProcess(2)
    }

    Database.init()
    val linhas = try {
        runBlocking { SankhyaDbExplorerClient.executarQuery(tenant, sql) }
    } catch (e: Exception) {
        System.err.println("ERRO: ${e.message}")
        exitProcess(1)
    }
    linhas.forEach { linha ->
        println(Json.encodeToString(JsonObject.serializer(), JsonObject(linha.mapValues { (_, v) -> v?.let(::JsonPrimitive) ?: JsonNull })))
    }
    System.err.println("(${linhas.size} linha(s))")
    exitProcess(0)
}
