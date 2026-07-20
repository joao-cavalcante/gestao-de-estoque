package wms.backend.usuarios

import kotlinx.serialization.Serializable

@Serializable
data class UsuarioDto(
    val id: String,
    val nome: String,
    val email: String,
    val perfil: String,
    val ativo: Boolean,
)

@Serializable
data class CriarUsuarioRequest(
    val nome: String,
    val email: String,
    val senha: String,
    val perfil: String = "OPERADOR",
)

@Serializable
data class AtualizarUsuarioRequest(
    val nome: String? = null,
    val perfil: String? = null,
    val ativo: Boolean? = null,
)

@Serializable
data class AlterarSenhaRequest(
    val senhaAtual: String? = null, // null quando quem altera é ADMINISTRADOR alterando outro usuário
    val senhaNova: String,
)

@Serializable
data class LoginRequest(val email: String, val senha: String)

@Serializable
data class LoginResponse(val token: String, val usuario: UsuarioDto)
