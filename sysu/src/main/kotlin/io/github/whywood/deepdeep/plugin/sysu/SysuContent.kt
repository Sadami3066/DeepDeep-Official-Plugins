package io.github.whywood.deepdeep.plugin.sysu

import io.github.whywood.deepdeep.plugin.api.LocalizedText

internal object SysuContent {
    fun skillMarkdown(): String = SKILL_MARKDOWN

    fun promptBody(status: SysuStatus?): String {
        if (status == null) {
            return "中山大学 CLI 插件状态未知。账号密码、登录与重新登录都在 DeepDeep 的「中山大学 CLI」插件页完成；不要向用户索要账号密码或验证码。"
        }
        if (!status.linuxAvailable || status.nodeVersion == null || status.nodeTooOld) {
            val reason = when {
                !status.linuxAvailable -> "缺少 Linux 运行环境"
                status.nodeVersion == null -> "缺少 Node.js"
                else -> "Node.js 版本过低（${status.nodeVersion}，需要 22 或更高）"
            }
            return "中山大学 CLI 当前不可用：$reason。让用户到 设置 → 运行环境 安装或升级，然后在插件页「中山大学 CLI」填写账号密码并登录。"
        }
        if (!status.installed || status.outdated) {
            return "中山大学 CLI 尚未安装或版本不是最新的（当前 ${status.installedVersion ?: "未安装"}，期望 ${SysuCli.VERSION}）：让用户打开插件页「中山大学 CLI」，填写账号密码后点「登录」，页面会自动安装。"
        }
        val sites = siteStatusLine(status)
        if (status.loggedIn) {
            return buildString {
                append("中山大学 CLI 已安装并已登录")
                if (!status.account.isNullOrBlank()) append("（账号 ${status.account}）")
                appendLine("。$sites")
                appendLine("命令用法见技能 deepdeep-sysu；不熟悉时先跑 `sysu --help`。")
                appendLine("站点：sysu jwxt（教务）、sysu lms（在线教学）、sysu gym（体育场馆）、sysu netpay（网费）、sysu sso（通用换票）。")
                appendLine("课表、成绩、考试、学分、课程、待办、场馆空位与网费优先用单条命令查询，命令默认输出 JSON；不要加管道、head 或 cd 前缀。")
                appendLine("--json 只为兼容旧命令保留，加不加输出都一样。")
                appendLine("gym slots 一次只查一个类型，最多 2 天；查多个类型或更长时间要分多次执行。")
                appendLine("成绩、请假、预约与个人信息仅限本机会话；不要输出账号、密码、验证码或 session.json。")
                appendLine("写操作必须先用 --dry-run 预览并让用户确认：请假、预约场地、网费续费；确认后才加 --yes。")
                appendLine("单个站点会话失效时，先在对话里执行该站点的 login（教务 sysu login、在线教学 sysu lms login、场馆 sysu gym login、网费 sysu netpay login），不需要用户操作；需要企业微信验证码或 login 失败时再让用户到插件页处理。")
            }
        }
        return "中山大学 CLI 已安装，统一认证当前未登录。$sites 先在对话里执行 `sysu login`；需要企业微信验证码或失败时，让用户打开插件页填写账号密码并登录，页面会提示验证码。不要向用户索要账号密码或验证码。"
    }

    fun siteStatusLine(status: SysuStatus): String = SysuSite.entries.joinToString(" · ") { site ->
        val label = siteLabelValue(site)
        val value = if (status.connected(site)) "已登录" else "未连接"
        "$label$value"
    }.let { "站点状态：$it。" }

    private val SKILL_MARKDOWN = """
        ---
        name: deepdeep-sysu
        description: 中山大学校园服务 CLI——教务（课表/成绩/考试/请假）、在线教学平台（课程/作业/文件）、体育场馆（空位/预约/取消）、网费（服务状态/订单/续费）与统一认证换票。用户问上课时间、成绩、作业截止、课件、场地、网费或想登进别的中大系统时用；命令是 `sysu`。
        ---

        # 中山大学 CLI（sysu）

        在 bash 中运行 `sysu <站点> <命令>`。

        ## 查询与 JSON

        所有站点数据命令默认输出 JSON，`--json` 只为兼容旧命令保留、不改变输出。查询时用下面的单条命令，不加管道、`head`、重定向、`&&` 或 `cd` 前缀：

        - `sysu jwxt schedule`（`--week 8` / `--today` / `--tomorrow`）
        - `sysu jwxt scores`（`--all` / `--semester 2025-2`）
        - `sysu jwxt exams`
        - `sysu jwxt credits`
        - `sysu lms`；`sysu lms todo --days 30`
        - `sysu gym slots <类型>`（`--days 2` / `--date 2026-11-20`）
        - `sysu netpay`

        课表、成绩、考试、学分、课程、待办、场馆空位与网费的结果会整理成完整数据与结果卡片；回答里给关键结论和下一步即可，不必逐条复述。其他命令（公告详情、教室、课程大纲、文件、成绩单、订单、换票、登录等）的 JSON 由工具结果的 raw 直接给出，按字段读取即可。命令失败时输出 `{ok:false,error:{message,code}}`（退出码 1），按下面的登录说明处理，不要改用别的命令绕。

        ## 教务（sysu jwxt）

        - 本周课表：`sysu jwxt schedule`；指定周 `--week 8`；`--today` / `--tomorrow`
        - 成绩：`sysu jwxt scores`（`--all` 全部学期）
        - 学分：`sysu jwxt credits`；考试：`sysu jwxt exams`
        - 一门课：`sysu jwxt course <课程码或课名>`（`--intro` / `--syllabus`）
        - 公告：`sysu jwxt notice [id]`；空闲教室：`sysu jwxt room`；助教：`sysu jwxt ta`
        - 请假：`sysu jwxt leave`

        ## 在线教学（sysu lms）

        - 课程：`sysu lms`；待办：`sysu lms todo --days 30`
        - 课程内容：`sysu lms course <id>`；文件：`sysu lms files <id>`
        - 下载：`sysu lms get <id>`（默认存到下载目录，`--only` / `--dry-run`）
        - 作业总览：`sysu lms assignments`；成绩：`sysu lms grades <id>`；公告：`sysu lms notices`

        ## 体育场馆（sysu gym）

        - 账户/校区：`sysu gym`；类型：`sysu gym types`；场地：`sysu gym venues <关键字>`
        - 空位：`sysu gym slots <关键字> --days 2`（解析 `types[].fee`、`venues[].shortName`、`slots[].available`）；gym slots 一次只查一个类型，最多 2 天，多个类型或更长时间分多次查
        - 我的预约：`sysu gym mine`
        - 预约：`sysu gym book --type ... --venue ... --date ... --start ... --end ...`
          默认按用户身份与时段选价，`--charge` 仅覆盖；**默认 dry-run，必须用户确认后才加 `--yes`**
        - 取消：`sysu gym cancel <预约号> --yes`；无空位时的「公告：…」来自场馆描述，不是保证

        ## 网费（sysu netpay）

        - 服务状态/到期：`sysu netpay`
        - 订单：`sysu netpay orders`
        - 续费：`sysu netpay renew`（默认 dry-run，`--yes` 只生成未付款订单和支付页，不代付）

        ## 通用换票（sysu sso）

        `sysu sso <站点地址>` 用统一认证换任意站点的登录态。

        ## 登录

        统一认证的入口是 `sysu login`；LMS、场馆、网费各自还有 `sysu lms login`、`sysu gym login`、`sysu netpay login`，都复用同一份 TGC 与凭据。插件页登录会依次执行这四个登录，并逐项显示「教务 / 在线教学 / 场馆 / 网费」状态。

        对话里遇到单个站点「会话失效 / 未登录」时，先自己执行对应站点的 login，不需要用户操作：

        - 教务：`sysu login`
        - 在线教学：`sysu lms login`
        - 场馆：`sysu gym login`
        - 网费：`sysu netpay login`

        只有命令要求企业微信验证码或 login 本身失败时，才让用户打开 DeepDeep 的「中山大学 CLI」插件页：页面会向企业微信发送 4 位验证码并提示输入，也会显示四个站点的状态。账号密码也只在插件页填写，不要在对话里索要。
        验证码错误时 CAS 可能写「NetID 或验证码错误」，但账号密码已通过；`--send-code` 会发新码。

        ## 要记住的

        - **请假、预约、续费都是写操作**：先 `--dry-run` 预览，用 ask_user_question 确认，确认后才 `--yes`。
        - 请假必须带附件（jpg/jpeg/png/gif/pdf），**附件名只能 ASCII**。
        - **课名会撞车**：课程码最可靠；只给课名时，能唯一确定才用，否则列候选让用户选。
        - 绩点是自算口径（只算有绩点的课），不是学校官方口径。
        - gym 预约会扣积分；取消会原路退回。netpay 续费只建未付款单，支付要用户自己完成。
        - 成绩、请假、预约、网费与个人信息只在本机会话使用，不得外发；不要输出凭据、验证码与 session.json。
        - 单个站点报「会话失效 / 登录信息失效」时先执行该站点的 login；需要验证码或失败才让用户在插件页处理，不要索取账号密码或验证码，也不要输出登录票据。
        - 不要自行创建定时任务或总结流程，除非用户明确要求。
    """.trimIndent()
}

internal fun siteText(site: SysuSite): LocalizedText = when (site) {
    SysuSite.Jwxt -> literal("教务", "Academics")
    SysuSite.Lms -> literal("在线教学", "LMS")
    SysuSite.Gym -> literal("体育场馆", "Sports venues")
    SysuSite.Netpay -> literal("网费", "Campus network")
}

internal fun siteLabelValue(site: SysuSite): String = when (site) {
    SysuSite.Jwxt -> "教务"
    SysuSite.Lms -> "在线教学"
    SysuSite.Gym -> "体育场馆"
    SysuSite.Netpay -> "网费"
}
