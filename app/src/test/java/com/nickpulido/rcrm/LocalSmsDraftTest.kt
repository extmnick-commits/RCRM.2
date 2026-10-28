package com.nickpulido.rcrm

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalSmsDraftTest {

    @Test
    fun testGenerateOnUnsupportedEnvironmentReturnsValidDraft() {
        runBlocking {
            val draft = LocalSmsDraft.generate("John Doe", "Met at starbucks, interested in life insurance") { }
            println("Generated draft: $draft")
            assertTrue("Draft should not be empty", draft.isNotBlank())
            assertTrue("Draft should address John", draft.contains("John"))
        }
    }

    @Test
    fun testGenerateWithBlankInputsReturnsDefaultDraft() {
        runBlocking {
            val draft = LocalSmsDraft.generate("   ", "") { }
            println("Generated blank input draft: $draft")
            assertTrue("Draft should not be empty", draft.isNotBlank())
        }
    }
}
