package com.iips.launcher.ui.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.location.Location
import android.util.AttributeSet
import android.view.View

class GeofenceRadarView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var azimuth: Float = 0f
    private var currentLat: Double? = null
    private var currentLng: Double? = null
    private var expectedLat: Double? = null
    private var expectedLng: Double? = null
    private var radiusM: Double = 30.0

    private val safeZonePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#334CAF50") // Translucent Green
        style = Paint.Style.FILL
    }
    
    private val safeZoneBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#884CAF50")
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }

    private val centerCrosshairPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    private val userDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.RED
        style = Paint.Style.FILL
    }
    
    private val userDotBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    private val radarRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#22FFFFFF")
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    fun updateData(currLat: Double?, currLng: Double?, expLat: Double?, expLng: Double?, radius: Double?) {
        this.currentLat = currLat
        this.currentLng = currLng
        this.expectedLat = expLat
        this.expectedLng = expLng
        if (radius != null) this.radiusM = radius
        invalidate()
    }

    fun updateAzimuth(azimuthDegrees: Float) {
        this.azimuth = azimuthDegrees
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        
        // We want the geofence radius to take up 60% of the view's radius
        val maxRadius = minOf(cx, cy)
        val drawingRadiusPixels = maxRadius * 0.6f
        
        // Draw background radar rings
        canvas.drawCircle(cx, cy, maxRadius * 0.3f, radarRingPaint)
        canvas.drawCircle(cx, cy, maxRadius * 0.6f, radarRingPaint)
        canvas.drawCircle(cx, cy, maxRadius * 0.9f, radarRingPaint)
        
        // Save canvas state before rotation
        canvas.save()
        
        // Rotate the entire canvas opposite to the device's compass azimuth
        // This makes the radar act like a true compass (North always points "up" physically)
        canvas.rotate(-azimuth, cx, cy)
        
        // Draw the authorized zone (at the center)
        canvas.drawCircle(cx, cy, drawingRadiusPixels, safeZonePaint)
        canvas.drawCircle(cx, cy, drawingRadiusPixels, safeZoneBorderPaint)
        
        // Draw Center Crosshair (+)
        canvas.drawLine(cx - 15f, cy, cx + 15f, cy, centerCrosshairPaint)
        canvas.drawLine(cx, cy - 15f, cx, cy + 15f, centerCrosshairPaint)
        
        val cLat = currentLat
        val cLng = currentLng
        val eLat = expectedLat
        val eLng = expectedLng
        
        if (cLat != null && cLng != null && eLat != null && eLng != null) {
            val expectedLoc = Location("").apply { latitude = eLat; longitude = eLng }
            val currentLoc = Location("").apply { latitude = cLat; longitude = cLng }
            
            val distance = expectedLoc.distanceTo(currentLoc)
            val bearing = expectedLoc.bearingTo(currentLoc) // Bearing in degrees from North
            
            // Calculate scale: pixels per meter
            val pixelsPerMeter = drawingRadiusPixels / radiusM
            
            // Distance in pixels from center
            val dotDistPixels = (distance * pixelsPerMeter).toFloat()
            
            // Convert bearing to radians. Bearing 0 is North (up -> -y).
            // In Android Canvas, 0 degrees is positive X (Right). 
            // Bearing is measured clockwise from North.
            // So angle for Math.sin/cos needs to be adjusted.
            // X = sin(bearing) * distance
            // Y = -cos(bearing) * distance
            val bearingRad = Math.toRadians(bearing.toDouble())
            
            val dotX = cx + (Math.sin(bearingRad) * dotDistPixels).toFloat()
            val dotY = cy - (Math.cos(bearingRad) * dotDistPixels).toFloat()
            
            // Draw the user dot
            canvas.drawCircle(dotX, dotY, 12f, userDotPaint)
            canvas.drawCircle(dotX, dotY, 12f, userDotBorderPaint)
        }
        
        canvas.restore()
    }
}
