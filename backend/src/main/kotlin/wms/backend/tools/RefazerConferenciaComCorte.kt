package wms.backend.tools

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
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
import java.time.Instant
import java.util.UUID
import kotlin.system.exitProcess

/**
 * Correção de conferência fechada como "Finalizada divergente" (TGFCON2.STATUS 'D') por engano —
 * o operador apertou "Finalizar divergente" em vez de "Ajustar". Refaz a conferência pelo caminho do
 * corte: exclui a conferência no Sankhya (ConferenciaSP.excluirConferencia), abre uma sessão nova,
 * reaplica EXATAMENTE as leituras da sessão original (mesmas quantidades/pesos), copia volumes e
 * operador, conclui as etapas e finaliza com `cortar` — a divergência vai pra Liberação de Corte.
 *
 * Sem rota HTTP de propósito (mesmo esquema do SankhyaQueryKt): só quem tem acesso ao container.
 *
 *   docker exec wms-backend-prod sh -c 'java -cp "/app/lib/[jars]" wms.backend.tools.RefazerConferenciaComCorteKt negri 58447'
 *   ... acrescente --executar pra valer; sem ele só mostra o que faria (nada muda).
 *   --origem=<sessaoId> escolhe a sessão local de onde vêm as leituras (padrão: a do NUCONF atual).
 */
fun main(args: Array<String>) {
    val slug = args.getOrNull(0)
    val nunota = args.getOrNull(1)?.toLongOrNull()
    val executar = "--executar" in args
    if (slug == null || nunota == null) {
        System.err.println("uso: RefazerConferenciaComCorteKt <tenant> <nunota> [--executar]")
        exitProcess(2)
    }
    Database.init()
    val codigo = try {
        val origem = args.firstOrNull { it.startsWith("--origem=") }?.substringAfter("=")?.let(UUID::fromString)
        runBlocking { refazer(slug, nunota, executar, origem) }
        0
    } catch (e: Exception) {
        println("ERRO: ${e.message}")
        1
    }
    exitProcess(codigo)
}

private data class Leitura(
    val codprod: Int,
    val controle: String,
    val codvol: String?,
    val codigoBarra: String?,
    val qtd: BigDecimal,
    val peso: BigDecimal?,
)

private suspend fun refazer(slug: String, nunota: Long, executar: Boolean, origemArg: UUID?) {
    val tenantId = UUID.fromString(TenantRepository.buscarPorSlug(slug)?.id ?: error("tenant '$slug' não encontrado"))

    // 1. Estado no Sankhya: só mexe em conferência finalizada DIVERGENTE de nota não faturada.
    val cab = SankhyaDbExplorerClient.executarQuery(
        slug,
        "SELECT C.NUCONFATUAL, (SELECT F.STATUS FROM TGFCON2 F WHERE F.NUCONF = C.NUCONFATUAL) STATUS_CONF, " +
            "(SELECT COUNT(*) FROM TGFVAR V WHERE V.NUNOTAORIG = C.NUNOTA AND V.NUNOTA <> C.NUNOTA) DESTINOS " +
            "FROM TGFCAB C WHERE C.NUNOTA = $nunota",
    ).firstOrNull() ?: error("nota $nunota não encontrada no Sankhya")
    val nuconf = cab["NUCONFATUAL"]?.toIntOrNull() ?: error("nota $nunota sem conferência (NUCONFATUAL vazio)")
    val statusConf = cab["STATUS_CONF"]?.trim()
    println("Nota $nunota — NUCONF $nuconf, status no Sankhya '$statusConf', notas geradas: ${cab["DESTINOS"]}")
    if (statusConf !in setOf("D", "RD")) error("só refaz conferência 'D'/'RD' (Finalizada divergente); esta está '$statusConf'")
    if ((cab["DESTINOS"]?.toIntOrNull() ?: 0) > 0) error("nota já gerou outra nota (faturada) — não dá pra refazer a conferência")

    // 2. Sessão local de origem: a do NUCONF atual com leituras, ou a escolhida com --origem=<id>.
    val sessoesDaNota = TenantTx.run(tenantId) {
        SeparacaoSessoesTable.selectAll()
            .where { (SeparacaoSessoesTable.tenantId eq tenantId) and (SeparacaoSessoesTable.nunota eq nunota.toInt()) }
            .orderBy(SeparacaoSessoesTable.criadoEm to SortOrder.DESC)
            .toList()
    }
    fun qtdLeituras(id: UUID) = TenantTx.run(tenantId) {
        SeparacaoLeiturasTable.selectAll()
            .where { (SeparacaoLeiturasTable.tenantId eq tenantId) and (SeparacaoLeiturasTable.sessaoId eq id) }
            .count()
    }
    println("Sessões locais da nota:")
    sessoesDaNota.forEach { s ->
        println("  ${s[SeparacaoSessoesTable.id]} status=${s[SeparacaoSessoesTable.status]} nuconf=${s[SeparacaoSessoesTable.nuconf]} " +
            "criada=${s[SeparacaoSessoesTable.criadoEm]} leituras=${qtdLeituras(s[SeparacaoSessoesTable.id])}")
    }
    val origemEscolhida = origemArg?.let { id -> sessoesDaNota.firstOrNull { it[SeparacaoSessoesTable.id] == id } ?: error("sessão $id não é desta nota") }
    val origem = origemEscolhida
        ?: sessoesDaNota.filter { it[SeparacaoSessoesTable.nuconf] == nuconf }.firstOrNull { qtdLeituras(it[SeparacaoSessoesTable.id]) > 0 }
        ?: error("nenhuma sessão local do NUCONF $nuconf com leituras — escolha uma das sessões acima com --origem=<id>")
    val origemId = origem[SeparacaoSessoesTable.id]

    val leituras = TenantTx.run(tenantId) {
        SeparacaoLeiturasTable.selectAll()
            .where { (SeparacaoLeiturasTable.tenantId eq tenantId) and (SeparacaoLeiturasTable.sessaoId eq origemId) }
            .orderBy(SeparacaoLeiturasTable.criadoEm to SortOrder.ASC)
            .map {
                Leitura(
                    it[SeparacaoLeiturasTable.codprod], it[SeparacaoLeiturasTable.controle], it[SeparacaoLeiturasTable.codvol],
                    it[SeparacaoLeiturasTable.codigoBarra], it[SeparacaoLeiturasTable.qtd], it[SeparacaoLeiturasTable.peso],
                )
            }
    }
    val volumesPorEtapa: Map<Short, Int> = TenantTx.run(tenantId) {
        SeparacaoEtapasTable.selectAll()
            .where { (SeparacaoEtapasTable.tenantId eq tenantId) and (SeparacaoEtapasTable.sessaoId eq origemId) }
            .associate { it[SeparacaoEtapasTable.tipoSeparacao] to it[SeparacaoEtapasTable.qtdVol] }
    }
    val volumeTotal = SeparacaoRepository.totalQtdVol(tenantId, origemId)
    val operadorId = origem[SeparacaoSessoesTable.operadorId]
    val estacaoId = origem[SeparacaoSessoesTable.estacaoId]

    println("Sessão de origem $origemId (status '${origem[SeparacaoSessoesTable.status]}'), operador $operadorId")
    println("Volumes: total $volumeTotal${if (volumesPorEtapa.isNotEmpty()) " — por etapa $volumesPorEtapa" else ""}")
    leituras.groupBy { it.codprod to it.controle }.forEach { (chave, ls) ->
        val peso = ls.mapNotNull { it.peso }.takeIf { it.isNotEmpty() }?.fold(BigDecimal.ZERO, BigDecimal::add)
        println("  prod ${chave.first}${if (chave.second.isNotBlank()) " lote ${chave.second}" else ""}: " +
            "${ls.size} leitura(s), qtd ${ls.fold(BigDecimal.ZERO) { a, l -> a + l.qtd }.stripTrailingZeros().toPlainString()}" +
            (peso?.let { ", peso ${it.stripTrailingZeros().toPlainString()}" } ?: ""))
    }
    if (!executar) {
        println("SIMULAÇÃO — nada foi alterado. Rode de novo com --executar.")
        return
    }

    // 3. Exclui a conferência no Sankhya e aposenta as sessões locais da nota (a de origem fica guardada,
    //    só sai de 'concluida' pra nova não ser tratada como recontagem).
    println("Excluindo a conferência $nuconf no Sankhya…")
    SeparacaoService.excluirConferenciaSankhya(slug, nunota)
    TenantTx.run(tenantId) {
        SeparacaoSessoesTable.update({
            (SeparacaoSessoesTable.tenantId eq tenantId) and (SeparacaoSessoesTable.nunota eq nunota.toInt()) and
                (SeparacaoSessoesTable.status inList listOf(SeparacaoStatus.CONCLUIDA, SeparacaoStatus.PRONTA, SeparacaoStatus.CARREGANDO))
        }) {
            it[status] = SeparacaoStatus.CANCELADA
            it[erro] = "Refeita com corte (correção de 'Finalizar divergente' por engano)"
            it[atualizadoEm] = Instant.now()
        }
    }

    // 4. Sessão nova (abre a conferência de novo no Sankhya) e espera carregar.
    val novaId = SeparacaoService.iniciar(slug, tenantId, nunota).sessaoId
    println("Sessão nova $novaId — carregando…")
    var nova = SeparacaoRepository.buscarSessao(tenantId, novaId)
    val limite = System.currentTimeMillis() + 180_000
    while (nova?.status == SeparacaoStatus.CARREGANDO && System.currentTimeMillis() < limite) {
        delay(1_000)
        nova = SeparacaoRepository.buscarSessao(tenantId, novaId)
    }
    if (nova?.status != SeparacaoStatus.PRONTA) {
        error("sessão nova não ficou pronta (status '${nova?.status}', erro: ${nova?.erro}) — a conferência JÁ FOI EXCLUÍDA no Sankhya; confira a nota na fila")
    }

    // 5. Reaplica as leituras originais.
    val naoAplicadas = mutableListOf<Leitura>()
    leituras.forEach { l ->
        val r = SeparacaoRepository.conferirItem(
            tenantId, novaId, l.codprod, l.controle, l.qtd, permitirQtdMaior = true,
            peso = l.peso, codvolEscanado = l.codvol, codigoBarra = l.codigoBarra,
        )
        if (r == null) naoAplicadas += l
    }
    println("Leituras reaplicadas: ${leituras.size - naoAplicadas.size} de ${leituras.size}")
    naoAplicadas.forEach { println("  NÃO reaplicada (produto não está no pedido): prod ${it.codprod} qtd ${it.qtd.toPlainString()}") }

    // 6. Volumes, operador e etapas.
    if (operadorId != null) SeparacaoRepository.definirOperador(tenantId, novaId, operadorId, estacaoId ?: operadorId)
    val etapasNova = SeparacaoRepository.listarEtapas(tenantId, novaId)
    if (etapasNova.isEmpty()) {
        SeparacaoRepository.definirQtdVol(tenantId, novaId, volumeTotal)
    } else {
        // Volume por etapa igual ao original; se a original não era por etapa, o total vai na primeira.
        etapasNova.forEachIndexed { i, e ->
            val tipo = e.tipoSeparacao.toShort()
            val vol = if (volumesPorEtapa.isNotEmpty()) volumesPorEtapa[tipo] ?: 0 else if (i == 0) volumeTotal else 0
            SeparacaoRepository.definirQtdVolEtapa(tenantId, novaId, tipo, vol)
            SeparacaoRepository.concluirEtapa(tenantId, novaId, tipo, "Correção (refeita com corte)", divergente = true)
        }
    }
    SeparacaoLockRepository.liberarTodos(tenantId, novaId)

    // 7. Finaliza pelo corte (mesmo caminho do botão "Ajustar").
    println("Finalizando com cortar…")
    val resultado = SeparacaoService.finalizar(slug, tenantId, novaId, semCorte = false, usuarioFinalizadorId = operadorId)
    println("OK — NUCONF novo ${resultado.nuconf}, aguardando liberação de corte: ${resultado.aguardandoCorte}")
}
