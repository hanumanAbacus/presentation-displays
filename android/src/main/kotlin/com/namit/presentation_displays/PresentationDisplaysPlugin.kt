package com.namit.presentation_displays

import android.content.ContentValues.TAG
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import androidx.annotation.NonNull
import com.google.gson.Gson
import io.flutter.FlutterInjector
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.embedding.engine.FlutterEngineCache
import io.flutter.embedding.engine.dart.DartExecutor
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import org.json.JSONObject

/** PresentationDisplaysPlugin */
class PresentationDisplaysPlugin : FlutterPlugin, ActivityAware, MethodChannel.MethodCallHandler {

  private lateinit var channel: MethodChannel
  private lateinit var eventChannel: EventChannel
  private var flutterEngineChannel: MethodChannel? = null
  private var context: Context? = null
  private var presentation: PresentationDisplay? = null

  override fun onAttachedToEngine(@NonNull flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
    channel = MethodChannel(flutterPluginBinding.binaryMessenger, viewTypeId)
    channel.setMethodCallHandler(this)

    eventChannel = EventChannel(flutterPluginBinding.binaryMessenger, viewTypeEventsId)
    displayManager =
        flutterPluginBinding.applicationContext.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
    val displayConnectedStreamHandler = DisplayConnectedStreamHandler(displayManager)
    eventChannel.setStreamHandler(displayConnectedStreamHandler)
  }

  override fun onDetachedFromEngine(@NonNull binding: FlutterPlugin.FlutterPluginBinding) {
    channel.setMethodCallHandler(null)
    eventChannel.setStreamHandler(null)
  }

  companion object {
    private const val viewTypeId = "presentation_displays_plugin"
    private const val viewTypeEventsId = "presentation_displays_plugin_events"
    private var displayManager: DisplayManager? = null
  }

  override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
    Log.i(TAG, "Method called: ${call.method} | arguments: ${call.arguments}")
    when (call.method) {
      "showPresentation" -> showPresentation(call, result)
      "hidePresentation" -> hidePresentation(result)
      "listDisplay" -> listDisplay(call, result)
      "transferDataToPresentation" -> transferDataToPresentation(call, result)
      else -> result.notImplemented()
    }
  }

  private fun showPresentation(call: MethodCall, result: MethodChannel.Result) {
    try {
      val obj = JSONObject(call.arguments as String)
      val displayId = obj.getInt("displayId")
      val tag = obj.getString("routerName")

      val display = displayManager?.getDisplay(displayId)
      if (display != null) {
        val flutterEngine = createFlutterEngine(tag)
        flutterEngine?.let {
          flutterEngineChannel = MethodChannel(it.dartExecutor.binaryMessenger, "${viewTypeId}_engine")
          presentation = context?.let { ctx -> PresentationDisplay(ctx, tag, display) }
          Log.i(TAG, "Showing presentation: $presentation")
          presentation?.show()
          result.success(true)
        } ?: result.error("404", "FlutterEngine not found", null)
      } else {
        result.error("404", "Display not found: $displayId", null)
      }
    } catch (e: Exception) {
      result.error("showPresentation", e.message, null)
    }
  }

  private fun hidePresentation(result: MethodChannel.Result) {
    try {
      presentation?.dismiss()
      presentation = null
      result.success(true)
    } catch (e: Exception) {
      result.error("hidePresentation", e.message, null)
    }
  }

  private fun listDisplay(call: MethodCall, result: MethodChannel.Result) {
    val listJson = mutableListOf<DisplayJson>()
    val category = call.arguments as? String
    val displays = displayManager?.getDisplays(category)
    displays?.forEach { display ->
      Log.i(TAG, "Display found: $display")
      listJson.add(DisplayJson(display.displayId, display.flags, display.rotation, display.name))
    }
    result.success(Gson().toJson(listJson))
  }

  private fun transferDataToPresentation(call: MethodCall, result: MethodChannel.Result) {
    try {
      flutterEngineChannel?.invokeMethod("DataTransfer", call.arguments)
      result.success(true)
    } catch (e: Exception) {
      result.success(false)
    }
  }

  private fun createFlutterEngine(tag: String): FlutterEngine? {
    context ?: return null

    return FlutterEngineCache.getInstance().get(tag) ?: run {
      val flutterEngine = FlutterEngine(context!!)
      flutterEngine.navigationChannel.setInitialRoute(tag)
      FlutterInjector.instance().flutterLoader().startInitialization(context!!)
      val path = FlutterInjector.instance().flutterLoader().findAppBundlePath()
      val entrypoint = DartExecutor.DartEntrypoint(path, "secondaryDisplayMain")
      flutterEngine.dartExecutor.executeDartEntrypoint(entrypoint)
      flutterEngine.lifecycleChannel.appIsResumed()
      FlutterEngineCache.getInstance().put(tag, flutterEngine)
      flutterEngine
    }
  }

  override fun onDetachedFromActivity() {}
  override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {}
  override fun onAttachedToActivity(binding: ActivityPluginBinding) {
    this.context = binding.activity
    displayManager = context?.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
  }
  override fun onDetachedFromActivityForConfigChanges() {}
}

class DisplayConnectedStreamHandler(private var displayManager: DisplayManager?) :
    EventChannel.StreamHandler {
  private var sink: EventChannel.EventSink? = null
  private var handler: Handler? = null

  private val displayListener =
      object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {
          sink?.success(1)
        }

        override fun onDisplayRemoved(displayId: Int) {
          sink?.success(0)
        }

        override fun onDisplayChanged(displayId: Int) {}
      }

  override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
    sink = events
    handler = Handler(Looper.getMainLooper())
    displayManager?.registerDisplayListener(displayListener, handler)
  }

  override fun onCancel(arguments: Any?) {
    sink = null
    handler = null
    displayManager?.unregisterDisplayListener(displayListener)
  }
}
