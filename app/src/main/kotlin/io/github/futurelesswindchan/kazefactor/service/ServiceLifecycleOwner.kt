package io.github.futurelesswindchan.kazefactor.service

import android.view.View
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

/**
 * 为无障碍服务中的悬浮窗 `ComposeView` 提供 [Lifecycle]、[ViewModelStore] 和 [SavedStateRegistry]（架构方案第 2.2、2.3 节）。
 *
 * Activity 会自动在根视图上挂载这三个 Owner，但 `AccessibilityService` 直接通过 `WindowManager.addView`
 * 挂载视图时没有 Activity 宿主，必须手动创建并调用 `attachTo(view)`，否则 `ComposeView` 会抛出
 * `ViewTreeLifecycleOwner not found` 异常。
 */
class ServiceLifecycleOwner :
    LifecycleAppsOwner,
    ViewModelStoreOwner,
    SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    private val store = ViewModelStore()

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    override val viewModelStore: ViewModelStore
        get() = store

    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    /** 初始化并进入 `RESUMED` 状态（在 `WindowManager.addView` 前调用）。 */
    fun onCreateAndResume() {
        if (lifecycleRegistry.currentState == Lifecycle.State.INITIALIZED) {
            savedStateRegistryController.performRestore(null)
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        }
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    /** 销毁生命周期并清理状态（在服务销毁或彻底移除悬浮窗时调用）。 */
    fun onDestroy() {
        if (lifecycleRegistry.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        }
        if (lifecycleRegistry.currentState != Lifecycle.State.DESTROYED) {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        }
        store.clear()
    }

    /** 将自身绑定到悬浮窗根视图 [view] 的 ViewTree 上。 */
    fun attachTo(view: View) {
        view.setViewTreeLifecycleOwner(this)
        view.setViewTreeViewModelStoreOwner(this)
        view.setViewTreeSavedStateRegistryOwner(this)
    }
}

/** 内部标记接口，保持与 LifecycleOwner 继承层次清晰。 */
private interface LifecycleAppsOwner : androidx.lifecycle.LifecycleOwner
