# Keep public Issuetracker SDK API and the Activity that hosts the
# reporter UI — the Activity is referenced via Intent class lookup,
# not by direct symbol reference, so R8 may otherwise strip it.
-keep public class io.issuetracker.sdk.Issuetracker { *; }
-keep public class io.issuetracker.sdk.IssueReportType { *; }
-keep class io.issuetracker.sdk.ui.ReportActivity { *; }
