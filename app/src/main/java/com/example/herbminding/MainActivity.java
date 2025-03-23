package com.example.herbminding;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Log;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import org.tensorflow.lite.Interpreter;
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "HerbMindingApp";
    private ImageView imageView;
    private Interpreter tflite;
    private List<String> labels; // Holds the class labels

    private final ActivityResultLauncher<Intent> cameraLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    Bundle extras = result.getData().getExtras();
                    if (extras != null) {
                        Bitmap imageBitmap = (Bitmap) extras.get("data");
                        if (imageBitmap != null) {
                            // Resize to 320x320 before inference
                            Bitmap resizedBitmap = Bitmap.createScaledBitmap(imageBitmap, 640, 640, true);
                            imageView.setImageBitmap(resizedBitmap);
                            runInference(resizedBitmap);
                        } else {
                            Toast.makeText(this, "Failed to capture image", Toast.LENGTH_SHORT).show();
                        }
                    }
                }
            });

    @SuppressLint("MissingInflatedId")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Button openCameraButton = findViewById(R.id.button_open_camera);
        imageView = findViewById(R.id.image_view);

        // Load YOLOv8 model
        try {
            tflite = new Interpreter(loadModelFile());
            Toast.makeText(this, "Model loaded successfully!", Toast.LENGTH_SHORT).show();

            // Check model input shape and data type
            int[] inputShape = tflite.getInputTensor(0).shape();
            Log.d(TAG, "Model Input Shape: " + inputShape[1] + "x" + inputShape[2] + "x" + inputShape[3]);
            Log.d(TAG, "Model Input Data Type: " + tflite.getInputTensor(0).dataType());

        } catch (IOException e) {
            Log.e(TAG, "Model load failed: " + e.getMessage(), e);
            Toast.makeText(this, "Model load failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }

        // Load class labels
        labels = loadLabels();

        // Open camera when button is clicked
        openCameraButton.setOnClickListener(v -> {
            Intent cameraIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            if (cameraIntent.resolveActivity(getPackageManager()) != null) {
                cameraLauncher.launch(cameraIntent);
            } else {
                Toast.makeText(this, "No camera app found!", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private MappedByteBuffer loadModelFile() throws IOException {
        AssetFileDescriptor fileDescriptor = getAssets().openFd("ml/model.tflite");
        FileInputStream inputStream = fileDescriptor.createInputStream();
        FileChannel fileChannel = inputStream.getChannel();
        long startOffset = fileDescriptor.getStartOffset();
        long declaredLength = fileDescriptor.getDeclaredLength();
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength);
    }

    private List<String> loadLabels() {
        List<String> labels = new ArrayList<>();
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(getAssets().open("labels.txt")));
            String line;
            while ((line = reader.readLine()) != null) {
                labels.add(line);
            }
            reader.close();
        } catch (IOException e) {
            Log.e(TAG, "Failed to load labels: " + e.getMessage());
        }
        return labels;
    }

    private void runInference(Bitmap bitmap) {
        // Convert Bitmap to ByteBuffer
        ByteBuffer byteBuffer = convertBitmapToByteBuffer(bitmap);

        // Define output buffer (Ensure it matches your YOLOv8 model output dimensions)
        TensorBuffer outputBuffer = TensorBuffer.createFixedSize(new int[]{1, 25200, 85}, org.tensorflow.lite.DataType.FLOAT32);

        try {
            // Run inference
            tflite.run(byteBuffer, outputBuffer.getBuffer().rewind());
            handleResults(outputBuffer);
        } catch (Exception e) {
            Log.e(TAG, "Inference failed!", e);
            Toast.makeText(this, "Error during inference: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private ByteBuffer convertBitmapToByteBuffer(Bitmap bitmap) {
        int modelInputSize = 640; // Ensure input size is 320x320
        ByteBuffer byteBuffer = ByteBuffer.allocateDirect(4 * modelInputSize * modelInputSize * 3); // 3 for RGB
        byteBuffer.order(ByteOrder.nativeOrder());

        int[] intValues = new int[modelInputSize * modelInputSize];
        bitmap.getPixels(intValues, 0, bitmap.getWidth(), 0, 0, bitmap.getWidth(), bitmap.getHeight());

        int pixel = 0;
        for (int i = 0; i < modelInputSize; i++) {
            for (int j = 0; j < modelInputSize; j++) {
                int val = intValues[pixel++];
                byteBuffer.putFloat(((val >> 16) & 0xFF) / 255.0f); // Red
                byteBuffer.putFloat(((val >> 8) & 0xFF) / 255.0f);  // Green
                byteBuffer.putFloat((val & 0xFF) / 255.0f);         // Blue
            }
        }

        // Log ByteBuffer size for verification
        Log.d(TAG, "ByteBuffer Size: " + byteBuffer.capacity() + " bytes");
        return byteBuffer;
    }

    private void handleResults(TensorBuffer outputBuffer) {
        float[] results = outputBuffer.getFloatArray();
        int numClasses = labels.size(); // Number of classes in labels.txt

        // Loop through detections and find the highest confidence
        float maxConfidence = 0;
        int detectedClassIndex = -1;

        for (int i = 0; i < results.length / 85; i++) {
            float confidence = results[i * 85 + 4]; // Confidence score at position 4
            if (confidence > maxConfidence) {
                maxConfidence = confidence;

                // Extract the class with highest probability (from index 5 to end)
                int bestClassIndex = 5;
                for (int j = 5; j < 85; j++) {
                    if (results[i * 85 + j] > results[i * 85 + bestClassIndex]) {
                        bestClassIndex = j;
                    }
                }
                detectedClassIndex = bestClassIndex - 5; // Offset by 5
            }
        }

        if (detectedClassIndex >= 0 && detectedClassIndex < numClasses) {
            String detectedPlant = labels.get(detectedClassIndex);
            Toast.makeText(this, "Detected: " + detectedPlant + "\nConfidence: " + (maxConfidence * 100) + "%", Toast.LENGTH_LONG).show();
            Log.d(TAG, "Detected: " + detectedPlant + " with confidence: " + maxConfidence);
        } else {
            Toast.makeText(this, "No plant detected", Toast.LENGTH_SHORT).show();
            Log.d(TAG, "No plant detected or confidence too low.");
        }
    }
}
