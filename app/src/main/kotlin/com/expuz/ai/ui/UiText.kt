package com.expuz.ai.ui

import android.content.Context
import com.expuz.ai.R
import com.expuz.ai.data.ConnectionMode
import com.expuz.ai.domain.ConnectionTestResult
import com.expuz.ai.domain.FailureKind
import com.expuz.ai.domain.ServerKind
import com.expuz.ai.domain.UrlProblem
import com.expuz.ai.domain.WebFailure

object UiText {

    fun urlProblem(context: Context, problem: UrlProblem): String = context.getString(
        when (problem) {
            UrlProblem.EMPTY -> R.string.url_error_empty
            UrlProblem.MALFORMED -> R.string.url_error_malformed
            UrlProblem.UNSUPPORTED_SCHEME -> R.string.url_error_scheme
            UrlProblem.USER_INFO_NOT_ALLOWED -> R.string.url_error_credentials
            UrlProblem.QUERY_OR_FRAGMENT_NOT_ALLOWED -> R.string.url_error_query
            UrlProblem.INSECURE_REMOTE_HTTP -> R.string.url_error_remote_http
            UrlProblem.CLEARTEXT_DISABLED -> R.string.url_error_http_disabled
            UrlProblem.INVALID_HOST -> R.string.url_error_host
        }
    )

    fun kind(context: Context, kind: ServerKind): String = context.getString(
        when (kind) {
            ServerKind.LOCAL -> R.string.kind_local
            ServerKind.REMOTE -> R.string.kind_remote
            ServerKind.UNKNOWN -> R.string.kind_unknown
        }
    )

    fun mode(context: Context, mode: ConnectionMode): String = context.getString(
        when (mode) {
            ConnectionMode.MANUAL -> R.string.settings_mode_single
            ConnectionMode.AUTO -> R.string.settings_mode_auto
            ConnectionMode.LOCAL_ONLY -> R.string.settings_mode_local
            ConnectionMode.REMOTE_ONLY -> R.string.settings_mode_remote
        }
    )

    fun failureTitle(context: Context, failure: WebFailure): String = when (failure.kind) {
        FailureKind.HTTP_ERROR -> if (failure.httpStatus != null) {
            context.getString(R.string.error_http_title, failure.httpStatus)
        } else {
            context.getString(R.string.error_http_generic_title)
        }

        else -> context.getString(
            when (failure.kind) {
                FailureKind.NO_NETWORK -> R.string.error_offline_title
                FailureKind.HOST_NOT_FOUND -> R.string.error_not_found_title
                FailureKind.CONNECTION_REFUSED -> R.string.error_refused_title
                FailureKind.TIMEOUT -> R.string.error_timeout_title
                FailureKind.TLS -> R.string.error_tls_title
                FailureKind.CLEARTEXT_BLOCKED -> R.string.error_cleartext_title
                FailureKind.WEBVIEW_CRASH -> R.string.error_crash_title
                FailureKind.BLOCKED -> R.string.error_blocked_title
                FailureKind.UNKNOWN -> R.string.error_unknown_title
                FailureKind.HTTP_ERROR -> R.string.error_http_generic_title
            }
        )
    }

    fun failureMessage(context: Context, failure: WebFailure): String = context.getString(
        when (failure.kind) {
            FailureKind.NO_NETWORK -> R.string.error_offline_message
            FailureKind.HOST_NOT_FOUND -> R.string.error_not_found_message
            FailureKind.CONNECTION_REFUSED -> R.string.error_refused_message
            FailureKind.TIMEOUT -> R.string.error_timeout_message
            FailureKind.TLS -> R.string.error_tls_message
            FailureKind.HTTP_ERROR -> {
                if (failure.httpStatus != null) {
                    R.string.error_http_message
                } else {
                    R.string.error_http_generic_message
                }
            }

            FailureKind.CLEARTEXT_BLOCKED -> R.string.error_cleartext_message
            FailureKind.WEBVIEW_CRASH -> R.string.error_crash_message
            FailureKind.BLOCKED -> R.string.error_blocked_message
            FailureKind.UNKNOWN -> R.string.error_unknown_message
        }
    )

    fun testSummary(context: Context, result: ConnectionTestResult): String {
        if (result.reachable) {
            val kind = kind(context, result.kind)
            return if (result.httpStatus != null) {
                context.getString(
                    R.string.test_result_success,
                    kind,
                    result.httpStatus,
                    result.latencyMs,
                )
            } else {
                context.getString(
                    R.string.test_result_success_no_status,
                    kind,
                    result.latencyMs,
                )
            }
        }

        val reason = result.urlProblem?.let { urlProblem(context, it) }
            ?: probeFailure(context, result.failure, result.httpStatus)
        return context.getString(R.string.test_result_failure, reason)
    }

    fun probeFailure(
        context: Context,
        failure: FailureKind?,
        httpStatus: Int? = null,
    ): String = when (failure) {
        FailureKind.NO_NETWORK -> context.getString(R.string.probe_unknown)
        FailureKind.HOST_NOT_FOUND -> context.getString(R.string.probe_dns)
        FailureKind.CONNECTION_REFUSED -> context.getString(R.string.probe_refused)
        FailureKind.TIMEOUT -> context.getString(R.string.probe_timeout)
        FailureKind.TLS -> context.getString(R.string.probe_tls)
        FailureKind.HTTP_ERROR -> if (httpStatus != null) {
            context.getString(R.string.probe_http, httpStatus)
        } else {
            context.getString(R.string.probe_unknown)
        }

        FailureKind.CLEARTEXT_BLOCKED -> context.getString(R.string.probe_cleartext)
        else -> context.getString(R.string.probe_unknown)
    }
}
