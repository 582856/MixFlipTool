package com.parallelc.mixfliptool

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class AppInfo(
    val icon: Drawable,
    val appName: String,
    val packageName: String,
    var scale: String,
    val isSystem: Boolean = false
)

class AppScaleSettingsViewModel(private val context: Context) : ViewModel() {
    private val appScaleManager = AppScaleManager(context)
    private var loadJob: Job? = null

    private val _appList = MutableStateFlow<List<AppInfo>>(emptyList())
    val appList: StateFlow<List<AppInfo>> = _appList

    private val uiPreferences = context.getSharedPreferences("ui_preferences", Context.MODE_PRIVATE)
    private val _showSystemApps = MutableStateFlow(uiPreferences.getBoolean("show_system_apps", false))
    val showSystemApps: StateFlow<Boolean> = _showSystemApps

    fun setShowSystemApps(show: Boolean) {
        _showSystemApps.value = show
        uiPreferences.edit().putBoolean("show_system_apps", show).apply()
    }

    private val _isRefreshing = MutableStateFlow(true)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing

    private val _loadError = MutableStateFlow<String?>(null)
    val loadError: StateFlow<String?> = _loadError

    private val _requestPermission = MutableStateFlow(false)
    val requestPermission: StateFlow<Boolean> = _requestPermission

    fun refreshApplications() {
        loadApplications(forceRefresh = true)
    }

    fun loadApplications(forceRefresh: Boolean = false) {
        if (loadJob?.isActive == true) return
        try {
            val permissionInfo = context.packageManager.getPermissionInfo("com.android.permission.GET_INSTALLED_APPS", 0)
            if (permissionInfo != null && context.checkSelfPermission("com.android.permission.GET_INSTALLED_APPS") != PackageManager.PERMISSION_GRANTED) {
                // Request permission
                _isRefreshing.value = true
                _requestPermission.value = true
                return
            }
        } catch (e : PackageManager.NameNotFoundException) {
            Log.e("mixfliptool", e.stackTraceToString())
        }
        loadApplicationList(forceRefresh)
    }

    fun handlePermissionResult(isGranted: Boolean) {
        _requestPermission.value = false
        if (isGranted) {
            loadApplications()
        } else {
            _isRefreshing.value = false
            _loadError.value = "请先授权应用列表，再下拉刷新。"
            Toast.makeText(context, "获取应用列表失败！", Toast.LENGTH_SHORT).show()
        }
    }

    private fun loadApplicationList(forceRefresh: Boolean) {
        loadJob = viewModelScope.launch {
            _isRefreshing.value = forceRefresh || _appList.value.isEmpty()
            _loadError.value = null
            try {
                val apps = withContext(Dispatchers.IO) { AppListCache.load(context, forceRefresh) }
                val preferences = withContext(Dispatchers.IO) { appScaleManager.getAllPreferences() }
                _appList.value = apps.map { it.copy(scale = preferences[it.packageName] ?: "") }
                    .sortedWith(compareBy({ it.scale.isEmpty() }, { it.appName }))
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                Log.e("mixfliptool", "获取应用列表失败", exception)
                _loadError.value = "应用列表加载失败，请下拉刷新。"
                Toast.makeText(context, "获取应用列表失败！", Toast.LENGTH_SHORT).show()
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    fun settingAppScale(packageName: String, scale: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                appScaleManager.saveAppScale(packageName, scale)
            }
            _appList.value = _appList.value.map { app ->
                if (app.packageName == packageName) {
                    app.copy(scale = scale)
                } else {
                    app
                }
            }.sortedWith(compareBy({ it.scale.isEmpty() }, { it.appName } ))
        }
    }

    fun resetAppScale() {
        viewModelScope.launch {
            val preferences = withContext(Dispatchers.IO) {
                appScaleManager.resetDefaultPreferences()
                appScaleManager.getAllPreferences()
            }
            _appList.value = _appList.value.map { app ->
                app.copy(scale = preferences[app.packageName] ?: "")
            }.sortedWith(compareBy({ it.scale.isEmpty() }, { it.appName } ))
        }
    }
}

class AppScaleSettingsViewModelFactory(private val context: Context) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(AppScaleSettingsViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return AppScaleSettingsViewModel(context.applicationContext) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
