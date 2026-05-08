package io.issuetracker.sdk

/**
 * Issue classification sent to the server. [wireValue] matches the
 * server-side enum so we can transmit as a plain string without
 * depending on the shared schema package.
 */
enum class IssueReportType(val wireValue: String, val displayName: String) {
    BUG("bug", "Bug"),
    TASK("task", "Task"),
    STORY("story", "Story"),
}
