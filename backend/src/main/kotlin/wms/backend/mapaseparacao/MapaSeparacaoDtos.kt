package wms.backend.mapaseparacao

import kotlinx.serialization.Serializable

/**
 * Mapa de Separação por Ordem de Carga — porte do componente HTML5/JSP que
 * substituiu o iReport 513 no Sankhya (ver 189_html5Component.zip). Mesma
 * regra de negócio, mesmas tabelas (TGFCAB/TGFORD/TGFVEI/TGFITE/TGFPRO),
 * mesma classificação por TGFPRO.AD_TIPOSEPARACAO — só a apresentação muda
 * (identidade visual do WMS em vez do CSS solto do JSP).
 *
 * Consulta 100% AO VIVO no Sankhya (sem mirror local) — igual a
 * LiberacaoCorteService: relatório de impressão sob demanda, não precisa de
 * sync em background.
 */
@Serializable
data class MapaSeparacaoDto(
    val ordemCarga: Long,
    val notas: List<NotaSeparacaoDto>,
)

@Serializable
data class NotaSeparacaoDto(
    val nunota: Long,
    val codParc: Int,
    val nomeParceiro: String,
    val codVeiculo: Int?,
    val placa: String?,
    val modeloVeiculo: String?,
    /** TGFORD.CODPARCMOTORISTA — motorista é um Parceiro (TGFPAR), não TGFFUN. */
    val codParcMotorista: Int?,
    val nomeMotorista: String?,
    /** TGFORD.PESOMAX — peso máximo da Ordem de Carga (não da nota). */
    val pesoMaxOc: String?,
    val produtosDistintos: Int,
    val quantidadeTotal: String,
    val pesoTotal: String,
    val semClassificacao: Int,
    val categorias: List<CategoriaSeparacaoDto>,
)

/** Item da lista de Ordens de Carga FECHADAS (TGFORD.SITUACAO='F') pra seleção na tela. */
@Serializable
data class OrdemCargaResumoDto(
    val ordemCarga: Long,
    /** TGFORD.DTPREVSAIDA (dd/MM/yyyy), já formatada — ou "—" se ausente. */
    val dataPrevSaida: String,
    val placa: String?,
    val nomeMotorista: String?,
)

@Serializable
data class CategoriaSeparacaoDto(
    /** "1" SECO | "2" REFRIGERADO | "3" CONGELADO | "0" SEM CLASSIFICAÇÃO — ver TGFPRO.AD_TIPOSEPARACAO. */
    val codigo: String,
    val descricao: String,
    val quantidadeTotal: String,
    val pesoTotal: String,
    val itens: List<ItemSeparacaoDto>,
)

@Serializable
data class ItemSeparacaoDto(
    val codProd: Int,
    val descricao: String,
    val controle: String?,
    val unidade: String,
    val quantidade: String,
    val pesoUnitario: String,
    val pesoTotal: String,
)
