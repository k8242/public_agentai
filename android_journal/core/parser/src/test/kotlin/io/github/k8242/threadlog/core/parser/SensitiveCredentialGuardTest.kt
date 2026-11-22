// Copyright (c) 2026 k8242
// SPDX-License-Identifier: MIT

package io.github.k8242.threadlog.core.parser

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SensitiveCredentialGuardTest {

    @Test
    fun `rejects passwords with explicit separators`() {
        val blocked = listOf(
            "password: hunter2",
            "Password=MySecret123!",
            "pw: abc123",
            "PW=9876",
            "p/w: Summer2026",
            "passwd: rootpass",
            "passcode: 998877",
            "passphrase -> correct horse",
            "pw - secret",
            "password: \"let me in\"",
            "pw: xxx",
            "password=xx",
        )

        blocked.forEach { sample ->
            val result = SensitiveCredentialGuard.validate(sample)
            assertThat(result).isInstanceOf(CredentialCheckResult.Rejected::class.java)
        }
    }

    @Test
    fun `rejects PINs with separators connectors and bare numeric values`() {
        val blocked = listOf(
            "pin: 4829",
            "PIN=0451",
            "pin code: 123456",
            "pincode: 9090",
            "pin# 4829",
            "pin #4829",
            "ATM pin 4829",
            "door pin is 1234",
            "bank pin was 0000",
            "passcode changed to 8844",
            "pin: xxx",
            "pin 48a9",
        )

        blocked.forEach { sample ->
            val result = SensitiveCredentialGuard.validate(sample)
            assertThat(result).isInstanceOf(CredentialCheckResult.Rejected::class.java)
        }
    }

    @Test
    fun `rejects natural language and shorthand credential assignments`() {
        val blocked = listOf(
            "my password is hunter2",
            "wifi pw is Summer2026!",
            "pw hunter2",
            "pw 123456",
            "pw secret",
            "p/w mypass",
            "password 12345",
            "password hunter2",
            "password MySecret",
            "password \"my secret\"",
        )

        blocked.forEach { sample ->
            val result = SensitiveCredentialGuard.validate(sample)
            assertThat(result).isInstanceOf(CredentialCheckResult.Rejected::class.java)
        }
    }

    @Test
    fun `allows xxxx placeholders in all forms`() {
        val allowed = listOf(
            "password: xxxx",
            "password=XXXX",
            "pw: xxxx.",
            "pw xxxx",
            "my password is xxxx",
            "pin: xxxx",
            "ATM pin xxxx",
            "pin #xxxx",
            "passcode=xxxxxx",
            "wifi pw is \"xxxx\"",
            "bank pin is `XXXX`, reminder only",
        )

        allowed.forEach { sample ->
            val result = SensitiveCredentialGuard.validate(sample)
            assertThat(result).isEqualTo(CredentialCheckResult.Allowed)
        }
    }

    @Test
    fun `allows unix pwd commands and ordinary prose without false positives`() {
        val allowed = listOf(
            "pwd",
            "ran pwd /home/antigravity",
            "pwd: /var/log/syslog",
            "pwd = /tmp/build",
            "That is my opinion on the design",
            "Put a spin on it and pinned the message",
            "Safety pin in backpack",
            "Pin this note to the top",
            "Change password tomorrow",
            "Reset password link arrived by email",
            "Installed a password manager today",
            "My password is expired and not working",
            "Card pin is locked after 3 tries",
            "Reminder about #password and #pin hygiene",
            "password:",
            "Updated work password\n4829 items synced",
        )

        allowed.forEach { sample ->
            val result = SensitiveCredentialGuard.validate(sample)
            assertThat(result).isEqualTo(CredentialCheckResult.Allowed)
        }
    }

    @Test
    fun `maskDetectedWithXxxx replaces all secrets and passes validation`() {
        val input = "Wifi pw: Summer2026! and door pin is 4829. Also pw: \"secret pass\""
        val masked = SensitiveCredentialGuard.maskDetectedWithXxxx(input)

        assertThat(masked).isEqualTo("Wifi pw: xxxx! and door pin is xxxx. Also pw: \"xxxx\"")
        assertThat(SensitiveCredentialGuard.validate(masked)).isEqualTo(CredentialCheckResult.Allowed)
    }

    @Test
    fun `maskDetectedWithXxxx preserves trailing punctuation and hash prefix on pin`() {
        val input = "Door pin #4829, gate pin: 0451."
        val masked = SensitiveCredentialGuard.maskDetectedWithXxxx(input)

        assertThat(masked).isEqualTo("Door pin #xxxx, gate pin: xxxx.")
        assertThat(SensitiveCredentialGuard.validate(masked)).isEqualTo(CredentialCheckResult.Allowed)
    }
}
