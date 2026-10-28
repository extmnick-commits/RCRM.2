package com.nickpulido.rcrm

import org.junit.Assert.assertEquals
import org.junit.Test

class ContactNoteMergeTest {
    @Test fun propagatesPhoneEdit() = assertEquals("phone edit", ContactNoteMerge.merge("old", "phone edit", "old"))
    @Test fun propagatesCrmEdit() = assertEquals("crm edit", ContactNoteMerge.merge("old", "old", "crm edit"))
    @Test fun propagatesDeletion() = assertEquals("", ContactNoteMerge.merge("old", "", "old"))
    @Test fun retainsConcurrentEdits() = assertEquals("crm edit\n\n[Phone note conflict]: phone edit", ContactNoteMerge.merge("old", "phone edit", "crm edit"))
    @Test fun retainsConflictingClear() = assertEquals("crm edit\n\n[Phone note conflict]: [Phone note was cleared]", ContactNoteMerge.merge("old", "", "crm edit"))
    @Test fun retainsConflictingSubstring() = assertEquals("abcd\n\n[Phone note conflict]: abc", ContactNoteMerge.merge("old", "abc", "abcd"))
    @Test fun initialImportAvoidsDuplicateText() = assertEquals("history: phone note", ContactNoteMerge.merge(null, "phone note", "history: phone note"))
    @Test fun initialImportPreservesBothSides() = assertEquals("crm\n\nphone", ContactNoteMerge.merge(null, "phone", "crm"))
}
