package com.framepersecond.m3bitwarden.autofill.model

/**
 * Platform-neutral snapshot of one view in the screen the system asks us to fill.
 *
 * Android builds this from `AssistStructure.ViewNode`; tests build it by hand. Only
 * structural metadata is kept here, never a value the user typed into a field.
 */
data class AutofillNode(
    /** Opaque id the platform uses to address the view when filling. */
    val id: String?,
    val kind: NodeKind = NodeKind.Other,
    /** Android `InputType` bit mask, 0 when unknown (for example on the web). */
    val inputType: Int = 0,
    /** Android `autofillHints`, already lower-cased. */
    val autofillHints: List<String> = emptyList(),
    /** HTML tag for web content (`input`, `form`, `button` …), lower-cased. */
    val htmlTag: String? = null,
    /** HTML attributes for web content, keys and values lower-cased. */
    val htmlAttributes: Map<String, String> = emptyMap(),
    /** Visible text of labels and buttons. Never read from input fields. */
    val text: String? = null,
    /** Placeholder or `hint` of an input. */
    val hint: String? = null,
    /** Resource entry name (`R.id.password` → "password") or HTML id/name. */
    val idEntry: String? = null,
    val contentDescription: String? = null,
    val isFocused: Boolean = false,
    val isVisible: Boolean = true,
    val isImportantForAutofill: Boolean = true,
    /** `maxlength` for web inputs, `-1` when not set. */
    val maxLength: Int = -1,
    val webDomain: String? = null,
    val webScheme: String? = null,
    /** Visible option texts of a select / list field (`<select>`, spinner), in order. */
    val options: List<String> = emptyList(),
    val children: List<AutofillNode> = emptyList(),
) {
    /** Anything we can fill: text inputs and lists (selects). */
    val isInput: Boolean get() = kind == NodeKind.TextInput || kind == NodeKind.Select
    val isSelect: Boolean get() = kind == NodeKind.Select
}

enum class NodeKind {
    TextInput,

    /** A list the platform fills by index: HTML `<select>`, Android spinners. */
    Select,
    Button,
    Label,
    Container,
    Other,
}

/** What we believe a field is for. */
enum class FieldType {
    Username,
    Email,
    Password,
    NewPassword,
    ConfirmPassword,
    Otp,
    OtpDigit,
    CardNumber,
    CardHolder,
    CardExpiry,
    CardExpiryMonth,
    CardExpiryYear,
    CardCvv,

    /** Card network select or field ("Visa", "Mastercard"), filled from the card's brand. */
    CardBrand,
    FullName,
    FirstName,
    LastName,
    Phone,

    /** Split phone fields: "+49" | "089" | "1234567" (US: area | 3 digits | 4 digits). */
    PhoneCountryCode,
    PhoneAreaCode,
    PhoneLocal,

    /** Street and house number in one field ("Musterstraße 12"). */
    Address,

    /** Street name only, when the house number has its own field. */
    Street,
    HouseNumber,

    /** Apartment, c/o, floor ("Adresszusatz"). */
    AddressLine2,
    PostalCode,
    City,

    /** State, province, region ("Bundesland"). */
    State,
    Country,
    Organization,

    /** Salutation or honorific ("Anrede", "Herr/Frau", "Dr."). */
    Title,
    Unknown,
    ;

    val isPassword: Boolean get() = this == Password || this == NewPassword || this == ConfirmPassword
    val isIdentifier: Boolean get() = this == Username || this == Email
    val isCard: Boolean
        get() = this == CardNumber || this == CardHolder || this == CardExpiry ||
            this == CardExpiryMonth || this == CardExpiryYear || this == CardCvv || this == CardBrand
}

/** A classified input with the evidence that led to the decision. */
data class DetectedField(
    val id: String,
    val type: FieldType,
    /** 0–100; values below [com.framepersecond.m3bitwarden.autofill.classify.FieldClassifier.MIN_CONFIDENCE] are dropped. */
    val confidence: Int,
    val signals: List<String>,
    val isFocused: Boolean,
    /** Position in document order, used to pair usernames with passwords. */
    val order: Int,
    val maxLength: Int,
    val webDomain: String?,
    val webScheme: String?,
    /** What the field shows about the expected format, used when filling. */
    val format: FieldFormat = FieldFormat(),
    /** Which `<form>` on the page the field belongs to (0 = none), so two forms are never mixed. */
    val formIndex: Int = 0,
)

/** Format clues of one field: placeholder text, length limit and list options. */
data class FieldFormat(
    val placeholder: String? = null,
    val maxLength: Int = -1,
    val options: List<String> = emptyList(),
    val isSelect: Boolean = false,
)
