package com.turbocal.app.update

import com.turbocal.core.update.ReleaseInfo
import java.io.File

/** The one update state (AGENTS.md §9), shared by the launch check and the Software update screen. */
sealed class UpdateStatus {
    object Idle : UpdateStatus()
    object Checking : UpdateStatus()
    object UpToDate : UpdateStatus()
    object NoRelease : UpdateStatus()
    data class NoAsset(val tag: String) : UpdateStatus()
    data class Available(val info: ReleaseInfo) : UpdateStatus()
    data class Downloading(val info: ReleaseInfo, val percent: Int) : UpdateStatus()
    data class Downloaded(val info: ReleaseInfo, val file: File) : UpdateStatus()
    data class Failed(val message: String, val info: ReleaseInfo? = null) : UpdateStatus()
}
