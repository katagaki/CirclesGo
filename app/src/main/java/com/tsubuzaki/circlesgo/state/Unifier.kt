package com.tsubuzaki.circlesgo.state

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class Unifier {
    private val _isPresenting = MutableStateFlow(false)
    val isPresenting: StateFlow<Boolean> = _isPresenting

    private val _sidebarPosition = MutableStateFlow(SidebarPosition.LEADING)
    val sidebarPosition: StateFlow<SidebarPosition> = _sidebarPosition

    private val _currentPath = MutableStateFlow<UnifiedPath?>(UnifiedPath.CIRCLES)
    val currentPath: StateFlow<UnifiedPath?> = _currentPath

    private val _sheetPath = MutableStateFlow<List<UnifiedPath>>(emptyList())
    val sheetPath: StateFlow<List<UnifiedPath>> = _sheetPath

    private val _isMyComiketPresenting = MutableStateFlow(false)
    val isMyComiketPresenting: StateFlow<Boolean> = _isMyComiketPresenting

    private val _isGoingToSignOut = MutableStateFlow(false)
    val isGoingToSignOut: StateFlow<Boolean> = _isGoingToSignOut

    fun show() {
        _isPresenting.value = true
    }

    fun hide() {
        _isPresenting.value = false
    }

    fun close() {
        _isPresenting.value = false
        _currentPath.value = null
        _sheetPath.value = emptyList()
    }

    fun setCurrentPath(path: UnifiedPath?) {
        _currentPath.value = path
    }

    fun append(newPath: UnifiedPath) {
        if (_currentPath.value != null) {
            _sheetPath.value = _sheetPath.value + newPath
            _isPresenting.value = true
        } else {
            _currentPath.value = newPath
            show()
        }
    }

    fun popSheetPath() {
        val current = _sheetPath.value
        if (current.isNotEmpty()) {
            _sheetPath.value = current.dropLast(1)
        }
    }

    fun setIsMyComiketPresenting(value: Boolean) {
        _isMyComiketPresenting.value = value
    }

    fun setIsGoingToSignOut(value: Boolean) {
        _isGoingToSignOut.value = value
    }

    fun toggleSidebarPosition() {
        _sidebarPosition.value = if (_sidebarPosition.value == SidebarPosition.LEADING) {
            SidebarPosition.TRAILING
        } else {
            SidebarPosition.LEADING
        }
    }
}
