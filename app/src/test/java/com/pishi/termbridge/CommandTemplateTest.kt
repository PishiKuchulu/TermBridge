package com.pishi.termbridge

import com.pishi.termbridge.domain.CommandButton
import com.pishi.termbridge.domain.CommandMode
import com.pishi.termbridge.domain.CommandTemplate
import com.pishi.termbridge.domain.CommandVariable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class CommandTemplateTest {
    @Test
    fun extractsPlaceholdersInOrderWithoutDuplicates() {
        assertEquals(
            listOf("path", "user"),
            CommandTemplate.extractPlaceholders("ls {{path}} {{path}} {{user}}")
        )
    }

    @Test
    fun shellQuotesValuesSoInjectionBecomesOneWord() {
        val rendered = CommandTemplate.render(
            "ls {{path}}",
            mapOf("path" to "a b; rm -rf /")
        )
        assertEquals("ls 'a b; rm -rf /'", rendered)
    }

    @Test
    fun singleQuoteInValueIsEscaped() {
        assertEquals(
            "echo 'it'\\''s'",
            CommandTemplate.render("echo {{word}}", mapOf("word" to "it's"))
        )
    }

    @Test
    fun missingValueThrows() {
        try {
            CommandTemplate.render("ls {{path}}", emptyMap())
            fail("Expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("path"))
        }
    }

    @Test
    fun malformedPlaceholderIsRejected() {
        assertTrue(CommandTemplate.hasMalformedPlaceholder("ls {{ path }}"))
        assertTrue(CommandTemplate.hasMalformedPlaceholder("ls {{}}"))
        assertEquals(
            false,
            CommandTemplate.hasMalformedPlaceholder("ls {{path}}")
        )
    }

    @Test
    fun defaultsFillMissingAndRequiredThrows() {
        val button = CommandButton(
            hostId = 1L,
            label = "List",
            mode = CommandMode.EXECUTE_ONE,
            template = "ls {{path}} {{mode}}",
            variables = listOf(
                CommandVariable("path", "/tmp"),
                CommandVariable("mode", required = true)
            )
        )
        try {
            CommandTemplate.prepare(button, emptyMap())
            fail("Expected IllegalArgumentException for required mode")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("mode"))
        }
        val prepared = CommandTemplate.prepare(button, mapOf("mode" to "-la"))
        assertEquals("ls '/tmp' '-la'", prepared.rendered)
    }

    @Test
    fun suppliedValueForUndeclaredVariableIsIgnored() {
        val button = CommandButton(
            hostId = 1L,
            label = "List",
            mode = CommandMode.EXECUTE_ONE,
            template = "ls {{path}}",
            variables = listOf(CommandVariable("path", "/tmp"))
        )
        val prepared = CommandTemplate.prepare(
            button,
            mapOf("path" to "/var", "evil" to "; rm -rf /")
        )
        assertEquals("ls '/var'", prepared.rendered)
    }

    @Test
    fun sequenceStepsAreRenderedInOrder() {
        val button = CommandButton(
            hostId = 1L,
            label = "Two steps",
            mode = CommandMode.SEQUENCE,
            steps = listOf("cd {{dir}}", "ls"),
            variables = listOf(CommandVariable("dir", "/tmp"))
        )
        val prepared = CommandTemplate.prepare(button, emptyMap())
        assertEquals(listOf("cd '/tmp'", "ls"), prepared.renderedSteps)
    }
}
