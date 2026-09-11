package com.github.reygnn.nyx_launcher.home

import android.graphics.Bitmap
import android.widget.ImageView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Token-gated async icon load (ICL-INV-9), shared by the home/dock/drawer/folder
 * adapters (AUDIT-1 A1-09). Clears the view, then asynchronously sets the bitmap
 * from [produce] — but only if the holder hasn't been rebound or recycled since
 * (its token still equals [tokenAtBind]), so a fast scroll never flashes the
 * wrong icon. A failed load leaves the view cleared. The caller bumps the
 * holder's token on bind + recycle; [currentToken] reads the live value.
 */
fun ImageView.loadIconGated(
    scope: CoroutineScope,
    tokenAtBind: Int,
    currentToken: () -> Int,
    produce: suspend () -> Bitmap?,
) {
    setImageDrawable(null)
    scope.launch {
        val bitmap = runCatching { produce() }.getOrNull() ?: return@launch
        if (currentToken() == tokenAtBind) setImageBitmap(bitmap)
    }
}
