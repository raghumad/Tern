package com.madanala.tern.utils

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import androidx.core.view.doOnLayout
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.findViewTreeViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.findViewTreeSavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object ViewToBitmap {

    /**
     * Converts a Composable to a Bitmap asynchronously by attaching to a parent view.
     * Must be called on the main thread.
     */
    suspend fun createBitmapFromComposable(
        parentView: ViewGroup,
        width: Int,
        height: Int,
        lifecycleOwner: LifecycleOwner? = null,
        viewModelStoreOwner: ViewModelStoreOwner? = null,
        savedStateRegistryOwner: SavedStateRegistryOwner? = null,
        content: @Composable () -> Unit
    ): Bitmap = suspendCancellableCoroutine { continuation ->
        val context = parentView.context
        val view = ComposeView(context)
        
        try {
            // Explicitly propagate lifecycle and view model store owners BEFORE setContent
            val resolvedLifecycleOwner = lifecycleOwner ?: parentView.findViewTreeLifecycleOwner()
            val resolvedViewModelStoreOwner = viewModelStoreOwner ?: parentView.findViewTreeViewModelStoreOwner()
            val resolvedSavedStateRegistryOwner = savedStateRegistryOwner ?: parentView.findViewTreeSavedStateRegistryOwner()
            
            if (resolvedLifecycleOwner != null) {
                view.setViewTreeLifecycleOwner(resolvedLifecycleOwner)
            }
            if (resolvedViewModelStoreOwner != null) {
                view.setViewTreeViewModelStoreOwner(resolvedViewModelStoreOwner)
            }
            if (resolvedSavedStateRegistryOwner != null) {
                view.setViewTreeSavedStateRegistryOwner(resolvedSavedStateRegistryOwner)
            }

            view.setContent(content)
            
            // Set layout params
            view.layoutParams = ViewGroup.LayoutParams(width, height)
            
            // Add to parent (invisible) so it gets a window attachment and lifecycle
            view.visibility = View.INVISIBLE
            parentView.addView(view)
            
            view.doOnLayout { 
                try {
                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(bitmap)
                    view.draw(canvas)
                    continuation.resume(bitmap)
                } catch (e: Exception) {
                    continuation.resumeWithException(e)
                } finally {
                    parentView.removeView(view)
                }
            }
        } catch (e: Exception) {
            if (parentView.indexOfChild(view) != -1) {
                parentView.removeView(view)
            }
            continuation.resumeWithException(e)
        }
    }
}
