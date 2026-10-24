package com.thinkmorestupidless.ankka.kotlin.reference

import com.thinkmorestupidless.ankka.javasdk.annotations.Action
import com.thinkmorestupidless.ankka.javasdk.annotations.ComponentId
import com.thinkmorestupidless.ankka.javasdk.component.TimedAction
import com.thinkmorestupidless.ankka.javasdk.effect.TimedActionEffect
import com.thinkmorestupidless.ankka.kotlin.dsl.effects

/** A timed action that records on the conformance entity it is given. */
// docs:start action
@ComponentId("reminder")
class Reminder : TimedAction() {

    @Action("remind")
    fun remind(id: String): TimedActionEffect {
        record(id, "reminded")
        return effects { done() }
    }

    /** Records the due time it was run for, as the runtime told it. */
    @Action("tick")
    fun tick(id: String): TimedActionEffect {
        record(id, "due:${context().dueTime().toEpochMilli()}")
        return effects { done() }
    }

    private fun record(id: String, what: String) {
        context().componentClient().forEventSourcedEntity(id).call(ConformanceEntity::record).invoke(what)
    }
}
// docs:end action
