package com.github.reygnn.kolibri_launcher.ui.base

import kotlinx.coroutines.flow.Flow

interface BaseViewModelInterface<E> {
    val event: Flow<E>
}