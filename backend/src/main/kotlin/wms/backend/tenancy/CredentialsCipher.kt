package wms.backend.tenancy

/**
 * Ponto único usado pelo resto do backend (TenantRepository) — decide em
 * runtime qual implementação usar, sem o chamador precisar saber:
 *
 * - VAULT_ADDR + VAULT_TOKEN definidas -> VaultTransitCipher (produção).
 * - Ausentes -> LocalAesCipher (dev local, com aviso alto no log).
 *
 * Isso permite trocar de KMS (Vault -> AWS KMS -> outro) no futuro só
 * adicionando uma nova implementação de SecretsCipher aqui, sem tocar em
 * TenantRepository nem em nenhuma rota.
 */
object CredentialsCipher : SecretsCipher {
    private val delegate: SecretsCipher by lazy {
        val temVault = !System.getenv("VAULT_ADDR").isNullOrBlank() && !System.getenv("VAULT_TOKEN").isNullOrBlank()
        if (temVault) VaultTransitCipher else LocalAesCipher
    }

    override fun encrypt(plaintext: String): String = delegate.encrypt(plaintext)
    override fun decrypt(ciphertext: String): String = delegate.decrypt(ciphertext)
}
