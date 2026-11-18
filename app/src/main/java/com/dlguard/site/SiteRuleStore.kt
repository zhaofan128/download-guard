package com.dlguard.site

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.siteRuleDataStore: DataStore<Preferences> by preferencesDataStore(name = "site_rules")

/**
 * 站点规则持久层。
 *
 * 用 DataStore 存一段 JSON 而不是 Room 建表：规则是有序列表、整体导入导出，
 * 关系型表反而要处理迁移；JSON 本身也是用户可见的交换格式，一套结构两用。
 *
 * 规则文件里**不含 Cookie 明文**（CookieMode.CUSTOM 的值只在内存/本机偏好里，
 * 导出时由 [SiteRule] 的序列化统一清空），避免「分享规则 = 泄露登录态」。
 */
class SiteRuleStore(private val context: Context) {

    private object Keys {
        val JSON = stringPreferencesKey("rules_json")
    }

    val rules: Flow<List<SiteRule>> = context.siteRuleDataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { prefs -> decode(prefs[Keys.JSON]) }

    suspend fun snapshot(): List<SiteRule> = rules.first()

    suspend fun replace(rules: List<SiteRule>) {
        context.siteRuleDataStore.edit { prefs ->
            // 本机持久化保留自定义 Cookie；对外导出走 export()，那里会剥离
            prefs[Keys.JSON] = SiteRules.toJson(
                rules.take(SiteRules.MAX_RULES),
                pretty = false,
                includeSecrets = true
            )
        }
    }

    /** 新增或按 id 覆盖；返回操作后的完整列表。 */
    suspend fun upsert(rule: SiteRule): List<SiteRule> {
        val current = snapshot().toMutableList()
        val index = current.indexOfFirst { it.id == rule.id }
        if (index >= 0) current[index] = rule else current.add(0, rule)
        replace(current)
        return current
    }

    suspend fun delete(id: String): List<SiteRule> {
        val next = snapshot().filterNot { it.id == id }
        replace(next)
        return next
    }

    suspend fun setEnabled(id: String, enabled: Boolean): List<SiteRule> {
        val next = snapshot().map { if (it.id == id) it.copy(enabled = enabled) else it }
        replace(next)
        return next
    }

    /** 导入：合并模式保留现有规则，同名 id 覆盖；返回结果统计。 */
    suspend fun import(json: String, merge: Boolean): SiteRuleParseResult {
        val parsed = SiteRules.parse(json)
        if (parsed.error != null) return parsed
        val current = if (merge) snapshot().toMutableList() else mutableListOf()
        for (rule in parsed.rules) {
            val index = current.indexOfFirst { it.id == rule.id }
            if (index >= 0) current[index] = rule else current.add(rule)
        }
        replace(current)
        return parsed.copy(rules = current)
    }

    suspend fun export(): String = SiteRules.toJson(snapshot(), pretty = true)

    /**
     * 从 URL 生成一条规则草稿：host 自动填好，其余走默认值。
     * 用户点「保存为站点规则」时不用自己敲域名。
     */
    fun draftFor(url: String, nameHint: String? = null): SiteRule? {
        val host = SiteRules.hostOf(url) ?: return null
        val id = host.replace(Regex("[^a-z0-9.-]"), "-").trim('-').ifBlank { "rule" }
        val label = nameHint?.takeIf { it.isNotBlank() } ?: host
        return SiteRule(
            id = "$id-${System.currentTimeMillis().toString(36)}${(100..999).random()}",
            name = label.take(80),
            hosts = listOf(host),
            refererMode = RefererMode.PAGE,
            cookieMode = CookieMode.MERGE,
            note = "由「$host」自动生成"
        )
    }

    private fun decode(raw: String?): List<SiteRule> {
        if (raw.isNullOrBlank()) return emptyList()
        return SiteRules.parse(raw).rules
    }
}
