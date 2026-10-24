package com.thinkmorestupidless.ankka.agent.javasdk;

import com.thinkmorestupidless.ankka.agent.javasdk.bridge.AgentCell;
import com.thinkmorestupidless.ankka.javasdk.client.ComponentClient;
import com.thinkmorestupidless.ankka.javasdk.client.ServiceClients;
import com.thinkmorestupidless.ankka.javasdk.secret.SecretStore;
import com.thinkmorestupidless.ankka.javasdk.storage.ObjectStorage;

/**
 * A component that carries out a request by talking to a model, one instance per session.
 *
 * <pre>{@code
 * @ComponentId("assistant")
 * public class Assistant extends Agent {
 *   @Command("ask")
 *   public AgentEffect<String> ask(String question) {
 *     return effects().systemMessage("You are helpful.").userMessage(question).thenReply();
 *   }
 * }
 * }</pre>
 *
 * A handler is a method annotated {@code @Command} returning an {@link AgentEffect}, or {@code
 * @Stream} returning a {@link StreamEffect}, taking one parameter or none; the annotation's string
 * is its wire name. Building an effect calls no model: the platform runs the turn once the handler
 * has returned, so a tool the effect names runs after it, on the turn's thread.
 */
public abstract class Agent {

  private final AgentCell cell = AgentCell.take();

  protected Agent() {}

  /** What this agent is, read once when the service starts from an instance built for it. */
  protected AgentSettings settings() {
    return AgentSettings.defaults();
  }

  /** Describes one interaction with a model. */
  protected final AgentEffects effects() {
    return cell.effects();
  }

  /** The session this instance serves. */
  protected final String sessionId() {
    return cell.sessionId();
  }

  protected final ComponentClient componentClient() {
    return cell.componentClient();
  }

  /** Other services, called as this one, for a tool that needs one. */
  protected final ServiceClients services() {
    return cell.services();
  }

  /** The service's secrets, for a tool that needs a credential. */
  protected final SecretStore secrets() {
    return cell.secrets();
  }

  protected final ObjectStorage storage() {
    return cell.storage();
  }
}
