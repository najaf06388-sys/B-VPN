package com.bubble.vpn

import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.RandomAccessFile
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * A small read-only FTP server. Your PC can browse and copy files, nothing can be changed or deleted.
 */
class FtpServer(private val root: File, private val port: Int) {

    @Volatile private var stopped = false
    @Volatile private var serverSocket: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool()
    private val clients: MutableSet<Socket> = Collections.synchronizedSet(HashSet<Socket>())

    /** onResult(null) = started fine, onResult("text") = failed. */
    fun start(onResult: (String?) -> Unit) {
        Thread {
            try {
                val ss = ServerSocket()
                ss.reuseAddress = true
                ss.bind(InetSocketAddress(port))
                if (stopped) {
                    ss.close()
                    return@Thread
                }
                serverSocket = ss
                onResult(null)
                while (!stopped) {
                    val c = ss.accept()
                    pool.execute { Session(c).run() }
                }
            } catch (e: Exception) {
                if (!stopped) onResult(e.message ?: e.toString())
            }
        }.start()
    }

    fun stop() {
        stopped = true
        try {
            serverSocket?.close()
        } catch (e: Exception) {
        }
        val copy: List<Socket> = synchronized(clients) { clients.toList() }
        for (c in copy) {
            try {
                c.close()
            } catch (e: Exception) {
            }
        }
        pool.shutdownNow()
    }

    private inner class Session(private val ctrl: Socket) {
        private var cwd = "/"
        private var pasv: ServerSocket? = null
        private var activeHost: String? = null
        private var activePort = 0
        private var restOffset = 0L
        private val reader = BufferedReader(InputStreamReader(ctrl.getInputStream(), Charsets.UTF_8))
        private val ctrlOut = ctrl.getOutputStream()

        fun run() {
            try {
                clients.add(ctrl)
                ctrl.soTimeout = 10 * 60 * 1000
                send("220 BubbleVPN ready")
                while (!stopped) {
                    val line = reader.readLine() ?: break
                    if (line.isBlank()) continue
                    val sp = line.indexOf(' ')
                    val cmd = (if (sp < 0) line else line.substring(0, sp)).uppercase(Locale.ROOT)
                    val arg = if (sp < 0) "" else line.substring(sp + 1).trim()
                    if (!handle(cmd, arg)) break
                }
            } catch (e: Exception) {
                // client left or timed out
            } finally {
                closePasv()
                try {
                    ctrl.close()
                } catch (e: Exception) {
                }
                clients.remove(ctrl)
            }
        }

        private fun send(text: String) {
            ctrlOut.write((text + "\r\n").toByteArray(Charsets.UTF_8))
            ctrlOut.flush()
        }

        private fun handle(cmd: String, arg: String): Boolean {
            when (cmd) {
                "USER" -> send("331 Any password is fine")
                "PASS" -> send("230 Logged in")
                "SYST" -> send("215 UNIX Type: L8")
                "FEAT" -> send("211-Features:\r\n UTF8\r\n SIZE\r\n MDTM\r\n REST STREAM\r\n EPSV\r\n211 End")
                "OPTS" -> send("200 OK")
                "NOOP" -> send("200 OK")
                "TYPE", "MODE", "STRU" -> send("200 OK")
                "PWD", "XPWD" -> send("257 \"$cwd\" is current directory")
                "CWD", "XCWD" -> {
                    val v = virtualPath(arg)
                    val f = toFile(v)
                    if (f.isDirectory && f.canRead()) {
                        cwd = v
                        send("250 Directory changed")
                    } else {
                        send("550 No such directory")
                    }
                }
                "CDUP", "XCUP" -> {
                    cwd = virtualPath("..")
                    send("250 Directory changed")
                }
                "PASV" -> doPasv()
                "EPSV" -> {
                    if (arg.equals("ALL", ignoreCase = true)) {
                        send("200 OK")
                    } else {
                        val ss = openPasv()
                        if (ss == null) send("425 Cannot open passive port")
                        else send("229 Entering Extended Passive Mode (|||${ss.localPort}|)")
                    }
                }
                "PORT" -> {
                    try {
                        val p = arg.split(",").map { it.trim().toInt() }
                        closePasv()
                        activeHost = "${p[0]}.${p[1]}.${p[2]}.${p[3]}"
                        activePort = p[4] * 256 + p[5]
                        send("200 OK")
                    } catch (e: Exception) {
                        send("501 Bad PORT")
                    }
                }
                "LIST" -> doList(arg, false)
                "NLST" -> doList(arg, true)
                "RETR" -> doRetr(arg)
                "REST" -> {
                    restOffset = arg.toLongOrNull() ?: 0L
                    send("350 Restarting at $restOffset")
                }
                "SIZE" -> {
                    val f = toFile(virtualPath(arg))
                    if (f.isFile) send("213 ${f.length()}") else send("550 Not a file")
                }
                "MDTM" -> {
                    val f = toFile(virtualPath(arg))
                    if (f.exists()) {
                        val fmt = SimpleDateFormat("yyyyMMddHHmmss", Locale.US)
                        fmt.timeZone = TimeZone.getTimeZone("UTC")
                        send("213 ${fmt.format(Date(f.lastModified()))}")
                    } else {
                        send("550 Not found")
                    }
                }
                "STOR", "STOU", "APPE", "DELE", "MKD", "XMKD", "RMD", "XRMD", "RNFR", "RNTO" ->
                    send("550 Read-only server")
                "QUIT" -> {
                    send("221 Goodbye")
                    return false
                }
                else -> send("502 Command not implemented")
            }
            return true
        }

        // ---------- paths ----------

        private fun virtualPath(arg: String): String {
            val base = when {
                arg.startsWith("/") -> arg
                cwd == "/" -> "/$arg"
                else -> "$cwd/$arg"
            }
            val parts = ArrayList<String>()
            for (p in base.split("/")) {
                when (p) {
                    "", "." -> {}
                    ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
                    else -> parts.add(p)
                }
            }
            return "/" + parts.joinToString("/")
        }

        private fun toFile(v: String): File =
            if (v == "/") root else File(root, v.substring(1))

        // ---------- data connection ----------

        private fun closePasv() {
            try {
                pasv?.close()
            } catch (e: Exception) {
            }
            pasv = null
        }

        private fun openPasv(): ServerSocket? {
            closePasv()
            activeHost = null
            return try {
                val ss = ServerSocket(0)
                pasv = ss
                ss
            } catch (e: Exception) {
                null
            }
        }

        private fun doPasv() {
            val ss = openPasv()
            if (ss == null) {
                send("425 Cannot open passive port")
                return
            }
            val raw = ctrl.localAddress.address
            val ip = if (raw.size == 4) raw else byteArrayOf(127, 0, 0, 1)
            val p = ss.localPort
            send(
                "227 Entering Passive Mode (" +
                    "${ip[0].toInt() and 255},${ip[1].toInt() and 255}," +
                    "${ip[2].toInt() and 255},${ip[3].toInt() and 255}," +
                    "${p shr 8},${p and 255})"
            )
        }

        private fun openData(): Socket? {
            try {
                val p = pasv
                if (p != null) {
                    p.soTimeout = 20000
                    return p.accept()
                }
                val h = activeHost
                if (h != null) {
                    val s = Socket()
                    s.connect(InetSocketAddress(h, activePort), 10000)
                    return s
                }
                return null
            } catch (e: Exception) {
                return null
            } finally {
                closePasv()
            }
        }

        // ---------- LIST / RETR ----------

        private fun listLine(f: File): String {
            val perms = if (f.isDirectory) "dr-xr-xr-x" else "-r--r--r--"
            val size = if (f.isDirectory) 0L else f.length()
            val recent = abs(System.currentTimeMillis() - f.lastModified()) < 180L * 24 * 3600 * 1000
            val fmt = SimpleDateFormat(if (recent) "MMM dd HH:mm" else "MMM dd  yyyy", Locale.US)
            return String.format(
                Locale.US, "%s 1 owner group %12d %s %s",
                perms, size, fmt.format(Date(f.lastModified())), f.name
            )
        }

        private fun doList(argIn: String, namesOnly: Boolean) {
            var a = argIn
            while (a.startsWith("-")) {
                val i = a.indexOf(' ')
                a = if (i < 0) "" else a.substring(i + 1).trim()
            }
            val f = if (a.isEmpty()) toFile(cwd) else toFile(virtualPath(a))
            if (!f.exists()) {
                send("550 No such file or directory")
                return
            }
            send("150 Opening data connection")
            val d = openData()
            if (d == null) {
                send("425 Cannot open data connection")
                return
            }
            try {
                val w = BufferedWriter(OutputStreamWriter(d.getOutputStream(), Charsets.UTF_8))
                val items: List<File> =
                    if (f.isDirectory) {
                        (f.listFiles() ?: emptyArray<File>())
                            .sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase(Locale.ROOT) }))
                    } else {
                        listOf(f)
                    }
                for (x in items) {
                    w.write(if (namesOnly) x.name else listLine(x))
                    w.write("\r\n")
                }
                w.flush()
                d.close()
                send("226 Transfer complete")
            } catch (e: IOException) {
                try {
                    d.close()
                } catch (e2: Exception) {
                }
                send("426 Transfer aborted")
            }
        }

        private fun doRetr(arg: String) {
            val f = toFile(virtualPath(arg))
            if (!f.isFile || !f.canRead()) {
                send("550 File not available")
                return
            }
            send("150 Opening data connection for ${f.name}")
            val d = openData()
            if (d == null) {
                send("425 Cannot open data connection")
                return
            }
            try {
                RandomAccessFile(f, "r").use { raf ->
                    raf.seek(restOffset)
                    val os = d.getOutputStream()
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = raf.read(buf)
                        if (n < 0) break
                        os.write(buf, 0, n)
                    }
                    os.flush()
                }
                d.close()
                send("226 Transfer complete")
            } catch (e: IOException) {
                try {
                    d.close()
                } catch (e2: Exception) {
                }
                send("426 Transfer aborted")
            } finally {
                restOffset = 0L
            }
        }
    }
}
