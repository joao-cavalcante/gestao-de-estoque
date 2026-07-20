package wms.backend.tenancy

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.Base64

/**
 * Cifra via HashiCorp Vault — Transit secrets engine ("encryption as a
 * service"). A chave de verdade NUNCA sai do Vault: a aplicação manda o
 * texto plano numa chamada autenticada e recebe de volta só o ciphertext
 * (formato `vault:v1:...`, autodescritivo — inclui a versão da chave usada).
 *
 * Por que isso resolve o problema da chave única na aplicação
 * (LocalAesCipher): se este processo for comprometido, o atacante encontra
 * só ciphertext + um token de acesso ao Vault (revogável, com TTL, com ACL
 * escopada só à operação "encrypt/decrypt nesta chave transit") — não a
 * chave de criptografia em si. Cada chamada fica auditada no Vault. E
 * rotação de chave (`vault write -f transit/keys/wms-credentials/rotate`)
 * não exige re-cifrar nada: o Vault mantém as versões antigas pra decifrar
 * o que já existe, enquanto tudo novo já usa a versão mais recente.
 *
 * Payloads pequenos (JSON de client_id/secret/token, poucas centenas de
 * bytes) — por isso manda o blob inteiro pro Vault a cada chamada, sem
 * precisar de envelope encryption local (gerar uma DEK, cifrar localmente,
 * só mandar a DEK pro Vault). Isso só compensaria pra payloads grandes.
 */
object VaultTransitCipher : SecretsCipher {
    private val vaultAddr = System.getenv("VAULT_ADDR")?.trimEnd('/')
        ?: error("VAULT_ADDR não definida")
    private val vaultToken = System.getenv("VAULT_TOKEN")
        ?: error("VAULT_TOKEN não definida")
    private val transitKeyName = System.getenv("VAULT_TRANSIT_KEY") ?: "wms-credentials"

    private val http = HttpClient.newHttpClient()
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable private data class EncryptRequest(val plaintext: String)
    @Serializable private data class EncryptData(val ciphertext: String)
    @Serializable private data class EncryptResponse(val data: EncryptData)

    @Serializable private data class DecryptRequest(val ciphertext: String)
    @Serializable private data class DecryptData(val plaintext: String)
    @Serializable private data class DecryptResponse(val data: DecryptData)

    override fun encrypt(plaintext: String): String {
        val plaintextB64 = Base64.getEncoder().encodeToString(plaintext.toByteArray(Charsets.UTF_8))
        val body = json.encodeToString(EncryptRequest.serializer(), EncryptRequest(plaintextB64))
        val response = post("/v1/transit/encrypt/$transitKeyName", body)
        return json.decodeFromString(EncryptResponse.serializer(), response).data.ciphertext
    }

    override fun decrypt(ciphertext: String): String {
        val body = json.encodeToString(DecryptRequest.serializer(), DecryptRequest(ciphertext))
        val response = post("/v1/transit/decrypt/$transitKeyName", body)
        val plaintextB64 = json.decodeFromString(DecryptResponse.serializer(), response).data.plaintext
        return String(Base64.getDecoder().decode(plaintextB64), Charsets.UTF_8)
    }

    private fun post(path: String, jsonBody: String): String {
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$vaultAddr$path"))
            .header("X-Vault-Token", vaultToken)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() in 200..299) {
            "Vault respondeu ${response.statusCode()} em $path: ${response.body()}"
        }
        return response.body()
    }
}
