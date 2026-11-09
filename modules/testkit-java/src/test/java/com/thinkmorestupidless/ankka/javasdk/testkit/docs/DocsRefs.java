package com.thinkmorestupidless.ankka.javasdk.testkit.docs;

import com.thinkmorestupidless.ankka.agent.javasdk.AgentCommandRef;
import com.thinkmorestupidless.ankka.javasdk.Done;
import com.thinkmorestupidless.ankka.javasdk.cart.LineItem;
import com.thinkmorestupidless.ankka.javasdk.cart.ShoppingCart;
import com.thinkmorestupidless.ankka.javasdk.client.CommandRef;
import com.thinkmorestupidless.ankka.javasdk.client.NoArgRef;

/** The method references the documentation suites call through: a Scala function is not one. */
public final class DocsRefs {

  private DocsRefs() {}

  public static final CommandRef<ShoppingCart, LineItem, Done> addItem = ShoppingCart::addItem;
  public static final CommandRef<PayoutWorkflow, PayoutWorkflow.Payout, Done> startPayout =
      PayoutWorkflow::start;
  public static final CommandRef<CheckoutLog, Long, Done> recordCheckout = CheckoutLog::record;
  public static final NoArgRef<Listing, Listing.ListingState> listingPhotos = Listing::photos;
  public static final CommandRef<Wallet, Long, Done> deposit = Wallet::deposit;
  public static final NoArgRef<Wallet, Wallet.Balance> balance = Wallet::balance;
  public static final CommandRef<TransferWorkflow, TransferWorkflow.Transfer, Done> startTransfer =
      TransferWorkflow::start;
  public static final CommandRef<OnboardingWorkflow, String, Done> startOnboarding =
      OnboardingWorkflow::start;
  public static final CommandRef<ChargeWorkflow, ChargeWorkflow.Charge, Done> startCharge =
      ChargeWorkflow::start;
  public static final CommandRef<Preferences, String, Done> setPreferences = Preferences::set;
  public static final AgentCommandRef<ActivityAgent, ActivityAgent.Request, String> consult =
      ActivityAgent::consult;
  public static final AgentCommandRef<WeatherAgent, String, String> weatherAsk = WeatherAgent::ask;
  public static final AgentCommandRef<WeatherAgent, String, WeatherAgent.Forecast>
      weatherStructured = WeatherAgent::askStructured;
  public static final AgentCommandRef<WeatherAgent, String, String> weatherGuarded =
      WeatherAgent::guarded;
  public static final AgentCommandRef<WeatherAgent, String, String> weatherSafely =
      WeatherAgent::askSafely;
  public static final AgentCommandRef<WeatherAgent, String, String> weatherShortMemory =
      WeatherAgent::askWithShortMemory;
  public static final AgentCommandRef<ApprovalAgent, String, String> approvalAsk =
      ApprovalAgent::ask;
  public static final AgentCommandRef<WalletAgent, String, String> walletAsk = WalletAgent::ask;
  public static final AgentCommandRef<Triage, String, String> route = Triage::route;
  public static final AgentCommandRef<TriageAgent, TriageAgent.Ticket, TriageAgent.Routing>
      routing = TriageAgent::routing;
  public static final AgentCommandRef<TriageAgent, String, String> guarded = TriageAgent::guarded;
  public static final AgentCommandRef<TicketAgent, String, String> ticketsAsk = TicketAgent::ask;
}
