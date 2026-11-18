# 站点规则格式（v1）

站点规则是一份 JSON 文件，用来描述「某个域名下的下载请求该怎么发、文件该怎么命名」。
它是**纯声明式**的：没有脚本、没有回调、不能读写文件。

## 文件结构

```json
{
  "schema": "dlguard.site-rules",
  "version": 1,
  "rules": [
    {
      "id": "pan-demo",
      "name": "示例网盘",
      "enabled": true,
      "priority": 100,
      "hosts": ["pan.example.com", "*.example.net"],
      "referer": { "mode": "page", "value": "" },
      "cookie": { "mode": "merge", "value": "" },
      "userAgent": "",
      "headers": { "Accept-Language": "zh-CN" },
      "concurrency": 0,
      "fileNameTemplate": "",
      "sidecarHash": null,
      "openWhenDone": null,
      "note": "页面 + 直链 Cookie 合并"
    }
  ]
}
```

根节点也可以是裸数组 `[...]`，方便手写和分享单条规则。

## 字段

| 字段 | 类型 | 默认 | 说明 |
| --- | --- | --- | --- |
| `id` | string | 必填 | `[A-Za-z0-9._-]`，1~64 字符；同一文件内唯一，重复的后者被跳过 |
| `name` | string | = id | 展示名，≤80 字符 |
| `enabled` | bool | true | 关闭后不参与匹配 |
| `priority` | int | 100 | 越大越优先，范围 -1000~1000 |
| `hosts` | string[] | 必填 | 1~32 个域名模式 |
| `referer.mode` | string | `page` | `page` / `direct` / `custom` / `none` |
| `referer.value` | string | `""` | 仅 `custom` 时使用 |
| `cookie.mode` | string | `merge` | `merge` / `page` / `direct` / `custom` / `none` |
| `cookie.value` | string | `""` | 仅 `custom` 时使用；**导出时会被清空** |
| `userAgent` | string | `""` | 空 = 内置移动端 Chrome |
| `headers` | object | `{}` | 额外请求头，≤32 个；引擎保留头会被丢弃 |
| `concurrency` | int | 0 | 0 = 跟随全局；1~64 覆盖本规则下任务的初始并发 |
| `fileNameTemplate` | string | `""` | 见下方占位符 |
| `sidecarHash` | bool/null | null | null = 跟随全局插件配置 |
| `openWhenDone` | bool/null | null | null = 跟随全局；true = 校验通过后自动打开 |
| `note` | string | `""` | 备注，≤500 字符 |

`referer` 和 `cookie` 也接受简写：`"referer": "direct"`。

## 域名匹配

| 模式 | 匹配 | 不匹配 |
| --- | --- | --- |
| `example.com` | `example.com` | `pan.example.com`、`example.com.evil.net` |
| `*.example.com` | `example.com`、`pan.example.com`、`a.b.example.com` | `notexample.com`、`example.com.evil.net` |
| `.example.com` | 同 `*.example.com` | 同上 |
| `*` | 任意 host | — |

匹配忽略大小写；规则里手写的端口会被忽略（`pan.example.com:8443` 等价于 `pan.example.com`）。

选择顺序：`enabled` → host 命中 → `priority` 降序 → 命中的模式更具体者优先 →
文件里的先后顺序。`pan.example.com` 会赢过 `*.example.com`。

## Cookie 来源

| 模式 | 行为 |
| --- | --- |
| `merge` | 页面域 Cookie + 直链域 Cookie，按 name 去重，同名以**直链域**为准 |
| `page` | 只取页面域；没有页面地址时什么都不发 |
| `direct` | 只取直链域 |
| `custom` | 用 `cookie.value`；值只存本机，导出时清空 |
| `none` | 不发送 Cookie |

`merge` 是默认值，也是跨子域网盘成功率最高的选择。它无法表达 Cookie 的 path 维度——
HTTP 的 `Cookie` 请求头本身就不带 path，同名只能取一个。

## 文件名模板

| 占位符 | 含义 |
| --- | --- |
| `{name}` | 原文件名（不含扩展名） |
| `{ext}` | 扩展名（不含点） |
| `{host}` | 下载地址的域名 |
| `{date}` | 当天日期 `yyyyMMdd` |
| `{rule}` | 命中的规则名 |

未知占位符原样保留，方便发现写错。例：`{name}-{date}.{ext}`。

## 被拒绝的请求头

这些头由下载引擎自己管理，规则不能覆盖：

`Host`、`Content-Length`、`Range`、`If-Range`、`Accept-Encoding`、`Connection`、
`Transfer-Encoding`、`TE`、`Upgrade`、`Trailer`、`Proxy-Authorization`、`Proxy-Connection`。

另外：

- 头名必须是合法 token（不含空格、冒号）；
- 值里含 `\r` / `\n` / `\0` 的整条丢弃（防请求头注入）；
- 值长度 >4096 的丢弃。

`Referer` / `Cookie` / `User-Agent` 写在 `headers` 里也会生效，但会被提升到对应字段，
不会重复发送。

## 安全边界

- 导出的文件**不含自定义 Cookie 的值**（`toJson(includeSecrets=false)` 是默认行为）。
  分享规则不会顺带分享登录态。
- 导入时逐条校验：非法 id、空 hosts、非法域名、重复 id 都会被跳过并计数，不会整份失败。
- 文件上限 512 KB、JSON 深度 32、单对象 512 键、单数组 4096 项。
- 高版本 `version` 明确拒绝，提示升级 App。

## 403 排查顺序

1. 先确认直链是否过期（带一次性 token 的地址几分钟就失效）；
2. 用「网页模式」打开原页面，确认登录态还在；
3. 开规则测试，看 `Cookie` 是否只有页面域——是的话把 `cookie.mode` 改成 `merge`；
4. 还不行再补 `Referer`（有些站点要求精确到分享页，而不是站点首页）；
5. 最后才考虑 UA：少数站点只放行特定客户端。
