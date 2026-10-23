package com.thinkmorestupidless.ankka.kotlin.reference

import com.thinkmorestupidless.ankka.javasdk.annotations.ComponentId
import com.thinkmorestupidless.ankka.javasdk.component.ChangeSource
import com.thinkmorestupidless.ankka.javasdk.component.Consumer
import com.thinkmorestupidless.ankka.javasdk.component.Contract
import com.thinkmorestupidless.ankka.javasdk.component.Publication
import com.thinkmorestupidless.ankka.javasdk.component.View
import com.thinkmorestupidless.ankka.javasdk.effect.ConsumerEffect
import com.thinkmorestupidless.ankka.javasdk.effect.ViewEffect
import com.thinkmorestupidless.ankka.kotlin.dsl.effects
import java.util.Optional
import java.util.OptionalInt

/** The latest message about each subject. Declares no start, so it reads from the earliest. */
@ComponentId("topic-rows")
class TopicRows : View<Fanned, Fanned>() {

    override fun source(): ChangeSource<Fanned> = ChangeSource.ofTopic(TOPIC, Fanned::class.java)

    // Raised from 1 once: every reference declares 2, which names the group it reads under.
    override fun version(): OptionalInt = OptionalInt.of(2)

    override fun onChange(message: Fanned): ViewEffect<Fanned> = effects { updateRow(message) }

    companion object {
        /** The topic the conformance target fills before the service starts. */
        const val TOPIC = "conformance-topic"
    }
}

/** Republishes what it reads, from the latest: none of what the topic held when it started. */
@ComponentId("topic-relay")
class TopicRelay : Consumer<Fanned, Fanned>() {

    override fun source(): ChangeSource<Fanned> = ChangeSource.ofTopic(TopicRows.TOPIC, Fanned::class.java).fromLatest()

    override fun publishes(): Optional<Publication> = Optional.of(Publication.to("conformance-topic-relayed"))

    override fun onChange(message: Fanned): ConsumerEffect<Fanned> = effects { publish(message) }
}

/**
 * Reads `conformance-contracts` as `order.v1` on broker `legacy`, partitions in parallel, and
 * publishes one more.
 */
@ComponentId("contract-relay")
class ContractRelay : Consumer<Fanned, Fanned>() {

    override fun source(): ChangeSource<Fanned> =
        ChangeSource.ofTopic("conformance-contracts", Fanned::class.java)
            .fromEarliest()
            .withContract(ORDER)
            .onBroker(BROKER)
            .parallel()

    override fun publishes(): Optional<Publication> =
        Optional.of(Publication.to("conformance-contracted").withContract(ORDER).onBroker(BROKER))

    override fun onChange(message: Fanned): ConsumerEffect<Fanned> = effects { publish(Fanned(message.n + 1)) }

    companion object {
        /** The contract every reference states, from the same schema document: the fixtures' one. */
        const val ORDER_SCHEMA =
            "{\"\$schema\":\"https://json-schema.org/draft/2020-12/schema\",\"type\":\"object\"," +
                "\"required\":[\"id\",\"total\"],\"properties\":{\"id\":{\"type\":\"string\"}," +
                "\"total\":{\"type\":\"number\"}}}"

        val ORDER: Contract = Contract.fromSchema("order.v1", ORDER_SCHEMA.toByteArray())

        /** The declared broker the reference names; every conformance target declares it. */
        const val BROKER = "legacy"
    }
}

/** Reads project `partner`'s `conformance-shared` and republishes to its other. */
@ComponentId("partner-relay")
class PartnerRelay : Consumer<Fanned, Fanned>() {

    override fun source(): ChangeSource<Fanned> =
        ChangeSource.ofTopic(PARTNER, "conformance-shared", Fanned::class.java).fromEarliest()

    override fun publishes(): Optional<Publication> = Optional.of(Publication.to(PARTNER, "conformance-shared-relayed"))

    override fun onChange(message: Fanned): ConsumerEffect<Fanned> = effects { publish(message) }

    companion object {
        const val PARTNER = "partner"
    }
}
