# 🐟 Fish Freshness App  

<p align="center">
  <img src="https://img.shields.io/badge/Android-Kotlin-green?logo=kotlin" alt="Kotlin"/>
  <img src="https://img.shields.io/badge/SDK-21%2B-blue" alt="SDK"/>
  <img src="https://img.shields.io/badge/License-MIT-orange" alt="License"/>
  <img src="https://img.shields.io/badge/Status-Research%20Project-yellow" alt="Status"/>
</p>

---

## 📌 Overview  

**Fish Freshness App** is an Android application developed to analyze the freshness of **Mackerel Scad (Galunggong)**.  
The app allows users to **scan fish using the camera** or **upload an image** for analysis.  
It was created as part of a **Computer Science research project** focused on **food safety and shelf-life prediction**.  

---

## ✨ Features  

✅ **Real-time Camera Scanning** – Capture images directly via the device camera  
✅ **Upload from Gallery** – Analyze fish freshness from stored images  
✅ **About Section** – Research details and app purpose  
✅ **Modern UI** – Black theme with clean Material Design  
✅ **Lightweight & Fast** – Optimized for research use  

---

## 🛠️ Tech Stack  

- **Language:** Kotlin  
- **UI Design:** XML, Material Design Components  
- **Camera:** AndroidX CameraX  
- **IDE:** Android Studio  

---

## 📂 Project Structure  

```plaintext
Fish-Freshness-App/
│── app/
│   └── src/
│       └── main/
│           ├── java/com/example/fishfreshness/
│           │   ├── MainActivity.kt      # Home screen with About & Start Scan
│           │   ├── CamActivity.kt       # Camera + image upload
│           │
│           ├── res/
│           │   ├── layout/
│           │   │   ├── activity_main.xml
│           │   │   ├── activity_cam.xml
│           │   ├── drawable/            # App icons, buttons, banners
│           │   ├── values/              # Strings, colors, styles
│           │
│           ├── AndroidManifest.xml
│
│── README.md
