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
        }

        findViewById<Button>(R.id.btn_theme_purple).setOnClickListener { vibrate(); saveTheme("PURPLE") }
        findViewById<Button>(R.id.btn_theme_turquoise).setOnClickListener { vibrate(); saveTheme("TURQUOISE") }
        findViewById<Button>(R.id.btn_theme_orange).setOnClickListener { vibrate(); saveTheme("ORANGE") }
        findViewById<Button>(R.id.btn_theme_green).setOnClickListener { vibrate(); saveTheme("GREEN") }

        updateLabels()
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
    }

    private fun saveTheme(theme: String) {
        getSharedPreferences("PC_STATS_PREFS", Context.MODE_PRIVATE).edit().putString("APP_THEME", theme).apply()
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        startActivity(intent)
    }
}
