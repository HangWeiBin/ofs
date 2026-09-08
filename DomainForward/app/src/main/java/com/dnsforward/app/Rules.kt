package com.dnsforward.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.Inet6Address
import java.net.InetAddress

/** One forwarding rule: [domain] should resolve to [ip] (optionally including subdomains). */
data class Rule(
    val domain: String,
    val ip: String,
    val sub: Boolean
) {
    val inet: InetAddress?
        get() = runCatching { InetAddress.getByName(ip) }.getOrNull()
}

/** Persists rules in SharedPreferences as JSON. */
object RulesStore {

    private const val PREFS = "rules"
    private const val KEY = "list"

    @Volatile
    var rules: List<Rule> = emptyList()

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(context: Context) {
        val raw = prefs(context).getString(KEY, "[]") ?: "[]"
        val result = ArrayList<Rule>()
        runCatching {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val domain = obj.optString("domain", "").trim().lowercase()
                val ip = obj.optString("ip", "").trim()
                val sub = obj.optBoolean("sub", true)
                if (Valid.isDomain(domain) && Valid.isIp(ip)) {
                    result.add(Rule(domain, ip, sub))
                }
            }
        }
        rules = result
    }

    fun save(context: Context, list: List<Rule>) {
        val arr = JSONArray()
        for (rule in list) {
            val obj = JSONObject()
            obj.put("domain", rule.domain)
            obj.put("ip", rule.ip)
            obj.put("sub", rule.sub)
            arr.put(obj)
        }
        prefs(context).edit().putString(KEY, arr.toString()).apply()
        rules = list
    }
}

/** Input validation helpers. */
object Valid {

    private val IPV4 = Regex(
        """^(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)(\.(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)){3}$"""
    )

    private val DOMAIN = Regex(
        """^(?=.{1,253}$)([a-zA-Z0-9]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?\.)*[a-zA-Z0-9]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?$"""
    )

    /** True for numeric IPv4 or IPv6 literals (never triggers a DNS lookup). */
    fun isIp(value: String): Boolean {
        if (IPV4.matches(value)) return true
        if (!value.contains(":")) return false
        return runCatching { InetAddress.getByName(value) is Inet6Address }.getOrDefault(false)
    }

    fun isDomain(value: String): Boolean = DOMAIN.matches(value.trim().trimEnd('.'))
}
