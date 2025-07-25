# Continuous Authentication System – User Guide

This repository contains two Android applications developed in Kotlin using Android Studio:
- Service Application – Handles profile enrollment, data collection, feature extraction, and continuous authentication (face, audio, gait).
- Controller Application – Sends signals to the service application to initiate real-time data collection and monitoring.


---
## Installation
- Download and install both applications on your Android phone:
  - Service Application
  - Controller Application

- Ensure you have granted all required permissions (camera, microphone, motion sensors).


---
## Getting Started
- Step 1: Enroll Your Profile 
  - Launch the Service Application. 
  - Navigate to the Profile section. 
  - Enroll the following modalities:
    - Face – Capture and register your face image. 
    - Audio – Record your voice sample. 
    - Gait – Capture your walking pattern. 
  - Verify that your face, audio, and gait data have been stored successfully.

- Note: At this stage, you will not see any real-time data because the service is not yet receiving signals from the controller app.

- Step 2: Start Real-Time Monitoring 
  - Restart the Service Application to ensure proper initialization. 
  - Launch the Controller Application. 
  - Press the button on the controller app to send a start signal to the service application. 
  - In the Service Application:
    - Go to Live Feed to monitor real-time data collection. 
    - Go to Score Board to view:
      - Confidence score for each modality (face, audio, gait). 
      - Combined confidence score for overall authentication.

- Model Integration 
The Service Application uses TensorFlow Lite (TFLite) models for recognition. To plug in new models:
  - Add your new .tflite model weights to the assets folder of the Service Application. 
  - Update the model paths in:
    - RecognizerManager.kt 
    - VerificationManager.kt 
  - For the MFCC extractor model, replace the existing MFCC model file and adjust the corresponding path if a different preprocessing method is required.

- Scaler Configuration
To ensure proper scaling for audio and gait features:
  - Update mean and standard deviation values in:
    - AudioScaler.kt 
    - GaitScaler.kt
  - These values must match the parameters used during training of your latest model.

- Troubleshooting 
  - No data update in Live Feed or score update in Score Board, particularly image: 
    - Try restarting the service and controller app and press the button on the controller app again. 
  - Low confidence scores: 
    - Verify if the enrolled face/audio/gait confidence scores are similar on Profile Section. 
    - Verify if the model paths and scaler values are correct.
  - Model errors: 
    - Verify that the .tflite model paths are correctly updated in RecognizerManager.kt and VerificationManager.kt.
