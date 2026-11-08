package com.xjyzs.operator.utils.ui

import android.view.View
import com.xjyzs.operator.utils.data.OverlayController
import com.xjyzs.operator.utils.execute.awaitFrame
import com.xjyzs.operator.utils.execute.waitForTouchThroughEnabled
import com.xjyzs.operator.utils.execute.waitForViewGone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

class FloatingOverlay(private val view: View) : OverlayController {
    override suspend fun <T> withHidden(block: suspend () -> T): T {
        val saved = withContext(Dispatchers.Main) {
            view.translationX.also { view.translationX = 114514f }
        }
        try {
            withContext(Dispatchers.Main) {
                waitForViewGone(view)
                awaitFrame()
                awaitFrame()
            }
            return block()
        } finally {
            withContext(NonCancellable + Dispatchers.Main) { view.translationX = saved }
        }
    }

    override suspend fun <T> withTouchThroughEnabled(block: suspend () -> T): T {
        try {
            withContext(Dispatchers.Main) {
                waitForTouchThroughEnabled(view)
                awaitFrame()
                awaitFrame()
            }
            return block()
        } finally {
        }
    }
}