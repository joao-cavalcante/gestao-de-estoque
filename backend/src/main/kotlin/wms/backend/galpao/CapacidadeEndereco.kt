package wms.backend.galpao

/** Peso bruto (kg) e volume (m³) da unidade padrão do produto — TGFPRO.PESOBRUTO / TGFPRO.M3. null = não cadastrado. */
data class FisicoProduto(val pesoBruto: Double?, val m3: Double?)

data class ItemNoEndereco(val codprod: Int, val quantidade: Double)

data class Capacidade(val qtd: Double?, val peso: Double?, val volume: Double?)

/**
 * Ocupação e regra de capacidade do endereço — mesma regra do Negri Planta Log 3D
 * (Analytics.occupancy + StoragePolicy.validate):
 *  - ocupação = a MAIOR razão entre quantidade/capQtd, peso/capPeso e volume/capVolume, contando só as
 *    capacidades cadastradas (> 0) e as medidas conhecidas;
 *  - peso/volume do endereço só existem se TODO produto nele tiver peso/volume cadastrado (um sem
 *    cadastro deixa a medida desconhecida — não é tratado como zero);
 *  - nenhuma razão possível = "capacidade não parametrizada" (N/D, nunca 0%).
 *
 * Ficaram de fora (não há o dado no WMS): compatibilidade de setor do produto, temperatura e
 * mistura de validades.
 */
object CapacidadeEndereco {

    /** [razoes] = cada razão possível (QUANTIDADE/PESO/VOLUME) — os modos "capacidade por peso/volume" do 3D. */
    data class Resultado(
        val ocupacao: Double?,
        val base: String?,
        val peso: Double?,
        val volume: Double?,
        val razoes: Map<String, Double> = emptyMap(),
    )

    fun ocupacao(cap: Capacidade, itens: List<ItemNoEndereco>, fisicos: Map<Int, FisicoProduto>): Resultado {
        val itensComSaldo = itens.filter { it.quantidade != 0.0 }
        val qtd = itensComSaldo.sumOf { it.quantidade }
        val peso = somarSe(itensComSaldo) { fisicos[it]?.pesoBruto }
        val volume = somarSe(itensComSaldo) { fisicos[it]?.m3 }
        val razoes = buildMap {
            cap.qtd?.takeIf { it > 0 }?.let { put("QUANTIDADE", qtd / it) }
            if (peso != null) cap.peso?.takeIf { it > 0 }?.let { put("PESO", peso / it) }
            if (volume != null) cap.volume?.takeIf { it > 0 }?.let { put("VOLUME", volume / it) }
        }
        val maior = razoes.maxByOrNull { it.value }
        return Resultado(maior?.value, maior?.key, peso, volume, razoes)
    }

    /**
     * Erro que impede pôr [extra] de [codprod] no endereço, ou null se cabe. [atuais] = o que já está lá.
     */
    fun validarEntrada(
        cap: Capacidade,
        atuais: List<ItemNoEndereco>,
        codprod: Int,
        extra: Double,
        fisicos: Map<Int, FisicoProduto>,
    ): String? {
        val r = ocupacao(cap, atuais + ItemNoEndereco(codprod, extra), fisicos)
        if (r.ocupacao == null) return "Capacidade do endereço não parametrizada"
        if (r.ocupacao > 1 + 1e-9) return "Sobrecapacidade no destino: ${"%.0f".format(r.ocupacao * 100)}% (${rotulo(r.base)})"
        if ((cap.peso != null && cap.peso > 0 && r.peso == null) || (cap.volume != null && cap.volume > 0 && r.volume == null)) {
            return "Peso ou volume do produto não cadastrado no Sankhya — necessário para validar a capacidade do endereço"
        }
        return null
    }

    private fun rotulo(base: String?) = when (base) {
        "PESO" -> "peso"
        "VOLUME" -> "volume"
        else -> "quantidade"
    }

    private fun somarSe(itens: List<ItemNoEndereco>, medida: (Int) -> Double?): Double? {
        var total = 0.0
        for (i in itens) {
            val m = medida(i.codprod)?.takeIf { it > 0 } ?: return null
            total += m * i.quantidade
        }
        return total
    }
}
