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
    }

    private fun updateDeviceStats(ip: String, stats: PCStats) {
        val prefs = getSharedPreferences("PC_STATS_PREFS", Context.MODE_PRIVATE)
        val isRussian = prefs.getString("APP_LANGUAGE", "RU") == "RU"
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
        val prefs = getSharedPreferences("PC_STATS_PREFS", Context.MODE_PRIVATE)
        val isRussian = prefs.getString("APP_LANGUAGE", "RU") == "RU"
        val index = devices.indexOfFirst { it.ipAddress == ip }
        if (index != -1) {
            val device = devices[index]
            device.isOnline = isOnline
            if (!isOnline) {
                device.status = "Offline"
                device.pcName = if (isRussian) "Загрузка..." else "Loading..."
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
                    val isRussian = getSharedPreferences("PC_STATS_PREFS", Context.MODE_PRIVATE).getString("APP_LANGUAGE", "RU") == "RU"
                    Toast.makeText(this, if (isRussian) "Устройство не в сети" else "Device offline", Toast.LENGTH_SHORT).show()
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
        val prefs = getSharedPreferences("PC_STATS_PREFS", Context.MODE_PRIVATE)
        val isRussian = prefs.getString("APP_LANGUAGE", "RU") == "RU"
        
        val view = layoutInflater.inflate(R.layout.dialog_add_device, null)
        val inputIp = view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.inputIp)
        val inputPin = view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.inputPin)
        val ipLayout = view.findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.ipInputLayout)
        val pinLayout = view.findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.pinInputLayout)

        ipLayout.hint = if (isRussian) "IP (например: 192.168.1.23)" else "IP (e.g. 192.168.1.23)"
        pinLayout.hint = if (isRussian) "PIN-код (6 цифр)" else "PIN code (6 digits)"

        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(if (isRussian) "Добавить устройство" else "Add Device")
            .setView(view)
            .setPositiveButton(if (isRussian) "Подключить" else "Connect") { _, _ ->
                vibrate()
                val ip = inputIp.text.toString().trim()
                val pin = inputPin.text.toString().trim()
                
                if (ip.isEmpty() || pin.isEmpty()) {
                    Toast.makeText(this, 
                        if (isRussian) "Введите IP и PIN-код" else "Enter IP and PIN code", 
                        Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                
                if (devices.any { it.ipAddress == ip }) {
                    Toast.makeText(this, 
                        if (isRussian) "Устройство уже добавлено" else "Device already added", 
                        Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                
                // Perform pairing request in background
                Thread {
                    try {
                    val client = OkHttpClient.Builder()
                        .connectTimeout(5, TimeUnit.SECONDS)
                        .readTimeout(5, TimeUnit.SECONDS)
                        .build()
                    
                    val jsonBody = "{\"pin\":\"$pin\"}"
                    val body = okhttp3.RequestBody.create(
                        okhttp3.MediaType.parse("application/json"), jsonBody
                    )
                    val request = Request.Builder()
                        .url("http://$ip:5000/auth/pair")
                        .post(body)
                        .build()
                    
                    val response = client.newCall(request).execute()
                    
                    if (response.isSuccessful) {
                        val responseBody = response.body()?.string() ?: ""
                        val json = com.google.gson.JsonParser.parseString(responseBody).asJsonObject
                        val token = json.get("token")?.asString
                        
                        if (token != null) {
                            runOnUiThread {
                                // Save token for this IP
                                prefs.edit().putString("TOKEN_$ip", token).apply()
                                
                                val newDevice = Device(ip, pcName = if (isRussian) "Загрузка..." else "Loading...")
                                devices.add(newDevice)
                                deviceAdapter.notifyItemInserted(devices.size - 1)
                                saveDevices()
                                
                                Toast.makeText(this, 
                                    if (isRussian) "Устройство подключено!" else "Device connected!", 
                                    Toast.LENGTH_SHORT).show()
                            }
                        }
                    } else if (response.code() == 401) {
                        runOnUiThread {
                            Toast.makeText(this, 
                                if (isRussian) "Неверный PIN-код" else "Wrong PIN code", 
                                Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        runOnUiThread {
                            Toast.makeText(this, 
                                if (isRussian) "Ошибка сервера: ${response.code()}" else "Server error: ${response.code()}", 
                                Toast.LENGTH_SHORT).show()
                        }
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        Toast.makeText(this, 
                            if (isRussian) "Не удалось подключиться к $ip" else "Failed to connect to $ip", 
                            Toast.LENGTH_SHORT).show()
                    }
                }
            }.start()
        }
        .setNegativeButton(if (isRussian) "Отмена" else "Cancel", null)
        .show()
    }

    private fun showDeleteDeviceDialog(device: Device) {
        val prefs = getSharedPreferences("PC_STATS_PREFS", Context.MODE_PRIVATE)
        val isRussian = prefs.getString("APP_LANGUAGE", "RU") == "RU"
        
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(if (isRussian) "Удалить устройство?" else "Delete device?")
            .setMessage(if (isRussian) "Вы уверены?" else "Are you sure?")
            .setPositiveButton(if (isRussian) "Удалить" else "Delete") { _, _ ->
                vibrate()
                val index = devices.indexOf(device)
                if (index != -1) {
                    // Remove the stored auth token for this device
                    prefs.edit().remove("TOKEN_${device.ipAddress}").apply()
                    
                    devices.removeAt(index)
                    deviceAdapter.notifyItemRemoved(index)
                    saveDevices()
                }
            }
            .setNegativeButton(if (isRussian) "Отмена" else "Cancel", null)
            .show()
    }

    private fun saveDevices() {
        val prefs = getSharedPreferences("PC_STATS_PREFS", Context.MODE_PRIVATE)
        prefs.edit().putStringSet("DEVICE_IPS", devices.map { it.ipAddress }.toSet()).apply()
        PCForegroundService.startService(this)
    }

    private fun loadDevices() {
        val prefs = getSharedPreferences("PC_STATS_PREFS", Context.MODE_PRIVATE)
        val isRussian = prefs.getString("APP_LANGUAGE", "RU") == "RU"
        val ipSet = prefs.getStringSet("DEVICE_IPS", emptySet()) ?: emptySet()
        devices.clear()
        ipSet.forEach { devices.add(Device(it, pcName = if (isRussian) "Загрузка..." else "Loading...")) }
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