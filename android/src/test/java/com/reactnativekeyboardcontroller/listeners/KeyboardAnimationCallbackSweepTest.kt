package com.reactnativekeyboardcontroller.listeners

import android.app.Activity
import android.content.Context
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewTreeObserver
import android.widget.EditText
import android.widget.FrameLayout
import androidx.core.graphics.Insets
import androidx.core.view.WindowInsetsAnimationCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ApplicationProvider
import com.facebook.react.bridge.BridgeReactContext
import com.facebook.react.bridge.JavaScriptModule
import com.facebook.react.bridge.WritableMap
import com.facebook.react.uimanager.ThemedReactContext
import com.facebook.react.views.view.ReactViewGroup
import com.reactnativekeyboardcontroller.interactive.InteractiveKeyboardProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy
import java.time.Duration

/**
 * The single-scenario tests above lock one path each; this sweep locks the class. Every
 * combination of settled state, orphaned-latch pattern, window change, protection, and
 * recovery trigger runs against the same invariants:
 *
 * 1. a latched transition never survives a settled recovery (and a suppressed one stays put),
 * 2. the persisted height ends at the live window height,
 * 3. the JS keyboard state - folded exactly the way `KeyboardController.state()` does it,
 *    which only listens for `keyboardWillShow` and `keyboardDidHide` - matches the live
 *    window once every protection has been released.
 *
 * The oracle is the window: whatever the root reports at the end is the truth the whole
 * stack must have converged to.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, shadows = [ShadowArguments::class])
class KeyboardAnimationCallbackSweepTest {
  private lateinit var activity: Activity

  /** The JS-side `lastState`, folded from the emitted events the way module.ts does. */
  private class JsKeyboardState {
    var visible = false
    var height = 0.0

    fun fold(event: Pair<String, Double?>) {
      when (event.first) {
        "KeyboardController::keyboardWillShow" -> {
          visible = true
          height = event.second ?: 0.0
        }
        "KeyboardController::keyboardDidHide" -> {
          visible = false
          height = 0.0
        }
        // `keyboardDidShow` deliberately does not update `state()` - that asymmetry is
        // exactly why recoveries must replay the show pair.
      }
    }
  }

  private class Cell(
    val initial: String,
    val orphan: String,
    val target: Int,
    val protection: String,
    val trigger: String,
  ) {
    override fun toString(): String =
      "initial=$initial orphan=$orphan target=$target protection=$protection trigger=$trigger"
  }

  private val initials = listOf("hidden", "hiddenAfter881", "shown881")
  private val orphans = listOf("none", "prepareOnly", "endLost")
  private val targetsByInitial =
    mapOf("hidden" to listOf(0, 881), "hiddenAfter881" to listOf(0, 881, 1080), "shown881" to listOf(881, 1080, 0))
  private val protections = listOf("none", "suspended", "gesture")
  private val triggers = listOf("insets", "recheck", "focus")

  // A cancelled open can only be staged where it starts and ends hidden at the same
  // level: the window must be able to return to its starting height with nothing due.
  private fun orphansFor(
    initial: String,
    target: Int,
  ): List<String> = if (initial == "hidden" && target == 0) orphans + "cancelledOpen" else orphans

  @Before
  fun setUp() {
    activity = Robolectric.buildActivity(Activity::class.java).setup().get()
  }

  @Test
  fun `every loss combination converges to the live window`() {
    val failures = mutableListOf<String>()
    for (initial in initials) {
      for (target in targetsByInitial.getValue(initial)) {
        for (orphan in orphansFor(initial, target)) {
          for (protection in protections) {
            for (trigger in triggers) {
              val cell = Cell(initial, orphan, target, protection, trigger)
              runCatching { runCell(cell) }.onFailure { failure ->
                failures.add("$cell -> ${failure.message}")
              }
            }
          }
        }
      }
    }
    assertTrue(
      "recovery sweep failures (${failures.size}):\n${failures.joinToString("\n")}",
      failures.isEmpty(),
    )
  }

  private fun runCell(cell: Cell) {
    // Consume the traversal the setup queues before any staging happens (it would
    // otherwise restage the root empty on the first idle).
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(5))

    val events = mutableListOf<Pair<String, Double?>>()
    val (callback, root, input) = buildCallback(events)

    // Establish the settled state the loss happens from.
    if (cell.initial == "shown881") {
      runCatching { callback.syncKeyboardPosition(881.0, true) }
    }
    if (cell.initial == "hiddenAfter881") {
      // A real open followed by a real close: the tracked height drops to 0, but the
      // persisted one keeps the last shown height - the exact state a same-height
      // re-show has to be judged against.
      runCatching { callback.syncKeyboardPosition(881.0, true) }
      stageRoot(root, 0)
      callback.onPrepare(imeAnimation())
      runCatching { callback.onStart(imeAnimation(), imeBounds()) }
      runCatching { callback.onEnd(imeAnimation()) }
    }
    stageRoot(root, cell.target)

    // Deliver the loss: which lifecycle callbacks of the changing animation survive.
    if (cell.orphan == "cancelledOpen") {
      // The open began while the window briefly reported the keyboard visible - `onStart`
      // memorized it, persisted 881, and already emitted `willShow` - then the user
      // cancelled and the lifecycle was lost with the window back at its starting height.
      stageRoot(root, 881)
      callback.onPrepare(imeAnimation())
      runCatching { callback.onStart(imeAnimation(), imeBounds()) }
      stageRoot(root, 0)
    } else if (cell.orphan != "none") {
      callback.onPrepare(imeAnimation())
      if (cell.orphan == "endLost") {
        runCatching { callback.onStart(imeAnimation(), imeBounds()) }
      }
    }

    val js = JsKeyboardState()
    val suppressFrom = events.size

    if (cell.trigger == "recheck") {
      // The settling dispatch lands inside the grace window and is ignored; only the
      // deferred recheck can recover it.
      runCatching { callback.onApplyWindowInsets(root, insetsFor(cell.target)) }
      applyProtection(cell, callback)
      shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(2_600))
    } else {
      SystemClock.sleep(2_600)
      applyProtection(cell, callback)
      stageRoot(root, cell.target)
      if (cell.trigger == "insets") {
        runCatching { callback.onApplyWindowInsets(root, insetsFor(cell.target)) }
      } else {
        dispatchFocus(callback, root, input)
      }
    }

    // A protected callback must stay quiet through an insets-borne trigger, and a
    // suppressed latch must survive it. Without a latched transition the dispatch is
    // upstream's own handling (the resize path was never suspension-gated, and a bare
    // dispatch recovers at once), so only the recovery exits owe the protection contract.
    if (cell.protection != "none" && cell.trigger != "focus" && cell.orphan != "none") {
      assertEquals(
        "$cell: a protected callback must not emit during the trigger",
        emptyList<Pair<String, Double?>>(),
        events.subList(suppressFrom, events.size),
      )
      if (cell.orphan != "none") {
        assertTrue(
          "$cell: a suppressed latch must wait for the protection to lift",
          field(callback, "isTransitioning") as Boolean,
        )
      }
    }

    // Release every protection and let the insets re-dispatch that follows (the modal
    // dismissing, the gesture ending) settle the stack.
    releaseProtection(cell, callback)
    stageRoot(root, cell.target)
    SystemClock.sleep(2_600)
    // An unprotected deferred recheck owes the whole recovery itself: a rescue dispatch
    // here would mask a missing recheck, which is exactly the stuck-forever shape the
    // deferred recovery exists to close. Every other cell may lean on the re-dispatch a
    // real window performs on resume/layout.
    if (!(cell.protection == "none" && cell.trigger == "recheck" && cell.orphan != "none")) {
      runCatching { callback.onApplyWindowInsets(root, insetsFor(cell.target)) }
      // The upstream resize path updates the persisted height only - the second dispatch
      // lets the desync path catch the tracked height up.
      runCatching { callback.onApplyWindowInsets(root, insetsFor(cell.target)) }
    }

    events.forEach(js::fold)

    // Invariant 1: no latch survives a settled recovery. The one exception owes nothing:
    // a hide whose lifecycle was lost while the state already reads hidden and level - no
    // event is due, no height moved, and the next real transition or focus clears it.
    // A cancelled open is never that case: it emitted `willShow` and memorized visible.
    val lingersOwingNothing =
      cell.target == 0 && cell.orphan != "none" && cell.orphan != "cancelledOpen" && cell.initial != "shown881"
    if (!lingersOwingNothing) {
      assertFalse("$cell: the latch must be cleared", field(callback, "isTransitioning") as Boolean)
    }
    // Invariant 2: the persisted height ends at the live window height. (`prevKeyboardHeight`
    // lags one resize behind on the untouched upstream path, where nothing owes a sync.)
    // While visible the persisted height must be exact. While hidden nothing on the JS
    // side reads it, and a recovery may legitimately sync it to 0 or keep the last shown
    // height (what a same-height re-show recovers against) - both are correct.
    val persisted = field(callback, "persistentKeyboardHeight") as Double
    if (cell.target > 0) {
      assertEquals("$cell: the persisted height must end at the live height", cell.target.toDouble(), persisted, 0.001)
    } else {
      val lastShown = if (cell.initial == "hidden") 0.0 else 881.0
      assertTrue(
        "$cell: a hidden window must keep the last shown height ($lastShown) or zero, was $persisted",
        persisted == lastShown || persisted == 0.0,
      )
    }
    assertEquals(
      "$cell: the tracked height must catch up to the live window",
      cell.target.toDouble(),
      field(callback, "prevKeyboardHeight") as Double,
      0.001,
    )
    // Invariant 3: the JS state folds to the live window.
    assertEquals("$cell: the JS visibility must match the live window", cell.target > 0, js.visible)
    assertEquals("$cell: the JS height must match the live window", cell.target.toDouble(), js.height, 0.001)
  }

  private fun applyProtection(
    cell: Cell,
    callback: KeyboardAnimationCallback,
  ) {
    when (cell.protection) {
      "suspended" -> callback.suspend(true)
      "gesture" -> InteractiveKeyboardProvider.isInteractive = true
    }
  }

  private fun releaseProtection(
    cell: Cell,
    callback: KeyboardAnimationCallback,
  ) {
    when (cell.protection) {
      "suspended" -> callback.suspend(false)
      "gesture" -> InteractiveKeyboardProvider.isInteractive = false
    }
  }

  private fun buildCallback(
    events: MutableList<Pair<String, Double?>>,
  ): Triple<KeyboardAnimationCallback, FrameLayout, EditText> {
    val root = FrameLayout(activity)
    val input = EditText(activity)
    root.addView(input)
    activity.setContentView(root)
    // Consume the traversal this layout swap queued, so it cannot wipe a staged root later.
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1))

    val appContext = ApplicationProvider.getApplicationContext<Context>()
    val reactContext =
      object : BridgeReactContext(appContext) {
        override fun <T : JavaScriptModule> getJSModule(moduleInterface: Class<T>): T {
          val proxy =
            Proxy.newProxyInstance(
              moduleInterface.classLoader,
              arrayOf(moduleInterface),
            ) { _, method, args ->
              if (method.name == "emit" && args != null && args.size == 2) {
                val params = args[1] as? WritableMap
                events.add(args[0] as String to params?.takeIf { it.hasKey("height") }?.getDouble("height"))
              }
              null
            }
          @Suppress("UNCHECKED_CAST")
          return proxy as T
        }
      }
    val themedContext = ThemedReactContext(reactContext, appContext)
    val callback =
      KeyboardAnimationCallback(
        eventPropagationView = ReactViewGroup(themedContext),
        view = root,
        context = themedContext,
        config =
          KeyboardAnimationCallbackConfig(
            persistentInsetTypes = WindowInsetsCompat.Type.systemBars(),
            deferredInsetTypes = WindowInsetsCompat.Type.ime(),
            hasTranslucentNavigationBar = false,
          ),
      )
    return Triple(callback, root, input)
  }

  private fun imeAnimation(): WindowInsetsAnimationCompat =
    WindowInsetsAnimationCompat(WindowInsetsCompat.Type.ime(), null, 300)

  private fun imeBounds(): WindowInsetsAnimationCompat.BoundsCompat =
    WindowInsetsAnimationCompat.BoundsCompat(Insets.NONE, Insets.NONE)

  private fun insetsFor(target: Int): WindowInsetsCompat =
    if (target > 0) {
      WindowInsetsCompat
        .Builder()
        .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, target))
        .setVisible(WindowInsetsCompat.Type.ime(), true)
        .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.NONE)
        .build()
    } else {
      WindowInsetsCompat
        .Builder()
        .setInsets(WindowInsetsCompat.Type.ime(), Insets.NONE)
        .setVisible(WindowInsetsCompat.Type.ime(), false)
        .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.NONE)
        .build()
    }

  private fun stageRoot(
    root: FrameLayout,
    target: Int,
  ) {
    val impl = View::class.java.getMethod("getViewRootImpl").invoke(root)
    val field = impl.javaClass.getDeclaredField("mLastWindowInsets")
    field.isAccessible = true
    field.set(impl, insetsFor(target).toWindowInsets()!!)
  }

  private fun dispatchFocus(
    callback: KeyboardAnimationCallback,
    oldFocus: View,
    newFocus: View,
  ) {
    val listener =
      KeyboardAnimationCallback::class.java
        .getDeclaredField("focusListener")
        .apply { isAccessible = true }
        .get(callback) as ViewTreeObserver.OnGlobalFocusChangeListener
    runCatching { listener.onGlobalFocusChanged(oldFocus, newFocus) }
  }

  private fun field(
    callback: KeyboardAnimationCallback,
    name: String,
  ): Any? {
    val declared = KeyboardAnimationCallback::class.java.getDeclaredField(name)
    declared.isAccessible = true
    return declared.get(callback)
  }
}
