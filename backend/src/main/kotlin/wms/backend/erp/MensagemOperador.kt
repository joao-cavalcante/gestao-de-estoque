package wms.backend.erp

/**
 * Mensagens de erro da emissão da nota em linguagem de operador (usuário, 09/11/2026: "precisa que fique claro na
 * cara dele"). Toda mensagem sai em duas partes: o PROBLEMA e, depois de [SEPARADOR], O QUE FAZER. O texto técnico do
 * Sankhya continua no log do backend (AVISO: ...), não na tela.
 *
 * - [erroSankhya]: recusa do Sankhya ao faturar/confirmar (ORA-20101, regra de negócio, tempo esgotado...).
 * - [rejeicaoSefaz]: código de rejeição da SEFAZ (TGFREJNFE.CODREJEICAO) da nota já confirmada.
 */
object MensagemOperador {

    /** Separa o problema da ação — o front mostra cada parte numa linha. */
    const val SEPARADOR = " O que fazer: "

    private fun msg(problema: String, acao: String) = problema.trim().trimEnd('.') + "." + SEPARADOR + acao.trim().trimEnd('.') + "."

    /** Já traduzida (ex.: lida de novo do banco) — não traduz de novo. */
    fun jaTraduzida(texto: String) = SEPARADOR.trim() in texto

    /** Tira o prefixo técnico ("Sankhya X falhou para tenant 'y': ", "ORA-20101: ", "Erro interno: "). */
    fun semPrefixoTecnico(bruto: String): String {
        var s = bruto
        if ("falhou para tenant" in s) s = s.substringAfter("falhou para tenant").substringAfter("': ", s.substringAfter("falhou para tenant"))
        s = s.replace(Regex("""ORA-\d{5}:\s*"""), "").removePrefix("Erro interno: ")
        // O Oracle às vezes cola o stack (ORA-06512 "at line ...") depois da mensagem.
        s = s.substringBefore("ORA-06512").substringBefore(" at \"")
        return s.replace(Regex("""\s+"""), " ").trim().trimEnd('.', ':', ' ').ifEmpty { bruto.trim() }
    }

    private val NOTA_NAO_CONFIRMADA = Regex("""^nota (\d+) gerada mas não confirmada \((.*)\)\s*—.*$""", RegexOption.DOT_MATCHES_ALL)

    /** Recusa do Sankhya (faturar/confirmar) → problema + o que fazer. null/vazio → null. */
    fun erroSankhya(bruto: String?): String? {
        if (bruto.isNullOrBlank()) return null
        if (jaTraduzida(bruto)) return bruto
        // "nota 82168 gerada mas não confirmada (motivo) — confirme na Central de Notas" (confirmarNotasGeradas).
        val partes = bruto.split(Regex(";\\s*(?=nota \\d+ gerada)")).map { it.trim() }.filter { it.isNotEmpty() }
        if (partes.size > 1) return partes.joinToString("\n") { erroSankhya(it) ?: it }
        NOTA_NAO_CONFIRMADA.matchEntire(bruto.trim())?.let { m ->
            val nota = m.groupValues[1]
            val motivo = m.groupValues[2]
            if ("liberação pendente no Sankhya" in motivo) {
                return msg(
                    "A nota $nota foi gerada, mas ficou PARADA esperando uma liberação no Sankhya (preço, desconto ou crédito) " +
                        "que o sistema não conseguiu liberar sozinho",
                    "peça ao liberador para liberar a nota $nota no Sankhya e depois clique em \"Confirmar nota\" aqui no card",
                )
            }
            val (problema, acao) = traduzirMotivo(motivo)
            return msg("A nota $nota foi gerada, mas NÃO foi confirmada: ${problema.replaceFirstChar { it.lowercase() }}", acao)
        }
        val (problema, acao) = traduzirMotivo(bruto)
        return msg(problema, acao)
    }

    private fun traduzirMotivo(bruto: String): Pair<String, String> {
        val t = semPrefixoTecnico(bruto)
        val baixo = t.lowercase()
        fun grupo(r: String) = Regex(r, RegexOption.IGNORE_CASE).find(t)?.groupValues?.getOrNull(1)
        return when {
            "venda a prazo bloqueada" in baixo -> {
                val parc = grupo("""parceiro\s+(\d+)""")?.let { " (cód. $it)" } ?: ""
                "Cliente$parc BLOQUEADO para venda a prazo no financeiro" to
                    "peça ao financeiro para liberar o cliente ou trocar o pedido para pagamento à vista. Depois tente de novo"
            }
            "tipo de negocia" in baixo && ("ativo" in baixo || "data de altera" in baixo) -> {
                val tipo = grupo("""TIPO DE NEGOCIA\S*\s+(\d+)""")?.let { " $it" } ?: ""
                "A forma de pagamento (tipo de negociação$tipo) do pedido está INATIVA ou foi alterada depois do pedido" to
                    "peça ao comercial/faturamento para trocar o tipo de negociação no pedido. Depois tente de novo"
            }
            "tipo de negocia" in baixo && "não autorizado" in baixo ->
                "A forma de pagamento do pedido NÃO É PERMITIDA para este cliente" to
                    "peça ao comercial para trocar o tipo de negociação do pedido ou ajustar o grupo de autorização do cliente. Depois tente de novo"
            "liberação reprovada" in baixo || "liberacao reprovada" in baixo ->
                "Uma liberação deste pedido foi REPROVADA no Sankhya — a nota não pode sair" to
                    "fale com o comercial/liberador. Não carregue a mercadoria até resolver"
            "liberação pendente" in baixo || "liberacao pendente" in baixo ->
                "O pedido tem LIBERAÇÃO PENDENTE no Sankhya (preço, desconto ou crédito)" to
                    "peça ao liberador para liberar o pedido no Sankhya. Depois tente de novo"
            "limite de cr" in baixo ->
                "Cliente SEM LIMITE DE CRÉDITO para este valor" to
                    "peça ao financeiro/liberador para liberar o crédito no Sankhya. Depois tente de novo"
            "sendo processada neste momento" in baixo -> {
                val quem = grupo("""pelo usu[aá]rio\s+([^,]+?)(?:,|\s+no processo|$)""")?.trim()?.let { " ($it)" } ?: ""
                "Alguém$quem está com esta nota ABERTA no Sankhya agora" to
                    "peça para fechar a tela da nota no Sankhya e tente de novo em 1 minuto"
            }
            "campo fone" in baixo || "telefone" in baixo ->
                "O TELEFONE do cliente está errado no cadastro" to
                    "peça ao cadastro para corrigir o telefone do cliente (DDD + número). Depois tente de novo"
            "não esta pendente" in baixo || "não está pendente" in baixo || "já foi faturada" in baixo ->
                "Este pedido JÁ FOI FATURADO (ou não está mais pendente) no Sankhya" to
                    "atualize a fila e confira a nota do pedido. Não fature de novo"
            "estoque insuficiente" in baixo || "sem estoque" in baixo ->
                "FALTA ESTOQUE no Sankhya para algum produto do pedido" to
                    "chame o responsável pelo estoque para acertar o saldo no Sankhya. Depois tente de novo"
            "timed out" in baixo || "timeout" in baixo || "tempo esgotado" in baixo || "não confirmou o faturamento" in baixo ->
                "O Sankhya DEMOROU para responder e não deu para saber se a nota saiu" to
                    "espere 1 minuto, atualize a fila e veja se a nota apareceu antes de tentar de novo"
            "corte aguardando liberação" in baixo || "corte antes de faturar" in baixo ->
                "Tem CORTE aguardando liberação neste pedido" to
                    "peça ao liberador para liberar o corte. Depois gere a nota"
            "confirmação que o wms não sabe responder" in baixo ->
                "O Sankhya pediu uma confirmação que a Torre não faz sozinha" to
                    "gere esta nota pela Central de Notas do Sankhya ou chame o suporte"
            else ->
                "O Sankhya recusou: $t" to "chame o faturamento para resolver no Sankhya. Depois tente de novo"
        }
    }

    /**
     * Rejeição da SEFAZ (código cStat de TGFREJNFE.CODREJEICAO). Os mais comuns têm texto próprio; o resto cai num
     * texto genérico com o código, pro fiscal procurar.
     */
    fun rejeicaoSefaz(codigo: Int?, numeroNota: String? = null): String {
        val nf = numeroNota?.takeIf { it.isNotBlank() && it != "0" }?.let { "A NF $it" } ?: "A nota"
        val cod = codigo?.let { " (código $it)" } ?: ""
        val naoCarregue = "Não carregue a mercadoria deste pedido até a nota ser aprovada"
        val (problema, acao) = when (codigo) {
            301 -> "$nf foi BARRADA PELA SEFAZ$cod: a própria empresa está com irregularidade fiscal" to
                "chame o fiscal/contabilidade AGORA. $naoCarregue"
            302 -> "$nf foi BARRADA PELA SEFAZ$cod: o CLIENTE está com a Inscrição Estadual irregular (suspensa, baixada ou inapta)" to
                "avise o comercial e o fiscal — com a inscrição irregular a nota não sai para este cliente. $naoCarregue"
            303 -> "$nf foi BARRADA PELA SEFAZ$cod: o CLIENTE não está habilitado a receber nota neste estado" to
                "avise o comercial e o fiscal para conferir o cadastro do cliente. $naoCarregue"
            209, 210, 232, 233, 234 -> "$nf foi RECUSADA PELA SEFAZ$cod: INSCRIÇÃO ESTADUAL do cliente errada ou não cadastrada" to
                "peça ao cadastro para corrigir a inscrição estadual do cliente e ao faturamento para reenviar a nota. $naoCarregue"
            237, 621 -> "$nf foi RECUSADA PELA SEFAZ$cod: CPF do cliente inválido" to
                "peça ao cadastro para corrigir o CPF do cliente e ao faturamento para reenviar a nota. $naoCarregue"
            207, 208, 241, 245 -> "$nf foi RECUSADA PELA SEFAZ$cod: CNPJ inválido ou não cadastrado" to
                "peça ao cadastro para conferir o CNPJ do cliente e ao faturamento para reenviar a nota. $naoCarregue"
            778 -> "$nf foi RECUSADA PELA SEFAZ$cod: NCM de algum produto não existe" to
                "peça ao fiscal para corrigir o NCM do produto e ao faturamento para reenviar a nota. $naoCarregue"
            204, 539 -> "$nf foi RECUSADA PELA SEFAZ$cod: este número de nota JÁ FOI USADO (duplicidade)" to
                "chame o faturamento para conferir a nota na Central de Notas. Não gere outra nota sem falar com eles"
            108, 109 -> "A SEFAZ está FORA DO AR$cod" to
                "espere alguns minutos — a nota é reenviada pelo faturamento quando a SEFAZ voltar"
            656 -> "A SEFAZ BLOQUEOU temporariamente os envios$cod (muitas tentativas)" to
                "espere 1 hora antes de reenviar e avise o faturamento"
            in 528..535, 610, 629, 694, 695, 696, 930 -> "$nf foi RECUSADA PELA SEFAZ$cod: erro de IMPOSTO ou de VALOR na nota" to
                "chame o fiscal/faturamento para corrigir a nota na Central de Notas e reenviar. $naoCarregue"
            225, 215 -> "$nf foi RECUSADA PELA SEFAZ$cod: o arquivo da nota saiu com erro" to
                "chame o faturamento para corrigir e reenviar a nota. $naoCarregue"
            else -> "$nf foi RECUSADA PELA SEFAZ$cod" to
                "chame o faturamento/fiscal para ver o motivo na Central de Notas, corrigir e reenviar. $naoCarregue"
        }
        return msg(problema, acao)
    }
}
