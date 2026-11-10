package com.arkheia.app

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.mutableStateOf

enum class Sort(val title: String) { Name("Name"), Date("Newest"), Size("Largest"), Type("Type") }

/** App settings, backed by SharedPreferences and observable from Compose. */
object Prefs {
    private lateinit var sp: SharedPreferences

    private val _onboarded = mutableStateOf(false)
    private val _lastSeen = mutableStateOf(0)
    private val _showHidden = mutableStateOf(false)
    private val _foldersFirst = mutableStateOf(true)
    private val _sort = mutableStateOf(Sort.Name)
    private val _pins = mutableStateOf<List<String>>(emptyList())
    private val _recent = mutableStateOf<List<String>>(emptyList())

    fun init(c: Context) {
        if (::sp.isInitialized) return
        sp = c.applicationContext.getSharedPreferences("arkheia", Context.MODE_PRIVATE)
        _onboarded.value = sp.getBoolean("onboarded", false)
        _lastSeen.value = sp.getInt("lastSeen", 0)
        _showHidden.value = sp.getBoolean("hidden", false)
        _foldersFirst.value = sp.getBoolean("foldersFirst", true)
        _sort.value = runCatching { Sort.valueOf(sp.getString("sort", null) ?: "Name") }.getOrDefault(Sort.Name)
        _pins.value = sp.getString("pins", "").orEmpty().lines().filter { it.isNotBlank() }
        _recent.value = sp.getString("recent", "").orEmpty().lines().filter { it.isNotBlank() }
    }

    var onboarded: Boolean
        get() = _onboarded.value
        set(v) { _onboarded.value = v; sp.edit().putBoolean("onboarded", v).apply() }

    var lastSeenVersion: Int
        get() = _lastSeen.value
        set(v) { _lastSeen.value = v; sp.edit().putInt("lastSeen", v).apply() }

    var showHidden: Boolean
        get() = _showHidden.value
        set(v) { _showHidden.value = v; sp.edit().putBoolean("hidden", v).apply() }

    var foldersFirst: Boolean
        get() = _foldersFirst.value
        set(v) { _foldersFirst.value = v; sp.edit().putBoolean("foldersFirst", v).apply() }

    var sort: Sort
        get() = _sort.value
        set(v) { _sort.value = v; sp.edit().putString("sort", v.name).apply() }

    var pins: List<String>
        get() = _pins.value
        private set(v) { _pins.value = v; sp.edit().putString("pins", v.joinToString("\n")).apply() }

    var recent: List<String>
        get() = _recent.value
        private set(v) { _recent.value = v; sp.edit().putString("recent", v.joinToString("\n")).apply() }

    fun isPinned(path: String) = path in pins
    fun togglePin(path: String) { pins = if (path in pins) pins - path else pins + path }
    fun addRecent(path: String) { recent = (listOf(path) + recent.filter { it != path }).take(8) }
    fun clearRecent() { recent = emptyList() }

    /** Keep pins and recents pointing at the right place after a rename, move or delete. */
    fun movePath(from: String, to: String?) {
        fun fix(p: String): String? = when {
            p == from -> to
            p.startsWith("$from/") -> to?.let { it + p.removePrefix(from) }
            else -> p
        }
        pins = pins.mapNotNull(::fix).distinct()
        recent = recent.mapNotNull(::fix).distinct()
    }
}
