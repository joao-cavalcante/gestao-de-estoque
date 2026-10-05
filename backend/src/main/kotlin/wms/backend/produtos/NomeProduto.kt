package wms.backend.produtos

/**
 * Nome do produto exibido nas telas e etiquetas: "DESCRIÇÃO - COMPLEMENTO" (TGFPRO.DESCRPROD +
 * TGFPRO.COMPLDESC). Regra do usuário (05/10/2026): toda tela mostra o complemento, separado por " - ".
 * Sem complemento -> só a descrição; sem descrição -> null (quem chama decide o fallback).
 */
object NomeProduto {
    /**
     * Ordem alfabética das listas de produto (regra do usuário, 05/10/2026): pt-BR, ignora
     * maiúscula/acento ("AÇÚCAR" junto de "ACUCAR"). Nulo vai pro fim.
     */
    val ORDEM: Comparator<String?> = run {
        val collator = java.text.Collator.getInstance(java.util.Locale.forLanguageTag("pt-BR")).apply {
            strength = java.text.Collator.PRIMARY
        }
        nullsLast(Comparator { a: String, b: String -> collator.compare(a.trim(), b.trim()) })
    }

    fun formatar(descricao: String?, complemento: String?): String? {
        val desc = descricao?.trim()?.takeIf { it.isNotEmpty() }
        val compl = complemento?.trim()?.takeIf { it.isNotEmpty() }
        return when {
            desc == null -> compl
            compl == null -> desc
            else -> "$desc - $compl"
        }
    }

    /**
     * "Prod.:" da OBSERVACAO da liberação de corte do Sankhya ("X, Complem.: Y, Controle: Z")
     * no mesmo formato das outras telas ("X - Y · Z").
     */
    fun daObservacaoLiberacao(produto: String?): String? =
        produto?.replace(", Complem.: ", " - ")?.replace(", Controle: ", " · ")
}
