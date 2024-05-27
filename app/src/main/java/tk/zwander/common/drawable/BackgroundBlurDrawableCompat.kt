package tk.zwander.common.drawable

import android.annotation.ColorInt
import android.graphics.drawable.Drawable
import android.os.Build
import android.view.ViewRootImpl
import androidx.annotation.RequiresApi
import com.android.internal.graphics.drawable.BackgroundBlurDrawable

sealed class BackgroundBlurDrawableCompatDelegate(open val wrapped: Drawable) {
    abstract fun setColor(@ColorInt color: Int)
    abstract fun setBlurRadius(blurRadius: Int)
    abstract fun setCornerRadius(cornerRadius: Float)
    abstract fun setCornerRadius(
        cornerRadiusTL: Float,
        cornerRadiusTR: Float,
        cornerRadiusBL: Float,
        cornerRadiusBR: Float,
    )

    @RequiresApi(Build.VERSION_CODES.S)
    class BackgroundBlurDrawableCompatApi31(override val wrapped: BackgroundBlurDrawable) : BackgroundBlurDrawableCompatDelegate(wrapped) {
        override fun setColor(color: Int) {
            wrapped.setColor(color)
        }

        override fun setBlurRadius(blurRadius: Int) {
            wrapped.setBlurRadius(blurRadius)
        }

        override fun setCornerRadius(cornerRadius: Float) {
            wrapped.setCornerRadius(cornerRadius)
        }

        override fun setCornerRadius(
            cornerRadiusTL: Float,
            cornerRadiusTR: Float,
            cornerRadiusBL: Float,
            cornerRadiusBR: Float,
        ) {
            wrapped.setCornerRadius(cornerRadiusTL, cornerRadiusTR, cornerRadiusBL, cornerRadiusBR)
        }
    }

    companion object {
        fun createInstance(viewRootImpl: ViewRootImpl): BackgroundBlurDrawableCompatDelegate? {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                BackgroundBlurDrawableCompatApi31(viewRootImpl.createBackgroundBlurDrawable())
            } else {
                null
            }
        }
    }
}