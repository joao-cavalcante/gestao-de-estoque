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
