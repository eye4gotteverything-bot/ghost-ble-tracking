package com.ghosttasker.ghostble

import android.Manifest
import android.app.AlertDialog
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Ghost BLE Tracking — native Android, REAL-TIME scanning.
 *
 * This activity wires together:
 *  - [BleScanner]  : real Bluetooth LE radio scanning (continuous)
 *  - [WifiScanner] : real Wi-Fi scanning (Android-throttled, see README)
 *  - [TargetingDetector] : pattern flags (patterns, never proof)
 *
 * The list starts EMPTY and only fills from real scan results.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val REQ_PERMISSIONS = 1001
        private const val CHANNEL_ID = "proximity_alerts"
        private const val UI_REFRESH_MS = 1_000L
        private const val HISTORY_MAX_SAMPLES = 120
    }

    // ---- Views ----
    private lateinit var statusText: TextView
    private lateinit var bandCountsText: TextView
    private lateinit var elapsedText: TextView
    private lateinit var messageText: TextView
    private lateinit var emptyText: TextView
    private lateinit var envSpinner: Spinner
    private lateinit var thresholdInput: EditText
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var wifiButton: Button
    private lateinit var resultsList: ListView

    // ---- State ----
    private val records = LinkedHashMap<String, DeviceRecord>() // key: "BLE:AA:BB.." / "WIFI:AA:BB.."
    private val sessionSeenKeys = mutableSetOf<String>()
    private val detector = TargetingDetector()
    private var sessionId = 0
    private var scanning = false
    private var scanStartMs = 0L
    private var pendingStart = false
    private var listDirty = true

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var adapter: DeviceAdapter

    private lateinit var bleScanner: BleScanner
    private lateinit var wifiScanner: WifiScanner

    private val timeFormat = SimpleDateFormat("h:mm:ss a", Locale.US)

    private val ticker = object : Runnable {
        override fun run() {
            if (scanning) {
                val elapsedSec = (System.currentTimeMillis() - scanStartMs) / 1000
                elapsedText.text = getString(R.string.elapsed_format, elapsedSec / 60, elapsedSec % 60)
            }
            if (listDirty) {
                refreshList()
                listDirty = false
            }
            checkProximityAlerts()
            handler.postDelayed(this, UI_REFRESH_MS)
        }
    }

    // ------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        bandCountsText = findViewById(R.id.bandCountsText)
        elapsedText = findViewById(R.id.elapsedText)
        messageText = findViewById(R.id.messageText)
        emptyText = findViewById(R.id.emptyText)
        envSpinner = findViewById(R.id.envSpinner)
        thresholdInput = findViewById(R.id.thresholdInput)
        startButton = findViewById(R.id.startButton)
        stopButton = findViewById(R.id.stopButton)
        wifiButton = findViewById(R.id.wifiButton)
        resultsList = findViewById(R.id.resultsList)

        createNotificationChannel()

        envSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            DistanceUtil.Environment.values().map { it.label }
        )
        envSpinner.setSelection(DistanceUtil.Environment.INDOOR.ordinal)
        envSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                recalculateDistances()
                listDirty = true
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        adapter = DeviceAdapter()
        resultsList.adapter = adapter
        resultsList.emptyView = emptyText
        resultsList.setOnItemClickListener { _, _, position, _ ->
            adapter.getItem(position)?.let { showDetail(it) }
        }
        resultsList.setOnItemLongClickListener { _, _, position, _ ->
            adapter.getItem(position)?.let { toggleTracked(it) }
            true
        }

        bleScanner = BleScanner(
            context = this,
            onResult = { fresh, _ -> runOnUiThread { mergeBleResult(fresh) } },
            onError = { msg -> runOnUiThread { showMessage(msg) } }
        )
        wifiScanner = WifiScanner(
            context = this,
            onResults = { fresh -> runOnUiThread { mergeWifiResults(fresh) } },
            onThrottled = {
                runOnUiThread {
                    showMessage(getString(R.string.wifi_throttled))
                }
            },
            onError = { msg -> runOnUiThread { showMessage(msg) } }
        )

        startButton.setOnClickListener { startScanningFlow() }
        stopButton.setOnClickListener { stopScanning() }
        wifiButton.setOnClickListener {
            if (ensurePermissions()) {
                wifiScanner.register()
                wifiScanner.requestScan()
            }
        }

        updateStatus()
    }

    override fun onDestroy() {
        bleScanner.stop()
        wifiScanner.unregister()
        handler.removeCallbacks(ticker)
        super.onDestroy()
    }

    // ------------------------------------------------------------------
    // Permissions (Android 12+ model, with older-version fallbacks)
    // ------------------------------------------------------------------

    private fun requiredPermissions(): Array<String> {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) { // 31+
            perms.add(Manifest.permission.BLUETOOTH_SCAN)
            perms.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) { // 33+
            perms.add(Manifest.permission.NEARBY_WIFI_DEVICES)
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        // Android requires location permission for BLE (<= Android 11) and
        // Wi-Fi scan results; on 12+ the neverForLocation flag covers BLE,
        // but Wi-Fi results still need location below Android 13.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.ACCESS_FINE_LOCATION)
        } else {
            // Keep location available for Wi-Fi on devices/OEM builds that
            // still gate scan results on it; denial is handled gracefully.
            perms.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        return perms.toTypedArray()
    }

    private fun missingPermissions(): List<String> =
        requiredPermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

    /** Returns true if every needed permission is already granted. */
    private fun ensurePermissions(): Boolean {
        val missing = missingPermissions()
        if (missing.isEmpty()) return true
        showMessage(getString(R.string.permission_explanation))
        ActivityCompat.requestPermissions(this, missing.toTypedArray(), REQ_PERMISSIONS)
        return false
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_PERMISSIONS) return

        val denied = permissions.filterIndexed { i, _ ->
            grantResults.getOrNull(i) != PackageManager.PERMISSION_GRANTED
        }
        // POST_NOTIFICATIONS denial must not block scanning — alerts just
        // won't post. Any scan-critical denial stops the start, explained.
        val scanCriticalDenied = denied.filter { it != Manifest.permission.POST_NOTIFICATIONS }
        if (scanCriticalDenied.isNotEmpty()) {
            pendingStart = false
            showMessage(getString(R.string.permission_denied))
            updateStatus()
            return
        }
        if (pendingStart) {
            pendingStart = false
            startScanning()
        }
    }

    // ------------------------------------------------------------------
    // Scanning flow
    // ------------------------------------------------------------------

    private fun startScanningFlow() {
        if (scanning) return
        if (!ensurePermissions()) {
            pendingStart = true
            return
        }
        startScanning()
    }

    private fun startScanning() {
        if (!bleScanner.isBluetoothAvailable()) {
            showMessage("This phone has no Bluetooth adapter, so BLE scanning is not possible.")
            return
        }
        sessionId += 1
        sessionSeenKeys.clear()
        detector.resetSessionState()

        val bleStarted = bleScanner.start()
        wifiScanner.register()
        val wifiRequested = wifiScanner.requestScan()

        if (!bleStarted && !wifiRequested) {
            updateStatus()
            return
        }
        scanning = true
        scanStartMs = System.currentTimeMillis()

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            // On Android 11 and below, BLE/Wi-Fi scan results silently come
            // back empty unless the system Location toggle is ON.
            val lm = getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            val locationOn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                lm?.isLocationEnabled == true
            } else {
                true
            }
            if (!locationOn) {
                showMessage("Android needs the system Location toggle ON to return scan results on this Android version. The app never reads your GPS location.")
            }
        }

        handler.removeCallbacks(ticker)
        handler.post(ticker)
        updateStatus()
    }

    private fun stopScanning() {
        bleScanner.stop()
        scanning = false
        handler.removeCallbacks(ticker)
        updateStatus()
    }

    // ------------------------------------------------------------------
    // Merging real results
    // ------------------------------------------------------------------

    private fun keyFor(record: DeviceRecord) = "${record.source}:${record.address}"

    private fun currentEnvironment(): DistanceUtil.Environment =
        DistanceUtil.Environment.fromLabel(envSpinner.selectedItem?.toString() ?: "")

    private fun mergeBleResult(fresh: DeviceRecord) {
        val now = System.currentTimeMillis()
        detector.onBleAdvertisement(fresh.address, now)
        val key = keyFor(fresh)
        val existing = records[key]
        if (existing == null) {
            fresh.distanceFeet = DistanceUtil.distanceFeet(
                fresh.rssi, DistanceUtil.DEFAULT_TX_POWER_AT_1M, currentEnvironment()
            )
            fresh.distanceHistory.add(now to fresh.distanceFeet)
            records[key] = fresh
            detector.onSeenInSession(fresh.address, sessionId, fresh.rssi)
            sessionSeenKeys.add(key)
            detector.evaluateBle(fresh)
        } else {
            existing.rssi = fresh.rssi
            existing.lastSeenMs = now
            existing.seenCount += 1
            if (fresh.name != "Unknown") existing.name = fresh.name
            existing.deviceType = fresh.deviceType
            existing.serviceUuids = fresh.serviceUuids
            existing.serviceNames = fresh.serviceNames
            existing.manufacturerData = fresh.manufacturerData
            existing.manufacturerNames = fresh.manufacturerNames
            existing.connectable = fresh.connectable
            existing.appearance = fresh.appearance
            existing.txPowerAdvertised = fresh.txPowerAdvertised
            existing.distanceFeet = DistanceUtil.distanceFeet(
                fresh.rssi, DistanceUtil.DEFAULT_TX_POWER_AT_1M, currentEnvironment()
            )
            val last = existing.distanceHistory.lastOrNull()
            if (last == null || now - last.first >= 1_000) {
                existing.distanceHistory.add(now to existing.distanceFeet)
                if (existing.distanceHistory.size > HISTORY_MAX_SAMPLES) {
                    existing.distanceHistory.removeAt(0)
                }
            }
            if (key !in sessionSeenKeys) {
                detector.onSeenInSession(existing.address, sessionId, existing.rssi)
                sessionSeenKeys.add(key)
            }
            detector.evaluateBle(existing)
        }
        listDirty = true
    }

    private fun mergeWifiResults(freshList: List<DeviceRecord>) {
        val now = System.currentTimeMillis()
        for (fresh in freshList) {
            if (fresh.address.isBlank()) continue
            val key = keyFor(fresh)
            val existing = records[key]
            if (existing == null) {
                fresh.distanceFeet = DistanceUtil.distanceFeet(
                    fresh.rssi, DistanceUtil.DEFAULT_TX_POWER_AT_1M, currentEnvironment()
                )
                fresh.distanceHistory.add(now to fresh.distanceFeet)
                records[key] = fresh
            } else {
                existing.rssi = fresh.rssi
                existing.lastSeenMs = now
                existing.seenCount += 1
                existing.ssid = fresh.ssid
                existing.name = fresh.name
                existing.frequencyMhz = fresh.frequencyMhz
                existing.channel = fresh.channel
                existing.band = fresh.band
                existing.capabilities = fresh.capabilities
                existing.securityLabel = fresh.securityLabel
                existing.distanceFeet = DistanceUtil.distanceFeet(
                    fresh.rssi, DistanceUtil.DEFAULT_TX_POWER_AT_1M, currentEnvironment()
                )
                existing.distanceHistory.add(now to existing.distanceFeet)
                if (existing.distanceHistory.size > HISTORY_MAX_SAMPLES) {
                    existing.distanceHistory.removeAt(0)
                }
            }
        }
        detector.evaluateWifi(records.values)
        listDirty = true
    }

    private fun recalculateDistances() {
        val env = currentEnvironment()
        for (record in records.values) {
            record.distanceFeet = DistanceUtil.distanceFeet(
                record.rssi, DistanceUtil.DEFAULT_TX_POWER_AT_1M, env
            )
        }
    }

    // ------------------------------------------------------------------
    // UI
    // ------------------------------------------------------------------

    private fun sortedRecords(): List<DeviceRecord> =
        records.values.sortedWith(
            compareBy<DeviceRecord> { it.distanceFeet.isNaN() }
                .thenBy { it.distanceFeet }
        )

    private fun refreshList() {
        adapter.replaceAll(sortedRecords())
        val bleCount = records.values.count { it.source == ScanSource.BLE }
        val wifi24 = records.values.count { it.source == ScanSource.WIFI && it.band == "2.4 GHz" }
        val wifi5 = records.values.count { it.source == ScanSource.WIFI && it.band == "5 GHz" }
        val wifi6 = records.values.count { it.source == ScanSource.WIFI && it.band == "6 GHz" }
        bandCountsText.text = getString(
            R.string.band_counts_format, bleCount, wifi24, wifi5, wifi6
        )
        emptyText.visibility = if (records.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun updateStatus() {
        statusText.text = when {
            scanning -> getString(R.string.status_scanning)
            records.isNotEmpty() -> getString(R.string.status_stopped)
            else -> getString(R.string.status_idle)
        }
        startButton.isEnabled = !scanning
        stopButton.isEnabled = scanning
    }

    private fun showMessage(msg: String) {
        messageText.text = msg
        messageText.visibility = View.VISIBLE
    }

    private inner class DeviceAdapter : ArrayAdapter<DeviceRecord>(
        this@MainActivity, R.layout.item_device
    ) {
        fun replaceAll(items: List<DeviceRecord>) {
            clear()
            addAll(items)
            notifyDataSetChanged()
        }

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: LayoutInflater.from(context)
                .inflate(R.layout.item_device, parent, false)
            val record = getItem(position) ?: return view
            val title = view.findViewById<TextView>(R.id.itemTitle)
            val detail = view.findViewById<TextView>(R.id.itemDetail)
            val trackButton = view.findViewById<Button>(R.id.itemTrackButton)

            val dist = DistanceUtil.formatFeet(record.distanceFeet)
            val trackedMark = if (record.tracked) "📌 Tracked · " else ""
            val flagMark = if (record.flags.isNotEmpty()) " ⚠ pattern" else ""
            title.text = "$trackedMark${record.displayName()} — $dist (est.)$flagMark"
            detail.text = when (record.source) {
                ScanSource.BLE ->
                    "${record.bandLabel()} · ${record.deviceType} · ${record.address} · ${record.rssi} dBm"
                ScanSource.WIFI ->
                    "${record.bandLabel()} · ch ${record.channel} · ${record.securityLabel} · ${record.address} · ${record.rssi} dBm"
            }
            trackButton.text = if (record.tracked) getString(R.string.untrack) else getString(R.string.track)
            trackButton.setOnClickListener { toggleTracked(record) }
            return view
        }
    }

    // ------------------------------------------------------------------
    // Detail + tracking + proximity alerts
    // ------------------------------------------------------------------

    private fun toggleTracked(record: DeviceRecord) {
        record.tracked = !record.tracked
        record.alertFired = false
        Toast.makeText(
            this,
            if (record.tracked) "Tracking ${record.displayName()}" else "Stopped tracking ${record.displayName()}",
            Toast.LENGTH_SHORT
        ).show()
        listDirty = true
        refreshList()
    }

    private fun alertThresholdFeet(): Double =
        thresholdInput.text?.toString()?.toDoubleOrNull() ?: 10.0

    private fun checkProximityAlerts() {
        if (!scanning) return
        val threshold = alertThresholdFeet()
        for (record in records.values) {
            if (!record.tracked || record.distanceFeet.isNaN()) continue
            if (record.distanceFeet <= threshold) {
                if (!record.alertFired) {
                    record.alertFired = true
                    postProximityNotification(record, threshold)
                }
            } else if (record.distanceFeet > threshold + 2) {
                // Hysteresis: only re-arm after it clearly moves away.
                record.alertFired = false
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.channel_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply { description = getString(R.string.channel_description) }
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    private fun postProximityNotification(record: DeviceRecord, threshold: Double) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return // Alerts are optional; scanning continues without them.
        }
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(getString(R.string.alert_title))
            .setContentText(
                "${record.displayName()} is about ${DistanceUtil.formatFeet(record.distanceFeet)} away " +
                    "(your alert is set to ${threshold.toInt()} ft). Distance is an estimate."
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(this)
            .notify(record.address.hashCode(), notification)
    }

    private fun showDetail(record: DeviceRecord) {
        val sb = StringBuilder()
        sb.appendLine("Name: ${record.displayName()}")
        sb.appendLine("Type: ${record.deviceType}")
        sb.appendLine("Address: ${record.address}")
        sb.appendLine("Source: ${record.bandLabel()}")
        sb.appendLine("Signal: ${record.rssi} dBm")
        sb.appendLine("Distance (estimate): ${DistanceUtil.formatFeet(record.distanceFeet)}")
        sb.appendLine("First seen: ${timeFormat.format(Date(record.firstSeenMs))}")
        sb.appendLine("Last seen: ${timeFormat.format(Date(record.lastSeenMs))}")
        sb.appendLine("Times seen this session: ${record.seenCount}")
        if (record.source == ScanSource.BLE) {
            sb.appendLine("Connectable: ${if (record.connectable) "Yes" else "No / not reported"}")
            record.appearance?.let { sb.appendLine("Appearance value: $it") }
            record.txPowerAdvertised?.let { sb.appendLine("Advertised TX power: $it dBm") }
            if (record.serviceUuids.isNotEmpty()) {
                sb.appendLine()
                sb.appendLine("Advertised services:")
                record.serviceUuids.forEachIndexed { i, uuid ->
                    val friendly = record.serviceNames.getOrNull(i)
                    sb.appendLine(" • $uuid" + (friendly?.let { " ($it)" } ?: ""))
                }
            }
            if (record.manufacturerData.isNotEmpty()) {
                sb.appendLine()
                sb.appendLine("Manufacturer data:")
                record.manufacturerData.forEach { (id, hex) ->
                    val maker = DeviceType.KNOWN_MANUFACTURERS[id]
                    sb.appendLine(" • Company 0x${id.toString(16).uppercase()}" +
                        (maker?.let { " ($it)" } ?: "") + ": $hex")
                }
            }
        } else {
            sb.appendLine("SSID: ${if (record.ssid.isBlank()) "(hidden)" else record.ssid}")
            sb.appendLine("Frequency: ${record.frequencyMhz} MHz · Channel: ${record.channel}")
            sb.appendLine("Security: ${record.securityLabel}")
            sb.appendLine("Capabilities: ${record.capabilities}")
        }
        if (record.flags.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("Targeting patterns (patterns only — NOT proof of an attack):")
            record.flags.distinct().forEach { sb.appendLine(" ⚠ $it") }
        }
        if (record.distanceHistory.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("Distance history this session (newest last):")
            record.distanceHistory.takeLast(10).forEach { (t, ft) ->
                sb.appendLine(" • ${timeFormat.format(Date(t))} — ${DistanceUtil.formatFeet(ft)}")
            }
        }

        AlertDialog.Builder(this)
            .setTitle(record.displayName())
            .setMessage(sb.toString())
            .setPositiveButton(if (record.tracked) getString(R.string.untrack) else getString(R.string.track)) { _, _ ->
                toggleTracked(record)
            }
            .setNegativeButton(R.string.close_button, null)
            .show()
    }
}
