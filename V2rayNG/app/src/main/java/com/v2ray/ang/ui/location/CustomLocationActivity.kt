package com.v2ray.ang.ui.location

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.extension.toastError
import com.v2ray.ang.handler.location.RegionalSettingsAdapter
import com.v2ray.ang.ui.base.BaseComponentActivity
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.launch

class CustomLocationActivity : BaseComponentActivity() {

    private val viewModel: CustomLocationViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The user may have changed mock-location, VPN or date settings while away: re-read on return.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                viewModel.onAction(CustomLocationAction.RefreshEnvironment)
            }
        }
    }

    private fun openSettings(intent: Intent) {
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            reportSettingsFailure(e)
        } catch (e: SecurityException) {
            reportSettingsFailure(e)
        }
    }

    private fun reportSettingsFailure(error: RuntimeException) {
        LogUtil.e(AppConfig.TAG, "CustomLocation: cannot open a system settings screen", error)
        toastError(R.string.cl_cannot_open_settings)
    }

    @Composable
    override fun ScreenContent() {
        CustomLocationScreen(
            viewModel = viewModel,
            onBackClick = { finish() },
            onOpenDeveloperSettings = { openSettings(RegionalSettingsAdapter.developerSettingsIntent()) },
            onOpenDateSettings = { openSettings(RegionalSettingsAdapter.dateSettingsIntent()) },
            onOpenVpnSettings = { openSettings(Intent(Settings.ACTION_VPN_SETTINGS)) }
        )
    }
}
