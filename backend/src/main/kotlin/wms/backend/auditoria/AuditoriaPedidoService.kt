package wms.backend.auditoria

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import wms.backend.erp.SankhyaDbExplorerClient
import wms.backend.tenancy.TenantTx
import java.util.UUID

/**
 * Auditoria de um pedido — tudo o que aconteceu com ele, do Sankhya e do WMS, numa linha do tempo só.
 * Só leitura. Cada fonte é independente: a que falhar vira aviso e o resto aparece mesmo assim.
 */
object AuditoriaPedidoService {

    class PedidoNaoEncontradoException(msg: String) : Exception(msg)

    private val ETAPA = mapOf(1 to "Seco", 2 to "Refrigerado", 3 to "Congelado")
    private val STATUS_CONF = mapOf(
        "A" to "em andamento", "F" to "finalizada", "R" to "aguardando recontagem",
        "C" to "aguardando liberação de corte", "D" to "finalizada divergente",
    )

    suspend fun auditar(tenantSlug: String, tenantId: UUID, nunotaOuNumero: Long): AuditoriaPedidoDto = coroutineScope {
        val avisos = mutableListOf<String>()
        val cab = buscarCabecalho(tenantSlug, nunotaOuNumero)
            ?: throw PedidoNaoEncontradoException("Pedido $nunotaOuNumero não encontrado no Sankhya (nem como número único, nem como número do pedido).")
        val nunota = cab.nunota

        val itensAsync = async { runCatching { buscarItens(tenantSlug, nunota, cab.nuconfAtual) } }
        val confsAsync = async { runCatching { buscarConferencias(tenantSlug, nunota) } }
        val notasAsync = async { runCatching { buscarNotasGeradas(tenantSlug, nunota) } }
        val locaisAsync = async { runCatching { withContext(Dispatchers.IO) { eventosWms(tenantId, nunota) } } }

        val itens = itensAsync.await().onFailure { avisos += "Itens do Sankhya: ${it.message}" }.getOrDefault(emptyList())
        val nomes = itens.associate { it.codprod to it.produto }
        val (conferencias, liberacoes) = confsAsync.await().onFailure { avisos += "Conferências do Sankhya: ${it.message}" }
            .getOrDefault(emptyList<AuditoriaEventoDto>() to emptyList())
        val notas = notasAsync.await().onFailure { avisos += "Notas geradas: ${it.message}" }.getOrDefault(emptyList())
        val locais = locaisAsync.await().onFailure { avisos += "Histórico do WMS: ${it.message}" }.getOrDefault(emptyList())
            .map { ev -> ev.copy(titulo = ev.titulo.replace(Regex("""\{prod:(\d+)\}""")) { m -> nomeProduto(m.groupValues[1].toInt(), nomes) }) }

        val eventos = buildList {
            cab.incluidoEm?.let {
                add(AuditoriaEventoDto(it, "SANKHYA", "pedido", "Pedido incluído", "${cab.top ?: "TOP ${cab.codtipoper}"} · ${itens.size} item(ns)", cab.vendedor))
            }
            addAll(conferencias)
            addAll(liberacoes)
            addAll(locais)
            notas.forEach { n ->
                n.geradaEm?.let {
                    add(AuditoriaEventoDto(it, "SANKHYA", "nota", "Nota ${n.numnota ?: n.nunota} gerada", "${n.top ?: ""} · nº único ${n.nunota}".trim(' ', '·'), nivel = "sucesso"))
                }
            }
        }.sortedWith(compareBy({ it.quando }, { ORDEM_TIPO.indexOf(it.tipo) }))

        AuditoriaPedidoDto(cab, itens, notas, eventos, avisos)
    }

    /** Desempate de eventos no mesmo segundo — ordem natural do fluxo. */
    private val ORDEM_TIPO = listOf(
        "pedido", "impressao", "abertura", "diagnostico", "operador", "leitura", "etiqueta", "etapa",
        "conferencia", "liberacao", "status", "carregamento", "nota",
    )

    private fun nomeProduto(codprod: Int, nomes: Map<Int, String>) = nomes[codprod]?.let { "$codprod · $it" } ?: "produto $codprod"

    // ─── Sankhya ─────────────────────────────────────────────────────────────

    private suspend fun buscarCabecalho(tenantSlug: String, numero: Long): AuditoriaCabecalhoDto? {
        val sql = """
            SELECT C.NUNOTA, C.NUMNOTA, C.CODTIPOPER, T.DESCROPER, C.TIPMOV, C.STATUSNOTA, C.ORDEMCARGA, C.CODPARC,
                   P.NOMEPARC, C.CODVEND, V.APELIDO, C.VLRNOTA, C.DTMOV, C.HRMOV, C.DTALTER, C.NUCONFATUAL
            FROM TGFCAB C
            JOIN TGFPAR P ON P.CODPARC = C.CODPARC
            LEFT JOIN TGFVEN V ON V.CODVEND = C.CODVEND
            LEFT JOIN TGFTOP T ON T.CODTIPOPER = C.CODTIPOPER AND T.DHALTER = C.DHTIPOPER
            WHERE %s
            ORDER BY C.DTMOV DESC, C.NUNOTA DESC
        """.trimIndent()
        // Número único primeiro; não achou, tenta o número do pedido (o mais recente com esse número).
        val linha = SankhyaDbExplorerClient.executarQuery(tenantSlug, sql.format("C.NUNOTA = $numero")).firstOrNull()
            ?: SankhyaDbExplorerClient.executarQuery(tenantSlug, sql.format("C.NUMNOTA = $numero AND C.TIPMOV IN ('P', 'O')")).firstOrNull()
            ?: return null
        return AuditoriaCabecalhoDto(
            nunota = linha["NUNOTA"]!!.toBigDecimal().toLong(),
            numnota = linha["NUMNOTA"].long(),
            codtipoper = linha["CODTIPOPER"].int(),
            top = linha["DESCROPER"],
            tipmov = linha["TIPMOV"],
            statusNota = linha["STATUSNOTA"],
            ordemCarga = linha["ORDEMCARGA"].int()?.takeIf { it > 0 },
            codparc = linha["CODPARC"].int(),
            parceiro = linha["NOMEPARC"],
            codvend = linha["CODVEND"].int(),
            vendedor = linha["APELIDO"],
            valor = linha["VLRNOTA"].dbl(),
            incluidoEm = dataComHora(linha["DTMOV"], linha["HRMOV"]),
            alteradoEm = dataSankhya(linha["DTALTER"]),
            nuconfAtual = linha["NUCONFATUAL"].int(),
        )
    }

    private suspend fun buscarItens(tenantSlug: String, nunota: Long, nuconf: Int?): List<AuditoriaItemDto> {
        val itens = SankhyaDbExplorerClient.executarQuery(
            tenantSlug,
            """
            SELECT I.SEQUENCIA, I.CODPROD, P.DESCRPROD, P.COMPLDESC, I.CODVOL, I.QTDNEG, I.VLRUNIT, I.VLRTOT
            FROM TGFITE I JOIN TGFPRO P ON P.CODPROD = I.CODPROD
            WHERE I.NUNOTA = $nunota ORDER BY P.DESCRPROD, I.SEQUENCIA
            """.trimIndent(),
        )
        val conferido = if (nuconf == null) emptyMap() else SankhyaDbExplorerClient.executarQuery(
            tenantSlug, "SELECT CODPROD, CODVOL, SUM(QTDCONF) AS QTD FROM TGFCOI2 WHERE NUCONF = $nuconf GROUP BY CODPROD, CODVOL",
        ).mapNotNull { r -> r["CODPROD"].int()?.let { it to (r["QTD"].dbl() to r["CODVOL"]) } }.toMap()
        return itens.mapNotNull { r ->
            val codprod = r["CODPROD"].int() ?: return@mapNotNull null
            val compl = r["COMPLDESC"]?.trim()?.takeIf { it.isNotEmpty() }
            AuditoriaItemDto(
                sequencia = r["SEQUENCIA"].int() ?: 0,
                codprod = codprod,
                produto = listOfNotNull(r["DESCRPROD"]?.trim(), compl).joinToString(" - "),
                unidade = r["CODVOL"],
                qtdNegociada = r["QTDNEG"].dbl(),
                qtdConferida = conferido[codprod]?.first,
                unidadeConferida = conferido[codprod]?.second,
                valorUnitario = r["VLRUNIT"].dbl(),
                valorTotal = r["VLRTOT"].dbl(),
            )
        }
    }

    /** Conferências (início/fim) + liberações de corte (pedida/decidida) — eventos do lado do Sankhya. */
    private suspend fun buscarConferencias(tenantSlug: String, nunota: Long): Pair<List<AuditoriaEventoDto>, List<AuditoriaEventoDto>> {
        val confs = SankhyaDbExplorerClient.executarQuery(
            tenantSlug,
            """
            SELECT C.NUCONF, C.STATUS, C.DHINICONF, C.DHFINCONF, C.QTDVOL, U.NOMEUSU
            FROM TGFCON2 C LEFT JOIN TSIUSU U ON U.CODUSU = C.CODUSUCONF
            WHERE C.NUNOTAORIG = $nunota ORDER BY C.NUCONF
            """.trimIndent(),
        )
        val eventosConf = confs.flatMap { c ->
            val nuconf = c["NUCONF"]
            val status = c["STATUS"]?.trim().orEmpty()
            listOfNotNull(
                dataSankhya(c["DHINICONF"])?.let { AuditoriaEventoDto(it, "SANKHYA", "conferencia", "Conferência $nuconf iniciada") },
                dataSankhya(c["DHFINCONF"])?.let {
                    AuditoriaEventoDto(
                        it, "SANKHYA", "conferencia", "Conferência $nuconf ${STATUS_CONF[status] ?: "status $status"}",
                        c["QTDVOL"].int()?.let { v -> "$v volume(s)" }, c["NOMEUSU"],
                        nivel = when (status) { "F" -> "sucesso"; "D", "C" -> "alerta"; "R" -> "erro"; else -> "info" },
                    )
                },
            )
        }
        val nuconfs = confs.mapNotNull { it["NUCONF"].int() }
        if (nuconfs.isEmpty()) return eventosConf to emptyList()

        val libs = SankhyaDbExplorerClient.executarQuery(
            tenantSlug,
            """
            SELECT L.NUCHAVE, L.SEQUENCIA, L.DHSOLICIT, L.DHLIB, L.REPROVADO, L.OBSERVACAO, U.NOMEUSU
            FROM TSILIB L LEFT JOIN TSIUSU U ON U.CODUSU = L.CODUSULIB
            WHERE L.EVENTO = 64 AND L.TABELA = 'TGFCOI2' AND L.NUCHAVE IN (${nuconfs.joinToString(",")})
            """.trimIndent(),
        )
        val eventosLib = libs.flatMap { l ->
            val obs = Observacao.ler(l["OBSERVACAO"])
            val diferenca = obs.descricaoDiferenca()
            listOfNotNull(
                dataSankhya(l["DHSOLICIT"])?.let {
                    AuditoriaEventoDto(it, "SANKHYA", "liberacao", "Corte pedido: ${obs.produto ?: "item"}", diferenca, nivel = "alerta")
                },
                dataSankhya(l["DHLIB"])?.let {
                    val negado = l["REPROVADO"]?.trim() == "S"
                    AuditoriaEventoDto(
                        it, "SANKHYA", "liberacao",
                        (if (negado) "Corte NEGADO: " else "Corte liberado: ") + (obs.produto ?: "item"),
                        if (negado) "nota volta pra recontagem" else diferenca, l["NOMEUSU"],
                        nivel = if (negado) "erro" else "sucesso",
                    )
                },
            )
        }
        return eventosConf to eventosLib
    }

    private suspend fun buscarNotasGeradas(tenantSlug: String, nunota: Long): List<AuditoriaNotaGeradaDto> =
        SankhyaDbExplorerClient.executarQuery(
            tenantSlug,
            """
            SELECT DISTINCT C.NUNOTA, C.NUMNOTA, C.TIPMOV, T.DESCROPER, C.VLRNOTA, C.DTMOV, C.HRMOV
            FROM TGFVAR V
            JOIN TGFCAB C ON C.NUNOTA = V.NUNOTA
            LEFT JOIN TGFTOP T ON T.CODTIPOPER = C.CODTIPOPER AND T.DHALTER = C.DHTIPOPER
            WHERE V.NUNOTAORIG = $nunota AND V.NUNOTA <> $nunota
            """.trimIndent(),
        ).mapNotNull { r ->
            AuditoriaNotaGeradaDto(
                nunota = r["NUNOTA"].long() ?: return@mapNotNull null,
                numnota = r["NUMNOTA"].long(),
                tipmov = r["TIPMOV"],
                top = r["DESCROPER"],
                valor = r["VLRNOTA"].dbl(),
                geradaEm = dataComHora(r["DTMOV"], r["HRMOV"]),
            )
        }

    /** "Prod.: X, Qtd. total conf.: 1.2563… CX, Qtd. total pedido/nota: 1E+1 CX" (o Sankhya grava redondo em notação científica). */
    private data class Observacao(val produto: String?, val conferido: Double?, val pedido: Double?, val unidade: String?) {
        fun descricaoDiferenca(): String? {
            if (conferido == null || pedido == null) return null
            val dif = conferido - pedido
            val un = unidade?.let { " $it" }.orEmpty()
            return "pedido ${qtd(pedido)}$un · conferido ${qtd(conferido)}$un · " +
                (if (dif > 0) "a maior ${qtd(dif)}$un" else "falta ${qtd(-dif)}$un")
        }

        companion object {
            private val RE = Regex("""Prod\.: (.*), Qtd\. total conf\.: (\S+) (\S+), Qtd\. total pedido/nota: (\S+) (\S+)""")
            fun ler(texto: String?): Observacao {
                val m = texto?.let { RE.find(it) } ?: return Observacao(texto?.removePrefix("Prod.: ")?.substringBefore(","), null, null, null)
                val (produto, conf, unConf, ped, _) = m.destructured
                return Observacao(produto.trim(), conf.toBigDecimalOrNull()?.toDouble(), ped.toBigDecimalOrNull()?.toDouble(), unConf)
            }
        }
    }

    // ─── WMS ─────────────────────────────────────────────────────────────────

    private const val BR = "to_char(%s at time zone 'America/Sao_Paulo', 'YYYY-MM-DD\"T\"HH24:MI:SS')"

    /** Produto aparece como "{prod:123}" e é trocado pelo nome (vindo do Sankhya) depois. */
    private fun eventosWms(tenantId: UUID, nunota: Long): List<AuditoriaEventoDto> = TenantTx.run(tenantId, statementTimeoutMs = 10_000) {
        val eventos = mutableListOf<AuditoriaEventoDto>()
        fun consulta(sql: String, linha: (java.sql.ResultSet) -> AuditoriaEventoDto?) {
            exec(sql) { rs -> while (rs.next()) linha(rs)?.let { eventos += it } }
        }
        val sessoes = "SELECT id FROM app.separacao_sessoes WHERE tenant_id = '$tenantId' AND nunota = $nunota"

        consulta("SELECT ${BR.format("impresso_em")} q, ordem_carga, impresso_por FROM app.mapa_impressoes WHERE tenant_id = '$tenantId' AND nunota = $nunota") { rs ->
            AuditoriaEventoDto(rs.getString("q"), "WMS", "impressao", "Mapa de separação impresso", "OC ${rs.getInt("ordem_carga")}", rs.getString("impresso_por"))
        }
        consulta(
            """
            SELECT ${BR.format("s.criado_em")} q, s.nuconf, s.recontagem, s.conferencia_segmentada, s.status, s.erro,
                   ${BR.format("s.atualizado_em")} fim
            FROM app.separacao_sessoes s WHERE s.tenant_id = '$tenantId' AND s.nunota = $nunota
            """.trimIndent(),
        ) { rs ->
            val tipo = if (rs.getBoolean("recontagem")) "Recontagem aberta" else "Conferência aberta no WMS"
            val detalhe = listOfNotNull(
                rs.getObject("nuconf")?.let { "conferência $it" },
                if (rs.getBoolean("conferencia_segmentada")) "por etapas" else null,
                rs.getString("erro")?.takeIf { it.isNotBlank() }?.let { "erro: $it" },
            ).joinToString(" · ").ifEmpty { null }
            AuditoriaEventoDto(rs.getString("q"), "WMS", "abertura", tipo, detalhe, nivel = if (rs.getBoolean("recontagem")) "alerta" else "info")
        }
        consulta(
            """
            SELECT ${BR.format("h.identificado_em")} q, op.nome operador, est.nome estacao
            FROM app.separacao_operador_historico h
            LEFT JOIN app.users op ON op.id = h.operador_id
            LEFT JOIN app.users est ON est.id = h.estacao_id
            WHERE h.tenant_id = '$tenantId' AND h.sessao_id IN ($sessoes)
            """.trimIndent(),
        ) { rs ->
            AuditoriaEventoDto(rs.getString("q"), "WMS", "operador", "Operador identificado", rs.getString("estacao")?.let { "estação $it" }, rs.getString("operador"))
        }
        consulta(
            """
            SELECT ${BR.format("d.criado_em")} q, d.origem, d.linhas_pedido, d.linhas_carregadas
            FROM app.separacao_diagnosticos d WHERE d.tenant_id = '$tenantId' AND d.nunota = $nunota
            """.trimIndent(),
        ) { rs ->
            val pedido = rs.getInt("linhas_pedido")
            val carregadas = rs.getInt("linhas_carregadas")
            // Só vira evento quando escondeu linha — carga completa é o normal e só poluiria a linha do tempo.
            if (pedido == carregadas) null
            else AuditoriaEventoDto(
                rs.getString("q"), "WMS", "diagnostico", "Itens já conferidos ficaram fora da lista (${rs.getString("origem")})",
                "$carregadas de $pedido linha(s) carregadas", nivel = "alerta",
            )
        }
        // Leituras agrupadas por produto — uma linha por produto, não uma por bipe.
        consulta(
            """
            SELECT ${BR.format("min(l.criado_em)")} q, ${BR.format("max(l.criado_em)")} ate, l.codprod, l.codvol,
                   sum(l.qtd) qtd, sum(l.peso) peso, count(*) n, s.recontagem
            FROM app.separacao_leituras l JOIN app.separacao_sessoes s ON s.id = l.sessao_id
            WHERE l.tenant_id = '$tenantId' AND s.nunota = $nunota
            GROUP BY l.sessao_id, s.recontagem, l.codprod, l.codvol
            """.trimIndent(),
        ) { rs ->
            val n = rs.getInt("n")
            val peso = rs.getBigDecimal("peso")
            val quantidade = rs.getBigDecimal("qtd")
            val detalhe = listOfNotNull(
                if (peso != null && peso.signum() > 0) "${qtd(peso.toDouble())} kg" else "${qtd(quantidade.toDouble())} ${rs.getString("codvol").orEmpty()}".trim(),
                if (n > 1) "$n leituras até ${rs.getString("ate").substring(11)}" else null,
                if (rs.getBoolean("recontagem")) "recontagem" else null,
            ).joinToString(" · ")
            AuditoriaEventoDto(rs.getString("q"), "WMS", "leitura", "Conferiu {prod:${rs.getInt("codprod")}}", detalhe)
        }
        consulta(
            """
            SELECT ${BR.format("e.criado_em")} q, e.numero, e.codprod, e.peso, e.correcao, e.substitui_numero
            FROM app.etiquetas_peso e WHERE e.tenant_id = '$tenantId' AND e.nunota = $nunota
            """.trimIndent(),
        ) { rs ->
            val correcao = rs.getBoolean("correcao")
            AuditoriaEventoDto(
                rs.getString("q"), "WMS", "etiqueta",
                (if (correcao) "Etiqueta de peso corrigida" else "Etiqueta de peso") + " nº ${rs.getLong("numero")}",
                "{prod:${rs.getInt("codprod")}} · ${qtd(rs.getBigDecimal("peso")?.toDouble() ?: 0.0)} kg" +
                    (rs.getObject("substitui_numero")?.let { " · substitui a nº $it" } ?: ""),
            )
        }
        consulta(
            """
            SELECT ${BR.format("e.concluida_em")} q, e.tipo_separacao, e.divergente, e.concluida_por, e.qtd_vol
            FROM app.separacao_etapas e WHERE e.tenant_id = '$tenantId' AND e.status = 'C' AND e.concluida_em IS NOT NULL
              AND e.sessao_id IN ($sessoes)
            """.trimIndent(),
        ) { rs ->
            val divergente = rs.getBoolean("divergente")
            AuditoriaEventoDto(
                rs.getString("q"), "WMS", "etapa",
                "Etapa ${ETAPA[rs.getInt("tipo_separacao")] ?: rs.getInt("tipo_separacao")} concluída" + if (divergente) " com divergência" else "",
                rs.getObject("qtd_vol")?.let { "$it volume(s)" }, rs.getString("concluida_por"),
                nivel = if (divergente) "alerta" else "sucesso",
            )
        }
        consulta(
            """
            SELECT ${BR.format("min(c.checado_em)")} q, ${BR.format("max(c.checado_em)")} ate, c.checado_por, count(*) n
            FROM app.reconferencia_checks c WHERE c.tenant_id = '$tenantId' AND c.sessao_id IN ($sessoes)
            GROUP BY c.checado_por
            """.trimIndent(),
        ) { rs ->
            AuditoriaEventoDto(
                rs.getString("q"), "WMS", "carregamento", "Carregado no veículo",
                "${rs.getInt("n")} item(ns) marcado(s)", rs.getString("checado_por")?.removeSuffix(" (na conferência)"), "sucesso",
            )
        }
        consulta(
            """
            SELECT ${BR.format("criado_em")} q, status_anterior, status_novo, origem, motivo
            FROM app.tarefas_auditoria WHERE tenant_id = '$tenantId' AND nunota = $nunota
            """.trimIndent(),
        ) { rs ->
            AuditoriaEventoDto(
                rs.getString("q"), "WMS", "status",
                "Status: ${statusTarefa(rs.getString("status_anterior"))} → ${statusTarefa(rs.getString("status_novo"))}",
                rs.getString("origem")?.let { if (it == "sync_sankhya") "sincronização com o Sankhya" else it },
            )
        }
        eventos
    }

    private fun statusTarefa(s: String?) = when (s) {
        null -> "—"
        "aguardando" -> "aguardando"
        "andamento" -> "em conferência"
        "aguardando_corte" -> "aguardando corte"
        "aguardando_liberacao" -> "aguardando liberação"
        "concluida", "concluido" -> "concluída"
        else -> s.replace('_', ' ')
    }

    // ─── formatação ──────────────────────────────────────────────────────────

    private fun qtd(v: Double): String =
        java.math.BigDecimal(v).setScale(3, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString().replace('.', ',')

    /** DbExplorer devolve data como "07102026 04:42:29" (já em Brasília) → "2026-10-07T04:42:29". */
    private fun dataSankhya(v: String?): String? {
        val m = v?.trim()?.let { Regex("""^(\d{2})(\d{2})(\d{4})(?: (\d{2}:\d{2}:\d{2}))?$""").find(it) } ?: return null
        val (d, mes, a, hora) = m.destructured
        return "$a-$mes-${d}T${hora.ifEmpty { "00:00:00" }}"
    }

    /** DTMOV (data) + HRMOV (hhmmss como número: 70303 = 07:03:03). */
    private fun dataComHora(data: String?, hora: String?): String? {
        val dia = dataSankhya(data)?.substring(0, 10) ?: return null
        val h = hora?.trim()?.toBigDecimalOrNull()?.toInt()?.toString()?.padStart(6, '0') ?: return "${dia}T00:00:00"
        return "${dia}T${h.substring(0, 2)}:${h.substring(2, 4)}:${h.substring(4, 6)}"
    }

    private fun String?.int() = this?.toBigDecimalOrNull()?.toInt()
    private fun String?.long() = this?.toBigDecimalOrNull()?.toLong()
    private fun String?.dbl() = this?.toBigDecimalOrNull()?.toDouble()
}
