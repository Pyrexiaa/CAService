# Continuous Authentication – Service Application

The **Service Application** is the server-side component in the Continuous Authentication project. It is a continuous authentication system that verifies a user’s identity in real-time using multiple biometric modalities: facial recognition, audio recognition, and gait analysis. The system runs unobtrusively in the background, ensuring that the device is still being used by the legitimate user.

The app is divided into three main modules:
- Profile (ui/home) – User enrollment and verification.
- Live Feed (ui/dashboard) – Real-time visualization of biometric and sensor data.
- Score Board (ui/verifications) – Confidence scores from individual modalities and combined authentication metrics.

---

## System Architecture
1. The system operates on a multi-modal biometric authentication framework that leverages:
   - Facial recognition via front camera frames. 
   - Audio-based authentication using voice frequency analysis. 
   - Gait recognition from inertial measurement sensors (IMU). 
   - A fusion layer with Hidden Markov Model (HMM) for combining scores and making decisions.
2. Platform: Android (Kotlin)
3. Data Visualization: Graph views for real-time plotting (MPAndroidChart).
4. Machine Learning: 
   - Facial Detection Model (MLKit Face Detection)
   - Facial Recognition Model (LightCNN)
   - Audio Detection Model (AndroidVAD - WebRTC)
   - Audio & Gait Recognition Model (Simple FNN)
   - HMM for score fusion.
   - LightCNN and Simple FNN is trained in Pytorch, converted to ONNX format -> TFLite
5. Sensor APIs: Android SensorManager for accelerometer, gyroscope, and magnetometer data.

---

## Features
1. ### Profile Module
- **User Enrollment**: Users can register themselves by providing facial images, recording audio samples, and gait patterns.
- **User Verification**: Each biometric modality can be tested individually to verify that enrollment was successful.

2. ### Live Feed Module
- **Real-time Data Visualization**:
  - **Facial Data**: Front camera captures images every second and displays them.
  - **Audio Data**: Captured audio is transformed into a frequency-magnitude graph updated every second.
  - **Gait Data**: Motion data is collected from accelerometers, gyroscopes, and magnetometers on all three axes (X, Y, Z) and plotted on three separate graphs in real-time.

3. ### Score Board Module
- **Confidence Score Measurement**:
  - Each modality (face, audio, gait) generates a real-time confidence score.
  - A Hidden Markov Model (HMM) is used to compute a combined confidence score across all modalities.
  
- **Visual Representation**:
  - Four line graphs: One for the combined score, and three for the individual modality scores.
  - Doughnut charts display the latest confidence score for each modality.

---

## Assets

This directory stores all of the model weights, you may plug in your new model weights.

1. ### Machine Learning Model Weights
   - **Facial Recognition Model**: light_cnn_float16.tflite
   - **Audio Recognition Model**: custom_audio_model_float16.tflite
   - **Gait Recognition Model**: custom_gait_model_float16.tflite
   
2. ### Preprocessing Model
   - ** Audio Preprocessing Model**: mfcc_model.tflite
   - The reason of storing audio preprocessing as a tflite model instead of a function is because the audio preprocessing libraries are different for android and python. The most widely used audio library in Android is TarsosDSP while python uses Librosa. To find a common ground between Android and Python to ensure consistent result, I have decided to use log_mel_spectrograms from Tensorflow and wrap the function as a model to run in Android.

---

## Files Structure

1. models
   - audio
     - AudioRecognizer.kt
     - AudioScaler.kt
     - MFCCExtractor.kt
   - face
     - FaceRecognizer.kt
   - gait
     - GaitRecognizer.kt
     - GaitScaler.kt
   - tflite
     - TfLiteModelRunner.kt
2. service
   - handlers
     - AudioHandler.kt
     - CameraHandler.kt
     - SensorHandler.kt
     - HandlerManager.kt
   - ipc
     - IpcManager.kt
   - preferences
     - PreferencesManager.kt
   - processors
     - audioProcessor.kt
     - imageProcessor.kt
     - sensorProcessor.kt
   - recordings
     - RecordingManager.kt
   - SmartService.kt
3. ui
   - dashboard
     - DashboardFragment.kt
     - DashboardViewModel.kt
   - home
     - utils
       - camera
         - CameraManager.kt
         - FaceCaptureManager.kt
         - FaceProcessor.kt
         - ImageUtils.kt
       - recording
         - RecordingManager.kt
       - PermissionManager.kt
       - RecognizerManager.kt
       - UIHelper.kt
     - HomeFragment.kt
     - HomeViewModel.kt
   - verifications
     - utils
       - enrollment
         - EnrollmentChecker.kt
         - EnrollmentStatus.kt
       - verification
         - audio
           - AudioFileProcessor.kt
           - AudioVerificationProcessor.kt
           - VoiceActivityDetector.kt
         - face
           - FaceVerificationProcessor.kt
         - gait
           - GaitFileProcessor.kt
           - GaitVerificationProcessor.kt
         - BaseVerificationProcessor.kt
       - ChartManager.kt
       - CombinedScoreCalculator.kt
       - IndexManager.kt
       - VerificationManager.kt
     - VerificationsFragment.kt
     - VerificationsViewModel.kt
4. MainActivity.kt

---

## Technical Implementations

1. ### models/
   - This directory contains all machine learning-related classes for handling model inference and preprocessing.
   **models/audio/**
     - AudioRecognizer.kt
       Handles audio recognition tasks using the custom_audio_model_float16.tflite model. It takes preprocessed audio features (e.g., MFCCs) and outputs confidence scores.
     - AudioScaler.kt 
       Normalizes or scales raw audio input values to match the range expected by the audio model.
     - MFCCExtractor.kt
       Interfaces with the mfcc_model.tflite preprocessing model to extract log-mel spectrograms or MFCCs (Mel-frequency cepstral coefficients) from audio streams.
   
   **models/face/**
     - FaceRecognizer.kt
       Runs inference on the light_cnn_float16.tflite model to extract facial embeddings and calculate similarity scores for face verification.
   
   **models/gait/**
     - GaitRecognizer.kt
       Handles gait recognition tasks using the custom_gait_model_float16.tflite model. It processes time-series IMU data and returns confidence scores.
     - GaitScaler.kt
       Normalizes gait sensor data (accelerometer, gyroscope, magnetometer) to a consistent scale.

   **models/tflite/**
     - TfLiteModelRunner.kt
       A utility class for loading, running inference, and managing TensorFlow Lite models. It abstracts common TFLite operations for all modalities.

2. ### service/
   - This directory manages background services, sensor data collection, and inter-process communication with controller application.
   **service/handlers/**
     - AudioHandler.kt
       Manages real-time audio recording and streaming to the audio processor.
     - CameraHandler.kt
       Captures frames from the front camera every second and provides them to the image processor.
     - SensorHandler.kt
       Record accelerometer, gyroscope, and magnetometer data for gait analysis.
     - HandlerManager.kt
       Coordinates the lifecycle of all handlers (audio, camera, sensors) and ensures synchronization.
   
   **service/ipc/**
    - IpcManager.kt
      Provides inter-process communication between background services and the controller application.

   **service/processors/**
    - audioProcessor.kt
      Preprocesses and streams real-time audio collected to Live Feed UI components.
    - imageProcessor.kt
      Prepares camera frames for display purpose on Live Feed UI components.
    - sensorProcessor.kt
      Aggregates and preprocesses gait data for display purpose in 3 different graphs with 3 axes each.
   
   **service/recordings/**
    - RecordingManager.kt
      Manages recorded sessions, operations such as circular file saving and file storage.

3. ### ui/
   - This directory contains the presentation layer, with fragments, view models, and UI utilities.
   
   **ui/dashboard/ (Live Feed)**
    - DashboardFragment.kt 
      Displays real-time data collection and data display using image view and graphs. 
    - DashboardViewModel.kt 
      Provides data binding between live data collection (from SmartService) and the Live Feed UI. 
   
   **ui/home/ (Profile)**
    - **utils**
      - camera/ 
        - CameraManager.kt: Controls cameraX operations (e.g., initialization, frame capture). 
        - FaceCaptureManager.kt: Special Manager for capturing face images during enrollment, control the prompt and orientation needed. 
        - FaceProcessor.kt: Detecting and processes captured facial images for enrollment and verification. 
        - ImageUtils.kt: Helper utilities for image conversion, resizing, or format handling. 
        
      - recording/
        - RecordingManager.kt: Control audio or sensor recordings (start and stop) initiated from the Profile module (different from service recording manager). 
      
      - PermissionManager.kt: Handles runtime permissions (camera, microphone, sensor access). 
      - RecognizerManager.kt: Coordinates between various recognizers (audio, face, gait) for Profile operations (initialization and getting). 
      - UIHelper.kt: UI utility functions for dialogs, toasts, and loading indicators.

    - HomeFragment.kt: The screen for the Profile segment, allowing users to enroll and verify themselves. 
    - HomeViewModel.kt: Manages data binding for the Profile screen.
   
   **ui/verifications/ (Score Board)**
    - **utils**
      - enrollment/
        - EnrollmentChecker.kt: Verifies if a user’s biometric enrollment data is valid by checking sharedPreferences. 
        - EnrollmentStatus.kt: Verifies if a user's biometric enrollment status is true or false. 
        
      - verification/
        - audio/
          - AudioFileProcessor.kt: Preprocesses recorded audio samples into format that the model can process such as merging files into 5 seconds duration.
          - AudioVerificationProcessor.kt: Executes suspend real-time audio verification using Audio Detection and Audio Recognizer.
          - VoiceActivityDetector.kt: Detects voice activity to segment valid audio frames and return valid score. 
          
        - face/
          - FaceVerificationProcessor.kt: It includes preprocessing functions and real-time face verification using Face Detection and Face Recognizer. 
          
        - gait/
          - GaitFileProcessor.kt: Preprocesses gait data from recorded files into format that the model can process such as merging files into 5 seconds duration. 
          - GaitVerificationProcessor.kt: Executes suspend real-time gait verification using Gait Recognizer. 
          
        - BaseVerificationProcessor.kt: An abstract class providing a unified interface for all verification processors including getting scores, start preprocessing and verification loop to get score from time to time. 
        
      - ChartManager.kt: Manages creation and updating of dataset and graphs (line, doughnut charts) for the Score Board. 
      - CombinedScoreCalculator.kt: Planned to use Hidden Markov Model (HMM) to combine confidence scores from face, audio, and gait into a single metric, currently using weighted sum. Any logic altering the confidence score will be added here. 
      - IndexManager.kt: Handles indexing or reference mapping of the latest data collected from smart service. 
      - VerificationManager.kt: Orchestrates all verification processors and recognizers, initialization. 
      
    - VerificationsFragment.kt: UI fragment displaying the Score Board, the real-time results. 
    - VerificationsViewModel.kt: Manages live data and state for the Verifications module.

4. ### MainActivity.kt
   - The main entry point of the application, hosting navigation between fragments (Home - Profile, Dashboard - Live Feed, Verifications - Score Board).

