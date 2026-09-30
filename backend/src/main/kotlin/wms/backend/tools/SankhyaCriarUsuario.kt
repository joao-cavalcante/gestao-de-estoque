package wms.backend.tools

import kotlinx.coroutines.runBlocking
import wms.backend.Database
import wms.backend.usuarios.SankhyaUsuarioService
import kotlin.system.exitProcess

/**
 * Cria usuários no Sankhya em lote pela entidade `Usuario` — mesma regra do botão "Criar no Sankhya"
 * da tela de Usuários (SankhyaUsuarioService). stdin: "NOME COMPLETO" ou "NOME;email" por linha.
 * Saída: "CODUSU;NOMEUSU;CRIADO|JA_EXISTIA" ou "ERRO;NOME;motivo".
 */
fun main(args: Array<String>) {
    val tenant = args.getOrNull(0) ?: run {
        System.err.println("uso: SankhyaCriarUsuarioKt <tenant>  (stdin: NOME[;email] por linha)")
        exitProcess(2)
    }
    val entradas = generateSequence(::readLine).map { it.trim() }.filter { it.isNotEmpty() }
        .map { l -> l.split(';').let { it[0].trim() to it.getOrNull(1)?.trim() } }
        .toList()
    Database.init()
    runBlocking {
        for ((nome, email) in entradas) {
            runCatching { SankhyaUsuarioService.criar(tenant, nome, email) }
                .onSuccess { println("${it.codusu};${it.nomeUsu};${if (it.jaExistia) "JA_EXISTIA" else "CRIADO"}") }
                .onFailure { println("ERRO;$nome;${it.message?.replace('\n', ' ')}") }
        }
    }
    exitProcess(0)
}
