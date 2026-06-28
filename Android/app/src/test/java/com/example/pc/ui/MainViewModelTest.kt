package com.example.pc.ui

import org.junit.Assert.*
import org.junit.Test
import com.example.pc.ui.Device

class MainViewModelTest {

    @Test
    fun 	est load devices updates livedata() {
        val viewModel = MainViewModel()
        val devices = listOf(Device("192.168.1.1", "Test PC"))
        viewModel.loadDevices(devices)
        
        assertEquals(devices, viewModel.devices.value)
    }
}

