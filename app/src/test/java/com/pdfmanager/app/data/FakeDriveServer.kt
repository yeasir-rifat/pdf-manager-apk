package com.pdfmanager.app.data

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/** What the fake server received. Header names are lower-case. */
class FakeReq(
    val method: String, val path: String, val query: String?,
    val headers: Map<String, String>, val body: ByteArray,
)

class FakeReply(val code: Int, val body: String = "", val headers: Map<String, String> = emptyMap())

/**
 * A small local HTTP server that stands in for Google Drive in unit tests.
 * It uses only java.net sockets (not the JDK's com.sun.net.httpserver, which the Kotlin test compile cannot see).
 */
class FakeDriveServer(private val respond: (FakeReq) -> FakeReply) {
    val requests = java.util.Collections.synchronizedList(ArrayList<FakeReq>())
    private val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())

    val base: String get() = "http://127.0.0.1:${server.localPort}"

    val endpoints: DriveEndpoints get() = DriveEndpoints("$base/drive/v3", "$base/upload/drive/v3")

    init {
        Thread {
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (e: Exception) { break }
                Thread { serve(socket) }.apply { isDaemon = true }.start()
            }
        }.apply { isDaemon = true }.start()
    }

    private fun readLine(input: InputStream): String? {
        val out = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (out.size() == 0) null else out.toString("ISO-8859-1")
            if (b == '\n'.code) break
            if (b != '\r'.code) out.write(b)
        }
        return out.toString("ISO-8859-1")
    }

    private fun readBody(input: InputStream, headers: Map<String, String>): ByteArray {
        val te = headers["transfer-encoding"]?.lowercase()
        if (te != null && te.contains("chunked")) {
            val out = ByteArrayOutputStream()
            while (true) {
                val line = readLine(input) ?: break
                val size = line.substringBefore(';').trim().toIntOrNull(16) ?: break
                if (size == 0) { while (true) { val t = readLine(input); if (t == null || t.isEmpty()) break }; break }
                val buf = ByteArray(size)
                var got = 0
                while (got < size) { val n = input.read(buf, got, size - got); if (n < 0) break; got += n }
                out.write(buf, 0, got)
                readLine(input)
            }
            return out.toByteArray()
        }
        val len = headers["content-length"]?.trim()?.toIntOrNull() ?: 0
        val buf = ByteArray(len)
        var got = 0
        while (got < len) { val n = input.read(buf, got, len - got); if (n < 0) break; got += n }
        return buf
    }

    private fun serve(socket: Socket) {
        socket.use { s ->
            try {
                val input = s.getInputStream().buffered()
                val requestLine = readLine(input) ?: return
                val parts = requestLine.split(' ')
                if (parts.size < 2) return
                val headers = LinkedHashMap<String, String>()
                while (true) {
                    val line = readLine(input) ?: break
                    if (line.isEmpty()) break
                    val i = line.indexOf(':')
                    if (i > 0) {
                        val k = line.substring(0, i).trim().lowercase()
                        val v = line.substring(i + 1).trim()
                        headers[k] = if (headers.containsKey(k)) headers[k] + "," + v else v
                    }
                }
                val body = readBody(input, headers)
                val target = parts[1]
                val rawPath = target.substringBefore('?')
                val query = if (target.contains('?')) target.substringAfter('?') else null
                val path = try { java.net.URI(rawPath).path ?: rawPath } catch (e: Exception) { rawPath }
                val req = FakeReq(parts[0], path, query, headers, body)
                requests += req
                val reply = respond(req)
                val bytes = reply.body.toByteArray(Charsets.UTF_8)
                val head = StringBuilder("HTTP/1.1 ${reply.code} OK\r\n")
                reply.headers.forEach { (k, v) -> head.append(k).append(": ").append(v).append("\r\n") }
                head.append("Content-Length: ").append(bytes.size).append("\r\n")
                head.append("Connection: close\r\n\r\n")
                val out = s.getOutputStream()
                out.write(head.toString().toByteArray(Charsets.ISO_8859_1))
                out.write(bytes)
                out.flush()
            } catch (e: Exception) {
                // a client that hangs up early is not a test failure
            }
        }
    }

    fun stop() { try { server.close() } catch (e: Exception) { } }
}

/** A client for tests: fixed token, waits are recorded instead of slept, no random spread. */
class TestClientParts {
    val sleeps = ArrayList<Long>()
    val refreshCalls = ArrayList<Boolean>()

    fun client(server: FakeDriveServer, refreshedToken: String = "tok2") = DriveClient(
        tokens = DriveTokens { force -> refreshCalls += force; if (force) refreshedToken else "tok1" },
        endpoints = server.endpoints,
        sleeper = { sleeps += it },
        jitter = { 0.0 },
    )
}
