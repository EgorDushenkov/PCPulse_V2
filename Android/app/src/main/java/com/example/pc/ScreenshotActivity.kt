package com.example.pc

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.Toast
import okhttp3.ResponseBody
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.io.OutputStream

class ScreenshotActivity : BaseActivity() {

    private lateinit var imageView: ImageView
    private lateinit var progressBar: ProgressBar
    private var currentBitmap: Bitmap? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_screenshot)

        imageView = findViewById(R.id.screenshotImageView)
        progressBar = findViewById(R.id.screenshotProgressBar)
        val btnSave = findViewById<Button>(R.id.btnSaveScreenshot)
        val btnClose = findViewById<Button>(R.id.btnCloseScreenshot)

        val isRussian = getSharedPreferences("PC_STATS_PREFS", MODE_PRIVATE).getString("APP_LANGUAGE", "RU") == "RU"
        btnSave.text = if (isRussian) "Сохранить" else "Save"
        btnClose.text = if (isRussian) "Закрыть" else "Close"

        btnSave.setOnClickListener {
            currentBitmap?.let { saveBitmapToGallery(it) }
        }
        btnClose.setOnClickListener { finish() }

        loadScreenshot()
    }

    private fun loadScreenshot() {
        if (currentApi == null) {
            progressBar.visibility = View.GONE
            val isRussian = getSharedPreferences("PC_STATS_PREFS", MODE_PRIVATE).getString("APP_LANGUAGE", "RU") == "RU"
            Toast.makeText(this, if (isRussian) "Ошибка: IP не задан" else "Error: IP not set", Toast.LENGTH_SHORT).show()
            return
        }
        progressBar.visibility = View.VISIBLE
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
                                    progressBar.visibility = View.GONE
                                    if (bitmap != null) {
                                        currentBitmap = bitmap
                                        imageView.setImageBitmap(bitmap)
                                    }
                                }
                            } catch (e: Exception) {
                                runOnUiThread { progressBar.visibility = View.GONE }
                            }
                        }.start()
                    } else {
                        progressBar.visibility = View.GONE
                    }
                } else {
                    progressBar.visibility = View.GONE
                }
            }

            override fun onFailure(call: Call<ResponseBody>, t: Throwable) {
                progressBar.visibility = View.GONE
            }
        })
    }

    private fun saveBitmapToGallery(bitmap: Bitmap) {
        val isRussian = getSharedPreferences("PC_STATS_PREFS", MODE_PRIVATE).getString("APP_LANGUAGE", "RU") == "RU"
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
}
