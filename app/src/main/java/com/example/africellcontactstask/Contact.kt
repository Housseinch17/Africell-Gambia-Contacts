package com.example.africellcontactstask

enum class ContactStatus {
    CHANGEABLE,     // old 7-digit format, falls in a known carrier block -> fix is auto-computed
    ERROR,          // looks like an attempted/near-Gambia number but can't be safely classified
    UNCHANGEABLE    // already correct, unaffected, international, or has no Gambia signal at all
}

data class Contact(
    val id: String,              // contact _ID from ContactsContract
    val name: String,
    var phoneNumber: String,
    var status: ContactStatus,
    var suggestedNumber: String? = null, // pre-computed corrected number, only set for CHANGEABLE
    var reason: String? = null,          // short human-readable explanation, set for ERROR and UNCHANGEABLE
    var resolved: Boolean = false,        // set true once the user applies/enters a fix or picks "keep as is"
    var selected: Boolean = false,       // checkbox state, only meaningful for CHANGEABLE
    var carrier: String? = null,         // "Africell" / "Qcell" / "Comium" when known, else null
    var isEditing: Boolean = false       // true while this ERROR contact's inline edit card is open
)