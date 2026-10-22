package com.nickpulido.rcrm.ui.components

enum class PipelineType(val label: String) {
    SALES("Client Sales"),
    RECRUITING("Downline Recruiting")
}

enum class SalesStage(val id: String, val title: String, val shortLabel: String) {
    NEW_LEAD("new_lead", "New Lead", "New"),
    CONTACTED("contacted", "Contacted", "Contacted"),
    APPT_SET("appt_set", "Appointment Set", "Appt Set"),
    PRESENTATION("presentation", "Kitchen Table / FNA", "Presentation"),
    APP_SUBMITTED("app_submitted", "App Submitted", "Submitted"),
    IN_FORCE("in_force", "Client / In-Force", "In-Force")
}

enum class RecruitStage(val id: String, val title: String, val shortLabel: String) {
    PROSPECT("prospect", "Recruit Prospect", "Prospect"),
    INVITED_BOP("invited_bop", "Invited to Overview", "Invited"),
    INTERVIEWED("interviewed", "Interview Completed", "Interviewed"),
    IBA_SUBMITTED("iba_submitted", "IBA Submitted", "IBA Signed"),
    LICENSING("licensing", "Licensing / Fast Start", "Licensing"),
    LICENSED_ACTIVE("licensed_active", "Licensed & Field Trained", "Licensed")
}

data class PipelineLead(
    val id: String,
    val name: String,
    val phone: String,
    val category: String,
    val targetMarketPoints: Int,
    val salesStage: SalesStage,
    val recruitStage: RecruitStage,
    val lastNotePreview: String,
    val followUpDateLabel: String?,
    val isPinned: Boolean
)
