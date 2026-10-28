package com.jacobusoettle.satmap.app

import androidx.car.app.Session
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** iOS's `UIApplication` state and its three notifications, for the whole process (Android has no such thing). The
 *  phone's activities (through `ProcessLifecycleOwner`) and every Android Auto `Session` count: with Android Auto
 *  showing SatMap the app is in the foreground even while the phone is locked, as with CarPlay. Nothing else observes
 *  `ProcessLifecycleOwner` directly. Main thread only. */
object AppLifecycle {
    /** `UIApplication.State`, lowest first. */
    enum class AppState { background, inactive, active }

    /** Told of the crossings in registration order, on the main thread. */
    interface Observer {
        fun willEnterForeground() {}
        fun didBecomeActive() {}
        fun didEnterBackground() {}
    }

    private val _state = MutableStateFlow(AppState.background)
    val state: StateFlow<AppState> = _state.asStateFlow()

    /** `applicationState != .background` */
    val isForeground: Boolean get() = state.value != AppState.background

    /** `applicationState == .active` */
    val isActive: Boolean get() = state.value == AppState.active

    private val observers = mutableListOf<Observer>()
    private var phone = Lifecycle.State.INITIALIZED
    private val cars = LinkedHashMap<Session, Lifecycle.State>()

    /** Starts following the phone. `ProcessLifecycleOwner` is already set up by App Startup when
     *  `Application.onCreate` runs. Its ON_STOP comes 700 ms after the last activity stops, which is fine. */
    fun init() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(LifecycleEventObserver { _, event ->
            phone = event.targetState
            update()
        })
    }

    /** Follows an Android Auto session until it is destroyed (called by the car subsystem). */
    fun observeCar(session: Session) {
        session.lifecycle.addObserver(LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_DESTROY) cars.remove(session) else cars[session] = event.targetState
            update()
        })
    }

    fun addObserver(o: Observer) {
        observers += o
    }

    /** The state is the highest of the phone's and the cars': RESUMED → active, STARTED → inactive, lower →
     *  background. A jump from background to active sends willEnterForeground first, then didBecomeActive. */
    private fun update() {
        val highest = (cars.values + phone).maxBy { it.ordinal }
        val new = when {
            highest.isAtLeast(Lifecycle.State.RESUMED) -> AppState.active
            highest.isAtLeast(Lifecycle.State.STARTED) -> AppState.inactive
            else -> AppState.background
        }
        val old = _state.value
        if (new == old) return
        _state.value = new
        val told = observers.toList()
        if (old == AppState.background) told.forEach { it.willEnterForeground() }
        if (new == AppState.active) told.forEach { it.didBecomeActive() }
        if (new == AppState.background) told.forEach { it.didEnterBackground() }
    }
}
