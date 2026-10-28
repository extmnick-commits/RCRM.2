package com.nickpulido.rcrm

import android.content.ContentProviderOperation
import android.content.ContentUris
import android.provider.ContactsContract
import android.provider.ContactsContract.RawContacts
import android.provider.ContactsContract.Data
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ContactNoteStoreDeviceTest {
    @Test fun notesCanBeCreatedUpdatedAndProtectedFromConcurrentChanges() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val resolver = context.contentResolver
        val ops = arrayListOf(
            ContentProviderOperation.newInsert(RawContacts.CONTENT_URI)
                .withValue(RawContacts.ACCOUNT_NAME, null).withValue(RawContacts.ACCOUNT_TYPE, null).build(),
            ContentProviderOperation.newInsert(Data.CONTENT_URI)
                .withValueBackReference(Data.RAW_CONTACT_ID, 0)
                .withValue(Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, "RCRM temporary sync test")
                .build(),
            ContentProviderOperation.newInsert(Data.CONTENT_URI)
                .withValueBackReference(Data.RAW_CONTACT_ID, 0)
                .withValue(Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, "999000123456789")
                .build()
        )
        val rawUri = resolver.applyBatch(ContactsContract.AUTHORITY, ops)[0].uri!!
        val rawId = ContentUris.parseId(rawUri)
        try {
            val contactId = resolver.query(rawUri, arrayOf(RawContacts.CONTACT_ID), null, null, null)!!.use {
                check(it.moveToFirst()); it.getLong(0)
            }
            val store = ContactNoteStore(context)
            val contact = store.readContacts()["999000123456789"]?.singleOrNull { it.id == contactId }
            assertNotNull(contact)
            val empty = store.readNote(contactId)!!
            assertEquals("", empty.text)
            store.writeNote(contactId, empty, "Phone test note")
            val first = store.readNote(contactId)!!
            assertEquals("Phone test note", first.text)
            store.writeNote(contactId, first, "CRM test note")
            assertEquals("CRM test note", store.readNote(contactId)!!.text)
            assertThrows(Exception::class.java) { store.writeNote(contactId, first, "stale overwrite") }
            assertEquals("CRM test note", store.readNote(contactId)!!.text)
        } finally {
            resolver.delete(rawUri, null, null)
        }
    }
}

