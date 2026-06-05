package com.example.pc

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import okhttp3.*
import java.util.concurrent.TimeUnit

data class Device(
    val ipAddress: String,
    var pcName: String = "Загрузка...",
    var status: String = "Проверка...",
    var quickStats: String = "CPU: --% | GPU: --%",
    var isOnline: Boolean = false
)

class MainActivity : BaseActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var devicesRecyclerView: RecyclerView
    private lateinit var fabAdd: FloatingActionButton
    private lateinit var fabSettings: FloatingActionButton
    private lateinit var fabWidgets: FloatingActionButton

    private lateinit var deviceAdapter: DeviceAdapter
    private val devices = mutableListOf<Device>()

    private val statsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == PCForegroundService.ACTION_STATS_UPDATE) {
                val ip = intent.getStringExtra("DEVICE_IP") ?: return
                val isOnline = intent.getBooleanExtra("IS_ONLINE", false)
                val statsJson = intent.getStringExtra("STATS_JSON")
                
                if (statsJson != null) {
                    val stats = gson.fromJson(statsJson, PCStats::class.java)
                    updateDeviceStats(ip, stats)
                } else {
                    updateDeviceStatusOnly(ip, isOnline)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        devicesRecyclerView = findViewById(R.id.devicesRecyclerView)
        fabAdd = findViewById(R.id.fab_add)
        fabSettings = findViewById(R.id.fab_settings)
        fabWidgets = findViewById(R.id.fab_widgets)

        setupRecyclerView()
        loadDevices()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 101)
        }

        val filter = IntentFilter(PCForegroundService.ACTION_STATS_UPDATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(statsReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(statsReceiver, filter)
        }

        PCForegroundService.startService(this)
        PCForegroundService.refresh(this)

        fabAdd.setOnClickListener {
            vibrate()
            showAddDeviceDialog()
        }
        
        fabSettings.setOnClickListener {
            vibrate()
            val intent = Intent(this, SettingsActivity::class.java)
            startActivity(intent)
        }

        fabWidgets.setOnClickListener {
            vibrate()
            val intent = Intent(this, WidgetDesignerActivity::class.java)
            startActivity(intent)
        }
    }

    private fun updateDeviceStats(ip: String, stats: PCStats) {
        val index = devices.indexOfFirst { it.ipAddress == ip }
        if (index != -1) {
            val device = devices[index]
            device.isOnline = true
            device.pcName = stats.pc_name
            device.status = "Online | ${stats.time}"
            device.quickStats = "CPU: ${stats.cpu.usage.toInt()}% | GPU: ${stats.gpu.getOrNull(0)?.load ?: 0}%"
            runOnUiThread {
                deviceAdapter.notifyItemChanged(index)
            }
        }
    }

    private fun updateDeviceStatusOnly(ip: String, isOnline: Boolean) {
        val index = devices.indexOfFirst { it.ipAddress == ip }
        if (index != -1) {
            val device = devices[index]
            device.isOnline = isOnline
            if (!isOnline) {
                device.status = "Offline"
                device.pcName = "Загрузка..."
                device.quickStats = "CPU: --% | GPU: --%"
            }
            runOnUiThread {
                deviceAdapter.notifyItemChanged(index)
            }
        }
    }

    private fun setupRecyclerView() {
        deviceAdapter = DeviceAdapter(devices,
            onItemClick = { device ->
                vibrate()
                if (device.isOnline) {
                    val intent = Intent(this, DashboardActivity::class.java)
                    intent.putExtra("DEVICE_IP", device.ipAddress)
                    startActivity(intent)
                } else {
                    Toast.makeText(this, "Устройство не в сети", Toast.LENGTH_SHORT).show()
                }
            },
            onItemLongClick = { device ->
                vibrate(20)
                showDeleteDeviceDialog(device)
            }
        )
        devicesRecyclerView.adapter = deviceAdapter
        devicesRecyclerView.layoutManager = LinearLayoutManager(this)
    }

    private fun showAddDeviceDialog() {
        val builder = AlertDialog.Builder(this)
        builder.setTitle("Добавить устройство")
        val input = EditText(this)
        input.hint = "Введите IP (например: 192.168.1.23)"
        input.setSingleLine()
        builder.setView(input)
        builder.setPositiveButton("Сохранить") { _, _ ->
            vibrate()
            val ip = input.text.toString().trim()
            if (ip.isNotEmpty() && devices.none { it.ipAddress == ip }) {
                val newDevice = Device(ip)
                devices.add(newDevice)
                deviceAdapter.notifyItemInserted(devices.size - 1)
                saveDevices()
            }
        }
        builder.setNegativeButton("Отмена", null)
        builder.show()
    }

    private fun showDeleteDeviceDialog(device: Device) {
        AlertDialog.Builder(this)
            .setTitle("Удалить устройство?")
            .setMessage("Вы уверены?")
            .setPositiveButton("Удалить") { _, _ ->
                vibrate()
                val index = devices.indexOf(device)
                if (index != -1) {
                    devices.removeAt(index)
                    deviceAdapter.notifyItemRemoved(index)
                    saveDevices()
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun saveDevices() {
        val prefs = getSharedPreferences("PC_STATS_PREFS", Context.MODE_PRIVATE)
        prefs.edit().putStringSet("DEVICE_IPS", devices.map { it.ipAddress }.toSet()).apply()
        PCForegroundService.startService(this)
    }

    private fun loadDevices() {
        val prefs = getSharedPreferences("PC_STATS_PREFS", Context.MODE_PRIVATE)
        val ipSet = prefs.getStringSet("DEVICE_IPS", emptySet()) ?: emptySet()
        devices.clear()
        ipSet.forEach { devices.add(Device(it)) }
        deviceAdapter.notifyDataSetChanged()
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(statsReceiver)
        } catch (e: Exception) {
            // Receiver might not be registered
        }
    }
}

class DeviceAdapter(
    private val devices: List<Device>,
    private val onItemClick: (Device) -> Unit,
    private val onItemLongClick: (Device) -> Unit
) : RecyclerView.Adapter<DeviceAdapter.DeviceViewHolder>() {
    class DeviceViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val deviceName: TextView = view.findViewById(R.id.deviceName)
        val deviceStatus: TextView = view.findViewById(R.id.deviceStatus)
        val quickStats: TextView = view.findViewById(R.id.quickStats)
    }
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DeviceViewHolder {
        return DeviceViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_device, parent, false))
    }
    override fun onBindViewHolder(holder: DeviceViewHolder, position: Int) {
        val device = devices[position]
        holder.deviceName.text = device.pcName
        holder.deviceStatus.text = device.status
        holder.quickStats.text = device.quickStats
        holder.itemView.setOnClickListener { onItemClick(device) }
        holder.itemView.setOnLongClickListener { onItemLongClick(device); true }
    }
    override fun getItemCount() = devices.size
}