from enum import StrEnum


class PiiCategory(StrEnum):
    """The 12 PII categories defined by the case study."""

    EMAIL_ADDRESS = "email_address"
    PHONE_NUMBER = "phone_number"
    SSN = "ssn"
    CREDIT_CARD_NUMBER = "credit_card_number"
    NATIONAL_ID_NUMBER = "national_id_number"
    FULL_NAME = "full_name"
    FIRST_NAME = "first_name"
    LAST_NAME = "last_name"
    TCKN = "tckn"
    HOME_ADDRESS = "home_address"
    DATE_OF_BIRTH = "date_of_birth"
    IP_ADDRESS = "ip_address"


class MaskingMode(StrEnum):
    """How sample values are masked before they are sent to the LLM."""

    NONE = "none"
    PARTIAL = "partial"
    STRICT = "strict"
