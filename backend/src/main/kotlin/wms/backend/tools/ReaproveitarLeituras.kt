package wms.backend.tools

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import wms.backend.Database
import wms.backend.erp.SankhyaDbExplorerClient
import wms.backend.separacao.SeparacaoEtapasTable
import wms.backend.separacao.SeparacaoLeiturasTable
import wms.backend.separacao.SeparacaoLockRepository
import wms.backend.separacao.SeparacaoRepository
import wms.backend.separacao.SeparacaoService
import wms.backend.separacao.SeparacaoSessoesTable
import wms.backend.separacao.SeparacaoStatus
import wms.backend.tenancy.TenantRepository
import wms.backend.tenancy.TenantTx
import java.math.BigDecimal
import java.util.UUID
import kotlin.system.exitProcess

/**
 * Nota excluída no Sankhya e lançada de novo (NUNOTA novo, mesma NF) depois de já conferida no WMS: a nota nova
 * volta pra fila sem conferência e as pesagens ficam presas no NUNOTA antigo. Esta ferramenta abre a conferência
 * da nota NOVA (mesmo caminho do "iniciar" da fila) e reaplica as leituras da sessão concluída da nota ANTIGA —
 * os itens aparecem como conferidos na tela. Com --finalizar também conclui as etapas e finaliza pelo mesmo
 * caminho do botão da tela (divergência vai pra Liberação de Corte); sem ele quem finaliza é o operador.
 * Caso de uso (20/10/2026): NFs Seara 8836252/3/4, Polenghi 2228657 e Allfood 1039421.
 *
 *   docker exec wms-backend-prod sh -c 'java -cp "/app/lib/[jars]" wms.backend.tools.ReaproveitarLeiturasKt negri 71628 69159'
 *   ... acrescente --executar pra valer; sem ele só mostra o que faria (nada muda). --finalizar = finaliza também.
 *   --origem=<sessaoId> escolhe a sessão da nota antiga (padrão: a concluída mais recente com leituras).
 */
fun main(args: Array<String>) {
    val slug = args.getOrNull(0)
    val destino = args.getOrNull(1)?.toLongOrNull()
    val antiga = args.getOrNull(2)?.toLongOrNull()
    val executar = "--executar" in args
    val finalizar = "--finalizar" in args
    if (slug == null || destino == null || antiga == null) {
        System.err.println("uso: ReaproveitarLeiturasKt <tenant> <nunota nova> <nunota antiga> [--origem=<sessaoId>] [--finalizar] [--executar]")
        exitProcess(2)
    }
    Database.init()
    val codigo = try {
        val origem = args.firstOrNull { it.startsWith("--origem=") }?.substringAfter("=")?.let(UUID::fromString)
        runBlocking { reaproveitar(slug, destino, antiga, executar, finalizar, origem) }
        0
    } catch (e: Exception) {
        println("ERRO: ${e.message}")
        1
    }
    exitProcess(codigo)
}

private data class LeituraAntiga(
    val codprod: Int,
    val controle: String,
    val codvol: String?,
    val codigoBarra: String?,
    val qtd: BigDecimal,
    val peso: BigDecimal?,
)

private suspend fun reaproveitar(slug: String, destino: Long, antiga: Long, executar: Boolean, finalizar: Boolean, origemArg: UUID?) {
    val tenantId = UUID.fromString(TenantRepository.buscarPorSlug(slug)?.id ?: error("tenant '$slug' não encontrado"))

    // 1. Nota nova no Sankhya: sem conferência ou com conferência só em andamento, e não faturada.
    val cab = SankhyaDbExplorerClient.executarQuery(
        slug,
        "SELECT C.NUMNOTA, C.STATUSNOTA, C.NUCONFATUAL, (SELECT F.STATUS FROM TGFCON2 F WHERE F.NUCONF = C.NUCONFATUAL) STATUS_CONF, " +
            "(SELECT COUNT(*) FROM TGFVAR V WHERE V.NUNOTAORIG = C.NUNOTA AND V.NUNOTA <> C.NUNOTA) DESTINOS " +
            "FROM TGFCAB C WHERE C.NUNOTA = $destino",
    ).firstOrNull() ?: error("nota $destino não encontrada no Sankhya")
    val statusConf = cab["STATUS_CONF"]?.trim()
    println("Nota nova $destino (NF ${cab["NUMNOTA"]}) — STATUSNOTA ${cab["STATUSNOTA"]}, NUCONF ${cab["NUCONFATUAL"]} '${statusConf ?: "-"}'")
    if (statusConf != null && statusConf != "A") error("conferência da nota nova está '$statusConf' — só reaproveita em nota sem conferência ou em andamento")
    if ((cab["DESTINOS"]?.toIntOrNull() ?: 0) > 0) error("nota nova já gerou outra nota (faturada)")
    val itensNovos = SankhyaDbExplorerClient.executarQuery(
        slug, "SELECT DISTINCT CODPROD FROM TGFITE WHERE NUNOTA = $destino AND SEQUENCIA > 0",
    ).mapNotNull { it["CODPROD"]?.toIntOrNull() }.toSet()

    // 2. Sessão de origem: a concluída mais recente da nota antiga com leituras, ou a escolhida.
    val sessoesAntigas = TenantTx.run(tenantId) {
        SeparacaoSessoesTable.selectAll()
            .where { (SeparacaoSessoesTable.tenantId eq tenantId) and (SeparacaoSessoesTable.nunota eq antiga.toInt()) }
            .orderBy(SeparacaoSessoesTable.criadoEm to SortOrder.DESC)
            .toList()
    }
    fun leiturasDa(id: UUID) = TenantTx.run(tenantId) {
        SeparacaoLeiturasTable.selectAll()
            .where { (SeparacaoLeiturasTable.tenantId eq tenantId) and (SeparacaoLeiturasTable.sessaoId eq id) }
            .orderBy(SeparacaoLeiturasTable.criadoEm to SortOrder.ASC)
            .map {
                LeituraAntiga(
                    it[SeparacaoLeiturasTable.codprod], it[SeparacaoLeiturasTable.controle], it[SeparacaoLeiturasTable.codvol],
                    it[SeparacaoLeiturasTable.codigoBarra], it[SeparacaoLeiturasTable.qtd], it[SeparacaoLeiturasTable.peso],
                )
            }
    }
    val origem = origemArg?.let { id -> sessoesAntigas.firstOrNull { it[SeparacaoSessoesTable.id] == id } ?: error("sessão $id não é da nota $antiga") }
        ?: sessoesAntigas.firstOrNull { it[SeparacaoSessoesTable.status] == SeparacaoStatus.CONCLUIDA && leiturasDa(it[SeparacaoSessoesTable.id]).isNotEmpty() }
        ?: error("nota antiga $antiga sem sessão concluída com leituras")
    val origemId = origem[SeparacaoSessoesTable.id]
    val leituras = leiturasDa(origemId)
    val etapasOrigem = TenantTx.run(tenantId) {
        SeparacaoEtapasTable.selectAll()
            .where { (SeparacaoEtapasTable.tenantId eq tenantId) and (SeparacaoEtapasTable.sessaoId eq origemId) }
            .toList()
    }
    val volumesPorEtapa: Map<Short, Int> = etapasOrigem.associate { it[SeparacaoEtapasTable.tipoSeparacao] to it[SeparacaoEtapasTable.qtdVol] }
    val divergentePorEtapa: Map<Short, Boolean> = etapasOrigem.associate { it[SeparacaoEtapasTable.tipoSeparacao] to it[SeparacaoEtapasTable.divergente] }
    val operadorId = origem[SeparacaoSessoesTable.operadorId]
    val estacaoId = origem[SeparacaoSessoesTable.estacaoId]
    val volumeTotal = SeparacaoRepository.totalQtdVol(tenantId, origemId)

    println("Origem: nota antiga $antiga, sessão $origemId (NUCONF ${origem[SeparacaoSessoesTable.nuconf]}, ${origem[SeparacaoSessoesTable.criadoEm]})")
    println("Volumes: total $volumeTotal${if (volumesPorEtapa.isNotEmpty()) " — por etapa $volumesPorEtapa" else ""}")
    leituras.groupBy { it.codprod }.forEach { (codprod, ls) ->
        val fora = if (codprod in itensNovos) "" else "  <- NÃO está na nota nova"
        println("  prod $codprod: ${ls.size} leitura(s), qtd ${ls.fold(BigDecimal.ZERO) { a, l -> a + l.qtd }.stripTrailingZeros().toPlainString()}$fora")
    }
    (itensNovos - leituras.map { it.codprod }.toSet()).forEach { println("  prod $it: está na nota nova e NÃO tem leitura na antiga") }
    if (!executar) {
        println("SIMULAÇÃO — nada foi alterado. Rode de novo com --executar.")
        return
    }

    // 3. Abre (ou reaproveita a sessão ativa) da nota nova e espera carregar.
    val novaId = SeparacaoService.iniciar(slug, tenantId, destino).sessaoId
    var nova = SeparacaoRepository.buscarSessao(tenantId, novaId)
    val limite = System.currentTimeMillis() + 180_000
    while (nova?.status == SeparacaoStatus.CARREGANDO && System.currentTimeMillis() < limite) {
        delay(1_000)
        nova = SeparacaoRepository.buscarSessao(tenantId, novaId)
    }
    if (nova?.status != SeparacaoStatus.PRONTA) error("sessão $novaId não ficou pronta (status '${nova?.status}', erro: ${nova?.erro})")
    if (leiturasDa(novaId).isNotEmpty()) error("sessão $novaId da nota nova já tem leituras — não reaplico por cima (evita duplicar)")
    println("Sessão da nota nova $novaId pronta")

    // 4. Reaplica as leituras e copia volumes (e operador); etapas só são concluídas com --finalizar.
    var aplicadas = 0
    leituras.forEach { l ->
        val r = SeparacaoRepository.conferirItem(
            tenantId, novaId, l.codprod, l.controle, l.qtd, permitirQtdMaior = true,
            peso = l.peso, codvolEscanado = l.codvol, codigoBarra = l.codigoBarra,
        )
        if (r == null) println("  NÃO reaplicada (produto não está na nota nova): prod ${l.codprod} qtd ${l.qtd.toPlainString()}") else aplicadas++
    }
    println("Leituras reaplicadas: $aplicadas de ${leituras.size}")
    val etapasNova = SeparacaoRepository.listarEtapas(tenantId, novaId)
    if (etapasNova.isEmpty()) {
        SeparacaoRepository.definirQtdVol(tenantId, novaId, volumeTotal)
    } else {
        etapasNova.forEachIndexed { i, e ->
            val tipo = e.tipoSeparacao.toShort()
            val vol = if (volumesPorEtapa.isNotEmpty()) volumesPorEtapa[tipo] ?: 0 else if (i == 0) volumeTotal else 0
            SeparacaoRepository.definirQtdVolEtapa(tenantId, novaId, tipo, vol)
            if (finalizar) SeparacaoRepository.concluirEtapa(tenantId, novaId, tipo, "Reaproveitada da nota $antiga", divergentePorEtapa[tipo] ?: false)
        }
    }
    if (operadorId != null) SeparacaoRepository.definirOperador(tenantId, novaId, operadorId, estacaoId ?: operadorId)
    SeparacaoLockRepository.liberarTodos(tenantId, novaId)
    if (!finalizar) {
        println("OK — itens conferidos na sessão $novaId; falta finalizar pela tela.")
        return
    }

    // 5. Finaliza pelo mesmo caminho do botão da tela.
    println("Finalizando…")
    val resultado = SeparacaoService.finalizar(slug, tenantId, novaId, semCorte = false, usuarioFinalizadorId = operadorId)
    println("OK — NUCONF ${resultado.nuconf}, aguardando liberação de corte: ${resultado.aguardandoCorte}")
}
