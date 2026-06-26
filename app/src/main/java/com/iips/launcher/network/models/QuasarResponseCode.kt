package com.iips.launcher.network.models

/**
 * Quasar Response Messages and Response Codes mapping.
 */
enum class QuasarResponseCode(val code: String, val meaning: String) {
    // 200 Success
    SUCCESS("SY00", "Success"),
    USER_RETRIEVED("US21", "User Retrieved"),
    USER_UPDATED("US22", "User Updated"),
    TENANT_LOADED("TN21", "Tenant Loaded"),
    PAYMENT_SUCCESSFUL("PY21", "Payment Successful"),
    TRANSFER_SUCCESSFUL("TR21", "Transfer Successful"),
    WALLET_CREDITED("WL21", "Wallet Credited"),
    WALLET_DEBITED("WL22", "Wallet Debited"),
    DEVICE_ACTIVATED("DV21", "Device Activated"),
    EVENT_PUBLISHED("EV21", "Event Published"),
    TICKET_ISSUED("TKT21", "Ticket Issued"),
    KYC_VERIFIED("KY21", "KYC Verified"),

    // 201 Created
    USER_CREATED("US2011", "User Created"),
    TENANT_CREATED("TN2011", "Tenant Created"),
    EVENT_CREATED("EV2011", "Event Created"),
    TICKET_CREATED("TKT2011", "Ticket Created"),
    PAYMENT_CREATED("PY2011", "Payment Created"),
    WALLET_CREATED("WL2011", "Wallet Created"),
    DEVICE_REGISTERED("DV2011", "Device Registered"),
    BANK_ACCOUNT_CREATED("BK2011", "Bank Account Created"),

    // 400 Bad Request
    // Validation
    VALIDATION_FAILED("VR41", "Validation Failed"),
    MISSING_REQUIRED_FIELD("VR42", "Missing Required Field"),
    INVALID_JSON("VR43", "Invalid JSON"),
    INVALID_PAYLOAD("VR44", "Invalid Payload"),
    INVALID_HEADER("VR45", "Invalid Header"),
    INVALID_QUERY_PARAMETER("VR46", "Invalid Query Parameter"),
    INVALID_PATH_VARIABLE("VR47", "Invalid Path Variable"),
    INVALID_SIGNATURE("VR48", "Invalid Signature"),
    // Identity
    INVALID_EMAIL("IC41", "Invalid Email"),
    INVALID_PHONE("IC42", "Invalid Phone"),
    INVALID_OTP("IC43", "Invalid OTP"),
    INVALID_PASSWORD("IC44", "Invalid Password"),
    INVALID_PIN("IC45", "Invalid PIN"),
    INVALID_USERNAME("IC46", "Invalid Username"),
    // Banking
    INVALID_ACCOUNT_NUMBER("BK41", "Invalid Account Number"),
    INVALID_BANK_CODE("BK42", "Invalid Bank Code"),
    INVALID_BVN("BK43", "Invalid BVN"),
    INVALID_NIN("BK44", "Invalid NIN"),
    INVALID_CURRENCY("BK45", "Invalid Currency"),

    // 401 Unauthorized
    AUTHENTICATION_FAILED("AU4011", "Authentication Failed"),
    INVALID_CREDENTIALS("IC4011", "Invalid Credentials"),
    TOKEN_EXPIRED("TK4011", "Token Expired"),
    INVALID_TOKEN("TK4012", "Invalid Token"),
    REFRESH_TOKEN_EXPIRED("TK4013", "Refresh Token Expired"),
    REFRESH_TOKEN_INVALID("TK4014", "Refresh Token Invalid"),
    ACCESS_TOKEN_MISSING("TK4015", "Access Token Missing"),
    LOGIN_REQUIRED("AU4012", "Login Required"),
    SESSION_EXPIRED("AU4013", "Session Expired"),
    DEVICE_NOT_AUTHORIZED("DV4011", "Device Not Authorized"),
    TENANT_UNAUTHORIZED("TN4011", "Tenant Unauthorized"),

    // 403 Forbidden
    PERMISSION_DENIED("PM4031", "Permission Denied"),
    ROLE_REQUIRED("RL4031", "Role Required"),
    ADMIN_ONLY("RL4032", "Admin Only"),
    SUPER_ADMIN_ONLY("RL4033", "Super Admin Only"),
    KYC_REQUIRED("KY4031", "KYC Required"),
    ACCOUNT_LOCKED("AC4031", "Account Locked"),
    ACCOUNT_SUSPENDED("AC4032", "Account Suspended"),
    ACCOUNT_DISABLED("AC4033", "Account Disabled"),
    DEVICE_LOCKED("DV4031", "Device Locked"),
    DEVICE_OWNER_REQUIRED("DV4032", "Device Owner Required"),
    SUBSCRIPTION_EXPIRED("SB4031", "Subscription Expired"),
    LICENSE_EXPIRED("LC4031", "License Expired"),

    // 404 Not Found
    USER_NOT_FOUND("US4041", "User Not Found"),
    TENANT_NOT_FOUND("TN4041", "Tenant Not Found"),
    DEVICE_NOT_FOUND("DV4041", "Device Not Found"),
    EVENT_NOT_FOUND("EV4041", "Event Not Found"),
    TICKET_NOT_FOUND("TKT4041", "Ticket Not Found"),
    WALLET_NOT_FOUND("WL4041", "Wallet Not Found"),
    PAYMENT_NOT_FOUND("PY4041", "Payment Not Found"),
    BANK_NOT_FOUND("BK4041", "Bank Not Found"),
    LEDGER_ENTRY_NOT_FOUND("LG4041", "Ledger Entry Not Found"),

    // 409 Conflict
    USER_ALREADY_EXISTS("US4091", "User Already Exists"),
    TENANT_ALREADY_EXISTS("TN4091", "Tenant Already Exists"),
    DEVICE_ALREADY_REGISTERED("DV4091", "Device Already Registered"),
    EVENT_ALREADY_EXISTS("EV4091", "Event Already Exists"),
    WALLET_ALREADY_EXISTS("WL4091", "Wallet Already Exists"),
    BANK_ACCOUNT_ALREADY_EXISTS("BK4091", "Bank Account Already Exists"),
    DUPLICATE_TRANSACTION("PY4091", "Duplicate Transaction"),
    VERSION_CONFLICT("AP4091", "Version Conflict"),

    // 422 Validation
    INVALID_INPUT("VR4221", "Invalid Input"),
    INVALID_ENUM("VR4222", "Invalid Enum"),
    INVALID_FILE("VR4223", "Invalid File"),
    PASSWORD_TOO_WEAK("VR4224", "Password Too Weak"),
    PIN_TOO_WEAK("VR4225", "PIN Too Weak"),
    INVALID_IMAGE("VR4226", "Invalid Image"),
    INVALID_DOCUMENT("VR4227", "Invalid Document"),

    // 429 Too Many Requests
    TOO_MANY_REQUESTS("RT4291", "Too Many Requests"),
    LOGIN_ATTEMPTS_EXCEEDED("RT4292", "Login Attempts Exceeded"),
    OTP_LIMIT_EXCEEDED("RT4293", "OTP Limit Exceeded"),
    API_LIMIT_EXCEEDED("RT4294", "API Limit Exceeded"),
    RATE_LIMIT_EXCEEDED("RT4295", "Rate Limit Exceeded"),

    // Financial Errors
    INSUFFICIENT_FUNDS("PY4021", "Insufficient Funds"),
    DAILY_LIMIT_EXCEEDED("PY4022", "Daily Limit Exceeded"),
    MONTHLY_LIMIT_EXCEEDED("PY4023", "Monthly Limit Exceeded"),
    DUPLICATE_TRANSFER("PY4024", "Duplicate Transfer"),
    BENEFICIARY_NOT_FOUND("PY4025", "Beneficiary Not Found"),
    SETTLEMENT_PENDING("PY4026", "Settlement Pending"),
    SETTLEMENT_FAILED("PY4027", "Settlement Failed"),
    CHARGEBACK_RECEIVED("PY4028", "Chargeback Received"),
    PAYMENT_REVERSED("PY4029", "Payment Reversed"),
    PAYMENT_PENDING("PY402010", "Payment Pending"),

    // Wallet
    WALLET_FROZEN("WL4231", "Wallet Frozen"),
    WALLET_CLOSED("WL4232", "Wallet Closed"),
    WALLET_RESTRICTED("WL4233", "Wallet Restricted"),
    WALLET_LIMIT_REACHED("WL4234", "Wallet Limit Reached"),

    // Events
    EVENT_FULL("EV4091", "Event Full"), // Note: Code EV4091 also means "Event Already Exists". Appended "_FULL" to enum to avoid duplicate name.
    EVENT_CANCELLED("EV4092", "Event Cancelled"),
    EVENT_POSTPONED("EV4093", "Event Postponed"),
    EVENT_CLOSED("EV4094", "Event Closed"),
    TICKET_SOLD_OUT("TKT4091", "Ticket Sold Out"),
    TICKET_ALREADY_USED("TKT4092", "Ticket Already Used"),
    TICKET_INVALID("TKT4093", "Ticket Invalid"),
    CHECK_IN_SUCCESSFUL("AT21", "Check-in Successful"),
    CHECK_OUT_SUCCESSFUL("AT22", "Check-out Successful"),

    // Device Management
    DEVICE_OFFLINE("DV5031", "Device Offline"),
    DEVICE_ONLINE("DV22", "Device Online"),
    DEVICE_SYNCED("DV23", "Device Synced"),
    DEVICE_SYNC_FAILED("DV51", "Device Sync Failed"),
    DEVICE_COMMAND_FAILED("DV52", "Device Command Failed"),
    DEVICE_COMMAND_EXECUTED("DV24", "Device Command Executed"),
    LOCATION_UPDATED("DV25", "Location Updated"),

    // Notifications
    EMAIL_SENT("EM21", "Email Sent"),
    WHATSAPP_SENT("WA21", "WhatsApp Sent"),
    SMS_SENT("SM21", "SMS Sent"),
    PUSH_SENT("PN21", "Push Sent"),
    NOTIFICATION_FAILED("NT51", "Notification Failed"),

    // Webhooks
    WEBHOOK_RECEIVED("WB21", "Webhook Received"),
    WEBHOOK_VERIFIED("WB22", "Webhook Verified"),
    WEBHOOK_INVALID_SIGNATURE("WB41", "Invalid Signature"),
    DUPLICATE_WEBHOOK("WB4091", "Duplicate Webhook"),
    WEBHOOK_PROCESSING_FAILED("WB51", "Webhook Processing Failed"),

    // External Services
    SERVICE_UNAVAILABLE("DU5031", "Service Unavailable"),
    BANK_SERVICE_UNAVAILABLE("DU5032", "Bank Service Unavailable"),
    PAYMENT_GATEWAY_UNAVAILABLE("DU5033", "Payment Gateway Unavailable"),
    WHATSAPP_API_UNAVAILABLE("DU5034", "WhatsApp API Unavailable"),
    SMS_PROVIDER_UNAVAILABLE("DU5035", "SMS Provider Unavailable"),
    EMAIL_PROVIDER_UNAVAILABLE("DU5036", "Email Provider Unavailable"),
    CLOUD_STORAGE_UNAVAILABLE("DU5037", "Cloud Storage Unavailable"),
    AI_SERVICE_UNAVAILABLE("DU5038", "AI Service Unavailable"),

    // Internal System
    DATABASE_ERROR("DB51", "Database Error"),
    DATABASE_TIMEOUT("DB52", "Database Timeout"),
    CACHE_ERROR("CH51", "Cache Error"),
    QUEUE_FAILURE("MQ51", "Queue Failure"),
    FILE_STORAGE_FAILURE("FS51", "File Storage Failure"),
    CONFIGURATION_ERROR("CF51", "Configuration Error"),
    SECURITY_ERROR("SC51", "Security Error"),
    INTERNAL_SERVER_ERROR("SY51", "Internal Server Error"),
    UNKNOWN_EXCEPTION("SY52", "Unknown Exception"),
    UNEXPECTED_ERROR("SY53", "Unexpected Error"),

    UNKNOWN("", "Unknown");

    companion object {
        fun fromCode(code: String?): QuasarResponseCode {
            if (code == null) return UNKNOWN
            return entries.find { it.code == code } ?: UNKNOWN
        }
    }
}
