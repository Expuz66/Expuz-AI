package com.expuz.ai.domain

enum class FailureKind {
    NO_NETWORK,
    HOST_NOT_FOUND,
    CONNECTION_REFUSED,
    TIMEOUT,
    TLS,
    HTTP_ERROR,
    CLEARTEXT_BLOCKED,
    WEBVIEW_CRASH,
    BLOCKED,
    UNKNOWN,
}

data class WebFailure(
    val kind: FailureKind,
    val url: String? = null,
    val httpStatus: Int? = null,
    val detail: String? = null,
)

fun UrlProblem.toFailureKind(): FailureKind = when (this) {
    UrlProblem.EMPTY,
    UrlProblem.MALFORMED,
    UrlProblem.USER_INFO_NOT_ALLOWED,
    UrlProblem.QUERY_OR_FRAGMENT_NOT_ALLOWED,
    UrlProblem.INVALID_HOST,
    UrlProblem.UNSUPPORTED_SCHEME,
    -> FailureKind.UNKNOWN

    UrlProblem.INSECURE_REMOTE_HTTP,
    UrlProblem.CLEARTEXT_DISABLED,
    -> FailureKind.CLEARTEXT_BLOCKED
}
