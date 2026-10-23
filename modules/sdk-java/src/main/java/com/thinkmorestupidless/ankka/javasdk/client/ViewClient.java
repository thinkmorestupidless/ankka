package com.thinkmorestupidless.ankka.javasdk.client;

import com.thinkmorestupidless.ankka.javasdk.component.KeyedView;
import com.thinkmorestupidless.ankka.javasdk.component.View;

/**
 * Queries of this service's views. Separate from the component client because a view is not
 * addressed by an instance id: it is queried by what its rows hold.
 */
public interface ViewClient {

  <Row> ViewQueries<Row> forView(Class<? extends View<?, Row>> view);

  <Row> ViewQueries<Row> forKeyedView(Class<? extends KeyedView<Row>> view);
}
