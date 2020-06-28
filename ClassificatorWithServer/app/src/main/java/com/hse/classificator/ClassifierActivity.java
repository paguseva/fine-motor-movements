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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

import com.hse.classificator.env.Logger;
import com.hse.classificator.env.ParameterStringBuilder;

import org.json.*;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import static java.lang.Math.sqrt;

public class ClassifierActivity extends AppCompatActivity implements SensorEventListener {
  private static Logger LOGGER = new Logger();
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

  private TextView[] gestures = new TextView[3];
  private TextView[] gestureProbs = new TextView[3];

  private MediaPlayer player;

  private String serverURL = null;

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    LOGGER.d("onCreate " + this);
    super.onCreate(null);
    getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

    samples = new ArrayDeque<>(91);
    gyroStd = getIntent().getFloatExtra("gyroStd", 0);
    accStd = getIntent().getFloatExtra("accStd", 0);
    serverURL = "https://" + getIntent().getStringExtra("serverURL");

    maxSampleSize = getResources().getInteger(R.integer.maxSampleSize);
    sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
    accSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION);
    gyroSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
    player = new MediaPlayer();

    setContentView(R.layout.activity_classifier);

    gestures[0] = findViewById(R.id.gestureName1);
    gestures[1] = findViewById(R.id.gestureName2);
    gestures[2] = findViewById(R.id.gestureName3);

    gestureProbs[0] = findViewById(R.id.gestureProb1);
    gestureProbs[1] = findViewById(R.id.gestureProb2);
    gestureProbs[2] = findViewById(R.id.gestureProb3);

    disableSSLCertificateChecking();
  }

  @Override
  protected void onDestroy() {
    super.onDestroy();
    player.release();
    player = null;
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
      2 * SensorManager.SENSOR_DELAY_GAME
    );
    sensorManager.registerListener(
      this, gyroSensor,
      2 * SensorManager.SENSOR_DELAY_GAME
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
      float[][] input = samples.toArray(new float[0][6]);
      samples.removeFirst();
      detectionCount++;
      if (detectionCount % detectionFreq == 1 && !is_processing)
        runInBackground(() -> {
          is_processing = true;
          try {
            Map<String, String> parameters = new HashMap<>();
            parameters.put("input", ParameterStringBuilder.convert2DArrayToString(input));

            URL url = new URL(serverURL + "?" + ParameterStringBuilder.getParamsString(parameters));
            HttpURLConnection con = (HttpURLConnection) url.openConnection();
            con.setConnectTimeout(40000);
            con.setReadTimeout(40000);
            con.setRequestProperty("Accept-Charset", "UTF-8");
            con.setRequestProperty("Content-Type", "application/json");

            int status_code = con.getResponseCode();
            LOGGER.i("Status code: %d", status_code);

            BufferedReader in = new BufferedReader(new InputStreamReader(con.getInputStream()));
            String inputLine;
            StringBuilder content = new StringBuilder();
            while ((inputLine = in.readLine()) != null)
              content.append(inputLine);
            in.close();
            con.disconnect();
            try {
              JSONArray res = new JSONArray(content.toString());
              runOnUiThread(() -> {
                for (int i = 0; i < 3; ++i) {
                  try {
                    gestures[i].setText(res.getJSONObject(i).getString("label"));
                    gestureProbs[i].setText(res.getJSONObject(i).getString("prob"));
                  } catch (JSONException e) {
                    LOGGER.w("Malformed JSON: %s", e.getMessage());
                  }
                }
                String bestGuessName = "No data";
                try {
                  bestGuessName = res.getJSONObject(0).getString("label").replace("-", "_");
                  int resID = getResources().getIdentifier(bestGuessName, "raw", getPackageName());
                  AssetFileDescriptor afd = getResources().openRawResourceFd(resID);
                  player.reset();
                  player.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
                  player.prepare();
                  player.start();
                } catch (IOException e) {
                  LOGGER.e(e, "IO Unable to play mp3 for %s", bestGuessName);
                } catch (IllegalArgumentException e) {
                  LOGGER.e(e, "Illegal Unable to play mp3 for %s", bestGuessName);
                } catch (JSONException e) {
                  LOGGER.w("Malformed JSON: %s", e.getMessage());
                }
              });
            } catch (JSONException e) {
              LOGGER.w("Unable to parse JSON: %s", e.getMessage());
            }
          } catch (IOException e) {
            LOGGER.i("Unable to connect to server: %s", e.getMessage());
          }
          // setStatus("Успешно!");
          is_processing = false;
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

  private static void disableSSLCertificateChecking() {
    TrustManager[] trustAllCerts = new TrustManager[] {
      new X509TrustManager() {
        @Override
        public void checkClientTrusted(java.security.cert.X509Certificate[] x509Certificates, String s) throws java.security.cert.CertificateException {
        }
        @Override
        public void checkServerTrusted(java.security.cert.X509Certificate[] x509Certificates, String s) throws java.security.cert.CertificateException {
        }
        @Override
        public java.security.cert.X509Certificate[] getAcceptedIssuers() {
          return null;
        }
      }
    };

    try {
      HttpsURLConnection.setDefaultHostnameVerifier(new HostnameVerifier() {
        @Override
        public boolean verify(String s, SSLSession sslSession) {
          return true;
        }
      });
      SSLContext sc = SSLContext.getInstance("TLS");
      sc.init(null, trustAllCerts, new java.security.SecureRandom());
      HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory());
    } catch (KeyManagementException | NoSuchAlgorithmException e) {
      e.printStackTrace();
    }
  }
}