package com.tsubuzaki.circlesgo.sharedbuys

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import java.util.UUID

sealed interface BluetoothEvent {
    data class PeerCount(val count: Int) : BluetoothEvent
    data class PeerVerified(val digest: ByteArray?) : BluetoothEvent
    data class Payload(val bytes: ByteArray) : BluetoothEvent
    data class Unavailable(val reason: String) : BluetoothEvent
}

private const val MTU = 247
private const val REFRESH_INTERVAL_MS = 30_000L
private val CLIENT_CONFIG_UUID: UUID =
    UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

@SuppressLint("MissingPermission")
class SharedBuysBluetooth(private val context: Context) {

    private val manager = context.getSystemService(BluetoothManager::class.java)
    private val adapter get() = manager?.adapter

    private var server: BluetoothGattServer? = null
    private var outbox: BluetoothGattCharacteristic? = null
    private val subscribers = mutableSetOf<BluetoothDevice>()
    private val clients = mutableMapOf<String, BluetoothGatt>()
    private val inboxes = mutableMapOf<String, BluetoothGattCharacteristic>()
    // Kept apart by role. A peer we are both connected to and serving sends us the
    // same message down both paths under one message id; funnelling them into a single
    // buffer let the duplicate indices overwrite each other and complete early.
    private val clientReassemblers = mutableMapOf<String, SharedBuysFraming.Reassembler>()
    private val serverReassemblers = mutableMapOf<String, SharedBuysFraming.Reassembler>()
    private val mtus = mutableMapOf<String, Int>()
    private val verifiedClients = mutableSetOf<String>()
    private val verifiedCentrals = mutableSetOf<String>()
    private val rejectedUntil = mutableMapOf<String, Long>()
    private val pendingNotifies = ArrayDeque<Pair<ByteArray, BluetoothDevice>>()
    private val writeQueues = mutableMapOf<String, ArrayDeque<ByteArray>>()
    private val writing = mutableSetOf<String>()
    private val peerDigests = mutableMapOf<String, ByteArray>()
    private val handler = Handler(Looper.getMainLooper())
    private var advertisedWindow: Long? = null

    private var sessionKey: ByteArray? = null
    private var digest: ByteArray = ByteArray(4)
    private var onEvent: ((BluetoothEvent) -> Unit)? = null
    private var messageCounter: Byte = 0

    val peerCount: Int get() = verifiedClients.size + verifiedCentrals.size

    private val requiredPermissions: List<String>
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_CONNECT
            )
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    fun missingPermissions(): List<String> = requiredPermissions.filter {
        ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
    }

    fun start(sessionKey: ByteArray, digest: ByteArray, onEvent: (BluetoothEvent) -> Unit) {
        this.sessionKey = sessionKey
        this.digest = digest
        this.onEvent = onEvent

        val adapter = adapter
        if (adapter == null || !adapter.isEnabled) {
            onEvent(BluetoothEvent.Unavailable("bluetooth off"))
            return
        }
        val missing = missingPermissions()
        if (missing.isNotEmpty()) {
            onEvent(BluetoothEvent.Unavailable("permission: ${missing.joinToString()}"))
            return
        }
        publishService()
        advertise()
        scan()
        startRefreshing()
    }

    fun stop() {
        handler.removeCallbacks(refresh)
        advertisedWindow = null
        runCatching { adapter?.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback) }
        runCatching { adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
        clients.values.forEach { runCatching { it.close() } }
        clients.clear()
        inboxes.clear()
        subscribers.clear()
        clientReassemblers.clear()
        serverReassemblers.clear()
        mtus.clear()
        verifiedClients.clear()
        verifiedCentrals.clear()
        rejectedUntil.clear()
        pendingNotifies.clear()
        writeQueues.clear()
        writing.clear()
        peerDigests.clear()
        runCatching { server?.close() }
        server = null
        sessionKey = null
        onEvent = null
    }

    fun update(digest: ByteArray) {
        if (this.digest.contentEquals(digest)) return
        this.digest = digest
        advertise()
    }

    fun send(payload: ByteArray) {
        messageCounter = (messageCounter + 1).toByte()
        // Each link negotiates its own MTU, so the payload is cut to fit the peer it is
        // going to rather than to one hardcoded size.
        subscribers.filter { verifiedCentrals.contains(it.address) }.forEach { device ->
            SharedBuysFraming.chunks(payload, messageCounter, payloadLimit(device.address))
                .forEach { frame -> notify(frame, device) }
        }
        clients.forEach { (address, gatt) ->
            if (!verifiedClients.contains(address)) return@forEach
            SharedBuysFraming.chunks(payload, messageCounter, payloadLimit(address))
                .forEach { frame -> enqueueWrite(gatt, frame) }
        }
    }

    /**
     * A GATT client can only have one write in flight. Writing every chunk of a message
     * back to back hands the stack the second one before it has reported the first, and
     * the second is what gets dropped — so a two chunk message never reassembles.
     */
    private fun enqueueWrite(gatt: BluetoothGatt, frame: ByteArray) {
        val address = gatt.device.address
        writeQueues.getOrPut(address) { ArrayDeque() }.addLast(frame)
        pumpWrites(gatt)
    }

    private fun pumpWrites(gatt: BluetoothGatt) {
        val address = gatt.device.address
        if (writing.contains(address)) return
        val queue = writeQueues[address] ?: return
        val inbox = inboxes[address] ?: return
        val frame = queue.removeFirstOrNull() ?: return
        writing.add(address)
        val sent = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeCharacteristic(
                    inbox,
                    frame,
                    BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                ) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                run {
                    inbox.value = frame
                    inbox.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                    gatt.writeCharacteristic(inbox)
                }
            }
        }.getOrDefault(false)
        if (!sent) {
            writing.remove(address)
        }
    }

    private fun publishService() {
        val service = BluetoothGattService(
            SharedBuysProfile.SERVICE_UUID,
            BluetoothGattService.SERVICE_TYPE_PRIMARY
        )
        val inbox = BluetoothGattCharacteristic(
            SharedBuysProfile.INBOX_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        val outboxCharacteristic = BluetoothGattCharacteristic(
            SharedBuysProfile.OUTBOX_UUID,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        outboxCharacteristic.addDescriptor(
            BluetoothGattDescriptor(
                CLIENT_CONFIG_UUID,
                BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
            )
        )
        service.addCharacteristic(inbox)
        service.addCharacteristic(outboxCharacteristic)
        outbox = outboxCharacteristic
        server = manager?.openGattServer(context, serverCallback)?.also { it.addService(service) }
    }

    private fun advertise() {
        val key = sessionKey ?: return
        val advertiser = adapter?.bluetoothLeAdvertiser ?: return
        runCatching { advertiser.stopAdvertising(advertiseCallback) }
        advertisedWindow = SharedBuysProfile.window()
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(true)
            .build()
        val data = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(SharedBuysProfile.SERVICE_UUID))
            .setIncludeDeviceName(false)
            .build()
        // A 128 bit service UUID and its service data do not both fit in the 31 byte
        // advertisement, so the room tag and digest go in the scan response. iOS reads
        // the merged record, and puts the same bytes in its local name.
        val scanResponse = AdvertiseData.Builder()
            .addServiceData(
                ParcelUuid(SharedBuysProfile.SERVICE_UUID),
                SharedBuysProfile.advertisement(key, digest)
            )
            .setIncludeDeviceName(false)
            .build()
        runCatching {
            advertiser.startAdvertising(settings, data, scanResponse, advertiseCallback)
        }
    }

    /**
     * The advertised tag is only valid for its window, so it has to be reissued before
     * the window turns over, or peers stop recognising us as part of the room.
     */
    private fun startRefreshing() {
        handler.removeCallbacks(refresh)
        handler.postDelayed(refresh, REFRESH_INTERVAL_MS)
    }

    private val refresh = object : Runnable {
        override fun run() {
            if (sessionKey == null) return
            if (advertisedWindow != SharedBuysProfile.window()) advertise()
            handler.postDelayed(this, REFRESH_INTERVAL_MS)
        }
    }

    private fun scan() {
        val scanner = adapter?.bluetoothLeScanner ?: return
        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(SharedBuysProfile.SERVICE_UUID))
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_BALANCED)
            .build()
        runCatching { scanner.startScan(listOf(filter), settings, scanCallback) }
    }

    private fun shouldConnect(result: ScanResult): Boolean {
        sessionKey ?: return false
        val address = result.device.address
        if (clients.containsKey(address)) return false
        val until = rejectedUntil[address] ?: return true
        return until <= System.currentTimeMillis()
    }

    private fun reject(gatt: BluetoothGatt) {
        rejectedUntil[gatt.device.address] = System.currentTimeMillis() + 60_000L
        runCatching { gatt.disconnect() }
    }

    private fun notify(frame: ByteArray, device: BluetoothDevice) {
        val characteristic = outbox ?: return
        if (pendingNotifies.isNotEmpty()) {
            pendingNotifies.addLast(frame to device)
            return
        }
        characteristic.value = frame
        val sent = runCatching {
            server?.notifyCharacteristicChanged(device, characteristic, false) == true
        }.getOrDefault(false)
        if (!sent) pendingNotifies.addLast(frame to device)
    }

    private fun flushNotifies() {
        val characteristic = outbox ?: return
        while (pendingNotifies.isNotEmpty()) {
            val (frame, device) = pendingNotifies.first()
            characteristic.value = frame
            val sent = runCatching {
                server?.notifyCharacteristicChanged(device, characteristic, false) == true
            }.getOrDefault(false)
            if (!sent) return
            pendingNotifies.removeFirst()
        }
    }

    private fun deliverFromServer(address: String, frame: ByteArray) {
        val reassembler = serverReassemblers.getOrPut(address) { SharedBuysFraming.Reassembler() }
        reassembler.accept(frame)?.let { onEvent?.invoke(BluetoothEvent.Payload(it)) }
    }

    private fun deliverFromClient(address: String, frame: ByteArray) {
        val reassembler = clientReassemblers.getOrPut(address) { SharedBuysFraming.Reassembler() }
        reassembler.accept(frame)?.let { onEvent?.invoke(BluetoothEvent.Payload(it)) }
    }

    private fun payloadLimit(address: String): Int =
        SharedBuysProfile.payloadLimit(mtus[address] ?: SharedBuysProfile.DEFAULT_ATT_MTU)

    /**
     * Peer state is touched from binder threads and from the app thread both.
     *
     * clients, subscribers, verifiedCentrals and the reassemblers are plain collections,
     * so send() iterating them on the app thread while a disconnect removed from them on
     * a binder thread was a ConcurrentModificationException on a routine walk-away. Every
     * callback hands its work to the main thread, which also means Compose state is
     * mutated where Compose expects it.
     */
    private fun confined(work: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) work() else handler.post(work)
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartFailure(errorCode: Int) = confined {
            onEvent?.invoke(BluetoothEvent.Unavailable("advertise failed $errorCode"))
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) = confined {
            if (!shouldConnect(result)) return@confined
            val address = result.device.address
            if (clients.containsKey(address)) return@confined
            val key = sessionKey ?: return@confined
            // A peer that carries no room bytes is not necessarily a stranger — an iOS
            // app in the background cannot advertise a local name — so it still gets a
            // chance at the handshake. One that carries the wrong room is a stranger,
            // and connecting to it could only end in a failed handshake.
            val advertisement = SharedBuysProfile.advertisement(
                result.scanRecord?.getServiceData(ParcelUuid(SharedBuysProfile.SERVICE_UUID)),
                result.scanRecord?.deviceName
            )
            if (advertisement != null) {
                if (!SharedBuysProfile.accepts(advertisement, key)) {
                    rejectedUntil[address] = System.currentTimeMillis() + 60_000L
                    return@confined
                }
                peerDigests[address] = SharedBuysProfile.digest(advertisement)
            }
            clients[address] = result.device.connectGatt(context, false, gattCallback)
        }

        override fun onScanFailed(errorCode: Int) = confined {
            onEvent?.invoke(BluetoothEvent.Unavailable("scan failed $errorCode"))
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) = confined {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                if (!gatt.requestMtu(MTU)) gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                val address = gatt.device.address
                clients.remove(address)?.close()
                inboxes.remove(address)
                clientReassemblers.remove(address)
                mtus.remove(address)
                verifiedClients.remove(address)
                writeQueues.remove(address)
                writing.remove(address)
                peerDigests.remove(address)
                onEvent?.invoke(BluetoothEvent.PeerCount(peerCount))
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) = confined {
            // Discovery has to proceed either way; only the size is conditional, since a
            // failed negotiation leaves the link at the 23 byte default.
            if (status == BluetoothGatt.GATT_SUCCESS) mtus[gatt.device.address] = mtu
            gatt.discoverServices()
            Unit
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) = confined {
            val service = gatt.getService(SharedBuysProfile.SERVICE_UUID) ?: return@confined
            service.getCharacteristic(SharedBuysProfile.INBOX_UUID)?.let {
                inboxes[gatt.device.address] = it
            }
            val characteristic = service.getCharacteristic(SharedBuysProfile.OUTBOX_UUID)
            if (characteristic == null) {
                reject(gatt)
                return@confined
            }
            gatt.setCharacteristicNotification(characteristic, true)
            val descriptor = characteristic.getDescriptor(CLIENT_CONFIG_UUID)
            if (descriptor == null) {
                reject(gatt)
                return@confined
            }
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            runCatching { gatt.writeDescriptor(descriptor) }
            Unit
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) = confined {
            val key = sessionKey ?: return@confined
            enqueueWrite(gatt, SharedBuysProfile.handshake(key))
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) = confined {
            writing.remove(gatt.device.address)
            pumpWrites(gatt)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            val value = characteristic.value?.copyOf() ?: return
            confined { received(gatt, value) }
        }

        private fun received(gatt: BluetoothGatt, value: ByteArray) {
            val address = gatt.device.address
            if (SharedBuysProfile.isHandshake(value)) {
                val key = sessionKey
                if (key == null || !SharedBuysProfile.accepts(value, key)) {
                    reject(gatt)
                    return
                }
                verifiedClients.add(address)
                onEvent?.invoke(BluetoothEvent.PeerCount(peerCount))
                onEvent?.invoke(BluetoothEvent.PeerVerified(peerDigests[address]))
                return
            }
            if (!verifiedClients.contains(address)) return
            deliverFromClient(address, value)
        }
    }

    private val serverCallback = object : BluetoothGattServerCallback() {
        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) = confined {
            if (SharedBuysProfile.isHandshake(value)) {
                val key = sessionKey
                if (key != null && SharedBuysProfile.accepts(value, key)) {
                    verifiedCentrals.add(device.address)
                    notify(SharedBuysProfile.handshake(key), device)
                    onEvent?.invoke(BluetoothEvent.PeerCount(peerCount))
                    // We never scanned this one, so its digest is unknown.
                    onEvent?.invoke(BluetoothEvent.PeerVerified(null))
                }
            } else if (verifiedCentrals.contains(device.address)) {
                deliverFromServer(device.address, value)
            }
            if (responseNeeded) {
                server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
            }
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) = confined {
            flushNotifies()
        }

        // The peer drives negotiation when we are the server, so the size arrives here
        // rather than from a request of ours.
        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) = confined {
            mtus[device.address] = mtu
            Unit
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) = confined {
            if (value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)) {
                subscribers.add(device)
            } else {
                subscribers.remove(device)
                verifiedCentrals.remove(device.address)
            }
            if (responseNeeded) {
                server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
            }
        }

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) = confined {
            if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                subscribers.remove(device)
                verifiedCentrals.remove(device.address)
                serverReassemblers.remove(device.address)
                pendingNotifies.removeAll { it.second.address == device.address }
                onEvent?.invoke(BluetoothEvent.PeerCount(peerCount))
            }
        }
    }
}
