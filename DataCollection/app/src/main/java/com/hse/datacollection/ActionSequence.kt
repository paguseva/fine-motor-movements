package com.hse.datacollection

import android.animation.*
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.*
import androidx.appcompat.app.AppCompatActivity
import android.text.method.LinkMovementMethod
import androidx.core.content.ContextCompat
import java.io.FileOutputStream
import kotlinx.android.synthetic.main.activity_action_sequence.*
import java.io.File
import kotlin.math.sqrt
import kotlin.random.Random


class ActionSequence: AppCompatActivity(), SensorEventListener {
    private lateinit var mainHandler:Handler
    private var sensorManager : SensorManager ?= null
    private var accSensor: Sensor ?= null
    private var gyroSensor: Sensor ?= null
    private lateinit var gyroFile:File
    private lateinit var accFile:File
    private var gyroStd = 0f
    private var accStd = 0f
    private var gyroSteps = 0
    private var accSteps = 0
    private var defaultActionTime = 0L

    private val gyroFileName = "gyro_data.csv"
    private val accFileName = "acc_data.csv"

    private lateinit var movementLabels:Array<String>

    private fun vibrate() {
        val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        if (Build.VERSION.SDK_INT >= 26) {
            vibrator.vibrate(VibrationEffect.createOneShot(100, VibrationEffect.EFFECT_HEAVY_CLICK))
        } else {
            vibrator.vibrate(100)
        }
    }

    private fun pause() {
        sensorManager!!.unregisterListener(this)
        sensorManager!!.unregisterListener(this)
        mainHandler.removeCallbacks(update)
    }

    private fun resume() {
        sensorManager!!.registerListener(
            this, accSensor,
            SensorManager.SENSOR_DELAY_GAME
        )
        sensorManager!!.registerListener(
            this, gyroSensor,
            SensorManager.SENSOR_DELAY_GAME
        )
        mainHandler.post(update)
    }

    private val update = object : Runnable {
        override fun run() {
            val delaySec = newAction()
            mainHandler.postDelayed(this, delaySec * 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        gyroStd = intent.getFloatExtra("gyroStd", 0f)
        accStd = intent.getFloatExtra("accStd", 0f)
        defaultActionTime = resources.getInteger(R.integer.minActionTime).toLong()
        movementLabels = resources.getStringArray(R.array.actions_array)
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accSensor = sensorManager!!.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        gyroSensor = sensorManager!!.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        setContentView(R.layout.activity_action_sequence)

        mainHandler = Handler(Looper.getMainLooper())

        tutorial.movementMethod = LinkMovementMethod.getInstance()

        val directory = getExternalFilesDir(null)
        accFile = File(directory, accFileName)
        gyroFile = File(directory, gyroFileName)

        actionBtn.setOnClickListener {
            actionBtn.isEnabled = false
            if (actionBtn.text == resources.getString(R.string.stop)) {
                pause()
                actionBtn.text = resources.getString(R.string.start)
                output.text = resources.getString(R.string.placeholder)
            } else {
                resume()
                actionBtn.text = resources.getString(R.string.stop)
            }
            actionBtn.isEnabled = true
        }

        clearBtn.setOnClickListener {
            clearBtn.isEnabled = false

            if (accFile.exists())
                accFile.delete()
            if (gyroFile.exists())
                gyroFile.delete()

            clearBtn.isEnabled = true
        }
    }

    override fun onResume() {
        super.onResume()
        if (actionBtn.text == resources.getString(R.string.stop))
            resume()
    }

    override fun onPause() {
        super.onPause()
        if (actionBtn.text == resources.getString(R.string.start))
            pause()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
    }

    override fun onSensorChanged(event: SensorEvent) {
        lateinit var file:File
        val std:Float
        val steps:Double
        if (event.sensor.type == Sensor.TYPE_LINEAR_ACCELERATION) {
            file = accFile
            steps = (++accSteps).toDouble()
            std = accStd
        } else {
            file = gyroFile
            steps = (++gyroSteps).toDouble()
            std = gyroStd
        }
        val result = arrayOf(
            event.values[0] - std * sqrt(steps),
            event.values[1] - std * sqrt(steps),
            event.values[2] - std * sqrt(steps)
        )
        val data = "${result[0]},${result[1]},${result[2]},${output.text}"
        try {
            FileOutputStream(file, true).bufferedWriter().use { writer ->
                writer.appendln(data)
            }
        } catch (e: Exception){
            e.printStackTrace()
        }
    }

    fun newAction() : Long {
        val nextActionId = Random.nextInt(movementLabels.size)
        output.text = movementLabels[nextActionId]
        vibrate()

        val objectAnimator = ObjectAnimator.ofObject(
            output,
            "backgroundColor",
            ArgbEvaluator(),
            ContextCompat.getColor(this, R.color.colorAccent),
            ContextCompat.getColor(this, android.R.color.transparent)
        )
        objectAnimator.repeatCount = 0
        objectAnimator.repeatMode = ValueAnimator.REVERSE
        objectAnimator.duration = 1000
        objectAnimator.start()

        return defaultActionTime
    }
}
