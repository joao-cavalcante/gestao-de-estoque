package wms.backend.master

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import wms.backend.configconferencia.ConfigConferenciaTable
import wms.backend.erp.LoadRecordsRequest
import wms.backend.erp.SankhyaAuthService
import wms.backend.erp.SankhyaDbExplorerClient
import wms.backend.erp.SankhyaLoadRecordsClient
import wms.backend.liberacaocorte.LiberadorServico
import wms.backend.tenancy.Modulos
import wms.backend.tenancy.SyncEstadoTable
import wms.backend.tenancy.TenantDto
import wms.backend.tenancy.TenantRepository
import wms.backend.tenancy.TenantTx
import wms.backend.tipooperacao.TipoOperacaoDestinosTable
import wms.backend.tipooperacao.TipoOperacaoTable
import wms.backend.usuarios.SankhyaUsuarioService
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import java.util.UUID

/**
 * Campos personalizados (AD_*) que o código lê ou grava hoje — auditoria de 30/10/2026. A declaração por tenant
 * (perfil de campos) ainda não existe: este catálogo diz o que a instalação PRECISA ter no dicionário do Sankhya.
 * [modulo] null = lido por todo tenant (débito M01/M05/M06: sem o campo a consulta tende a falhar).
 */
object CamposPersonalizados {
    @Serializable
    data class Campo(val tabela: String, val campo: String, val uso: String, val modulo: String? = null, val gravacao: Boolean = false)

    val CATALOGO = listOf(
        Campo("TGFCAB", "AD_ENTREGA", "Fila: pedido de entrega só com Ordem de Carga; pins e contagem da TV"),
        Campo("TGFCAB", "AD_EXPRESS", "Fila/Mapa/TV: modalidade Express (visual)"),
        Campo("TGFCAB", "AD_RETIRA", "Fila/Mapa/TV: modalidade Cliente retira (visual)"),
        Campo("TGFCAB", "AD_TURNOENTREGA", "Fila: turno de entrega no card (visual)"),
        Campo("TGFPRO", "AD_TIPOSEPARACAO", "Conferência (lido sempre) e Mapa de Separação por temperatura"),
        Campo("TGFPRO", "AD_PESAVEL", "Pesável pelo produto", modulo = Modulos.PESAVEL_POR_PRODUTO),
        Campo("TGFCAB", "AD_FEEDBACK", "Faturamento: frase \"Faturado pelo WMS\" gravada no pedido (falha só vai pro log)", gravacao = true),
    )
}

@Serializable
data class ItemDiagnosticoDto(
    val grupo: String,
    val titulo: String,
    /** OK | ALERTA | ERRO | NAO_VERIFICADO */
    val situacao: String,
    val detalhe: String,
)

@Serializable
data class DiagnosticoDto(val tenantId: String, val tenantSlug: String, val executadoEm: String, val itens: List<ItemDiagnosticoDto>)

/**
 * Diagnóstico da instalação de UM tenant — só leituras: autenticação, SELECT pelo DbExplorer, loadRecords sem
 * resultado (NUNOTA = -1) e o espelho local. Nada é gravado no Sankhya nem no WMS; nenhuma rotina fiscal ou
 * comercial é chamada. O tenant vem do id resolvido no servidor (nunca de slug digitado).
 */
object DiagnosticoInstalacao {

    suspend fun executar(tenant: TenantDto): DiagnosticoDto {
        val tenantId = UUID.fromString(tenant.id)
        val slug = tenant.slug
        val itens = mutableListOf<ItemDiagnosticoDto>()
        fun add(grupo: String, titulo: String, situacao: String, detalhe: String) { itens += ItemDiagnosticoDto(grupo, titulo, situacao, detalhe) }

        val conexao = tenant.erpConnections.firstOrNull { it.erpType == "sankhya" }
        when {
            conexao == null -> add("Integração", "Conexão Sankhya cadastrada", "ERRO", "sem conexão Sankhya — cadastre na aba Integração")
            !conexao.ativo -> add("Integração", "Conexão Sankhya cadastrada", "ALERTA", "conexão desativada — sincronização parada")
            !conexao.credenciaisConfiguradas -> add("Integração", "Conexão Sankhya cadastrada", "ERRO", "credenciais não informadas")
            else -> add("Integração", "Conexão Sankhya cadastrada", "OK", "${conexao.baseUrl} · dialeto ${conexao.dialect ?: "não informado"}")
        }
        if (conexao != null && conexao.dialect != null && conexao.dialect != "ORACLE") {
            add("Integração", "Banco do Sankhya", "ALERTA", "as consultas do WMS são escritas para Oracle (auditoria M29); dialeto ${conexao.dialect} não homologado")
        }

        val autenticou = if (conexao == null || !conexao.credenciaisConfiguradas) {
            add("Integração", "Autenticação no Sankhya", "NAO_VERIFICADO", "sem credenciais"); false
        } else {
            runCatching { SankhyaAuthService.obterTokenValido(slug) }
                .onSuccess { add("Integração", "Autenticação no Sankhya", "OK", "token obtido") }
                .onFailure { add("Integração", "Autenticação no Sankhya", "ERRO", it.message ?: "falha") }
                .isSuccess
        }

        if (autenticou) {
            runCatching { SankhyaDbExplorerClient.executarQuery(slug, "SELECT 1 AS OK FROM DUAL") }
                .onSuccess { add("Rotinas", "DbExplorer (consultas SQL)", "OK", "SELECT executado") }
                .onFailure { add("Rotinas", "DbExplorer (consultas SQL)", "ERRO", "o usuário de integração precisa de acesso ao DbExplorer: ${it.message}") }
            runCatching {
                SankhyaLoadRecordsClient.loadRecords(slug, LoadRecordsRequest("CabecalhoNota", listOf("NUNOTA"), "NUNOTA = -1"))
            }
                .onSuccess { add("Rotinas", "DatasetSP.loadRecords (Fila)", "OK", "consulta sem resultado executada") }
                .onFailure { add("Rotinas", "DatasetSP.loadRecords (Fila)", "ERRO", it.message ?: "falha") }
            verificarCampos(slug, tenant, ::add)
            runCatching {
                SankhyaDbExplorerClient.executarQuery(slug, "SELECT COUNT(*) AS QTD FROM TGFVOL WHERE UTILICONFPESO = 'S'")
                    .firstOrNull()?.get("QTD")?.toBigDecimalOrNull()?.toInt() ?: 0
            }.onSuccess { qtd ->
                if (qtd > 0) add("Configuração", "Unidades pesáveis (TGFVOL.UTILICONFPESO)", "OK", "$qtd unidade(s) marcada(s)")
                else add("Configuração", "Unidades pesáveis (TGFVOL.UTILICONFPESO)", "ALERTA", "nenhuma unidade marcada — pesagem por unidade não vai ocorrer")
            }.onFailure { add("Configuração", "Unidades pesáveis (TGFVOL.UTILICONFPESO)", "NAO_VERIFICADO", it.message ?: "falha") }
        } else {
            add("Rotinas", "Rotinas e campos do Sankhya", "NAO_VERIFICADO", "sem autenticação no Sankhya")
        }

        verificarEspelhoLocal(tenantId, ::add)
        verificarModulosEParametros(tenantId, conexao?.modulos.orEmpty(), ::add)

        val sync = withContext(Dispatchers.IO) {
            transaction { SyncEstadoTable.selectAll().where { SyncEstadoTable.tenantId eq tenantId }.singleOrNull() }
        }
        when {
            sync == null -> add("Sincronização", "Fila de Conferência", "ALERTA", "tenant ainda não entrou na fila de sincronização (status ou conexão inativos)")
            sync[SyncEstadoTable.falhasConsecutivas] > 0 ->
                add("Sincronização", "Fila de Conferência", "ERRO", "${sync[SyncEstadoTable.falhasConsecutivas]} falha(s) seguidas: ${sync[SyncEstadoTable.ultimoErro]?.take(300)}")
            else -> add("Sincronização", "Fila de Conferência", "OK", "último sucesso: ${sync[SyncEstadoTable.ultimoSyncSucessoEm] ?: "nunca"}")
        }

        return DiagnosticoDto(tenant.id ?: "", slug, java.time.Instant.now().toString(), itens)
    }

    /** Dicionário do Sankhya (TDDCAM): cada AD_* do catálogo existe? Obrigatoriedade conforme os módulos do tenant. */
    private suspend fun verificarCampos(slug: String, tenant: TenantDto, add: (String, String, String, String) -> Unit) {
        val modulos = tenant.erpConnections.flatMap { it.modulos }.toSet()
        val filtro = CamposPersonalizados.CATALOGO.joinToString(" OR ") { "(NOMETAB = '${it.tabela}' AND NOMECAMPO = '${it.campo}')" }
        val existentes = runCatching {
            SankhyaDbExplorerClient.executarQuery(slug, "SELECT NOMETAB, NOMECAMPO FROM TDDCAM WHERE $filtro")
                .map { "${it["NOMETAB"]?.trim()}.${it["NOMECAMPO"]?.trim()}" }.toSet()
        }.getOrElse {
            add("Campos personalizados", "Dicionário de dados (TDDCAM)", "NAO_VERIFICADO", it.message ?: "falha")
            return
        }
        for (c in CamposPersonalizados.CATALOGO) {
            val nome = "${c.tabela}.${c.campo}"
            val exigido = c.modulo == null || c.modulo in modulos
            val situacao = when {
                nome in existentes -> "OK"
                !exigido -> "OK"
                c.gravacao -> "ALERTA"
                else -> "ERRO"
            }
            val detalhe = when {
                nome in existentes -> "existe · ${c.uso}"
                !exigido -> "não existe, e só é usado com o módulo ${c.modulo} (desligado)"
                c.gravacao -> "não existe — a gravação falha a cada faturamento (só log) · ${c.uso}"
                else -> "não existe e hoje é lido por todo tenant — a consulta tende a falhar · ${c.uso}"
            }
            add("Campos personalizados", nome, situacao, detalhe)
        }
    }

    private suspend fun verificarEspelhoLocal(tenantId: UUID, add: (String, String, String, String) -> Unit) {
        val (tops, destinos, nuccos) = withContext(Dispatchers.IO) {
            TenantTx.run(tenantId) {
                Triple(
                    TipoOperacaoTable.selectAll().where { TipoOperacaoTable.tenantId eq tenantId }.count(),
                    TipoOperacaoDestinosTable.selectAll().where { TipoOperacaoDestinosTable.tenantId eq tenantId }.count(),
                    ConfigConferenciaTable.selectAll().where { ConfigConferenciaTable.tenantId eq tenantId }.count(),
                )
            }
        }
        add("Configuração", "TOPs com conferência", if (tops > 0) "OK" else "ALERTA", if (tops > 0) "$tops TOP(s) no espelho" else "nenhuma TOP sincronizada ainda")
        add("Configuração", "TOPs de destino do faturamento (TGFREP)", if (destinos > 0) "OK" else "ALERTA",
            if (destinos > 0) "$destinos destino(s)" else "sem restrição de destino — faturamento pela fila não terá TOP")
        add("Configuração", "Configurações de conferência (NUCCO)", if (nuccos > 0) "OK" else "ALERTA", if (nuccos > 0) "$nuccos NUCCO(s)" else "nenhuma configuração sincronizada")
    }

    private suspend fun verificarModulosEParametros(tenantId: UUID, modulos: List<String>, add: (String, String, String, String) -> Unit) {
        val desconhecidos = modulos.filter { it !in Modulos.TODOS }
        add("Módulos", "Módulos habilitados", if (desconhecidos.isEmpty()) "OK" else "ALERTA",
            (if (modulos.isEmpty()) "nenhum (instalação padrão)" else modulos.joinToString()) +
                (if (desconhecidos.isEmpty()) "" else " · desconhecidos: ${desconhecidos.joinToString()}"))

        val p = withContext(Dispatchers.IO) { TenantRepository.parametros(tenantId) }
        val erros = p.validar()
        add("Parâmetros", "Parâmetros operacionais", if (erros.isEmpty()) "OK" else "ERRO", if (erros.isEmpty()) "válidos" else erros.joinToString("; "))
        if (Modulos.LIBERACAO_HERDADA in modulos && p.liberacaoHerdada.eventos.isEmpty() && !p.liberacaoHerdada.todosEventos) {
            add("Parâmetros", "Liberação herdada", "ALERTA", "módulo ligado sem eventos — a regra não roda")
        }
        val liberador = withContext(Dispatchers.IO) {
            LiberadorServico.resolver(
                credencialTenant = { TenantRepository.obterCredencialLiberador(tenantId) },
                fallbackPermitido = p.liberador.fallbackVariavelAmbiente,
                variavel = System::getenv,
            )
        }
        when {
            liberador.credencial?.origem == "tenant" -> add("Parâmetros", "Liberador de serviço", "OK", "credencial do tenant gravada")
            liberador.credencial != null -> add("Parâmetros", "Liberador de serviço", "ALERTA", "usando a variável de ambiente (transição legada) — grave a credencial do tenant")
            else -> add("Parâmetros", "Liberador de serviço", "ALERTA", "${liberador.motivo} — auto-liberação de pesável e liberação herdada ficam manuais")
        }
        add("Parâmetros", "Tolerância de pesável sem NUCCO", "OK",
            p.toleranciaPesavel.semConfiguracaoNucco?.let { "política da instalação: $it" } ?: "padrão do produto: não libera sozinho")
        add("Parâmetros", "Fuso do faturamento", "OK", p.fuso ?: "padrão America/Sao_Paulo")
        add("Parâmetros", "Criação de usuários no Sankhya", "OK", SankhyaUsuarioService.motivoIndisponivel(p.usuarioSankhya) ?: "habilitada")
    }
}
