package de.lukas.briq

import android.app.Activity
import android.app.Application
import android.os.Bundle

/**
 * Tracks whether any activity is resumed.
 *
 * The completion notification exists for the case the user pocketed the
 * phone mid-apply. If they are still looking at the screen, the screen
 * already says the same thing and the notification is just noise.
 */
object AppForeground {
    @Volatile var visible: Boolean = false
        private set

    internal fun set(v: Boolean) { visible = v }
}

class BriqApp : Application() {
    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            private var resumed = 0
            override fun onActivityResumed(activity: Activity) {
                resumed++; AppForeground.set(true)
            }
            override fun onActivityPaused(activity: Activity) {
                resumed--; if (resumed <= 0) AppForeground.set(false)
            }
            override fun onActivityCreated(a: Activity, b: Bundle?) = Unit
            override fun onActivityStarted(a: Activity) = Unit
            override fun onActivityStopped(a: Activity) = Unit
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) = Unit
            override fun onActivityDestroyed(a: Activity) = Unit
        })
    }
}
