package com.kioskcamera

import android.app.AlertDialog
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

object ProjectPickerDialog {

    fun show(context: Context, onProjectSelected: (String) -> Unit) {
        val projects = ProjectManager.getProjects(context)
        val activeProject = ProjectManager.getActiveProject(context)
        val dp = { px: Int -> (px * context.resources.displayMetrics.density).toInt() }

        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }

        val dialog = AlertDialog.Builder(context)
            .setView(layout)
            .setNegativeButton("Cancel", null)
            .create()

        if (projects.isEmpty()) {
            val empty = TextView(context).apply {
                text = "No projects yet — create one below"
                textSize = 14f
                setTextColor(0xFF888888.toInt())
                setPadding(dp(16), dp(16), dp(16), dp(16))
                gravity = Gravity.CENTER
            }
            layout.addView(empty)
        } else {
            val scrollView = ScrollView(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            val listLayout = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
            }

            for (project in projects) {
                val isActive = project == activeProject
                val card = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(16), dp(14), dp(16), dp(14))
                    val lp = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    lp.bottomMargin = dp(4)
                    layoutParams = lp

                    background = GradientDrawable().apply {
                        cornerRadius = dp(8).toFloat()
                        if (isActive) {
                            setColor(0xFF2A2A1A.toInt())
                            setStroke(dp(2), 0xFFFFD700.toInt())
                        } else {
                            setColor(0xFF1E1E1E.toInt())
                            setStroke(dp(1), 0xFF333333.toInt())
                        }
                    }
                    isClickable = true
                    setOnClickListener {
                        dialog.dismiss()
                        onProjectSelected(project)
                    }
                }

                val icon = TextView(context).apply {
                    text = "\uD83D\uDCC1"
                    textSize = 20f
                    setPadding(0, 0, dp(12), 0)
                }
                card.addView(icon)

                val name = TextView(context).apply {
                    text = project
                    textSize = 17f
                    setTextColor(if (isActive) 0xFFFFD700.toInt() else 0xFFFFFFFF.toInt())
                    if (isActive) setTypeface(null, Typeface.BOLD)
                    layoutParams = LinearLayout.LayoutParams(0,
                        LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                card.addView(name)

                if (isActive) {
                    val check = TextView(context).apply {
                        text = "✓"
                        textSize = 18f
                        setTextColor(0xFFFFD700.toInt())
                    }
                    card.addView(check)
                }

                listLayout.addView(card)
            }

            scrollView.addView(listLayout)
            layout.addView(scrollView)
        }

        val spacer = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(12)
            )
        }
        layout.addView(spacer)

        val createBtn = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(0xFF448AFF.toInt())
            }
            isClickable = true
            setOnClickListener {
                dialog.dismiss()
                showCreateDialog(context, onProjectSelected)
            }
        }
        val plusIcon = TextView(context).apply {
            text = "＋"
            textSize = 18f
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(0, 0, dp(8), 0)
        }
        createBtn.addView(plusIcon)
        val createText = TextView(context).apply {
            text = "Create new project"
            textSize = 16f
            setTextColor(0xFFFFFFFF.toInt())
            setTypeface(null, Typeface.BOLD)
        }
        createBtn.addView(createText)
        layout.addView(createBtn)

        dialog.show()
    }

    private fun showCreateDialog(context: Context, onProjectSelected: (String) -> Unit) {
        val input = EditText(context).apply {
            hint = "Project number"
            setPadding(48, 32, 48, 32)
        }

        AlertDialog.Builder(context)
            .setTitle("New project")
            .setView(input)
            .setPositiveButton("Create") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    ProjectManager.addProject(context, name)
                    onProjectSelected(name)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
