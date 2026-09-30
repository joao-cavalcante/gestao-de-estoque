package wms.backend.tools

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import wms.backend.Database
import wms.backend.erp.SankhyaDbExplorerClient
import wms.backend.erp.SankhyaSpClient
import kotlin.system.exitProcess

/**
 * Cria usuário no Sankhya (TSIUSU) pela ENTIDADE `Usuario` (CRUDServiceProvider.saveRecord, com a
 * credencial de integração do tenant) — não por INSERT direto. Replica o modelo do usuário
 * ALLAN GARCIA BISPO (CODUSU 194, grupo 32 LOGÍSTICA/SEPARAÇÃO), trocando CODUSU (próximo livre),
 * NOMEUSU e ACCOUNTEMAIL. Senha (INTERNO) NÃO vai: pela entidade o Sankhya trata a senha à parte.
 *
 * Uso (uma linha por usuário no stdin: "NOME COMPLETO;email@dominio"):
 *   docker exec -i wms-backend-prod sh -c 'java -cp "/app/lib/[jars]" wms.backend.tools.SankhyaCriarUsuarioKt negri' < usuarios.txt
 * Saída: "CODUSU;NOMEUSU" de cada criado (ou "ERRO;NOMEUSU;motivo").
 */
private val MODELO = linkedMapOf(
    "ABREGAVETA" to "N",
    "ACESSAFORMULAREL" to "N",
    "ACESSOVISUALCAB" to "T",
    "ALTCTAFAT" to "N",
    "ALTCTAIMPBOL" to "N",
    "ALTORDCFECH" to "N",
    "APENASCOMPLIB" to "N",
    "APROVCOT" to "M",
    "AVISAVARPRECO" to "N",
    "CAIXA" to "N",
    "CODCENCUSPAD" to "0",
    "CODGRUPO" to "32",
    "CODIDECONECT" to "0",
    "CODPARC" to "0",
    "CODVEND" to "0",
    "DESCTOTALNOTAPDV" to "N",
    "EXCLIBORC" to "N",
    "EXIBIRVALANALRENT" to "S",
    "IGNORAHORASCRUZ" to "N",
    "IMP2SANSUPCAI" to "S",
    "IMPNFCENTRAL" to "S",
    "INSTALAPACOTESS" to "N",
    "INTEGRAECONECT" to "N",
    "LOCALE" to "PT_BR",
    "MINUTOSFIN" to "0",
    "NIVEL" to "100",
    "PERMALTMOEDA" to "N",
    "PERMEXPREL" to "S",
    "PERMIMPRIMEREL" to "N",
    "PERMREPERRO" to "N",
    "PORTASMTP" to "25",
    "RESTRINGECART" to "N",
    "SEGURANCASMTP" to "N",
    "SELECTWCAPO" to "N",
    "SENHANUNCAEXPIRA" to "S",
    "TEMECF" to "N",
    "TIMBAIXAWORD" to "S",
    "TIMBAIXTITRECABE" to "N",
    "TIMVERTODASFACS" to "N",
    "TIPOSMTP" to "N",
    "TIPOUSU" to "0",
    "VERCABPROPRIA" to "N",
    "VISACESOUTUSU" to "S",
)

fun main(args: Array<String>) {
    val tenant = args.getOrNull(0) ?: run {
        System.err.println("uso: SankhyaCriarUsuarioKt <tenant>  (stdin: NOME;email por linha)")
        exitProcess(2)
    }
    val entradas = generateSequence(::readLine).map { it.trim() }.filter { it.isNotEmpty() }
        .map { l -> l.split(';').let { it[0].trim().uppercase() to it.getOrNull(1)?.trim() } }
        .toList()
    Database.init()
    runBlocking {
        for ((nome, email) in entradas) {
            // Já existe (rodar de novo não duplica)?
            val existente = SankhyaDbExplorerClient.executarQuery(
                tenant, "SELECT CODUSU FROM TSIUSU WHERE UPPER(NOMEUSU) = '${nome.replace("'", "''")}'",
            ).firstOrNull()?.get("CODUSU")
            if (existente != null) {
                println("$existente;$nome;JA_EXISTIA")
                continue
            }
            val codusu = SankhyaDbExplorerClient.executarQuery(tenant, "SELECT NVL(MAX(CODUSU), 0) + 1 AS PROX FROM TSIUSU")
                .first()["PROX"]!!.trim()
            val campos = LinkedHashMap(MODELO).apply {
                put("CODUSU", codusu)
                put("NOMEUSU", nome)
                if (!email.isNullOrBlank()) put("ACCOUNTEMAIL", email)
            }
            runCatching {
                SankhyaSpClient.chamarRaw(
                    tenant, "CRUDServiceProvider.saveRecord", "mge",
                    buildJsonObject {
                        putJsonObject("dataSet") {
                            put("rootEntity", "Usuario")
                            put("includePresentationFields", "N")
                            putJsonObject("dataRow") {
                                putJsonObject("localFields") {
                                    campos.forEach { (k, v) -> putJsonObject(k) { put("\$", v) } }
                                }
                            }
                            putJsonObject("entity") { putJsonObject("fieldset") { put("list", "CODUSU,NOMEUSU") } }
                        }
                    },
                )
            }.onSuccess {
                val criado = SankhyaDbExplorerClient.executarQuery(
                    tenant, "SELECT CODUSU FROM TSIUSU WHERE UPPER(NOMEUSU) = '${nome.replace("'", "''")}'",
                ).firstOrNull()?.get("CODUSU")
                println("${criado ?: codusu};$nome;CRIADO")
            }.onFailure {
                println("ERRO;$nome;${it.message?.replace('\n', ' ')}")
            }
        }
    }
    exitProcess(0)
}
