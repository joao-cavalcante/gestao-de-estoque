package wms.backend.galpao

import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greater
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.batchInsert
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import wms.backend.produtos.ProdutosCacheTable
import wms.backend.tenancy.TenantTx
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

sealed class ResultadoPublicacao {
    data class Ok(val versao: VersaoResumoDto, val enderecosAtivos: Int, val enderecosDesativados: Int) : ResultadoPublicacao()
    object NaoEncontrada : ResultadoPublicacao()
    object NaoERascunho : ResultadoPublicacao()
    object ConfirmacaoInvalida : ResultadoPublicacao()
    data class Invalida(val erros: List<String>) : ResultadoPublicacao()
    data class EnderecoComSaldo(val codigos: List<String>) : ResultadoPublicacao()
}

object GalpaoRepository {

    /** Publicar grava até 100 mil endereços. */
    private const val TIMEOUT_PUBLICACAO_MS = 120_000

    // ─── Plantas ────────────────────────────────────────────────────────────

    fun listarPlantas(tenantId: UUID): List<PlantaDto> = TenantTx.run(tenantId) {
        GalpaoPlantasTable.selectAll()
            .where { (GalpaoPlantasTable.tenantId eq tenantId) and (GalpaoPlantasTable.ativo eq true) }
            .orderBy(GalpaoPlantasTable.nome to SortOrder.ASC)
            .map { plantaDto(it) }
    }

    fun buscarPlanta(tenantId: UUID, id: UUID): PlantaDto? = TenantTx.run(tenantId) {
        linhaPlanta(tenantId, id)?.let { plantaDto(it) }
    }

    fun nomeEmUso(tenantId: UUID, nome: String, exceto: UUID?): Boolean = TenantTx.run(tenantId) {
        GalpaoPlantasTable.selectAll()
            .where { (GalpaoPlantasTable.tenantId eq tenantId) and (GalpaoPlantasTable.nome eq nome) }
            .any { it[GalpaoPlantasTable.id] != exceto }
    }

    fun criarPlanta(tenantId: UUID, r: SalvarPlantaRequest): PlantaDto = TenantTx.run(tenantId) {
        val id = UUID.randomUUID()
        val agora = Instant.now()
        GalpaoPlantasTable.insert {
            it[GalpaoPlantasTable.id] = id
            it[GalpaoPlantasTable.tenantId] = tenantId
            it[nome] = r.nome.trim()
            it[codemp] = r.codemp
            it[codlocal] = r.codlocal
            it[largura] = r.largura.toBigDecimal()
            it[profundidade] = r.profundidade.toBigDecimal()
            it[ativo] = true
            it[criadoEm] = agora
            it[atualizadoEm] = agora
        }
        plantaDto(linhaPlanta(tenantId, id)!!)
    }

    fun atualizarPlanta(tenantId: UUID, id: UUID, r: SalvarPlantaRequest): PlantaDto? = TenantTx.run(tenantId) {
        val n = GalpaoPlantasTable.update({ (GalpaoPlantasTable.tenantId eq tenantId) and (GalpaoPlantasTable.id eq id) }) {
            it[nome] = r.nome.trim()
            it[codemp] = r.codemp
            it[codlocal] = r.codlocal
            it[largura] = r.largura.toBigDecimal()
            it[profundidade] = r.profundidade.toBigDecimal()
            it[atualizadoEm] = Instant.now()
        }
        if (n == 0) null else plantaDto(linhaPlanta(tenantId, id)!!)
    }

    // ─── Versões do desenho ─────────────────────────────────────────────────

    fun listarVersoes(tenantId: UUID, plantaId: UUID): List<VersaoResumoDto> = TenantTx.run(tenantId) {
        GalpaoVersoesTable.selectAll()
            .where { (GalpaoVersoesTable.tenantId eq tenantId) and (GalpaoVersoesTable.plantaId eq plantaId) }
            .orderBy(GalpaoVersoesTable.numero to SortOrder.DESC)
            .map { versaoResumo(it) }
    }

    /**
     * Desenho pra abrir no editor: a versão pedida; sem pedido, o rascunho aberto e, sem rascunho, a publicada.
     * null = planta ainda sem desenho (o editor começa vazio, com as medidas da planta).
     */
    fun versao(tenantId: UUID, plantaId: UUID, versaoId: UUID?): VersaoDto? = TenantTx.run(tenantId) {
        val linhas = GalpaoVersoesTable.selectAll()
            .where { (GalpaoVersoesTable.tenantId eq tenantId) and (GalpaoVersoesTable.plantaId eq plantaId) }
            .orderBy(GalpaoVersoesTable.numero to SortOrder.DESC)
            .toList()
        val linha = when {
            versaoId != null -> linhas.firstOrNull { it[GalpaoVersoesTable.id] == versaoId }
            else -> linhas.firstOrNull { it[GalpaoVersoesTable.status] == StatusVersao.RASCUNHO }
                ?: linhas.firstOrNull { it[GalpaoVersoesTable.status] == StatusVersao.PUBLICADA }
        } ?: return@run null
        VersaoDto(versaoResumo(linha), GeometriaJson.ler(linha[GalpaoVersoesTable.geometria]))
    }

    /** Um rascunho aberto por planta: salvar de novo sobrescreve o rascunho; depois de publicado, abre o próximo número. */
    fun salvarRascunho(tenantId: UUID, plantaId: UUID, g: GeometriaPlanta, observacao: String?, nomeUsuario: String): VersaoResumoDto? =
        TenantTx.run(tenantId) {
            travarPlanta(plantaId) ?: return@run null
            val texto = GeometriaJson.escrever(g)
            val hash = GeometriaJson.hash(g)
            val rascunho = GalpaoVersoesTable.selectAll()
                .where { (GalpaoVersoesTable.plantaId eq plantaId) and (GalpaoVersoesTable.status eq StatusVersao.RASCUNHO) }
                .singleOrNull()
            val id = if (rascunho != null) {
                val id = rascunho[GalpaoVersoesTable.id]
                GalpaoVersoesTable.update({ GalpaoVersoesTable.id eq id }) {
                    it[geometria] = texto
                    it[GalpaoVersoesTable.hash] = hash
                    it[GalpaoVersoesTable.observacao] = observacao?.trim()?.ifBlank { null }
                    it[criadoPorNome] = nomeUsuario
                    it[criadoEm] = Instant.now()
                }
                id
            } else {
                val proximo = (GalpaoVersoesTable.selectAll().where { GalpaoVersoesTable.plantaId eq plantaId }
                    .maxOfOrNull { it[GalpaoVersoesTable.numero] } ?: 0) + 1
                val id = UUID.randomUUID()
                GalpaoVersoesTable.insert {
                    it[GalpaoVersoesTable.id] = id
                    it[GalpaoVersoesTable.tenantId] = tenantId
                    it[GalpaoVersoesTable.plantaId] = plantaId
                    it[numero] = proximo
                    it[status] = StatusVersao.RASCUNHO
                    it[geometria] = texto
                    it[GalpaoVersoesTable.hash] = hash
                    it[GalpaoVersoesTable.observacao] = observacao?.trim()?.ifBlank { null }
                    it[criadoPorNome] = nomeUsuario
                    it[criadoEm] = Instant.now()
                }
                id
            }
            versaoResumo(GalpaoVersoesTable.selectAll().where { GalpaoVersoesTable.id eq id }.single())
        }

    /** Copia uma versão antiga pro rascunho (o rascunho aberto, se houver, é substituído). Publicar continua manual. */
    fun restaurarComoRascunho(tenantId: UUID, plantaId: UUID, versaoId: UUID, nomeUsuario: String): VersaoResumoDto? {
        val origem = versao(tenantId, plantaId, versaoId) ?: return null
        return salvarRascunho(tenantId, plantaId, origem.geometria, "Restaurada da versão ${origem.versao.numero}", nomeUsuario)
    }

    /**
     * Publica o rascunho: valida de novo, projeta os endereços (mantém o id dos que continuam — o saldo aponta
     * pra eles), desativa os que saíram do desenho (bloqueia se algum tiver saldo) e arquiva a publicada anterior.
     * Tudo numa transação, com a planta travada.
     */
    fun publicar(tenantId: UUID, plantaId: UUID, versaoId: UUID, confirmacao: String, nomeUsuario: String): ResultadoPublicacao =
        TenantTx.run(tenantId, TIMEOUT_PUBLICACAO_MS) {
            val planta = travarPlanta(plantaId) ?: return@run ResultadoPublicacao.NaoEncontrada
            if (confirmacao.trim() != planta[GalpaoPlantasTable.nome]) return@run ResultadoPublicacao.ConfirmacaoInvalida
            val versao = GalpaoVersoesTable.selectAll()
                .where { (GalpaoVersoesTable.id eq versaoId) and (GalpaoVersoesTable.plantaId eq plantaId) }
                .singleOrNull() ?: return@run ResultadoPublicacao.NaoEncontrada
            if (versao[GalpaoVersoesTable.status] != StatusVersao.RASCUNHO) return@run ResultadoPublicacao.NaoERascunho

            val g = GeometriaJson.ler(versao[GalpaoVersoesTable.geometria])
            val erros = ValidadorGeometria.validar(g)
            if (erros.isNotEmpty()) return@run ResultadoPublicacao.Invalida(erros)

            val gerados = GeradorEnderecos.gerar(g)
            val novos = gerados.associateBy { it.codigo }
            val existentes = GalpaoEnderecosTable.selectAll()
                .where { GalpaoEnderecosTable.plantaId eq plantaId }
                .associate { it[GalpaoEnderecosTable.codigo] to it[GalpaoEnderecosTable.id] }

            val saindo = existentes.filterKeys { it !in novos }
            if (saindo.isNotEmpty()) {
                val comSaldo = GalpaoSaldosEnderecoTable.selectAll()
                    .where { (GalpaoSaldosEnderecoTable.enderecoId inList saindo.values) and (GalpaoSaldosEnderecoTable.quantidade greater BigDecimal.ZERO) }
                    .map { it[GalpaoSaldosEnderecoTable.enderecoId] }
                    .toSet()
                if (comSaldo.isNotEmpty()) {
                    return@run ResultadoPublicacao.EnderecoComSaldo(saindo.filterValues { it in comSaldo }.keys.sorted())
                }
            }

            val agora = Instant.now()
            gerados.filter { it.codigo in existentes }.forEach { e ->
                GalpaoEnderecosTable.update({ GalpaoEnderecosTable.id eq existentes.getValue(e.codigo) }) {
                    preencher(it, e)
                    it[ativo] = true
                    it[atualizadoEm] = agora
                }
            }
            GalpaoEnderecosTable.batchInsert(gerados.filter { it.codigo !in existentes }, shouldReturnGeneratedValues = false) { e ->
                this[GalpaoEnderecosTable.id] = UUID.randomUUID()
                this[GalpaoEnderecosTable.tenantId] = tenantId
                this[GalpaoEnderecosTable.plantaId] = plantaId
                this[GalpaoEnderecosTable.codigo] = e.codigo
                this[GalpaoEnderecosTable.setor] = e.setor
                this[GalpaoEnderecosTable.estrutura] = e.estrutura
                this[GalpaoEnderecosTable.modulo] = e.modulo
                this[GalpaoEnderecosTable.nivel] = e.nivel
                this[GalpaoEnderecosTable.tipo] = e.tipo
                this[GalpaoEnderecosTable.x] = dec(e.x)
                this[GalpaoEnderecosTable.y] = dec(e.y)
                this[GalpaoEnderecosTable.z] = dec(e.z)
                this[GalpaoEnderecosTable.rotacao] = dec(e.rotacao)
                this[GalpaoEnderecosTable.largura] = dec(e.largura)
                this[GalpaoEnderecosTable.comprimento] = dec(e.comprimento)
                this[GalpaoEnderecosTable.altura] = dec(e.altura)
                this[GalpaoEnderecosTable.capQtd] = e.capQtd?.toBigDecimal()
                this[GalpaoEnderecosTable.capPeso] = e.capPeso?.toBigDecimal()
                this[GalpaoEnderecosTable.capVolume] = e.capVolume?.toBigDecimal()
                this[GalpaoEnderecosTable.bloqueado] = false
                this[GalpaoEnderecosTable.ativo] = true
                this[GalpaoEnderecosTable.atualizadoEm] = agora
            }
            if (saindo.isNotEmpty()) {
                GalpaoEnderecosTable.update({ GalpaoEnderecosTable.id inList saindo.values }) {
                    it[ativo] = false
                    it[atualizadoEm] = agora
                }
            }

            GalpaoVersoesTable.update({ (GalpaoVersoesTable.plantaId eq plantaId) and (GalpaoVersoesTable.status eq StatusVersao.PUBLICADA) }) {
                it[status] = StatusVersao.ARQUIVADA
            }
            GalpaoVersoesTable.update({ GalpaoVersoesTable.id eq versaoId }) {
                it[status] = StatusVersao.PUBLICADA
                it[publicadoPorNome] = nomeUsuario
                it[publicadoEm] = agora
            }
            GalpaoPlantasTable.update({ GalpaoPlantasTable.id eq plantaId }) {
                it[versaoPublicadaId] = versaoId
                it[largura] = g.largura.toBigDecimal()
                it[profundidade] = g.profundidade.toBigDecimal()
                it[atualizadoEm] = agora
            }
            ResultadoPublicacao.Ok(
                versao = versaoResumo(GalpaoVersoesTable.selectAll().where { GalpaoVersoesTable.id eq versaoId }.single()),
                enderecosAtivos = gerados.size,
                enderecosDesativados = saindo.size,
            )
        }

    // ─── Visão (3D / tabela de endereços) ───────────────────────────────────

    fun visao(tenantId: UUID, plantaId: UUID, fisicos: Map<Int, FisicoProduto> = emptyMap()): VisaoPlantaDto? = TenantTx.run(tenantId, 30_000) {
        val planta = linhaPlanta(tenantId, plantaId) ?: return@run null
        val geometria = planta[GalpaoPlantasTable.versaoPublicadaId]?.let { vid ->
            GalpaoVersoesTable.selectAll().where { GalpaoVersoesTable.id eq vid }.singleOrNull()
                ?.let { GeometriaJson.ler(it[GalpaoVersoesTable.geometria]) }
        }
        val enderecos = GalpaoEnderecosTable.selectAll()
            .where { (GalpaoEnderecosTable.plantaId eq plantaId) and (GalpaoEnderecosTable.ativo eq true) }
            .orderBy(GalpaoEnderecosTable.codigo to SortOrder.ASC)
            .toList()
        val ids = enderecos.map { it[GalpaoEnderecosTable.id] }
        val saldos = if (ids.isEmpty()) emptyMap() else GalpaoSaldosEnderecoTable.selectAll()
            .where { (GalpaoSaldosEnderecoTable.enderecoId inList ids) and (GalpaoSaldosEnderecoTable.quantidade greater BigDecimal.ZERO) }
            .groupBy { it[GalpaoSaldosEnderecoTable.enderecoId] }
        val codprods = saldos.values.flatten().map { it[GalpaoSaldosEnderecoTable.codprod] }.distinct()
        val nomes = nomesProdutos(tenantId, codprods)
        val pendentes = GalpaoContagensTable.selectAll()
            .where { (GalpaoContagensTable.plantaId eq plantaId) and (GalpaoContagensTable.status eq StatusContagem.PENDENTE) }
            .groupingBy { it[GalpaoContagensTable.enderecoId] }.eachCount()
        val ultimoMovimento = ultimoMovimentoPorEndereco(plantaId)
        val (produtosEmSeparacao, documentosEmSeparacao) = emSeparacao()

        VisaoPlantaDto(
            planta = plantaDto(planta),
            geometria = geometria,
            enderecos = enderecos.map { e ->
                val itens = saldos[e[GalpaoEnderecosTable.id]].orEmpty().map { s ->
                    val cod = s[GalpaoSaldosEnderecoTable.codprod]
                    SaldoEnderecoDto(cod, nomes[cod] ?: "Produto $cod", s[GalpaoSaldosEnderecoTable.controle], s[GalpaoSaldosEnderecoTable.quantidade].toDouble(), s[GalpaoSaldosEnderecoTable.versao])
                }.sortedBy { it.produto }
                val qtd = itens.sumOf { it.quantidade }
                val cap = e[GalpaoEnderecosTable.capQtd]?.toDouble()?.takeIf { it > 0 }
                // Mesma regra da movimentação: maior razão entre quantidade, peso e volume.
                val ocup = CapacidadeEndereco.ocupacao(
                    Capacidade(cap, e[GalpaoEnderecosTable.capPeso]?.toDouble(), e[GalpaoEnderecosTable.capVolume]?.toDouble()),
                    itens.map { ItemNoEndereco(it.codprod, it.quantidade) },
                    fisicos,
                )
                EnderecoDto(
                    id = e[GalpaoEnderecosTable.id].toString(),
                    codigo = e[GalpaoEnderecosTable.codigo],
                    setor = e[GalpaoEnderecosTable.setor],
                    estrutura = e[GalpaoEnderecosTable.estrutura],
                    modulo = e[GalpaoEnderecosTable.modulo],
                    nivel = e[GalpaoEnderecosTable.nivel],
                    tipo = e[GalpaoEnderecosTable.tipo],
                    x = e[GalpaoEnderecosTable.x].toDouble(),
                    y = e[GalpaoEnderecosTable.y].toDouble(),
                    z = e[GalpaoEnderecosTable.z].toDouble(),
                    rotacao = e[GalpaoEnderecosTable.rotacao].toDouble(),
                    largura = e[GalpaoEnderecosTable.largura].toDouble(),
                    comprimento = e[GalpaoEnderecosTable.comprimento].toDouble(),
                    altura = e[GalpaoEnderecosTable.altura].toDouble(),
                    capQtd = cap,
                    capPeso = e[GalpaoEnderecosTable.capPeso]?.toDouble(),
                    capVolume = e[GalpaoEnderecosTable.capVolume]?.toDouble(),
                    bloqueado = e[GalpaoEnderecosTable.bloqueado],
                    quantidade = qtd,
                    ocupacao = ocup.ocupacao,
                    baseOcupacao = ocup.base,
                    saldos = itens,
                    contagensPendentes = pendentes[e[GalpaoEnderecosTable.id]] ?: 0,
                    ocupacaoQtd = ocup.razoes["QUANTIDADE"],
                    ocupacaoPeso = ocup.razoes["PESO"],
                    ocupacaoVolume = ocup.razoes["VOLUME"],
                    pesoKg = ocup.peso,
                    volumeM3 = ocup.volume,
                    ultimoMovimentoEm = ultimoMovimento[e[GalpaoEnderecosTable.id]],
                    emSeparacao = itens.any { it.codprod in produtosEmSeparacao },
                )
            },
            documentosEmSeparacao = documentosEmSeparacao,
            geradoEm = Instant.now().toString(),
        )
    }

    // ─── Auxiliares ─────────────────────────────────────────────────────────

    /** Último movimento físico (sem bloqueio/desbloqueio) por endereço — origem ou destino. */
    private fun Transaction.ultimoMovimentoPorEndereco(plantaId: UUID): Map<UUID, String> {
        val r = mutableMapOf<UUID, String>()
        exec(
            "SELECT e, max(criado_em) FROM (" +
                "SELECT endereco_origem_id AS e, criado_em FROM app.galpao_movimentos WHERE planta_id = '$plantaId' AND tipo NOT IN ('BLOQUEAR','DESBLOQUEAR') " +
                "UNION ALL SELECT endereco_destino_id, criado_em FROM app.galpao_movimentos WHERE planta_id = '$plantaId' AND tipo NOT IN ('BLOQUEAR','DESBLOQUEAR')" +
                ") m WHERE e IS NOT NULL GROUP BY e",
        ) { rs ->
            while (rs.next()) r[UUID.fromString(rs.getString(1))] = rs.getTimestamp(2).toInstant().toString()
        }
        return r
    }

    /** Produtos e pedidos em conferência aberta na Torre (sessão 'pronta'). RLS limita ao tenant. */
    private fun Transaction.emSeparacao(): Pair<Set<Int>, Int> {
        val produtos = mutableSetOf<Int>()
        var documentos = 0
        exec(
            "SELECT DISTINCT i.codprod FROM app.separacao_itens i JOIN app.separacao_sessoes s ON s.id = i.sessao_id " +
                "WHERE s.status = 'pronta'",
        ) { rs -> while (rs.next()) produtos += rs.getInt(1) }
        exec("SELECT count(DISTINCT nunota) FROM app.separacao_sessoes WHERE status = 'pronta'") { rs -> if (rs.next()) documentos = rs.getInt(1) }
        return produtos to documentos
    }

    /** Produtos com saldo no endereço (pra ler peso/volume do Sankhya antes de validar capacidade). */
    fun codprodsNoEndereco(tenantId: UUID, enderecoId: UUID): List<Int> = TenantTx.run(tenantId) {
        GalpaoSaldosEnderecoTable.selectAll()
            .where { (GalpaoSaldosEnderecoTable.enderecoId eq enderecoId) and (GalpaoSaldosEnderecoTable.quantidade greater BigDecimal.ZERO) }
            .map { it[GalpaoSaldosEnderecoTable.codprod] }
            .distinct()
    }

    /** Produtos com saldo na planta (peso/volume pra ocupação da visão). */
    fun codprodsNaPlanta(tenantId: UUID, plantaId: UUID): List<Int> = TenantTx.run(tenantId) {
        GalpaoSaldosEnderecoTable.join(GalpaoEnderecosTable, org.jetbrains.exposed.sql.JoinType.INNER, GalpaoSaldosEnderecoTable.enderecoId, GalpaoEnderecosTable.id)
            .select(GalpaoSaldosEnderecoTable.codprod)
            .where { (GalpaoEnderecosTable.plantaId eq plantaId) and (GalpaoSaldosEnderecoTable.quantidade greater BigDecimal.ZERO) }
            .withDistinct()
            .map { it[GalpaoSaldosEnderecoTable.codprod] }
    }

    /** Quantidade endereçada por produto na planta (soma dos saldos dos endereços ativos) — base das análises. */
    fun enderecadoPorProduto(tenantId: UUID, plantaId: UUID): Map<Int, Double> = TenantTx.run(tenantId) {
        GalpaoSaldosEnderecoTable.join(GalpaoEnderecosTable, org.jetbrains.exposed.sql.JoinType.INNER, GalpaoSaldosEnderecoTable.enderecoId, GalpaoEnderecosTable.id)
            .select(GalpaoSaldosEnderecoTable.codprod, GalpaoSaldosEnderecoTable.quantidade)
            .where { (GalpaoEnderecosTable.plantaId eq plantaId) and (GalpaoEnderecosTable.ativo eq true) }
            .groupBy({ it[GalpaoSaldosEnderecoTable.codprod] }, { it[GalpaoSaldosEnderecoTable.quantidade].toDouble() })
            .mapValues { (_, v) -> v.sum() }
    }

    /** "Descrição - Complemento" (padrão de todas as telas). Chamar dentro de TenantTx. */
    fun nomesProdutos(tenantId: UUID, codprods: List<Int>): Map<Int, String> {
        if (codprods.isEmpty()) return emptyMap()
        return ProdutosCacheTable.selectAll()
            .where { (ProdutosCacheTable.tenantId eq tenantId) and (ProdutosCacheTable.codprod inList codprods) }
            .associate { r ->
                val compl = r[ProdutosCacheTable.compldesc]?.trim().orEmpty()
                r[ProdutosCacheTable.codprod] to (r[ProdutosCacheTable.descrprod].trim() + if (compl.isNotEmpty()) " - $compl" else "")
            }
    }

    private fun Transaction.travarPlanta(plantaId: UUID): ResultRow? {
        exec("SELECT id FROM app.galpao_plantas WHERE id = '$plantaId' FOR UPDATE")
        return GalpaoPlantasTable.selectAll().where { GalpaoPlantasTable.id eq plantaId }.singleOrNull()
    }

    private fun linhaPlanta(tenantId: UUID, id: UUID): ResultRow? = GalpaoPlantasTable.selectAll()
        .where { (GalpaoPlantasTable.tenantId eq tenantId) and (GalpaoPlantasTable.id eq id) and (GalpaoPlantasTable.ativo eq true) }
        .singleOrNull()

    private fun plantaDto(r: ResultRow): PlantaDto {
        val id = r[GalpaoPlantasTable.id]
        val publicada = r[GalpaoPlantasTable.versaoPublicadaId]?.let { vid ->
            GalpaoVersoesTable.selectAll().where { GalpaoVersoesTable.id eq vid }.singleOrNull()?.get(GalpaoVersoesTable.numero)
        }
        val total = GalpaoEnderecosTable.selectAll()
            .where { (GalpaoEnderecosTable.plantaId eq id) and (GalpaoEnderecosTable.ativo eq true) }
            .count().toInt()
        val temRascunho = !GalpaoVersoesTable.selectAll()
            .where { (GalpaoVersoesTable.plantaId eq id) and (GalpaoVersoesTable.status eq StatusVersao.RASCUNHO) }
            .empty()
        return PlantaDto(
            id = id.toString(),
            nome = r[GalpaoPlantasTable.nome],
            codemp = r[GalpaoPlantasTable.codemp],
            codlocal = r[GalpaoPlantasTable.codlocal],
            largura = r[GalpaoPlantasTable.largura].toDouble(),
            profundidade = r[GalpaoPlantasTable.profundidade].toDouble(),
            versaoPublicada = publicada,
            totalEnderecos = total,
            temRascunho = temRascunho,
        )
    }

    private fun versaoResumo(r: ResultRow): VersaoResumoDto {
        val totalObjetos = runCatching { GeometriaJson.ler(r[GalpaoVersoesTable.geometria]).objetos.size }.getOrDefault(0)
        return VersaoResumoDto(
            id = r[GalpaoVersoesTable.id].toString(),
            numero = r[GalpaoVersoesTable.numero],
            status = r[GalpaoVersoesTable.status],
            observacao = r[GalpaoVersoesTable.observacao],
            criadoPorNome = r[GalpaoVersoesTable.criadoPorNome],
            criadoEm = r[GalpaoVersoesTable.criadoEm].toString(),
            publicadoPorNome = r[GalpaoVersoesTable.publicadoPorNome],
            publicadoEm = r[GalpaoVersoesTable.publicadoEm]?.toString(),
            totalObjetos = totalObjetos,
        )
    }

    private fun dec(v: Double): BigDecimal = BigDecimal.valueOf(v).setScale(3, java.math.RoundingMode.HALF_UP)

    private fun preencher(it: org.jetbrains.exposed.sql.statements.UpdateStatement, e: EnderecoGerado) {
        it[GalpaoEnderecosTable.setor] = e.setor
        it[GalpaoEnderecosTable.estrutura] = e.estrutura
        it[GalpaoEnderecosTable.modulo] = e.modulo
        it[GalpaoEnderecosTable.nivel] = e.nivel
        it[GalpaoEnderecosTable.tipo] = e.tipo
        it[GalpaoEnderecosTable.x] = dec(e.x)
        it[GalpaoEnderecosTable.y] = dec(e.y)
        it[GalpaoEnderecosTable.z] = dec(e.z)
        it[GalpaoEnderecosTable.rotacao] = dec(e.rotacao)
        it[GalpaoEnderecosTable.largura] = dec(e.largura)
        it[GalpaoEnderecosTable.comprimento] = dec(e.comprimento)
        it[GalpaoEnderecosTable.altura] = dec(e.altura)
        it[GalpaoEnderecosTable.capQtd] = e.capQtd?.toBigDecimal()
        it[GalpaoEnderecosTable.capPeso] = e.capPeso?.toBigDecimal()
        it[GalpaoEnderecosTable.capVolume] = e.capVolume?.toBigDecimal()
    }
}
