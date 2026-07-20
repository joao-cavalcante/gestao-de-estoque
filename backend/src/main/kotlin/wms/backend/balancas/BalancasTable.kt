package wms.backend.balancas

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp

/** app.balancas — só CONFIGURAÇÃO; a leitura de peso é 100% client-side (ver LocalScaleService no frontend). */
object BalancasTable : Table("app.balancas") {
    val id = uuid("id")
    val tenantId = uuid("tenant_id")
    val nome = text("nome")
    val fabricante = text("fabricante").nullable()
    val tipoComunicacao = text("tipo_comunicacao")
    val portaCom = text("porta_com").nullable()
    val baudRate = integer("baud_rate").nullable()
    val dataBits = integer("data_bits").nullable()
    val paridade = text("paridade").nullable()
    val stopBits = integer("stop_bits").nullable()
    val protocoloSerial = text("protocolo_serial").nullable()
    val ip = text("ip").nullable()
    val porta = integer("porta").nullable()
    val rota = text("rota").nullable()
    val ativo = bool("ativo")
    val criadoEm = timestamp("criado_em")
    val atualizadoEm = timestamp("atualizado_em")

    override val primaryKey = PrimaryKey(id)
}
