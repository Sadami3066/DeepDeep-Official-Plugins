package io.github.whywood.deepdeep.plugin.sysu

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
            return "中山大学 CLI 尚未安装或版本不是最新的（当前 ${status.installedVersion ?: "未安装"}，期望 ${SysuCli.VERSION}）：让用户打开插件页「中山大学 CLI」，填写账号密码后点「保存并登录」，页面会自动安装。"
        }
        return if (status.loggedIn) {
            buildString {
                append("中山大学 CLI 已安装并已登录")
                if (!status.account.isNullOrBlank()) append("（账号 ${status.account}）")
                appendLine("。命令用法见技能 deepdeep-sysu；不熟悉时先跑 `sysu --help`。")
                appendLine("站点：sysu jwxt（教务）、sysu lms（在线教学）、sysu gym（体育场馆）、sysu netpay（网费）、sysu sso（通用换票）。")
                appendLine("成绩、请假、预约与个人信息仅限本机会话；不要输出账号、密码、验证码或 session.json。")
                appendLine("写操作必须先用 --dry-run 预览并让用户确认：请假、预约场地、网费续费；确认后才加 --yes。")
                appendLine("会话失效时让用户在插件页点「重新登录」；接口报错不要反复重试，说明现象即可。")
            }
        } else {
            "中山大学 CLI 已安装，当前未登录。让用户打开插件页填写 NetID 与密码并点「保存并登录」；需要验证码时页面会提示。不要向用户索要账号密码或验证码。"
        }
    }

    private val SKILL_MARKDOWN = """
        ---
        name: deepdeep-sysu
        description: 中山大学校园服务 CLI——教务（课表/成绩/考试/请假）、在线教学平台（课程/作业/文件）、体育场馆（空位/预约/取消）、网费（服务状态/订单/续费）与统一认证换票。用户问上课时间、成绩、作业截止、课件、场地、网费或想登进别的中大系统时用；命令是 `sysu`。
        ---

        # 中山大学 CLI（sysu）

        在 bash 中运行 `sysu <站点> <命令>`；默认排版文本，`gym` 子命令支持 `--json`。

        ## 教务（sysu jwxt）

        - 本周课表：`sysu jwxt`；指定周 `--week 8`；`--today` / `--tomorrow`
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
        - 空位：`sysu gym slots <关键字> --days 2`（`--json` 解析 `types[].fee.CreditFee`、`venues[].shortName`、`slots[].available`）
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

        `sysu login` 成功即统一认证已登录，场馆 token 会自动获取，不需要单独 `gym login`。
        会话失效会自动续期；真失效时让用户打开 DeepDeep 的「中山大学 CLI」插件页点「重新登录」：
        页面会向企业微信发送 4 位验证码并提示输入。账号密码也只在插件页填写，不要在对话里索要。
        验证码错误时 CAS 可能写「NetID 或验证码错误」，但账号密码已通过；`--send-code` 会发新码。

        ## 要记住的

        - **请假、预约、续费都是写操作**：先 `--dry-run` 预览，用 ask_user_question 确认，确认后才 `--yes`。
        - 请假必须带附件（jpg/jpeg/png/gif/pdf），**附件名只能 ASCII**。
        - **课名会撞车**：课程码最可靠；只给课名时，能唯一确定才用，否则列候选让用户选。
        - 绩点是自算口径（只算有绩点的课），不是学校官方口径。
        - gym 预约会扣积分；取消会原路退回。netpay 续费只建未付款单，支付要用户自己完成。
        - 成绩、请假、预约、网费与个人信息只在本机会话使用，不得外发；不要输出凭据、验证码与 session.json。
        - 不要自行创建定时任务或总结流程，除非用户明确要求。
    """.trimIndent()
}
