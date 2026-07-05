package com.example.pc.ui

import com.example.pc.*
import com.example.pc.data.*
import com.example.pc.network.*
import com.example.pc.ui.*
import com.example.pc.ui.widgets.*

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat

class SettingsActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val prefs = getSharedPreferences("PC_STATS_PREFS", Context.MODE_PRIVATE)

        findViewById<View>(R.id.btn_back).setOnClickListener {
            vibrate()
            finish()
        }
        
        val vibrationSwitch = findViewById<SwitchCompat>(R.id.switch_vibration)
        vibrationSwitch.isChecked = prefs.getBoolean("VIBRATION_ENABLED", true)
        vibrationSwitch.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("VIBRATION_ENABLED", isChecked).apply()
            if (isChecked) vibrate()
        }

        val deviceNamesSwitch = findViewById<SwitchCompat>(R.id.switch_device_names)
        deviceNamesSwitch.isChecked = prefs.getBoolean("SHOW_DEVICE_NAMES", false)
        deviceNamesSwitch.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("SHOW_DEVICE_NAMES", isChecked).apply()
            vibrate()
        }

        val languageSwitch = findViewById<SwitchCompat>(R.id.switch_language)
        languageSwitch.isChecked = prefs.getString("APP_LANGUAGE", "RU") == "RU"
        languageSwitch.setOnCheckedChangeListener { _, isChecked ->
            val lang = if (isChecked) "RU" else "EN"
            prefs.edit().putString("APP_LANGUAGE", lang).apply()
            vibrate()
            updateLabels()
            updateDefaultMediaSpinner()
        }

        val mediaNotifSwitch = findViewById<SwitchCompat>(R.id.switch_media_notif)
        mediaNotifSwitch.isChecked = prefs.getBoolean("MEDIA_NOTIF_ENABLED", true)
        mediaNotifSwitch.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("MEDIA_NOTIF_ENABLED", isChecked).apply()
            vibrate()
            PCForegroundService.refresh(this)
        }

        updateDefaultMediaSpinner()

        val currentTheme = prefs.getString("APP_THEME", "PURPLE") ?: "PURPLE"
        val btnPurple = findViewById<View>(R.id.btn_theme_purple)
        val btnTurquoise = findViewById<View>(R.id.btn_theme_turquoise)
        val btnOrange = findViewById<View>(R.id.btn_theme_orange)
        val btnGreen = findViewById<View>(R.id.btn_theme_green)

        btnPurple.isSelected = (currentTheme == "PURPLE")
        btnTurquoise.isSelected = (currentTheme == "TURQUOISE")
        btnOrange.isSelected = (currentTheme == "ORANGE")
        btnGreen.isSelected = (currentTheme == "GREEN")

        btnPurple.setOnClickListener { vibrate(); saveTheme("PURPLE") }
        btnTurquoise.setOnClickListener { vibrate(); saveTheme("TURQUOISE") }
        btnOrange.setOnClickListener { vibrate(); saveTheme("ORANGE") }
        btnGreen.setOnClickListener { vibrate(); saveTheme("GREEN") }

        updateLabels()
    }

    private fun updateDefaultMediaSpinner() {
        val prefs = getSharedPreferences("PC_STATS_PREFS", Context.MODE_PRIVATE)
        val ipSet = prefs.getStringSet("DEVICE_IPS", emptySet()) ?: emptySet()
        val ipList = ipSet.toList().sorted()
        val defaultMediaIp = prefs.getString("DEFAULT_MEDIA_IP", "")
        
        val spinner = findViewById<android.widget.Spinner>(R.id.spinner_default_media)
        val options = listOf(if (prefs.getString("APP_LANGUAGE", "RU") == "RU") "Не выбрано" else "None") + ipList
        val adapter = android.widget.ArrayAdapter(this, R.layout.item_spinner, options)
        adapter.setDropDownViewResource(R.layout.item_spinner_dropdown)
        spinner.adapter = adapter
        
        val currentIndex = if (defaultMediaIp.isNullOrEmpty()) 0 else ipList.indexOf(defaultMediaIp) + 1
        if (currentIndex >= 0 && currentIndex < options.size) {
            spinner.setSelection(currentIndex)
        }
        
        spinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                val selectedIp = if (position == 0) null else ipList[position - 1]
                if (prefs.getString("DEFAULT_MEDIA_IP", "") != (selectedIp ?: "")) {
                    prefs.edit().putString("DEFAULT_MEDIA_IP", selectedIp).apply()
                    PCForegroundService.refresh(this@SettingsActivity)
                }
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }
    }

    private fun updateLabels() {
        val prefs = getSharedPreferences("PC_STATS_PREFS", Context.MODE_PRIVATE)
        val isRussian = prefs.getString("APP_LANGUAGE", "RU") == "RU"

        findViewById<TextView>(R.id.settings_title).text = if (isRussian) "Настройки" else "Settings"
        
        findViewById<TextView>(R.id.theme_title).text = if (isRussian) "ТЕМА ОФОРМЛЕНИЯ" else "APP THEME"
        findViewById<TextView>(R.id.theme_desc).text = if (isRussian) "Выберите цветовой акцент интерфейса" else "Choose accent color for the interface"
        
        findViewById<TextView>(R.id.tv_theme_purple).text = if (isRussian) "Фиолетовая" else "Purple"
        findViewById<TextView>(R.id.tv_theme_turquoise).text = if (isRussian) "Бирюзовая" else "Turquoise"
        findViewById<TextView>(R.id.tv_theme_orange).text = if (isRussian) "Оранжевая" else "Orange"
        findViewById<TextView>(R.id.tv_theme_green).text = if (isRussian) "Зеленая" else "Green"

        findViewById<TextView>(R.id.general_title).text = if (isRussian) "ОСНОВНЫЕ" else "GENERAL"

        findViewById<TextView>(R.id.vibration_text).text = if (isRussian) "Виброотдача" else "Haptic Feedback"
        findViewById<TextView>(R.id.vibration_desc).text = if (isRussian) "Легкая вибрация при нажатии на кнопки" else "Light vibration on button clicks"
        
        findViewById<TextView>(R.id.device_names_text).text = if (isRussian) "Названия устройств" else "Device Names"
        findViewById<TextView>(R.id.device_names_desc).text = if (isRussian) "Показывать конкретные названия вместо CPU/GPU" else "Show hardware names instead of CPU/GPU"
        
        findViewById<TextView>(R.id.language_text).text = if (isRussian) "Язык приложения" else "App Language"
        findViewById<TextView>(R.id.language_desc).text = if (isRussian) "Переключение между Русским и Английским" else "Switch between Russian and English"

        findViewById<TextView>(R.id.media_notif_text).text = if (isRussian) "Медиа в уведомлениях" else "Media in Notifications"
        findViewById<TextView>(R.id.media_notif_desc).text = if (isRussian) "Показывать плеер в шторке для активных устройств" else "Show player in shade for active devices"

        findViewById<TextView>(R.id.media_section_title).text = if (isRussian) "УВЕДОМЛЕНИЯ И МЕДИА" else "NOTIFICATIONS & MEDIA"

        findViewById<TextView>(R.id.default_media_title).text = if (isRussian) "Приоритетное устройство в шторке" else "Priority Device in Shade"
        findViewById<TextView>(R.id.default_media_desc).text = if (isRussian) 
            "Если несколько устройств играют музыку, в шторке останется только выбранное" else 
            "If multiple devices are playing music, only the selected one will remain in the shade"
    }

    private fun saveTheme(theme: String) {
        getSharedPreferences("PC_STATS_PREFS", Context.MODE_PRIVATE).edit().putString("APP_THEME", theme).apply()
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        startActivity(intent)
    }
}
