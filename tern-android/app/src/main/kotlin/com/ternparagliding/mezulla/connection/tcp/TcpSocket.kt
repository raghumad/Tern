package com.ternparagliding.mezulla.connection.tcp

import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Tiny seam over a TCP socket so tests can substitute a fake without
 * standing up a real listener.
 *
 * The interface is intentionally byte-level — same shape as
 * `java.net.Socket`'s streams. The framing logic in
 * [TcpMeshtasticConnection] runs against this seam and is therefore
 * testable end-to-end against in-memory bytes.
 *
 * "Connection refused", "host unreachable" and similar are all signalled
 * by [open] throwing — the caller turns those into a `LinkState.DOWN`
 * event and never lets them escape the event flow.
 */
interface TcpSocketFactory {
    @Throws(IOException::class)
    fun open(host: String, port: Int): TcpSocketHandle
}

/**
 * One open TCP connection. Read until EOF or until [close] is called.
 *
 * - [read] returns the number of bytes read, or -1 on clean EOF.
 *   Implementations may throw [IOException] on socket error; the
 *   connection layer catches and demotes to a link-state-DOWN event.
 * - [write] writes the whole array; throws on failure.
 * - [close] is idempotent.
 */
interface TcpSocketHandle {
    @Throws(IOException::class)
    fun read(buf: ByteArray): Int

    @Throws(IOException::class)
    fun write(bytes: ByteArray)

    fun close()
}

/**
 * Production factory that opens a real `java.net.Socket`.
 *
 * `connectTimeoutMillis` is the TCP-level connect timeout — how long we
 * wait for a SYN/ACK. Past that we give up and the connection layer
 * surfaces `LinkState.DOWN`. This matters specifically for the
 * board-on-WiFi case: if the user's phone is on a different network than
 * mezulla, we should fail fast rather than block the UI thread.
 */
class RealTcpSocketFactory(
    private val connectTimeoutMillis: Int = 3_000,
) : TcpSocketFactory {

    override fun open(host: String, port: Int): TcpSocketHandle {
        val sock = Socket()
        sock.tcpNoDelay = true
        // Meshtastic packets are small and bursty (a position broadcast
        // every few seconds); we don't want Nagle delaying them.
        sock.connect(InetSocketAddress(host, port), connectTimeoutMillis)
        return RealTcpSocketHandle(sock)
    }
}

private class RealTcpSocketHandle(private val sock: Socket) : TcpSocketHandle {
    private val input = sock.getInputStream()
    private val output = sock.getOutputStream()

    override fun read(buf: ByteArray): Int = input.read(buf)

    override fun write(bytes: ByteArray) {
        output.write(bytes)
        output.flush()
    }

    override fun close() {
        try { sock.close() } catch (_: IOException) { /* idempotent */ }
    }
}
