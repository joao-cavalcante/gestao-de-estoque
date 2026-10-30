package wms.backend.tools

import wms.backend.Database
import wms.backend.master.MasterRepository
import kotlin.system.exitProcess

/**
 * Cadastra um usuário Master da plataforma (tenancy.master_users) — o ÚNICO jeito de criar um: não existe rota pra
 * isto. Roda dentro do container; a senha vem pelo stdin (não fica no histórico do shell nem na lista de processos).
 *
 *   docker exec -i wms-backend-prod sh -c 'java -cp "/app/lib/[todos os jars]" wms.backend.tools.CriarMasterKt email@empresa.com "Nome"'
 *   (digitar a senha e Enter; mínimo 12 caracteres)
 */
fun main(args: Array<String>) {
    val email = args.getOrNull(0)?.trim()?.lowercase()
    val nome = args.getOrNull(1)?.trim()
    if (email.isNullOrBlank() || !email.contains('@') || nome.isNullOrBlank()) {
        System.err.println("uso: CriarMasterKt <email> <nome>   (senha pelo stdin)")
        exitProcess(2)
    }
    val senha = readLine()?.trim().orEmpty()
    if (senha.length < 12) {
        System.err.println("senha precisa ter pelo menos 12 caracteres")
        exitProcess(2)
    }
    Database.init()
    if (MasterRepository.buscarParaLogin(email) != null) {
        System.err.println("já existe Master com o e-mail $email")
        exitProcess(1)
    }
    val id = MasterRepository.criar(email, nome, senha)
    println("Master criado: $id $email")
    exitProcess(0)
}
