package com.example.aichat.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.util.DisplayMetrics
import android.view.Display
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ScreenControlService : AccessibilityService() {

    companion object {
        var instance: ScreenControlService? = null
            private set
        var screenWidth: Int = 1080
        var screenHeight: Int = 1920

        fun isAvailable(): Boolean = instance != null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        try {
            val dm = DisplayMetrics()
            val wm = getSystemService(WINDOW_SERVICE) as? WindowManager
            wm?.defaultDisplay?.getRealMetrics(dm)
            screenWidth = dm.widthPixels
            screenHeight = dm.heightPixels
        } catch (_: Exception) {}
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    /** 获取所有可见节点的文本（扁平列表） */
    fun getAccessibilityTree(): String {
        val sb = StringBuilder()
        try {
            val root = rootInActiveWindow ?: return "(无活跃窗口)"
            dumpNode(root, sb, 0)
            root.recycle()
        } catch (e: Exception) {
            sb.append("(读取界面失败: ${e.message})")
        }
        return sb.toString().take(15000)
    }

    private fun dumpNode(node: AccessibilityNodeInfo, sb: StringBuilder, depth: Int) {
        if (depth > 20) return
        try {
            val indent = "  ".repeat(depth)
            val cls = node.className?.toString()?.substringAfterLast('.') ?: "?"
            val text = node.text?.toString()?.trim()?.take(60) ?: ""
            val desc = node.contentDescription?.toString()?.trim()?.take(60) ?: ""
            val bounds = Rect().also { node.getBoundsInScreen(it) }
            val clickable = if (node.isClickable) " [可点击]" else ""
            val checkable = if (node.isCheckable) " [${if (node.isChecked) "✓" else "☐"}]" else ""

            if (text.isNotBlank() || desc.isNotBlank() || node.isClickable) {
                sb.appendLine("$indent$cls: ${if (text.isNotBlank()) "「$text」" else if (desc.isNotBlank()) "($desc)" else ""}$clickable$checkable @(${bounds.left},${bounds.top})")
            }
            for (i in 0 until Math.min(node.childCount, 50)) {
                val child = node.getChild(i) ?: continue
                dumpNode(child, sb, depth + 1)
                child.recycle()
            }
        } catch (_: Exception) {}
    }

    /** 在 (x, y) 处执行点击 */
    fun performClick(x: Float, y: Float): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    /** 从 (x1,y1) 滑动到 (x2,y2) */
    fun performSwipe(x1: Float, y1: Float, x2: Float, y2: Float, duration: Long = 300): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        val path = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    /** 向当前聚焦的输入框输入文本 */
    fun performSetText(text: String): Boolean {
        try {
            val root = rootInActiveWindow ?: return false
            val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                ?: return false
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            val result = focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            focused.recycle()
            root.recycle()
            return result
        } catch (_: Exception) { return false }
    }

    /** 按文本查找并点击节点 */
    fun findAndClickByText(text: String): Boolean {
        var root: AccessibilityNodeInfo? = null
        val parents = mutableSetOf<AccessibilityNodeInfo>()
        try {
            root = rootInActiveWindow ?: return false
            val nodes = root.findAccessibilityNodeInfosByText(text)
            try {
                for (node in nodes) {
                    if (node.isClickable && node.isEnabled) {
                        return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    }
                    var parent = node.parent
                    while (parent != null) {
                        parents.add(parent)
                        if (parent.isClickable && parent.isEnabled) {
                            return parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        }
                        val grand = parent.parent
                        parent = grand
                    }
                }
            } finally {
                nodes.forEach { it.recycle() }
                parents.forEach { it.recycle() }
            }
        } catch (_: Exception) {
        } finally {
            root?.recycle()
        }
        return false
    }

    /** 截屏并压缩为 JPEG。Android 11+ 的无障碍服务支持 takeScreenshot，返回 null 表示不可用/失败。 */
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.R)
    fun captureScreenshotJpeg(maxWidth: Int = 1280, quality: Int = 80): ByteArray? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val latch = CountDownLatch(1)
        var bitmap: Bitmap? = null
        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshot: ScreenshotResult) {
                        try {
                            val hb = screenshot.hardwareBuffer
                            val raw = Bitmap.wrapHardwareBuffer(hb, screenshot.colorSpace)
                            hb.close()
                            if (raw != null) {
                                bitmap = raw.copy(Bitmap.Config.ARGB_8888, false)
                                raw.recycle()
                            }
                        } catch (_: Exception) {
                        } finally {
                            latch.countDown()
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        latch.countDown()
                    }
                })
            latch.await(6, TimeUnit.SECONDS)
        } catch (_: Exception) {
            return null
        }
        val bmp = bitmap ?: return null
        val scaled = if (bmp.width > maxWidth) {
            val h = (bmp.height.toLong() * maxWidth / bmp.width).toInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(bmp, maxWidth, h, true).also { bmp.recycle() }
        } else bmp
        return try {
            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
            out.toByteArray()
        } catch (_: Exception) {
            null
        } finally {
            scaled.recycle()
        }
    }

    /** 按返回键 */
    fun performBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)

    /** 按 Home 键 */
    fun performHome(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)
}
