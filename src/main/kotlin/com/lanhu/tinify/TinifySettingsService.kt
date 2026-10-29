package com.lanhu.tinify

import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.openapi.components.Service

@Service(Service.Level.APP)
class TinifySettingsService {
    private val apiKeyAttributes = CredentialAttributes("com.lanhu.tinify.api-key")
    private val lanhuCookieAttributes = CredentialAttributes("com.lanhu.tinify.lanhu-cookie")

    fun getApiKey(): String? =
        PasswordSafe.instance.get(apiKeyAttributes)?.getPasswordAsString()?.takeIf { it.isNotBlank() }

    fun setApiKey(apiKey: String) {
        val credentials = apiKey.trim().takeIf { it.isNotEmpty() }?.let { Credentials("Tinify", it) }
        PasswordSafe.instance.set(apiKeyAttributes, credentials)
    }

    fun getLanhuCookie(): String? =
        PasswordSafe.instance.get(lanhuCookieAttributes)?.getPasswordAsString()?.takeIf { it.isNotBlank() }

    fun setLanhuCookie(cookie: String) {
        val credentials = cookie.trim().takeIf { it.isNotEmpty() }?.let { Credentials("Lanhu", it) }
        PasswordSafe.instance.set(lanhuCookieAttributes, credentials)
    }
}
