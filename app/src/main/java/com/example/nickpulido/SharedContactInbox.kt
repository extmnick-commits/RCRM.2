package com.nickpulido.rcrm

import android.content.Context
import androidx.core.content.edit
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.nickpulido.rcrm.domain.LabeledPhone
import com.nickpulido.rcrm.domain.SharedContactItem
import com.nickpulido.rcrm.domain.SmartLogPlanner
import org.json.JSONArray
import org.json.JSONObject

object SharedContactInbox {
    private const val PREFS = "shared_contact_inbox"
    private const val KEY_ITEMS = "pending_items_json"

    fun items(context: Context): List<SharedContactItem> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_ITEMS, null)
            ?: return emptyList()
        return decodeItems(raw)
    }

    fun add(context: Context, item: SharedContactItem) {
        val current = items(context).filterNot { it.id == item.id } + item
        save(context, current)
    }

    fun updateName(context: Context, id: String, name: String) {
        val updated = items(context).map { if (it.id == id) it.copy(name = name) else it }
        save(context, updated)
    }

    fun remove(context: Context, id: String) {
        val updated = items(context).filterNot { it.id == id }
        save(context, updated)
    }

    fun encodePhones(phones: List<LabeledPhone>): String {
        val arr = JSONArray()
        phones.forEach { phone ->
            arr.put(JSONObject().apply {
                put("label", phone.label)
                put("number", phone.number)
            })
        }
        return arr.toString()
    }

    fun decodePhones(raw: String?): List<LabeledPhone> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val number = obj.optString("number").trim()
                    if (number.isNotEmpty()) {
                        add(LabeledPhone(label = obj.optString("label").ifBlank { "Phone" }, number = number))
                    }
                }
            }
        }.getOrDefault(emptyList())
    }

    fun importToFirestore(
        context: Context,
        db: FirebaseFirestore,
        userId: String,
        itemsToImport: List<SharedContactItem>,
        onComplete: (importedCount: Int, error: String?) -> Unit
    ) {
        if (itemsToImport.isEmpty()) {
            onComplete(0, null)
            return
        }
        db.collection("leads").whereEqualTo("ownerId", userId).get()
            .addOnSuccessListener { snapshot ->
                val known = snapshot.documents.map { doc ->
                    doc.id to (doc.data?.toMutableMap() ?: mutableMapOf())
                }.toMutableList()
                val batch = db.batch()
                var imported = 0

                for (item in itemsToImport) {
                    val documentId = "shared_${item.id}"
                    if (known.any { it.first == documentId }) {
                        remove(context, item.id)
                        continue
                    }

                    val existingIndex = known.indexOfFirst { (_, data) ->
                        val primary = data["phone"] as? String
                        val additional = data["additionalPhoneNumbers"]
                        val email = (data["email"] as? String).orEmpty().trim()
                        val phoneMatch = item.phones.any { phone ->
                            SmartLogPlanner.leadMatchesPhone(phone.number, primary, additional)
                        }
                        val emailMatch = item.email.isNotBlank() && email.equals(item.email.trim(), ignoreCase = true)
                        phoneMatch || emailMatch
                    }

                    if (existingIndex >= 0) {
                        val (existingId, existingData) = known[existingIndex]
                        val ref = db.collection("leads").document(existingId)
                        val updates = mutableMapOf<String, Any>()
                        val existingPrimary = (existingData["phone"] as? String).orEmpty()
                        if (existingPrimary.isBlank() && item.phones.isNotEmpty()) {
                            updates["phone"] = item.phones.first().number
                            existingData["phone"] = item.phones.first().number
                        }
                        if ((existingData["email"] as? String).isNullOrBlank() && item.email.isNotBlank()) {
                            updates["email"] = item.email
                            existingData["email"] = item.email
                        }
                        if ((existingData["company"] as? String).isNullOrBlank() && item.company.isNotBlank()) {
                            updates["company"] = item.company
                            existingData["company"] = item.company
                        }
                        if ((existingData["jobTitle"] as? String).isNullOrBlank() && item.jobTitle.isNotBlank()) {
                            updates["jobTitle"] = item.jobTitle
                            existingData["jobTitle"] = item.jobTitle
                        }
                        if ((existingData["address"] as? String).isNullOrBlank() && item.address.isNotBlank()) {
                            updates["address"] = item.address
                            existingData["address"] = item.address
                        }
                        val mergedPhones = SmartLogPlanner.mergeAdditionalPhones(
                            existing = SmartLogPlanner.readAdditionalPhones(existingData["additionalPhoneNumbers"]),
                            incoming = item.phones,
                            primaryPhone = existingPrimary.ifBlank { null }
                        )
                        val serialized = SmartLogPlanner.serializeAdditionalPhones(mergedPhones)
                        updates["additionalPhoneNumbers"] = serialized
                        existingData["additionalPhoneNumbers"] = serialized
                        batch.set(ref, updates, SetOptions.merge())
                    } else {
                        val primaryPhone = item.phones.firstOrNull()?.number.orEmpty()
                        val resolvedName = item.name.trim().ifEmpty {
                            item.company.trim().ifEmpty { primaryPhone.ifEmpty { "Unknown Contact" } }
                        }
                        val ref = db.collection("leads").document(documentId)
                        val newData = hashMapOf<String, Any>(
                            "ownerId" to userId,
                            "name" to resolvedName,
                            "phone" to primaryPhone,
                            "additionalPhoneNumbers" to SmartLogPlanner.serializeAdditionalPhones(item.phones),
                            "email" to item.email,
                            "address" to item.address,
                            "company" to item.company,
                            "jobTitle" to item.jobTitle,
                            "category" to "Prospect",
                            "notes" to "",
                            "source" to "shared_contact",
                            "timestamp" to Timestamp.now()
                        )
                        batch.set(ref, newData, SetOptions.merge())
                        known.add(documentId to newData.toMutableMap())
                    }
                    imported++
                }

                if (imported == 0) {
                    onComplete(0, null)
                    return@addOnSuccessListener
                }

                batch.commit()
                    .addOnSuccessListener {
                        itemsToImport.forEach { remove(context, it.id) }
                        onComplete(imported, null)
                    }
                    .addOnFailureListener { error ->
                        onComplete(0, error.localizedMessage ?: "Could not import shared contacts.")
                    }
            }
            .addOnFailureListener { error ->
                onComplete(0, error.localizedMessage ?: "Could not load existing contacts.")
            }
    }

    private fun save(context: Context, list: List<SharedContactItem>) {
        val arr = JSONArray()
        list.forEach { item ->
            arr.put(JSONObject().apply {
                put("id", item.id)
                put("name", item.name)
                put("email", item.email)
                put("company", item.company)
                put("jobTitle", item.jobTitle)
                put("address", item.address)
                put("phones", JSONArray().apply {
                    item.phones.forEach { phone ->
                        put(JSONObject().apply {
                            put("label", phone.label)
                            put("number", phone.number)
                        })
                    }
                })
            })
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putString(KEY_ITEMS, arr.toString())
        }
    }

    private fun decodeItems(raw: String): List<SharedContactItem> = runCatching {
        val arr = JSONArray(raw)
        buildList {
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val phonesArr = obj.optJSONArray("phones") ?: JSONArray()
                val phones = buildList {
                    for (j in 0 until phonesArr.length()) {
                        val p = phonesArr.optJSONObject(j) ?: continue
                        val number = p.optString("number").trim()
                        if (number.isNotEmpty()) {
                            add(LabeledPhone(label = p.optString("label").ifBlank { "Phone" }, number = number))
                        }
                    }
                }
                add(
                    SharedContactItem(
                        id = obj.optString("id").ifBlank { java.util.UUID.randomUUID().toString() },
                        name = obj.optString("name"),
                        phones = phones,
                        email = obj.optString("email"),
                        company = obj.optString("company"),
                        jobTitle = obj.optString("jobTitle"),
                        address = obj.optString("address")
                    )
                )
            }
        }
    }.getOrDefault(emptyList())
}
