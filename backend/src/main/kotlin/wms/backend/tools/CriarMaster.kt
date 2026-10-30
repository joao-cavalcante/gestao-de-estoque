package wms.backend.tools

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import wms.backend.Database
import wms.backend.master.AtorAdmin
import wms.backend.master.AuditoriaAdmin
import wms.backend.master.MasterRepository
import wms.backend.master.MfaTotp
import java.time.Instant
import kotlin.system.exitProcess

/**
 * Administração das contas Master da plataforma (tenancy.master_users) — o ÚNICO jeito de criar ou recuperar uma:
 * não existe rota pra isto. Roda dentro do container do backend (usa a conexão dele com o banco). Senha só pelo
 * terminal, sem eco (nunca em argumento: ficaria na lista de processos e no histórico do shell). Toda ação vai pra
 * auditoria (ator "sistema", via "cli"). Procedimento completo: deploy/RUNBOOK-master.md.
 *
 *   docker exec -it wms-backend-prod sh -c 'java -cp "/app/lib/[todos os jars]" wms.backend.tools.CriarMasterKt situacao'
 *   (classpath exato e o passo a passo: deploy/RUNBOOK-master.md)
 *
 *   situacao                        contas Master (e-mail, ativa, MFA) — sem senha nem segredo
 *   criar <email> <nome>            primeiro Master; recusa se já existir Master ativo
 *   criar <email> <nome> --adicional  Master adicional (com outro já ativo)
 *   redefinir-senha <email>
 *   redefinir-mfa <email>           segredo TOTP novo (o anterior para de valer)
 *   desbloquear <email>             zera as tentativas erradas de login (antes dos 15 min)
 *   desativar <email>               recusa desativar o último Master ativo
 *   reativar <email>
 */
fun main(args: Array<String>) {
    // Forma antiga (Entrega 1.1): CriarMasterKt <email> <nome> = criar.
    val argumentos = if (args.firstOrNull()?.contains('@') == true) listOf("criar") + args else args.toList()
    val comando = argumentos.getOrNull(0)
    val email = argumentos.getOrNull(1)?.let(MasterRepository::normalizarEmail)
    val precisaEmail = comando in setOf("criar", "redefinir-senha", "redefinir-mfa", "desbloquear", "desativar", "reativar")

    if (comando == null || (comando != "situacao" && !precisaEmail) || (precisaEmail && (email.isNullOrBlank() || !email.contains('@')))) {
        uso()
    }

    Database.init()
    val codigo = when (comando) {
        "situacao" -> situacao()
        "criar" -> criar(email!!, argumentos.getOrNull(2)?.trim().orEmpty(), adicional = "--adicional" in argumentos)
        "redefinir-senha" -> redefinirSenha(email!!)
        "redefinir-mfa" -> redefinirMfa(email!!)
        "desbloquear" -> desbloquear(email!!)
        "desativar" -> desativar(email!!)
        "reativar" -> reativar(email!!)
        else -> uso()
    }
    exitProcess(codigo)
}

private fun uso(): Nothing {
    System.err.println(
        """
        uso: CriarMasterKt <comando> [email] [nome]
          situacao | criar <email> <nome> [--adicional] | redefinir-senha <email> | redefinir-mfa <email>
          desbloquear <email> | desativar <email> | reativar <email>
        (senha sempre pelo terminal; use docker exec -it)
        """.trimIndent(),
    )
    exitProcess(2)
}

private fun situacao(): Int {
    val contas = MasterRepository.listar()
    val ativas = contas.count { it.ativo }
    println("Masters cadastrados: ${contas.size} (ativos: $ativas)")
    contas.forEach { println("  ${it.email} | ${it.nome} | ${if (it.ativo) "ativo" else "inativo"} | MFA ${if (it.mfaCadastrado) "cadastrado" else "NÃO cadastrado"}") }
    if (ativas == 0) println("Nenhum Master ativo — rode: criar <email> <nome>")
    return 0
}

private fun criar(email: String, nome: String, adicional: Boolean): Int {
    if (nome.isBlank()) uso()
    val contas = MasterRepository.listar()
    if (contas.any { it.email == email }) {
        System.err.println("já existe Master com o e-mail $email (use redefinir-senha / redefinir-mfa / reativar)")
        return 1
    }
    if (contas.any { it.ativo } && !adicional) {
        System.err.println("já existe Master ativo (${contas.filter { it.ativo }.joinToString { it.email }}). Para um Master adicional, repita com --adicional.")
        return 1
    }
    val senha = lerSenhaNova(email) ?: return 1
    val segredo = MfaTotp.novoSegredo()
    val id = MasterRepository.criar(email, nome, senha, segredo)

    // Confere o que foi gravado: conta lida de volta, senha confere, segredo decifra igual.
    val gravado = MasterRepository.buscarParaLogin(email)
    if (gravado == null || !gravado.ativo || !MasterRepository.validarSenha(senha, gravado.senhaHash) || gravado.mfaSegredo != segredo) {
        System.err.println("ERRO: a conta não foi gravada corretamente — rode 'situacao' e, se ela aparecer, 'desativar' + criar de novo")
        return 1
    }
    auditar("master.criar", email, buildJsonObject { put("nome", nome); put("adicional", adicional) })
    println("Master criado: $id $email")
    mostrarCadastroMfa(email, segredo)
    return 0
}

private fun redefinirSenha(email: String): Int {
    if (MasterRepository.buscarParaLogin(email) == null) return naoEncontrado(email)
    val senha = lerSenhaNova(email) ?: return 1
    MasterRepository.redefinirSenha(email, senha)
    auditar("master.redefinir_senha", email)
    println("Senha redefinida: $email")
    return 0
}

private fun redefinirMfa(email: String): Int {
    if (MasterRepository.buscarParaLogin(email) == null) return naoEncontrado(email)
    val segredo = MfaTotp.novoSegredo()
    MasterRepository.redefinirMfa(email, segredo)
    auditar("master.redefinir_mfa", email)
    println("MFA redefinido: $email (o código do aplicativo antigo não vale mais)")
    mostrarCadastroMfa(email, segredo)
    return 0
}

private fun desbloquear(email: String): Int {
    val removidas = MasterRepository.limparFalhas(email)
    auditar("master.desbloquear", email, buildJsonObject { put("tentativasRemovidas", removidas) })
    println("Tentativas erradas removidas: $removidas ($email)")
    return 0
}

private fun desativar(email: String): Int {
    val contas = MasterRepository.listar()
    val alvo = contas.firstOrNull { it.email == email } ?: return naoEncontrado(email)
    if (alvo.ativo && contas.count { it.ativo } == 1) {
        System.err.println("$email é o único Master ativo — crie outro (criar ... --adicional) antes de desativar este")
        return 1
    }
    MasterRepository.definirAtivo(email, false)
    auditar("master.desativar", email)
    println("Master desativado: $email (sessões abertas dele param de valer na próxima chamada)")
    return 0
}

private fun reativar(email: String): Int {
    if (!MasterRepository.definirAtivo(email, true)) return naoEncontrado(email)
    auditar("master.reativar", email)
    println("Master reativado: $email")
    return 0
}

private fun naoEncontrado(email: String): Int {
    System.err.println("Master $email não encontrado")
    return 1
}

private fun auditar(acao: String, alvo: String, extra: kotlinx.serialization.json.JsonObject? = null) {
    AuditoriaAdmin.registrar(
        AtorAdmin("sistema", null, "cli", null), null, null, acao,
        buildJsonObject { put("via", "cli"); put("alvo", alvo); extra?.forEach { (k, v) -> put(k, v) } },
    )
}

/**
 * Senha pelo terminal: com TTY (docker exec -it) sem eco e digitada duas vezes; sem TTY (stdin redirecionado,
 * automação) uma linha só. Mínimo 12 caracteres e diferente do e-mail.
 */
private fun lerSenhaNova(email: String): String? {
    val console = System.console()
    val senha: String
    if (console != null) {
        senha = String(console.readPassword("Senha (mínimo 12 caracteres): ") ?: CharArray(0))
        val repetida = String(console.readPassword("Repita a senha: ") ?: CharArray(0))
        if (senha != repetida) {
            System.err.println("as senhas não conferem")
            return null
        }
    } else {
        System.err.println("(sem terminal interativo: lendo a senha da entrada padrão)")
        senha = readLine().orEmpty()
    }
    if (senha.length < 12 || senha.trim().lowercase() == email) {
        System.err.println("senha precisa ter pelo menos 12 caracteres e ser diferente do e-mail")
        return null
    }
    return senha
}

/**
 * Mostra o cadastro do aplicativo autenticador UMA vez, só neste terminal (não vai pra log nem pra auditoria) e,
 * com TTY, confere um código do aplicativo antes de terminar.
 */
private fun mostrarCadastroMfa(email: String, segredo: String) {
    println()
    println("Cadastre no aplicativo autenticador (Google/Microsoft Authenticator, 1Password...):")
    println("  chave: $segredo")
    println("  ou o endereço: ${MfaTotp.uriCadastro(email, segredo)}")
    println("Guarde a chave em local seguro; ela não será mostrada de novo (perdeu: redefinir-mfa).")
    val console = System.console() ?: return println("(sem terminal interativo: confirme o código no primeiro login)")
    repeat(3) {
        val codigo = console.readLine("Código de 6 dígitos mostrado pelo aplicativo: ")
        if (MfaTotp.passoDoCodigo(segredo, codigo, Instant.now()) != null) {
            println("Código confere — aplicativo cadastrado.")
            return
        }
        println("Código não confere (confira a hora do celular).")
    }
    println("ATENÇÃO: aplicativo não confirmado. Rode redefinir-mfa $email e cadastre de novo.")
}
