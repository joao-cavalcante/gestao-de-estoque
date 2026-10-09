package wms.backend.auditoria

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import wms.backend.erp.SankhyaDbExplorerClient
import wms.backend.tenancy.TenantTx
import java.util.UUID

/**
 * Auditoria de um pedido — tudo o que aconteceu com ele, do Sankhya e da Torre de Operação, numa linha do tempo
 * só. Só leitura. Cada fonte é independente: a que falhar vira aviso e o resto aparece mesmo assim.
 *
 * QUANTIDADES: o Sankhya (QTDNEG/QTDCONF) e o WMS (leituras) guardam na UNIDADE PADRÃO do produto. A tela mostra
 * na unidade comercial do pedido (FD, CX…) com a padrão entre parênteses — ex. farinha FD = 5 PT: "15 FD (75 PT)".
 * Produto com unidade padrão KG mostra em kg (peso é o que importa).
 */
object AuditoriaPedidoService {

    class PedidoNaoEncontradoException(msg: String) : Exception(msg)

    private val ETAPA = mapOf(1 to "Seco", 2 to "Refrigerado", 3 to "Congelado")
    private val STATUS_CONF = mapOf(
        "A" to "em andamento", "F" to "finalizada", "R" to "enviada para recontagem",
        "C" to "aguardando liberação de corte", "D" to "finalizada com divergência",
    )

    /** Usuário de serviço que a Torre de Operação usa na liberação automática de peso dentro da tolerância. */
    private const val LIBERADOR_AUTOMATICO = "LIBERADOR"

    /**
     * Conversão padrão ↔ unidade de exibição de um produto (TGFVOA da unidade do pedido).
     * Multiplica (M, Q): 1 unidade comercial = Q padrão. Divide (D, Q): 1 unidade comercial = 1/Q padrão.
     */
    private data class Unidades(val comercial: String?, val padrao: String?, val divideMultiplica: String?, val fator: Double?) {
        val emKg get() = padrao?.uppercase() == "KG"
        /** Unidade em que a tela mostra: KG quando o padrão é KG, senão a comercial do pedido. */
        val exibicao get() = if (emKg) padrao else comercial ?: padrao
        /** Vendido numa unidade alternativa com fator cadastrado. */
        private val temFator get() = comercial != null && padrao != null && comercial != padrao && (fator ?: 0.0) > 0.0

        fun deComercialParaPadrao(v: Double): Double = if (!temFator) v else if (divideMultiplica == "D") v / fator!! else v * fator!!
        private fun dePadraoParaComercial(v: Double): Double = if (!temFator) v else if (divideMultiplica == "D") v * fator!! else v / fator!!
        fun dePadraoParaExibicao(v: Double): Double = if (emKg) v else dePadraoParaComercial(v)

        /**
         * [padrao] na unidade padrão do produto → "15 FD (75 PT)" (alternativa) / "42 kg (2 CX)" (padrão KG vendido
         * em CX) / "27,64 kg" / "4 PT".
         */
        fun texto(padrao: Double): String {
            val principal = "${qtd(dePadraoParaExibicao(padrao))} ${rotulo(exibicao)}".trim()
            if (!temFator) return principal
            return if (emKg) "$principal (${qtd(dePadraoParaComercial(padrao))} $comercial)" else "$principal (${qtd(padrao)} ${this.padrao})"
        }

        companion object {
            val NENHUMA = Unidades(null, null, null, null)
        }
    }

    private fun rotulo(un: String?) = if (un?.uppercase() == "KG") "kg" else un.orEmpty()

    suspend fun auditar(tenantSlug: String, tenantId: UUID, nunotaOuNumero: Long): AuditoriaPedidoDto = coroutineScope {
        val avisos = mutableListOf<String>()
        val cab = buscarCabecalho(tenantSlug, nunotaOuNumero)
            ?: throw PedidoNaoEncontradoException("Pedido $nunotaOuNumero não encontrado no Sankhya (nem como número único, nem como número do pedido).")
        val nunota = cab.nunota

        val confsAsync = async { runCatching { buscarConferencias(tenantSlug, nunota) } }
        val notasAsync = async { runCatching { buscarNotasGeradas(tenantSlug, nunota) } }
        val itensRes = runCatching { buscarItens(tenantSlug, nunota, cab.nuconfAtual) }
        val (itens, unidades) = itensRes.onFailure { avisos += "Itens do Sankhya: ${it.message}" }
            .getOrDefault(emptyList<AuditoriaItemDto>() to emptyMap())
        val nomes = itens.associate { it.codprod to it.produto }
        // Liberação só traz a descrição do produto: casa com o item pra converter a unidade (pesável → kg).
        val porNome = itens.associate { normalizarNome(it.produto) to it.codprod }

        val locais = runCatching { withContext(Dispatchers.IO) { eventosWms(tenantId, nunota, unidades) } }
            .onFailure { avisos += "Histórico da Torre de Operação: ${it.message}" }.getOrDefault(emptyList())
            .map { ev ->
                ev.copy(
                    titulo = ev.titulo.replace(PROD) { m -> nomeProduto(m.groupValues[1].toInt(), nomes) },
                    detalhe = ev.detalhe?.replace(PROD) { m -> nomeProduto(m.groupValues[1].toInt(), nomes) },
                )
            }
        val (conferencias, libsBrutas) = confsAsync.await().onFailure { avisos += "Conferências do Sankhya: ${it.message}" }
            .getOrDefault(emptyList<AuditoriaEventoDto>() to emptyList())
        val liberacoes = libsBrutas.flatMap { eventosLiberacao(it, unidades, porNome) }
        val notas = notasAsync.await().onFailure { avisos += "Notas geradas: ${it.message}" }.getOrDefault(emptyList())

        // Conferências que o WMS abriu mas não existem mais no Sankhya (excluídas depois) — antes a linha do tempo só "parava".
        val nuconfsWms = runCatching { withContext(Dispatchers.IO) { nuconfsDoWms(tenantId, nunota) } }.getOrDefault(emptyList())
        val nuconfsSankhya = runCatching {
            SankhyaDbExplorerClient.executarQuery(tenantSlug, "SELECT NUCONF FROM TGFCON2 WHERE NUNOTAORIG = $nunota")
                .mapNotNull { it["NUCONF"].int() }.toSet()
        }.getOrNull()

        val eventos = buildList {
            cab.alteradoEm?.takeIf { alt -> cab.incluidoEm == null || alt > cab.incluidoEm }?.let {
                val zerado = itens.isEmpty()
                add(
                    AuditoriaEventoDto(
                        it, "SANKHYA", "pedido", "Pedido alterado no Sankhya (última alteração)",
                        if (zerado) "pedido hoje SEM ITENS · ${cab.valor?.let(::reais) ?: "R$ 0,00"}"
                        else "pedido hoje com ${itens.size} item(ns)${cab.valor?.let { v -> " · " + reais(v) } ?: ""}",
                        cab.alteradoPor?.let { u -> "por $u" },
                        nivel = if (zerado) "alerta" else "info",
                    ),
                )
            }
            if (nuconfsSankhya != null) {
                nuconfsWms.filter { (nuconf, _) -> nuconf !in nuconfsSankhya }.forEach { (nuconf, quando) ->
                    add(
                        AuditoriaEventoDto(
                            cab.alteradoEm?.takeIf { alt -> alt > quando } ?: quando, "SANKHYA", "conferencia",
                            "Conferência $nuconf (feita no WMS) não existe mais no Sankhya — foi excluída",
                            "excluída no Sankhya depois da conferência; o horário exato não fica registrado",
                            cab.alteradoPor?.let { u -> "última alteração no pedido por $u" },
                            nivel = "alerta",
                        ),
                    )
                }
            }
            cab.incluidoEm?.let {
                add(
                    AuditoriaEventoDto(
                        it, "SANKHYA", "pedido", "Pedido incluído no Sankhya",
                        listOfNotNull(cab.top ?: "TOP ${cab.codtipoper}", "${itens.size} item(ns)", cab.valor?.let(::reais)).joinToString(" · "),
                        cab.vendedor?.let { v -> "vendedor $v" },
                    ),
                )
            }
            addAll(conferencias)
            addAll(liberacoes)
            addAll(locais)
            notas.forEach { n ->
                n.geradaEm?.let {
                    add(
                        AuditoriaEventoDto(
                            it, "SANKHYA", "nota", "Nota ${n.numnota ?: n.nunota} gerada a partir do pedido",
                            listOfNotNull(n.top, "nº único ${n.nunota}", n.valor?.let(::reais)).joinToString(" · "), nivel = "sucesso",
                        ),
                    )
                }
            }
        }.sortedWith(compareBy({ it.quando }, { ORDEM_TIPO.indexOf(it.tipo) }))

        AuditoriaPedidoDto(cab, itens, notas, eventos, avisos)
    }

    private val PROD = Regex("""\{prod:(\d+)\}""")

    /** Desempate de eventos no mesmo segundo — ordem natural do fluxo. */
    private val ORDEM_TIPO = listOf(
        "pedido", "impressao", "abertura", "diagnostico", "operador", "leitura", "etiqueta", "etapa",
        "conferencia", "liberacao", "status", "carregamento", "nota",
    )

    private fun nomeProduto(codprod: Int, nomes: Map<Int, String>) = nomes[codprod]?.let { "$codprod · $it" } ?: "produto $codprod"

    /** "DESCR - COMPL" (itens) e "DESCR, Complem.: COMPL" (liberação) na mesma forma. */
    private fun normalizarNome(n: String) = n.replace(", Complem.: ", " - ").trim().uppercase()

    // ─── Sankhya ─────────────────────────────────────────────────────────────

    private suspend fun buscarCabecalho(tenantSlug: String, numero: Long): AuditoriaCabecalhoDto? {
        val sql = """
            SELECT C.NUNOTA, C.NUMNOTA, C.CODTIPOPER, T.DESCROPER, C.TIPMOV, C.STATUSNOTA, C.ORDEMCARGA, C.CODPARC,
                   P.NOMEPARC, C.CODVEND, V.APELIDO, C.VLRNOTA, C.DTMOV, C.HRMOV, C.DTALTER, C.NUCONFATUAL,
                   (SELECT U.NOMEUSU FROM TSIUSU U WHERE U.CODUSU = C.CODUSU) AS ALTERADO_POR
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
            alteradoPor = linha["ALTERADO_POR"]?.trim()?.takeIf { it.isNotEmpty() },
            nuconfAtual = linha["NUCONFATUAL"].int(),
        )
    }

    /** Itens (na unidade de exibição + padrão) e a conversão de cada produto, usada também nos eventos. */
    private suspend fun buscarItens(tenantSlug: String, nunota: Long, nuconf: Int?): Pair<List<AuditoriaItemDto>, Map<Int, Unidades>> {
        val itens = SankhyaDbExplorerClient.executarQuery(
            tenantSlug,
            """
            SELECT I.SEQUENCIA, I.CODPROD, P.DESCRPROD, P.COMPLDESC, I.CODVOL, P.CODVOL AS CODVOLPAD, I.QTDNEG, I.VLRUNIT, I.VLRTOT,
                   V.DIVIDEMULTIPLICA, V.QUANTIDADE
            FROM TGFITE I
            JOIN TGFPRO P ON P.CODPROD = I.CODPROD
            LEFT JOIN TGFVOA V ON V.CODPROD = I.CODPROD AND V.CODVOL = I.CODVOL AND V.CODVOL <> P.CODVOL AND NVL(V.CONTROLE, ' ') = ' '
            WHERE I.NUNOTA = $nunota ORDER BY P.DESCRPROD, I.SEQUENCIA
            """.trimIndent(),
        )
        val conferido = if (nuconf == null) emptyMap() else SankhyaDbExplorerClient.executarQuery(
            tenantSlug, "SELECT CODPROD, SUM(QTDCONFVOLPAD) AS QTD FROM TGFCOI2 WHERE NUCONF = $nuconf GROUP BY CODPROD",
        ).mapNotNull { r -> r["CODPROD"].int()?.let { it to (r["QTD"].dbl() ?: 0.0) } }.toMap()

        val unidades = mutableMapOf<Int, Unidades>()
        val lista = itens.mapNotNull { r ->
            val codprod = r["CODPROD"].int() ?: return@mapNotNull null
            val un = Unidades(r["CODVOL"]?.trim(), r["CODVOLPAD"]?.trim(), r["DIVIDEMULTIPLICA"]?.trim(), r["QUANTIDADE"].dbl())
            unidades.putIfAbsent(codprod, un)
            val compl = r["COMPLDESC"]?.trim()?.takeIf { it.isNotEmpty() }
            val pedidoPadrao = r["QTDNEG"].dbl()
            val conferidoPadrao = conferido[codprod]
            val mostraPadrao = un.exibicao != un.padrao
            AuditoriaItemDto(
                sequencia = r["SEQUENCIA"].int() ?: 0,
                codprod = codprod,
                produto = listOfNotNull(r["DESCRPROD"]?.trim(), compl).joinToString(" - "),
                unidade = rotulo(un.exibicao),
                qtdNegociada = pedidoPadrao?.let(un::dePadraoParaExibicao),
                qtdConferida = conferidoPadrao?.let(un::dePadraoParaExibicao),
                unidadePadrao = un.padrao.takeIf { mostraPadrao },
                qtdNegociadaPadrao = pedidoPadrao.takeIf { mostraPadrao },
                qtdConferidaPadrao = conferidoPadrao.takeIf { mostraPadrao },
                valorUnitario = r["VLRUNIT"].dbl(),
                valorTotal = r["VLRTOT"].dbl(),
            )
        }
        return lista to unidades
    }

    /** Linha crua de liberação (TSILIB) — o texto é montado depois, com a conversão do produto. */
    private data class LiberacaoBruta(val solicitada: String?, val decidida: String?, val negada: Boolean, val liberador: String?, val observacao: String?)

    /** Conferências (início/fim) + liberações de corte cruas — lado do Sankhya. */
    private suspend fun buscarConferencias(tenantSlug: String, nunota: Long): Pair<List<AuditoriaEventoDto>, List<LiberacaoBruta>> {
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
                dataSankhya(c["DHINICONF"])?.let { AuditoriaEventoDto(it, "SANKHYA", "conferencia", "Conferência nº $nuconf aberta no Sankhya") },
                dataSankhya(c["DHFINCONF"])?.let {
                    AuditoriaEventoDto(
                        it, "SANKHYA", "conferencia", "Conferência nº $nuconf ${STATUS_CONF[status] ?: "com status $status"} no Sankhya",
                        c["QTDVOL"].int()?.let { v -> "$v volume(s) informado(s)" },
                        c["NOMEUSU"]?.let { u -> "conferente $u" },
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
            SELECT L.DHSOLICIT, L.DHLIB, L.REPROVADO, L.OBSERVACAO, U.NOMEUSU
            FROM TSILIB L LEFT JOIN TSIUSU U ON U.CODUSU = L.CODUSULIB
            WHERE L.EVENTO = 64 AND L.TABELA = 'TGFCOI2' AND L.NUCHAVE IN (${nuconfs.joinToString(",")})
            """.trimIndent(),
        ).map { l ->
            LiberacaoBruta(dataSankhya(l["DHSOLICIT"]), dataSankhya(l["DHLIB"]), l["REPROVADO"]?.trim() == "S", l["NOMEUSU"], l["OBSERVACAO"])
        }
        return eventosConf to libs
    }

    /**
     * A observação vem na unidade do pedido ("1,52 CX de 2 CX"). Pesável (padrão KG) vira kg; o resto fica na
     * comercial com a padrão entre parênteses.
     */
    private fun eventosLiberacao(l: LiberacaoBruta, unidades: Map<Int, Unidades>, porNome: Map<String, Int>): List<AuditoriaEventoDto> {
        val obs = Observacao.ler(l.observacao)
        val produto = obs.produto?.replace(", Complem.: ", " - ") ?: "item"
        val un = obs.produto?.let { porNome[normalizarNome(it)] }?.let { unidades[it] } ?: Unidades.NENHUMA
        val diferenca = if (obs.conferido == null || obs.pedido == null) null else {
            val ped = un.deComercialParaPadrao(obs.pedido)
            val conf = un.deComercialParaPadrao(obs.conferido)
            val semConversao = un === Unidades.NENHUMA
            fun t(v: Double) = if (semConversao) "${qtd(v)} ${obs.unidade.orEmpty()}".trim() else un.texto(v)
            val dif = conf - ped
            "pedido ${t(ped)} · conferido ${t(conf)} · " + if (dif > 0) "a maior ${t(dif)}" else "faltou ${t(-dif)}"
        }
        val automatico = l.liberador?.trim()?.uppercase() == LIBERADOR_AUTOMATICO
        return listOfNotNull(
            l.solicitada?.let { AuditoriaEventoDto(it, "SANKHYA", "liberacao", "Liberação de corte solicitada: $produto", diferenca, nivel = "alerta") },
            l.decidida?.let {
                AuditoriaEventoDto(
                    it, "SANKHYA", "liberacao",
                    if (l.negada) "Corte NEGADO: $produto" else "Corte liberado: $produto",
                    when {
                        l.negada -> "a nota volta para recontagem deste item"
                        automatico -> listOfNotNull("automático — peso dentro da tolerância", diferenca).joinToString(" · ")
                        else -> diferenca
                    },
                    when {
                        automatico -> "Torre de Operação (automático)"
                        else -> l.liberador?.let { u -> "liberador $u" }
                    },
                    nivel = if (l.negada) "erro" else "sucesso",
                )
            },
        )
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
        companion object {
            private val RE = Regex("""Prod\.: (.*), Qtd\. total conf\.: (\S+) (\S+), Qtd\. total pedido/nota: (\S+) (\S+)""")
            fun ler(texto: String?): Observacao {
                val m = texto?.let { RE.find(it) } ?: return Observacao(texto?.removePrefix("Prod.: ")?.substringBefore(", Qtd"), null, null, null)
                val (produto, conf, unConf, ped, _) = m.destructured
                return Observacao(produto.trim(), conf.toBigDecimalOrNull()?.toDouble(), ped.toBigDecimalOrNull()?.toDouble(), unConf)
            }
        }
    }

    // ─── Torre de Operação (banco do WMS) ────────────────────────────────────

    private const val BR = "to_char(%s at time zone 'America/Sao_Paulo', 'YYYY-MM-DD\"T\"HH24:MI:SS')"

    /** Produto aparece como "{prod:123}" e é trocado pelo nome (vindo do Sankhya) depois. */
    private fun eventosWms(tenantId: UUID, nunota: Long, unidades: Map<Int, Unidades>): List<AuditoriaEventoDto> = TenantTx.run(tenantId, statementTimeoutMs = 10_000) {
        val eventos = mutableListOf<AuditoriaEventoDto>()
        fun consulta(sql: String, linha: (java.sql.ResultSet) -> AuditoriaEventoDto?) {
            exec(sql) { rs -> while (rs.next()) linha(rs)?.let { eventos += it } }
        }
        val sessoes = "SELECT id FROM app.separacao_sessoes WHERE tenant_id = '$tenantId' AND nunota = $nunota"

        consulta("SELECT ${BR.format("impresso_em")} q, ordem_carga, impresso_por FROM app.mapa_impressoes WHERE tenant_id = '$tenantId' AND nunota = $nunota") { rs ->
            val oc = rs.getObject("ordem_carga")
            AuditoriaEventoDto(
                rs.getString("q"), "WMS", "impressao",
                if (oc != null) "Mapa de separação da OC $oc impresso" else "Mapa de separação impresso",
                null, rs.getString("impresso_por")?.let { "impresso por $it" },
            )
        }
        consulta(
            """
            SELECT ${BR.format("s.criado_em")} q, s.nuconf, s.recontagem, s.conferencia_segmentada, s.erro
            FROM app.separacao_sessoes s WHERE s.tenant_id = '$tenantId' AND s.nunota = $nunota
            """.trimIndent(),
        ) { rs ->
            val recontagem = rs.getBoolean("recontagem")
            val detalhe = listOfNotNull(
                rs.getObject("nuconf")?.let { "conferência nº $it" },
                if (rs.getBoolean("conferencia_segmentada")) "separada por etapas (seco / refrigerado / congelado)" else null,
                rs.getString("erro")?.takeIf { it.isNotBlank() }?.let { "erro: $it" },
            ).joinToString(" · ").ifEmpty { null }
            AuditoriaEventoDto(
                rs.getString("q"), "WMS", "abertura",
                if (recontagem) "Recontagem iniciada na Torre de Operação" else "Conferência iniciada na Torre de Operação",
                detalhe, nivel = if (recontagem) "alerta" else "info",
            )
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
            AuditoriaEventoDto(
                rs.getString("q"), "WMS", "operador", "Operador assumiu a conferência",
                rs.getString("estacao")?.let { "na estação $it" }, rs.getString("operador"),
            )
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
                "$carregadas de $pedido linha(s) do pedido carregadas na tela", nivel = "alerta",
            )
        }
        // Leituras agrupadas por produto — uma linha por produto, não uma por bipe. Quantidade da leitura está na
        // unidade padrão do produto; mostra na comercial do pedido (ou kg, pesável).
        consulta(
            """
            SELECT ${BR.format("min(l.criado_em)")} q, ${BR.format("max(l.criado_em)")} ate, l.codprod,
                   sum(l.qtd) qtd, sum(l.peso) peso, count(*) n, s.recontagem
            FROM app.separacao_leituras l JOIN app.separacao_sessoes s ON s.id = l.sessao_id
            WHERE l.tenant_id = '$tenantId' AND s.nunota = $nunota
            GROUP BY l.sessao_id, s.recontagem, l.codprod
            """.trimIndent(),
        ) { rs ->
            val n = rs.getInt("n")
            val peso = rs.getBigDecimal("peso")?.toDouble() ?: 0.0
            val quantidade = rs.getBigDecimal("qtd")?.toDouble() ?: 0.0
            val un = unidades[rs.getInt("codprod")] ?: Unidades.NENHUMA
            val detalhe = listOfNotNull(
                if (peso > 0) "pesado ${qtd(peso)} kg" else "conferido ${un.texto(quantidade)}",
                if (n > 1) "$n leituras, a última às ${rs.getString("ate").substring(11, 16)}" else null,
                if (rs.getBoolean("recontagem")) "na recontagem" else null,
            ).joinToString(" · ")
            AuditoriaEventoDto(rs.getString("q"), "WMS", "leitura", "Conferido: {prod:${rs.getInt("codprod")}}", detalhe)
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
                (if (correcao) "Etiqueta de peso corrigida" else "Etiqueta de peso emitida") + " — nº ${rs.getLong("numero")}",
                "{prod:${rs.getInt("codprod")}} · ${qtd(rs.getBigDecimal("peso")?.toDouble() ?: 0.0)} kg" +
                    (rs.getObject("substitui_numero")?.let { " · substitui a etiqueta nº $it" } ?: ""),
            )
        }
        consulta(
            """
            SELECT ${BR.format("e.concluida_em")} q, e.tipo_separacao, e.divergente, e.concluida_por, e.qtd_vol,
                   (SELECT count(*) FROM app.separacao_itens i WHERE i.sessao_id = e.sessao_id AND i.tipo_separacao = e.tipo_separacao
                      AND NOT i.silencioso) AS itens_total,
                   (SELECT count(*) FROM app.separacao_itens i WHERE i.sessao_id = e.sessao_id AND i.tipo_separacao = e.tipo_separacao
                      AND NOT i.silencioso AND i.qtd_conferida_local > 0) AS itens_conferidos
            FROM app.separacao_etapas e WHERE e.tenant_id = '$tenantId' AND e.status = 'C' AND e.concluida_em IS NOT NULL
              AND e.sessao_id IN ($sessoes)
            """.trimIndent(),
        ) { rs ->
            val divergente = rs.getBoolean("divergente")
            val total = rs.getInt("itens_total")
            val conferidos = rs.getInt("itens_conferidos")
            // Deixa explícito o que a divergência foi — "com divergência" sozinho era dúbio (pedido 65777: 0 conferidos).
            val resumo = when {
                total == 0 -> null
                conferidos == 0 -> "nenhum item conferido — corte de todos os itens da etapa"
                else -> "$conferidos de $total item(ns) conferido(s)"
            }
            AuditoriaEventoDto(
                rs.getString("q"), "WMS", "etapa",
                "Etapa ${ETAPA[rs.getInt("tipo_separacao")] ?: rs.getInt("tipo_separacao")} concluída" + if (divergente) " com divergência" else " sem divergência",
                listOfNotNull(resumo, rs.getObject("qtd_vol")?.let { "$it volume(s)" }).joinToString(" · ").ifEmpty { null },
                rs.getString("concluida_por"),
                nivel = if (total > 0 && conferidos == 0) "erro" else if (divergente) "alerta" else "sucesso",
            )
        }
        consulta(
            """
            SELECT ${BR.format("min(c.checado_em)")} q, c.checado_por, count(*) n
            FROM app.reconferencia_checks c WHERE c.tenant_id = '$tenantId' AND c.sessao_id IN ($sessoes)
            GROUP BY c.checado_por
            """.trimIndent(),
        ) { rs ->
            val por = rs.getString("checado_por")
            val naConferencia = por?.endsWith("(na conferência)") == true
            AuditoriaEventoDto(
                rs.getString("q"), "WMS", "carregamento", "Carregado no veículo",
                "${rs.getInt("n")} item(ns) marcado(s) como carregado(s)" + if (naConferencia) " ao concluir a etapa" else "",
                por?.removeSuffix(" (na conferência)"), "sucesso",
            )
        }
        consulta(
            """
            SELECT ${BR.format("criado_em")} q, status_anterior, status_novo, origem
            FROM app.tarefas_auditoria WHERE tenant_id = '$tenantId' AND nunota = $nunota
            """.trimIndent(),
        ) { rs ->
            AuditoriaEventoDto(
                rs.getString("q"), "WMS", "status",
                "Situação na fila: ${statusTarefa(rs.getString("status_anterior"))} → ${statusTarefa(rs.getString("status_novo"))}",
                rs.getString("origem")?.let { if (it == "sync_sankhya") "atualizado pela sincronização com o Sankhya" else it },
            )
        }
        eventos
    }

    /** (nuconf, última atualização da sessão) das conferências que o WMS abriu pro pedido. */
    private fun nuconfsDoWms(tenantId: UUID, nunota: Long): List<Pair<Int, String>> = TenantTx.run(tenantId, statementTimeoutMs = 10_000) {
        val r = mutableListOf<Pair<Int, String>>()
        exec(
            "SELECT DISTINCT ON (nuconf) nuconf, ${BR.format("atualizado_em")} q FROM app.separacao_sessoes " +
                "WHERE tenant_id = '$tenantId' AND nunota = $nunota AND nuconf IS NOT NULL ORDER BY nuconf, atualizado_em DESC",
        ) { rs -> while (rs.next()) r += rs.getInt("nuconf") to rs.getString("q") }
        r
    }

    private fun statusTarefa(s: String?) = when (s) {
        null -> "—"
        "aguardando" -> "aguardando conferência"
        "andamento" -> "em conferência"
        "aguardando_corte" -> "aguardando liberação de corte"
        "aguardando_liberacao" -> "aguardando liberação"
        "concluida", "concluido" -> "concluída"
        else -> s.replace('_', ' ')
    }

    // ─── formatação ──────────────────────────────────────────────────────────

    private fun qtd(v: Double): String =
        java.math.BigDecimal(v).setScale(3, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString().replace('.', ',')

    private fun reais(v: Double): String =
        java.text.NumberFormat.getCurrencyInstance(java.util.Locale.forLanguageTag("pt-BR")).format(v)

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
