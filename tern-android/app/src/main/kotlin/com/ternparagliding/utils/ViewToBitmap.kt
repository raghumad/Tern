package com.ternparagliding.utils

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import androidx.core.view.doOnLayout
import androidx.core.view.contains
import androidx.core.graphics.createBitmap
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
     * Dimensions are provided in DP (Density-independent Pixels) and converted to 
     * physical pixels based on device density.
     */
    suspend fun createBitmapFromComposableDP(
        parentView: ViewGroup,
        widthDp: Int,
        heightDp: Int,
        lifecycleOwner: LifecycleOwner? = null,
        viewModelStoreOwner: ViewModelStoreOwner? = null,
        savedStateRegistryOwner: SavedStateRegistryOwner? = null,
        content: @Composable () -> Unit
    ): Bitmap {
        val density = parentView.context.resources.displayMetrics.density
        val widthPx = (widthDp * density).toInt()
        val heightPx = (heightDp * density).toInt()
        
        return createBitmapFromComposable(
            parentView, widthPx, heightPx, 
            lifecycleOwner, viewModelStoreOwner, savedStateRegistryOwner, 
            content
        )
    }

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
        
        // Add to root view to avoid interfering with parent's layout (e.g. MapView)
        val safeParent = parentView.rootView as? ViewGroup ?: parentView

        // Track if view was added to parent for proper cleanup
        var viewAdded = false

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
            
            if (continuation.isActive) {
                safeParent.addView(view)
                viewAdded = true
            } else {
                return@suspendCancellableCoroutine
            }
            
            view.doOnLayout { 
                try {
                    if (continuation.isActive) {
                        // DIAGNOSTIC GUARD: Check if Composable's intrinsic size fits in buffer
                        // Note: Measuring children in Compose is usually done via modifiers, 
                        // but here we check the View's own measure output.
                        if (view.measuredWidth > width || view.measuredHeight > height) {
                            val msg = "ViewToBitmap: Clipping detected! Composable (${view.measuredWidth}x${view.measuredHeight}px) " +
                                     "is larger than request (${width}x${height}px)"
                            if (com.ternparagliding.BuildConfig.DEBUG) {
                                throw IllegalStateException(msg)
                            } else {
                                android.util.Log.e("ViewToBitmap", msg)
                            }
                        }

                        val bitmap = createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        val canvas = Canvas(bitmap)
                        view.draw(canvas)
                        continuation.resume(bitmap)
                    }
                } catch (e: Exception) {
                    if (continuation.isActive) {
                        continuation.resumeWithException(e)
                    }
                } finally {
                    if (viewAdded) {
                        // FIX: Wrap removeView in post to avoid NPE in FrameLayout.layoutChildren.
                        view.post {
                            safeParent.removeView(view)
                            // PERFORMANCE: Explicitly dispose composition to free memory faster
                            view.disposeComposition()
                        }
                        viewAdded = false
                    }
                }
            }

            // Fallback cleanup if coroutine is cancelled while waiting for layout
            continuation.invokeOnCancellation {
                if (viewAdded) {
                    view.post {
                        safeParent.removeView(view)
                        view.disposeComposition()
                    }
                    viewAdded = false
                }
            }

        } catch (e: Exception) {
            if (viewAdded) {
                safeParent.removeView(view)
                viewAdded = false
            }
            if (continuation.isActive) {
                continuation.resumeWithException(e)
            }
        }
    }
}
