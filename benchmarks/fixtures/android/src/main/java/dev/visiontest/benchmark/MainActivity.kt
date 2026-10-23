package dev.visiontest.benchmark

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var content: LinearLayout
    private var scenario = "navigation"
    private var language = "en"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        scenario = intent.getStringExtra("scenario") ?: "navigation"
        language = intent.getStringExtra("language")?.takeIf { it == "it" } ?: "en"
        render()
    }

    private fun render() {
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        setContentView(content, LinearLayout.LayoutParams(-1, -1))
        button(R.id.start, text("start")).also(content::addView)
        when (scenario) {
            "navigation" -> {
                val field = EditText(this).apply { id = R.id.email; contentDescription = "email"; hint = "Email" }
                content.addView(field)
                button(R.id.continue_action, text("continue")).also(content::addView)
            }
            "appear" -> delayed(true)
            "disappear" -> delayed(false)
            "ambiguous" -> {
                button(R.id.continue_primary, text("continue")).also(content::addView)
                button(R.id.continue_secondary, text("continue")).also(content::addView)
            }
            "error" -> label(R.id.error, text("error")).also(content::addView)
            "missing" -> Unit
        }
        if (scenario != "appear" && scenario != "disappear") markReady()
    }

    private fun delayed(appears: Boolean) {
        val loading = label(R.id.loading, text("loading"))
        if (!appears) content.addView(loading)
        handler.postDelayed({
            if (isFinishing || isDestroyed) return@postDelayed
            if (appears) label(R.id.done, text("done")).also(content::addView) else content.removeView(loading)
            markReady()
        }, 750)
    }

    private fun markReady() {
        label(R.id.fixture_ready, "Ready").also(content::addView)
    }

    private fun button(id: Int, value: String) = Button(this).apply {
        this.id = id
        text = value
        contentDescription = idName(id)
        if (id == R.id.continue_action) setOnClickListener {
            content.removeAllViews()
            label(R.id.done, text("done")).also(content::addView)
        }
    }

    private fun label(id: Int, value: String) = TextView(this).apply {
        this.id = id
        text = value
        contentDescription = idName(id)
    }

    private fun idName(id: Int) = when (id) {
        R.id.start -> "start"
        R.id.fixture_ready -> "fixture_ready"
        R.id.email -> "email"
        R.id.continue_action -> "continue"
        R.id.continue_primary -> "continue_primary"
        R.id.continue_secondary -> "continue_secondary"
        R.id.loading -> "loading"
        R.id.done -> "done"
        R.id.error -> "error"
        else -> "email"
    }

    private fun text(key: String) = when (language) {
        "it" -> when (key) { "start" -> "Avvia"; "continue" -> "Continua"; "loading" -> "Caricamento"; "done" -> "Fatto"; "error" -> "Errore"; else -> key }
        else -> when (key) { "start" -> "Start"; "continue" -> "Continue"; "loading" -> "Loading"; "done" -> "Done"; "error" -> "Error"; else -> key }
    }
}
