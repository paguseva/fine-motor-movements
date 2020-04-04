package com.hse.classificator;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.WindowManager;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.hse.classificator.tflite.Classifier;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;

import com.hse.classificator.R;
import com.hse.classificator.env.Logger;

import static java.lang.Math.sqrt;

public class ClassifierActivity extends AppCompatActivity implements SensorEventListener {
  private static Logger LOGGER = new Logger();
  private Classifier classifier;
  private Handler handler;
  private HandlerThread handlerThread;

  private int maxSampleSize;
  private long gyroSteps = 0L;
  private long accSteps = 0L;
  private float gyroStd = 0.0f;
  private float accStd = 0.0f;
  private Deque <float[]> samples;
  private long detectionCount = 0L;
  private long detectionFreq = 120L;

  private SensorManager sensorManager = null;
  private Sensor accSensor = null;
  private Sensor gyroSensor = null;
  private boolean is_processing = false;

  private TextView gestureName1, gestureName2, gestureName3;
  private TextView gestureProb1, gestureProb2, gestureProb3;

  private MediaPlayer player;

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    LOGGER.d("onCreate " + this);
    super.onCreate(null);
    getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

    samples = new ArrayDeque<float[]>(91);
    gyroStd = getIntent().getFloatExtra("gyroStd", 0);
    accStd = getIntent().getFloatExtra("accStd", 0);
    maxSampleSize = getResources().getInteger(R.integer.maxSampleSize);
    sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
    accSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION);
    gyroSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
    player = new MediaPlayer();
    createClassifier();

    setContentView(R.layout.activity_classifier);

    gestureName1 = findViewById(R.id.gestureName1);
    gestureName2 = findViewById(R.id.gestureName2);
    gestureName3 = findViewById(R.id.gestureName3);

    gestureProb1 = findViewById(R.id.gestureProb1);
    gestureProb2 = findViewById(R.id.gestureProb2);
    gestureProb3 = findViewById(R.id.gestureProb3);
  }

  @Override
  protected void onDestroy() {
    super.onDestroy();
    player.release();
    player = null;
  }

  private void createClassifier() {
    if (classifier != null) {
      LOGGER.d("Closing classifier.");
      classifier.close();
      classifier = null;
    }
    Classifier.Model model = Classifier.Model.FLOAT_EFFICIENTNET;
    Classifier.Device dev = Classifier.Device.GPU;
    int numThreads = 1;
    try {
      LOGGER.d(
          "Creating classifier (model=%s, device=%s, numThreads=%d)", model, dev, numThreads);
      classifier = Classifier.create(this);
    } catch (IOException e) {
      LOGGER.e(e, "Failed to create classifier.");
    }
  }

  @Override
  public synchronized void onPause() {
    sensorManager.unregisterListener(this);
    sensorManager.unregisterListener(this);

    handlerThread.quitSafely();
    try {
      handlerThread.join();
      handlerThread = null;
      handler = null;
    } catch (InterruptedException e) {
      LOGGER.e(e, "Exception!");
    }

    super.onPause();
  }

  @Override
  public synchronized void onResume() {
    super.onResume();

    handlerThread = new HandlerThread("inference");
    handlerThread.start();
    handler = new Handler(handlerThread.getLooper());

    sensorManager.registerListener(
      this, accSensor,
      3 * SensorManager.SENSOR_DELAY_GAME
    );
    sensorManager.registerListener(
      this, gyroSensor,
      3 * SensorManager.SENSOR_DELAY_GAME
    );
  }

  @Override
  public synchronized void onSensorChanged(SensorEvent event) {
    LOGGER.d("Detected sensor change");
    float[] sample;
    if (samples.isEmpty()) {
      sample = new float[6];
      Arrays.fill(sample, 0.0f);
    } else {
      sample = samples.getLast();
    }
    long steps;
    float std;
    int start_ind;
    if (event.sensor.getType() == Sensor.TYPE_LINEAR_ACCELERATION) {
      steps = ++accSteps;
      std = accStd;
      start_ind = 0;
    } else {
      steps = ++gyroSteps;
      std = gyroStd;
      start_ind = 3;
    }
    for (int i = start_ind; i < start_ind + 3; i++)
      sample[i] = event.values[0] - std * (float) sqrt(steps);
    samples.addLast(sample);
    if (samples.size() >= maxSampleSize) {
      LOGGER.d("Collected enough samples to classify a gesture");
      float[][] input = samples.toArray(new float[maxSampleSize][6]);
      samples.removeFirst();
      detectionCount++;
      if (detectionCount % detectionFreq == 1 && !is_processing)
        runInBackground(
          new Runnable() {
            @Override
            public void run() {
              is_processing = true;
              if (classifier != null) {
                List <Classifier.Recognition> results = classifier.recognizeGesture(input);
                LOGGER.v("Detect: %s", results);

                runOnUiThread(
                  new Runnable() {
                    @Override
                    public void run() {
                      if (results != null && results.size() >= 3) {
                        Classifier.Recognition rec = results.get(0);
                        if (rec != null) {
                          if (rec.getTitle() != null) {
                            gestureName1.setText(rec.getTitle());
                            try {
                              AssetFileDescriptor afd = getAssets().openFd(
                                      rec.getTitle() + ".mp3");
                              LOGGER.i(rec.getTitle() + ".mp3");
                              player.reset();
                              player.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
                              player.prepare();
                              player.start();
                            } catch (IOException e) {
                              LOGGER.e(e, "IO Unable to play mp3 for " + rec.getTitle());
                            } catch (IllegalArgumentException e) {
                              LOGGER.e(e, "Illegal Unable to play mp3 for " + rec.getTitle());
                            }
                          }
                          if (rec.getConfidence() != null) gestureProb1.setText(
                            String.format("%.2f", rec.getConfidence())
                          );
                        }
                        rec = results.get(1);
                        if (rec != null) {
                          if (rec.getTitle() != null) gestureName2.setText(rec.getTitle());
                          if (rec.getConfidence() != null) gestureProb2.setText(
                            String.format("%.2f", rec.getConfidence())
                          );
                        }
                        rec = results.get(2);
                        if (rec != null) {
                          if (rec.getTitle() != null) gestureName3.setText(rec.getTitle());
                          if (rec.getConfidence() != null) gestureProb3.setText(
                            String.format("%.2f", rec.getConfidence())
                          );
                        }
                      }
                    }
                });
              }
              is_processing = false;
            }
        });
    }
  }

  @Override
  public void onAccuracyChanged(Sensor sensor, int accuracy) {

  }

  private synchronized void runInBackground(Runnable r) {
    if (handler != null) {
      handler.post(r);
    }
  }
}
