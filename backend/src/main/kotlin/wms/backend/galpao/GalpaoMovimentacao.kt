package wms.backend.galpao

import io.ktor.http.HttpStatusCode
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greater
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.like
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.upperCase
import wms.backend.produtos.ProdutosCacheTable
import wms.backend.tenancy.TenantRepository
import wms.backend.tenancy.TenantTx
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.util.UUID

/** Erro de negócio da movimentação: vira {erro} com o status HTTP indicado. */
class MovimentacaoException(val status: HttpStatusCode, message: String) : Exception(message)

private fun recusar(msg: String): Nothing = throw MovimentacaoException(HttpStatusCode.UnprocessableEntity, msg)
private fun conflito(msg: String): Nothing = throw MovimentacaoException(HttpStatusCode.Conflict, msg)

/**
 * Movimentação física por endereço (fase 4) — regras do Negri Planta Log 3D (OperationService):
 *  - ENDERECAR: põe saldo já reconhecido pelo Sankhya num endereço; o total endereçado do produto/controle no
 *    empresa/local não passa do TGFEST (estoque próprio). Recebimento fiscal continua no Sankhya.
 *  - TRANSFERIR: endereço → endereço; RETIRAR: tira do endereço (saída manual; baixa fiscal no faturamento).
 *  - CONTAR: registra a contagem; não ajusta — vira divergência PENDENTE (aprovar = AJUSTE_CONTAGEM).
 *  - BLOQUEAR/DESBLOQUEAR: endereço bloqueado não recebe nem libera.
 * Cada comando tem UUID (reenviar não repete), confere a versão do saldo que a tela viu (mudou = 409) e roda
 * com a planta travada (FOR UPDATE), um comando por vez por planta. Nada é gravado no Sankhya.
 */
object GalpaoMovimentacao {

    private val ZERO = BigDecimal.ZERO
    private const val ESCALA = 5

    // ─── comando ────────────────────────────────────────────────────────────

    suspend fun executar(tenantId: UUID, plantaId: UUID, req: MovimentoRequest, usuarioId: UUID, usuarioNome: String): ResultadoMovimentoDto {
        val comandoId = runCatching { UUID.fromString(req.comandoId) }.getOrNull() ?: recusar("Comando sem identificador")
        val tipo = req.tipo
        val origem = req.origemId?.let { uuidOu(it, "Endereço de origem inválido") }
        val destino = req.destinoId?.let { uuidOu(it, "Endereço de destino inválido") }
        val controle = GalpaoErp.normalizarControle(req.controle)
        val qtd = quantidade(req.quantidade, permiteZero = tipo == "CONTAR" || tipo == TipoMovimento.BLOQUEAR || tipo == TipoMovimento.DESBLOQUEAR)
        val motivo = req.motivo?.trim()?.take(200)?.ifBlank { null }

        when (tipo) {
            TipoMovimento.ENDERECAR, "CONTAR" -> if (destino == null || origem != null) recusar("A operação exige só o endereço de destino")
            TipoMovimento.TRANSFERIR -> if (origem == null || destino == null || origem == destino) recusar("Origem e destino distintos são obrigatórios")
            TipoMovimento.RETIRAR -> if (origem == null || destino != null) recusar("A retirada exige só o endereço de origem")
            TipoMovimento.BLOQUEAR, TipoMovimento.DESBLOQUEAR -> if (destino == null) recusar("Informe o endereço")
            else -> recusar("Operação desconhecida: $tipo")
        }
        val bloqueio = tipo == TipoMovimento.BLOQUEAR || tipo == TipoMovimento.DESBLOQUEAR
        val codprod = if (bloqueio) null else (req.codprod?.takeIf { it > 0 } ?: recusar("Produto obrigatório"))

        // Leituras do Sankhya ANTES da transação (rede fora do lock do banco).
        val planta = GalpaoRepository.buscarPlanta(tenantId, plantaId) ?: throw MovimentacaoException(HttpStatusCode.NotFound, "Planta não encontrada")
        val slug = TenantRepository.buscarPorId(tenantId)?.slug ?: recusar("Instalação não encontrada")
        val contabil = if (tipo == TipoMovimento.ENDERECAR) {
            val emp = planta.codemp ?: recusar("Cadastre a empresa e o local de estoque da planta para endereçar")
            val loc = planta.codlocal ?: recusar("Cadastre a empresa e o local de estoque da planta para endereçar")
            val linhas = lerSankhya { GalpaoErp.estoqueContabil(slug, emp, loc, codprod) }
            linhas.filter { it.controle == controle }.sumOf { it.estoque }
        } else null
        val fisicos = if (tipo == TipoMovimento.ENDERECAR || tipo == TipoMovimento.TRANSFERIR) {
            val noDestino = GalpaoRepository.codprodsNoEndereco(tenantId, destino!!)
            lerSankhya { GalpaoErp.fisicos(slug, noDestino + codprod!!) }
        } else emptyMap()

        return TenantTx.run(tenantId, 30_000) {
            travarPlanta(plantaId)
            movimentoExistente(tenantId, comandoId)?.let { return@run ResultadoMovimentoDto(repetido = true, mensagem = "Comando já registrado", movimentoId = it) }
            if (tipo == "CONTAR") {
                registrarContagem(tenantId, plantaId, comandoId, destino!!, codprod!!, controle, qtd, req.versaoDestino, motivo, usuarioNome)
            } else {
                val o = origem?.let { enderecoAtivo(plantaId, it, "origem") }
                val d = destino?.let { enderecoAtivo(plantaId, it, "destino") }
                if (bloqueio) {
                    d!!
                    val bloquear = tipo == TipoMovimento.BLOQUEAR
                    if (d[GalpaoEnderecosTable.bloqueado] == bloquear) recusar(if (bloquear) "Endereço já está bloqueado" else "Endereço não está bloqueado")
                    GalpaoEnderecosTable.update({ GalpaoEnderecosTable.id eq destino }) {
                        it[bloqueado] = bloquear
                        it[atualizadoEm] = Instant.now()
                    }
                } else {
                    codprod!!
                    if (o != null) {
                        if (o[GalpaoEnderecosTable.bloqueado]) recusar("Origem bloqueada")
                        val s = saldo(origem, codprod, controle)
                        conferirVersao(s, req.versaoOrigem, "origem")
                        if ((s?.get(GalpaoSaldosEnderecoTable.quantidade) ?: ZERO) < qtd) recusar("Saldo insuficiente na origem")
                    }
                    if (d != null) {
                        if (d[GalpaoEnderecosTable.bloqueado]) recusar("Destino bloqueado")
                        conferirVersao(saldo(destino, codprod, controle), req.versaoDestino, "destino")
                        val atuais = GalpaoSaldosEnderecoTable.selectAll()
                            .where { GalpaoSaldosEnderecoTable.enderecoId eq destino }
                            .map { ItemNoEndereco(it[GalpaoSaldosEnderecoTable.codprod], it[GalpaoSaldosEnderecoTable.quantidade].toDouble()) }
                        CapacidadeEndereco.validarEntrada(capacidade(d), atuais, codprod, qtd.toDouble(), fisicos)?.let { recusar(it) }
                    }
                    if (tipo == TipoMovimento.ENDERECAR) {
                        val enderecado = enderecadoNoLocal(tenantId, planta.codemp!!, planta.codlocal!!, codprod, controle)
                        if (enderecado + qtd > BigDecimal.valueOf(contabil!!).setScale(ESCALA, RoundingMode.HALF_UP)) {
                            val livre = (BigDecimal.valueOf(contabil) - enderecado).max(ZERO).stripTrailingZeros().toPlainString()
                            recusar("Quantidade acima do saldo do Sankhya ainda sem endereço ($livre). Entrada fiscal é feita no Sankhya")
                        }
                    }
                    origem?.let { alterarSaldo(tenantId, it, codprod, controle, qtd.negate()) }
                    destino?.let { alterarSaldo(tenantId, it, codprod, controle, qtd) }
                }
                val id = inserirMovimento(tenantId, plantaId, comandoId, tipo, origem, destino, codprod, controle, if (bloqueio) ZERO else qtd, motivo, null, usuarioId, usuarioNome)
                ResultadoMovimentoDto(repetido = false, mensagem = mensagemOk(tipo), movimentoId = id.toString())
            }
        }
    }

    private fun Transaction.registrarContagem(
        tenantId: UUID, plantaId: UUID, comandoId: UUID, enderecoId: UUID, codprod: Int, controle: String,
        contada: BigDecimal, versaoVista: Int?, motivo: String?, usuarioNome: String,
    ): ResultadoMovimentoDto {
        val e = enderecoAtivo(plantaId, enderecoId, "destino")
        if (e[GalpaoEnderecosTable.bloqueado]) recusar("Endereço bloqueado")
        val s = saldo(enderecoId, codprod, controle)
        conferirVersao(s, versaoVista, "endereço")
        val sistema = s?.get(GalpaoSaldosEnderecoTable.quantidade) ?: ZERO
        val semDivergencia = sistema.compareTo(contada) == 0
        val id = UUID.randomUUID()
        val agora = Instant.now()
        GalpaoContagensTable.insert {
            it[GalpaoContagensTable.id] = id
            it[GalpaoContagensTable.tenantId] = tenantId
            it[GalpaoContagensTable.plantaId] = plantaId
            it[GalpaoContagensTable.enderecoId] = enderecoId
            it[GalpaoContagensTable.comandoId] = comandoId
            it[GalpaoContagensTable.codprod] = codprod
            it[GalpaoContagensTable.controle] = controle
            it[quantidadeContada] = contada
            it[quantidadeSistema] = sistema
            it[versaoSaldo] = s?.get(GalpaoSaldosEnderecoTable.versao) ?: 0
            it[GalpaoContagensTable.motivo] = motivo
            // Contagem igual ao saldo não gera pendência (nada a aprovar).
            it[status] = if (semDivergencia) StatusContagem.APROVADA else StatusContagem.PENDENTE
            it[contadoPorNome] = usuarioNome
            it[contadoEm] = agora
            if (semDivergencia) {
                it[decididoPorNome] = usuarioNome
                it[decididoEm] = agora
                it[observacaoDecisao] = "Sem divergência"
            }
        }
        return ResultadoMovimentoDto(
            repetido = false,
            mensagem = if (semDivergencia) "Contagem confere com o saldo — sem divergência" else "Contagem registrada — divergência aguardando aprovação",
            contagemId = id.toString(),
        )
    }

    // ─── contagens ──────────────────────────────────────────────────────────

    fun aprovarContagem(tenantId: UUID, plantaId: UUID, contagemId: UUID, observacao: String?, usuarioId: UUID, usuarioNome: String): ResultadoMovimentoDto =
        TenantTx.run(tenantId, 30_000) {
            travarPlanta(plantaId)
            val c = contagemPendente(plantaId, contagemId)
            val endereco = c[GalpaoContagensTable.enderecoId]
            val codprod = c[GalpaoContagensTable.codprod]
            val controle = c[GalpaoContagensTable.controle]
            enderecoAtivo(plantaId, endereco, "endereço")
            val atual = saldo(endereco, codprod, controle)
            if ((atual?.get(GalpaoSaldosEnderecoTable.versao) ?: 0) != c[GalpaoContagensTable.versaoSaldo]) {
                conflito("O saldo do endereço mudou depois da contagem. Descarte e conte de novo")
            }
            val delta = c[GalpaoContagensTable.quantidadeContada] - (atual?.get(GalpaoSaldosEnderecoTable.quantidade) ?: ZERO)
            alterarSaldo(tenantId, endereco, codprod, controle, delta)
            val obs = observacao?.trim()?.take(200)?.ifBlank { null }
            val id = inserirMovimento(
                tenantId, plantaId, UUID.randomUUID(), TipoMovimento.AJUSTE_CONTAGEM, null, endereco, codprod, controle, delta,
                obs ?: c[GalpaoContagensTable.motivo], contagemId, usuarioId, usuarioNome,
            )
            decidir(contagemId, StatusContagem.APROVADA, obs, usuarioNome)
            ResultadoMovimentoDto(repetido = false, mensagem = "Ajuste aplicado no saldo do endereço", movimentoId = id.toString())
        }

    fun descartarContagem(tenantId: UUID, plantaId: UUID, contagemId: UUID, observacao: String?, usuarioNome: String): ResultadoMovimentoDto =
        TenantTx.run(tenantId) {
            travarPlanta(plantaId)
            contagemPendente(plantaId, contagemId)
            decidir(contagemId, StatusContagem.DESCARTADA, observacao?.trim()?.take(200)?.ifBlank { null }, usuarioNome)
            ResultadoMovimentoDto(repetido = false, mensagem = "Contagem descartada", contagemId = contagemId.toString())
        }

    fun listarContagens(tenantId: UUID, plantaId: UUID, status: String?): List<ContagemDto> = TenantTx.run(tenantId) {
        val linhas = GalpaoContagensTable.selectAll()
            .where {
                (GalpaoContagensTable.plantaId eq plantaId) and
                    (if (status.isNullOrBlank()) Op.TRUE else GalpaoContagensTable.status eq status)
            }
            .orderBy(GalpaoContagensTable.contadoEm to SortOrder.DESC)
            .limit(500)
            .toList()
        val codigos = codigosEndereco(linhas.map { it[GalpaoContagensTable.enderecoId] })
        val nomes = nomesProdutos(tenantId, linhas.map { it[GalpaoContagensTable.codprod] })
        linhas.map { c ->
            val cod = c[GalpaoContagensTable.codprod]
            val contada = c[GalpaoContagensTable.quantidadeContada]
            val sistema = c[GalpaoContagensTable.quantidadeSistema]
            ContagemDto(
                id = c[GalpaoContagensTable.id].toString(),
                endereco = codigos[c[GalpaoContagensTable.enderecoId]] ?: "—",
                enderecoId = c[GalpaoContagensTable.enderecoId].toString(),
                codprod = cod,
                produto = nomes[cod] ?: "Produto $cod",
                controle = c[GalpaoContagensTable.controle],
                quantidadeSistema = sistema.toDouble(),
                quantidadeContada = contada.toDouble(),
                diferenca = (contada - sistema).toDouble(),
                motivo = c[GalpaoContagensTable.motivo],
                status = c[GalpaoContagensTable.status],
                contadoPorNome = c[GalpaoContagensTable.contadoPorNome],
                contadoEm = c[GalpaoContagensTable.contadoEm].toString(),
                decididoPorNome = c[GalpaoContagensTable.decididoPorNome],
                decididoEm = c[GalpaoContagensTable.decididoEm]?.toString(),
                observacaoDecisao = c[GalpaoContagensTable.observacaoDecisao],
            )
        }
    }

    // ─── consultas ──────────────────────────────────────────────────────────

    fun listarMovimentos(tenantId: UUID, plantaId: UUID, codprod: Int?, endereco: String?, tipo: String?, pagina: Int): MovimentosPaginadosDto =
        TenantTx.run(tenantId, 15_000) {
            val porPagina = 50
            val enderecoIds = endereco?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }?.let { cod ->
                GalpaoEnderecosTable.selectAll()
                    .where { (GalpaoEnderecosTable.plantaId eq plantaId) and (GalpaoEnderecosTable.codigo eq cod) }
                    .map { it[GalpaoEnderecosTable.id] }
            }
            val filtro = (GalpaoMovimentosTable.plantaId eq plantaId) and
                (if (codprod != null) GalpaoMovimentosTable.codprod eq codprod else Op.TRUE) and
                (if (!tipo.isNullOrBlank()) GalpaoMovimentosTable.tipo eq tipo else Op.TRUE) and
                (
                    if (enderecoIds != null) {
                        (GalpaoMovimentosTable.enderecoOrigemId inList enderecoIds) or (GalpaoMovimentosTable.enderecoDestinoId inList enderecoIds)
                    } else Op.TRUE
                    )
            val total = GalpaoMovimentosTable.selectAll().where { filtro }.count().toInt()
            val totalPaginas = maxOf(1, (total + porPagina - 1) / porPagina)
            val p = pagina.coerceIn(1, totalPaginas)
            val linhas = GalpaoMovimentosTable.selectAll().where { filtro }
                .orderBy(GalpaoMovimentosTable.criadoEm to SortOrder.DESC)
                .limit(porPagina).offset(((p - 1) * porPagina).toLong())
                .toList()
            val codigos = codigosEndereco(linhas.flatMap { listOfNotNull(it[GalpaoMovimentosTable.enderecoOrigemId], it[GalpaoMovimentosTable.enderecoDestinoId]) })
            val nomes = nomesProdutos(tenantId, linhas.mapNotNull { it[GalpaoMovimentosTable.codprod] })
            MovimentosPaginadosDto(
                itens = linhas.map { m ->
                    val cod = m[GalpaoMovimentosTable.codprod]
                    MovimentoDto(
                        id = m[GalpaoMovimentosTable.id].toString(),
                        tipo = m[GalpaoMovimentosTable.tipo],
                        origem = m[GalpaoMovimentosTable.enderecoOrigemId]?.let { codigos[it] },
                        destino = m[GalpaoMovimentosTable.enderecoDestinoId]?.let { codigos[it] },
                        codprod = cod,
                        produto = cod?.let { nomes[it] ?: "Produto $it" },
                        controle = m[GalpaoMovimentosTable.controle],
                        quantidade = m[GalpaoMovimentosTable.quantidade].toDouble(),
                        motivo = m[GalpaoMovimentosTable.motivo],
                        usuarioNome = m[GalpaoMovimentosTable.usuarioNome],
                        criadoEm = m[GalpaoMovimentosTable.criadoEm].toString(),
                    )
                },
                paginaAtual = p,
                totalPaginas = totalPaginas,
                totalRegistros = total,
            )
        }

    /** Busca no catálogo local (código exato ou trecho da descrição), em ordem alfabética. */
    fun buscarProdutos(tenantId: UUID, termo: String): List<ProdutoBuscaDto> = TenantTx.run(tenantId) {
        val t = termo.trim()
        if (t.length < 2 && t.toIntOrNull() == null) return@run emptyList()
        val numero = t.toIntOrNull()
        val padrao = "%" + t.uppercase().replace("%", "").replace("_", "") + "%"
        ProdutosCacheTable.selectAll()
            .where {
                val porDescricao = ProdutosCacheTable.descrprod.upperCase() like padrao
                (ProdutosCacheTable.tenantId eq tenantId) and
                    (if (numero != null) (ProdutosCacheTable.codprod eq numero) or porDescricao else porDescricao)
            }
            .orderBy(ProdutosCacheTable.descrprod to SortOrder.ASC)
            .limit(30)
            .map {
                val compl = it[ProdutosCacheTable.compldesc]?.trim().orEmpty()
                ProdutoBuscaDto(
                    codprod = it[ProdutosCacheTable.codprod],
                    produto = it[ProdutosCacheTable.descrprod].trim() + if (compl.isNotEmpty()) " - $compl" else "",
                    unidade = it[ProdutosCacheTable.codvol],
                )
            }
            .sortedWith(compareBy({ it.codprod != numero }, { it.produto }))
    }

    /** Produto no local da planta: por controle, contábil × endereçado, e onde está endereçado nesta planta. */
    suspend fun estoqueProduto(tenantId: UUID, plantaId: UUID, codprod: Int): EstoqueProdutoDto {
        val planta = GalpaoRepository.buscarPlanta(tenantId, plantaId) ?: throw MovimentacaoException(HttpStatusCode.NotFound, "Planta não encontrada")
        val slug = TenantRepository.buscarPorId(tenantId)?.slug ?: recusar("Instalação não encontrada")
        val (contabil, aviso) = if (planta.codemp == null || planta.codlocal == null) {
            emptyList<LinhaContabil>() to "Planta sem empresa/local cadastrados — não é possível endereçar"
        } else {
            try {
                GalpaoErp.estoqueContabil(slug, planta.codemp, planta.codlocal, codprod) to null
            } catch (e: Exception) {
                emptyList<LinhaContabil>() to "Não foi possível ler o estoque no Sankhya: ${e.message}"
            }
        }
        return TenantTx.run(tenantId) {
            val enderecado = if (planta.codemp != null && planta.codlocal != null) {
                enderecadoPorControle(tenantId, planta.codemp, planta.codlocal, codprod)
            } else emptyMap()
            val controles = (contabil.map { it.controle } + enderecado.keys).distinct().sorted()
            val locais = GalpaoSaldosEnderecoTable.join(GalpaoEnderecosTable, org.jetbrains.exposed.sql.JoinType.INNER, GalpaoSaldosEnderecoTable.enderecoId, GalpaoEnderecosTable.id)
                .selectAll()
                .where {
                    (GalpaoEnderecosTable.plantaId eq plantaId) and (GalpaoSaldosEnderecoTable.codprod eq codprod) and
                        (GalpaoSaldosEnderecoTable.quantidade greater ZERO)
                }
                .orderBy(GalpaoEnderecosTable.codigo to SortOrder.ASC)
                .map {
                    ProdutoNoEnderecoDto(it[GalpaoEnderecosTable.codigo], it[GalpaoEnderecosTable.id].toString(), it[GalpaoSaldosEnderecoTable.controle], it[GalpaoSaldosEnderecoTable.quantidade].toDouble())
                }
            EstoqueProdutoDto(
                codprod = codprod,
                produto = nomesProdutos(tenantId, listOf(codprod))[codprod] ?: "Produto $codprod",
                controles = controles.map { c ->
                    val cont = contabil.filter { it.controle == c }
                    val est = cont.sumOf { it.estoque }
                    val end = enderecado[c]?.toDouble() ?: 0.0
                    ControleEstoqueDto(c, est, cont.sumOf { it.reservado }, end, maxOf(est - end, 0.0))
                },
                enderecos = locais,
                aviso = aviso,
            )
        }
    }

    /** Físico endereçado (todas as plantas do mesmo empresa/local) × TGFEST, por produto/controle. */
    suspend fun conciliacao(tenantId: UUID, plantaId: UUID): ConciliacaoDto {
        val planta = GalpaoRepository.buscarPlanta(tenantId, plantaId) ?: throw MovimentacaoException(HttpStatusCode.NotFound, "Planta não encontrada")
        val emp = planta.codemp ?: recusar("Cadastre a empresa e o local de estoque da planta para conciliar")
        val loc = planta.codlocal ?: recusar("Cadastre a empresa e o local de estoque da planta para conciliar")
        val slug = TenantRepository.buscarPorId(tenantId)?.slug ?: recusar("Instalação não encontrada")
        val contabil = lerSankhya { GalpaoErp.estoqueContabil(slug, emp, loc) }
        return TenantTx.run(tenantId, 30_000) {
            val fisico = enderecadoNoLocalTodos(tenantId, emp, loc)
            val chaves = (contabil.map { it.codprod to it.controle } + fisico.keys).toSet()
            val nomes = nomesProdutos(tenantId, chaves.map { it.first })
            val porChave = contabil.associateBy { it.codprod to it.controle }
            val linhas = chaves.mapNotNull { k ->
                val c = porChave[k]
                val est = c?.estoque ?: 0.0
                val end = fisico[k]?.toDouble() ?: 0.0
                if (est == 0.0 && end == 0.0) return@mapNotNull null
                // Saldo negativo no Sankhya é problema do ERP, não "endereçado a mais": situação própria.
                val situacao = when {
                    est < -1e-9 -> "NEGATIVO"
                    end > est + 1e-9 -> "EXCEDENTE"
                    end == 0.0 -> "SEM_ENDERECO"
                    end < est - 1e-9 -> "PARCIAL"
                    else -> "OK"
                }
                ConciliacaoLinhaDto(
                    k.first, nomes[k.first] ?: "Produto ${k.first}", k.second, est, c?.reservado ?: 0.0, end,
                    maxOf(est - end, 0.0), maxOf(end - maxOf(est, 0.0), 0.0), situacao,
                )
            }.sortedWith(compareBy({ it.produto }, { it.controle }))
            ConciliacaoDto(codemp = emp, codlocal = loc, linhas = linhas, lidoEm = Instant.now().toString())
        }
    }

    // ─── auxiliares ─────────────────────────────────────────────────────────

    private suspend fun <T> lerSankhya(bloco: suspend () -> T): T = try {
        bloco()
    } catch (e: MovimentacaoException) {
        throw e
    } catch (e: Exception) {
        throw MovimentacaoException(HttpStatusCode.BadGateway, "Não foi possível consultar o Sankhya: ${e.message}")
    }

    private fun uuidOu(v: String, erro: String): UUID = runCatching { UUID.fromString(v) }.getOrNull() ?: recusar(erro)

    private fun quantidade(v: Double, permiteZero: Boolean): BigDecimal {
        if (v.isNaN() || v.isInfinite()) recusar("Quantidade inválida")
        val q = BigDecimal.valueOf(v)
        if (q.signum() < 0 || (!permiteZero && q.signum() == 0)) recusar("Quantidade deve ser maior que zero")
        if (q.stripTrailingZeros().scale() > ESCALA) recusar("Quantidade com no máximo $ESCALA casas decimais")
        return q.setScale(ESCALA, RoundingMode.UNNECESSARY)
    }

    private fun mensagemOk(tipo: String) = when (tipo) {
        TipoMovimento.ENDERECAR -> "Produto endereçado"
        TipoMovimento.TRANSFERIR -> "Transferência registrada"
        TipoMovimento.RETIRAR -> "Retirada registrada"
        TipoMovimento.BLOQUEAR -> "Endereço bloqueado"
        TipoMovimento.DESBLOQUEAR -> "Endereço desbloqueado"
        else -> "Movimento registrado"
    }

    private fun Transaction.travarPlanta(plantaId: UUID) {
        exec("SELECT id FROM app.galpao_plantas WHERE id = '$plantaId' FOR UPDATE")
    }

    private fun movimentoExistente(tenantId: UUID, comandoId: UUID): String? =
        GalpaoMovimentosTable.selectAll()
            .where { (GalpaoMovimentosTable.tenantId eq tenantId) and (GalpaoMovimentosTable.comandoId eq comandoId) }
            .singleOrNull()?.get(GalpaoMovimentosTable.id)?.toString()
            ?: GalpaoContagensTable.selectAll()
                .where { (GalpaoContagensTable.tenantId eq tenantId) and (GalpaoContagensTable.comandoId eq comandoId) }
                .singleOrNull()?.get(GalpaoContagensTable.id)?.toString()

    private fun enderecoAtivo(plantaId: UUID, id: UUID, papel: String): ResultRow =
        GalpaoEnderecosTable.selectAll()
            .where { (GalpaoEnderecosTable.id eq id) and (GalpaoEnderecosTable.plantaId eq plantaId) }
            .singleOrNull()
            ?.takeIf { it[GalpaoEnderecosTable.ativo] }
            ?: recusar("Endereço de $papel inexistente ou inativo nesta planta")

    private fun capacidade(e: ResultRow) = Capacidade(
        e[GalpaoEnderecosTable.capQtd]?.toDouble(),
        e[GalpaoEnderecosTable.capPeso]?.toDouble(),
        e[GalpaoEnderecosTable.capVolume]?.toDouble(),
    )

    private fun saldo(enderecoId: UUID, codprod: Int, controle: String): ResultRow? =
        GalpaoSaldosEnderecoTable.selectAll()
            .where {
                (GalpaoSaldosEnderecoTable.enderecoId eq enderecoId) and (GalpaoSaldosEnderecoTable.codprod eq codprod) and
                    (GalpaoSaldosEnderecoTable.controle eq controle)
            }
            .forUpdate()
            .singleOrNull()

    /** A tela manda a versão do saldo que mostrou (0 = não havia saldo). Diferente = alguém mexeu antes. */
    private fun conferirVersao(s: ResultRow?, vista: Int?, papel: String) {
        val atual = s?.get(GalpaoSaldosEnderecoTable.versao) ?: 0
        if (vista == null || vista != atual) conflito("O saldo do $papel mudou desde que a tela foi carregada. Atualize e confira")
    }

    private fun alterarSaldo(tenantId: UUID, enderecoId: UUID, codprod: Int, controle: String, delta: BigDecimal) {
        val s = saldo(enderecoId, codprod, controle)
        val agora = Instant.now()
        if (s == null) {
            if (delta.signum() < 0) recusar("Saldo insuficiente")
            GalpaoSaldosEnderecoTable.insert {
                it[id] = UUID.randomUUID()
                it[GalpaoSaldosEnderecoTable.tenantId] = tenantId
                it[GalpaoSaldosEnderecoTable.enderecoId] = enderecoId
                it[GalpaoSaldosEnderecoTable.codprod] = codprod
                it[GalpaoSaldosEnderecoTable.controle] = controle
                it[quantidade] = delta
                it[versao] = 1
                it[atualizadoEm] = agora
            }
        } else {
            val nova = s[GalpaoSaldosEnderecoTable.quantidade] + delta
            if (nova.signum() < 0) recusar("Saldo insuficiente")
            GalpaoSaldosEnderecoTable.update({ GalpaoSaldosEnderecoTable.id eq s[GalpaoSaldosEnderecoTable.id] }) {
                it[quantidade] = nova
                it[versao] = s[GalpaoSaldosEnderecoTable.versao] + 1
                it[atualizadoEm] = agora
            }
        }
    }

    private fun inserirMovimento(
        tenantId: UUID, plantaId: UUID, comandoId: UUID, tipo: String, origem: UUID?, destino: UUID?, codprod: Int?, controle: String,
        quantidade: BigDecimal, motivo: String?, contagemId: UUID?, usuarioId: UUID, usuarioNome: String,
    ): UUID {
        val id = UUID.randomUUID()
        GalpaoMovimentosTable.insert {
            it[GalpaoMovimentosTable.id] = id
            it[GalpaoMovimentosTable.tenantId] = tenantId
            it[GalpaoMovimentosTable.plantaId] = plantaId
            it[GalpaoMovimentosTable.comandoId] = comandoId
            it[GalpaoMovimentosTable.tipo] = tipo
            it[enderecoOrigemId] = origem
            it[enderecoDestinoId] = destino
            it[GalpaoMovimentosTable.codprod] = codprod
            it[GalpaoMovimentosTable.controle] = controle
            it[GalpaoMovimentosTable.quantidade] = quantidade
            it[GalpaoMovimentosTable.motivo] = motivo
            it[GalpaoMovimentosTable.contagemId] = contagemId
            it[GalpaoMovimentosTable.usuarioId] = usuarioId
            it[GalpaoMovimentosTable.usuarioNome] = usuarioNome
            it[criadoEm] = Instant.now()
        }
        return id
    }

    private fun contagemPendente(plantaId: UUID, contagemId: UUID): ResultRow {
        val c = GalpaoContagensTable.selectAll()
            .where { (GalpaoContagensTable.id eq contagemId) and (GalpaoContagensTable.plantaId eq plantaId) }
            .forUpdate()
            .singleOrNull() ?: throw MovimentacaoException(HttpStatusCode.NotFound, "Contagem não encontrada")
        if (c[GalpaoContagensTable.status] != StatusContagem.PENDENTE) conflito("Contagem já foi decidida")
        return c
    }

    private fun decidir(contagemId: UUID, status: String, obs: String?, usuarioNome: String) {
        GalpaoContagensTable.update({ GalpaoContagensTable.id eq contagemId }) {
            it[GalpaoContagensTable.status] = status
            it[decididoPorNome] = usuarioNome
            it[decididoEm] = Instant.now()
            it[observacaoDecisao] = obs
        }
    }

    /** Plantas do tenant no mesmo empresa/local (o limite do Sankhya vale pro local inteiro, não por planta). */
    private fun plantasDoLocal(tenantId: UUID, codemp: Int, codlocal: Int): List<UUID> =
        GalpaoPlantasTable.selectAll()
            .where {
                (GalpaoPlantasTable.tenantId eq tenantId) and (GalpaoPlantasTable.codemp eq codemp) and
                    (GalpaoPlantasTable.codlocal eq codlocal) and (GalpaoPlantasTable.ativo eq true)
            }
            .map { it[GalpaoPlantasTable.id] }

    private fun enderecadoNoLocalTodos(tenantId: UUID, codemp: Int, codlocal: Int, codprod: Int? = null): Map<Pair<Int, String>, BigDecimal> {
        val plantas = plantasDoLocal(tenantId, codemp, codlocal)
        if (plantas.isEmpty()) return emptyMap()
        return GalpaoSaldosEnderecoTable.join(GalpaoEnderecosTable, org.jetbrains.exposed.sql.JoinType.INNER, GalpaoSaldosEnderecoTable.enderecoId, GalpaoEnderecosTable.id)
            .selectAll()
            .where {
                (GalpaoEnderecosTable.plantaId inList plantas) and
                    (if (codprod != null) GalpaoSaldosEnderecoTable.codprod eq codprod else Op.TRUE)
            }
            .groupBy({ it[GalpaoSaldosEnderecoTable.codprod] to it[GalpaoSaldosEnderecoTable.controle] }, { it[GalpaoSaldosEnderecoTable.quantidade] })
            .mapValues { (_, v) -> v.fold(ZERO, BigDecimal::add) }
            .filterValues { it.signum() != 0 }
    }

    private fun enderecadoNoLocal(tenantId: UUID, codemp: Int, codlocal: Int, codprod: Int, controle: String): BigDecimal =
        enderecadoNoLocalTodos(tenantId, codemp, codlocal, codprod)[codprod to controle] ?: ZERO

    private fun enderecadoPorControle(tenantId: UUID, codemp: Int, codlocal: Int, codprod: Int): Map<String, BigDecimal> =
        enderecadoNoLocalTodos(tenantId, codemp, codlocal, codprod).mapKeys { it.key.second }

    private fun codigosEndereco(ids: Collection<UUID>): Map<UUID, String> = if (ids.isEmpty()) emptyMap() else
        GalpaoEnderecosTable.selectAll().where { GalpaoEnderecosTable.id inList ids.distinct() }
            .associate { it[GalpaoEnderecosTable.id] to it[GalpaoEnderecosTable.codigo] }

    private fun nomesProdutos(tenantId: UUID, codprods: Collection<Int>): Map<Int, String> = GalpaoRepository.nomesProdutos(tenantId, codprods.distinct())
}
