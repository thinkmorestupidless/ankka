package com.thinkmorestupidless.ankka.proxy

/**
 * The proxy's living features, one suite per file, each run whole against the real proxy on
 * loopback (see `ProxySteps`). A forked test runs in the project's directory, so the features are
 * reached from `proxy/`.
 */
class RequestsFeature extends ProxySteps("../features/web-hosting/requests.feature")

class CallingServicesFeature extends ProxySteps("../features/web-hosting/calling-services.feature")
