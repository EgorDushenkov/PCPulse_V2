package com.example.pc.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.example.pc.ui.Device
import com.example.pc.data.PCRepository
import com.example.pc.data.PCRepositoryImpl

class MainViewModel : ViewModel() {
    private val repository: PCRepository = PCRepositoryImpl()

    private val _devices = MutableLiveData<List<Device>>()
    val devices: LiveData<List<Device>> get() = _devices

    private val _pairResult = MutableLiveData<Pair<Boolean, String>>()
    val pairResult: LiveData<Pair<Boolean, String>> get() = _pairResult

    fun loadDevices(deviceList: List<Device>) {
        _devices.value = deviceList
    }

    fun pairDevice(ip: String, pin: String) {
        repository.pairDevice(ip, pin,
            onSuccess = { token ->
                _pairResult.postValue(Pair(true, token))
            },
            onError = { errorMsg ->
                _pairResult.postValue(Pair(false, errorMsg))
            }
        )
    }
}

