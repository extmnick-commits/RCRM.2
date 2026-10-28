package com.nickpulido.rcrm

/** Resolves two note copies relative to their last successfully synchronized value. */
object ContactNoteMerge {
    fun merge(base: String?, phone: String, crm: String): String {
        if (phone == crm) return phone
        if (base != null) {
            if (phone == base) return crm
            if (crm == base) return phone
            // Preserve both changes even when one is a substring of the other or was cleared.
            val crmVersion = crm.ifBlank { "[RCRM note was cleared]" }
            val phoneVersion = phone.ifBlank { "[Phone note was cleared]" }
            return "$crmVersion\n\n[Phone note conflict]: $phoneVersion"
        }
        if (phone.isBlank()) return crm
        if (crm.isBlank()) return phone
        if (crm.contains(phone)) return crm
        if (phone.contains(crm)) return phone
        return "$crm\n\n$phone"
    }
}
