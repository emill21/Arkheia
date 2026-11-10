package com.arkheia.app.adb

import android.content.Context
import android.os.Build
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import io.github.muntashirakon.adb.AdbStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.PrivateKey
import java.security.cert.Certificate
import java.util.concurrent.TimeUnit

private class Manager(
    private val key: PrivateKey,
    private val cert: Certificate,
) : AbsAdbConnectionManager() {
    init {
        api = Build.VERSION_CODES.R
        setTimeout(20, TimeUnit.SECONDS)
        isThrowOnUnauthorised = false
    }

    override fun getPrivateKey(): PrivateKey = key
    override fun getCertificate(): Certificate = cert
    override fun getDeviceName(): String = "Arkheia"
}

/**
 * ADB to *this* watch, over its own Wireless debugging on 127.0.0.1. Arkheia only uses it to
 * run the one command that turns on its All files access.
 */
object SelfAdb {
    const val HOST = "127.0.0.1"
    private var manager: Manager? = null

    @Synchronized
    private fun manager(c: Context): Manager {
        manager?.let { return it }
        val (key, cert) = Keys.load(c)
        return Manager(key, cert).also { manager = it }
    }

    /** Pair with the six-digit code from Wireless debugging › Pair new device. */
    suspend fun pair(c: Context, port: Int, code: String) = withContext(Dispatchers.IO) {
        manager(c).pair(HOST, port, code.filter { it.isDigit() })
        Unit
    }

    /** True once connected. Throws if the watch refuses (e.g. not paired yet). */
    suspend fun connect(c: Context, port: Int): Boolean = withContext(Dispatchers.IO) {
        val m = manager(c)
        runCatching { m.disconnect() }
        m.connect(HOST, port) || m.isConnected
    }

    /** Run a shell command and return what it printed. */
    suspend fun sh(command: String): String = withContext(Dispatchers.IO) {
        val m = manager ?: throw IOException("Not connected")
        val s = m.openStream("exec:$command 2>&1")
        try {
            String(s.readAll(), Charsets.UTF_8)
        } finally {
            runCatching { s.close() }
        }
    }

    suspend fun disconnect() = withContext(Dispatchers.IO) {
        runCatching { manager?.disconnect() }
        Unit
    }

    private fun AdbStream.readAll(): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (true) {
            val n = try {
                read(buf, 0, buf.size)
            } catch (e: IOException) {
                if (isClosed) -1 else throw e
            }
            if (n < 0) break
            if (n > 0) out.write(buf, 0, n)
        }
        return out.toByteArray()
    }
}
