package com.nickpulido.rcrm

import java.util.Locale

data class ParsedBusinessCard(
    val name: String?,
    val phone: String?,
    val email: String?,
    val address: String?,
    val company: String?,
    val title: String?,
    val extraNotes: String?
)

class BusinessCardParser {
    fun parse(text: String): ParsedBusinessCard {
        val lines = text.split("\n").map { it.trim() }.filter { it.isNotEmpty() }

        // --- 1. Smart Phone Number Selection ---
        val phoneRegex = Regex("(\\+?\\d{1,2}\\s?)?\\(?\\d{3}\\)?[\\s.-]?\\d{3}[\\s.-]?\\d{4}")
        var bestPhone: String? = null
        var fallbackPhone: String? = null

        for (line in lines) {
            val match = phoneRegex.find(line)
            if (match != null) {
                val lowerLine = line.lowercase()
                if (lowerLine.contains("f") || lowerLine.contains("fax")) continue // Skip fax numbers
                if (fallbackPhone == null) fallbackPhone = match.value
                
                // Prioritize mobile/cell/direct over generic or office numbers
                if (lowerLine.contains("m") || lowerLine.contains("c") || lowerLine.contains("cell") || lowerLine.contains("mobile") || lowerLine.contains("direct")) {
                    bestPhone = match.value
                }
            }
        }
        val finalPhone = bestPhone ?: fallbackPhone

        // --- 2. Email Extraction ---
        val emailRegex = Regex("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}")
        val emailMatch = emailRegex.find(text)
        val extractedEmail = emailMatch?.value

        // --- 3. Smart Company & Title Extraction ---
        val titleKeywords = listOf("manager", "director", "president", "ceo", "engineer", "agent", "representative", "consultant", "founder", "owner", "specialist", "vp", "vice president", "associate", "broker", "realtor", "officer", "advisor")
        val companyKeywords = listOf("llc", "inc", "corp", "ltd", "company", "group", "solutions", "enterprises", "partners", "agency")

        var detectedTitle: String? = null
        var detectedCompany: String? = null
        var companyLineUsed: String? = null

        val genericDomains = listOf("gmail", "yahoo", "hotmail", "outlook", "aol", "icloud", "msn", "me", "live")
        if (extractedEmail != null) {
            val domainPart = extractedEmail.substringAfter("@").substringBeforeLast(".")
            if (domainPart.lowercase() !in genericDomains) {
                val companyLine = lines.firstOrNull { 
                    it.contains(domainPart, ignoreCase = true) && !it.contains("@") && !it.lowercase().contains("www.")
                }
                if (companyLine != null) {
                    detectedCompany = companyLine
                    companyLineUsed = companyLine
                } else {
                    detectedCompany = domainPart.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
                }
            }
        }

        // --- 4. Smart Name Extraction ---
        val possibleName = lines.firstOrNull { line ->
            val lowerLine = line.lowercase()
            !line.contains(Regex("\\d")) && 
            !line.contains("@") && 
            line.split(Regex("\\s+")).size in 2..4 &&
            !titleKeywords.any { lowerLine.contains(it) } &&
            !companyKeywords.any { lowerLine.contains(it) }
        }
        
        for (line in lines) {
            val lowerLine = line.lowercase()
            if (line == possibleName || line.contains("@") || line.contains(Regex("\\d"))) continue
            
            if (detectedTitle == null && titleKeywords.any { lowerLine.contains(it) }) {
                detectedTitle = line
            } else if (detectedCompany == null && companyKeywords.any { lowerLine.contains(it) }) {
                detectedCompany = line
                companyLineUsed = line
            }
        }

        // --- 5. Smart Address Extraction ---
        var detectedAddress: String? = null
        val addressKeywords = listOf(
            "street", "st.", " st ", "avenue", "ave.", " ave ", "boulevard", "blvd", 
            "road", "rd.", " rd ", "drive", "dr.", " dr ", "suite", "ste.", " ste ", "pkwy", "parkway", 
            "lane", "ln.", "court", "ct.", "plaza", "way", "po box", "p.o. box", "floor", "fl.", "highway", "hwy",
            "bldg", "building", "terrace", "circle", "cir", "trail", "trl", "square", "sq"
        )
        val zipRegex = Regex("\\b\\d{5}(?:-\\d{4})?\\b")
        val stateZipRegex = Regex("\\b[A-Za-z]{2}[\\s,]+\\d{5}(?:-\\d{4})?\\b")
        val addressParts = mutableListOf<String>()
        
        for (i in lines.indices) {
            val line = lines[i]
            val lowerLine = line.lowercase()
            
            val startsWithNumber = Regex("^\\s*\\d+.*").matches(line)
            val isPoBox = lowerLine.contains("po box") || lowerLine.contains("p.o. box")
            val hasKeyword = addressKeywords.any { lowerLine.contains(it) }
            val hasZip = zipRegex.containsMatchIn(line)
            val hasStateZip = stateZipRegex.containsMatchIn(line)
            
            if ((startsWithNumber && hasKeyword) || isPoBox || hasStateZip) {
                detectedAddress = line
                addressParts.add(line)
                
                if (!hasZip) {
                    var lookahead = 1
                    while (i + lookahead < lines.size && lookahead <= 3) {
                        val nextLine = lines[i + lookahead]
                        val lowerNext = nextLine.lowercase()
                        
                        val hasEmail = nextLine.contains("@")
                        val hasWeb = lowerNext.contains("www.") || lowerNext.contains(".com")
                        val hasPhone = Regex("(\\+?\\d{1,2}\\s?)?\\(?\\d{3}\\)?[\\s.-]?\\d{3}[\\s.-]?\\d{4}").containsMatchIn(nextLine)
                        
                        if (hasEmail || hasWeb || hasPhone) break
                        
                        detectedAddress += ", $nextLine"
                        addressParts.add(nextLine)
                        
                        if (zipRegex.containsMatchIn(nextLine)) {
                            break
                        }
                        lookahead++
                    }
                }
                break
            }
        }

        val unusedLines = lines.toMutableList()
        if (finalPhone != null) unusedLines.removeAll { it.contains(finalPhone) }
        if (extractedEmail != null) unusedLines.removeAll { it.contains(extractedEmail) }
        if (possibleName != null) unusedLines.remove(possibleName)
        if (detectedTitle != null) unusedLines.remove(detectedTitle)
        if (companyLineUsed != null) unusedLines.remove(companyLineUsed)
        unusedLines.removeAll(addressParts)

        val leftoverText = unusedLines.joinToString("\n").trim()
        val parsedNotes = if (leftoverText.isNotEmpty()) "[Scanned Card Extra Info]:\n$leftoverText" else null
        
        return ParsedBusinessCard(
            name = possibleName,
            phone = finalPhone,
            email = extractedEmail,
            address = detectedAddress,
            company = detectedCompany,
            title = detectedTitle,
            extraNotes = parsedNotes
        )
    }
}