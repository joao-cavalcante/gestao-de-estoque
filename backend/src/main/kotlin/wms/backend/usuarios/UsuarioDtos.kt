package wms.backend.usuarios

import kotlinx.serialization.Serializable

@Serializable
data class UsuarioDto(
    val id: String,
    val nome: String,
    val email: String,
    val perfil: String,
    val ativo: Boolean,
    val crachaoCodigo: String? = null,
    /** MANHA | NOITE | null — exibido no header global ("Unidade / Turno"). */
    val turno: String? = null,
)

@Serializable
data class CriarUsuarioRequest(
    val nome: String,
    val email: String,
    val senha: String,
    val perfil: String = "OPERADOR",
    val turno: String? = null,
)

@Serializable
data class AtualizarUsuarioRequest(
    val nome: String? = null,
    val perfil: String? = null,
    val ativo: Boolean? = null,
    /** Mesma convenção dos campos acima: null = não mexe (não dá pra "limpar" o turno por aqui, só trocar). */
    val turno: String? = null,
)

@Serializable
data class AlterarSenhaRequest(
    val senhaAtual: String? = null, // null quando quem altera é ADMINISTRADOR alterando outro usuário
    val senhaNova: String,
)

@Serializable
data class LoginRequest(val email: String, val senha: String)

@Serializable
data class LoginResponse(val token: String, val usuario: UsuarioDto, val tenantSlug: String)

@Serializable
data class DefinirCrachaRequest(val crachaoCodigo: String?)
