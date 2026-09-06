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
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import java.util.UUID

sealed interface BluetoothEvent {
    data class PeerCount(val count: Int) : BluetoothEvent
    data class Payload(val bytes: ByteArray) : BluetoothEvent
    data class Unavailable(val reason: String) : BluetoothEvent
}

@SuppressLint("MissingPermission")
class SharedBuysBluetooth(private val context: Context) {

    private val manager = context.getSystemService(BluetoothManager::class.java)
    private val adapter get() = manager?.adapter

    private var server: BluetoothGattServer? = null
    private var outbox: BluetoothGattCharacteristic? = null
    private val subscribers = mutableSetOf<BluetoothDevice>()
    private val clients = mutableMapOf<String, BluetoothGatt>()
    private val inboxes = mutableMapOf<String, BluetoothGattCharacteristic>()
    private val reassemblers = mutableMapOf<String, SharedBuysFraming.Reassembler>()
    private val seenDigests = mutableMapOf<String, String>()

    private var sessionKey: ByteArray? = null
    private var digest: ByteArray = ByteArray(4)
    private var onEvent: ((BluetoothEvent) -> Unit)? = null
    private var messageCounter: Byte = 0

    val peerCount: Int get() = clients.size + subscribers.size

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
    }

    fun stop() {
        runCatching { adapter?.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback) }
        runCatching { adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
        clients.values.forEach { runCatching { it.close() } }
        clients.clear()
        inboxes.clear()
        subscribers.clear()
        reassemblers.clear()
        seenDigests.clear()
        runCatching { server?.close() }
        server = null
        sessionKey = null
        onEvent = null
    }

    fun update(digest: ByteArray) {
        if (this.digest.contentEquals(digest)) return
        this.digest = digest
        if (missingPermissions().isEmpty() && adapter?.isEnabled == true) advertise()
    }

    fun send(payload: ByteArray) {
        messageCounter = (messageCounter + 1).toByte()
        for (frame in SharedBuysFraming.chunks(payload, messageCounter)) {
            val characteristic = outbox
            if (characteristic != null) {
                characteristic.value = frame
                subscribers.forEach { device ->
                    runCatching { server?.notifyCharacteristicChanged(device, characteristic, false) }
                }
            }
            clients.forEach { (address, gatt) ->
                inboxes[address]?.let { inbox ->
                    inbox.value = frame
                    inbox.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                    runCatching { gatt.writeCharacteristic(inbox) }
                }
            }
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
                UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"),
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
        val payload = SharedBuysProfile.sessionTag(key) + digest + byteArrayOf(peerCount.toByte())
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(true)
            .build()
        val data = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(SharedBuysProfile.SERVICE_UUID))
            .addServiceData(ParcelUuid(SharedBuysProfile.SERVICE_UUID), payload)
            .setIncludeDeviceName(false)
            .build()
        runCatching { advertiser.startAdvertising(settings, data, advertiseCallback) }
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
        val key = sessionKey ?: return false
        val payload = result.scanRecord
            ?.getServiceData(ParcelUuid(SharedBuysProfile.SERVICE_UUID)) ?: return false
        if (payload.size < 6) return false
        val tag = payload.copyOfRange(0, 2)
        if (SharedBuysProfile.acceptedTags(key).none { it.contentEquals(tag) }) return false
        val theirDigest = payload.copyOfRange(2, 6).toHex()
        val address = result.device.address
        if (theirDigest == digest.toHex() && seenDigests[address] == theirDigest) return false
        seenDigests[address] = theirDigest
        return true
    }

    private fun deliver(address: String, frame: ByteArray) {
        val reassembler = reassemblers.getOrPut(address) { SharedBuysFraming.Reassembler() }
        reassembler.accept(frame)?.let { onEvent?.invoke(BluetoothEvent.Payload(it)) }
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartFailure(errorCode: Int) {
            onEvent?.invoke(BluetoothEvent.Unavailable("advertise failed $errorCode"))
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (!shouldConnect(result)) return
            val address = result.device.address
            if (clients.containsKey(address)) return
            val gatt = result.device.connectGatt(context, false, gattCallback)
            clients[address] = gatt
            onEvent?.invoke(BluetoothEvent.PeerCount(peerCount))
        }

        override fun onScanFailed(errorCode: Int) {
            onEvent?.invoke(BluetoothEvent.Unavailable("scan failed $errorCode"))
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                val address = gatt.device.address
                clients.remove(address)?.close()
                inboxes.remove(address)
                reassemblers.remove(address)
                onEvent?.invoke(BluetoothEvent.PeerCount(peerCount))
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val service = gatt.getService(SharedBuysProfile.SERVICE_UUID) ?: return
            service.getCharacteristic(SharedBuysProfile.INBOX_UUID)?.let {
                inboxes[gatt.device.address] = it
            }
            service.getCharacteristic(SharedBuysProfile.OUTBOX_UUID)?.let { characteristic ->
                gatt.setCharacteristicNotification(characteristic, true)
                characteristic.getDescriptor(
                    UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
                )?.let { descriptor ->
                    descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    runCatching { gatt.writeDescriptor(descriptor) }
                }
            }
            onEvent?.invoke(BluetoothEvent.PeerCount(peerCount))
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            characteristic.value?.let { deliver(gatt.device.address, it) }
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
        ) {
            deliver(device.address, value)
            if (responseNeeded) {
                server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
            }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            if (value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)) {
                subscribers.add(device)
            } else {
                subscribers.remove(device)
            }
            if (responseNeeded) {
                server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
            }
            onEvent?.invoke(BluetoothEvent.PeerCount(peerCount))
        }

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                subscribers.remove(device)
                reassemblers.remove(device.address)
                onEvent?.invoke(BluetoothEvent.PeerCount(peerCount))
            }
        }
    }
}
