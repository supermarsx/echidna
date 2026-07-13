package com.echidna.interceptionprobe

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

class ProbeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply { text = getString(R.string.app_name) })
    }
}
