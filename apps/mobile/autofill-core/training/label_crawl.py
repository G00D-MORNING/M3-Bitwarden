#!/usr/bin/env python3
"""Turns crawled form structure into labelled training/test data.

A field is labelled only when the page itself declares it with a valid WHATWG autocomplete
attribute; the attribute is then removed, so detection has to work from names, labels and
placeholders alone, like on the many pages without it. Read-only fields are dropped.
"""
import csv
import sys

AC = {
    "username": "Username", "email": "Email", "current-password": "Password", "new-password": "NewPassword",
    "one-time-code": "Otp", "cc-number": "CardNumber", "cc-name": "CardHolder", "cc-given-name": "CardHolder",
    "cc-family-name": "CardHolder", "cc-exp": "CardExpiry", "cc-exp-month": "CardExpiryMonth", "cc-exp-year": "CardExpiryYear",
    "cc-csc": "CardCvv", "cc-type": "CardBrand", "name": "FullName", "given-name": "FirstName", "family-name": "LastName",
    "honorific-prefix": "Title", "tel": "Phone", "tel-national": "Phone", "tel-country-code": "PhoneCountryCode",
    "tel-area-code": "PhoneAreaCode", "tel-local": "PhoneLocal", "street-address": "Address", "address-line1": "Address",
    "address-line2": "AddressLine2", "address-line3": "AddressLine2", "postal-code": "PostalCode", "address-level2": "City",
    "address-level1": "State", "country": "Country", "country-name": "Country", "organization": "Organization",
    "bday": "Unknown", "bday-day": "Unknown", "bday-month": "Unknown", "bday-year": "Unknown", "sex": "Unknown",
    "url": "Unknown", "tel-extension": "Unknown", "additional-name": "Unknown",
}
MODIFIERS = {"shipping", "billing", "home", "work", "mobile", "fax", "pager", "webauthn"}


def field_name(raw):
    tokens = [t for t in (raw or "").lower().split() if t != "webauthn"]
    if not tokens or tokens[-1] not in AC:
        return None
    if not all(t.startswith("section-") or t in MODIFIERS for t in tokens[:-1]):
        return None
    return tokens[-1]


def main(src, out):
    rows = list(csv.DictReader(open(src, encoding="utf-8"), delimiter="\t", quoting=csv.QUOTE_NONE))
    by_form = {}
    for r in rows:
        by_form.setdefault(r["form"], []).append(r)
    fields = ["form", "source", "kind", "type", "name", "id", "autocomplete", "placeholder", "aria", "label", "maxlength", "options", "expected", "formidx", "formbuttons"]
    kept, labelled = 0, 0
    with open(out, "w", encoding="utf-8") as f:
        f.write("\t".join(fields) + "\n")
        for form, rs in by_form.items():
            rs = [r for r in rs if r.get("readonly") != "1"]
            new_pw = 0
            for r in rs:
                name = field_name(r["autocomplete"])
                expected = AC[name] if name else "Skip"
                # "new-password" on a text field is a common trick to switch Chrome's autofill off.
                if expected in ("Password", "NewPassword") and r["type"] != "password":
                    expected = "Skip"
                if expected == "NewPassword":
                    new_pw += 1
                    if new_pw > 1:
                        expected = "ConfirmPassword"
                if expected == "Username" and r["type"] == "email":
                    expected = "Email"
                if expected != "Skip":
                    labelled += 1
                kept += 1
                f.write("\t".join([form, "crawl/" + r["source"], r["kind"], r["type"], r["name"], r["id"], "",
                                   r["placeholder"], r["aria"], r["label"], r["maxlength"], r["options"], expected,
                                   r.get("formidx", "-1"), r.get("formbuttons", "")]) + "\n")
    print(f"{kept} Felder aus {len(by_form)} Seiten, davon {labelled} per autocomplete beschriftet")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
