package wms.backend.galpao

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.batchInsert
import org.jetbrains.exposed.sql.javatime.timestamp
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import wms.backend.tarefas.jsonb
import wms.backend.tenancy.TenantTx
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Ver V75__galpao_alertas.sql. */
object GalpaoAlertasTable : Table("app.galpao_alertas") {
    val id = uuid("id")
    val tenantId = uuid("tenant_id")
    val plantaId = uuid("planta_id")
    val chave = text("chave")
    val codigo = text("codigo")
    val severidade = text("severidade")
    val entidade = text("entidade")
    val mensagem = text("mensagem")
    val evidencia = jsonb("evidencia")
    val codprod = integer("codprod").nullable()
    val controle = text("controle").nullable()
    val enderecoId = uuid("endereco_id").nullable()
    val status = text("status")
    val atribuido = text("atribuido").nullable()
    val slaHoras = integer("sla_horas")
    val versao = integer("versao")
    val criadoEm = timestamp("criado_em")
    val atualizadoEm = timestamp("atualizado_em")
    val vistoEm = timestamp("visto_em")
    val reconhecidoEm = timestamp("reconhecido_em").nullable()
    val resolvidoEm = timestamp("resolvido_em").nullable()
    val tratadoPor = text("tratado_por").nullable()

    override val primaryKey = PrimaryKey(id)
}

object StatusAlerta {
    const val ABERTO = "ABERTO"
    const val RECONHECIDO = "RECONHECIDO"
    const val RESOLVIDO = "RESOLVIDO"
    val TODOS = setOf(ABERTO, RECONHECIDO, RESOLVIDO)
}

/** Uma ocorrência encontrada na fotografia (antes de virar/atualizar um alerta). */
data class Ocorrencia(
    val codigo: String,
    /** Objeto da ocorrência (produto, lote, endereço…) — com o código forma a chave de deduplicação. */
    val objeto: String,
    val severidade: String,
    val entidade: String,
    val mensagem: String,
    val evidencia: JsonObject,
    val codprod: Int? = null,
    val controle: String? = null,
    val enderecoId: String? = null,
) {
    val chave: String get() = "$codigo:$objeto"
}

@Serializable
data class AlertaDto(
    val id: String,
    val codigo: String,
    val severidade: String,
    val entidade: String,
    val mensagem: String,
    val evidencia: JsonElement,
    val codprod: Int?,
    val produto: String?,
    val controle: String?,
    val enderecoId: String?,
    val endereco: String?,
    val status: String,
    val atribuido: String?,
    val slaHoras: Int,
    val versao: Int,
    val criadoEm: String,
    val vistoEm: String,
    val reconhecidoEm: String?,
    val resolvidoEm: String?,
    val tratadoPor: String?,
    /** Aberto/reconhecido há mais tempo que o SLA. */
    val slaVencido: Boolean,
    /** A última geração ainda encontrou a condição (false = ocorrência sumiu da fotografia). */
    val vigente: Boolean,
)

@Serializable
data class AlertasPlantaDto(val alertas: List<AlertaDto>, val geradoEm: String?, val aviso: String? = null)

@Serializable
data class TratarAlertaRequest(val status: String, val versao: Int, val atribuido: String? = null)

/**
 * Alertas da planta (SyncService.generateAlerts / ApiController.alerts do Planta Log). Regras puras em [ocorrencias];
 * gravação deduplicada em [gravar]. Sankhya só leitura.
 */
object GalpaoAlertas {
    private val json = Json { encodeDefaults = true }

    /** Última fotografia já transformada em alertas, por planta (evita regravar a cada consulta). */
    private val ultimaGeracao = ConcurrentHashMap<String, Instant>()

    // ─── Regras (puras) ──────────────────────────────────────────────────────

    fun ocorrencias(
        leitura: GalpaoAnalisesService.Leitura,
        analises: AnalisesPlantaDto,
        visao: VisaoPlantaDto,
    ): List<Ocorrencia> {
        val p = analises.parametros
        val r = mutableListOf<Ocorrencia>()
        fun ev(vararg pares: Pair<String, Any?>): JsonObject = buildJsonObject {
            pares.forEach { (k, v) ->
                when (v) {
                    null -> put(k, kotlinx.serialization.json.JsonNull)
                    is Number -> put(k, v)
                    is Boolean -> put(k, v)
                    else -> put(k, v.toString())
                }
            }
        }

        // 1. Linhas de estoque do Sankhya (TGFEST próprio do local).
        for (l in leitura.estoque) {
            val obj = "ESTOQUE:${l.codprod}:${l.controle}"
            val e = ev("codprod" to l.codprod, "controle" to l.controle, "estoque" to l.estoque, "reservado" to l.reservado)
            if (l.estoque < 0) r += Ocorrencia("ESTOQUE_NEGATIVO", obj, "CRITICO", "ESTOQUE", "Saldo contábil negativo", e, l.codprod, l.controle)
            if (l.reservado > l.estoque) r += Ocorrencia("RESERVA_EXCEDENTE", obj, "CRITICO", "ESTOQUE", "Reserva maior que o estoque", e, l.codprod, l.controle)
            if (l.dtval == null) r += Ocorrencia("VALIDADE_AUSENTE", obj, "INFO", "ESTOQUE", "Validade não informada", e, l.codprod, l.controle)
        }

        // 2. Produtos (análise).
        for (a in analises.produtos) {
            val obj = "PRODUTO:${a.codprod}"
            val cod = a.codprod
            if (a.setor == null) r += Ocorrencia("SEM_SETOR", obj, "ATENCAO", "PRODUTO", "Produto sem setor", ev("codprod" to cod), cod)
            if (a.pesoBruto == null || a.m3 == null) {
                r += Ocorrencia(
                    "DIMENSOES_AUSENTES", obj, "INFO", "PRODUTO", "Peso/volume insuficientes para validar todas as capacidades",
                    ev("codprod" to cod, "pesoBruto" to a.pesoBruto, "m3" to a.m3), cod,
                )
            }
            if (a.parado) {
                r += Ocorrencia("ESTOQUE_PARADO", obj, "ATENCAO", "PRODUTO", "Saldo sem venda na janela analítica", ev("codprod" to cod, "estoque" to a.estoque, "dias" to p.dias), cod)
            }
            a.cobertura?.let { c ->
                if (c < p.coberturaMinDias) r += Ocorrencia("COBERTURA_BAIXA", obj, "ATENCAO", "PRODUTO", "Cobertura abaixo do limite", ev("codprod" to cod, "dias" to c, "minimo" to p.coberturaMinDias), cod)
                if (c > p.coberturaMaxDias) r += Ocorrencia("COBERTURA_ALTA", obj, "ATENCAO", "PRODUTO", "Cobertura acima do limite", ev("codprod" to cod, "dias" to c, "maximo" to p.coberturaMaxDias), cod)
            }
            if (a.validade in setOf("VENCIDO", "ATE_30", "31_90")) {
                r += Ocorrencia(
                    "VALIDADE_${a.validade}", obj, if (a.validade == "VENCIDO") "CRITICO" else "ATENCAO", "PRODUTO",
                    "Validade: ${a.validade}", ev("codprod" to cod, "dtval" to a.dtval), cod,
                )
            }
            if (a.disponivel <= 0 && a.demandaDia > 0) {
                r += Ocorrencia("RUPTURA", obj, "CRITICO", "PRODUTO", "Sem disponível com demanda", ev("codprod" to cod, "disponivel" to a.disponivel, "demandaDia" to a.demandaDia), cod)
            }
        }

        // 3. Endereços da planta.
        val tipoDoSetor = visao.geometria?.setores.orEmpty().associate { it.codigo to it.tipoSeparacao }
        val setorProduto = analises.produtos.associate { it.codprod to it.setor }
        for (e in visao.enderecos) {
            val obj = "END:${e.id}"
            val base = ev("endereco" to e.codigo, "ocupacao" to e.ocupacao, "base" to e.baseOcupacao, "quantidade" to e.quantidade)
            if (e.ocupacao == null && (e.capQtd == null && e.capPeso == null && e.capVolume == null || e.quantidade > 0)) {
                r += Ocorrencia("CAPACIDADE_AUSENTE", obj, "ATENCAO", "ENDERECO", "Capacidade não parametrizada ou sem dados suficientes", base, enderecoId = e.id)
            } else if ((e.ocupacao ?: 0.0) > 1 + 1e-9) {
                r += Ocorrencia("SOBRECAPACIDADE", obj, "CRITICO", "ENDERECO", "Endereço acima de 100% de capacidade", base, enderecoId = e.id)
            }
            if (e.bloqueado && e.quantidade != 0.0) {
                r += Ocorrencia("BLOQUEADO_COM_SALDO_TAREFA", obj, "ATENCAO", "ENDERECO", "Endereço bloqueado possui saldo ou tarefa", base, enderecoId = e.id)
            }
            val tipo = tipoDoSetor[e.setor]
            if (tipo != null) {
                for (s in e.saldos) {
                    val sp = setorProduto[s.codprod] ?: continue
                    if (sp != tipo) {
                        r += Ocorrencia(
                            "SETOR_INCOMPATIVEL", "$obj:PROD:${s.codprod}", "CRITICO", "ENDERECO", "Produto em setor incompatível",
                            ev("endereco" to e.codigo, "setorEndereco" to e.setor, "tipoSetor" to tipo, "codprod" to s.codprod, "setorProduto" to sp),
                            s.codprod, s.controle, e.id,
                        )
                    }
                }
            }
        }

        // 4. Conciliação contábil (TGFEST) × endereçado na planta, por produto + controle.
        val contabil = leitura.estoque.groupBy { it.codprod to it.controle }.mapValues { (_, v) -> v.sumOf { it.estoque } }
        val enderecado = visao.enderecos.flatMap { it.saldos }.groupBy { it.codprod to it.controle }.mapValues { (_, v) -> v.sumOf { it.quantidade } }
        for (k in contabil.keys + enderecado.keys) {
            val c = contabil[k] ?: 0.0
            val en = enderecado[k] ?: 0.0
            val obj = "ESTOQUE:${k.first}:${k.second}"
            val e = ev("codprod" to k.first, "controle" to k.second, "contabil" to c, "enderecado" to en, "diferenca" to c - en)
            if (c > 0 && en == 0.0) r += Ocorrencia("SALDO_SEM_ENDERECO", obj, "ATENCAO", "ESTOQUE", "Saldo contábil sem distribuição física", e, k.first, k.second)
            if (kotlin.math.abs(c - en) > 1e-6) r += Ocorrencia("RECONCILIACAO", obj, "ATENCAO", "ESTOQUE", "Diferença entre contábil e espacial", e, k.first, k.second)
        }
        return r.distinctBy { it.chave }
    }

    // ─── Gravação ────────────────────────────────────────────────────────────

    /** Gera os alertas desta fotografia (uma vez por leitura do Sankhya). Devolve quantos foram criados/reabertos. */
    fun gerarSeNovo(tenantId: UUID, plantaId: UUID, lidoEm: Instant, sla: Int, ocorrencias: () -> List<Ocorrencia>): Int {
        val chave = "$tenantId:$plantaId"
        if (ultimaGeracao[chave] == lidoEm) return 0
        val n = gravar(tenantId, plantaId, ocorrencias(), sla, Instant.now())
        ultimaGeracao[chave] = lidoEm
        return n
    }

    fun gravar(tenantId: UUID, plantaId: UUID, lista: List<Ocorrencia>, sla: Int, agora: Instant): Int = TenantTx.run(tenantId, 60_000) {
        val existentes = GalpaoAlertasTable.selectAll().where { GalpaoAlertasTable.plantaId eq plantaId }
            .associateBy { it[GalpaoAlertasTable.chave] }
        val novos = mutableListOf<Ocorrencia>()
        val vistos = mutableListOf<UUID>()
        var reabertos = 0
        for (o in lista) {
            val ex = existentes[o.chave]
            when {
                ex == null -> novos += o
                ex[GalpaoAlertasTable.status] != StatusAlerta.RESOLVIDO -> vistos += ex[GalpaoAlertasTable.id]
                else -> {
                    // Resolvido e a condição continua: volta a ABERTO (igual ao Planta Log).
                    val id = ex[GalpaoAlertasTable.id]
                    GalpaoAlertasTable.update({ GalpaoAlertasTable.id eq id }) {
                        it[status] = StatusAlerta.ABERTO
                        it[severidade] = o.severidade
                        it[mensagem] = o.mensagem
                        it[evidencia] = o.evidencia.toString()
                        it[atribuido] = null
                        it[reconhecidoEm] = null
                        it[resolvidoEm] = null
                        it[versao] = ex[GalpaoAlertasTable.versao] + 1
                        it[atualizadoEm] = agora
                        it[vistoEm] = agora
                    }
                    reabertos++
                }
            }
        }
        // Vistos de novo: só a data (a evidência original fica, como no Planta Log).
        vistos.chunked(1000).forEach { lote ->
            exec("UPDATE app.galpao_alertas SET visto_em = now() WHERE id IN (${lote.joinToString(",") { "'$it'" }})")
        }
        GalpaoAlertasTable.batchInsert(novos, shouldReturnGeneratedValues = false) { o ->
            this[GalpaoAlertasTable.id] = UUID.randomUUID()
            this[GalpaoAlertasTable.tenantId] = tenantId
            this[GalpaoAlertasTable.plantaId] = plantaId
            this[GalpaoAlertasTable.chave] = o.chave
            this[GalpaoAlertasTable.codigo] = o.codigo
            this[GalpaoAlertasTable.severidade] = o.severidade
            this[GalpaoAlertasTable.entidade] = o.entidade
            this[GalpaoAlertasTable.mensagem] = o.mensagem
            this[GalpaoAlertasTable.evidencia] = o.evidencia.toString()
            this[GalpaoAlertasTable.codprod] = o.codprod
            this[GalpaoAlertasTable.controle] = o.controle
            this[GalpaoAlertasTable.enderecoId] = o.enderecoId?.let(UUID::fromString)
            this[GalpaoAlertasTable.status] = StatusAlerta.ABERTO
            this[GalpaoAlertasTable.slaHoras] = sla
            this[GalpaoAlertasTable.versao] = 1
            this[GalpaoAlertasTable.criadoEm] = agora
            this[GalpaoAlertasTable.atualizadoEm] = agora
            this[GalpaoAlertasTable.vistoEm] = agora
        }
        novos.size + reabertos
    }

    // ─── Consulta e tratamento ───────────────────────────────────────────────

    fun listar(tenantId: UUID, plantaId: UUID, agora: Instant = Instant.now()): List<AlertaDto> = TenantTx.run(tenantId, 30_000) {
        val linhas = GalpaoAlertasTable.selectAll().where { GalpaoAlertasTable.plantaId eq plantaId }
            .orderBy(GalpaoAlertasTable.criadoEm to SortOrder.DESC).toList()
        val ultimaVista = linhas.maxOfOrNull { it[GalpaoAlertasTable.vistoEm] }
        val nomes = GalpaoRepository.nomesProdutos(tenantId, linhas.mapNotNull { it[GalpaoAlertasTable.codprod] }.distinct())
        val enderecos = GalpaoEnderecosTable.select(GalpaoEnderecosTable.id, GalpaoEnderecosTable.codigo)
            .where { GalpaoEnderecosTable.plantaId eq plantaId }
            .associate { it[GalpaoEnderecosTable.id] to it[GalpaoEnderecosTable.codigo] }
        linhas.map { r ->
            val st = r[GalpaoAlertasTable.status]
            val sla = r[GalpaoAlertasTable.slaHoras]
            val desde = r[GalpaoAlertasTable.atualizadoEm]
            AlertaDto(
                id = r[GalpaoAlertasTable.id].toString(),
                codigo = r[GalpaoAlertasTable.codigo],
                severidade = r[GalpaoAlertasTable.severidade],
                entidade = r[GalpaoAlertasTable.entidade],
                mensagem = r[GalpaoAlertasTable.mensagem],
                evidencia = runCatching { json.parseToJsonElement(r[GalpaoAlertasTable.evidencia]) }.getOrDefault(JsonPrimitive(r[GalpaoAlertasTable.evidencia])),
                codprod = r[GalpaoAlertasTable.codprod],
                produto = r[GalpaoAlertasTable.codprod]?.let { nomes[it] },
                controle = r[GalpaoAlertasTable.controle],
                enderecoId = r[GalpaoAlertasTable.enderecoId]?.toString(),
                endereco = r[GalpaoAlertasTable.enderecoId]?.let { enderecos[it] },
                status = st,
                atribuido = r[GalpaoAlertasTable.atribuido],
                slaHoras = sla,
                versao = r[GalpaoAlertasTable.versao],
                criadoEm = r[GalpaoAlertasTable.criadoEm].toString(),
                vistoEm = r[GalpaoAlertasTable.vistoEm].toString(),
                reconhecidoEm = r[GalpaoAlertasTable.reconhecidoEm]?.toString(),
                resolvidoEm = r[GalpaoAlertasTable.resolvidoEm]?.toString(),
                tratadoPor = r[GalpaoAlertasTable.tratadoPor],
                slaVencido = st != StatusAlerta.RESOLVIDO && Duration.between(desde, agora).toHours() >= sla,
                vigente = ultimaVista != null && !r[GalpaoAlertasTable.vistoEm].isBefore(ultimaVista.minusSeconds(120)),
            )
        }
    }

    sealed interface ResultadoTratamento {
        data class Ok(val alerta: AlertaDto) : ResultadoTratamento
        data class Erro(val conflito: Boolean, val mensagem: String) : ResultadoTratamento
    }

    /** Muda o status com controle de versão (salvar sobre versão velha = conflito, recarregar). */
    fun tratar(tenantId: UUID, plantaId: UUID, alertaId: UUID, req: TratarAlertaRequest, usuario: String): ResultadoTratamento {
        if (req.status !in StatusAlerta.TODOS) return ResultadoTratamento.Erro(false, "Status inválido")
        val agora = Instant.now()
        val alterados = TenantTx.run(tenantId) {
            GalpaoAlertasTable.update({
                (GalpaoAlertasTable.id eq alertaId) and (GalpaoAlertasTable.plantaId eq plantaId) and (GalpaoAlertasTable.versao eq req.versao)
            }) {
                it[status] = req.status
                it[atribuido] = req.atribuido?.trim()?.takeIf { s -> s.isNotEmpty() } ?: usuario
                it[tratadoPor] = usuario
                it[atualizadoEm] = agora
                it[versao] = req.versao + 1
                when (req.status) {
                    StatusAlerta.RECONHECIDO -> it[reconhecidoEm] = agora
                    StatusAlerta.RESOLVIDO -> it[resolvidoEm] = agora
                    else -> {
                        it[reconhecidoEm] = null
                        it[resolvidoEm] = null
                    }
                }
            }
        }
        if (alterados == 0) return ResultadoTratamento.Erro(true, "Alerta atualizado por outra pessoa ou não encontrado; recarregue")
        val dto = listar(tenantId, plantaId).firstOrNull { it.id == alertaId.toString() }
            ?: return ResultadoTratamento.Erro(false, "Alerta não encontrado")
        return ResultadoTratamento.Ok(dto)
    }
}
