package com.thinkmorestupidless.ankka.kotlin.agent.samples

import com.thinkmorestupidless.ankka.agent.javasdk.Agent
import com.thinkmorestupidless.ankka.agent.javasdk.AgentEffect
import com.thinkmorestupidless.ankka.agent.javasdk.Tool
import com.thinkmorestupidless.ankka.javasdk.annotations.Command
import com.thinkmorestupidless.ankka.javasdk.annotations.ComponentId
import com.thinkmorestupidless.ankka.javasdk.annotations.Describe
import com.thinkmorestupidless.ankka.javasdk.client.ServiceClients
import com.thinkmorestupidless.ankka.kotlin.agent.effects

// docs:start service-call-tool
/** A support agent whose tool reads a balance from the wallet service, as its own service. */
@ComponentId("wallet-agent")
class WalletAgent : Agent() {

    class Tools(private val services: ServiceClients) {
        @Tool("read_balance", description = "Reads a customer's wallet balance.")
        fun readBalance(@Describe("The customer whose balance to read.") customer: String): String =
            services.of("wallet").getText("/wallet/balances/$customer")
    }

    @Command("ask")
    fun ask(question: String): AgentEffect<String> = effects {
        systemMessage("You answer questions about wallets.").userMessage(question).tools(Tools(services())).thenReply()
    }
}
// docs:end service-call-tool
