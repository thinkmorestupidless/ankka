package com.thinkmorestupidless.ankka.agent.javasdk;

import com.thinkmorestupidless.ankka.agent.javasdk.bridge.AutonomousCell;
import com.thinkmorestupidless.ankka.javasdk.client.ComponentClient;
import com.thinkmorestupidless.ankka.javasdk.client.ServiceClients;
import com.thinkmorestupidless.ankka.javasdk.secret.SecretStore;
import com.thinkmorestupidless.ankka.javasdk.storage.ObjectStorage;
import java.util.List;
import java.util.Optional;

/**
 * An agent that is handed tasks and works each one until the model completes it with a result its
 * type's rules accept, fails it, or spends its budget. Every iteration is recorded, so an instance
 * that stops mid-task resumes where it was.
 *
 * <pre>{@code
 * @ComponentId("answerer")
 * public class Answerer extends AutonomousAgent {
 *   protected AutonomousDefinition definition() {
 *     return AutonomousDefinition.describedAs("Answers questions").accepts(Tasks.ANSWER, 5);
 *   }
 *   protected List<Object> tools() { return List.of(new Lookup(componentClient())); }
 * }
 * }</pre>
 *
 * What the agent is for and which tasks it takes is its {@link #definition()}, read once when the
 * service starts from an instance built for it. Its tools are read for each activation of an
 * instance, because a tool usually needs the component client, and the instance has one.
 */
public abstract class AutonomousAgent {

  private final AutonomousCell cell = AutonomousCell.take();

  protected AutonomousAgent() {}

  /** What the agent is for, how it behaves, and the task types it accepts. */
  protected abstract AutonomousDefinition definition();

  /**
   * The tools the model may call while working a task, besides completing or failing it: {@link
   * FunctionTool}s, objects whose {@link Tool} methods are tools, or classes built to be. A tool may
   * run again after a crash, so one with a side effect should tolerate being repeated.
   */
  protected List<Object> tools() {
    return List.of();
  }

  /** The instance this is: an autonomous agent is addressed by instance, not by session. */
  protected final String instanceId() {
    return cell.instanceId();
  }

  protected final ComponentClient componentClient() {
    return cell.componentClient();
  }

  protected final ServiceClients services() {
    return cell.services();
  }

  protected final SecretStore secrets() {
    return cell.secrets();
  }

  protected final ObjectStorage storage() {
    return cell.storage();
  }

  /** The task the calling thread is working on, inside a tool or a guardrail. */
  public static Optional<String> currentTask() {
    return AutonomousCell.currentTask();
  }
}
