package com.example.pc

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat

class SettingsActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val prefs = getSharedPreferences("PC_STATS_PREFS", Context.MODE_PRIVATE)
        
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

        findViewById<Button>(R.id.btn_theme_purple).setOnClickListener { vibrate(); saveTheme("PURPLE") }
        findViewById<Button>(R.id.btn_theme_turquoise).setOnClickListener { vibrate(); saveTheme("TURQUOISE") }
        findViewById<Button>(R.id.btn_theme_orange).setOnClickListener { vibrate(); saveTheme("ORANGE") }
        findViewById<Button>(R.id.btn_theme_green).setOnClickListener { vibrate(); saveTheme("GREEN") }

        updateLabels()
    }

    private fun updateDefaultMediaSpinner() {
        val prefs = getSharedPreferences("PC_STATS_PREFS", Context.MODE_PRIVATE)
        val ipSet = prefs.getStringSet("DEVICE_IPS", emptySet()) ?: emptySet()
        val ipList = ipSet.toList().sorted()
        val defaultMediaIp = prefs.getString("DEFAULT_MEDIA_IP", "")
        
        val spinner = findViewById<android.widget.Spinner>(R.id.spinner_default_media)
        val options = listOf(if (prefs.getString("APP_LANGUAGE", "RU") == "RU") "Не выбрано" else "None") + ipList
        val adapter = android.widget.ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, options)
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
        findViewById<TextView>(R.id.theme_title).text = if (isRussian) "Тема приложения" else "App Theme"
        
        findViewById<Button>(R.id.btn_theme_purple).text = if (isRussian) "Фиолетовая тема" else "Purple Theme"
        findViewById<Button>(R.id.btn_theme_turquoise).text = if (isRussian) "Бирюзовая тема" else "Turquoise Theme"
        findViewById<Button>(R.id.btn_theme_orange).text = if (isRussian) "Оранжевая тема" else "Orange Theme"
        findViewById<Button>(R.id.btn_theme_green).text = if (isRussian) "Зеленая тема" else "Green Theme"

        findViewById<TextView>(R.id.vibration_text).text = if (isRussian) "Виброотдача" else "Haptic Feedback"
        findViewById<TextView>(R.id.vibration_desc).text = if (isRussian) "Легкая вибрация при нажатии на кнопки" else "Light vibration on button clicks"
        
        findViewById<TextView>(R.id.device_names_text).text = if (isRussian) "Названия устройств" else "Device Names"
        findViewById<TextView>(R.id.device_names_desc).text = if (isRussian) "Показывать названия железа вместо CPU/GPU" else "Show hardware names instead of CPU/GPU"
        
        findViewById<TextView>(R.id.language_text).text = if (isRussian) "Язык приложения" else "App Language"
        findViewById<TextView>(R.id.language_desc).text = if (isRussian) "Переключение между RU и EN" else "Switch between RU and EN"

        findViewById<TextView>(R.id.media_notif_text).text = if (isRussian) "Медиа в уведомлениях" else "Media in Notifications"
        findViewById<TextView>(R.id.media_notif_desc).text = if (isRussian) "Показывать плеер в шторке для активных устройств" else "Show player in shade for active devices"

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
