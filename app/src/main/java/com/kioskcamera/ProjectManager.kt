package com.kioskcamera

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray

object ProjectManager {

    private const val PREFS_NAME = "projects"
    private const val KEY_PROJECTS = "project_list"
    private const val KEY_ACTIVE = "active_project"

    private fun prefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun getProjects(context: Context): List<String> {
        val json = prefs(context).getString(KEY_PROJECTS, "[]") ?: "[]"
        val arr = JSONArray(json)
        val list = mutableListOf<String>()
        for (i in 0 until arr.length()) {
            list.add(arr.getString(i))
        }
        return list.sortedWith(String.CASE_INSENSITIVE_ORDER)
    }

    fun addProject(context: Context, name: String) {
        val projects = getProjects(context).toMutableList()
        val trimmed = name.trim()
        // Case-insensitive dedup — keep the casing of the new entry
        val existing = projects.find { it.equals(trimmed, ignoreCase = true) }
        if (trimmed.isNotEmpty() && existing == null) {
            projects.add(trimmed)
            save(context, projects)
        }
    }

    fun removeProject(context: Context, name: String) {
        val projects = getProjects(context).toMutableList()
        projects.remove(name)
        save(context, projects)
    }

    fun getActiveProject(context: Context): String? {
        return prefs(context).getString(KEY_ACTIVE, null)
    }

    fun setActiveProject(context: Context, name: String?) {
        prefs(context).edit().apply {
            if (name != null) putString(KEY_ACTIVE, name)
            else remove(KEY_ACTIVE)
        }.apply()
    }

    private fun save(context: Context, projects: List<String>) {
        val arr = JSONArray()
        projects.forEach { arr.put(it) }
        prefs(context).edit().putString(KEY_PROJECTS, arr.toString()).apply()
    }
}
