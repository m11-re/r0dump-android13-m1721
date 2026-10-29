package com.android.r0dumpmanager

import android.Manifest
import android.app.NotificationManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.android.r0dumpmanager.compat.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import com.android.r0dumpmanager.compat.calculateWindowWidthSizeClass
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.AbstractSavedStateViewModelFactory
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import com.android.r0dumpmanager.app.R0dumpManagerViewModel
import com.android.r0dumpmanager.ui.R0dumpManagerScreen
import com.android.r0dumpmanager.ui.theme.R0dumpManagerTheme

class MainActivity : ComponentActivity() {
    private lateinit var viewModel: R0dumpManagerViewModel
    // A plain Int state is enough here: the field only needs to invalidate the composition when the
    // permission result arrives.  The newer mutableIntStateOf exists to avoid boxing, which is not
    // reachable from a non-composable property initialiser, so the standard delegate is used.
    private var notificationPermissionEpoch by androidx.compose.runtime.mutableStateOf(0)
    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        notificationPermissionEpoch++
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val factory = object : AbstractSavedStateViewModelFactory(this, intent.extras) {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(key: String, modelClass: Class<T>, handle: SavedStateHandle): T {
                if (modelClass.isAssignableFrom(R0dumpManagerViewModel::class.java)) {
                    return R0dumpManagerViewModel(application, handle) as T
                }
                error("Unknown ViewModel ${modelClass.name}")
            }
        }
        viewModel = ViewModelProvider(this, factory)[R0dumpManagerViewModel::class.java]
        setContent {
            notificationPermissionEpoch
            R0dumpManagerTheme {
                R0dumpManagerScreen(
                    viewModel = viewModel,
                    windowWidthSizeClass = calculateWindowWidthSizeClass(),
                    notificationsEnabled = getSystemService(NotificationManager::class.java)
                        .areNotificationsEnabled(),
                    onRequestNotifications = {
                        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    },
                    onExit = ::finish,
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        viewModel.setForeground(true)
    }

    override fun onStop() {
        viewModel.setForeground(false)
        super.onStop()
    }
}
