package com.kioskcamera

import android.app.AlertDialog
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputFilter
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

object ProjectPickerDialog {

    fun show(context: Context, onProjectSelected: (String) -> Unit, onDismiss: (() -> Unit)? = null) {
        val projects = ProjectManager.getProjects(context)
        val activeProject = ProjectManager.getActiveProject(context)
        val dp = { px: Int -> (px * context.resources.displayMetrics.density).toInt() }

        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }

        val hasProjects = projects.isNotEmpty()

        val builder = AlertDialog.Builder(context)
            .setView(layout)
            .setNegativeButton("Cancel", null)
        if (hasProjects) {
            builder.setNeutralButton("Manage") { _, _ ->
                showManageDialog(context, onProjectSelected)
            }
        }
        val dialog = builder.create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(0xFFFF6B6B.toInt())
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setTextColor(0xFFBB86FC.toInt())
        }
        dialog.setOnDismissListener { onDismiss?.invoke() }

        if (!hasProjects) {
            val empty = TextView(context).apply {
                text = "No projects yet — create one below"
                textSize = 14f
                setTextColor(0xFF888888.toInt())
                setPadding(dp(16), dp(48), dp(16), dp(48))
                gravity = Gravity.CENTER
            }
            layout.addView(empty)
        } else {
            val maxHeight = (context.resources.displayMetrics.heightPixels * 0.4).toInt()
            // Estimate if list will exceed max height and set fixed height upfront
            val estimatedItemHeight = dp(46) // 14+14 padding + ~18 text
            val estimatedTotalHeight = projects.size * estimatedItemHeight
            val scrollView = ScrollView(context).apply {
                if (estimatedTotalHeight > maxHeight) {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, maxHeight
                    )
                }
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

            // Scroll to active project after layout
            val activeIndex = projects.indexOf(activeProject)
            if (activeIndex >= 0) {
                scrollView.post {
                    val child = listLayout.getChildAt(activeIndex)
                    if (child != null) {
                        scrollView.scrollTo(0, child.top)
                    }
                }
            }
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

    private fun showManageDialog(context: Context, onProjectSelected: (String) -> Unit) {
        val dp = { px: Int -> (px * context.resources.displayMetrics.density).toInt() }
        val activeProject = ProjectManager.getActiveProject(context)

        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }

        val projects = ProjectManager.getProjects(context).toMutableList()

        val dialog = AlertDialog.Builder(context)
            .setTitle("Manage projects")
            .setView(layout)
            .setPositiveButton("Done") { _, _ ->
                if (ProjectManager.getProjects(context).isEmpty()) {
                    showCreateDialog(context, onProjectSelected)
                } else {
                    show(context, onProjectSelected)
                }
            }
            .setNeutralButton("Remove all") { _, _ ->
                AlertDialog.Builder(context)
                    .setTitle("Remove all projects?")
                    .setPositiveButton("Remove all") { _, _ ->
                        val allProjects = ProjectManager.getProjects(context)
                        allProjects.forEach { ProjectManager.removeProject(context, it) }
                        ProjectManager.setActiveProject(context, null)
                        showCreateDialog(context, onProjectSelected)
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(0xFF81C784.toInt())
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setTextColor(0xFFFF6B6B.toInt())
        }

        fun updateRemoveAllVisibility() {
            val btn = dialog.getButton(AlertDialog.BUTTON_NEUTRAL)
            if (ProjectManager.getProjects(context).isEmpty()) {
                btn?.visibility = View.GONE
            } else {
                btn?.visibility = View.VISIBLE
            }
        }

        fun rebuildList() {
            layout.removeAllViews()
            val currentProjects = ProjectManager.getProjects(context)

            if (currentProjects.isEmpty()) {
                val empty = TextView(context).apply {
                    text = "No projects"
                    textSize = 14f
                    setTextColor(0xFF888888.toInt())
                    setPadding(dp(16), dp(48), dp(16), dp(48))
                    gravity = Gravity.CENTER
                }
                layout.addView(empty)
            } else {
                for (project in currentProjects) {
                    val isActive = project == ProjectManager.getActiveProject(context)
                    val row = LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(dp(16), dp(10), dp(8), dp(10))
                        val lp = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        )
                        lp.bottomMargin = dp(4)
                        layoutParams = lp

                        background = GradientDrawable().apply {
                            cornerRadius = dp(8).toFloat()
                            setColor(0xFF1E1E1E.toInt())
                            setStroke(dp(1), if (isActive) 0xFFFFD700.toInt() else 0xFF333333.toInt())
                        }
                    }

                    val icon = TextView(context).apply {
                        text = "\uD83D\uDCC1"
                        textSize = 18f
                        setPadding(0, 0, dp(10), 0)
                    }
                    row.addView(icon)

                    val name = TextView(context).apply {
                        text = project
                        textSize = 16f
                        setTextColor(if (isActive) 0xFFFFD700.toInt() else 0xFFFFFFFF.toInt())
                        if (isActive) setTypeface(null, Typeface.BOLD)
                        layoutParams = LinearLayout.LayoutParams(0,
                            LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    }
                    row.addView(name)

                    if (isActive) {
                        val activeLabel = TextView(context).apply {
                            text = "active"
                            textSize = 11f
                            setTextColor(0xFFFFD700.toInt())
                            setPadding(0, 0, dp(8), 0)
                        }
                        row.addView(activeLabel)
                    }

                    val deleteBtn = TextView(context).apply {
                        text = "✕"
                        textSize = 18f
                        setTextColor(0xFFFF6B6B.toInt())
                        setPadding(dp(12), dp(4), dp(12), dp(4))
                        setOnClickListener {
                            AlertDialog.Builder(context)
                                .setTitle("Remove \"$project\"?")
                                .setMessage(if (isActive) "This is the active project. It will be deselected." else null)
                                .setPositiveButton("Remove") { _, _ ->
                                    ProjectManager.removeProject(context, project)
                                    if (isActive) {
                                        ProjectManager.setActiveProject(context, null)
                                    }
                                    rebuildList()
                                    updateRemoveAllVisibility()
                                    if (ProjectManager.getProjects(context).isEmpty()) {
                                        dialog.dismiss()
                                        showCreateDialog(context, onProjectSelected)
                                    }
                                }
                                .setNegativeButton("Cancel", null)
                                .show()
                        }
                    }
                    row.addView(deleteBtn)

                    layout.addView(row)
                }
            }
        }

        rebuildList()
        dialog.show()
        updateRemoveAllVisibility()
    }

    private const val MAX_PROJECT_NAME_LENGTH = 64

    // Allow only characters safe on all major filesystems
    // (NTFS, ext2/3/4, FAT32, exFAT, ZFS, Btrfs, APFS, HFS+)
    private val SAFE_CHAR_FILTER = InputFilter { source, start, end, _, _, _ ->
        val filtered = StringBuilder()
        for (i in start until end) {
            val c = source[i]
            if (c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '-' || c == '_' || c == ' ' || c == '.') {
                filtered.append(c)
            }
        }
        if (filtered.length == end - start) null else filtered.toString()
    }

    private fun sanitizeProjectName(name: String): String {
        return name.trim()
            .trimStart('.')  // No leading dots (hidden files on Unix)
            .trimEnd('.')    // No trailing dots (Windows issue)
            .trim()
            .take(MAX_PROJECT_NAME_LENGTH)
    }

    private fun showCreateDialog(context: Context, onProjectSelected: (String) -> Unit) {
        val dp = { px: Int -> (px * context.resources.displayMetrics.density).toInt() }
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(16), dp(24), dp(8))
        }
        val input = EditText(context).apply {
            hint = "Project number"
            setPadding(dp(12), dp(12), dp(12), dp(12))
            filters = arrayOf(SAFE_CHAR_FILTER, InputFilter.LengthFilter(MAX_PROJECT_NAME_LENGTH))
        }
        container.addView(input)

        val createDialog = AlertDialog.Builder(context)
            .setTitle("New project")
            .setMessage("Start with the project number, and a brief label you'll recognize on the server from your computer.\n\ne.g. 32672 ProjectName or 26CA123 ProjectName\n\nLetters, numbers, dashes, underscores, spaces, and dots only.")
            .setView(container)
            .setPositiveButton("Create") { _, _ ->
                val name = sanitizeProjectName(input.text.toString())
                if (name.isNotEmpty()) {
                    ProjectManager.addProject(context, name)
                    onProjectSelected(name)
                }
            }
            .setNeutralButton("Cancel", null)
            .create()

        createDialog.setOnShowListener {
            createDialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(0xFF81C784.toInt())
            createDialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setTextColor(0xFFFF6B6B.toInt())
        }
        createDialog.show()
    }
}
