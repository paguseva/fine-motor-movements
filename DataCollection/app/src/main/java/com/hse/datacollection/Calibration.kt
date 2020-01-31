package com.hse.datacollection

import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.*
import androidx.appcompat.app.AppCompatActivity
import android.util.Log
import android.view.View
import kotlinx.android.synthetic.main.activity_calibration.*


class Calibration : AppCompatActivity() {
    private var calibrationTime = 0L
    private var accStd = 0f
    private var gyroStd = 0f

    fun average(a: ArrayList <FloatArray>) : Float {
        var sum = 0f
        for (i in a.indices)
            sum += a[i].sum()
        return sum / (3 * a.size)
    }

    fun findStd(a: ArrayList <FloatArray>, avg: Float) : Float {
        var sum = 0f
        for (i in a.indices)
            for (j in 0..2)
                sum += (a[i][j] - avg) * (a[i][j] - avg)
        return sum / (3 * a.size)
    }

    private fun vibrate() {
        val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        if (Build.VERSION.SDK_INT >= 26) {
            vibrator.vibrate(VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            vibrator.vibrate(100)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_calibration)

        val gyroData = ArrayList <FloatArray> (0)
        val accData = ArrayList <FloatArray> (0)

        calibrationTime = getResources().getInteger(R.integer.calibrationTime).toLong()

        calBtn.setOnClickListener(object: View.OnClickListener, SensorEventListener {
            var sensorManager : SensorManager ?= null
            private var accSensor: Sensor ?= null
            private var gyroSensor: Sensor ?= null

            fun stopListening() {
                sensorManager!!.unregisterListener(this)
                sensorManager!!.unregisterListener(this)
            }

            override fun onClick(v: View?) {
                calBtn.isEnabled = false
                timer.text = calibrationTime.toString()

                sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
                accSensor = sensorManager!!.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
                gyroSensor = sensorManager!!.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

                sensorManager!!.registerListener(this, accSensor,
                    SensorManager.SENSOR_DELAY_GAME)
                sensorManager!!.registerListener(this, gyroSensor,
                    SensorManager.SENSOR_DELAY_GAME)

                val timer = object: CountDownTimer(calibrationTime * 1000, 1000) {
                    override fun onTick(millisUntilFinished: Long) {
                        timer.text = (millisUntilFinished / 1000).toString()
                    }

                    override fun onFinish() {
                        timer.text = getResources().getString(R.string.completed)

                        stopListening()

                        accStd = findStd(accData, average(accData))
                        gyroStd = findStd(gyroData, average(gyroData))

                        Log.i("Calibrate", "Calibration complete: accStd=$accStd, gyroStd=$gyroStd")
                        vibrate()

                        calBtn.isEnabled = true
                    }
                }
                timer.start()
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
            }

            override fun onSensorChanged(event: SensorEvent) {
                if (event.sensor.type == Sensor.TYPE_LINEAR_ACCELERATION)
                    accData.add(event.values)
                else
                    gyroData.add(event.values)
            }
        })

        seqBtn.setOnClickListener {
            val intent = Intent(this@Calibration, ActionSequence::class.java)
            intent.putExtra("gyroStd", gyroStd)
            intent.putExtra("accStd", accStd)
            startActivity(intent)
        }

        actBtn.setOnClickListener {
            val intent = Intent(this@Calibration, SingleAction::class.java)
            intent.putExtra("gyroStd", gyroStd)
            intent.putExtra("accStd", accStd)
            startActivity(intent)
        }
    }
}
