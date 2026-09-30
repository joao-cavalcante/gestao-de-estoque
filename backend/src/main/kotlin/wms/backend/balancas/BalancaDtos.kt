package wms.backend.balancas

import kotlinx.serialization.Serializable

@Serializable
data class BalancaDto(
    val id: String,
    val nome: String,
    val fabricante: String? = null,
    val tipoComunicacao: String,
    val portaCom: String? = null,
    val baudRate: Int? = null,
    val dataBits: Int? = null,
    val paridade: String? = null,
    val stopBits: Int? = null,
    val protocoloSerial: String? = null,
    val ip: String? = null,
    val porta: Int? = null,
    val rota: String? = null,
    val ativo: Boolean,
    /** Qtd. de usuários autorizados (só na listagem da tela de balanças) — 0 = sem restrição. */
    val usuariosAutorizados: Int = 0,
)

@Serializable
data class SalvarBalancaRequest(
    val nome: String,
    val fabricante: String? = null,
    val tipoComunicacao: String,
    val portaCom: String? = null,
    val baudRate: Int? = null,
    val dataBits: Int? = null,
    val paridade: String? = null,
    val stopBits: Int? = null,
    val protocoloSerial: String? = null,
    val ip: String? = null,
    val porta: Int? = null,
    val rota: String? = null,
    val ativo: Boolean = true,
)

@Serializable
data class PesoCapturadoDto(val peso: Double)

/** Usuários autorizados de um recurso (balança/TOP) — lista vazia = sem restrição. */
@Serializable
data class UsuariosAutorizadosDto(val usuarioIds: List<String> = emptyList())
