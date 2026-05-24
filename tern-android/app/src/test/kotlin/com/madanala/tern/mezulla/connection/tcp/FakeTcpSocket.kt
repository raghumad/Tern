package com.madanala.tern.mezulla.connection.tcp

import java.io.IOException
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * In-memory TCP socket adapter for tests.
 *
 * A test owns:
 *  - the [TcpSocketFactory] (it hands it to [TcpMeshtasticConnection]),
 *  - the matching [FakeTcpSocketHandle] (it pushes bytes "from the board"
 *    via [feedFromBoard] and reads bytes "to the board" via [drainToBoard]).
 *
 * The "board side" sees a streaming byte channel — same shape as a real
 * socket. Bytes pushed by [feedFromBoard] become available to the
 * connection's read loop. Bytes the connection writes are captured for
 * the test to assert against.
 *
 * Concurrency: read() blocks on a piped input stream until either
 * bytes are available or the handle is closed (returning -1 to signal
 * EOF). This matches real socket semantics, which is the property the
 * read loop is built against.
 */
internal class FakeTcpSocketFactory : TcpSocketFactory {
    private val pendingHandles = ArrayDeque<FakeTcpSocketHandle>()
    private val refused = ArrayDeque<Boolean>()
    private val opens = mutableListOf<String>()

    val openHosts: List<String> get() = opens.toList()

    fun enqueueHandle(handle: FakeTcpSocketHandle) {
        pendingHandles.addLast(handle)
        refused.addLast(false)
    }

    fun enqueueConnectionRefused() {
        refused.addLast(true)
        pendingHandles.addLast(FakeTcpSocketHandle()) // placeholder, never used
    }

    override fun open(host: String, port: Int): TcpSocketHandle {
        opens.add("$host:$port")
        check(refused.isNotEmpty()) { "no enqueued result for open($host:$port)" }
        val refuse = refused.removeFirst()
        val handle = pendingHandles.removeFirst()
        if (refuse) throw IOException("connection refused (fake)")
        return handle
    }
}

internal class FakeTcpSocketHandle(
    /**
     * If non-null, [read] throws this on the first call. Used to test the
     * "read fails immediately after connect" path.
     */
    private val readThrowsOnFirstCall: IOException? = null,
) : TcpSocketHandle {
    private val toClient = PipedOutputStream()
    private val clientInput = PipedInputStream(toClient, 16 * 1024)
    private val capturedWrites = mutableListOf<ByteArray>()
    private val closed = AtomicBoolean(false)
    @Volatile private var firstReadDone = false

    /** Push bytes into the client read stream as if the board sent them. */
    fun feedFromBoard(bytes: ByteArray) {
        toClient.write(bytes)
        toClient.flush()
    }

    /** Close just the board-side end so the client read returns -1 (EOF). */
    fun simulateBoardDisconnect() {
        toClient.close()
    }

    /**
     * Bytes the connection wrote to us, in write order. Drains the
     * list — subsequent calls only see writes that happened after
     * the last drain.
     */
    fun drainToBoard(): List<ByteArray> {
        val snapshot = capturedWrites.toList()
        capturedWrites.clear()
        return snapshot
    }

    override fun read(buf: ByteArray): Int {
        if (closed.get()) return -1
        if (!firstReadDone && readThrowsOnFirstCall != null) {
            firstReadDone = true
            throw readThrowsOnFirstCall
        }
        firstReadDone = true
        return try {
            clientInput.read(buf)
        } catch (e: IOException) {
            if (closed.get()) -1 else throw e
        }
    }

    override fun write(bytes: ByteArray) {
        if (closed.get()) throw IOException("socket closed")
        capturedWrites.add(bytes.copyOf())
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            try { toClient.close() } catch (_: IOException) {}
            try { clientInput.close() } catch (_: IOException) {}
        }
    }
}
