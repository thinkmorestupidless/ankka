package com.thinkmorestupidless.ankka.agent.javasdk;

import com.thinkmorestupidless.ankka.javasdk.client.ComponentClient;
import com.thinkmorestupidless.ankka.javasdk.client.ServiceClients;
import com.thinkmorestupidless.ankka.javasdk.secret.SecretStore;
import com.thinkmorestupidless.ankka.javasdk.storage.ObjectStorage;

/** What a blueprint registry is built from when the service starts: what its tools may call. */
public interface BlueprintContext {

  ComponentClient componentClient();

  ServiceClients services();

  SecretStore secrets();

  ObjectStorage storage();
}
