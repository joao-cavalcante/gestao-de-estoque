package wms.backend.tools

import kotlinx.coroutines.runBlocking
import wms.backend.Database
import wms.backend.impressao.ImpressaoOcService
import java.io.File
import kotlin.system.exitProcess

/**
 * Teste da impressão da OC: gera o PDF (romaneio + DANFEs) de uma OC sem imprimir nem gravar nada
 * no Sankhya (só SELECT). Sem rota HTTP de propósito, igual às outras ferramentas.
 *
 *   docker exec wms-backend-prod sh -c 'java -cp "/app/lib/[jars]" wms.backend.tools.GerarImpressaoOcKt negri 633 /tmp/oc633.pdf'
 */
fun main(args: Array<String>) {
    val slug = args.getOrNull(0)
    val oc = args.getOrNull(1)?.toLongOrNull()
    val saida = args.getOrNull(2)
    if (slug == null || oc == null || saida == null) {
        System.err.println("uso: GerarImpressaoOcKt <tenant> <ordem de carga> <saida.pdf>")
        exitProcess(2)
    }
    Database.init()
    val codigo = try {
        val r = runBlocking { ImpressaoOcService.gerar(slug, oc) }
        r.notas.forEach { println("nota ${it.nunota} NF ${it.numNota} STATUSNFE '${it.statusNfe}'") }
        if (r.pdf == null) {
            println("PDF não gerado — notas ainda não autorizadas: ${r.pendentes.map { it.nunota }.ifEmpty { "nenhuma nota na OC" }}")
            1
        } else {
            File(saida).writeBytes(r.pdf)
            println("PDF gerado: $saida (${r.pdf.size} bytes, ${r.notas.size} nota(s))")
            0
        }
    } catch (e: Exception) {
        println("ERRO: ${e.message}")
        1
    }
    exitProcess(codigo)
}
