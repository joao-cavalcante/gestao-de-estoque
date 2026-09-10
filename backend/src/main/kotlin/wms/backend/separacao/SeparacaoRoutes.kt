package wms.backend.separacao

import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import wms.backend.produtos.ProdutoImagemService
import wms.backend.tenancy.TenantRepository
import java.util.UUID

/**
 * Fluxo: POST /iniciar responde na hora (cria sessão local, dispara o
 * carregamento em background); o front dá polling em GET /sessoes/{id} até
 * status == "pronta" (mesma ideia do "sessao-pronta" do projeto base, só
 * que aqui a checagem de integridade — ver SeparacaoRepository.revalidar —
 * roda a CADA poll, de graça, sem chamada nova ao Sankhya).
 */
fun Route.separacaoRoutes() {
    route("/api/separacao") {

        post("/iniciar") {
            val slug = call.request.queryParameters["tenant"]
            if (slug.isNullOrBlank()) {
                call.respond(HttpStatusCode.BadRequest, mapOf("erro" to "query param 'tenant' é obrigatório"))
                return@post
            }
            val tenantId = resolverTenantId(slug)
            if (tenantId == null) {
                call.respond(HttpStatusCode.NotFound, mapOf("erro" to "tenant '$slug' não encontrado"))
                return@post
            }

            val body = call.receive<IniciarSeparacaoRequest>()
            val resultado = SeparacaoService.iniciar(slug, tenantId, body.nunota)
            call.respond(HttpStatusCode.Accepted, mapOf("sessaoId" to resultado.sessaoId.toString(), "status" to resultado.status))
        }

        get("/sessoes/{id}") {
            val slug = call.request.queryParameters["tenant"]
            val sessaoId = call.parameters["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            if (slug.isNullOrBlank() || sessaoId == null) {
                call.respond(HttpStatusCode.BadRequest, mapOf("erro" to "'tenant' (query) e id (path) são obrigatórios"))
                return@get
            }
            val tenantId = resolverTenantId(slug)
            if (tenantId == null) {
                call.respond(HttpStatusCode.NotFound, mapOf("erro" to "tenant '$slug' não encontrado"))
                return@get
            }

            // Revalida a cada poll — leitura 100% local (compara contra o que
            // o sync já sabe da tarefa), nunca chama o Sankhya aqui.
            val sessao = SeparacaoRepository.revalidar(tenantId, sessaoId)
            if (sessao == null) {
                call.respond(HttpStatusCode.NotFound, mapOf("erro" to "sessão não encontrada"))
                return@get
            }
            call.respond(sessao)
        }

        get("/sessoes/{id}/itens") {
            val slug = call.request.queryParameters["tenant"]
            val sessaoId = call.parameters["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            if (slug.isNullOrBlank() || sessaoId == null) {
                call.respond(HttpStatusCode.BadRequest, mapOf("erro" to "'tenant' (query) e id (path) são obrigatórios"))
                return@get
            }
            val tenantId = resolverTenantId(slug)
            if (tenantId == null) {
                call.respond(HttpStatusCode.NotFound, mapOf("erro" to "tenant '$slug' não encontrado"))
                return@get
            }

            val sessao = SeparacaoRepository.buscarSessao(tenantId, sessaoId)
            if (sessao == null) {
                call.respond(HttpStatusCode.NotFound, mapOf("erro" to "sessão não encontrada"))
                return@get
            }
            if (sessao.status != SeparacaoStatus.PRONTA) {
                call.respond(HttpStatusCode.Conflict, mapOf("erro" to "sessão ainda não está pronta (status='${sessao.status}')"))
                return@get
            }

            call.respond(SeparacaoRepository.listarItens(tenantId, sessaoId))
        }

        get("/sessoes/{id}/codigos-barra") {
            val slug = call.request.queryParameters["tenant"]
            val sessaoId = call.parameters["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            if (slug.isNullOrBlank() || sessaoId == null) {
                call.respond(HttpStatusCode.BadRequest, mapOf("erro" to "'tenant' (query) e id (path) são obrigatórios"))
                return@get
            }
            val tenantId = resolverTenantId(slug)
            if (tenantId == null) {
                call.respond(HttpStatusCode.NotFound, mapOf("erro" to "tenant '$slug' não encontrado"))
                return@get
            }

            val sessao = SeparacaoRepository.buscarSessao(tenantId, sessaoId)
            if (sessao == null) {
                call.respond(HttpStatusCode.NotFound, mapOf("erro" to "sessão não encontrada"))
                return@get
            }
            if (sessao.status != SeparacaoStatus.PRONTA) {
                call.respond(HttpStatusCode.Conflict, mapOf("erro" to "sessão ainda não está pronta (status='${sessao.status}')"))
                return@get
            }

            call.respond(SeparacaoRepository.listarCodigosBarra(tenantId, sessaoId))
        }

        post("/sessoes/{id}/resolver-codigo-barras") {
            val slug = call.request.queryParameters["tenant"]
            val sessaoId = call.parameters["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            if (slug.isNullOrBlank() || sessaoId == null) {
                call.respond(HttpStatusCode.BadRequest, mapOf("erro" to "'tenant' (query) e id (path) são obrigatórios"))
                return@post
            }
            val tenantId = resolverTenantId(slug)
            if (tenantId == null) {
                call.respond(HttpStatusCode.NotFound, mapOf("erro" to "tenant '$slug' não encontrado"))
                return@post
            }

            val body = call.receive<ResolverCodigoBarraRequest>()
            val resolvido = SeparacaoRepository.resolverCodigoBarras(tenantId, sessaoId, body.codigoBarra)
            if (resolvido == null) {
                call.respond(HttpStatusCode.NotFound, mapOf("erro" to "código de barras não encontrado nos itens desta sessão"))
                return@post
            }
            call.respond(resolvido)
        }

        /**
         * Passo 1 do fluxo por Tab: identifica o produto a partir do que foi
         * bipado/digitado no primeiro campo, e já devolve como o campo de
         * controle deve se comportar (select com opções vs. lote livre) —
         * ver SeparacaoRepository.identificarProduto.
         */
        post("/sessoes/{id}/identificar") {
            val slug = call.request.queryParameters["tenant"]
            val sessaoId = call.parameters["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            if (slug.isNullOrBlank() || sessaoId == null) {
                call.respond(HttpStatusCode.BadRequest, mapOf("erro" to "'tenant' (query) e id (path) são obrigatórios"))
                return@post
            }
            val tenantId = resolverTenantId(slug)
            if (tenantId == null) {
                call.respond(HttpStatusCode.NotFound, mapOf("erro" to "tenant '$slug' não encontrado"))
                return@post
            }

            val body = call.receive<IdentificarProdutoRequest>()
            val resultado = SeparacaoRepository.identificarProduto(
                tenantId, sessaoId, body.codigoBarra, body.codprod, body.etapa?.toShort(),
            )
            if (resultado == null) {
                call.respond(HttpStatusCode.NotFound, mapOf("erro" to "código não encontrado nos itens desta sessão"))
                return@post
            }

            // Imagem: SÓ o cache local aqui (leitura instantânea) — uma ida ao
            // Sankhya no caminho da bipagem atrasaria o primeiro Tab. Se não
            // estiver cacheada, dispara o sync em background e o frontend busca
            // a imagem depois via GET /produtos/{codprod}/imagem.
            val (achou, imagem) = ProdutoImagemService.buscarCacheado(tenantId, resultado.codprod)
            if (!achou) ProdutoImagemService.prefetchEmBackground(slug, tenantId, resultado.codprod)
            call.respond(resultado.copy(imagemBase64 = imagem))
        }

        /** Imagem do produto — cache-first, busca lazy no Sankhya. Chamada à parte pra não travar a bipagem. */
        get("/produtos/{codprod}/imagem") {
            val slug = call.request.queryParameters["tenant"]
            val codprod = call.parameters["codprod"]?.toIntOrNull()
            if (slug.isNullOrBlank() || codprod == null) {
                call.respond(HttpStatusCode.BadRequest, mapOf("erro" to "'tenant' (query) e codprod (path) são obrigatórios"))
                return@get
            }
            val tenantId = resolverTenantId(slug)
                ?: return@get call.respond(HttpStatusCode.NotFound, mapOf("erro" to "tenant '$slug' não encontrado"))
            val imagem = try {
                ProdutoImagemService.buscarOuSincronizar(slug, tenantId, codprod)
            } catch (e: Exception) {
                null
            }
            call.respond(mapOf("imagemBase64" to imagem))
        }

        /**
         * Passo 2 (final) do fluxo por Tab: produto+controle já resolvidos
         * no passo anterior — só confirma a quantidade. Grava a leitura e
         * recalcula numa única transação (rápido: bipagem não pode acumular
         * latência entre um item e o próximo).
         */
        post("/sessoes/{id}/conferir") {
            val slug = call.request.queryParameters["tenant"]
            val sessaoId = call.parameters["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            if (slug.isNullOrBlank() || sessaoId == null) {
                call.respond(HttpStatusCode.BadRequest, mapOf("erro" to "'tenant' (query) e id (path) são obrigatórios"))
                return@post
            }
            val tenantId = resolverTenantId(slug)
            if (tenantId == null) {
                call.respond(HttpStatusCode.NotFound, mapOf("erro" to "tenant '$slug' não encontrado"))
                return@post
            }

            val body = call.receive<ConferirItemRequest>()
            val qtd = body.qtd.trim().replace(",", ".").toBigDecimalOrNull()
            if (qtd == null || qtd <= java.math.BigDecimal.ZERO) {
                call.respond(HttpStatusCode.BadRequest, mapOf("erro" to "'qtd' precisa ser um número maior que zero"))
                return@post
            }

            val peso = body.peso?.trim()?.replace(",", ".")?.takeIf { it.isNotEmpty() }?.toBigDecimalOrNull()
            val permitirQtdMaior = SeparacaoRepository.permiteQtdMaior(tenantId, sessaoId)
            val resultado = try {
                SeparacaoRepository.conferirItem(
                    tenantId, sessaoId, body.codprod, body.controle, qtd, permitirQtdMaior, peso,
                    codvolEscanado = body.codvol, codigoBarra = body.codigoBarra,
                )
            } catch (e: SeparacaoRepository.QuantidadeExcedeException) {
                call.respond(HttpStatusCode.Conflict, mapOf("erro" to "quantidade excede o pendente (máximo ${e.maximo})"))
                return@post
            }
            if (resultado == null) {
                call.respond(HttpStatusCode.NotFound, mapOf("erro" to "produto não encontrado nos itens desta sessão"))
                return@post
            }
            call.respond(resultado)
        }

        /**
         * Fecha a conferência DE VERDADE no Sankhya (corte de estoque +
         * financeiro) — ver SeparacaoService.finalizar pro contrato completo
         * confirmado ao vivo (ConferenciaSP.salvarItemConferido/cortar/
         * finalizarConferencia).
         */
        post("/sessoes/{id}/finalizar") {
            val slug = call.request.queryParameters["tenant"]
            val sessaoId = call.parameters["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            if (slug.isNullOrBlank() || sessaoId == null) {
                call.respond(HttpStatusCode.BadRequest, mapOf("erro" to "'tenant' (query) e id (path) são obrigatórios"))
                return@post
            }
            val tenantId = resolverTenantId(slug)
            if (tenantId == null) {
                call.respond(HttpStatusCode.NotFound, mapOf("erro" to "tenant '$slug' não encontrado"))
                return@post
            }

            try {
                val resultado = SeparacaoService.finalizar(slug, tenantId, sessaoId)
                call.respond(resultado)
            } catch (e: SeparacaoService.FinalizarSeparacaoException) {
                call.respond(HttpStatusCode.Conflict, mapOf("erro" to (e.message ?: "não foi possível finalizar")))
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadGateway, mapOf("erro" to (e.message ?: "falha ao finalizar no Sankhya")))
            }
        }

        // ─── Conferência por etapa (V29) ────────────────────────────────────

        /** Etapas da conferência segmentada (uma por tipo de separação com item). Vazio = não segmentada. */
        get("/sessoes/{id}/etapas") {
            val slug = call.request.queryParameters["tenant"]
            val sessaoId = call.parameters["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            if (slug.isNullOrBlank() || sessaoId == null) {
                call.respond(HttpStatusCode.BadRequest, mapOf("erro" to "'tenant' (query) e id (path) são obrigatórios"))
                return@get
            }
            val tenantId = resolverTenantId(slug)
                ?: return@get call.respond(HttpStatusCode.NotFound, mapOf("erro" to "tenant '$slug' não encontrado"))
            call.respond(SeparacaoRepository.listarEtapas(tenantId, sessaoId))
        }

        /**
         * Conclui uma etapa. Se for a última pendente, dispara o `finalizar`
         * real (push pro Sankhya) e devolve os campos de FinalizarResultado.
         */
        post("/sessoes/{id}/concluir-etapa") {
            val slug = call.request.queryParameters["tenant"]
            val sessaoId = call.parameters["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            if (slug.isNullOrBlank() || sessaoId == null) {
                call.respond(HttpStatusCode.BadRequest, mapOf("erro" to "'tenant' (query) e id (path) são obrigatórios"))
                return@post
            }
            val tenantId = resolverTenantId(slug)
                ?: return@post call.respond(HttpStatusCode.NotFound, mapOf("erro" to "tenant '$slug' não encontrado"))

            val body = call.receive<ConcluirEtapaRequest>()
            try {
                val resultado = SeparacaoService.concluirEtapa(
                    slug, tenantId, sessaoId, body.tipoSeparacao, body.manterPendente, body.operador,
                )
                call.respond(resultado)
            } catch (e: SeparacaoService.EtapaComPendentesException) {
                call.respond(HttpStatusCode.Conflict, EtapaComPendentesDto(pendentes = e.pendentes))
            } catch (e: SeparacaoService.ConcluirEtapaException) {
                call.respond(HttpStatusCode.Conflict, mapOf("erro" to (e.message ?: "não foi possível concluir a etapa")))
            } catch (e: SeparacaoService.FinalizarSeparacaoException) {
                call.respond(HttpStatusCode.Conflict, mapOf("erro" to (e.message ?: "não foi possível finalizar")))
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadGateway, mapOf("erro" to (e.message ?: "falha ao concluir etapa")))
            }
        }

        /** Breakdown de tipos de separação por nunota — pro card da Fila. `{}` quando o tenant não é segmentado. */
        post("/etapas-fila") {
            val slug = call.request.queryParameters["tenant"]
            if (slug.isNullOrBlank()) {
                call.respond(HttpStatusCode.BadRequest, mapOf("erro" to "query param 'tenant' é obrigatório"))
                return@post
            }
            val tenantId = resolverTenantId(slug)
                ?: return@post call.respond(HttpStatusCode.NotFound, mapOf("erro" to "tenant '$slug' não encontrado"))
            val body = call.receive<FilaEtapasRequest>()
            try {
                call.respond(SeparacaoService.etapasFila(slug, tenantId, body.nunotas).mapKeys { it.key.toString() })
            } catch (e: Exception) {
                // Não deixa a fila quebrar por causa disso — degrada pra "sem etapas".
                call.respond(emptyMap<String, FilaEtapasDto>())
            }
        }

        /**
         * Cancela a sessão inteira (desiste do pedido) — só LOCAL por
         * enquanto, ver SeparacaoService.cancelar pro motivo (contrato do
         * ConferenciaSP.excluirConferencia ainda não confirmado).
         */
        post("/sessoes/{id}/cancelar") {
            val slug = call.request.queryParameters["tenant"]
            val sessaoId = call.parameters["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            if (slug.isNullOrBlank() || sessaoId == null) {
                call.respond(HttpStatusCode.BadRequest, mapOf("erro" to "'tenant' (query) e id (path) são obrigatórios"))
                return@post
            }
            val tenantId = resolverTenantId(slug)
            if (tenantId == null) {
                call.respond(HttpStatusCode.NotFound, mapOf("erro" to "tenant '$slug' não encontrado"))
                return@post
            }

            try {
                SeparacaoService.cancelar(slug, tenantId, sessaoId)
                call.respond(mapOf("ok" to true))
            } catch (e: SeparacaoService.CancelarSeparacaoException) {
                call.respond(HttpStatusCode.Conflict, mapOf("erro" to (e.message ?: "não foi possível cancelar")))
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadGateway, mapOf("erro" to (e.message ?: "falha ao cancelar")))
            }
        }

        /** Recontagem — reabre a sessão pra bipar tudo de novo do zero (mesmos itens/config, só zera o que foi conferido). */
        post("/sessoes/{id}/recontar") {
            val slug = call.request.queryParameters["tenant"]
            val sessaoId = call.parameters["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            if (slug.isNullOrBlank() || sessaoId == null) {
                call.respond(HttpStatusCode.BadRequest, mapOf("erro" to "'tenant' (query) e id (path) são obrigatórios"))
                return@post
            }
            val tenantId = resolverTenantId(slug)
            if (tenantId == null) {
                call.respond(HttpStatusCode.NotFound, mapOf("erro" to "tenant '$slug' não encontrado"))
                return@post
            }

            try {
                SeparacaoService.iniciarRecontagem(slug, tenantId, sessaoId)
                call.respond(mapOf("ok" to true))
            } catch (e: SeparacaoService.RecontagemException) {
                call.respond(HttpStatusCode.Conflict, mapOf("erro" to (e.message ?: "não foi possível recontar")))
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadGateway, mapOf("erro" to (e.message ?: "falha ao recontar no Sankhya")))
            }
        }

        /** UMAs (Unidade de Movimentação/Armazenagem) dos produtos pesáveis da sessão — rotina de peso portada do projeto base. */
        get("/sessoes/{id}/uma") {
            val slug = call.request.queryParameters["tenant"]
            val sessaoId = call.parameters["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            if (slug.isNullOrBlank() || sessaoId == null) {
                call.respond(HttpStatusCode.BadRequest, mapOf("erro" to "'tenant' (query) e id (path) são obrigatórios"))
                return@get
            }
            val tenantId = resolverTenantId(slug)
            if (tenantId == null) {
                call.respond(HttpStatusCode.NotFound, mapOf("erro" to "tenant '$slug' não encontrado"))
                return@get
            }
            call.respond(SeparacaoRepository.listarUma(tenantId, sessaoId))
        }

        /** Modo simplificado (sem dimensão) — só o total de volumes do pedido, gravado nativamente no Sankhya (TGFCON2.QTDVOL). */
        get("/sessoes/{id}/volume") {
            val slug = call.request.queryParameters["tenant"]
            val sessaoId = call.parameters["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            if (slug.isNullOrBlank() || sessaoId == null) {
                call.respond(HttpStatusCode.BadRequest, mapOf("erro" to "'tenant' (query) e id (path) são obrigatórios"))
                return@get
            }
            val tenantId = resolverTenantId(slug)
            if (tenantId == null) {
                call.respond(HttpStatusCode.NotFound, mapOf("erro" to "tenant '$slug' não encontrado"))
                return@get
            }

            call.respond(VolumeDto(SeparacaoRepository.buscarQtdVol(tenantId, sessaoId)))
        }

        put("/sessoes/{id}/volume") {
            val slug = call.request.queryParameters["tenant"]
            val sessaoId = call.parameters["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            if (slug.isNullOrBlank() || sessaoId == null) {
                call.respond(HttpStatusCode.BadRequest, mapOf("erro" to "'tenant' (query) e id (path) são obrigatórios"))
                return@put
            }
            val tenantId = resolverTenantId(slug)
            if (tenantId == null) {
                call.respond(HttpStatusCode.NotFound, mapOf("erro" to "tenant '$slug' não encontrado"))
                return@put
            }

            val body = call.receive<DefinirVolumeRequest>()
            if (body.quantidade < 0) {
                call.respond(HttpStatusCode.BadRequest, mapOf("erro" to "'quantidade' não pode ser negativa"))
                return@put
            }

            // Contador LOCAL (modo simplificado) — resposta imediata, sem round-trip
            // ao Sankhya. O total vai pro Sankhya no `cortar` da finalização.
            val gravado = SeparacaoRepository.definirQtdVol(tenantId, sessaoId, body.quantidade)
            call.respond(VolumeDto(gravado))
        }

        /** Desfaz tudo que foi conferido pra esse produto+controle — volta a pendente do zero (corrige bipe errado). */
        post("/sessoes/{id}/devolver-item") {
            val slug = call.request.queryParameters["tenant"]
            val sessaoId = call.parameters["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            if (slug.isNullOrBlank() || sessaoId == null) {
                call.respond(HttpStatusCode.BadRequest, mapOf("erro" to "'tenant' (query) e id (path) são obrigatórios"))
                return@post
            }
            val tenantId = resolverTenantId(slug)
            if (tenantId == null) {
                call.respond(HttpStatusCode.NotFound, mapOf("erro" to "tenant '$slug' não encontrado"))
                return@post
            }

            val body = call.receive<DevolverItemRequest>()
            val ok = SeparacaoRepository.devolverItem(tenantId, sessaoId, body.codprod, body.controle)
            if (!ok) {
                call.respond(HttpStatusCode.NotFound, mapOf("erro" to "produto não encontrado nos itens desta sessão"))
                return@post
            }
            call.respond(HttpStatusCode.OK, mapOf("ok" to true))
        }

        // ─── Faturamento (portado do fila-de-conferencia) ────────────────────

        /** TOPs de destino possíveis pro faturamento da nota — só quando a CCO tem FATAOCONCLUIR='S'. */
        get("/sessoes/{id}/tops-faturamento") {
            val (slug, sessaoId, tenantId) = resolverSessao(call) ?: return@get
            try {
                call.respond(SeparacaoService.topsFaturamento(slug, tenantId, sessaoId))
            } catch (e: SeparacaoService.FaturamentoException) {
                call.respond(HttpStatusCode.Conflict, mapOf("erro" to (e.message ?: "não foi possível listar TOPs")))
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadGateway, mapOf("erro" to (e.message ?: "falha ao consultar TOPs no Sankhya")))
            }
        }

        /** Fatura a nota da sessão na TOP escolhida (SelecaoDocumentoSP.faturar). */
        post("/sessoes/{id}/faturar") {
            val (slug, sessaoId, tenantId) = resolverSessao(call) ?: return@post
            val body = call.receive<FaturarRequest>()
            try {
                SeparacaoService.faturar(slug, tenantId, sessaoId, body.codTipOper, body.serie)
                call.respond(mapOf("ok" to true))
            } catch (e: SeparacaoService.FaturamentoException) {
                call.respond(HttpStatusCode.Conflict, mapOf("erro" to (e.message ?: "não foi possível faturar")))
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadGateway, mapOf("erro" to (e.message ?: "falha ao faturar no Sankhya")))
            }
        }

        // ─── Etiquetas de volume (impressão no frontend) ─────────────────────

        get("/sessoes/{id}/etiquetas") {
            val (slug, sessaoId, tenantId) = resolverSessao(call) ?: return@get
            try {
                call.respond(SeparacaoService.dadosEtiqueta(slug, tenantId, sessaoId))
            } catch (e: SeparacaoService.FaturamentoException) {
                call.respond(HttpStatusCode.NotFound, mapOf("erro" to (e.message ?: "sessão não encontrada")))
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadGateway, mapOf("erro" to (e.message ?: "falha ao montar dados da etiqueta")))
            }
        }

        /** Conferências finalizadas pelo WMS — pra tela de reimpressão de etiquetas. Local, sem Sankhya. */
        get("/conferencias-finalizadas") {
            val slug = call.request.queryParameters["tenant"]
            if (slug.isNullOrBlank()) {
                call.respond(HttpStatusCode.BadRequest, mapOf("erro" to "query param 'tenant' é obrigatório"))
                return@get
            }
            val tenantId = resolverTenantId(slug)
                ?: return@get call.respond(HttpStatusCode.NotFound, mapOf("erro" to "tenant '$slug' não encontrado"))
            val nunota = call.request.queryParameters["nunota"]?.toLongOrNull()
            val numnota = call.request.queryParameters["numnota"]?.toLongOrNull()
            val page = call.request.queryParameters["page"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0
            val perPage = call.request.queryParameters["perPage"]?.toIntOrNull()?.coerceIn(1, 100) ?: 15
            call.respond(SeparacaoRepository.listarConferenciasFinalizadas(tenantId, nunota, numnota, page, perPage))
        }

        /** Reimpressão por número da nota (fora da tela de conferência). */
        get("/etiquetas") {
            val slug = call.request.queryParameters["tenant"]
            val nunota = call.request.queryParameters["nunota"]?.toLongOrNull()
            if (slug.isNullOrBlank() || nunota == null) {
                call.respond(HttpStatusCode.BadRequest, mapOf("erro" to "'tenant' e 'nunota' (query) são obrigatórios"))
                return@get
            }
            val tenantId = resolverTenantId(slug)
                ?: return@get call.respond(HttpStatusCode.NotFound, mapOf("erro" to "tenant '$slug' não encontrado"))
            try {
                call.respond(SeparacaoService.dadosEtiquetaPorNota(slug, tenantId, nunota))
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadGateway, mapOf("erro" to (e.message ?: "falha ao montar dados da etiqueta")))
            }
        }
    }
}

/** Preâmbulo comum das rotas de sessão: valida ?tenant=slug + {id} e resolve o tenantId. Responde 400/404 e devolve null se algo faltar. */
private suspend fun resolverSessao(call: io.ktor.server.application.ApplicationCall): Triple<String, UUID, UUID>? {
    val slug = call.request.queryParameters["tenant"]
    val sessaoId = call.parameters["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
    if (slug.isNullOrBlank() || sessaoId == null) {
        call.respond(HttpStatusCode.BadRequest, mapOf("erro" to "'tenant' (query) e id (path) são obrigatórios"))
        return null
    }
    val tenantId = resolverTenantId(slug)
    if (tenantId == null) {
        call.respond(HttpStatusCode.NotFound, mapOf("erro" to "tenant '$slug' não encontrado"))
        return null
    }
    return Triple(slug, sessaoId, tenantId)
}

private fun resolverTenantId(slug: String): UUID? =
    TenantRepository.buscarPorSlug(slug)?.id?.let { UUID.fromString(it) }
