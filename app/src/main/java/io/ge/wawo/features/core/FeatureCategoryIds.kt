package io.ge.wawo.features.core

object FeatureCategoryIds {
    const val CHAT = "chat"
    const val CONTACTS_GROUPS = "contacts_groups"
    const val PAYMENT = "payment"
    const val MOMENTS = "moments"
    const val SYSTEM_PRIVACY = "system_privacy"
    const val BEAUTIFY = "beautify"
    const val OFFICIAL_ACCOUNTS = "official_accounts"
    const val MINIAPPS = "miniapps"
    const val CHANNELS = "channels"
    const val BATCH = "batch"
    const val HOME_SCREEN_MENU = "home_screen_menu"
    const val CONTACT_DETAILS = "contact_details"
    const val API = "api"

    val ALL: Set<String> = setOf(
        CHAT,
        CONTACTS_GROUPS,
        PAYMENT,
        MOMENTS,
        SYSTEM_PRIVACY,
        BEAUTIFY,
        OFFICIAL_ACCOUNTS,
        MINIAPPS,
        CHANNELS,
        BATCH,
        HOME_SCREEN_MENU,
        CONTACT_DETAILS,
        API,
    )
}
