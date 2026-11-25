package io.agentscope.qwenpaw.mobile
import expo.modules.splashscreen.SplashScreenManager

import android.os.Bundle

import androidx.activity.OnBackPressedCallback

import com.facebook.react.ReactActivity
import com.facebook.react.ReactActivityDelegate
import com.facebook.react.defaults.DefaultNewArchitectureEntryPoint.fabricEnabled
import com.facebook.react.defaults.DefaultReactActivityDelegate

import expo.modules.ReactActivityDelegateWrapper

class MainActivity : ReactActivity() {

  private val backCallback = OnBackPressedCallback(true) {
    // Forward to RN's back handling so the JS BackHandler decides what to
    // do (pop an inner screen, or nothing on a root/tab screen). We never
    // finish the activity here, so a back press never exits to the desktop.
    onBackPressed()
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    // Set the theme to AppTheme BEFORE onCreate to support
    // coloring the background, status bar, and navigation bar.
    // This is required for expo-splash-screen.
    // setTheme(R.style.AppTheme);
    // @generated begin expo-splashscreen - expo prebuild (DO NOT MODIFY) sync-f3ff59a738c56c9a6119210cb55f0b613eb8b6af
    SplashScreenManager.registerOnActivity(this)
    // @generated end expo-splashscreen
    super.onCreate(null)
    // Intercept predictive-back dispatches (enableOnBackInvokedCallback=true)
    // and route them to RN's back handling instead of the default finish.
    onBackPressedDispatcher.addCallback(this, backCallback)
  }

  /**
   * Returns the name of the main component registered from JavaScript. This is used to schedule
   * rendering of the component.
   */
  override fun getMainComponentName(): String = "main"

  /**
   * Returns the instance of the [ReactActivityDelegate]. We use [DefaultReactActivityDelegate]
   * which allows you to enable New Architecture with a single boolean flags [fabricEnabled]
   */
  override fun createReactActivityDelegate(): ReactActivityDelegate {
    return ReactActivityDelegateWrapper(
          this,
          BuildConfig.IS_NEW_ARCHITECTURE_ENABLED,
          object : DefaultReactActivityDelegate(
              this,
              mainComponentName,
              fabricEnabled
          ){})
  }

  /**
    * Align the back button behavior with Android S
    * where moving root activities to background instead of finishing activities.
    * @see <a href="https://developer.android.com/reference/android/app/Activity#onBackPressed()">onBackPressed</a>
    */
  /**
   * Native back-press fallback. When the JS BackHandler consumes the press
   * it returns true and this is never reached. When it does not (e.g. on a
   * root/tab screen with nothing to pop), this must do NOTHING so the back
   * button never exits to the desktop. Previously this called
   * moveTaskToBack(true), which is exactly "move the app to background" — the
   * very behavior the user is complaining about, so we drop it entirely.
   */
  override fun invokeDefaultOnBackPressed() {
      // Intentionally empty: keep the user in the app on back press.
  }
}
