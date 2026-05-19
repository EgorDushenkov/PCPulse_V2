package com.example.pc

import android.content.Context
import com.google.gson.annotations.SerializedName

data class PCStats(
    val pc_name: String,
    val time: String,
    val uptime: Float,
    val cpu: CpuData,
    val gpu: List<GpuData>,
    val ram: RamData,
    val disks: List<DiskData>,
    val network: NetworkData,
    val fans: List<FanData>,
    val procs: List<ProcessData>,
    val volume: Int,
    val mic_muted: Boolean = false,
    val audio_sessions: List<MixerSession>,
    val media: MediaData? = null,
    val active_app: String? = null,
    val running_apps: List<String> = emptyList()
)

data class MediaData(
    val title: String,
    val artist: String,
    val status: Int
)

data class CpuData(val name: String, val usage: Double, val freq: Double, val temp: Any)
data class GpuData(val name: String, val load: Int, val temp: Float, val mem_p: Int)
data class RamData(val usage: Double, val total: Float, val used: Float, val free: Float)
data class DiskData(val dev: String, val total: Float, val used: Float, val percent: Double)
data class NetworkData(val down_kbps: Double, val up_kbps: Double)
data class FanData(val name: String, val rpm: Int)
data class ProcessData(val pid: Int, val name: String, val cpu: Double)
data class MixerSession(val name: String, val volume: Int)

enum class WidgetType {
    @SerializedName("controls") CONTROLS,
    @SerializedName("audio_mixer") AUDIO_MIXER,
    @SerializedName("storage") STORAGE,
    @SerializedName("cooling") COOLING,
    @SerializedName("top_processes") TOP_PROCESSES,
    @SerializedName("cpu") CPU,
    @SerializedName("ram") RAM,
    @SerializedName("gpu") GPU,
    @SerializedName("network") NETWORK,
    @SerializedName("action_button") ACTION_BUTTON,
    @SerializedName("media_player") MEDIA_PLAYER
}

data class WidgetConfig(
    @SerializedName("type") val type: WidgetType,
    @SerializedName("x_position") val x: Int,
    @SerializedName("y_position") val y: Int,
    @SerializedName("width_span") val width: Int,
    @SerializedName("height_span") val height: Int,
    @SerializedName("label") val label: String? = null,
    @SerializedName("action") val action: String? = null,
    @SerializedName("use_icon") val useIcon: Boolean = false
)

data class DashboardLayout(
    @SerializedName("dashboard_name") val name: String,
    @SerializedName("widgets") val widgets: List<WidgetConfig>
)

interface UpdatableWidget {
    fun updateData(stats: PCStats)
    fun updateConfig(config: WidgetConfig) {}
}

object Localization {
    fun get(context: Context, key: String): String {
        val prefs = context.getSharedPreferences("PC_STATS_PREFS", Context.MODE_PRIVATE)
        val isRussian = prefs.getString("APP_LANGUAGE", "RU") == "RU"
        
        return when (key) {
            "CPU" -> if (isRussian) "ПРОЦЕССОР" else "CPU"
            "GPU" -> if (isRussian) "ВИДЕОКАРТА" else "GPU"
            "RAM" -> if (isRussian) "ОЗУ" else "RAM"
            "STORAGE" -> if (isRussian) "ХРАНИЛИЩЕ" else "STORAGE"
            "COOLING" -> if (isRussian) "ОХЛАЖДЕНИЕ" else "COOLING"
            "NETWORK" -> if (isRussian) "СЕТЬ" else "NETWORK"
            "PROCESSES" -> if (isRussian) "ПРОЦЕССЫ" else "PROCESSES"
            "AUDIO_MIXER" -> if (isRussian) "АУДИО МИКШЕР" else "AUDIO MIXER"
            "NO_FANS" -> if (isRussian) "Вентиляторы не найдены" else "No fans detected"
            "NO_MEDIA" -> if (isRussian) "Нет медиа" else "No Media"
            else -> key
        }
    }
}
