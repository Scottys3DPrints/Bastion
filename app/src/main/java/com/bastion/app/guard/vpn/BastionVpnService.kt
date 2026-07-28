package com.bastion.app.guard.vpn

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import com.bastion.app.BastionApp
import com.bastion.app.MainActivity
import com.bastion.app.R
import com.bastion.app.data.BastionGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The content filter.
 *
 * A local VPN in the narrowest possible sense: the tunnel advertises a synthetic
 * resolver and routes *only that single address*, so the only packets that ever
 * enter Bastion are DNS queries. Web traffic, app traffic, banking, messages —
 * none of it is routed here, none of it is inspected, and none of it could be.
 *
 * Honest limits, stated in the app as well as here:
 *   - apps using DNS-over-HTTPS or DNS-over-TLS bypass this entirely;
 *   - it filters by domain, so it cannot police individual pages on a mixed site;
 *   - IPv6 resolvers are not intercepted in this version.
 * It is one layer. The Guard service and the filtered browser are the others.
 */
class BastionVpnService : VpnService() {

    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.IO)
    private val forwarders = Executors.newFixedThreadPool(6)

    private var tunnel: ParcelFileDescriptor? = null
    @Volatile private var filter: DomainFilter = DomainFilter.PERMISSIVE
    @Volatile private var upstream: InetAddress = InetAddress.getByName(DEFAULT_UPSTREAM)
    @Volatile private var shouldRun = false

    private var output: FileOutputStream? = null
    private val writeLock = Any()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            teardown()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, buildNotification())

        scope.launch {
            val graph = BastionGraph.from(this@BastionVpnService)
            graph.guard.seedIfEmpty()
            val data = graph.guard.filterData()
            filter = DomainFilter(data.blocked, data.allowed, data.keywords)
            upstream = runCatching {
                InetAddress.getByName(graph.settings.current().upstreamDns)
            }.getOrElse { InetAddress.getByName(DEFAULT_UPSTREAM) }

            if (establish()) runLoop()
        }
        return START_STICKY
    }

    private fun establish(): Boolean {
        return runCatching {
            val builder = Builder()
                .setSession("Bastion content filter")
                .addAddress(TUN_ADDRESS, 32)
                .addDnsServer(FAKE_DNS)
                // The single most important line in this file: route only the
                // synthetic resolver. Everything else on the device is untouched.
                .addRoute(FAKE_DNS, 32)
                .setBlocking(true)
                .setMtu(1500)

            runCatching { builder.addDisallowedApplication(packageName) }

            tunnel = builder.establish()
            output = tunnel?.let { FileOutputStream(it.fileDescriptor) }
            shouldRun = tunnel != null
            running.value = shouldRun
            shouldRun
        }.getOrElse {
            Log.e(TAG, "Could not establish the filter tunnel", it)
            running.value = false
            false
        }
    }

    private fun runLoop() {
        val descriptor = tunnel ?: return
        val input = FileInputStream(descriptor.fileDescriptor)
        val buffer = ByteArray(32_767)

        // A persistent read failure used to spin this loop at 100% CPU, because
        // `continue` retried immediately forever. Back off, then give up rather
        // than cook the battery behind a filter that is no longer working.
        var consecutiveFailures = 0

        while (shouldRun) {
            val length = runCatching { input.read(buffer) }.getOrElse { -1 }
            if (length <= 0) {
                if (!shouldRun) break
                consecutiveFailures++
                if (consecutiveFailures >= MAX_READ_FAILURES) {
                    Log.w(TAG, "Tunnel read failed $consecutiveFailures times; shutting the filter down")
                    teardown()
                    break
                }
                runCatching { Thread.sleep(READ_BACKOFF_MS * consecutiveFailures) }
                continue
            }
            consecutiveFailures = 0
            val packet = buffer.copyOf(length)
            val parsed = DnsPacket.parse(packet, length) ?: continue
            handleQuery(packet, parsed)
        }
    }

    private fun handleQuery(packet: ByteArray, parsed: DnsPacket.Parsed) {
        val hostname = DnsPacket.questionName(packet, parsed.payloadOffset, parsed.payloadLength)

        if (hostname != null && filter.isBlocked(hostname)) {
            blockedCount.value += 1
            lastBlocked.value = hostname
            val nx = DnsPacket.buildNxDomain(packet, parsed.payloadOffset, parsed.payloadLength)
            write(DnsPacket.buildResponsePacket(parsed, nx))
            return
        }
        forward(packet, parsed)
    }

    /** Anything not blocked goes to the real resolver, off-tunnel via protect(). */
    private fun forward(packet: ByteArray, parsed: DnsPacket.Parsed) {
        forwarders.execute {
            runCatching {
                DatagramSocket().use { socket ->
                    protect(socket)
                    socket.soTimeout = UPSTREAM_TIMEOUT_MS

                    val query = packet.copyOfRange(
                        parsed.payloadOffset,
                        parsed.payloadOffset + parsed.payloadLength,
                    )
                    socket.send(DatagramPacket(query, query.size, upstream, DnsPacket.DNS_PORT))

                    val replyBuffer = ByteArray(4_096)
                    val reply = DatagramPacket(replyBuffer, replyBuffer.size)
                    socket.receive(reply)

                    write(DnsPacket.buildResponsePacket(parsed, replyBuffer.copyOf(reply.length)))
                }
            }.onFailure {
                // A dropped lookup is retried by the client's own resolver; far
                // better than holding the tunnel up waiting on a dead network.
                Log.d(TAG, "Upstream lookup failed", it)
            }
        }
    }

    private fun write(packet: ByteArray) {
        synchronized(writeLock) {
            runCatching { output?.write(packet) }
        }
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, BastionApp.CHANNEL_GUARD)
            .setContentTitle("Content filter on")
            .setContentText("Adult domains are being blocked on this device.")
            .setSmallIcon(R.drawable.ic_shield)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    private fun teardown() {
        shouldRun = false
        running.value = false
        runCatching { tunnel?.close() }
        tunnel = null
        output = null
        forwarders.shutdownNow()
        runCatching { forwarders.awaitTermination(500, TimeUnit.MILLISECONDS) }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onRevoke() {
        teardown()
        super.onRevoke()
    }

    override fun onDestroy() {
        shouldRun = false
        running.value = false
        runCatching { tunnel?.close() }
        scope.cancel()
        job.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "BastionVpn"
        private const val NOTIFICATION_ID = 4201
        private const val UPSTREAM_TIMEOUT_MS = 4_000
        private const val MAX_READ_FAILURES = 12
        private const val READ_BACKOFF_MS = 50L

        /** Addresses inside a reserved range, chosen not to collide with a real LAN. */
        private const val TUN_ADDRESS = "10.111.222.2"
        private const val FAKE_DNS = "10.111.222.3"

        /** Cloudflare's family resolver: a second, independent layer of filtering. */
        private const val DEFAULT_UPSTREAM = "1.1.1.3"

        const val ACTION_STOP = "com.bastion.app.STOP_FILTER"

        private val running = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = running.asStateFlow()

        val blockedCount = MutableStateFlow(0)
        val lastBlocked = MutableStateFlow<String?>(null)

        fun start(context: Context) {
            context.startService(Intent(context, BastionVpnService::class.java))
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, BastionVpnService::class.java).setAction(ACTION_STOP)
            )
        }

        /** Null when already authorised; otherwise the consent intent to launch. */
        fun prepareIntent(context: Context): Intent? = prepare(context)
    }
}
