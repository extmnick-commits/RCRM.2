package com.nickpulido.rcrm.domain

import java.io.ByteArrayOutputStream
import java.util.UUID

data class SharedContactItem(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val phones: List<LabeledPhone>,
    val email: String = "",
    val company: String = "",
    val jobTitle: String = "",
    val address: String = ""
)

object VCardParser {
    fun parse(raw: String): List<SharedContactItem> {
        val unfolded = unfoldLines(raw)
        val cards = mutableListOf<SharedContactItem>()
        var currentLines: MutableList<String>? = null

        for (line in unfolded) {
            val trimmed = line.trim()
            if (trimmed.equals("BEGIN:VCARD", ignoreCase = true)) {
                currentLines = mutableListOf()
            } else if (trimmed.equals("END:VCARD", ignoreCase = true)) {
                currentLines?.let { lines ->
                    parseCard(lines)?.let(cards::add)
                }
                currentLines = null
            } else if (currentLines != null && trimmed.isNotEmpty()) {
                currentLines.add(line)
            }
        }
        return cards
    }

    private fun parseCard(lines: List<String>): SharedContactItem? {
        var formattedName = ""
        var structuredName = ""
        val phones = mutableListOf<LabeledPhone>()
        var email = ""
        var company = ""
        var jobTitle = ""
        var address = ""

        for (rawLine in lines) {
            val colonIndex = rawLine.indexOf(':')
            if (colonIndex <= 0) continue

            val header = rawLine.substring(0, colonIndex)
            val rawValue = rawLine.substring(colonIndex + 1)
            val parts = header.split(';')
            val propName = parts.firstOrNull()?.substringAfterLast('.')?.uppercase().orEmpty()
            val params = parts.drop(1).map { it.trim().uppercase() }
            val isQuotedPrintable = params.any { it == "ENCODING=QUOTED-PRINTABLE" || it == "QUOTED-PRINTABLE" }
            val value = if (isQuotedPrintable) decodeQuotedPrintable(rawValue) else unescapeValue(rawValue)

            when (propName) {
                "FN" -> if (formattedName.isBlank()) formattedName = value.trim()
                "N" -> if (structuredName.isBlank()) {
                    val nameParts = value.split(';')
                    val family = nameParts.getOrNull(0)?.trim().orEmpty()
                    val given = nameParts.getOrNull(1)?.trim().orEmpty()
                    val middle = nameParts.getOrNull(2)?.trim().orEmpty()
                    structuredName = listOf(given, middle, family).filter { it.isNotEmpty() }.joinToString(" ")
                }
                "TEL" -> {
                    val number = value.trim()
                    if (number.isNotEmpty() && phones.none { SmartLogPlanner.phonesMatch(it.number, number) }) {
                        phones.add(LabeledPhone(label = extractPhoneLabel(params), number = number))
                    }
                }
                "EMAIL" -> if (email.isBlank()) email = value.trim()
                "ORG" -> if (company.isBlank()) {
                    company = value.split(';').map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()
                }
                "TITLE" -> if (jobTitle.isBlank()) jobTitle = value.trim()
                "ADR" -> if (address.isBlank()) {
                    address = value.split(';')
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                        .joinToString(", ")
                }
            }
        }

        val resolvedName = formattedName.ifBlank { structuredName }
        if (phones.isEmpty() && resolvedName.isBlank() && email.isBlank()) return null

        return SharedContactItem(
            name = resolvedName,
            phones = phones,
            email = email,
            company = company,
            jobTitle = jobTitle,
            address = address
        )
    }

    private fun extractPhoneLabel(params: List<String>): String {
        val types = params.flatMap { param ->
            if (param.startsWith("TYPE=")) param.removePrefix("TYPE=").split(',') else listOf(param)
        }.map { it.trim() }

        return when {
            "CELL" in types || "MOBILE" in types -> "Mobile"
            "WORK" in types -> "Work"
            "HOME" in types -> "Home"
            "MAIN" in types -> "Main"
            "FAX" in types -> "Fax"
            "IPHONE" in types -> "iPhone"
            "OTHER" in types -> "Other"
            else -> "Phone"
        }
    }

    private fun unfoldLines(raw: String): List<String> {
        val normalized = raw.replace("\r\n", "\n").replace("\r", "\n")
        val rawLines = normalized.split('\n')
        val result = mutableListOf<String>()
        for (line in rawLines) {
            if ((line.startsWith(" ") || line.startsWith("\t")) && result.isNotEmpty()) {
                result[result.lastIndex] = result.last() + line.drop(1)
            } else if (result.isNotEmpty() && result.last().endsWith("=") && isQuotedPrintableContinuation(result.last())) {
                result[result.lastIndex] = result.last().dropLast(1) + line
            } else {
                result.add(line)
            }
        }
        return result
    }

    private fun isQuotedPrintableContinuation(line: String): Boolean =
        line.contains("QUOTED-PRINTABLE", ignoreCase = true) || line.contains("=")

    private fun decodeQuotedPrintable(input: String): String {
        val out = ByteArrayOutputStream()
        var i = 0
        while (i < input.length) {
            val ch = input[i]
            if (ch == '=' && i + 2 < input.length) {
                val hex = input.substring(i + 1, i + 3)
                val byteVal = hex.toIntOrNull(16)
                if (byteVal != null) {
                    out.write(byteVal)
                    i += 3
                    continue
                }
            }
            out.write(ch.code)
            i++
        }
        return unescapeValue(out.toString(Charsets.UTF_8.name()))
    }

    private fun unescapeValue(value: String): String =
        value.replace("\\n", "\n")
            .replace("\\N", "\n")
            .replace("\\,", ",")
            .replace("\\;", ";")
            .replace("\\\\", "\\")
}
