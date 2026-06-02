package com.swiftdrop

import android.os.Bundle
import com.journeyapps.barcodescanner.CaptureActivity
import com.journeyapps.barcodescanner.DecoratedBarcodeView

class ScannerActivity : CaptureActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Configure camera settings before the camera opens (happens in onResume).
        val barcodeView = findViewById<DecoratedBarcodeView>(com.google.zxing.client.android.R.id.zxing_barcode_scanner)
        barcodeView?.barcodeView?.cameraSettings?.apply {
            isAutoFocusEnabled = true
            isContinuousFocusEnabled = true
            isMeteringEnabled = true
        }
    }
}
