package com.nickpulido.rcrm

import android.Manifest
import android.content.ContentProviderOperation
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.Note as NoteKind
import android.provider.ContactsContract.Data
import android.provider.ContactsContract.RawContacts
import android.telephony.PhoneNumberUtils
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.Source
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/** Synchronizes only uniquely matched leads and contacts. The baseline is device and account local. */
class ContactNoteSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val preferences = applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val userId = FirebaseAuth.getInstance().currentUser?.uid ?: return@withContext Result.success()
        if (!preferences.getBoolean(enabledKey(userId), false) || !hasPermissions(applicationContext)) return@withContext Result.success()
        try {
            val db = FirebaseFirestore.getInstance()
            val leads = db.collection("leads").whereEqualTo("ownerId", userId).get(Source.SERVER).await().documents
            val leadNumbers = leads.mapNotNull { doc -> normalized(doc.getString("phone"))?.let { it to doc } }
                .groupBy({ it.first }, { it.second })
            val store = ContactNoteStore(applicationContext)
            val contacts = store.readContacts()
            val candidateCounts = leadNumbers.flatMap { (number, docs) ->
                if (docs.size == 1 && contacts[number]?.size == 1) listOf(contacts.getValue(number).single().id)
                else emptyList()
            }.groupingBy { it }.eachCount()
            var failed = false
            for ((number, docs) in leadNumbers) {
                if (isStopped || FirebaseAuth.getInstance().currentUser?.uid != userId ||
                    !preferences.getBoolean(enabledKey(userId), false)) break
                val matches = contacts[number] ?: continue
                if (docs.size != 1 || matches.size != 1) continue
                val doc = docs.single()
                val contact = matches.single()
                if (candidateCounts[contact.id] != 1) continue
                try {
                    ContactNoteReconciler.sync(db, store, preferences, userId, number, doc, contact) {
                        !isStopped && FirebaseAuth.getInstance().currentUser?.uid == userId &&
                            preferences.getBoolean(enabledKey(userId), false) && hasPermissions(applicationContext)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failed = true
                    Log.w(TAG, "Unable to sync one contact", e)
                }
            }
            if (failed) Result.retry() else Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Contact note sync could not finish; it will retry", e)
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "ContactNoteSync"
        const val PREFS = "contact_note_sync"
        private const val WORK = "contact_note_sync_periodic"
        fun enabledKey(userId: String) = "enabled:$userId"
        fun hasPermissions(context: Context) = listOf(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS)
            .all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
        fun normalized(value: String?): String? = value?.let { PhoneNumberUtils.normalizeNumber(it) }
            ?.takeIf { it.count(Char::isDigit) >= 7 }
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<ContactNoteSyncWorker>(15, TimeUnit.MINUTES).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, request)
        }
        fun cancel(context: Context) = WorkManager.getInstance(context).cancelUniqueWork(WORK)
    }
}

internal object ContactNoteReconciler {
    suspend fun sync(
        db: FirebaseFirestore,
        store: ContactNoteStore,
        preferences: android.content.SharedPreferences,
        userId: String,
        number: String,
        doc: DocumentSnapshot,
        contact: ContactNoteStore.Contact,
        isActive: () -> Boolean
    ) {
        val key = "$userId:${doc.id}"
        val savedLink = preferences.getString("link:$key", null)
        if (savedLink != null && savedLink != contact.lookupKey) return
        val phoneNote = store.readNote(contact.id) ?: return
        val crm = doc.getString("notes").orEmpty()
        val base = preferences.getString("base:$key", null)
        val merged = ContactNoteMerge.merge(base, phoneNote.text, crm)
        db.runTransaction { transaction ->
            val fresh = transaction.get(doc.reference)
            if (!fresh.exists() || fresh.getString("ownerId") != userId ||
                fresh.getString("notes").orEmpty() != crm ||
                ContactNoteSyncWorker.normalized(fresh.getString("phone")) != number) {
                throw IllegalStateException("Lead changed during contact sync")
            }
            if (merged != crm) transaction.update(doc.reference, "notes", merged)
        }.await()
        if (!isActive()) return
        if (merged != phoneNote.text) store.writeNote(contact.id, phoneNote, merged)
        preferences.edit().putString("link:$key", contact.lookupKey).putString("base:$key", merged).commit()
    }
}

internal class ContactNoteStore(private val context: Context) {
    data class Contact(val id: Long, val lookupKey: String)
    data class Note(val id: Long?, val rawId: Long, val storedText: String?) {
        val text: String get() = storedText.orEmpty()
    }

    fun readContacts(): Map<String, Set<Contact>> {
        val result = mutableMapOf<String, MutableSet<Contact>>()
        context.contentResolver.query(Phone.CONTENT_URI,
            arrayOf(Phone.CONTACT_ID, Phone.LOOKUP_KEY, Phone.NUMBER), null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val number = ContactNoteSyncWorker.normalized(cursor.getString(2)) ?: continue
                val lookup = cursor.getString(1) ?: continue
                result.getOrPut(number) { mutableSetOf() }.add(Contact(cursor.getLong(0), lookup))
            }
        } ?: throw IllegalStateException("Contacts unavailable")
        return result
    }

    fun readNote(contactId: Long): Note? {
        val resolver = context.contentResolver
        val rows = mutableListOf<Note>()
        resolver.query(Data.CONTENT_URI, arrayOf(Data._ID, Data.RAW_CONTACT_ID, NoteKind.NOTE),
            "${Data.CONTACT_ID} = ? AND ${Data.MIMETYPE} = ?",
            arrayOf(contactId.toString(), NoteKind.CONTENT_ITEM_TYPE), null)?.use { cursor ->
            while (cursor.moveToNext()) rows.add(Note(cursor.getLong(0), cursor.getLong(1), cursor.getString(2)))
        } ?: throw IllegalStateException("Contact notes unavailable")
        if (rows.size > 1) return null
        if (rows.size == 1) return rows.single()
        val ids = mutableListOf<Long>()
        resolver.query(RawContacts.CONTENT_URI, arrayOf(RawContacts._ID), "${RawContacts.CONTACT_ID} = ? AND ${RawContacts.DELETED} = 0",
            arrayOf(contactId.toString()), null)?.use { cursor ->
            while (cursor.moveToNext()) ids.add(cursor.getLong(0))
        } ?: throw IllegalStateException("Raw contacts unavailable")
        return ids.singleOrNull()?.let { Note(null, it, "") }
    }

    fun writeNote(contactId: Long, previous: Note, text: String) {
        val selection = "${Data.CONTACT_ID} = ? AND ${Data.MIMETYPE} = ?"
        val args = arrayOf(contactId.toString(), NoteKind.CONTENT_ITEM_TYPE)
        val operations = arrayListOf<ContentProviderOperation>()
        // Guard against concurrent edits or another note row being added after the read.
        val assertion = ContentProviderOperation.newAssertQuery(Data.CONTENT_URI)
            .withSelection(selection, args).withExpectedCount(if (previous.id == null) 0 else 1)
        if (previous.id != null) assertion.withValue(Data._ID, previous.id).withValue(NoteKind.NOTE, previous.storedText)
        operations.add(assertion.build())
        if (previous.id == null) {
            operations.add(ContentProviderOperation.newInsert(Data.CONTENT_URI)
                .withValue(Data.RAW_CONTACT_ID, previous.rawId).withValue(Data.MIMETYPE, NoteKind.CONTENT_ITEM_TYPE)
                .withValue(NoteKind.NOTE, text).build())
        } else {
            operations.add(ContentProviderOperation.newUpdate(Data.CONTENT_URI)
                .withSelection("${Data._ID} = ?", arrayOf(previous.id.toString()))
                .withValue(NoteKind.NOTE, text).withExpectedCount(1).build())
        }
        context.contentResolver.applyBatch(ContactsContract.AUTHORITY, operations)
    }
}

