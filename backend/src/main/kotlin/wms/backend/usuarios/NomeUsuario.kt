package wms.backend.usuarios

/**
 * Padrão de nome de usuário: primeira letra de cada palavra maiúscula, o resto minúsculo, e as
 * partículas "da, de, do, das, dos, e" em minúsculo (menos no início) — "MATEUS VITOR DE LIMA SILVA"
 * vira "Mateus Vitor de Lima Silva". Espaços extras somem. Aplicado ao criar e ao editar usuário.
 */
object NomeUsuario {
    private val particulas = setOf("da", "de", "do", "das", "dos", "e")

    fun padronizar(nome: String): String =
        nome.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.mapIndexed { i, palavra ->
            val minuscula = palavra.lowercase()
            if (i > 0 && minuscula in particulas) minuscula
            else minuscula.split('-').joinToString("-") { parte -> parte.replaceFirstChar { it.titlecase() } }
        }.joinToString(" ")
}
