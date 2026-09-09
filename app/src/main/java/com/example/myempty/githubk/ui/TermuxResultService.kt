package com.example.myempty.githubk.ui

import android.app.IntentService
import android.content.Intent
import android.os.Bundle

/** Receives RUN_COMMAND results from Termux and forwards stdout/stderr to the active terminal. */
class TermuxResultService : IntentService("GitHubK-TermuxResult") {
    override fun onHandleIntent(intent: Intent?) {
        val bundle = intent?.getBundleExtra("com.termux.service.extra.PLUGIN_RESULT_BUNDLE") ?: return
        val stdout = bundle.getString("com.termux.service.extra.PLUGIN_RESULT_BUNDLE_STDOUT").orEmpty()
        val stderr = bundle.getString("com.termux.service.extra.PLUGIN_RESULT_BUNDLE_STDERR").orEmpty()
        val exit = bundle.getInt("com.termux.service.extra.PLUGIN_RESULT_BUNDLE_EXIT_CODE", 0)
        val err = bundle.getString("com.termux.service.extra.PLUGIN_RESULT_BUNDLE_ERRMSG").orEmpty()
        val text = buildString {
            if (stdout.isNotEmpty()) append(stdout)
            if (stderr.isNotEmpty()) append(if (isNotEmpty()) "\n" else "").append(stderr)
            if (err.isNotEmpty()) append("\n[Termux] ").append(err)
            append("\n[exit=$exit]\n")
        }
        TermuxResultBridge.sink?.invoke(text)
    }
}

object TermuxResultBridge {
    @Volatile var sink: ((String) -> Unit)? = null
}
