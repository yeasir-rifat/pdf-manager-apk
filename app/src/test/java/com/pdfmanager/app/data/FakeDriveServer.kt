package com.pdfmanager.app.data

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress

/** What the fake server received. Header names are lower-case. */
class FakeReq(
    val method: String, val path: String, val query: String?,
    val headers: Map<String, String>, val body: ByteArray,
)

class FakeReply(val code: Int, val body: String = "", val headers: Map<String, String> = emptyMap())

/** A small local HTTP server (the JDK's own) that stands in for Google Drive in unit tests. */
class FakeDriveServer(private val respond: (FakeReq) -> FakeReply) {
    val requests = java.util.Collections.synchronizedList(ArrayList<FakeReq>())
    private val server: HttpServer = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)

    val base: String get() = "http://127.0.0.1:${server.address.port}"

    val endpoints: DriveEndpoints get() = DriveEndpoints("$base/drive/v3", "$base/upload/drive/v3")

    init {
        server.createContext("/", HttpHandler { ex -> handle(ex) })
        server.start()
    }

    private fun handle(ex: HttpExchange) {
        val req = FakeReq(
            ex.requestMethod, ex.requestURI.path, ex.requestURI.rawQuery,
            ex.requestHeaders.entries.associate { it.key.lowercase() to it.value.joinToString(",") },
            ex.requestBody.readBytes(),
        )
        requests += req
        val reply = respond(req)
        reply.headers.forEach { (k, v) -> ex.responseHeaders.add(k, v) }
        val bytes = reply.body.toByteArray(Charsets.UTF_8)
        if (bytes.isEmpty()) {
            ex.sendResponseHeaders(reply.code, -1)
        } else {
            ex.sendResponseHeaders(reply.code, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        ex.close()
    }

    fun stop() = server.stop(0)
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
