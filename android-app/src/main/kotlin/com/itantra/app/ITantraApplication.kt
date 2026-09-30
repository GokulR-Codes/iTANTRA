package com.itantra.app

import android.app.Application

/**
 * Composition root. Stays thin on purpose (`android-app/build.gradle.kts`'s own "wires all
 * other modules together but must stay thin — logic belongs in the owning module" principle):
 * [SessionService] constructs its own real dependencies (STT/TTS engines, transport, the
 * `orchestrator.MessageOrchestrator`) in [SessionService.startSession] rather than this class
 * pre-building and injecting them, because those dependencies are session-scoped (torn down
 * and rebuilt per session, per [SessionStateMachine]'s terminal-STOPPED design) rather than
 * application-scoped singletons — there is nothing genuinely application-lifetime-scoped for
 * this class to own yet. No DI framework (Hilt/Dagger/Koin) is introduced for this: adding one
 * for a single-service, single-activity app would be exactly the kind of unnecessary
 * dependency/rewrite Rule 5/§8 of the driving prompt warns against.
 */
class ITantraApplication : Application()
