package dev.rboza.android.opensyncforproton

import dev.rboza.android.opensyncforproton.proton.ProtonConfig

/**
 * The real composition root with one change: the Proton server is the one a test sets, and with none set the client
 * can't be built at all, so an instrumented test never reaches Proton. That failure is the engine's (a failed run,
 * which the coordinator, the worker, and the adapter already handle); only a direct `newLoginInteractor()` call throws
 * it.
 */
class E2eApp : OspApp() {
    @Volatile var testServer: ProtonConfig? = null

    override val protonConfig: ProtonConfig
        get() = checkNotNull(testServer) { "no test server set" }
}
