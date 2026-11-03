package com.childprotector.app

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.MatOfDouble
import org.opencv.core.MatOfInt
import org.opencv.core.Size
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import org.opencv.objdetect.FaceDetectorYN
import org.opencv.objdetect.FaceRecognizerSF
import java.io.File
import java.io.FileOutputStream

data class FaceMatchStatus(
    val message: String,
    val confirmed: Boolean = false,
    val score: Double? = null
)

class OnDeviceFaceMatchAnalyzer(
    context: Context,
    private val registeredPhotoDataUrl: String,
    private val onStatus: (FaceMatchStatus) -> Unit,
    private val onFrameAvailable: () -> Unit = {}
) : ImageAnalysis.Analyzer {
    private val appContext = context.applicationContext
    private val scoreWindow = FaceMatchWindow(COSINE_MATCH_THRESHOLD)
    private val analyzerLock = Any()
    private var lastAnalysisAt = 0L
    private var referenceFeature: Mat? = null
    private var recognizer: FaceRecognizerSF? = null
    private var detector: FaceDetectorYN? = null
    private var failed = false
    @Volatile private var latestFrameJpeg: ByteArray? = null
    @Volatile private var closed = false

    override fun analyze(image: ImageProxy) {
        try {
            synchronized(analyzerLock) {
                if (closed) return
                val now = SystemClock.elapsedRealtime()
                if (now - lastAnalysisAt < ANALYSIS_INTERVAL_MS) return
                lastAnalysisAt = now

                val frame = imageToBgr(image)
                try {
                    val jpeg = encodeFrame(frame)
                    latestFrameJpeg = jpeg
                    onFrameAvailable()
                    if (failed) return
                    if (referenceFeature == null && !loadModelsAndReference()) return

                    val currentDetector = detector ?: return
                    val currentRecognizer = recognizer ?: return
                    val currentReference = referenceFeature ?: return

                    currentDetector.setInputSize(Size(frame.cols().toDouble(), frame.rows().toDouble()))
                    val faces = Mat()
                    try {
                        currentDetector.detect(frame, faces)
                        val faceCount = faces.rows()
                        if (faceCount != 1) {
                            scoreWindow.reset()
                            report(ImageQualityChecks.hint(faceCount, frame.rows(), null).orEmpty())
                            return
                        }

                        val qualityHint = imageQualityHint(frame, faces.get(0, 0))
                        if (qualityHint != null) {
                            scoreWindow.reset()
                            report(qualityHint)
                            return
                        }

                        val aligned = Mat()
                        val feature = Mat()
                        val face = faces.row(0)
                        try {
                            currentRecognizer.alignCrop(frame, face, aligned)
                            currentRecognizer.feature(aligned, feature)
                            val score = currentRecognizer.match(
                                currentReference,
                                feature,
                                FaceRecognizerSF.FR_COSINE
                            )
                            val frameMatched = score >= COSINE_MATCH_THRESHOLD
                            val confirmedScore = scoreWindow.add(score)
                            val confirmed = confirmedScore != null
                            report(
                                if (confirmed) "Face match confirmed. Confirm boarding to save the scan."
                                else if (frameMatched) "Possible match; hold still for confirmation."
                                else "No match yet. Align the child with the registered photo.",
                                confirmed,
                                confirmedScore ?: score
                            )
                        } finally {
                            face.release()
                            aligned.release()
                            feature.release()
                        }
                    } finally {
                        faces.release()
                    }
                } finally {
                    frame.release()
                }
            }
        } catch (exception: Exception) {
            failed = true
            scoreWindow.reset()
            Log.e(TAG, "On-device face matching failed", exception)
            report("Face matching is unavailable. Use manual override only after visual verification.")
        } finally {
            image.close()
        }
    }

    fun latestFrameBase64(): String? =
        latestFrameJpeg?.let { Base64.encodeToString(it, Base64.NO_WRAP) }

    fun close() {
        synchronized(analyzerLock) {
            closed = true
            latestFrameJpeg = null
            referenceFeature?.release()
            referenceFeature = null
            detector = null
            recognizer = null
        }
    }

    private fun encodeFrame(frame: Mat): ByteArray {
        val scale = minOf(1.0, MAX_FRAME_SIZE.toDouble() / maxOf(frame.cols(), frame.rows()))
        val resized = Mat()
        val encoded = MatOfByte()
        val parameters = MatOfInt(Imgcodecs.IMWRITE_JPEG_QUALITY, JPEG_QUALITY)
        try {
            if (scale < 1.0) {
                Imgproc.resize(
                    frame,
                    resized,
                    Size(frame.cols() * scale, frame.rows() * scale),
                    0.0,
                    0.0,
                    Imgproc.INTER_AREA
                )
            } else {
                frame.copyTo(resized)
            }
            check(Imgcodecs.imencode(".jpg", resized, encoded, parameters)) {
                "Could not encode camera frame"
            }
            return encoded.toArray()
        } finally {
            resized.release()
            encoded.release()
            parameters.release()
        }
    }

    private fun imageQualityHint(frame: Mat, face: DoubleArray): String? {
        val left = maxOf(0, face[0].toInt())
        val top = maxOf(0, face[1].toInt())
        val right = minOf(frame.cols(), (face[0] + face[2]).toInt())
        val bottom = minOf(frame.rows(), (face[1] + face[3]).toInt())
        ImageQualityChecks.hint(1, frame.rows(), (bottom - top).toDouble())?.let { return it }
        if (right <= left || bottom <= top) return "Keep one face in view of the camera."

        val faceRegion = frame.submat(top, bottom, left, right)
        val gray = Mat()
        val laplacian = Mat()
        val mean = MatOfDouble()
        val deviation = MatOfDouble()
        try {
            Imgproc.cvtColor(faceRegion, gray, Imgproc.COLOR_BGR2GRAY)
            val brightness = Core.mean(gray).`val`[0]
            ImageQualityChecks.hint(1, frame.rows(), (bottom - top).toDouble(), brightness)?.let {
                return it
            }

            Imgproc.Laplacian(gray, laplacian, CvType.CV_64F)
            Core.meanStdDev(laplacian, mean, deviation)
            val standardDeviation = deviation.toArray().firstOrNull() ?: 0.0
            return ImageQualityChecks.hint(
                1,
                frame.rows(),
                (bottom - top).toDouble(),
                brightness,
                standardDeviation * standardDeviation
            )
        } finally {
            faceRegion.release()
            gray.release()
            laplacian.release()
            mean.release()
            deviation.release()
        }
    }

    private fun loadModelsAndReference(): Boolean {
        val encodedPhoto = registeredPhotoDataUrl.substringAfter("base64,", "")
        if (encodedPhoto.isBlank()) {
            failed = true
            report("This student has no registered photo. Use manual override only after visual verification.")
            return false
        }

        if (!OpenCVLoader.initLocal()) {
            failed = true
            report("On-device face model could not be loaded.")
            return false
        }

        val modelDirectory = File(appContext.filesDir, "face-models").apply { mkdirs() }
        val detectorPath = copyFaceModelAsset(
            appContext,
            "models/face_detection_yunet_2023mar_int8bq.onnx",
            File(modelDirectory, "yunet.onnx")
        )
        val recognizerPath = copyFaceModelAsset(
            appContext,
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

        val photoBytes = Base64.decode(encodedPhoto, Base64.DEFAULT)
        val photoBuffer = MatOfByte().apply { fromArray(*photoBytes) }
        val registeredImage = try {
            Imgcodecs.imdecode(photoBuffer, Imgcodecs.IMREAD_COLOR)
        } finally {
            photoBuffer.release()
        }
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
                val face = faces.row(0)
                try {
                    currentRecognizer.alignCrop(registeredImage, face, aligned)
                    currentRecognizer.feature(aligned, feature)
                    referenceFeature = feature.clone()
                } finally {
                    face.release()
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
        val rotated = Mat()
        val bgr = Mat()
        try {
            sourceMat.put(0, 0, rgba)
            when (image.imageInfo.rotationDegrees) {
                90 -> Core.rotate(sourceMat, rotated, Core.ROTATE_90_CLOCKWISE)
                180 -> Core.rotate(sourceMat, rotated, Core.ROTATE_180)
                270 -> Core.rotate(sourceMat, rotated, Core.ROTATE_90_COUNTERCLOCKWISE)
                else -> sourceMat.copyTo(rotated)
            }
            Imgproc.cvtColor(rotated, bgr, Imgproc.COLOR_RGBA2BGR)
            return bgr
        } catch (exception: Exception) {
            bgr.release()
            throw exception
        } finally {
            sourceMat.release()
            rotated.release()
        }
    }

    private fun report(message: String, confirmed: Boolean = false, score: Double? = null) {
        onStatus(FaceMatchStatus(message, confirmed, score))
    }

    companion object {
        private const val TAG = "FaceMatchAnalyzer"
        private const val ANALYSIS_INTERVAL_MS = 500L
        private const val COSINE_MATCH_THRESHOLD = 0.363
        private const val MAX_FRAME_SIZE = 640
        private const val JPEG_QUALITY = 70
        private const val DETECTION_THRESHOLD = 0.8f
        private const val NMS_THRESHOLD = 0.3f
        private const val TOP_K = 5000
    }
}

fun validateAndEncodeStudentPhoto(context: Context, bitmap: Bitmap): String {
    check(OpenCVLoader.initLocal()) { "Face detection could not be initialized." }

    val modelDirectory = File(context.applicationContext.filesDir, "face-models").apply {
        check(isDirectory || mkdirs()) { "Could not prepare face model storage." }
    }
    val detectorPath = copyFaceModelAsset(
        context.applicationContext,
        "models/face_detection_yunet_2023mar_int8bq.onnx",
        File(modelDirectory, "yunet.onnx")
    )
    val detector = FaceDetectorYN.create(
        detectorPath,
        "",
        Size(320.0, 320.0),
        0.8f,
        0.3f,
        5000
    )
    val rgba = Mat()
    val bgr = Mat()
    val faces = Mat()
    val resized = Mat()
    val encoded = MatOfByte()
    val parameters = MatOfInt(Imgcodecs.IMWRITE_JPEG_QUALITY, 85)
    try {
        Utils.bitmapToMat(bitmap, rgba)
        check(!rgba.empty()) { "The student photo could not be read." }
        Imgproc.cvtColor(rgba, bgr, Imgproc.COLOR_RGBA2BGR)
        detector.setInputSize(Size(bgr.cols().toDouble(), bgr.rows().toDouble()))
        detector.detect(bgr, faces)
        when (faces.rows()) {
            0 -> throw IllegalArgumentException("No face found. Retake the student photo.")
            1 -> Unit
            else -> throw IllegalArgumentException("More than one face found. Retake with only the student in frame.")
        }

        val scale = minOf(1.0, 640.0 / maxOf(bgr.cols(), bgr.rows()))
        if (scale < 1.0) {
            Imgproc.resize(
                bgr,
                resized,
                Size(bgr.cols() * scale, bgr.rows() * scale),
                0.0,
                0.0,
                Imgproc.INTER_AREA
            )
        } else {
            bgr.copyTo(resized)
        }
        check(Imgcodecs.imencode(".jpg", resized, encoded, parameters)) {
            "The student photo could not be compressed."
        }
        return Base64.encodeToString(encoded.toArray(), Base64.NO_WRAP)
    } finally {
        rgba.release()
        bgr.release()
        faces.release()
        resized.release()
        encoded.release()
        parameters.release()
    }
}

private fun copyFaceModelAsset(context: Context, assetPath: String, destination: File): String {
    if (!destination.exists() || destination.length() == 0L) {
        context.assets.open(assetPath).use { input ->
            FileOutputStream(destination).use { output -> input.copyTo(output) }
        }
    }
    check(destination.length() > 0L) { "Model asset $assetPath is empty." }
    return destination.absolutePath
}
