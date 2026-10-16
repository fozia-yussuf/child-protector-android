package com.childprotector.app

import android.content.Context
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import org.opencv.android.OpenCVLoader
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.Size
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import org.opencv.objdetect.FaceDetectorYN
import org.opencv.objdetect.FaceRecognizerSF
import java.io.File
import java.io.FileOutputStream
import java.util.ArrayDeque

data class FaceMatchStatus(
    val message: String,
    val matched: Boolean = false,
    val score: Double? = null
)

class OnDeviceFaceMatchAnalyzer(
    context: Context,
    private val registeredPhotoDataUrl: String,
    private val onStatus: (FaceMatchStatus) -> Unit
) : ImageAnalysis.Analyzer {
    private val appContext = context.applicationContext
    private val scoreWindow = ArrayDeque<Double>()
    private var lastAnalysisAt = 0L
    private var referenceFeature: Mat? = null
    private var recognizer: FaceRecognizerSF? = null
    private var detector: FaceDetectorYN? = null
    private var failed = false

    override fun analyze(image: ImageProxy) {
        try {
            val now = SystemClock.elapsedRealtime()
            if (now - lastAnalysisAt < ANALYSIS_INTERVAL_MS) return
            lastAnalysisAt = now

            if (failed) return
            if (referenceFeature == null && !loadModelsAndReference()) return

            val frame = imageToBgr(image)
            try {
                val currentDetector = detector ?: return
                val currentRecognizer = recognizer ?: return
                val currentReference = referenceFeature ?: return

                currentDetector.setInputSize(Size(frame.cols().toDouble(), frame.rows().toDouble()))
                val faces = Mat()
                try {
                    currentDetector.detect(frame, faces)
                    if (faces.rows() != 1) {
                        report("Keep one face in view of the camera.")
                        return
                    }

                    val aligned = Mat()
                    val feature = Mat()
                    try {
                        currentRecognizer.alignCrop(frame, faces.row(0), aligned)
                        currentRecognizer.feature(aligned, feature)
                        val score = currentRecognizer.match(
                            currentReference,
                            feature,
                            FaceRecognizerSF.FR_COSINE
                        )
                        val frameMatched = score >= COSINE_MATCH_THRESHOLD
                        scoreWindow.addLast(if (frameMatched) score else -1.0)
                        while (scoreWindow.size > MATCH_WINDOW_SIZE) scoreWindow.removeFirst()
                        val matchingScores = scoreWindow.filter { it >= COSINE_MATCH_THRESHOLD }
                        val confirmed = matchingScores.size >= MATCHES_REQUIRED
                        val reportedScore = matchingScores.average().takeIf { confirmed }
                        report(
                            if (confirmed) "Face match confirmed. Confirm boarding to save the scan."
                            else if (frameMatched) "Possible match; hold still for confirmation."
                            else "No match yet. Align the child with the registered photo.",
                            confirmed,
                            reportedScore
                        )
                    } finally {
                        aligned.release()
                        feature.release()
                    }
                } finally {
                    faces.release()
                }
            } finally {
                frame.release()
            }
        } catch (exception: Exception) {
            failed = true
            Log.e(TAG, "On-device face matching failed", exception)
            report("Face matching is unavailable. Do not record this scan.")
        } finally {
            image.close()
        }
    }

    private fun loadModelsAndReference(): Boolean {
        if (!OpenCVLoader.initLocal()) {
            failed = true
            report("On-device face model could not be loaded.")
            return false
        }

        val modelDirectory = File(appContext.filesDir, "face-models").apply { mkdirs() }
        val detectorPath = copyAssetIfMissing(
            "models/face_detection_yunet_2023mar_int8bq.onnx",
            File(modelDirectory, "yunet.onnx")
        )
        val recognizerPath = copyAssetIfMissing(
            "models/face_recognition_sface_2021dec_int8bq.onnx",
            File(modelDirectory, "sface.onnx")
        )
        detector = FaceDetectorYN.create(
            detectorPath,
            "",
            Size(320.0, 320.0),
            DETECTION_THRESHOLD,
            NMS_THRESHOLD,
            TOP_K
        )
        recognizer = FaceRecognizerSF.create(recognizerPath, "")

        val encodedPhoto = registeredPhotoDataUrl.substringAfter("base64,", "")
        if (encodedPhoto.isBlank()) {
            failed = true
            report("This student has no registered photo.")
            return false
        }
        val photoBytes = Base64.decode(encodedPhoto, Base64.DEFAULT)
        val photoBuffer = MatOfByte().apply { fromArray(*photoBytes) }
        val registeredImage = Imgcodecs.imdecode(photoBuffer, Imgcodecs.IMREAD_COLOR)
        photoBuffer.release()
        if (registeredImage.empty()) {
            registeredImage.release()
            failed = true
            report("The registered student photo could not be opened.")
            return false
        }

        try {
            val currentDetector = detector ?: error("Face detector initialization failed")
            val currentRecognizer = recognizer ?: error("Face recognizer initialization failed")
            currentDetector.setInputSize(Size(registeredImage.cols().toDouble(), registeredImage.rows().toDouble()))
            val faces = Mat()
            try {
                currentDetector.detect(registeredImage, faces)
                if (faces.rows() != 1) {
                    failed = true
                    report("Registered photo must contain exactly one clear face.")
                    return false
                }
                val aligned = Mat()
                val feature = Mat()
                try {
                    currentRecognizer.alignCrop(registeredImage, faces.row(0), aligned)
                    currentRecognizer.feature(aligned, feature)
                    referenceFeature = feature.clone()
                } finally {
                    aligned.release()
                    feature.release()
                }
            } finally {
                faces.release()
            }
        } finally {
            registeredImage.release()
        }
        return true
    }

    private fun imageToBgr(image: ImageProxy): Mat {
        val plane = image.planes.first()
        val source = plane.buffer.duplicate()
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val rgba = ByteArray(image.width * image.height * 4)
        val row = ByteArray(rowStride)

        for (y in 0 until image.height) {
            source.position(y * rowStride)
            val bytesInRow = minOf(rowStride, source.remaining())
            source.get(row, 0, bytesInRow)
            for (x in 0 until image.width) {
                val sourceOffset = x * pixelStride
                val targetOffset = (y * image.width + x) * 4
                if (sourceOffset + 3 < bytesInRow) {
                    rgba[targetOffset] = row[sourceOffset]
                    rgba[targetOffset + 1] = row[sourceOffset + 1]
                    rgba[targetOffset + 2] = row[sourceOffset + 2]
                    rgba[targetOffset + 3] = row[sourceOffset + 3]
                }
            }
        }

        val sourceMat = Mat(image.height, image.width, CvType.CV_8UC4)
        sourceMat.put(0, 0, rgba)
        val rotated = Mat()
        when (image.imageInfo.rotationDegrees) {
            90 -> Core.rotate(sourceMat, rotated, Core.ROTATE_90_CLOCKWISE)
            180 -> Core.rotate(sourceMat, rotated, Core.ROTATE_180)
            270 -> Core.rotate(sourceMat, rotated, Core.ROTATE_90_COUNTERCLOCKWISE)
            else -> sourceMat.copyTo(rotated)
        }
        sourceMat.release()
        val bgr = Mat()
        Imgproc.cvtColor(rotated, bgr, Imgproc.COLOR_RGBA2BGR)
        rotated.release()
        return bgr
    }

    private fun copyAssetIfMissing(assetPath: String, destination: File): String {
        if (!destination.exists() || destination.length() == 0L) {
            appContext.assets.open(assetPath).use { input ->
                FileOutputStream(destination).use { output -> input.copyTo(output) }
            }
        }
        check(destination.length() > 0L) { "Model asset $assetPath is empty" }
        return destination.absolutePath
    }

    private fun report(message: String, matched: Boolean = false, score: Double? = null) {
        onStatus(FaceMatchStatus(message, matched, score))
    }

    companion object {
        private const val TAG = "FaceMatchAnalyzer"
        private const val ANALYSIS_INTERVAL_MS = 500L
        private const val COSINE_MATCH_THRESHOLD = 0.363
        private const val MATCH_WINDOW_SIZE = 5
        private const val MATCHES_REQUIRED = 3
        private const val DETECTION_THRESHOLD = 0.8f
        private const val NMS_THRESHOLD = 0.3f
        private const val TOP_K = 5000
    }
}
