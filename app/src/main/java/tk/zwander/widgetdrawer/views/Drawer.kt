package tk.zwander.widgetdrawer.views

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.Gravity
import android.view.KeyEvent
import android.view.WindowManager
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import androidx.core.animation.doOnEnd
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.arasthel.spannedgridlayoutmanager.SpannedGridLayoutManager
import tk.zwander.lockscreenwidgets.activities.DismissOrUnlockActivity
import tk.zwander.lockscreenwidgets.data.WidgetData
import tk.zwander.lockscreenwidgets.data.WidgetType
import tk.zwander.lockscreenwidgets.databinding.DrawerLayoutBinding
import tk.zwander.lockscreenwidgets.host.WidgetHostCompat
import tk.zwander.lockscreenwidgets.util.*
import tk.zwander.widgetdrawer.adapters.DrawerAdapter

class Drawer : FrameLayout, EventObserver {
    companion object {
        const val ANIM_DURATION = 200L
    }

    constructor(context: Context) : super(context)
    constructor(context: Context, attrs: AttributeSet) : super(context, attrs)

    val params: WindowManager.LayoutParams
        get() = WindowManager.LayoutParams().apply {
            val displaySize = context.screenSize
            type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            width = displaySize.x
            height = WindowManager.LayoutParams.MATCH_PARENT
            format = PixelFormat.RGBA_8888
            gravity = Gravity.TOP
            screenOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }

    private val wm by lazy { context.applicationContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager }
    private val host by lazy {
        WidgetHostCompat.getInstance(
            context,
            1003
        ) {
            DismissOrUnlockActivity.launch(context)
        }
    }
    private val manager by lazy { AppWidgetManager.getInstance(context.applicationContext) }
    private val shortcutIdManager by lazy { ShortcutIdManager.getInstance(context, host) }
    private val adapter by lazy {
        DrawerAdapter(manager, host, params) { _, widget, _ ->
            removeWidget(widget)
        }
    }

    private val gridLayoutManager = SpannedLayoutManager(context)
    private val touchHelperCallback = context.createTouchHelperCallback(
        adapter,
        widgetMoved = { moved ->
            if (moved) {
                updateState { it.copy(updatedForMove = true) }
                context.prefManager.drawerWidgets = LinkedHashSet(adapter.widgets)
                adapter.currentEditingInterfacePosition = -1
            }
        },
        onItemSelected = { selected ->
            updateState { it.copy(isHoldingItem = selected) }
        }
    )

    private val preferenceHandler = HandlerRegistry {
        handler(PrefManager.KEY_DRAWER_WIDGETS) {
            if (!state.updatedForMove) {
                //Only run the update if it wasn't generated by a reorder event
                adapter.updateWidgets(context.prefManager.drawerWidgets.toList())
            } else {
                updateState { it.copy(updatedForMove = false) }
            }
        }
        handler(PrefManager.KEY_DRAWER_BACKGROUND_COLOR) {
            setBackgroundColor(context.prefManager.drawerBackgroundColor)
        }
    }

    @Suppress("DEPRECATION")
    private val globalReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_CLOSE_SYSTEM_DIALOGS -> {
                    hideDrawer()
                }
            }
        }
    }

    private val binding by lazy { DrawerLayoutBinding.bind(this) }

    var state: State = State()
        private set

    override fun onFinishInflate() {
        super.onFinishInflate()

        if (!isInEditMode) {
            binding.addWidget.setOnClickListener { pickWidget() }
            binding.closeDrawer.setOnClickListener { hideDrawer() }
            binding.toggleTransparent.setOnClickListener {
                context.prefManager.transparentDrawerCards = !context.prefManager.transparentDrawerCards
            }

            binding.widgetGrid.layoutManager = gridLayoutManager
            gridLayoutManager.spanSizeLookup = adapter.spanSizeLookup
            ItemTouchHelper(touchHelperCallback).attachToRecyclerView(binding.widgetGrid)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()

        host.startListening()
        Handler(Looper.getMainLooper()).postDelayed({
            adapter.notifyItemRangeChanged(0, adapter.itemCount)
        }, 50)

        setPadding(paddingLeft, context.statusBarHeight, paddingRight, paddingBottom)

        handler?.postDelayed({
            val anim = ValueAnimator.ofFloat(0f, 1f)
            anim.interpolator = DecelerateInterpolator()
            anim.duration = ANIM_DURATION
            anim.addUpdateListener {
                alpha = it.animatedValue.toString().toFloat()
            }
            anim.doOnEnd {
                context.eventManager.sendEvent(Event.DrawerShown)
            }
            anim.start()
        }, 10)

        setBackgroundColor(context.prefManager.drawerBackgroundColor)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()

        try {
            host.stopListening()
        } catch (e: NullPointerException) {
            //AppWidgetServiceImpl$ProviderId NPE
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent?): Boolean {
        if (event?.keyCode == KeyEvent.KEYCODE_BACK) {
            hideDrawer()
            return true
        }

        return super.dispatchKeyEvent(event)
    }

    override fun onEvent(event: Event) {

    }

    fun onCreate() {
        context.registerReceiver(globalReceiver, IntentFilter().apply {
            @Suppress("DEPRECATION")
            addAction(Intent.ACTION_CLOSE_SYSTEM_DIALOGS)
        })

        preferenceHandler.register(context)
        binding.widgetGrid.adapter = adapter
        binding.widgetGrid.setHasFixedSize(true)
        updateSpanCount()
        adapter.updateWidgets(context.prefManager.drawerWidgets.toList())
        context.eventManager.addObserver(this)
    }

    fun onDestroy() {
        hideDrawer(false)
        context.prefManager.drawerWidgets = LinkedHashSet(adapter.widgets)

        context.unregisterReceiver(globalReceiver)
        preferenceHandler.unregister(context)
        context.eventManager.removeObserver(this)
    }

    fun showDrawer(wm: WindowManager = this.wm) {
        try {
            wm.addView(this, params)
        } catch (_: Exception) {}
    }

    fun hideDrawer(callListener: Boolean = true) {
        val anim = ValueAnimator.ofFloat(1f, 0f)
        anim.interpolator = AccelerateInterpolator()
        anim.duration = ANIM_DURATION
        anim.addUpdateListener {
            alpha = it.animatedValue.toString().toFloat()
        }
        anim.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator?) {
                handler?.postDelayed({
                    try {
                        wm.removeView(this@Drawer)
                        if (callListener) context.eventManager.sendEvent(Event.DrawerHidden)
                    } catch (_: Exception) {
                    }
                }, 10)
            }
        })
        anim.start()
    }

    fun updateState(transform: (State) -> State) {
        state = transform(state)
    }

    private fun updateSpanCount() {
        gridLayoutManager.columnCount = context.prefManager.drawerColCount
        gridLayoutManager.customHeight = context.dpAsPx(50f)
    }

    private fun pickWidget() {
        hideDrawer()
        context.eventManager.sendEvent(Event.LaunchAddDrawerWidget)
    }

    private fun removeWidget(info: WidgetData) {
        if (info.type == WidgetType.WIDGET) host.deleteAppWidgetId(info.id)
        else if (info.type == WidgetType.SHORTCUT) shortcutIdManager.removeShortcutId(
            info.id
        )
        context.prefManager.drawerWidgets = LinkedHashSet(adapter.widgets.apply { remove(info) })
    }

    private inner class SpannedLayoutManager(context: Context) : SpannedGridLayoutManager(
        context,
        RecyclerView.VERTICAL,
        1,
        context.prefManager.drawerColCount
    ) {
        override fun canScrollVertically(): Boolean {
            return (adapter.currentEditingInterfacePosition == -1 || state.isHoldingItem) && super.canScrollVertically()
        }
    }

    data class State(
        val isHoldingItem: Boolean = false,
        val updatedForMove: Boolean = false,
    )
}