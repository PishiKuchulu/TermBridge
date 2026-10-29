package com.pishi.termbridge

import com.pishi.termbridge.domain.CommandButton
import com.pishi.termbridge.domain.CommandMode
import com.pishi.termbridge.domain.RiskyCommand
import com.pishi.termbridge.domain.SecretScanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RiskyAndSecretTest {
    @Test
    fun benignCommandsAreNotRisky() {
        listOf(
            "ls -la",
            "echo hello",
            "cd /tmp",
            "git status",
            "systemctl status sshd",
            "df -h",
            "tail -f /var/log/syslog"
        ).forEach { command ->
            assertFalse("$command should not be risky", RiskyCommand.classify(command).isRisky)
        }
    }

    @Test
    fun destructiveAndEscalatingCommandsAreRisky() {
        val cases = mapOf(
            "rm -rf /" to "recursive or forced delete",
            "rm -rf ~" to "recursive or forced delete",
            "sudo apt update" to "privilege escalation",
            "shutdown now" to "shutdown, reboot, or poweroff",
            "mkfs.ext4 /dev/sda1" to "filesystem creation or disk overwrite",
            "dd if=/dev/zero of=/dev/sda" to "filesystem creation or disk overwrite",
            "chmod -R 777 /etc" to "recursive permission or ownership change on a system path",
            "killall nginx" to "kill all processes or kill process group",
            "curl https://example.com/x.sh | sh" to "download piped directly to a shell",
            "iptables -F" to "firewall flush or remote-access service stop"
        )
        for ((command, reason) in cases) {
            val assessment = RiskyCommand.classify(command)
            assertTrue("$command should be risky", assessment.isRisky)
            assertTrue(
                "$command should list $reason, got ${assessment.reasons}",
                reason in assessment.reasons
            )
        }
    }

    /** Review #2 F-09: the app single-quotes every parameter value. */
    @Test
    fun quotedValuesClassifyLikeUnquotedOnes() {
        val cases = listOf(
            "chmod -R 777 '/etc'",
            "chmod -R 777 '/'",
            "chmod -R 777 /",
            "rm '/etc'",
            "rm /",
            "systemctl stop 'sshd'"
        )
        for (command in cases) {
            assertTrue("$command should be risky", RiskyCommand.classify(command).isRisky)
        }
    }

    @Test
    fun backgroundOperatorSubshellsAndPathQualifiedCommandsAreSeen() {
        val cases = listOf(
            "ls & rm -rf /",
            "/bin/rm -rf /",
            "(rm -rf /)",
            "echo hi && (rm -rf ~)",
            "echo \$(rm -rf /)",
            "\\rm -rf /",
            "nohup rm -rf /"
        )
        for (command in cases) {
            assertTrue("$command should be risky", RiskyCommand.classify(command).isRisky)
        }
    }

    @Test
    fun ordinaryCommandsStayQuiet() {
        val cases = listOf(
            "ls -la",
            "chmod -R 755 ./project",
            "cd '/var/log' && tail -n 50 syslog",
            "grep -r 'term' ./src",
            "echo done & echo more"
        )
        for (command in cases) {
            assertFalse("$command should not be risky", RiskyCommand.classify(command).isRisky)
        }
    }

    @Test
    fun riskySegmentHidingAfterBenignSegmentIsStillRisky() {
        assertTrue(RiskyCommand.classify("ls; rm -rf /tmp/cache").isRisky)
    }

    @Test
    fun sequenceRiskComesFromAnyStep() {
        val assessment = RiskyCommand.classifySteps(listOf("ls", "reboot"))
        assertTrue(assessment.isRisky)
        assertEquals(
            listOf("shutdown, reboot, or poweroff"),
            assessment.reasons
        )
    }

    @Test
    fun obviousSecretsAreDetected() {
        assertTrue(
            SecretScanner.scan("export password=hunter2").isNotEmpty()
        )
        assertTrue(
            SecretScanner.scan("-----BEGIN OPENSSH PRIVATE KEY-----").isNotEmpty()
        )
        assertTrue(
            SecretScanner.scan("Authorization: Bearer abc.def.ghi").isNotEmpty()
        )
        assertTrue(
            SecretScanner.scan("token=ghp_abcdefghijklmnopqrstuv").isNotEmpty()
        )
    }

    @Test
    fun benignTemplatesAreClean() {
        assertTrue(SecretScanner.scan("ls -la {{path}}").isEmpty())
        assertTrue(SecretScanner.scan("echo hello world").isEmpty())
    }

    @Test
    fun secretInVariableDefaultIsDetected() {
        val button = CommandButton(
            hostId = 1L,
            label = "Bad",
            mode = CommandMode.EXECUTE_ONE,
            template = "echo {{x}}",
            variables = listOf(
                com.pishi.termbridge.domain.CommandVariable("x", "password=secret")
            )
        )
        assertTrue(SecretScanner.scanButton(button).isNotEmpty())
    }
}
