package com.nickpulido.rcrm

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Locale

object SettingsDataHelpers {

    private val auth get() = FirebaseAuth.getInstance()
    private val db get() = FirebaseFirestore.getInstance()

    fun syncPresetsToCloud(list: List<String>) {
        val userId = auth.currentUser?.uid ?: return
        db.collection("user_settings").document(userId).set(mapOf("sms_presets" to list), SetOptions.merge())
    }

    fun exportContactsToCsv(activity: AppCompatActivity) {
        val userId = auth.currentUser?.uid ?: return
        db.collection("leads").whereEqualTo("ownerId", userId).get().addOnSuccessListener { snapshot ->
            if (snapshot.isEmpty) {
                Toast.makeText(activity, "No contacts to export", Toast.LENGTH_SHORT).show()
                return@addOnSuccessListener
            }

            try {
                val csvContent = StringBuilder()
                csvContent.append("Name,Phone,Email,Address,Company,Job Title,Category,Notes,Follow-Up Date\n")

                val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

                for (doc in snapshot.documents) {
                    val name = doc.getString("name")?.replace(",", " ") ?: ""
                    val phone = doc.getString("phone") ?: ""
                    val email = doc.getString("email")?.replace(",", " ") ?: ""
                    val address = doc.getString("address")?.replace(",", " ") ?: ""
                    val company = doc.getString("company")?.replace(",", " ") ?: ""
                    val title = doc.getString("jobTitle")?.replace(",", " ") ?: ""
                    val category = doc.getString("category")?.replace(",", " ") ?: ""
                    val notes = doc.getString("notes")?.replace(",", " ")?.replace("\n", " ") ?: ""
                    val followUpDate = doc.getTimestamp("followUpDate")?.toDate()?.let { sdf.format(it) } ?: ""

                    csvContent.append(
                        "\"$name\",\"$phone\",\"$email\",\"$address\",\"$company\",\"$title\",\"$category\",\"$notes\",\"$followUpDate\"\n"
                    )
                }

                val fileName = "RCRM_Contacts_Export_${System.currentTimeMillis()}.csv"
                val file = File(activity.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), fileName)
                FileOutputStream(file).use { it.write(csvContent.toString().toByteArray()) }

                val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", file)
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/csv"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                try {
                    activity.startActivity(Intent.createChooser(intent, "Export CSV"))
                } catch (_: Exception) {
                    Toast.makeText(activity, "No app available to share export", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Toast.makeText(activity, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    fun performMassDelete(activity: AppCompatActivity, onComplete: () -> Unit = {}) {
        val userId = auth.currentUser?.uid ?: return
        db.collection("leads").whereEqualTo("ownerId", userId).get().addOnSuccessListener { snapshot ->
            val batch = db.batch()
            for (doc in snapshot.documents) {
                batch.delete(doc.reference)
            }
            batch.commit().addOnSuccessListener {
                Toast.makeText(activity, "All contacts deleted successfully", Toast.LENGTH_SHORT).show()
                onComplete()
            }
        }
    }

    fun writeBackupToFile(context: Context, uri: Uri) {
        val userId = auth.currentUser?.uid ?: return
        Toast.makeText(context, "Fetching data for backup...", Toast.LENGTH_SHORT).show()

        db.collection("leads").whereEqualTo("ownerId", userId).get().addOnSuccessListener { snapshot ->
            val leadsList = snapshot.documents.map { it.data ?: emptyMap<String, Any>() }

            db.collection("user_settings").document(userId).get().addOnCompleteListener { task ->
                val settingsData = if (task.isSuccessful) {
                    task.result?.data ?: emptyMap<String, Any>()
                } else {
                    emptyMap<String, Any>()
                }

                val backupMap = mapOf(
                    "leads" to leadsList,
                    "settings" to settingsData
                )

                try {
                    val backupDataToSave = toJsonObject(backupMap).toString(4)
                    context.contentResolver.openOutputStream(uri)?.use {
                        it.write(backupDataToSave.toByteArray())
                    }
                    Toast.makeText(context, "Backup saved!", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(context, "Error generating backup: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.addOnFailureListener { e ->
            Toast.makeText(context, "Failed to fetch leads: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    fun readBackupFromFile(context: Context, uri: Uri, onComplete: () -> Unit = {}) {
        try {
            val content = context.contentResolver.openInputStream(uri)?.bufferedReader().use { it?.readText() }
            if (content != null) {
                restoreData(context, JSONObject(content), onComplete)
            }
        } catch (e: Exception) {
            Toast.makeText(context, "Failed to read backup file: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun restoreData(context: Context, json: JSONObject, onComplete: () -> Unit) {
        val userId = auth.currentUser?.uid ?: return

        try {
            val writes = mutableListOf<Pair<com.google.firebase.firestore.DocumentReference, Map<String, Any>>>()

            if (json.has("leads")) {
                val leadsArr = json.getJSONArray("leads")
                for (i in 0 until leadsArr.length()) {
                    val leadObj = leadsArr.getJSONObject(i)
                    @Suppress("UNCHECKED_CAST")
                    val map = unwrapJsonValue(leadObj) as? Map<String, Any> ?: continue
                    val mutableMap = map.toMutableMap()
                    mutableMap["ownerId"] = userId
                    writes.add(Pair(db.collection("leads").document(), mutableMap))
                }
            }

            var settingsMap: Map<String, Any>? = null
            if (json.has("settings")) {
                val settingsObj = json.getJSONObject("settings")
                @Suppress("UNCHECKED_CAST")
                settingsMap = unwrapJsonValue(settingsObj) as? Map<String, Any>
            }

            val chunks = writes.chunked(490)
            var completedChunks = 0
            var hasError = false

            fun finishRestore() {
                if (settingsMap != null && settingsMap.isNotEmpty()) {
                    db.collection("user_settings").document(userId)
                        .set(settingsMap, SetOptions.merge())
                        .addOnCompleteListener {
                            Toast.makeText(context, "Restore successful!", Toast.LENGTH_SHORT).show()
                            onComplete()
                        }
                } else {
                    Toast.makeText(context, "Restore successful!", Toast.LENGTH_SHORT).show()
                    onComplete()
                }
            }

            if (chunks.isEmpty()) {
                finishRestore()
                return
            }

            for (chunk in chunks) {
                val batch = db.batch()
                for ((ref, data) in chunk) {
                    batch.set(ref, data)
                }
                batch.commit().addOnSuccessListener {
                    if (hasError) return@addOnSuccessListener
                    completedChunks++
                    if (completedChunks == chunks.size) {
                        finishRestore()
                    }
                }.addOnFailureListener { e ->
                    if (!hasError) {
                        hasError = true
                        Toast.makeText(context, "Restore failed: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        } catch (e: Exception) {
            Toast.makeText(context, "Invalid backup format: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun toJsonObject(map: Map<String, Any?>): JSONObject {
        val json = JSONObject()
        for ((k, v) in map) {
            json.put(k, wrapJsonValue(v))
        }
        return json
    }

    @Suppress("UNCHECKED_CAST")
    private fun wrapJsonValue(value: Any?): Any? {
        return when (value) {
            is Map<*, *> -> toJsonObject(value as Map<String, Any?>)
            is List<*> -> {
                val array = JSONArray()
                for (item in value) {
                    array.put(wrapJsonValue(item))
                }
                array
            }
            is Timestamp -> {
                val tsMap = mapOf("_type" to "timestamp", "seconds" to value.seconds, "nanoseconds" to value.nanoseconds)
                toJsonObject(tsMap)
            }
            is Number, is String, is Boolean -> value
            null -> JSONObject.NULL
            else -> value.toString()
        }
    }

    private fun unwrapJsonValue(value: Any): Any? {
        return when (value) {
            is JSONObject -> {
                val map = mutableMapOf<String, Any?>()
                val keys = value.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    map[k] = unwrapJsonValue(value.get(k))
                }
                if (map["_type"] == "timestamp") {
                    val seconds = (map["seconds"] as? Number)?.toLong() ?: 0L
                    val nanos = (map["nanoseconds"] as? Number)?.toInt() ?: 0
                    Timestamp(seconds, nanos)
                } else {
                    map
                }
            }
            is JSONArray -> {
                val list = mutableListOf<Any?>()
                for (i in 0 until value.length()) {
                    list.add(unwrapJsonValue(value.get(i)))
                }
                list
            }
            JSONObject.NULL -> null
            else -> value
        }
    }
}
