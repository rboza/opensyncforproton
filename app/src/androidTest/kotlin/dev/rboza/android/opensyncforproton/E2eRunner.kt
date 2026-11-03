package dev.rboza.android.opensyncforproton

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner

/** Runs the instrumented tests in [E2eApp], the real composition root with the Proton server a test sets. */
class E2eRunner : AndroidJUnitRunner() {
    override fun newApplication(cl: ClassLoader, className: String, context: Context): Application =
        super.newApplication(cl, E2eApp::class.java.name, context)
}
