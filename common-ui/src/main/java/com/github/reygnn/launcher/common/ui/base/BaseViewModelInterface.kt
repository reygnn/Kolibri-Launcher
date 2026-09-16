package com.github.reygnn.launcher.common.ui.base

import kotlinx.coroutines.flow.Flow

interface BaseViewModelInterface<E> {
    val event: Flow<E>
}
