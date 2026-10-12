package shoppingcart.api

import com.thinkmorestupidless.ankka.http.Caller

/** A caller as one word, as a grant names it: what the wallet's and affiliates' routes answer. */
object Who:
  def apply(caller: Caller): String = caller match
    case Caller.Gateway                => "gateway"
    case Caller.Service(project, name) => s"service:$project/$name"
    case Caller.Machine(org, name)     => s"machine:$org/$name"
    case Caller.Local                  => "local"
