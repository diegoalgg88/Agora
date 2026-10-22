package com.newoether.agora.ui.chat.message

import android.content.res.Resources
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import com.newoether.agora.R
import com.newoether.agora.model.MessageSegment

/**
 * The only localization layer for tool cards. Parsing and lifecycle inference live in
 * [ToolPresentationResolver]; compact, timeline and detail surfaces all call these functions.
 *
 * Each label is a [Resources] function so the WebUI can build the same text off the UI; the
 * Composable overloads below read the current configuration's resources.
 */
@Composable
internal fun currentResources(): Resources {
    // Read like stringResource does, so a configuration change recomposes the caller.
    LocalConfiguration.current
    return LocalContext.current.resources
}

@Composable
internal fun toolDisplayName(segment: MessageSegment): String =
    currentResources().toolDisplayName(segment)

@Composable
internal fun toolSummary(segment: MessageSegment): String = currentResources().toolSummary(segment)

@Composable
internal fun toolSummary(presentation: ToolPresentation): String =
    currentResources().toolSummary(presentation)

@Composable
internal fun shellToolSummary(presentation: ToolPresentation): String =
    currentResources().shellToolSummary(presentation)

@Composable
internal fun shellExecutionSummary(presentation: ToolPresentation): String =
    currentResources().shellExecutionSummary(presentation)

internal fun Resources.toolDisplayName(segment: MessageSegment): String {
    val toolName = segment.toolName.orEmpty()
    val kind = ToolPresentationResolver.kindForToolName(toolName)
    if (kind == ToolKind.MCP) {
        return mcpToolDisplayName(
            publicName = toolName,
            resolvedName = segment.toolDisplayName,
        ) ?: "MCP"
    }
    // El stand-in de una llamada malformed conserva el tool que el modelo realmente nombró.
    // Se etiqueta la card con ese nombre y el kind queda UNKNOWN, así el summary sigue siendo la
    // razón plana del fallo en vez de inventar una forma para una llamada que jamás corrió.
    val sentName = ToolPresentationResolver
        .malformedCallOriginals(toolName, segment.toolArgs)
        ?.name
    return toolBaseDisplayName(
        kind = kind,
        toolName = sentName?.takeIf { it.isNotBlank() } ?: toolName,
    )
}

private val MCP_PUBLIC_TOOL_NAME =
    Regex("""^mcp_[A-Za-z0-9]+_(.+)_[0-9a-fA-F]{6}$""")
private val TOOL_NAME_SEPARATOR = Regex("""[\s._-]+""")

/**
 * New calls use [resolvedName]. The public-name decoder is intentionally fallback-only for
 * history written before provider presentation metadata was persisted.
 */
internal fun mcpToolDisplayName(
    publicName: String?,
    resolvedName: String?,
): String? {
    val remoteName = resolvedName
        ?.takeIf { it.isNotBlank() }
        ?: publicName
            ?.let(MCP_PUBLIC_TOOL_NAME::matchEntire)
            ?.groupValues
            ?.getOrNull(1)
    return remoteName
        ?.trim()
        ?.split(TOOL_NAME_SEPARATOR)
        ?.filter(String::isNotBlank)
        ?.joinToString(" ") { word ->
            word.replaceFirstChar { char ->
                if (char.isLowerCase()) char.uppercaseChar() else char
            }
        }
        ?.takeIf(String::isNotBlank)
}

private fun Resources.toolBaseDisplayName(
    kind: ToolKind,
    toolName: String,
): String = when (kind) {
    ToolKind.MEMORY_LIST -> getString(R.string.tool_look_up_memories)
    ToolKind.MEMORY_READ -> getString(R.string.tool_read_memory)
    ToolKind.MEMORY_CREATE -> getString(R.string.tool_add_memory)
    ToolKind.MEMORY_EDIT -> getString(R.string.tool_edit_memory)
    ToolKind.MEMORY_DELETE -> getString(R.string.tool_delete_memory)
    ToolKind.MEMORY_UPDATE_ACTIVE -> getString(R.string.tool_update_active_memory)
    ToolKind.SKILL_LIST -> getString(R.string.tool_list_skills)
    ToolKind.SKILL_READ -> getString(R.string.tool_read_skill)
    ToolKind.SKILL_CREATE -> getString(R.string.tool_create_skill)
    ToolKind.SKILL_EDIT -> getString(R.string.tool_edit_skill)
    ToolKind.SKILL_DELETE -> getString(R.string.tool_delete_skill)
    ToolKind.WEB_SEARCH -> when (toolName) {
        "openai_search" -> getString(R.string.openai_search)
        "google_search" -> getString(R.string.google_search)
        else -> getString(R.string.tool_web_search)
    }
    ToolKind.WEB_FETCH -> getString(R.string.tool_web_fetch)
    ToolKind.CONVERSATION_SEARCH -> getString(R.string.tool_search_conversations)
    ToolKind.CONVERSATION_LIST -> getString(R.string.tool_list_conversations)
    ToolKind.CONVERSATION_READ -> getString(R.string.tool_read_conversation)
    ToolKind.SHELL_LIST -> getString(R.string.tool_list_shells)
    ToolKind.SHELL_EXECUTE -> getString(R.string.tool_execute_shell)
    ToolKind.SHELL_JOB_LIST -> getString(R.string.tool_shell_jobs)
    ToolKind.SHELL_JOB_GET -> getString(R.string.tool_shell_job)
    ToolKind.SHELL_JOB_WAIT -> getString(R.string.tool_wait_for_job)
    ToolKind.SHELL_JOB_STOP -> getString(R.string.tool_stop_shell_job)
    ToolKind.FILE_READ -> getString(R.string.tool_file_read)
    ToolKind.FILE_WRITE -> getString(R.string.tool_file_write)
    ToolKind.FILE_EDIT -> getString(R.string.tool_file_edit)
    ToolKind.FILE_GLOB -> getString(R.string.tool_file_glob)
    ToolKind.FILE_GREP -> getString(R.string.tool_file_grep)
    ToolKind.IMAGE_VIEW -> getString(R.string.tool_view_image)
    ToolKind.IMAGE_GENERATE -> getString(R.string.tool_generate_image)
    ToolKind.TASK_CREATE -> getString(R.string.tool_create_task)
    ToolKind.TASK_LIST -> getString(R.string.tool_list_tasks)
    ToolKind.TASK_DELETE -> getString(R.string.tool_delete_task)
    ToolKind.LOOP_START -> getString(R.string.tool_start_loop)
    ToolKind.LOOP_STOP -> getString(R.string.tool_stop_loop)
    ToolKind.MCP -> "MCP"
    ToolKind.UNKNOWN -> if (toolName == "code_execution") {
        getString(R.string.code_execution)
    } else {
        toolName
            .ifBlank { getString(R.string.tool_context) }
            .split("_")
            .joinToString(" ") { word ->
                word.replaceFirstChar { char -> char.uppercaseChar() }
            }
    }
}

internal fun Resources.toolSummary(segment: MessageSegment): String {
    return this.toolSummary(ToolPresentationResolver.resolve(segment))
}

internal fun Resources.toolSummary(presentation: ToolPresentation): String {
    if (presentation.kind == ToolKind.SHELL_EXECUTE) {
        return this.shellToolSummary(presentation)
    }
    val subject = presentation.subject
    return when (presentation.state) {
        ToolPresentationState.FAILED -> when {
            presentation.kind == ToolKind.SHELL_EXECUTE &&
                presentation.exitCode != null &&
                presentation.exitCode != 0 -> getString(
                    R.string.tool_shell_returned_exit_code,
                    presentation.exitCode,
                )
            else -> presentation.errorMessage?.take(160)
                ?: getString(R.string.tool_call_failed)
        }
        ToolPresentationState.STOPPED -> getString(R.string.tool_execution_stopped)
        ToolPresentationState.BACKGROUND_RUNNING -> {
            val job = presentation.jobId ?: subject
            if (job == null) {
                getString(R.string.tool_background_job_running_default)
            } else {
                getString(R.string.tool_background_job_running, job)
            }
        }
        ToolPresentationState.CALLING,
        ToolPresentationState.RUNNING -> runningSummary(presentation, subject)
        ToolPresentationState.EMPTY -> emptySummary(presentation, subject)
        ToolPresentationState.COMPLETED -> completedSummary(presentation, subject)
    }
}

private fun Resources.runningSummary(
    presentation: ToolPresentation,
    subject: String?,
): String = when (presentation.kind) {
    ToolKind.MEMORY_LIST -> getString(R.string.tool_looking_up_memories)
    ToolKind.MEMORY_READ -> optionalSubjectSummary(
        subject,
        R.string.tool_reading_memory,
        R.string.tool_progress_reading,
    )
    ToolKind.MEMORY_CREATE -> optionalSubjectSummary(
        subject,
        R.string.tool_saving_memory,
        R.string.tool_progress_saving,
    )
    ToolKind.MEMORY_EDIT -> optionalSubjectSummary(
        subject,
        R.string.tool_updating_memory,
        R.string.tool_progress_updating,
    )
    ToolKind.MEMORY_DELETE -> optionalSubjectSummary(
        subject,
        R.string.tool_removing_memory,
        R.string.tool_progress_removing,
    )
    ToolKind.MEMORY_UPDATE_ACTIVE -> getString(R.string.tool_updating_active)
    ToolKind.SKILL_LIST -> getString(R.string.tool_list_skills)
    ToolKind.SKILL_READ -> getString(R.string.tool_read_skill)
    ToolKind.SKILL_CREATE -> getString(R.string.tool_create_skill)
    ToolKind.SKILL_EDIT -> getString(R.string.tool_edit_skill)
    ToolKind.SKILL_DELETE -> getString(R.string.tool_delete_skill)
    ToolKind.WEB_SEARCH -> optionalSubjectSummary(
        subject,
        R.string.tool_searching_web,
        R.string.tool_progress_searching,
    )
    ToolKind.WEB_FETCH -> optionalSubjectSummary(
        subject,
        R.string.tool_web_fetching,
        R.string.tool_progress_fetching,
    )
    ToolKind.CONVERSATION_SEARCH -> optionalSubjectSummary(
        subject,
        R.string.tool_searching_for,
        R.string.tool_progress_searching,
    )
    ToolKind.CONVERSATION_LIST -> getString(R.string.tool_listing_conversations)
    ToolKind.CONVERSATION_READ -> optionalSubjectSummary(
        subject,
        R.string.tool_reading_conversation_subject,
        R.string.tool_progress_reading,
    )
    ToolKind.SHELL_LIST -> getString(R.string.tool_listing_shells)
    ToolKind.SHELL_EXECUTE -> optionalSubjectSummary(
        singleLineShellCommand(subject),
        R.string.tool_executing_shell,
        R.string.tool_progress_executing,
    )
    ToolKind.SHELL_JOB_LIST -> getString(R.string.tool_listing_shell_jobs)
    ToolKind.SHELL_JOB_WAIT -> optionalSubjectSummary(
        subject,
        R.string.tool_waiting_shell_job,
        R.string.tool_waiting_shell_job_default,
    )
    ToolKind.SHELL_JOB_GET -> optionalSubjectSummary(
        subject,
        R.string.tool_reading_shell_job,
        R.string.tool_progress_reading,
    )
    ToolKind.SHELL_JOB_STOP -> optionalSubjectSummary(
        subject,
        R.string.tool_stopping_shell_job,
        R.string.tool_progress_stopping,
    )
    ToolKind.FILE_READ -> optionalSubjectSummary(
        subject,
        R.string.tool_reading_file,
        R.string.tool_progress_reading,
    )
    ToolKind.FILE_WRITE -> optionalSubjectSummary(
        subject,
        R.string.tool_writing_file,
        R.string.tool_progress_writing,
    )
    ToolKind.FILE_EDIT -> optionalSubjectSummary(
        subject,
        R.string.tool_editing_file,
        R.string.tool_progress_editing,
    )
    ToolKind.FILE_GLOB -> optionalSubjectSummary(
        subject,
        R.string.tool_finding_files,
        R.string.tool_progress_finding,
    )
    ToolKind.FILE_GREP -> optionalSubjectSummary(
        subject,
        R.string.tool_searching_file,
        R.string.tool_progress_searching,
    )
    ToolKind.IMAGE_VIEW -> optionalSubjectSummary(
        subject,
        R.string.tool_viewing_image,
        R.string.tool_progress_viewing,
    )
    ToolKind.IMAGE_GENERATE -> optionalSubjectSummary(
        subject,
        R.string.tool_generating_image_subject,
        R.string.tool_progress_generating,
    )
    ToolKind.TASK_CREATE -> optionalSubjectSummary(
        subject,
        R.string.tool_creating_task_subject,
        R.string.tool_progress_creating,
    )
    ToolKind.TASK_LIST -> getString(R.string.tool_listing_tasks)
    ToolKind.TASK_DELETE -> optionalSubjectSummary(
        subject,
        R.string.tool_deleting_task_subject,
        R.string.tool_progress_deleting,
    )
    ToolKind.LOOP_START -> getString(R.string.tool_progress_starting)
    ToolKind.LOOP_STOP -> getString(R.string.tool_progress_stopping)
    ToolKind.MCP,
    ToolKind.UNKNOWN -> getString(R.string.tool_calling_ellipsis)
}

internal sealed interface ShellPresentationStatus {
    data object Executing : ShellPresentationStatus
    data class Background(val jobId: String?) : ShellPresentationStatus
    data object Stopped : ShellPresentationStatus
    data class Exit(val code: Int?) : ShellPresentationStatus
}

internal fun shellPresentationStatus(presentation: ToolPresentation): ShellPresentationStatus =
    when {
        presentation.state == ToolPresentationState.BACKGROUND_RUNNING ->
            ShellPresentationStatus.Background(presentation.jobId)
        presentation.isActive -> ShellPresentationStatus.Executing
        presentation.state == ToolPresentationState.STOPPED -> ShellPresentationStatus.Stopped
        else -> ShellPresentationStatus.Exit(presentation.exitCode)
    }

internal fun Resources.shellToolSummary(presentation: ToolPresentation): String =
    when (val status = shellPresentationStatus(presentation)) {
        ShellPresentationStatus.Executing -> optionalSubjectSummary(
            singleLineShellCommand(presentation.subject),
            R.string.tool_executing_shell,
            R.string.tool_progress_executing,
        )
        is ShellPresentationStatus.Background ->
            getString(R.string.tool_background_job_running_default)
        ShellPresentationStatus.Stopped -> getString(R.string.tool_state_stopped)
        is ShellPresentationStatus.Exit -> status.code?.let { code ->
            getString(R.string.tool_shell_returned_code, code)
        } ?: getString(R.string.tool_shell_execution_completed)
    }

internal fun Resources.shellExecutionSummary(presentation: ToolPresentation): String =
    when (val status = shellPresentationStatus(presentation)) {
        ShellPresentationStatus.Executing -> getString(R.string.tool_state_executing)
        is ShellPresentationStatus.Background ->
            getString(R.string.tool_background_job_running_default)
        ShellPresentationStatus.Stopped -> getString(R.string.tool_state_stopped)
        is ShellPresentationStatus.Exit -> status.code?.let { code ->
            getString(R.string.tool_shell_detail_returned_code, code)
        } ?: getString(R.string.tool_shell_detail_executed)
    }

internal fun singleLineShellCommand(
    command: String?,
    maxCharacters: Int = 120,
): String? = normalizeToolSummarySubject(command, maxCharacters)

private fun Resources.optionalSubjectSummary(
    subject: String?,
    @StringRes withSubject: Int,
    @StringRes withoutSubject: Int,
): String = if (subject.isNullOrBlank()) {
    getString(withoutSubject)
} else {
    getString(withSubject, subject)
}

private fun Resources.emptySummary(
    presentation: ToolPresentation,
    subject: String?,
): String = when (presentation.kind) {
    ToolKind.MEMORY_LIST -> getString(R.string.tool_lookup_default)
    ToolKind.WEB_SEARCH -> optionalSubjectSummary(
        subject,
        R.string.tool_web_search_no_result,
        R.string.tool_web_search_no_result_default,
    )
    ToolKind.CONVERSATION_SEARCH -> optionalSubjectSummary(
        subject,
        R.string.tool_conversation_search_no_result,
        R.string.tool_conversation_search_no_result_default,
    )
    ToolKind.CONVERSATION_LIST -> getString(R.string.tool_listed_no_conversations)
    ToolKind.SHELL_LIST -> getString(R.string.tool_shell_list_done)
    ToolKind.SHELL_EXECUTE -> shellExecutionSummary(presentation)
    ToolKind.SHELL_JOB_LIST -> getString(R.string.tool_no_shell_jobs)
    ToolKind.FILE_READ -> optionalSubjectSummary(
        subject,
        R.string.tool_read_file_empty,
        R.string.tool_read_file_empty_default,
    )
    ToolKind.FILE_GLOB -> getString(R.string.tool_found_no_files)
    ToolKind.FILE_GREP -> getString(R.string.tool_found_no_matches)
    ToolKind.TASK_LIST -> getString(R.string.tool_listed_tasks)
    else -> completedSummary(presentation, subject)
}

private fun Resources.completedSummary(
    presentation: ToolPresentation,
    subject: String?,
): String = when (presentation.kind) {
    ToolKind.MEMORY_LIST -> getString(
        R.string.tool_lookup_count,
        presentation.count ?: 0,
    )
    ToolKind.SKILL_LIST -> getString(R.string.tool_list_skills)
    ToolKind.SKILL_READ -> getString(R.string.tool_read_skill)
    ToolKind.SKILL_CREATE -> getString(R.string.tool_create_skill)
    ToolKind.SKILL_EDIT -> getString(R.string.tool_edit_skill)
    ToolKind.SKILL_DELETE -> getString(R.string.tool_delete_skill)
    ToolKind.MEMORY_READ -> optionalSubjectSummary(
        subject,
        R.string.tool_read_memory_name,
        R.string.tool_read_memory_success,
    )
    ToolKind.MEMORY_CREATE -> optionalSubjectSummary(
        subject,
        R.string.tool_save_memory_name,
        R.string.tool_save_memory_default,
    )
    ToolKind.MEMORY_EDIT -> optionalSubjectSummary(
        subject,
        R.string.tool_edit_memory_name,
        R.string.tool_edit_memory_default,
    )
    ToolKind.MEMORY_DELETE -> optionalSubjectSummary(
        subject,
        R.string.tool_delete_memory_name,
        R.string.tool_delete_memory_default,
    )
    ToolKind.MEMORY_UPDATE_ACTIVE -> getString(R.string.tool_update_active_default)
    ToolKind.WEB_SEARCH -> if (subject == null || presentation.count == null) {
        getString(R.string.tool_web_search_done_default)
    } else {
        getString(R.string.tool_web_search_done, presentation.count, subject)
    }
    ToolKind.WEB_FETCH -> optionalSubjectSummary(
        subject,
        R.string.tool_web_fetch_done,
        R.string.tool_web_fetch_done_default,
    )
    ToolKind.CONVERSATION_SEARCH -> if (subject == null) {
        getString(
            R.string.tool_conversation_search_done_default,
            presentation.count ?: 0,
        )
    } else {
        getString(
            R.string.tool_conversation_search_done_for,
            presentation.count ?: 0,
            subject,
        )
    }
    ToolKind.CONVERSATION_LIST -> getString(
        R.string.tool_listed_conversations,
        presentation.count ?: 0,
    )
    ToolKind.CONVERSATION_READ -> optionalSubjectSummary(
        subject,
        R.string.tool_read_conversation_done,
        R.string.tool_read_conversation_done_default,
    )
    ToolKind.SHELL_LIST -> getString(
        R.string.tool_shell_list_count,
        presentation.count ?: 0,
    )
    ToolKind.SHELL_EXECUTE -> shellExecutionSummary(presentation)
    ToolKind.SHELL_JOB_LIST -> getString(
        R.string.tool_shell_job_count,
        presentation.count ?: 0,
    )
    ToolKind.SHELL_JOB_WAIT -> shellToolSummary(presentation)
    ToolKind.SHELL_JOB_GET -> optionalSubjectSummary(
        presentation.jobId ?: subject,
        R.string.tool_shell_job_status,
        R.string.tool_shell_job_read_default,
    )
    ToolKind.SHELL_JOB_STOP -> optionalSubjectSummary(
        presentation.jobId ?: subject,
        R.string.tool_stopped_shell_job,
        R.string.tool_shell_job_stopped_default,
    )
    ToolKind.FILE_READ -> optionalSubjectSummary(
        subject,
        R.string.tool_read_file_done,
        R.string.tool_read_file_done_default,
    )
    ToolKind.FILE_WRITE -> optionalSubjectSummary(
        subject,
        R.string.tool_wrote_file,
        R.string.tool_wrote_file_default,
    )
    ToolKind.FILE_EDIT -> optionalSubjectSummary(
        subject,
        R.string.tool_edited_file,
        R.string.tool_edited_file_default,
    )
    ToolKind.FILE_GLOB -> getString(R.string.tool_found_files, presentation.count ?: 0)
    ToolKind.FILE_GREP -> getString(R.string.tool_searched_file, presentation.count ?: 0)
    ToolKind.IMAGE_VIEW -> optionalSubjectSummary(
        subject,
        R.string.tool_viewed_image,
        R.string.tool_viewed_image_default,
    )
    ToolKind.IMAGE_GENERATE -> getString(R.string.tool_generated_image)
    ToolKind.TASK_CREATE -> getString(R.string.tool_created_task)
    ToolKind.TASK_LIST -> getString(R.string.tool_listed_tasks)
    ToolKind.TASK_DELETE -> getString(R.string.tool_deleted_task)
    ToolKind.LOOP_START -> getString(R.string.tool_started_loop)
    ToolKind.LOOP_STOP -> getString(R.string.tool_stopped_loop)
    ToolKind.MCP,
    ToolKind.UNKNOWN -> getString(R.string.tool_done)
}
