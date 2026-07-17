package com.example.wcam.viewmodel

import androidx.lifecycle.ViewModel
import com.example.wcam.camera.FlashMode
import com.example.wcam.camera.PhotoParamState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class CameraViewModel : ViewModel() {

    private val _isPhotoMode = MutableStateFlow(true)
    val isPhotoMode: StateFlow<Boolean> = _isPhotoMode.asStateFlow()

    private val _isGridVisible = MutableStateFlow(false)
    val isGridVisible: StateFlow<Boolean> = _isGridVisible.asStateFlow()

    private val _isHistogramVisible = MutableStateFlow(false)
    val isHistogramVisible: StateFlow<Boolean> = _isHistogramVisible.asStateFlow()

    private val _isFormatPanelOpen = MutableStateFlow(false)
    val isFormatPanelOpen: StateFlow<Boolean> = _isFormatPanelOpen.asStateFlow()

    private val _photoParamState = MutableStateFlow(PhotoParamState.default())
    val photoParamState: StateFlow<PhotoParamState> = _photoParamState.asStateFlow()

    private val _currentFlashMode = MutableStateFlow(FlashMode.OFF)
    val currentFlashMode: StateFlow<FlashMode> = _currentFlashMode.asStateFlow()

    private val _zoomRatio = MutableStateFlow(1.0f)
    val zoomRatio: StateFlow<Float> = _zoomRatio.asStateFlow()

    fun setPhotoMode(isPhoto: Boolean) {
        _isPhotoMode.value = isPhoto
    }

    fun setGridVisible(visible: Boolean) {
        _isGridVisible.value = visible
    }

    fun setHistogramVisible(visible: Boolean) {
        _isHistogramVisible.value = visible
    }

    fun setFormatPanelOpen(open: Boolean) {
        _isFormatPanelOpen.value = open
    }

    fun setPhotoParamState(state: PhotoParamState) {
        _photoParamState.value = state
    }

    fun setFlashMode(mode: FlashMode) {
        _currentFlashMode.value = mode
    }

    fun setZoomRatio(ratio: Float) {
        _zoomRatio.value = ratio
    }
}
