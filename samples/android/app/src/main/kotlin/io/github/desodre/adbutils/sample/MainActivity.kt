package io.github.desodre.adbutils.sample

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import io.github.desodre.adbutils.client.AdbClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate(savedInstanceState: Bundle?): Unit {
        super.onCreate(savedInstanceState)

        val host = EditText(this).apply {
            hint = "ADB Server host"
            setText("10.0.2.2")
        }
        val port = EditText(this).apply {
            hint = "Port"
            inputType = InputType.TYPE_CLASS_NUMBER
            setText("5037")
        }
        val status = TextView(this).apply { text = "Enter a reachable ADB Server." }
        val connect = Button(this).apply {
            text = "List devices"
            setOnClickListener {
                val selectedHost = host.text.toString().trim()
                val selectedPort = port.text.toString().toIntOrNull()
                if (selectedHost.isEmpty() || selectedPort == null) {
                    status.text = "Host and port are required."
                    return@setOnClickListener
                }
                isEnabled = false
                status.text = "Connecting…"
                scope.launch {
                    val result = runCatching {
                        withContext(Dispatchers.IO) { AdbClient(selectedHost, selectedPort).devices() }
                    }
                    status.text = result.fold(
                        onSuccess = { devices ->
                            devices.joinToString("\n").ifEmpty { "No devices reported." }
                        },
                        onFailure = { error -> error.message ?: error::class.java.simpleName },
                    )
                    isEnabled = true
                }
            }
        }

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (24 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
            addView(host, matchWidth())
            addView(port, matchWidth())
            addView(connect, matchWidth())
            addView(status, matchWidth())
        })
    }

    override fun onDestroy(): Unit {
        scope.cancel()
        super.onDestroy()
    }

    private fun matchWidth(): LinearLayout.LayoutParams = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    )
}
