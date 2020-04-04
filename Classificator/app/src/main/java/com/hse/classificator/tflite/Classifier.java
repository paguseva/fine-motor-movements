package com.hse.classificator.tflite;

import android.app.Activity;
import android.os.SystemClock;
import android.os.Trace;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import org.tensorflow.lite.DataType;
import org.tensorflow.lite.Interpreter;
import com.hse.classificator.env.Logger;
import org.tensorflow.lite.gpu.GpuDelegate;
import org.tensorflow.lite.nnapi.NnApiDelegate;
import org.tensorflow.lite.support.common.FileUtil;
import org.tensorflow.lite.support.label.TensorLabel;
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer;

public class Classifier {
  private static Logger LOGGER = new Logger();
  private TensorBuffer inputBuffer;
  private TensorBuffer outputProbabilityBuffer;

  /** The model type used for classification. */
  public enum Model {
    FLOAT_MOBILENET,
    FLOAT_EFFICIENTNET,
  }

  /** The runtime device type used for executing classification. */
  public enum Device {
    CPU,
    GPU
  }

  /** Number of results to show in the UI. */
  private static int MAX_RESULTS = 3;

  /** The loaded TensorFlow Lite model. */
  private MappedByteBuffer tfliteModel;

  /** Optional GPU delegate for accleration. */
  private GpuDelegate gpuDelegate = null;

  /** Optional NNAPI delegate for accleration. */
  private NnApiDelegate nnApiDelegate = null;

  /** An instance of the driver class to run model inference with Tensorflow Lite. */
  private Interpreter tflite;

  /** Options for configuring the Interpreter. */
  private final Interpreter.Options tfliteOptions = new Interpreter.Options();

  /** Labels corresponding to the output of the vision model. */
  private List<String> labels;

  public static Classifier create(Activity activity) throws IOException {
    return new Classifier(activity, Device.CPU, 1);
  }

  private String getModelPath() {
    // you can download this file from
    // see build.gradle for where to obtain this file. It should be auto
    // downloaded into assets.
    return "model.tflite";
  }

  protected String getLabelPath() {
    return "labels.txt";
  }

  /** An immutable result returned by a Classifier describing what was recognized. */
  public static class Recognition {
    /**
     * A unique identifier for what has been recognized. Specific to the class, not the instance of
     * the object.
     */
    private final String id;

    /** Display name for the recognition. */
    private final String title;

    /**
     * A sortable score for how good the recognition is relative to others. Higher should be better.
     */
    private final Float confidence;

    public Recognition(
        final String id, final String title, final Float confidence) {
      this.id = id;
      this.title = title;
      this.confidence = confidence;
    }

    public String getId() {
      return id;
    }

    public String getTitle() {
      return title;
    }

    public Float getConfidence() {
      return confidence;
    }

    @Override
    public String toString() {
      String resultString = "";
      if (id != null) {
        resultString += "[" + id + "] ";
      }

      if (title != null) {
        resultString += title + " ";
      }

      if (confidence != null) {
        resultString += String.format("(%.1f%%) ", confidence * 100.0f);
      }

      return resultString.trim();
    }
  }

  /** Initializes a {@code Classifier}. */
  private Classifier(Activity activity, Device device, int numThreads) throws IOException {
    tfliteModel = FileUtil.loadMappedFile(activity, getModelPath());
    switch (device) {
      case GPU:
        gpuDelegate = new GpuDelegate();
        tfliteOptions.addDelegate(gpuDelegate);
        break;
      case CPU:
        break;
    }
    tfliteOptions.setNumThreads(numThreads);
    tflite = new Interpreter(tfliteModel, tfliteOptions);

    // Loads labels out from the label file.
    labels = FileUtil.loadLabels(activity, getLabelPath());

    // Reads type and shape of input and output tensors, respectively.
    int inputTensorIndex = 0;
    int[] inputShape = tflite.getInputTensor(inputTensorIndex).shape(); // {1, 91, 6}
    LOGGER.i("Input shape: %d %d %d", inputShape[0], inputShape[1], inputShape[2]);
    DataType inputDataType = tflite.getInputTensor(inputTensorIndex).dataType();
    LOGGER.i("Input data type: " + inputDataType.toString());
    int probabilityTensorIndex = 0;
    int[] probabilityShape = {10};
        //tflite.getOutputTensor(probabilityTensorIndex).shape(); // {1, 10}
    DataType probabilityDataType = tflite.getOutputTensor(probabilityTensorIndex).dataType();

    // Creates the input tensor.
    inputBuffer = TensorBuffer.createFixedSize(inputShape, inputDataType);

    // Creates the output tensor and its processor.
    outputProbabilityBuffer = TensorBuffer.createFixedSize(probabilityShape, probabilityDataType);

    LOGGER.d("Created a Tensorflow Lite Image Classifier.");
  }

  /** Runs inference and returns the classification results. */
  public List<Recognition> recognizeGesture(float[][] input) {
    // Logs this method so that it can be analyzed with systrace.
    Trace.beginSection("recognizeGesture");

    LOGGER.i("Input size: %d", input.length);
    Trace.beginSection("loadGesture");
    long startTimeForLoadImage = SystemClock.uptimeMillis();
    ByteBuffer d_buf = ByteBuffer.allocate(4 * input.length * 6);
    for (int i = 0; i < input.length; ++i)
      for (int j = 0; j < 6; ++j)
        d_buf.putFloat(input[i][j]);
    d_buf.rewind();
    inputBuffer.loadBuffer(d_buf);
    long endTimeForLoadImage = SystemClock.uptimeMillis();
    Trace.endSection();
    LOGGER.v("Timecost to load timeseries: " + (endTimeForLoadImage - startTimeForLoadImage));

    // Runs the inference call.
    Trace.beginSection("runInference");
    long startTimeForReference = SystemClock.uptimeMillis();
    tflite.run(inputBuffer.getBuffer().rewind(), outputProbabilityBuffer.getBuffer().rewind());
    long endTimeForReference = SystemClock.uptimeMillis();
    Trace.endSection();
    LOGGER.v("Timecost to run model inference: " + (endTimeForReference - startTimeForReference));

    // Gets the map of label and probability.
    Map<String, Float> labeledProbability =
        new TensorLabel(labels, outputProbabilityBuffer).getMapWithFloatValue();
    Trace.endSection();
    for (Map.Entry<String, Float> entry: labeledProbability.entrySet())
      LOGGER.i("%s %.10g", entry.getKey(), entry.getValue());
    // Gets top-k results.
    return getTopKProbability(labeledProbability);
  }

  /** Closes the interpreter and model to release resources. */
  public void close() {
    if (tflite != null) {
      tflite.close();
      tflite = null;
    }
    if (gpuDelegate != null) {
      gpuDelegate.close();
      gpuDelegate = null;
    }
    if (nnApiDelegate != null) {
      nnApiDelegate.close();
      nnApiDelegate = null;
    }
    tfliteModel = null;
  }

  /** Gets the top-k results. */
  private static List<Recognition> getTopKProbability(Map<String, Float> labelProb) {
    // Find the best classifications.
    PriorityQueue<Recognition> pq =
        new PriorityQueue<>(
            MAX_RESULTS,
            new Comparator<Recognition>() {
              @Override
              public int compare(Recognition lhs, Recognition rhs) {
                // Intentionally reversed to put high confidence at the head of the queue.
                return Float.compare(rhs.getConfidence(), lhs.getConfidence());
              }
            });

    for (Map.Entry<String, Float> entry : labelProb.entrySet()) {
      pq.add(new Recognition("" + entry.getKey(), entry.getKey(), entry.getValue()));
    }

    final ArrayList<Recognition> recognitions = new ArrayList<>();
    int recognitionsSize = Math.min(pq.size(), MAX_RESULTS);
    for (int i = 0; i < recognitionsSize; ++i) {
      recognitions.add(pq.poll());
    }
    return recognitions;
  }
}
