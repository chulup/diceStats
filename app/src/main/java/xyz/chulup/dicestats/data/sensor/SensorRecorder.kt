package xyz.chulup.dicestats.data.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Records the phone's motion sensors while the camera is open, so each roll photo can carry how
 * the phone was held and moving when it was taken (stored as a `.sensors.json` sidecar next to
 * the photo, see `PhotoStorage.sensorsFile`). Collected for later analysis — e.g. whether
 * misread dice cluster at steep camera tilts or with a shaking hand — not used for recognition.
 *
 * [start]/[stop] follow the camera screen; [snapshot] is taken once the photo is saved and covers
 * the motion since the shutter press ([markShutter]), which spans the exposure.
 */
class SensorRecorder(context: Context) : SensorEventListener {

    private val manager = context.getSystemService(SensorManager::class.java)
    private val latest = HashMap<Int, FloatArray>()
    /** (timestamp ns, gyro rad/s magnitude or NaN, linear acceleration m/s² magnitude or NaN). */
    private val motion = ArrayDeque<Triple<Long, Float, Float>>()
    @Volatile private var shutterNs = 0L

    fun start() {
        for (type in TYPES) {
            manager?.getDefaultSensor(type)?.let { manager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        }
    }

    fun stop() {
        manager?.unregisterListener(this)
    }

    fun markShutter() {
        shutterNs = SystemClock.elapsedRealtimeNanos()
    }

    override fun onSensorChanged(event: SensorEvent) {
        val values = event.values.copyOf()
        synchronized(this) {
            latest[event.sensor.type] = values
            val mag = sqrt(values[0] * values[0] + values[1] * values[1] + values[2] * values[2])
            when (event.sensor.type) {
                Sensor.TYPE_GYROSCOPE -> motion.addLast(Triple(event.timestamp, mag, Float.NaN))
                Sensor.TYPE_LINEAR_ACCELERATION -> motion.addLast(Triple(event.timestamp, Float.NaN, mag))
            }
            while (motion.isNotEmpty() && event.timestamp - motion.first().first > WINDOW_NS) motion.removeFirst()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit

    /** Current orientation plus motion from the shutter press (or the last second) until now. */
    fun snapshot(): JSONObject = synchronized(this) {
        val now = SystemClock.elapsedRealtimeNanos()
        val since = if (shutterNs in 1 until now) shutterNs else now - 1_000_000_000L
        val window = motion.filter { it.first >= since }
        val gyro = window.map { it.second }.filterNot { it.isNaN() }
        val accel = window.map { it.third }.filterNot { it.isNaN() }
        val json = JSONObject().put("shutterToSavedMs", if (shutterNs > 0) (now - shutterNs) / 1e6 else JSONObject.NULL)
        latest[Sensor.TYPE_GRAVITY]?.let { g ->
            json.put("gravity", g.toJson())
            cameraTiltDegrees(g)?.let { json.put("tiltDeg", it.toDouble()) }
            json.put("upInImageDeg", upInImageDegrees(g).toDouble())
        }
        latest[Sensor.TYPE_GAME_ROTATION_VECTOR]?.let { json.put("rotationVector", it.toJson()) }
        latest[Sensor.TYPE_LIGHT]?.let { json.put("lightLux", it[0].toDouble()) }
        json.put("gyroMaxRadS", gyro.maxOrNull()?.toDouble() ?: JSONObject.NULL)
            .put("gyroMeanRadS", gyro.takeIf { it.isNotEmpty() }?.average() ?: JSONObject.NULL)
            .put("linearAccelMaxMs2", accel.maxOrNull()?.toDouble() ?: JSONObject.NULL)
            .put("motionSamples", window.size)
    }

    private fun FloatArray.toJson() = JSONArray(map { it.toDouble() })

    companion object {
        private val TYPES = listOf(
            Sensor.TYPE_GRAVITY,
            Sensor.TYPE_GAME_ROTATION_VECTOR,
            Sensor.TYPE_GYROSCOPE,
            Sensor.TYPE_LINEAR_ACCELERATION,
            Sensor.TYPE_LIGHT,
        )
        private const val WINDOW_NS = 3_000_000_000L
    }
}

/**
 * Angle in degrees between the back camera's view direction (the device's −Z axis) and straight
 * down, from a gravity vector in device coordinates: 0 = phone flat above the table, 90 = upright.
 * Null when [gravity] is degenerate.
 */
fun cameraTiltDegrees(gravity: FloatArray): Float? {
    val norm = sqrt(gravity[0] * gravity[0] + gravity[1] * gravity[1] + gravity[2] * gravity[2])
    if (norm < 1e-3f) return null
    // Phone flat, screen up: gravity reads (0, 0, +g) and the back camera looks straight down.
    return Math.toDegrees(acos((gravity[2] / norm).coerceIn(-1f, 1f).toDouble())).toFloat()
}

/**
 * Direction of "up" (away from the ground) projected onto the screen plane, in degrees
 * counter-clockwise from the device's +Y axis (top edge in portrait). Meaningless near 0 tilt.
 */
fun upInImageDegrees(gravity: FloatArray): Float =
    Math.toDegrees(atan2(-gravity[0].toDouble(), gravity[1].toDouble())).toFloat()
