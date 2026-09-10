package com.example.myempty.githubk.core

import org.json.JSONObject

/**
 * SecurityPolicy v6.0 — AI 操作安全分级
 *
 * 三级模型（默认值）：
 *  - LOW   只读/检索/日志：自动执行，不打扰
 *  - MED   文件新增/修改/增量编辑：自动执行（受工作区白名单与快照回滚保护）
 *  - HIGH  删除/配置变更/高危 shell/系统级安装：进入 UI 审批门控，需用户逐项确认
 *
 * 另提供「目录白名单」说明与高风险工具/参数判定，供 AgentRuntime 与 UI 共享。
 */
object SecurityPolicy {

    /** 高风险工具（执行前一律弹窗审批）。 */
    val HIGH_RISK_TOOLS: Set<String> = setOf(
        "delete_file", "run_command", "shell", "terminal", "exec",
        "shizuku_exec", "shizuku", "install_packages", "pkg_install",
        "toolchain_install", "toolchain_install_full",
        "local_ai_install", "localai_install",
        "repo_push", "git_push", "git_commit", "create_project", "export_apk",
        "download_file", "download", "mcp_call_tool", "run_skill",
        // v5.1 GitHub 全量管理：不可逆/远端变更
        "repo_delete", "repo_upload_zip", "repo_branch_delete",
        "git_branch_delete", "git_branch_delete_remote",
        "git_push_force", "git_force_push", "release_delete"
    )

    /** 中风险：默认自动，仅记录审计。 */
    val MED_RISK_TOOLS: Set<String> = setOf(
        "write_file", "patch_file", "append_file", "batch_replace",
        "dependency_add", "dependency_lock", "auto_docs", "import_ability", "save_plugin",
        // v5.1 远端仓库常规写操作（可恢复/可编辑）
        "repo_create", "repo_rename", "repo_config", "repo_branch_create", "repo_clone",
        "release_create", "release_edit", "release_upload", "issue_create", "issue_comment",
        "pr_create", "pr_merge"
    )

    fun highRiskReason(tool: String, argsJson: String): String = when (tool.lowercase()) {
        "delete_file", "delete" -> "删除文件（可回滚快照保护）"
        "run_command", "shell", "terminal", "exec" -> "执行 shell 命令"
        "shizuku_exec", "shizuku" -> "系统特权命令"
        "install_packages", "pkg_install", "toolchain_install", "toolchain_install_full" -> "系统依赖/工具链安装"
        "local_ai_install", "localai_install" -> "在本机安装 AI 推理依赖（下载模型/运行库并落盘到 App 私有目录）"
        "git_push", "repo_push" -> "推送到远端仓库"
        "git_commit" -> "创建提交"
        "create_project" -> "新建/初始化项目"
        "export_apk", "export_file" -> "导出文件到设备存储"
        "download_file", "download" -> "下载并落盘文件"
        "mcp_call_tool" -> "调用外部 MCP 工具"
        "run_skill" -> "运行技能工作流"
        "repo_delete" -> "删除整个 GitHub 远程仓库（不可恢复）"
        "repo_upload_zip" -> "压缩包整体推送/覆盖远程仓库文件"
        "repo_branch_delete", "git_branch_delete_remote" -> "删除远程分支"
        "git_branch_delete" -> "删除本地分支"
        "git_push_force", "git_force_push" -> "强制推送（覆盖远端历史）"
        "release_delete" -> "删除 GitHub Release"
        else -> "高风险操作"
    }.let { if (argsJson.isNotBlank() && argsJson.length < 120) "$it：${argsJson}" else it }

    /** 判定是否需要在执行前弹窗审批（true=需要用户确认）。 */
    fun needsApproval(tool: String, argsJson: String): Boolean {
        val t = tool.lowercase()
        if (t in HIGH_RISK_TOOLS) return true
        if (t == "write_file" || t == "patch_file" || t == "append_file" || t == "batch_replace") {
            // 只要路径在项目白名单内就属于中风险（由 resolveSafe 限制），无需审批
            return false
        }
        return false
    }

    /** 安全策略说明文本（供 UI 展示）。 */
    fun policyText(): String = """
工作目录白名单：Agent 只能读/写 GitHubK 已登记的 Project 目录；写入一律经过 resolveSafe 路径校验，禁止越出项目目录。
风险分级：
  低风险（读取/检索/日志）→ 自动执行
  中风险（新增/修改文件、增量 diff）→ 自动执行 + 写前快照，可一键回滚
  高风险（删除/配置变更/高危 shell/系统依赖/特权命令）→ 执行前弹窗审批
自定义能力沙盒：AI 自建插件统一存放 .studio/plugins/，可在「功能面板 → 插件与能力」一键停用。
完整审计：每次修改/工具调用/审批/回滚都写入审计日志，可查看与导出。
    """.trim()

    fun parseArgs(s: String): JSONObject = runCatching { JSONObject(s) }.getOrElse { JSONObject() }
}
