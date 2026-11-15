package com.dlguard.plugin

/**
 * 市场源地址解析：把用户填的各种写法统一成可请求的 URL。
 *
 * 支持：
 *  - `owner/repo`                       → raw.githubusercontent.com/owner/repo/main/index.json
 *  - `https://github.com/owner/repo`    → 同上
 *  - `https://github.com/owner/repo/blob/main/docs/index.json` → 自动转 raw
 *  - `https://raw.githubusercontent.com/.../index.json`        → 原样
 *  - 任意 http(s) 的 .json / .zip        → 原样（自建市场）
 */
object MarketSource {

    private val REPO = Regex("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$")

    /** 默认 GitHub 市场索引（开源后把这里换成自己的仓库即可）。 */
    const val DEFAULT_REPO = "dlguard/plugins"
    const val DEFAULT_INDEX = "https://raw.githubusercontent.com/$DEFAULT_REPO/main/index.json"

    /** 默认安装分支，主分支不存在时刷新逻辑会自动回退 master。 */
    const val BRANCH = "main"
    const val FALLBACK_BRANCH = "master"

    /** 解析出 `owner/repo`；不是 GitHub 仓库写法则返回 null。 */
    fun repoOf(input: String): String? {
        val text = input.trim().removeSuffix("/")
        if (text.isBlank()) return null
        if (REPO.matches(text)) return text
        val match = Regex("^https?://(?:www\\.)?github\\.com/([^/]+)/([^/?#]+)", RegexOption.IGNORE_CASE)
            .find(text) ?: return null
        val owner = match.groupValues[1]
        val repo = match.groupValues[2].removeSuffix(".git")
        return if (owner.isBlank() || repo.isBlank()) null else "$owner/$repo"
    }

    /** 市场索引 URL；无法识别时返回 null。 */
    fun indexUrl(input: String): String? {
        val text = input.trim()
        if (text.isBlank()) return null
        if (text.startsWith("http://") || text.startsWith("https://")) {
            // github.com/owner/repo/blob/branch/path → raw.githubusercontent.com/owner/repo/branch/path
            val blob = Regex(
                "^https?://(?:www\\.)?github\\.com/([^/]+)/([^/]+)/blob/([^/]+)/(.+)$",
                RegexOption.IGNORE_CASE
            ).find(text)
            if (blob != null) {
                val owner = blob.groupValues[1]
                val repo = blob.groupValues[2]
                val branch = blob.groupValues[3]
                val path = blob.groupValues[4]
                return "https://raw.githubusercontent.com/$owner/$repo/$branch/$path"
            }
            // 仓库地址（可能带 .git / 尾斜杠）→ 默认索引
            val repo = repoOf(text)
            if (repo != null && !text.contains("/raw/", true) && !text.endsWith(".json", true)) {
                return "https://raw.githubusercontent.com/$repo/$BRANCH/index.json"
            }
            return text
        }
        val repo = repoOf(text) ?: return null
        return "https://raw.githubusercontent.com/$repo/$BRANCH/index.json"
    }

    /** 同一索引的 master 分支兜底地址（main 404 时尝试）。 */
    fun fallbackIndexUrl(url: String): String? {
        if (!url.contains("/main/")) return null
        return url.replace("/main/", "/$FALLBACK_BRANCH/")
    }

    /** 从 GitHub 仓库下载整包 zip（codeload，无需 API、无速率限制）。 */
    fun zipUrl(input: String): String? {
        val text = input.trim()
        if (text.startsWith("http://") || text.startsWith("https://")) {
            if (text.endsWith(".zip", ignoreCase = true)) return text
            val repo = repoOf(text) ?: return null
            return "https://codeload.github.com/$repo/zip/refs/heads/$BRANCH"
        }
        val repo = repoOf(text) ?: return null
        return "https://codeload.github.com/$repo/zip/refs/heads/$BRANCH"
    }

    fun fallbackZipUrl(url: String): String? {
        if (!url.contains("/refs/heads/main")) return null
        return url.replace("/refs/heads/main", "/refs/heads/$FALLBACK_BRANCH")
    }

    /** 展示用：把长 URL 压缩成一眼能认出的形式。 */
    fun pretty(input: String): String {
        val repo = repoOf(input)
        if (repo != null) return "GitHub · $repo"
        return input.ifBlank { "未配置" }
    }
}
