package com.raju.aiassistant.domain.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WakeWordTest {
    @Test
    fun nameAtTheStartWakesAndKeepsTheRequest() {
        assertEquals("time kya hua?", afterWakeWord("Seema, time kya hua?"))
        assertEquals("", afterWakeWord("hey सीमा"))
        assertEquals("गाना चलाओ", afterWakeWord("अरे सीमा गाना चलाओ"))
        assertEquals("batao", afterWakeWord("Sima batao"))
        assertEquals("", afterWakeWord("सीमाजी"))
    }

    @Test
    fun otherTalkDoesNotWake() {
        assertNull(afterWakeWord("TV pe news chal rahi hai"))
        assertNull(afterWakeWord("maine kal bazaar mein Seema ko dekha")) // her name buried in other talk
        assertNull(afterWakeWord("seemant aaya")) // a different word that starts the same
    }
}
