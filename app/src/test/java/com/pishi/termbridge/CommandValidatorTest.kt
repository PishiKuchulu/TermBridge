package com.pishi.termbridge

import com.pishi.termbridge.domain.CommandButton
import com.pishi.termbridge.domain.CommandMode
import com.pishi.termbridge.domain.CommandValidator
import com.pishi.termbridge.domain.CommandVariable
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandValidatorTest {
    private fun button() = CommandButton(
        hostId = 1L,
        label = "Check disk",
        mode = CommandMode.EXECUTE_ONE,
        template = "df -h {{path}}",
        variables = listOf(CommandVariable("path", "/"))
    )

    @Test
    fun validButtonHasNoIssues() {
        assertTrue(CommandValidator.validate(button()).isEmpty())
    }

    @Test
    fun blankLabelAndBlankTemplateAreReported() {
        val issues = CommandValidator.validate(
            button().copy(label = " ", template = "")
        )
        val fields = issues.map { it.field }.toSet()
        assertTrue(fields.contains("label"))
        assertTrue(fields.contains("template"))
    }

    @Test
    fun sequenceWithoutStepsIsRejected() {
        val issues = CommandValidator.validate(
            button().copy(mode = CommandMode.SEQUENCE, template = "", steps = emptyList())
        )
        assertTrue(issues.any { it.field == "steps" })
    }

    @Test
    fun singleCommandWithStepsIsRejected() {
        val issues = CommandValidator.validate(button().copy(steps = listOf("ls")))
        assertTrue(issues.any { it.field == "template" })
    }

    @Test
    fun placeholderWithoutDefinitionIsRejected() {
        val issues = CommandValidator.validate(
            button().copy(variables = emptyList())
        )
        assertTrue(issues.any { it.field == "variables" })
    }

    @Test
    fun duplicateAndInvalidVariableNamesAreRejected() {
        val issues = CommandValidator.validate(
            button().copy(
                template = "echo {{bad-name}}",
                variables = listOf(
                    CommandVariable("bad-name"),
                    CommandVariable("bad-name")
                )
            )
        )
        assertTrue(issues.any { it.field == "variables" })
    }

    @Test
    fun secretTemplateIsBlocked() {
        val issues = CommandValidator.validate(
            button().copy(template = "export token=abc123")
        )
        assertTrue(issues.any { it.message.contains("Refused") })
    }
}
