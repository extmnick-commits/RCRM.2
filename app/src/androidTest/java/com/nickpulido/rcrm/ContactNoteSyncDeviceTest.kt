package com.nickpulido.rcrm

import android.content.ContentProviderOperation
import android.content.ContentUris
import android.content.Context
import android.provider.ContactsContract
import android.provider.ContactsContract.Data
import android.provider.ContactsContract.RawContacts
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ContactNoteSyncDeviceTest {
    @Test fun syncsPhoneAndCrmEditsAndPreservesConflicts() = runBlocking {
        withTimeout(120_000) {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val resolver = context.contentResolver
            val uid = checkNotNull(FirebaseAuth.getInstance().currentUser?.uid) { "Sign in to RCRM before running this test" }
            val db = FirebaseFirestore.getInstance()
            val doc = db.collection("leads").document()
            val prefsName = "contact_sync_device_test_${UUID.randomUUID()}"
            val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
            val number = "999000987654321"
            val ops = arrayListOf(
                ContentProviderOperation.newInsert(RawContacts.CONTENT_URI)
                    .withValue(RawContacts.ACCOUNT_NAME, null).withValue(RawContacts.ACCOUNT_TYPE, null).build(),
                ContentProviderOperation.newInsert(Data.CONTENT_URI).withValueBackReference(Data.RAW_CONTACT_ID, 0)
                    .withValue(Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                    .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, "RCRM temporary sync test").build(),
                ContentProviderOperation.newInsert(Data.CONTENT_URI).withValueBackReference(Data.RAW_CONTACT_ID, 0)
                    .withValue(Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                    .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, number).build()
            )
            val rawUri = resolver.applyBatch(ContactsContract.AUTHORITY, ops)[0].uri!!
            try {
                val rawId = ContentUris.parseId(rawUri)
                val contactId = resolver.query(rawUri, arrayOf(RawContacts.CONTACT_ID), null, null, null)!!.use {
                    check(it.moveToFirst()); it.getLong(0)
                }
                val store = ContactNoteStore(context)
                val contact = checkNotNull(store.readContacts()[number]?.singleOrNull { it.id == contactId })
                checkNotNull(store.readNote(contactId)).also { check(it.rawId == rawId) }
                doc.set(mapOf("ownerId" to uid, "name" to "RCRM temporary sync test", "phone" to number, "notes" to "CRM initial" )).await()
                suspend fun sync() {
                    val snapshot = doc.get(Source.SERVER).await()
                    ContactNoteReconciler.sync(db, store, prefs, uid, number, snapshot, contact) { true }
                }
                sync()
                assertEquals("CRM initial", store.readNote(contactId)!!.text)
                store.writeNote(contactId, store.readNote(contactId)!!, "Phone changed")
                sync()
                assertEquals("Phone changed", doc.get(Source.SERVER).await().getString("notes"))
                doc.update("notes", "CRM changed").await()
                sync()
                assertEquals("CRM changed", store.readNote(contactId)!!.text)
                store.writeNote(contactId, store.readNote(contactId)!!, "Phone conflict")
                doc.update("notes", "CRM conflict").await()
                sync()
                val expected = "CRM conflict\n\n[Phone note conflict]: Phone conflict"
                assertEquals(expected, store.readNote(contactId)!!.text)
                assertEquals(expected, doc.get(Source.SERVER).await().getString("notes"))
            } finally {
                resolver.delete(rawUri, null, null)
                try { withContext(NonCancellable) { withTimeout(30_000) { doc.delete().await() } } }
                finally { context.deleteSharedPreferences(prefsName) }
            }
        }
    }
}
