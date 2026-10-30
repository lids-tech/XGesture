package com.slideindex.app.overlay

import androidx.compose.ui.geometry.Offset
import com.slideindex.app.settings.AppSettings
import com.slideindex.app.settings.FloatBallSide
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

internal class FloatBallDragSession {
  var dragFingerX = 0f
  var dragFingerY = 0f
  private var dragFingerAnchorX = 0f
  private var dragFingerAnchorY = 0f
  private var dragPointerAnchorX = 0f
  private var dragPointerAnchorY = 0f
  var dragJoystickOffsetX = 0f
  var dragJoystickOffsetY = 0f
  private var pointerTravelWidth = 0f
  private var pointerTravelHeight = 0f
  private var pickZoneWidth = 0f
  private var pickZoneHeight = 0f
  private var pickDockSide: FloatBallSide = FloatBallSide.RIGHT
  var pointerModeActive = false
    private set

  fun reset() {
    dragFingerX = 0f
    dragFingerY = 0f
    dragFingerAnchorX = 0f
    dragFingerAnchorY = 0f
    dragPointerAnchorX = 0f
    dragPointerAnchorY = 0f
    dragJoystickOffsetX = 0f
    dragJoystickOffsetY = 0f
    pointerTravelWidth = 0f
    pointerTravelHeight = 0f
    pickZoneWidth = 0f
    pickZoneHeight = 0f
    pickDockSide = FloatBallSide.RIGHT
    pointerModeActive = false
  }

  fun armAtTouch(
    settings: AppSettings,
    touchDownX: Float,
    touchDownY: Float,
    fingerX: Float,
    fingerY: Float,
    ballCenterX: Float,
    ballCenterY: Float,
    ballSize: FloatBallSize,
    screenWidth: Float,
    screenHeight: Float,
    density: Float,
    pickDockSide: FloatBallSide
  ) {
    this.pickDockSide = pickDockSide
    dragFingerX = fingerX
    dragFingerY = fingerY
    dragFingerAnchorX = fingerX
    dragFingerAnchorY = fingerY
    dragJoystickOffsetX = ballCenterX - touchDownX
    dragJoystickOffsetY = ballCenterY - touchDownY
    pointerModeActive = false
    establishPointerTravel(settings, screenWidth, screenHeight)

    val pick = edgeAnchoredPick(
      settings = settings,
      ballCenterY = ballCenterY,
      ballHeightPx = ballSize.heightPx.toFloat(),
      screenWidth = screenWidth,
      screenHeight = screenHeight,
      density = density,
      dockSide = pickDockSide
    )
    dragPointerAnchorX = pick.x +
      outsideSign() * FloatingPointerBounds.PICK_ANCHOR_OUTSIDE_DP * density
    dragPointerAnchorY = pick.y
  }

  /**
   * 锚点向"屏幕外"偏移的方向：右边缘唤醒 → +1（更靠右，出屏），左边缘唤醒 → −1。
   *
   * 目的：红十字第一帧落在屏幕外，随后被拖进来，观感是"从边缘滑入"而不是"贴在边上然后跳"。
   */
  private fun outsideSign(): Float =
    if (pickDockSide == FloatBallSide.LEFT) -1f else 1f

  fun updateFingerPosition(fingerX: Float, fingerY: Float) {
    dragFingerX = fingerX
    dragFingerY = fingerY
  }

  fun onFingerMove(dx: Float, dy: Float) {
    dragFingerX += dx
    dragFingerY += dy
  }

  fun fingerTravelPx(): Float =
    hypot(dragFingerX - dragFingerAnchorX, dragFingerY - dragFingerAnchorY)

  fun ballCenter(): Offset =
    Offset(dragFingerX + dragJoystickOffsetX, dragFingerY + dragJoystickOffsetY)

  fun clampedBallCenter(
    ballSize: FloatBallSize,
    marginPx: Int,
    screenWidth: Int,
    screenHeight: Int
  ): Offset {
    val center = ballCenter()
    val halfWidth = ballSize.widthPx / 2f
    val halfHeight = ballSize.heightPx / 2f
    val minCenterX = -halfWidth
    val maxCenterX = screenWidth + halfWidth
    val minCenterY = marginPx + halfHeight
    val maxCenterY = screenHeight - marginPx - halfHeight
    return Offset(
      x = center.x.coerceIn(minCenterX, maxCenterX),
      y = center.y.coerceIn(minCenterY, maxCenterY.coerceAtLeast(minCenterY))
    )
  }

  fun ballTopLeft(
    ballSize: FloatBallSize,
    marginPx: Int,
    screenWidth: Int,
    screenHeight: Int
  ): Pair<Int, Int> {
    val center = clampedBallCenter(
      ballSize = ballSize,
      marginPx = marginPx,
      screenWidth = screenWidth,
      screenHeight = screenHeight
    )
    val left = (center.x - ballSize.widthPx / 2f).roundToInt()
    val top = (center.y - ballSize.heightPx / 2f).roundToInt()
    return left to top
  }

  fun computePick(
    settings: AppSettings,
    ballSize: FloatBallSize,
    screenWidth: Float,
    screenHeight: Float,
    density: Float,
    marginPx: Int
  ): Offset {
    if (pointerTravelWidth <= 0f || pointerTravelHeight <= 0f) {
      establishPointerTravel(settings, screenWidth, screenHeight)
    }

    val deltaX = dragFingerX - dragFingerAnchorX
    val deltaY = dragFingerY - dragFingerAnchorY
    // 零位移直接返回锚点本身（锚点在屏幕外，所以这一帧红十字不可见，避免"贴边出现"）。
    if (deltaX == 0f && deltaY == 0f) {
      return Offset(dragPointerAnchorX, dragPointerAnchorY)
    }
    pointerModeActive = true

    val movedX = FloatingPointerBounds.pickCursorTravelPx(
      delta = deltaX,
      travelPx = pointerTravelWidth,
      screenPx = screenWidth,
      zonePx = pickZoneWidth
    )
    val movedY = FloatingPointerBounds.pickCursorTravelPx(
      delta = deltaY,
      travelPx = pointerTravelHeight,
      screenPx = screenHeight,
      zonePx = pickZoneHeight
    )
    // 夹取范围必须容纳"屏外锚点"，否则起点会被夹回边上，又变回"贴边出现"。
    return Offset(
      x = (dragPointerAnchorX + movedX)
        .coerceIn(min(0f, dragPointerAnchorX), max(screenWidth, dragPointerAnchorX)),
      y = (dragPointerAnchorY + movedY)
        .coerceIn(min(0f, dragPointerAnchorY), max(screenHeight, dragPointerAnchorY))
    )
  }

  fun refreshPointerTravel(settings: AppSettings, screenWidth: Float, screenHeight: Float) {
    if (pointerTravelWidth <= 0f && pointerTravelHeight <= 0f) return
    establishPointerTravel(settings, screenWidth, screenHeight)
  }

  private fun edgeAnchoredPick(
    settings: AppSettings,
    ballCenterY: Float,
    ballHeightPx: Float,
    screenWidth: Float,
    screenHeight: Float,
    density: Float,
    dockSide: FloatBallSide
  ): Offset = FloatBallPickAnchor.pickPointAtEdge(
    settings = settings,
    ballCenterY = ballCenterY,
    ballHeightPx = ballHeightPx,
    screenWidth = screenWidth,
    screenHeight = screenHeight,
    density = density,
    dockSide = dockSide
  )

  private fun establishPointerTravel(settings: AppSettings, screenWidth: Float, screenHeight: Float) {
    val horizontalSpeed = settings.floatBallPointerSpeedFraction.coerceIn(
      FloatingPointerBounds.PICK_POINTER_SPEED_MIN,
      FloatingPointerBounds.SENSITIVITY_MAX
    )
    val verticalSpeed = settings.floatBallPointerSpeedVerticalFraction.coerceIn(
      FloatingPointerBounds.PICK_POINTER_SPEED_MIN,
      FloatingPointerBounds.SENSITIVITY_MAX
    )
    val (travelWidth, _) = FloatingPointerBounds.effectivePointerTravelForSpeed(
      speedFraction = horizontalSpeed,
      screenWidth = screenWidth,
      screenHeight = screenHeight
    )
    val (_, travelHeight) = FloatingPointerBounds.effectivePointerTravelForSpeed(
      speedFraction = verticalSpeed,
      screenWidth = screenWidth,
      screenHeight = screenHeight
    )
    pointerTravelWidth = travelWidth
    pointerTravelHeight = travelHeight
    val zoneFraction = settings.floatBallPickAccelZoneFraction
    pickZoneWidth = FloatingPointerBounds.pickAccelZonePx(zoneFraction, pointerTravelWidth)
    // ⚠️ 加速区**只作用于横向**。纵向行程本来就很长（≈0.6 屏高）且手指能走的竖直距离有限，
    // 若同样加速，上下移动会被明显放大、取词变难；这里固定 0 = 保持原来的线性手感。
    pickZoneHeight = 0f
  }
}
