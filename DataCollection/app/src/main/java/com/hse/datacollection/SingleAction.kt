package com.hse.datacollection

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.*
import android.text.method.LinkMovementMethod
import android.util.Log
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import kotlinx.android.synthetic.main.activity_single_action.*
import java.io.File
import java.io.FileOutputStream

class SingleAction : AppCompatActivity() {
    private var gyroStd = 0f
    private var accStd = 0f
    private var gyroSteps = 0
    private var accSteps = 0
    private var actionTime = 0L
    private var directory : File ?= null

    private fun vibrate() {
        val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        if (Build.VERSION.SDK_INT >= 26) {
            vibrator.vibrate(VibrationEffect.createOneShot(100, VibrationEffect.EFFECT_HEAVY_CLICK))
        } else {
            vibrator.vibrate(100)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        gyroStd = intent.getFloatExtra("gyroStd", 0f)
        accStd = intent.getFloatExtra("accStd", 0f)
        actionTime = getResources().getInteger(R.integer.minActionTime).toLong()
        setContentView(R.layout.activity_single_action)

        tutorial.setMovementMethod(LinkMovementMethod.getInstance())

        directory = getExternalFilesDir(null)

        actionBtn.setOnClickListener(object: View.OnClickListener, SensorEventListener {
            var sensorManager : SensorManager ?= null
            private var accSensor: Sensor ?= null
            private var gyroSensor: Sensor ?= null

            private lateinit var accFile: File
            private lateinit var gyroFile: File

            fun stopListening() {
                sensorManager!!.unregisterListener(this)
                sensorManager!!.unregisterListener(this)
            }

            override fun onClick(v: View?) {
                actionBtn.isEnabled = false
                actions_spinner.isEnabled = false
                timer.text = actionTime.toString()

                sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
                accSensor = sensorManager!!.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
                gyroSensor = sensorManager!!.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

                sensorManager!!.registerListener(this, accSensor,
                    SensorManager.SENSOR_DELAY_GAME)
                sensorManager!!.registerListener(this, gyroSensor,
                    SensorManager.SENSOR_DELAY_GAME)

                accFile = File(directory, "single_action_acc_${actions_spinner.getSelectedItem()}.csv")
                gyroFile = File(directory, "single_action_gyro_${actions_spinner.getSelectedItem()}.csv")

                val timer = object: CountDownTimer(actionTime * 1000, 1000) {
                    override fun onTick(millisUntilFinished: Long) {
                        timer.text = (millisUntilFinished / 1000).toString()
                    }

                    override fun onFinish() {
                        timer.text = "Done!"

                        stopListening()

                        Log.i("SingleAction", "Recording complete!")
                        vibrate()

                        actionBtn.isEnabled = true
                        actions_spinner.isEnabled = true
                    }
                }
                timer.start()
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
            }

            override fun onSensorChanged(event: SensorEvent) {
                lateinit var file: File
                val std:Float
                val steps:Double
                if (event.sensor.getType() == Sensor.TYPE_LINEAR_ACCELERATION) {
                    file = accFile
                    steps = (++accSteps).toDouble()
                    std = accStd
                } else {
                    file = gyroFile
                    steps = (++gyroSteps).toDouble()
                    std = gyroStd
                }
                val result = arrayOf(
                    event.values[0] - std * Math.sqrt(steps),
                    event.values[1] - std * Math.sqrt(steps),
                    event.values[2] - std * Math.sqrt(steps)
                )
                val data = "${result[0]},${result[1]},${result[2]},${actions_spinner.getSelectedItem()}"
                try {
                    FileOutputStream(file, true).bufferedWriter().use { writer ->
                        writer.appendln(data)
                    }
                } catch (e: Exception){
                    e.printStackTrace()
                }
            }
        })

        clearBtn.setOnClickListener {
            clearBtn.isEnabled = false
            val listAllFiles = directory!!.listFiles()

            if (listAllFiles != null && listAllFiles.isNotEmpty()) {
                for (currentFile in listAllFiles)
                    if (currentFile.name.startsWith("single_action_"))
                        currentFile.delete()
            }
            clearBtn.isEnabled = true
        }
    }
}
