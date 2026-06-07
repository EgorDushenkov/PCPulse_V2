package com.example.pc

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.MediaStore
import android.util.Log
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.gson.Gson
import okhttp3.ResponseBody
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.io.OutputStream

abstract class BaseActivity : AppCompatActivity() {

    protected var currentApi: ApiService? = null
    protected var webSocketManager: WebSocketManager? = null
    protected val gson = Gson()

    open fun onStatsUpdated(stats: PCStats) {}
    open fun onStatusChanged(isOnline: Boolean) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        applyAppTheme()
        super.onCreate(savedInstanceState)
        
        intent.getStringExtra("DEVICE_IP")?.let { ip ->
            getSharedPreferences("PC_STATS_PREFS", Context.MODE_PRIVATE)
                .edit()
                .putString("SERVER_IP", ip)
                .apply()

            currentApi = RetrofitClient.getClient(ip)
            
            webSocketManager = WebSocketManager(gson, { isOnline ->
                runOnUiThread { onStatusChanged(isOnline) }
            }) { stats ->
                onStatsUpdated(stats)
            }
            webSocketManager?.connect("ws://$ip:5000/ws")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        webSocketManager?.disconnect()
    }

    protected fun applyAppTheme() {
        val prefs = getSharedPreferences("PC_STATS_PREFS", Context.MODE_PRIVATE)
        val theme = prefs.getString("APP_THEME", "PURPLE")
        val themeRes = when (theme) {
            "TURQUOISE" -> R.style.AppTheme_Turquoise
            "ORANGE" -> R.style.AppTheme_Orange
            "GREEN" -> R.style.AppTheme_Green
            else -> R.style.AppTheme_Purple
        }
        setTheme(themeRes)
    }

    fun vibrate(duration: Long = 10) {
        val prefs = getSharedPreferences("PC_STATS_PREFS", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("VIBRATION_ENABLED", true)) return

        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vibratorManager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(duration)
        }
    }

    protected fun showScreenshotDialog() {
        val isRussian = getSharedPreferences("PC_STATS_PREFS", Context.MODE_PRIVATE).getString("APP_LANGUAGE", "RU") == "RU"
        Toast.makeText(this, if (isRussian) "Загрузка скриншота..." else "Loading screenshot...", Toast.LENGTH_SHORT).show()
        currentApi?.getScreenshot()?.enqueue(object : Callback<ResponseBody> {
            override fun onResponse(call: Call<ResponseBody>, response: Response<ResponseBody>) {
                if (response.isSuccessful) {
                    val body = response.body()
                    if (body != null) {
                        Thread {
                            try {
                                val bytes = body.bytes()
                                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                                
                                runOnUiThread {
                                    if (bitmap != null) {
                                        val view = layoutInflater.inflate(R.layout.dialog_screenshot, null)
                                        view.findViewById<ImageView>(R.id.screenshotImage).setImageBitmap(bitmap)
                                        AlertDialog.Builder(this@BaseActivity)
                                            .setTitle(if (isRussian) "Скриншот ПК" else "PC Screenshot")
                                            .setView(view)
                                            .setPositiveButton(if (isRussian) "Закрыть" else "Close", null)
                                            .setNeutralButton(if (isRussian) "Сохранить" else "Save") { _, _ ->
                                                saveBitmapToGallery(bitmap)
                                            }
                                            .show()
                                    }
                                }
                            } catch (e: Exception) {
                                Log.e("Screenshot", "Error", e)
                            }
                        }.start()
                    }
                }
            }
            override fun onFailure(call: Call<ResponseBody>, t: Throwable) {}
        })
    }

    private fun saveBitmapToGallery(bitmap: Bitmap) {
        val isRussian = getSharedPreferences("PC_STATS_PREFS", Context.MODE_PRIVATE).getString("APP_LANGUAGE", "RU") == "RU"
        val filename = "PC_Screenshot_${System.currentTimeMillis()}.jpg"
        var fos: OutputStream? = null
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentResolver?.also { resolver ->
                    val contentValues = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                        put(MediaStore.MediaColumns.MIME_TYPE, "image/jpg")
                        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES)
                    }
                    val imageUri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
                    fos = imageUri?.let { resolver.openOutputStream(it) }
                }
            } else {
                val imagesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
                val image = java.io.File(imagesDir, filename)
                fos = java.io.FileOutputStream(image)
            }
            fos?.use {
                bitmap.compress(Bitmap.CompressFormat.JPEG, 100, it)
                Toast.makeText(this, if (isRussian) "Сохранено в галерею" else "Saved to gallery", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {}
    }

    protected fun sendMixerVolume(appName: String, volume: Int) {
        webSocketManager?.sendCommand("set_mixer_volume", mapOf("app" to appName, "vol" to volume))
    }

    protected fun sendMicMute(mute: Boolean) {
        webSocketManager?.sendCommand("set_mic_mute", mapOf("mute" to if (mute) 1 else 0))
    }

    protected fun killProcess(pid: Int, onComplete: () -> Unit = {}) {
        webSocketManager?.sendCommand("kill_process", mapOf("pid" to pid))
        onComplete()
    }

    protected fun sendShutdownCommand() {
        val isRussian = getSharedPreferences("PC_STATS_PREFS", Context.MODE_PRIVATE).getString("APP_LANGUAGE", "RU") == "RU"
        val action = if (isRussian) "выключить" else "shutdown"
        val title = if (isRussian) "Выключение" else "Shutdown"
        showPowerActionDialog(action, title) { webSocketManager?.sendCommand("shutdown") }
    }

    protected fun sendSleepCommand() {
        val isRussian = getSharedPreferences("PC_STATS_PREFS", Context.MODE_PRIVATE).getString("APP_LANGUAGE", "RU") == "RU"
        val action = if (isRussian) "отправить в спящий режим" else "sleep"
        val title = if (isRussian) "Сон" else "Sleep"
        showPowerActionDialog(action, title) { webSocketManager?.sendCommand("sleep") }
    }

    private fun showPowerActionDialog(actionName: String, title: String, onConfirm: () -> Unit) {
        val isRussian = getSharedPreferences("PC_STATS_PREFS", Context.MODE_PRIVATE).getString("APP_LANGUAGE", "RU") == "RU"
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(if (isRussian) "Вы уверены, что хотите $actionName ПК?" else "Are you sure you want to $actionName the PC?")
            .setPositiveButton(if (isRussian) "Да" else "Yes") { _, _ -> onConfirm() }
            .setNegativeButton(if (isRussian) "Нет" else "No", null)
            .show()
    }
}
