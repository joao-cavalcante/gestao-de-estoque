package wms.backend.galpao

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Desenho da planta (o que o editor grava, em metros). Origem no canto da planta: X na largura, Z na
 * profundidade, Y pra cima; (x, z) de um objeto é o CENTRO dele. Portado do Negri Planta Log 3D V1
 * (GeometryService/geometry-model.js), com os nomes do WMS.
 *
 * Endereço NÃO é desenhado um a um: sai das ESTRUTURAS (porta-palete/prateleira) por [GeradorEnderecos],
 * módulo × nível. O editor (frontend/src/app/galpao/geometria.ts) usa a mesma regra só pra prévia — quem
 * vale é a do backend, na publicação.
 */
@Serializable
data class GeometriaPlanta(
    val largura: Double,
    val profundidade: Double,
    val corredorMinimo: Double = 2.0,
    /** {estrutura}, {modulo}, {nivel} — módulo/nível com 2 dígitos. */
    val padraoEndereco: String = PADRAO_ENDERECO,
    val setores: List<SetorPlanta> = emptyList(),
    val objetos: List<ObjetoPlanta> = emptyList(),
)

@Serializable
data class SetorPlanta(
    val codigo: String,
    val nome: String,
    val cor: String = "#4a7bd0",
    /** AD_TIPOSEPARACAO do Sankhya que este setor recebe (1 Seco, 2 Refrigerado, 3 Congelado); null = qualquer. */
    val tipoSeparacao: Int? = null,
)

@Serializable
data class ObjetoPlanta(
    val codigo: String,
    /** [TipoObjeto] */
    val tipo: String,
    val x: Double,
    val z: Double,
    val largura: Double,
    val profundidade: Double,
    val altura: Double,
    val rotacao: Double = 0.0,
    val setor: String? = null,
    // Só ESTRUTURA:
    val modulos: Int = 1,
    val niveis: Int = 1,
    /** Níveis 1..nivelPicking = PICKING; acima = PULMÃO. */
    val nivelPicking: Int = 1,
    val capQtd: Double? = null,
    val capPeso: Double? = null,
    val capVolume: Double? = null,
)

object TipoObjeto {
    const val ESTRUTURA = "ESTRUTURA"
    const val CORREDOR = "CORREDOR"
    const val DOCA = "DOCA"
    const val AREA = "AREA"
    const val PAREDE = "PAREDE"
    val TODOS = setOf(ESTRUTURA, CORREDOR, DOCA, AREA, PAREDE)
}

const val PADRAO_ENDERECO = "{estrutura}-M{modulo}-N{nivel}"

/** Endereço projetado de uma estrutura (posição/medidas em metros). */
data class EnderecoGerado(
    val codigo: String,
    val setor: String,
    val estrutura: String,
    val modulo: Int,
    val nivel: Int,
    val tipo: String,
    val x: Double,
    val y: Double,
    val z: Double,
    val rotacao: Double,
    val largura: Double,
    val comprimento: Double,
    val altura: Double,
    val capQtd: Double?,
    val capPeso: Double?,
    val capVolume: Double?,
)

object GeometriaJson {
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun ler(texto: String): GeometriaPlanta = json.decodeFromString(texto)
    fun escrever(g: GeometriaPlanta): String = json.encodeToString(g)

    /** Hash do desenho (serialização determinística da data class). */
    fun hash(g: GeometriaPlanta): String =
        MessageDigest.getInstance("SHA-256").digest(escrever(g).toByteArray())
            .joinToString("") { "%02x".format(it) }
}

object GeradorEnderecos {
    const val MAX_MODULOS = 500
    const val MAX_NIVEIS = 30
    /** Folga entre o piso de um nível e a base do de cima (longarina). */
    private const val FOLGA_NIVEL = 0.15

    fun gerar(g: GeometriaPlanta): List<EnderecoGerado> = g.objetos
        .filter { it.tipo == TipoObjeto.ESTRUTURA }
        .flatMap { gerar(it, g.padraoEndereco) }

    fun gerar(o: ObjetoPlanta, padrao: String = PADRAO_ENDERECO): List<EnderecoGerado> {
        val modulos = o.modulos.coerceIn(1, MAX_MODULOS)
        val niveis = o.niveis.coerceIn(1, MAX_NIVEIS)
        val angulo = Math.toRadians(o.rotacao)
        val comprimentoModulo = o.profundidade / modulos
        val alturaNivel = o.altura / niveis
        val saida = ArrayList<EnderecoGerado>(modulos * niveis)
        for (m in 0 until modulos) {
            // Módulos ao longo da profundidade da estrutura, girados pela rotação dela.
            val dz = -o.profundidade / 2 + (m + 0.5) * comprimentoModulo
            val x = o.x - dz * sin(angulo)
            val z = o.z + dz * cos(angulo)
            for (n in 0 until niveis) {
                saida += EnderecoGerado(
                    codigo = codigo(padrao, o.codigo, m + 1, n + 1),
                    setor = o.setor.orEmpty(),
                    estrutura = o.codigo,
                    modulo = m + 1,
                    nivel = n + 1,
                    tipo = if (n + 1 <= o.nivelPicking) "PICKING" else "PULMAO",
                    x = x,
                    y = n * alturaNivel,
                    z = z,
                    rotacao = o.rotacao,
                    largura = o.largura,
                    comprimento = comprimentoModulo,
                    altura = max(alturaNivel - FOLGA_NIVEL, 0.01),
                    capQtd = o.capQtd,
                    capPeso = o.capPeso,
                    capVolume = o.capVolume,
                )
            }
        }
        return saida
    }

    fun codigo(padrao: String, estrutura: String, modulo: Int, nivel: Int): String =
        padrao.replace("{estrutura}", estrutura)
            .replace("{modulo}", modulo.toString().padStart(2, '0'))
            .replace("{nivel}", nivel.toString().padStart(2, '0'))
}

object ValidadorGeometria {
    const val MAX_OBJETOS = 10_000
    const val MAX_ENDERECOS = 100_000
    private const val MAX_ERROS = 200
    private val CODIGO_VALIDO = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,29}$")

    /** Lista de problemas que impedem a publicação (vazia = desenho válido). */
    fun validar(g: GeometriaPlanta): List<String> {
        val erros = LinkedHashSet<String>()
        if (g.largura <= 0 || g.profundidade <= 0) erros += "Medidas da planta devem ser maiores que zero"
        if (g.objetos.size > MAX_OBJETOS) erros += "Limite de $MAX_OBJETOS objetos no desenho"
        if (listOf("{estrutura}", "{modulo}", "{nivel}").any { it !in g.padraoEndereco }) {
            erros += "Padrão de endereço precisa de {estrutura}, {modulo} e {nivel}"
        }

        val setores = HashSet<String>()
        g.setores.forEach { s ->
            if (s.codigo.isBlank() || !setores.add(s.codigo)) erros += "Setor com código vazio ou repetido: ${s.codigo}"
        }

        val codigos = HashSet<String>()
        for (o in g.objetos) {
            val c = o.codigo
            if (!CODIGO_VALIDO.matches(c)) erros += "Código inválido: \"$c\" (letras, números, . _ -, até 30)"
            else if (!codigos.add(c)) erros += "Código repetido: $c"
            if (o.tipo !in TipoObjeto.TODOS) erros += "Tipo desconhecido em $c: ${o.tipo}"
            if (o.largura <= 0 || o.profundidade <= 0 || o.altura <= 0) erros += "Medidas inválidas: $c"
            if (o.tipo == TipoObjeto.CORREDOR && min(o.largura, o.profundidade) < g.corredorMinimo) {
                erros += "Corredor abaixo da largura mínima (${g.corredorMinimo} m): $c"
            }
            if (o.tipo == TipoObjeto.ESTRUTURA) {
                if (o.setor.isNullOrBlank() || o.setor !in setores) erros += "Estrutura sem setor cadastrado: $c"
                if (o.modulos !in 1..GeradorEnderecos.MAX_MODULOS) erros += "Módulos fora de 1–${GeradorEnderecos.MAX_MODULOS}: $c"
                if (o.niveis !in 1..GeradorEnderecos.MAX_NIVEIS) erros += "Níveis fora de 1–${GeradorEnderecos.MAX_NIVEIS}: $c"
                if (o.nivelPicking !in 0..o.niveis) erros += "Nível de picking fora dos níveis da estrutura: $c"
                if (listOf(o.capQtd, o.capPeso, o.capVolume).none { it != null && it > 0 }) {
                    erros += "Estrutura sem capacidade (quantidade, peso ou volume): $c"
                }
            }
            val (rx, rz) = extensao(o)
            if (o.x - rx / 2 < -1e-6 || o.z - rz / 2 < -1e-6 || o.x + rx / 2 > g.largura + 1e-6 || o.z + rz / 2 > g.profundidade + 1e-6) {
                erros += "Objeto fora da planta: $c"
            }
            if (erros.size >= MAX_ERROS) return erros.toList()
        }

        colisoesEntreEstruturas(g.objetos.filter { it.tipo == TipoObjeto.ESTRUTURA }).forEach { erros += it }

        val totalEnderecos = g.objetos.filter { it.tipo == TipoObjeto.ESTRUTURA }
            .sumOf { it.modulos.coerceIn(1, GeradorEnderecos.MAX_MODULOS).toLong() * it.niveis.coerceIn(1, GeradorEnderecos.MAX_NIVEIS) }
        if (totalEnderecos > MAX_ENDERECOS) erros += "Limite de $MAX_ENDERECOS endereços (desenho gera $totalEnderecos)"
        else {
            val repetidos = GeradorEnderecos.gerar(g).groupingBy { it.codigo }.eachCount().filterValues { it > 1 }.keys
            repetidos.take(20).forEach { erros += "Endereço gerado repetido: $it" }
        }
        return erros.take(MAX_ERROS)
    }

    /** Largura/profundidade da caixa alinhada aos eixos que contém o objeto girado. */
    private fun extensao(o: ObjetoPlanta): Pair<Double, Double> {
        val t = Math.toRadians(o.rotacao)
        return (abs(o.largura * cos(t)) + abs(o.profundidade * sin(t))) to (abs(o.largura * sin(t)) + abs(o.profundidade * cos(t)))
    }

    /** Hash espacial em células de 5 m — só compara estruturas vizinhas. */
    private fun colisoesEntreEstruturas(estruturas: List<ObjetoPlanta>): List<String> {
        val celulas = HashMap<Long, MutableList<ObjetoPlanta>>()
        val vistos = HashSet<String>()
        val erros = ArrayList<String>()
        for (o in estruturas) {
            val (rx, rz) = extensao(o)
            val bx0 = floor((o.x - rx / 2) / 5).toInt(); val bx1 = floor((o.x + rx / 2) / 5).toInt()
            val bz0 = floor((o.z - rz / 2) / 5).toInt(); val bz1 = floor((o.z + rz / 2) / 5).toInt()
            for (bx in bx0..bx1) for (bz in bz0..bz1) {
                val chave = (bx.toLong() shl 32) or (bz.toLong() and 0xffffffffL)
                val lista = celulas.getOrPut(chave) { mutableListOf() }
                for (p in lista) {
                    val par = if (o.codigo < p.codigo) "${o.codigo}|${p.codigo}" else "${p.codigo}|${o.codigo}"
                    if (vistos.add(par) && sobrepoe(o, p)) erros += "Estruturas sobrepostas: ${o.codigo} / ${p.codigo}"
                }
                lista += o
            }
        }
        return erros
    }

    private fun cantos(o: ObjetoPlanta): DoubleArray {
        val t = Math.toRadians(o.rotacao)
        val w = o.largura / 2; val d = o.profundidade / 2
        val out = DoubleArray(8)
        var k = 0
        for ((sx, sz) in listOf(-1 to -1, 1 to -1, 1 to 1, -1 to 1)) {
            out[k++] = o.x + sx * w * cos(t) - sz * d * sin(t)
            out[k++] = o.z + sx * w * sin(t) + sz * d * cos(t)
        }
        return out
    }

    /** Teorema do eixo separador entre dois retângulos girados; encostar não conta como sobrepor. */
    internal fun sobrepoe(a: ObjetoPlanta, b: ObjetoPlanta): Boolean {
        val pa = cantos(a); val pb = cantos(b)
        for (p in listOf(pa, pb)) for (i in 0 until 4) {
            val j = (i + 1) % 4
            val ax = -(p[j * 2 + 1] - p[i * 2 + 1]); val az = p[j * 2] - p[i * 2]
            var amin = Double.POSITIVE_INFINITY; var amax = Double.NEGATIVE_INFINITY
            var bmin = Double.POSITIVE_INFINITY; var bmax = Double.NEGATIVE_INFINITY
            for (n in 0 until 4) {
                val va = pa[2 * n] * ax + pa[2 * n + 1] * az
                val vb = pb[2 * n] * ax + pb[2 * n + 1] * az
                amin = min(amin, va); amax = max(amax, va); bmin = min(bmin, vb); bmax = max(bmax, vb)
            }
            if (amax <= bmin + 1e-7 || bmax <= amin + 1e-7) return false
        }
        return true
    }
}
