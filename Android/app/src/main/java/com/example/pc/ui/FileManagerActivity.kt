package com.example.pc.ui

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.pc.R
import com.example.pc.network.ApiService
import com.example.pc.network.FsCopyRequest
import com.example.pc.network.FsCopyResponse
import com.example.pc.network.FsItem
import com.example.pc.network.RetrofitClient
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import okhttp3.MediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.io.File
import java.io.FileOutputStream

class FileManagerActivity : BaseActivity() {

    private lateinit var rvFiles: RecyclerView
    private lateinit var btnBack: View
    private lateinit var tvHeaderTitle: TextView
    private lateinit var tvCurrentPath: TextView
    private lateinit var progressBar: View
    private lateinit var tvLoadingText: TextView
    private lateinit var fabAction: ExtendedFloatingActionButton
    private lateinit var fabDownload: ExtendedFloatingActionButton
    private lateinit var btnUpload: View
    
    private lateinit var api: ApiService
    private var currentPath: String = ""
    private var deviceIp: String = ""
    private var token: String = ""
    private var isRussian: Boolean = true
    
    private val items = mutableListOf<FsItem>()
    private val selectedPaths = mutableSetOf<String>()
    
    private var isCopyMode = false
    private val pathsToCopy = mutableListOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_file_manager)

        deviceIp = intent.getStringExtra("DEVICE_IP") ?: return finish()
        val prefs = getSharedPreferences("PC_STATS_PREFS", Context.MODE_PRIVATE)
        token = prefs.getString("TOKEN_$deviceIp", "") ?: ""
        isRussian = prefs.getString("APP_LANGUAGE", "RU") == "RU"

        api = RetrofitClient.getClient(deviceIp, token)

        rvFiles = findViewById(R.id.rvFiles)
        btnBack = findViewById(R.id.btn_back)
        tvHeaderTitle = findViewById(R.id.tvHeaderTitle)
        tvCurrentPath = findViewById(R.id.tvCurrentPath)
        progressBar = findViewById(R.id.progressBar)
        tvLoadingText = findViewById(R.id.tvLoadingText)
        fabAction = findViewById(R.id.fabAction)
        fabDownload = findViewById(R.id.fabDownload)
        btnUpload = findViewById(R.id.btnUpload)

        tvHeaderTitle.text = if (isRussian) "Файловый менеджер" else "File Manager"
        tvLoadingText.text = if (isRussian) "Загрузка..." else "Loading..."

        rvFiles.layoutManager = LinearLayoutManager(this)
        rvFiles.adapter = FileAdapter()

        val uploadLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) {
                doUpload(uri)
            }
        }

        btnUpload.setOnClickListener {
            vibrate()
            uploadLauncher.launch("*/*")
        }

        fabDownload.setOnClickListener {
            vibrate()
            doDownload()
        }

        btnBack.setOnClickListener {
            vibrate()
            onBackPressedDispatcher.onBackPressed()
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (isCopyMode) {
                    isCopyMode = false
                    pathsToCopy.clear()
                    updateFab()
                    return
                }
                if (selectedPaths.isNotEmpty()) {
                    selectedPaths.clear()
                    updateFab()
                    rvFiles.adapter?.notifyDataSetChanged()
                    return
                }
                if (currentPath.isNotEmpty()) {
                    goUp()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        fabAction.setOnClickListener {
            vibrate()
            if (isCopyMode) {
                doCopy()
            } else {
                startCopyMode()
            }
        }

        loadPath("")
    }

    private fun loadPath(path: String) {
        progressBar.visibility = View.VISIBLE
        api.listFs(path).enqueue(object : Callback<List<FsItem>> {
            override fun onResponse(call: Call<List<FsItem>>, response: Response<List<FsItem>>) {
                progressBar.visibility = View.GONE
                if (response.isSuccessful) {
                    currentPath = path
                    tvCurrentPath.text = if (path.isEmpty()) (if (isRussian) "КОРНЕВАЯ ПАПКА (ROOT)" else "ROOT") else path
                    items.clear()
                    response.body()?.let { items.addAll(it) }
                    selectedPaths.clear()
                    updateFab()
                    rvFiles.adapter?.notifyDataSetChanged()
                } else {
                    Toast.makeText(this@FileManagerActivity, "Error: ${response.code()}", Toast.LENGTH_SHORT).show()
                }
            }
            override fun onFailure(call: Call<List<FsItem>>, t: Throwable) {
                progressBar.visibility = View.GONE
                Toast.makeText(this@FileManagerActivity, "Failed: ${t.message}", Toast.LENGTH_SHORT).show()
            }
        })
    }

    private fun goUp() {
        if (currentPath.isEmpty()) return
        val lastSlash = currentPath.lastIndexOf('\\')
        if (lastSlash > 0) {
            loadPath(currentPath.substring(0, lastSlash))
        } else {
            loadPath("")
        }
    }

    private fun updateFab() {
        btnUpload.visibility = if (currentPath.isNotEmpty()) View.VISIBLE else View.GONE
        fabDownload.visibility = View.GONE
        
        if (isCopyMode) {
            fabAction.visibility = View.VISIBLE
            fabAction.text = if (isRussian) "Вставить сюда" else "Paste here"
            fabAction.setIconResource(R.drawable.ic_paste)
        } else if (selectedPaths.isNotEmpty()) {
            fabAction.visibility = View.VISIBLE
            fabAction.text = if (isRussian) "Копировать (${selectedPaths.size})" else "Copy (${selectedPaths.size})"
            fabAction.setIconResource(R.drawable.ic_copy)
            
            if (selectedPaths.size == 1) {
                val p = selectedPaths.first()
                val itm = items.find { it.path == p }
                if (itm != null && !itm.isDir) {
                    fabDownload.visibility = View.VISIBLE
                    fabDownload.text = if (isRussian) "Скачать" else "Download"
                }
            }
        } else {
            fabAction.visibility = View.GONE
        }
    }

    private fun startCopyMode() {
        pathsToCopy.clear()
        pathsToCopy.addAll(selectedPaths)
        selectedPaths.clear()
        isCopyMode = true
        updateFab()
        rvFiles.adapter?.notifyDataSetChanged()
    }

    private fun doCopy() {
        if (currentPath.isEmpty()) {
            Toast.makeText(this, if (isRussian) "Нельзя вставить в корневую папку" else "Cannot paste in ROOT", Toast.LENGTH_SHORT).show()
            return
        }
        val req = FsCopyRequest(pathsToCopy.toList(), currentPath)
        progressBar.visibility = View.VISIBLE
        api.copyFs(req).enqueue(object : Callback<FsCopyResponse> {
            override fun onResponse(call: Call<FsCopyResponse>, response: Response<FsCopyResponse>) {
                progressBar.visibility = View.GONE
                if (response.isSuccessful) {
                    Toast.makeText(this@FileManagerActivity, if (isRussian) "Копирование запущено в фоне" else "Copy started in background", Toast.LENGTH_SHORT).show()
                    isCopyMode = false
                    pathsToCopy.clear()
                    updateFab()
                    loadPath(currentPath)
                } else {
                    Toast.makeText(this@FileManagerActivity, "Error ${response.code()}", Toast.LENGTH_SHORT).show()
                }
            }
            override fun onFailure(call: Call<FsCopyResponse>, t: Throwable) {
                progressBar.visibility = View.GONE
                Toast.makeText(this@FileManagerActivity, "Fail: ${t.message}", Toast.LENGTH_SHORT).show()
            }
        })
    }

    private fun doDownload() {
        val p = selectedPaths.firstOrNull() ?: return
        val itm = items.find { it.path == p } ?: return
        progressBar.visibility = View.VISIBLE
        api.downloadFs(p).enqueue(object : Callback<ResponseBody> {
            override fun onResponse(call: Call<ResponseBody>, response: Response<ResponseBody>) {
                if (response.isSuccessful) {
                    val body = response.body()
                    if (body != null) {
                        Thread {
                            try {
                                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                                    val contentValues = android.content.ContentValues().apply {
                                        put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, itm.name)
                                        put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS)
                                    }
                                    val uri = contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                                    if (uri != null) {
                                        contentResolver.openOutputStream(uri)?.use { out ->
                                            body.byteStream().use { input ->
                                                input.copyTo(out)
                                            }
                                        }
                                    } else {
                                        throw Exception("Failed to create MediaStore entry")
                                    }
                                } else {
                                    val downloads = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
                                    downloads.mkdirs()
                                    val f = File(downloads, itm.name)
                                    var i = 1
                                    var dest = f
                                    while (dest.exists()) {
                                        val nameWithoutExt = itm.name.substringBeforeLast(".")
                                        val ext = if (itm.name.contains(".")) "." + itm.name.substringAfterLast(".") else ""
                                        dest = File(downloads, "$nameWithoutExt ($i)$ext")
                                        i++
                                    }
                                    val out = FileOutputStream(dest)
                                    body.byteStream().use { input ->
                                        input.copyTo(out)
                                    }
                                    out.close()
                                }
                                runOnUiThread {
                                    progressBar.visibility = View.GONE
                                    Toast.makeText(this@FileManagerActivity, if (isRussian) "Сохранено в загрузки (Downloads)" else "Saved to Downloads", Toast.LENGTH_SHORT).show()
                                    selectedPaths.clear()
                                    updateFab()
                                    rvFiles.adapter?.notifyDataSetChanged()
                                }
                            } catch (e: Exception) {
                                val err = e.localizedMessage ?: e.toString()
                                runOnUiThread {
                                    progressBar.visibility = View.GONE
                                    Toast.makeText(this@FileManagerActivity, if (isRussian) "Ошибка сохранения: $err" else "Error saving: $err", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }.start()
                    } else {
                        progressBar.visibility = View.GONE
                    }
                } else {
                    progressBar.visibility = View.GONE
                    Toast.makeText(this@FileManagerActivity, "Error ${response.code()}", Toast.LENGTH_SHORT).show()
                }
            }
            override fun onFailure(call: Call<ResponseBody>, t: Throwable) {
                progressBar.visibility = View.GONE
                val err = t.localizedMessage ?: t.toString()
                Toast.makeText(this@FileManagerActivity, "Fail: $err", Toast.LENGTH_SHORT).show()
            }
        })
    }

    private fun doUpload(uri: android.net.Uri) {
        if (currentPath.isEmpty()) return
        progressBar.visibility = View.VISIBLE
        try {
            val ins = contentResolver.openInputStream(uri)
            if (ins != null) {
                val tempFile = File.createTempFile("upload", ".tmp", cacheDir)
                val os = FileOutputStream(tempFile)
                ins.copyTo(os)
                os.close()
                ins.close()
                
                var filename = "uploaded_file"
                contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        if (nameIndex != -1) {
                            filename = cursor.getString(nameIndex)
                        }
                    }
                }
                
                val reqFile = RequestBody.create(MediaType.parse("*/*"), tempFile)
                val part = MultipartBody.Part.createFormData("file", filename, reqFile)
                
                api.uploadFs(currentPath, part).enqueue(object : Callback<ResponseBody> {
                    override fun onResponse(call: Call<ResponseBody>, response: Response<ResponseBody>) {
                        progressBar.visibility = View.GONE
                        if (response.isSuccessful) {
                            Toast.makeText(this@FileManagerActivity, if (isRussian) "Файл успешно загружен" else "Uploaded successfully", Toast.LENGTH_SHORT).show()
                            loadPath(currentPath)
                        } else {
                            Toast.makeText(this@FileManagerActivity, "Error ${response.code()}", Toast.LENGTH_SHORT).show()
                        }
                    }
                    override fun onFailure(call: Call<ResponseBody>, t: Throwable) {
                        progressBar.visibility = View.GONE
                        Toast.makeText(this@FileManagerActivity, if (isRussian) "Ошибка загрузки: ${t.message}" else "Upload fail: ${t.message}", Toast.LENGTH_SHORT).show()
                    }
                })
            } else {
                progressBar.visibility = View.GONE
            }
        } catch (e: Exception) {
            progressBar.visibility = View.GONE
            Toast.makeText(this, if (isRussian) "Ошибка загрузки: ${e.message}" else "Upload error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    inner class FileAdapter : RecyclerView.Adapter<FileAdapter.VH>() {
        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val ivIcon: ImageView = v.findViewById(R.id.ivIcon)
            val tvName: TextView = v.findViewById(R.id.tvName)
            val tvDetails: TextView = v.findViewById(R.id.tvDetails)
            val cbSelect: CheckBox = v.findViewById(R.id.cbSelect)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            return VH(LayoutInflater.from(parent.context).inflate(R.layout.item_file, parent, false))
        }

        override fun getItemCount() = items.size + if (currentPath.isNotEmpty()) 1 else 0

        private fun getItem(position: Int): FsItem? {
            if (currentPath.isNotEmpty()) {
                if (position == 0) return null
                return items[position - 1]
            }
            return items[position]
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = getItem(position)
            if (item == null) {
                holder.tvName.text = if (isRussian) "Назад / Родительская папка" else "Parent Folder (..)"
                holder.ivIcon.setImageResource(R.drawable.ic_folder_up)
                holder.tvDetails.text = if (isRussian) "Вернуться на уровень вверх" else "Go up one level"
                holder.cbSelect.visibility = View.GONE
                holder.itemView.setOnClickListener { vibrate(); goUp() }
                holder.itemView.setOnLongClickListener { true }
                return
            }

            holder.tvName.text = item.name
            holder.ivIcon.setImageResource(if (item.isDir) R.drawable.ic_folder else R.drawable.ic_file)
            holder.tvDetails.text = if (item.isDir) (if (isRussian) "Папка" else "Folder") else "${item.size / 1024} KB"
            
            holder.cbSelect.visibility = if (selectedPaths.isNotEmpty() && !isCopyMode) View.VISIBLE else View.GONE
            holder.cbSelect.isChecked = selectedPaths.contains(item.path)

            holder.itemView.setOnClickListener {
                if (selectedPaths.isNotEmpty() && !isCopyMode) {
                    toggleSelect(item.path)
                } else {
                    if (item.isDir) {
                        vibrate()
                        loadPath(item.path)
                    }
                }
            }
            
            holder.itemView.setOnLongClickListener {
                vibrate()
                if (!isCopyMode) {
                    toggleSelect(item.path)
                }
                true
            }
            
            holder.cbSelect.setOnClickListener {
                toggleSelect(item.path)
            }
        }

        private fun toggleSelect(p: String) {
            if (selectedPaths.contains(p)) selectedPaths.remove(p) else selectedPaths.add(p)
            updateFab()
            notifyDataSetChanged()
        }
    }
}
