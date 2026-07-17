package com.example.wcam.camera

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner

class CameraLifecycleObserver(
    private val onResumeAction: () -> Unit,
    private val onPauseAction: () -> Unit
) : DefaultLifecycleObserver {

    override fun onResume(owner: LifecycleOwner) {
        onResumeAction()
    }

    override fun onPause(owner: LifecycleOwner) {
        onPauseAction()
    }
}
