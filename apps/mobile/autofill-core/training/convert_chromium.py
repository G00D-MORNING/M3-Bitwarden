#!/usr/bin/env python3
"""Converts Chromium's autofill heuristics test corpus (BSD-licensed, real-world forms with
expected field types) into the TSV format ModelTrainer reads.

Input:  <dir>/input/*.html and <dir>/output/*.out from
        chromium/src/components/test/data/autofill/heuristics
Output: one line per field (see FIELDS below).

The .out files list, per field in document order: TYPE | name | label | value | section.
The label is Chrome's computed label, i.e. what Chrome hands to Android autofill services.
"""
import html.parser
import os
import re
import sys

FIELDS = ["form", "source", "kind", "type", "name", "id", "autocomplete", "placeholder", "aria", "label", "maxlength", "options", "expected"]

CHROME_TO_OURS = {
    "NAME_FIRST": "FirstName", "NAME_LAST": "LastName", "NAME_LAST_SECOND": "LastName", "NAME_LAST_FIRST": "LastName",
    "NAME_FULL": "FullName", "NAME_HONORIFIC_PREFIX": "Title", "COMPANY_NAME": "Organization", "EMAIL_ADDRESS": "Email",
    "PHONE_HOME_WHOLE_NUMBER": "Phone", "PHONE_HOME_CITY_AND_NUMBER": "Phone", "PHONE_HOME_NUMBER": "Phone",
    "PHONE_HOME_CITY_AND_NUMBER_WITHOUT_TRUNK_PREFIX": "Phone", "PHONE_HOME_WHOLE_NUMBER_WITHOUT_TRUNK_PREFIX": "Phone",
    "ADDRESS_HOME_LINE1": "Address", "ADDRESS_HOME_STREET_ADDRESS": "Address", "ADDRESS_HOME_LINE2": "AddressLine2",
    "ADDRESS_HOME_LINE3": "AddressLine2", "ADDRESS_HOME_APT_NUM": "AddressLine2", "ADDRESS_HOME_STREET_NAME": "Street",
    "ADDRESS_HOME_HOUSE_NUMBER": "HouseNumber", "ADDRESS_HOME_CITY": "City", "ADDRESS_HOME_STATE": "State",
    "ADDRESS_HOME_ZIP": "PostalCode", "ADDRESS_HOME_COUNTRY": "Country",
    "CREDIT_CARD_TYPE": "CardBrand", "CREDIT_CARD_NAME_FULL": "CardHolder", "CREDIT_CARD_NAME_FIRST": "CardHolder", "CREDIT_CARD_NAME_LAST": "CardHolder",
    "CREDIT_CARD_NUMBER": "CardNumber", "CREDIT_CARD_EXP_MONTH": "CardExpiryMonth", "CREDIT_CARD_EXP_2_DIGIT_YEAR": "CardExpiryYear",
    "CREDIT_CARD_EXP_4_DIGIT_YEAR": "CardExpiryYear", "CREDIT_CARD_EXP_DATE_2_DIGIT_YEAR": "CardExpiry",
    "CREDIT_CARD_EXP_DATE_4_DIGIT_YEAR": "CardExpiry", "CREDIT_CARD_VERIFICATION_CODE": "CardCvv",
    "PHONE_HOME_COUNTRY_CODE": "PhoneCountryCode", "PHONE_HOME_CITY_CODE": "PhoneAreaCode",
    "PHONE_HOME_CITY_CODE_WITH_TRUNK_PREFIX": "PhoneAreaCode", "PHONE_HOME_NUMBER_PREFIX": "PhoneLocal",
    "PHONE_HOME_NUMBER_SUFFIX": "PhoneLocal",
    "USERNAME": "Username", "PASSWORD": "Password", "ACCOUNT_CREATION_PASSWORD": "NewPassword",
    "CONFIRMATION_PASSWORD": "ConfirmPassword", "ONE_TIME_CODE": "Otp",
}
# Parts we do not fill on purpose (phone pieces, middle names, …): the right answer is "leave empty".
SKIP_TYPES = {"INPUT_TYPE_IGNORED"}

FILLABLE_INPUTS = {"text", "email", "tel", "password", "number", "", None, "search", "url"}


class Fields(html.parser.HTMLParser):
    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.fields = []
        self._select = None

    def handle_starttag(self, tag, attrs):
        a = {k.lower(): (v or "") for k, v in attrs}
        if tag == "input" and a.get("type", "text").lower() in FILLABLE_INPUTS:
            self.fields.append({"kind": "input", **a})
        elif tag == "select":
            self._select = {"kind": "select", "options": [], **a}
            self.fields.append(self._select)
        elif tag == "textarea":
            self.fields.append({"kind": "textarea", **a})
        elif tag == "option" and self._select is not None:
            self._select["options"].append("")

    def handle_data(self, data):
        if self._select is not None and self._select["options"]:
            self._select["options"][-1] += data

    def handle_endtag(self, tag):
        if tag == "select":
            self._select = None


def clean(s):
    return " ".join((s or "").split()).replace("\t", " ")


def main(src, out):
    rows, aligned, total_out = [], 0, 0
    for fname in sorted(os.listdir(os.path.join(src, "input"))):
        if not fname.endswith(".html"):
            continue
        stem = fname[:-5]
        out_path = os.path.join(src, "output", stem + ".out")
        if not os.path.exists(out_path):
            continue
        with open(os.path.join(src, "input", fname), encoding="utf-8", errors="replace") as f:
            p = Fields()
            try:
                p.feed(f.read())
            except Exception:
                continue
        expected = []
        with open(out_path, encoding="utf-8", errors="replace") as f:
            for line in f:
                parts = [x.strip() for x in line.rstrip("\n").split("|")]
                if len(parts) >= 3:
                    expected.append(parts)
        total_out += len(expected)
        # Align: the .out lists fields in document order by name (or id).
        i = 0
        for field in p.fields:
            if i >= len(expected):
                break
            ctype, name, label = expected[i][0], expected[i][1], expected[i][2]
            if name not in (field.get("name", ""), field.get("id", "")):
                continue
            i += 1
            aligned += 1
            if field["kind"] == "textarea" or ctype in SKIP_TYPES:
                continue
            ours = CHROME_TO_OURS.get(ctype, "Unknown")
            text = " ".join([field.get("name", ""), field.get("id", ""), label]).lower()
            if ours == "Unknown":
                # Chrome's address corpus leaves credential fields to its password manager and calls
                # them unknown; they are neither right nor wrong here, so they are not scored.
                if field.get("type", "").lower() == "password" or re.search(r"pass|kennwort|\bpin\b|user ?name|userid|login|benutzer|logon", text):
                    ours = "Skip"
                elif field["kind"] == "select" and sum(o.strip().lower().rstrip(".") in ("mr", "mrs", "ms", "miss", "herr", "frau", "m", "mme") for o in field.get("options", [])) >= 2:
                    ours = "Title"
            rows.append([
                stem, "chromium/" + stem, field["kind"], field.get("type", "text").lower() if field["kind"] == "input" else "",
                field.get("name", ""), field.get("id", ""), field.get("autocomplete", ""), field.get("placeholder", ""),
                field.get("aria-label", ""), label, field.get("maxlength", ""),
                "|".join(clean(o) for o in field.get("options", [])[:40]), ours,
            ])
    with open(out, "w", encoding="utf-8") as f:
        f.write("\t".join(FIELDS) + "\n")
        for r in rows:
            f.write("\t".join(clean(c) for c in r) + "\n")
    print(f"{len(rows)} Felder aus {len(set(r[0] for r in rows))} Formularen, {aligned}/{total_out} zugeordnet")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
