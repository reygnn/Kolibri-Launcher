package com.github.reygnn.kolibri_launcher.domain.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
enum class MenuContext : Parcelable {
    HOME_SCREEN,
    APP_DRAWER
}