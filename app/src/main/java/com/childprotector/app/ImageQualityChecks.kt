package com.childprotector.app

object ImageQualityChecks {
    const val MIN_FACE_HEIGHT_RATIO = 0.20
    const val MIN_BRIGHTNESS = 45.0
    const val MAX_BRIGHTNESS = 215.0
    const val MIN_LAPLACIAN_VARIANCE = 40.0

    fun hint(
        faceCount: Int,
        frameHeight: Int,
        faceHeight: Double?,
        brightness: Double? = null,
        laplacianVariance: Double? = null
    ): String? {
        if (faceCount != 1) return "Keep exactly one face in view of the camera."
        if (
            frameHeight <= 0 || faceHeight == null || !faceHeight.isFinite() ||
            faceHeight <= 0.0 || faceHeight < frameHeight * MIN_FACE_HEIGHT_RATIO
        ) {
            return "Move closer so the face fills more of the frame."
        }
        if (brightness != null && brightness < MIN_BRIGHTNESS) {
            return "Too dark. Move to a brighter area."
        }
        if (brightness != null && brightness > MAX_BRIGHTNESS) {
            return "Too bright. Reduce glare and try again."
        }
        if (laplacianVariance != null && laplacianVariance < MIN_LAPLACIAN_VARIANCE) {
            return "Hold still while the camera focuses."
        }
        return null
    }
}
