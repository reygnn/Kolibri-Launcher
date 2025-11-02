package com.github.reygnn.kolibri_launcher.ui

import kotlinx.coroutines.flow.SharedFlow

interface BaseViewModelInterface<E> {
    val event: SharedFlow<E>
}