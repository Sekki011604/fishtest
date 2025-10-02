package com.example.fishfreshness

import android.content.Intent
import android.os.Bundle
import android.text.Html
import android.widget.Button
import android.widget.ImageButton
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val btnStartScan: Button = findViewById(R.id.btnStartScan)
        val aboutBtn: ImageButton = findViewById(R.id.aboutBtn)

        btnStartScan.setOnClickListener {
            val intent = Intent(this, CamActivity::class.java)
            startActivity(intent)
        }

        aboutBtn.setOnClickListener {
            val aboutMessage = Html.fromHtml(
                "<b>About This App</b><br><br>" +
                        "The Fish Freshness App is a consumer-focused mobile tool designed to help individuals quickly check the freshness and estimated shelf-life of fish, with a primary focus on mackerel scad (Decapterus macarellus).<br><br>" +
                        "Using advanced artificial intelligence, the app combines:<br><br>" +
                        "• YOLO (You Only Look Once) for scanning and identifying visual indicators of fish freshness through the camera.<br>" +
                        "• XGBoost for predicting the natural shelf-life based on analyzed freshness features.<br><br>" +
                        "With this technology, consumers can make more informed decisions when buying or consuming fish. The app promotes food safety, smarter purchasing, and reduced food waste, while maintaining a simple and easy-to-use interface for everyday users.<br><br>" +

                        "<b>Developer</b><br>" +
                        "Developed by: Team Laurence/ Palawan State University<br>" +
                        "Field: Computer Science<br><br>" +

                        "<b>Version</b><br>" +
                        "Current Version: 1.0.0<br><br>" +

                        "<b>Contact Us</b><br>" +
                        "Email:202280141@psu.palawan.edu.ph<br>" +
                        "Phone: 0992 548 6977]<br><br>" +

                        "<b>User Agreement</b><br>" +
                        "By using this app, you agree to use it for informational and educational purposes only. The app provides estimates based on available data and should not be considered a substitute for professional food safety inspections.<br><br>" +

                        "<b>Privacy Policy</b><br>" +
                        "The app respects user privacy. We do not collect personal information without consent. Any data provided will only be used to improve app performance and user experience, and will not be shared with third parties.<br><br>" +

                        "<b>Acknowledgements</b><br>" +
                        "We would like to thank:<br>" +
                        "• Our mentors and advisers for their guidance<br>" +
                        "• Palawan State University for academic support<br>" +
                        "• Open-source tools and libraries that made development possible"
            )

            AlertDialog.Builder(this)
                .setTitle("About")
                .setMessage(aboutMessage)
                .setPositiveButton("OK", null)
                .show()
        }
    }
}
