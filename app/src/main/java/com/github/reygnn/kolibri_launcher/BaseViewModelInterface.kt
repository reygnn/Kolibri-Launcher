package com.github.reygnn.kolibri_launcher

import kotlinx.coroutines.flow.SharedFlow

interface BaseViewModelInterface {
    val eventFlow: SharedFlow<UiEvent>
}