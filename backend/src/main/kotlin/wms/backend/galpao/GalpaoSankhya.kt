package wms.backend.galpao

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import wms.backend.erp.SankhyaSpClient
import wms.backend.tenancy.TenantRepository
import wms.backend.tenancy.TenantTx
import java.util.UUID

/**
 * Endereçamento, etapa 3a (27/11/2026): os endereços publicados da Planta vão para o Sankhya (addon TOR,
 * `EnderecamentoSP.publicarEnderecos`, tabela TOREND) — é lá que ficam saldo por endereço, tarefas e movimentos
 * (decisão de 18/11/2026: dados no Sankhya; o desenho fica na Torre). Chave = id do endereço na Torre.
 */
object GalpaoSankhya {
    private const val MODULO_ADDON = "torre"

    data class Envio(val ok: Boolean, val mensagem: String)

    suspend fun enviarEnderecos(tenantId: UUID, plantaId: UUID): Envio {
        val slug = TenantRepository.buscarPorId(tenantId)?.slug ?: return Envio(false, "Cliente não encontrado.")
        val (planta, enderecos) = TenantTx.run(tenantId) {
            val p = GalpaoPlantasTable.selectAll().where { GalpaoPlantasTable.id eq plantaId }.singleOrNull()
            val e = GalpaoEnderecosTable.selectAll()
                .where { (GalpaoEnderecosTable.plantaId eq plantaId) and (GalpaoEnderecosTable.ativo eq true) }
                .toList()
            p to e
        }
        planta ?: return Envio(false, "Planta não encontrada.")
        val codemp = planta[GalpaoPlantasTable.codemp]
        val codlocal = planta[GalpaoPlantasTable.codlocal]
        if (codemp == null || codlocal == null) return Envio(false, "A planta não tem empresa e local do Sankhya: os endereços não foram para o Sankhya.")
        val corpo = buildJsonObject {
            putJsonObject("request") {
                put("CODEMP", codemp.toString())
                put("CODLOCAL", codlocal.toString())
                putJsonArray("ENDERECOS") {
                    enderecos.forEach { e ->
                        add(buildJsonObject {
                            put("ID", e[GalpaoEnderecosTable.id].toString())
                            put("CODIGO", e[GalpaoEnderecosTable.codigo])
                            put("TIPO", e[GalpaoEnderecosTable.tipo])
                            put("SETOR", e[GalpaoEnderecosTable.setor])
                            put("ESTRUTURA", e[GalpaoEnderecosTable.estrutura])
                            put("MODULO", e[GalpaoEnderecosTable.modulo].toString())
                            put("NIVEL", e[GalpaoEnderecosTable.nivel].toString())
                            e[GalpaoEnderecosTable.capQtd]?.let { put("CAPQTD", it.stripTrailingZeros().toPlainString()) }
                            e[GalpaoEnderecosTable.capPeso]?.let { put("CAPPESO", it.stripTrailingZeros().toPlainString()) }
                            put("BLOQUEADO", if (e[GalpaoEnderecosTable.bloqueado]) "S" else "N")
                        })
                    }
                }
            }
        }
        return runCatching {
            val r = SankhyaSpClient.chamarRaw(slug, "EnderecamentoSP.publicarEnderecos", MODULO_ADDON, corpo, retentar = false)
            val b = (r["body"] as? JsonObject) ?: r
            fun n(c: String) = (b[c] as? JsonPrimitive)?.content ?: "0"
            val comSaldo = when (val v = b["COMSALDO"]) {
                is JsonArray -> v.mapNotNull { (it as? JsonPrimitive)?.content }
                is JsonPrimitive -> listOf(v.content)
                else -> emptyList()
            }
            val extra = if (comSaldo.isEmpty()) "" else " ${comSaldo.size} saíram do desenho mas têm saldo e continuam ativos: ${comSaldo.take(10).joinToString(", ")}."
            Envio(true, "Sankhya: ${n("NOVOS")} novo(s), ${n("ALTERADOS")} atualizado(s), ${n("DESATIVADOS")} desativado(s).$extra")
        }.getOrElse { Envio(false, "Os endereços não foram para o Sankhya: ${it.message}. Use \"Enviar ao Sankhya\" depois.") }
    }
}
