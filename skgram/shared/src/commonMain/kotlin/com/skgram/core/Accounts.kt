package com.skgram.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File

/** Version info filled in by each app at startup. */
object AppInfo {
    var version = ""
}

/** All accounts on this device; each runs its own TDLib instance, so all of them receive messages. */
object Accounts {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _list = MutableStateFlow<List<Account>>(emptyList())
    val list: StateFlow<List<Account>> = _list
    private val _current = MutableStateFlow<Account?>(null)
    val current: StateFlow<Account?> = _current
    @Volatile private var started = false

    fun start() {
        if (started) return
        started = true
        val ids = Prefs.accountIds.ifEmpty { listOf(0) }
        val accounts = ids.map(::create)
        _list.value = accounts
        _current.value = accounts.firstOrNull { it.id == Prefs.currentAccountId } ?: accounts.first()
        accounts.forEach { it.start() }
        save()
    }

    private fun create(id: Int): Account {
        val base = Platform.dataDir
        // Version 1.0 kept its single account directly in the data folder.
        val legacy = File(base, "td")
        val (db, files) = if (id == 0 && legacy.exists()) legacy.path to File(base, "td_files").path
        else File(base, "accounts/$id/db").path to File(base, "accounts/$id/files").path
        return Account(id, db, files, ::onClosed)
    }

    /** Starts signing in another account and switches to it. */
    fun add(): Account {
        val a = create((_list.value.maxOfOrNull { it.id } ?: -1) + 1)
        _list.value = _list.value + a
        save()
        a.start()
        switch(a)
        return a
    }

    fun switch(a: Account) {
        _current.value = a
        Prefs.currentAccountId = a.id
        a.setOnline(AppState.foreground)
    }

    /** Abandons an account that hasn't finished signing in. */
    fun cancel(a: Account) {
        scope.launch { a.client.destroy() }
    }

    /** Other accounts that are fully signed in. */
    fun othersReady(a: Account) = _list.value.filter { it !== a && it.isReady }

    private fun onClosed(a: Account) {
        val rest = _list.value.filter { it !== a }
        val dir = File(Platform.dataDir, "accounts/${a.id}")
        if (dir.exists()) dir.deleteRecursively()
        if (rest.isEmpty()) {
            // Never leave the app without an account: start a fresh sign-in.
            val fresh = create(a.id)
            _list.value = listOf(fresh)
            fresh.start()
            switch(fresh)
        } else {
            _list.value = rest
            if (_current.value === a) switch(rest.firstOrNull { it.isReady } ?: rest.first())
        }
        save()
    }

    private fun save() {
        Prefs.accountIds = _list.value.map { it.id }
    }

    internal fun authChanged() {
        Platform.updateBackgroundMode(Prefs.background.value && _list.value.any { it.isReady })
    }

    fun setForeground(foreground: Boolean) {
        AppState.foreground = foreground
        _list.value.forEach { it.setOnline(foreground) }
    }
}
