package com.thyu.passthroughbrowser

import android.content.Context
import org.json.JSONObject
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException

object ServerResponses {
    private fun prefix(ctx: Context, msg: String) = ctx.getString(R.string.toast_prefix, msg)

    private fun field(body: String, name: String): String? = try {
        JSONObject(body).optString(name, "").ifEmpty { null }
    } catch (e: Exception) {
        null
    }

    /** Non-success description shared by /health and /open; null if [r] is HTTP 200. */
    private fun failureText(ctx: Context, r: CallResult, target: String): String? = when (r) {
        is CallResult.Failure -> when (r.error) {
            is ConnectException, is SocketTimeoutException, is NoRouteToHostException ->
                ctx.getString(R.string.msg_cant_reach, target)
            else -> ctx.getString(
                R.string.msg_send_failed, target,
                r.error.message ?: r.error.javaClass.simpleName,
            )
        }
        is CallResult.Http -> when (r.status) {
            200 -> null
            403 -> ctx.getString(R.string.msg_forbidden, target)
            422 -> ctx.getString(R.string.msg_refused_link, field(r.body, "message") ?: r.body.take(120))
            429 -> ctx.getString(R.string.msg_rate_limited)
            else -> ctx.getString(
                R.string.msg_server_error, r.status,
                field(r.body, "message") ?: r.body.take(120),
            )
        }
    }

    /** For /health: Pair(true, version) if status 200 and JSON name == SERVER_NAME, else (false, plain reason without any prefix). */
    fun describeHealth(ctx: Context, r: CallResult, target: String): Pair<Boolean, String> {
        failureText(ctx, r, target)?.let { return false to it }
        val body = (r as CallResult.Http).body
        return try {
            val o = JSONObject(body)
            if (o.optString("name") == SERVER_NAME) {
                true to o.optString("version", "?")
            } else {
                false to ctx.getString(R.string.msg_not_ours, target)
            }
        } catch (e: Exception) {
            false to ctx.getString(R.string.msg_not_ours, target)
        }
    }

    /** For /open failures: user-facing Toast text, or null if [r] is HTTP 200. */
    fun openFailureMessage(ctx: Context, r: CallResult, target: String): String? =
        failureText(ctx, r, target)?.let { prefix(ctx, it) }
}
