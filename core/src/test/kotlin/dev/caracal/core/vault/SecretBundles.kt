package dev.caracal.core.vault

import dev.caracal.engine.api.SecretBundle
import kotlin.test.assertIs

/**
 * Test-side conveniences for the one shape most of this module still deals in.
 *
 * A password is what the connection form collects and what every record written
 * before Phase 4 holds, so a test that is about something else — sealing, the
 * registry's fingerprint, a service call — says `password("hunter2")` and does not
 * spell out the bundle each time. The tests that are about the other shapes name
 * them in full, which is the point of not hiding them behind a helper as well.
 */
internal fun password(value: String): SecretBundle = SecretBundle.Password(value.toCharArray())

/** The characters of a bundle that is expected to be a password, and fails loudly if not. */
internal fun SecretBundle.passwordText(): String = String(assertIs<SecretBundle.Password>(this).password)

/** The same, for a record straight out of [Seal.open]. */
internal fun VaultRecord.passwordText(): String = secret.passwordText()
