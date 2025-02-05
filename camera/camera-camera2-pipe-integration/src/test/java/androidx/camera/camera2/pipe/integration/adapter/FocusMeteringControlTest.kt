/*
 * Copyright 2021 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package androidx.camera.camera2.pipe.integration.adapter

import android.graphics.Rect
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.CaptureResult.CONTROL_AF_STATE
import android.hardware.camera2.CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED
import android.hardware.camera2.CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED
import android.hardware.camera2.params.MeteringRectangle
import android.os.Build
import android.util.Rational
import android.util.Size
import androidx.camera.camera2.pipe.CameraId
import androidx.camera.camera2.pipe.Result3A
import androidx.camera.camera2.pipe.integration.impl.CameraProperties
import androidx.camera.camera2.pipe.integration.impl.FocusMeteringControl
import androidx.camera.camera2.pipe.integration.impl.UseCaseCamera
import androidx.camera.camera2.pipe.integration.impl.UseCaseCameraRequestControl
import androidx.camera.camera2.pipe.integration.impl.UseCaseThreads
import androidx.camera.camera2.pipe.integration.testing.FakeCameraProperties
import androidx.camera.camera2.pipe.integration.testing.FakeUseCaseCameraRequestControl
import androidx.camera.camera2.pipe.testing.FakeCameraMetadata
import androidx.camera.camera2.pipe.testing.FakeFrameMetadata
import androidx.camera.core.CameraControl
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.FocusMeteringResult
import androidx.camera.core.MeteringPointFactory
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import androidx.camera.core.UseCase
import androidx.camera.core.impl.utils.executor.CameraXExecutors
import androidx.camera.testing.SurfaceTextureProvider
import androidx.camera.testing.fakes.FakeCamera
import androidx.camera.testing.fakes.FakeUseCase
import androidx.lifecycle.MutableLiveData
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.google.common.util.concurrent.FutureCallback
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import junit.framework.TestCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.internal.DoNotInstrument

private const val CAMERA_ID_0 = "0" // 640x480 sensor size
private const val CAMERA_ID_1 = "1" // 1920x1080 sensor size
private const val CAMERA_ID_2 = "2" // 640x480 sensor size, not support AF_AUTO.
private const val CAMERA_ID_3 = "3" // camera that does not support 3A regions.

private const val SENSOR_WIDTH = 640
private const val SENSOR_HEIGHT = 480
private const val SENSOR_WIDTH2 = 1920
private const val SENSOR_HEIGHT2 = 1080

private val AREA_WIDTH = (MeteringPointFactory.getDefaultPointSize() * SENSOR_WIDTH).toInt()
private val AREA_HEIGHT = (MeteringPointFactory.getDefaultPointSize() * SENSOR_HEIGHT).toInt()
private val AREA_WIDTH_2 = (MeteringPointFactory.getDefaultPointSize() * SENSOR_WIDTH2).toInt()
private val AREA_HEIGHT_2 = (MeteringPointFactory.getDefaultPointSize() * SENSOR_HEIGHT2).toInt()

private val M_RECT_1 = Rect(0, 0, AREA_WIDTH / 2, AREA_HEIGHT / 2)
private val M_RECT_2 = Rect(0, SENSOR_HEIGHT - AREA_HEIGHT / 2, AREA_WIDTH / 2, SENSOR_HEIGHT)
private val M_RECT_3 = Rect(
    SENSOR_WIDTH - AREA_WIDTH / 2,
    SENSOR_HEIGHT - AREA_HEIGHT / 2,
    SENSOR_WIDTH,
    SENSOR_HEIGHT
)

@RunWith(RobolectricCameraPipeTestRunner::class)
@Config(minSdk = Build.VERSION_CODES.LOLLIPOP)
@DoNotInstrument
class FocusMeteringControlTest {
    private val pointFactory = SurfaceOrientedMeteringPointFactory(1f, 1f)
    private lateinit var focusMeteringControl: FocusMeteringControl

    private val point1 = pointFactory.createPoint(0f, 0f)
    private val point2 = pointFactory.createPoint(0.0f, 1.0f)
    private val point3 = pointFactory.createPoint(1.0f, 1.0f)

    private val cameraPropertiesMap = mutableMapOf<String, CameraProperties>()

    private val fakeRequestControl = FakeUseCaseCameraRequestControl()
    private val fakeUseCaseThreads by lazy {
        val executor = Executors.newSingleThreadExecutor()
        val dispatcher = executor.asCoroutineDispatcher()
        val cameraScope = CoroutineScope(Job() + dispatcher)

        UseCaseThreads(
            cameraScope,
            executor,
            dispatcher,
        )
    }

    @Before
    fun setUp() {
        loadCameraProperties()
        fakeRequestControl.focusMeteringResult3A = Result3A(status = Result3A.Status.OK)
        focusMeteringControl = initFocusMeteringControl(CAMERA_ID_0)
    }

    @Test
    fun meteringRegionsFromMeteringPoint_fovAspectRatioEqualToCropAspectRatio() {
        val meteringPoint = FakeMeteringPointFactory().createPoint(0.0f, 0.0f)
        val meteringRectangles = FocusMeteringControl.meteringRegionsFromMeteringPoints(
            listOf(meteringPoint),
            1,
            Rect(0, 0, 800, 600),
            Rational(4, 3)
        )
        assertThat(meteringRectangles.size).isEqualTo(1)
        // Aspect ratio of crop region is same as default aspect ratio. So no padding is needed
        // along width or height. However only the bottom right quadrant of the metering rectangle
        // will fit inside the crop region.
        val expectedMeteringRectangle = MeteringRectangle(
            0, 0, 60, 45, FocusMeteringControl.METERING_WEIGHT_DEFAULT
        )
        assertThat(meteringRectangles[0]).isEqualTo(expectedMeteringRectangle)

        val meteringPoint1 = FakeMeteringPointFactory().createPoint(0.5f, 0.5f)
        val meteringRectangles1 = FocusMeteringControl.meteringRegionsFromMeteringPoints(
            listOf(meteringPoint1),
            1,
            Rect(0, 0, 800, 600),
            Rational(4, 3)
        )
        assertThat(meteringRectangles1.size).isEqualTo(1)
        // Aspect ratio of crop region is same as default aspect ratio. So no padding is needed
        // along width or height. The metering region will completely fit inside the crop region.
        val expectedMeteringRectangle1 = MeteringRectangle(
            340, 255, 120, 90, FocusMeteringControl.METERING_WEIGHT_DEFAULT
        )
        assertThat(meteringRectangles1[0]).isEqualTo(expectedMeteringRectangle1)

        val meteringPoint2 = FakeMeteringPointFactory().createPoint(1f, 1f)
        val meteringRectangles2 = FocusMeteringControl.meteringRegionsFromMeteringPoints(
            listOf(meteringPoint2),
            1,
            Rect(0, 0, 800, 600),
            Rational(4, 3)
        )
        assertThat(meteringRectangles2.size).isEqualTo(1)
        // Aspect ratio of crop region is same as default aspect ratio. So no padding is needed
        // along width or height. However only the top left quadrant of the metering rectangle
        // will fit inside the crop region.
        val expectedMeteringRectangle2 = MeteringRectangle(
            740, 555, 60, 45, FocusMeteringControl.METERING_WEIGHT_DEFAULT
        )
        assertThat(meteringRectangles2[0]).isEqualTo(expectedMeteringRectangle2)
    }

    @Test
    fun meteringRegionsFromMeteringPoint_fovAspectRatioGreaterThanCropAspectRatio() {
        val meteringPoint = FakeMeteringPointFactory().createPoint(0.0f, 0.0f)
        val meteringRectangles = FocusMeteringControl.meteringRegionsFromMeteringPoints(
            listOf(meteringPoint),
            1,
            Rect(0, 0, 400, 400),
            Rational(4, 3)
        )
        assertThat(meteringRectangles.size).isEqualTo(1)
        // Default aspect ratio is greater than the aspect ratio of the crop region. So we need
        // to add some padding at the top.
        val expectedMeteringRectangle = MeteringRectangle(
            0, 20, 30, 60, FocusMeteringControl.METERING_WEIGHT_DEFAULT
        )
        assertThat(meteringRectangles[0]).isEqualTo(expectedMeteringRectangle)

        val meteringPoint1 = FakeMeteringPointFactory().createPoint(0.5f, 0.5f)
        val meteringRectangles1 = FocusMeteringControl.meteringRegionsFromMeteringPoints(
            listOf(meteringPoint1),
            1,
            Rect(0, 0, 400, 400),
            Rational(4, 3)
        )
        assertThat(meteringRectangles1.size).isEqualTo(1)
        val expectedMeteringRectangle1 = MeteringRectangle(
            170, 170, 60, 60, FocusMeteringControl.METERING_WEIGHT_DEFAULT
        )
        assertThat(meteringRectangles1[0]).isEqualTo(expectedMeteringRectangle1)

        val meteringPoint2 = FakeMeteringPointFactory().createPoint(1f, 1f)
        val meteringRectangles2 = FocusMeteringControl.meteringRegionsFromMeteringPoints(
            listOf(meteringPoint2),
            1,
            Rect(0, 0, 400, 400),
            Rational(4, 3)
        )
        assertThat(meteringRectangles2.size).isEqualTo(1)
        // Default aspect ratio is greater than the aspect ratio of the crop region. So we need
        // to add some padding at the bottom.
        val expectedMeteringRectangle2 = MeteringRectangle(
            370, 320, 30, 60, FocusMeteringControl.METERING_WEIGHT_DEFAULT
        )
        assertThat(meteringRectangles2[0]).isEqualTo(expectedMeteringRectangle2)
    }

    @Test
    fun meteringRegionsFromMeteringPoint_fovAspectRatioLessThanCropAspectRatio() {
        val meteringPoint = FakeMeteringPointFactory().createPoint(0.0f, 0.0f)
        val meteringRectangles = FocusMeteringControl.meteringRegionsFromMeteringPoints(
            listOf(meteringPoint),
            1,
            Rect(0, 0, 400, 400),
            Rational(3, 4)
        )
        assertThat(meteringRectangles.size).isEqualTo(1)
        val expectedMeteringRectangle = MeteringRectangle(
            20, 0, 60, 30, FocusMeteringControl.METERING_WEIGHT_DEFAULT
        )
        assertThat(meteringRectangles[0]).isEqualTo(expectedMeteringRectangle)

        val meteringPoint1 = FakeMeteringPointFactory().createPoint(0.5f, 0.5f)
        val meteringRectangles1 = FocusMeteringControl.meteringRegionsFromMeteringPoints(
            listOf(meteringPoint1),
            1,
            Rect(0, 0, 400, 400),
            Rational(3, 4)
        )
        assertThat(meteringRectangles1.size).isEqualTo(1)
        val expectedMeteringRectangle1 = MeteringRectangle(
            170, 170, 60, 60, FocusMeteringControl.METERING_WEIGHT_DEFAULT
        )
        assertThat(meteringRectangles1[0]).isEqualTo(expectedMeteringRectangle1)

        val meteringPoint2 = FakeMeteringPointFactory().createPoint(1f, 1f)
        val meteringRectangles2 = FocusMeteringControl.meteringRegionsFromMeteringPoints(
            listOf(meteringPoint2),
            1,
            Rect(0, 0, 400, 400),
            Rational(3, 4)
        )
        assertThat(meteringRectangles2.size).isEqualTo(1)
        val expectedMeteringRectangle2 = MeteringRectangle(
            320, 370, 60, 30, FocusMeteringControl.METERING_WEIGHT_DEFAULT
        )
        assertThat(meteringRectangles2[0]).isEqualTo(expectedMeteringRectangle2)
    }

    @Test
    fun startFocusAndMetering_invalidPoint() = runBlocking {
        val invalidPoint = pointFactory.createPoint(1f, 1.1f)

        startFocusMeteringAndAwait(FocusMeteringAction.Builder(invalidPoint).build())

        // TODO: This will probably throw an invalid argument exception in future instead of
        //  passing the parameters to request control, better to assert the exception then.

        with(fakeRequestControl.focusMeteringCalls.last()) {
            assertWithMessage("Wrong number of AE regions").that(aeRegions.size).isEqualTo(0)
            assertWithMessage("Wrong number of AF regions").that(afRegions.size).isEqualTo(0)
            assertWithMessage("Wrong number of AWB regions").that(awbRegions.size).isEqualTo(0)
        }
    }

    @Test
    fun startFocusAndMetering_defaultPoint_3ARectsAreCorrect() = runBlocking {
        startFocusMeteringAndAwait(FocusMeteringAction.Builder(point1).build())

        with(fakeRequestControl.focusMeteringCalls.last()) {
            assertWithMessage("Wrong number of AE regions").that(aeRegions.size).isEqualTo(1)
            assertWithMessage("Wrong AE region").that(aeRegions[0].rect).isEqualTo(M_RECT_1)

            assertWithMessage("Wrong number of AF regions").that(afRegions.size).isEqualTo(1)
            assertWithMessage("Wrong AF region").that(afRegions[0].rect).isEqualTo(M_RECT_1)

            assertWithMessage("Wrong number of AWB regions").that(awbRegions.size).isEqualTo(1)
            assertWithMessage("Wrong AWB region").that(awbRegions[0].rect).isEqualTo(M_RECT_1)
        }
    }

    @Test
    fun startFocusAndMetering_multiplePoints_3ARectsAreCorrect() = runBlocking {
        // Camera 0 i.e. Max AF count = 3, Max AE count = 3, Max AWB count = 1
        startFocusMeteringAndAwait(
            FocusMeteringAction.Builder(point1)
                .addPoint(point2)
                .addPoint(point3)
                .build()
        )

        with(fakeRequestControl.focusMeteringCalls.last()) {
            assertWithMessage("Wrong number of AE regions").that(aeRegions.size).isEqualTo(3)
            assertWithMessage("Wrong AE region").that(aeRegions[0].rect).isEqualTo(M_RECT_1)
            assertWithMessage("Wrong AE region").that(aeRegions[1].rect).isEqualTo(M_RECT_2)
            assertWithMessage("Wrong AE region").that(aeRegions[2].rect).isEqualTo(M_RECT_3)

            assertWithMessage("Wrong number of AF regions").that(afRegions.size).isEqualTo(3)
            assertWithMessage("Wrong AF region").that(afRegions[0].rect).isEqualTo(M_RECT_1)
            assertWithMessage("Wrong AF region").that(afRegions[1].rect).isEqualTo(M_RECT_2)
            assertWithMessage("Wrong AF region").that(afRegions[2].rect).isEqualTo(M_RECT_3)

            assertWithMessage("Wrong number of AWB regions").that(awbRegions.size).isEqualTo(1)
            assertWithMessage("Wrong AWB region").that(awbRegions[0].rect).isEqualTo(M_RECT_1)
        }
    }

    @Test
    fun startFocusAndMetering_multiplePointsVariousModes() = runBlocking {
        // Camera 0 i.e. Max AF count = 3, Max AE count = 3, Max AWB count = 1
        startFocusMeteringAndAwait(
            FocusMeteringAction.Builder(point1, FocusMeteringAction.FLAG_AWB)
                .addPoint(point2, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
                .addPoint(
                    point3,
                    FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE or
                        FocusMeteringAction.FLAG_AWB
                )
                .build()
        )

        with(fakeRequestControl.focusMeteringCalls.last()) {
            assertWithMessage("Wrong number of AE regions").that(aeRegions.size).isEqualTo(2)
            assertWithMessage("Wrong AE region").that(aeRegions[0].rect).isEqualTo(M_RECT_2)
            assertWithMessage("Wrong AE region").that(aeRegions[1].rect).isEqualTo(M_RECT_3)

            assertWithMessage("Wrong number of AF regions").that(afRegions.size).isEqualTo(2)
            assertWithMessage("Wrong AF region").that(afRegions[0].rect).isEqualTo(M_RECT_2)
            assertWithMessage("Wrong AF region").that(afRegions[1].rect).isEqualTo(M_RECT_3)

            assertWithMessage("Wrong number of AWB regions").that(awbRegions.size).isEqualTo(1)
            assertWithMessage("Wrong AWB region").that(awbRegions[0].rect).isEqualTo(M_RECT_1)
        }
    }

    @Test
    fun startFocusAndMetering_multiplePointsDistinctModes() {
        // Camera 0 i.e. Max AF count = 3, Max AE count = 3, Max AWB count = 1
        startFocusMeteringAndAwait(
            FocusMeteringAction.Builder(point1, FocusMeteringAction.FLAG_AF)
                .addPoint(point2, FocusMeteringAction.FLAG_AWB)
                .addPoint(point3, FocusMeteringAction.FLAG_AE)
                .build()
        )

        with(fakeRequestControl.focusMeteringCalls.last()) {
            assertWithMessage("Wrong number of AE regions").that(aeRegions.size).isEqualTo(1)
            assertWithMessage("Wrong AE region").that(aeRegions[0].rect).isEqualTo(M_RECT_3)

            assertWithMessage("Wrong number of AF regions").that(afRegions.size).isEqualTo(1)
            assertWithMessage("Wrong AF region").that(afRegions[0].rect).isEqualTo(M_RECT_1)

            assertWithMessage("Wrong number of AWB regions").that(awbRegions.size).isEqualTo(1)
            assertWithMessage("Wrong AWB region").that(awbRegions[0].rect).isEqualTo(M_RECT_2)
        }
    }

    @Test
    fun previewFovAdjusted_16by9_to_4by3() {
        // use 16:9 preview aspect ratio with sensor region of 4:3 (camera 0)
        focusMeteringControl = initFocusMeteringControl(
            CAMERA_ID_0,
            setOf(createPreview(Size(1920, 1080)))
        )

        startFocusMeteringAndAwait(
            FocusMeteringAction.Builder(point1).build()
        )

        val adjustedRect = Rect(0, 60 - AREA_HEIGHT / 2, AREA_WIDTH / 2, 60 + AREA_HEIGHT / 2)
        with(fakeRequestControl.focusMeteringCalls.last()) {
            assertWithMessage("Wrong number of AF regions").that(afRegions.size).isEqualTo(1)
            assertWithMessage("Wrong AF region").that(afRegions[0].rect).isEqualTo(adjustedRect)
        }
    }

    @Test
    fun previewFovAdjusted_4by3_to_16by9() {
        // use 4:3 preview aspect ratio with sensor region of 16:9 (camera 1)
        focusMeteringControl = initFocusMeteringControl(
            CAMERA_ID_1,
            setOf(createPreview(Size(640, 480)))
        )

        startFocusMeteringAndAwait(
            FocusMeteringAction.Builder(point1).build()
        )

        val adjustedRect = Rect(
            240 - AREA_WIDTH_2 / 2, 0,
            240 + AREA_WIDTH_2 / 2, AREA_HEIGHT_2 / 2
        )
        with(fakeRequestControl.focusMeteringCalls.last()) {
            assertWithMessage("Wrong number of AF regions").that(afRegions.size).isEqualTo(1)
            assertWithMessage("Wrong AF region").that(afRegions[0].rect).isEqualTo(adjustedRect)
        }
    }

    @Test
    fun customFovAdjusted() {
        // 16:9 to 4:3
        val useCase = FakeUseCase()
        useCase.updateSuggestedResolution(Size(1920, 1080))

        val factory = SurfaceOrientedMeteringPointFactory(1.0f, 1.0f, useCase)
        val point = factory.createPoint(0f, 0f)

        focusMeteringControl = initFocusMeteringControl(
            CAMERA_ID_0,
            setOf(createPreview(Size(640, 480)))
        )

        startFocusMeteringAndAwait(
            FocusMeteringAction.Builder(point).build()
        )

        val adjustedRect = Rect(0, 60 - AREA_HEIGHT / 2, AREA_WIDTH / 2, 60 + AREA_HEIGHT / 2)
        with(fakeRequestControl.focusMeteringCalls.last()) {
            assertWithMessage("Wrong number of AF regions").that(afRegions.size).isEqualTo(1)
            assertWithMessage("Wrong AF region").that(afRegions[0].rect).isEqualTo(adjustedRect)
        }
    }

    @Test
    fun previewRatioNotUsed_whenPreviewUseCaseIsRemoved() {
        // add 16:9 aspect ratio Preview with sensor region of 4:3 (camera 0), then remove Preview
        focusMeteringControl = initFocusMeteringControl(
            CAMERA_ID_0,
            setOf(createPreview(Size(1920, 1080)))
        )
        fakeUseCaseCamera.runningUseCasesLiveData.value = emptySet()

        startFocusMeteringAndAwait(
            FocusMeteringAction.Builder(point1).build()
        )

        val adjustedRect = Rect(0, 60 - AREA_HEIGHT / 2, AREA_WIDTH / 2, 60 + AREA_HEIGHT / 2)
        with(fakeRequestControl.focusMeteringCalls.last()) {
            assertWithMessage("Wrong number of AF regions").that(afRegions.size).isEqualTo(1)
            assertWithMessage("Wrong AF region").that(afRegions[0].rect).isEqualTo(adjustedRect)
        }
    }

    @Test
    fun meteringPointsWithSize_convertedCorrectly() {
        val point1 = pointFactory.createPoint(0.5f, 0.5f, 1.0f)
        val point2 = pointFactory.createPoint(0.5f, 0.5f, 0.5f)
        val point3 = pointFactory.createPoint(0.5f, 0.5f, 0.1f)

        startFocusMeteringAndAwait(
            FocusMeteringAction.Builder(point1)
                .addPoint(point2)
                .addPoint(point3).build()
        )

        with(fakeRequestControl.focusMeteringCalls.last()) {
            assertWithMessage("Wrong number of AF regions").that(afRegions.size).isEqualTo(3)

            assertWithMessage("Wrong AF region width")
                .that(afRegions[0].rect.width()).isEqualTo((SENSOR_WIDTH * 1.0f).toInt())
            assertWithMessage("Wrong AF region height")
                .that(afRegions[0].rect.height()).isEqualTo((SENSOR_HEIGHT * 1.0f).toInt())

            assertWithMessage("Wrong AF region width")
                .that(afRegions[1].rect.width()).isEqualTo((SENSOR_WIDTH * 0.5f).toInt())
            assertWithMessage("Wrong AF region height")
                .that(afRegions[1].rect.height()).isEqualTo((SENSOR_HEIGHT * 0.5f).toInt())

            assertWithMessage("Wrong AF region width")
                .that(afRegions[2].rect.width()).isEqualTo((SENSOR_WIDTH * 0.1f).toInt())
            assertWithMessage("Wrong AF region height")
                .that(afRegions[2].rect.height()).isEqualTo((SENSOR_HEIGHT * 0.1f).toInt())
        }
    }

    @Test
    fun startFocusMetering_AfLocked_completesWithFocusFalse() {
        fakeRequestControl.focusMeteringResult3A = Result3A(
            status = Result3A.Status.OK,
            frameMetadata = FakeFrameMetadata(
                extraMetadata = mapOf(
                    CONTROL_AF_STATE to CONTROL_AF_STATE_FOCUSED_LOCKED
                )
            )
        )
        val action = FocusMeteringAction.Builder(point1).build()

        val future = focusMeteringControl.startFocusAndMetering(action)

        assertFutureFocusCompleted(future, true)
    }

    @Test
    fun startFocusMetering_AfNotLocked_completesWithFocusFalse() {
        fakeRequestControl.focusMeteringResult3A = Result3A(
            status = Result3A.Status.OK,
            frameMetadata = FakeFrameMetadata(
                extraMetadata = mapOf(
                    CONTROL_AF_STATE to CONTROL_AF_STATE_NOT_FOCUSED_LOCKED
                )
            )
        )
        val action = FocusMeteringAction.Builder(point1).build()

        val future = focusMeteringControl.startFocusAndMetering(action)

        assertFutureFocusCompleted(future, false)
    }

    @Test
    @Ignore("b/263323720: When AfState is null, it means AF is not supported")
    fun startFocusMetering_AfStateIsNull_completesWithFocusTrue() {
        fakeRequestControl.focusMeteringResult3A = Result3A(
            status = Result3A.Status.OK,
            frameMetadata = FakeFrameMetadata(
                extraMetadata = mapOf(
                    CONTROL_AF_STATE to null
                )
            )
        )
        val action = FocusMeteringAction.Builder(point1)
            .build()

        val result = focusMeteringControl.startFocusAndMetering(
            action
        )

        assertFutureFocusCompleted(result, true)
    }

    @Test
    @Ignore("b/263323720: When AF is not supported, focus should be reported as successful")
    fun startFocusMeteringAfRequested_CameraNotSupportAfAuto_CompletesWithTrue() {
        // Use camera which does not support AF_AUTO
        focusMeteringControl = initFocusMeteringControl(CAMERA_ID_2)
        val action = FocusMeteringAction.Builder(point1)
            .build()

        val result = focusMeteringControl.startFocusAndMetering(
            action
        )

        assertFutureFocusCompleted(result, true)
    }

    @Test
    @Ignore("b/205662153")
    fun startFocusMetering_cancelledBeforeCompletion_failsWithOperationCanceledOperation() {
        val action = FocusMeteringAction.Builder(point1).build()
        val future = focusMeteringControl.startFocusAndMetering(
            action
        )

        // TODO: Check if the following is the correct method to call while enabling this test
        focusMeteringControl.cancelFocusAndMeteringAsync()

        try {
            future.get()
            TestCase.fail("The future should fail.")
        } catch (e: ExecutionException) {
            assertThat(e.cause)
                .isInstanceOf(CameraControl.OperationCanceledException::class.java)
        } catch (e: InterruptedException) {
            assertThat(e.cause)
                .isInstanceOf(CameraControl.OperationCanceledException::class.java)
        }
    }

    @Test
    @Ignore("b/205662153: Enable when cancelFocusAndMetering implementation is completed")
    fun startThenCancelThenStart_previous2FuturesFailsWithOperationCanceled() {
        val action = FocusMeteringAction.Builder(point1)
            .build()

        val result1 = focusMeteringControl.startFocusAndMetering(action)
        // TODO: b/205662153
//        val result2 = focusMeteringControl.cancelFocusAndMetering()
        focusMeteringControl.startFocusAndMetering(action)

        assertFutureFailedWithOperationCancellation(result1)
        // TODO: b/205662153
//        assertFutureFailedWithOperationCancellation(result2)
    }

    @Test
    @Ignore("b/205662153: Enable when cancelFocusAndMetering implementation is completed")
    fun startMultipleActions_allExceptLatestAreCancelled() {
        val action = FocusMeteringAction.Builder(point1)
            .build()
        val result1 = focusMeteringControl.startFocusAndMetering(action)
        val result2 = focusMeteringControl.startFocusAndMetering(action)
        val result3 = focusMeteringControl.startFocusAndMetering(action)
        assertFutureFailedWithOperationCancellation(result1)
        assertFutureFailedWithOperationCancellation(result2)
        assertFutureFocusCompleted(result3, true)
    }

    @Test
    @Ignore("b/205662153: Enable when cancelFocusAndMetering implementation is completed")
    fun startFocusMetering_focusedThenCancel_futureStillCompletes() {
        fakeRequestControl.focusMeteringResult3A = Result3A(
            status = Result3A.Status.OK,
            frameMetadata = FakeFrameMetadata(
                extraMetadata = mapOf(
                    CONTROL_AF_STATE to CONTROL_AF_STATE_FOCUSED_LOCKED
                )
            )
        )
        val action = FocusMeteringAction.Builder(point1).build()

        val result = focusMeteringControl.startFocusAndMetering(action)

        // cancel it and then ensure the returned ListenableFuture still completes;
        // TODO: Check if the following is the correct method to call while enabling this test
        focusMeteringControl.cancelFocusAndMeteringAsync()
        assertFutureFocusCompleted(result, true)
    }

    @Test
    @Ignore("aosp/2369189")
    fun startFocusMeteringAFAEAWB_noPointsAreSupported_failFuture() {
        val focusMeteringControl = initFocusMeteringControl(CAMERA_ID_3)
        val action = FocusMeteringAction.Builder(
            point1,
            FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE or
                FocusMeteringAction.FLAG_AWB
        ).build()
        val future = focusMeteringControl.startFocusAndMetering(action)

        assertThrows(ExecutionException::class.java) {
            future[500, TimeUnit.MILLISECONDS]
        }.also {
            assertThat(it.cause).isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    @Test
    @Ignore("aosp/2369189")
    fun startFocusMeteringAEAWB_noPointsAreSupported_failFuture() {
        val focusMeteringControl = initFocusMeteringControl(CAMERA_ID_3)
        val action = FocusMeteringAction.Builder(
            point1,
            FocusMeteringAction.FLAG_AE or FocusMeteringAction.FLAG_AWB
        ).build()
        val future = focusMeteringControl.startFocusAndMetering(action)

        assertThrows(ExecutionException::class.java) {
            future[500, TimeUnit.MILLISECONDS]
        }.also {
            assertThat(it.cause).isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    @Test
    @Ignore("aosp/2369189")
    fun startFocusMeteringAFAWB_noPointsAreSupported_failFuture() {
        val focusMeteringControl = initFocusMeteringControl(CAMERA_ID_3)
        val action = FocusMeteringAction.Builder(
            point1,
            FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AWB
        ).build()
        val future = focusMeteringControl.startFocusAndMetering(action)

        assertThrows(ExecutionException::class.java) {
            future[500, TimeUnit.MILLISECONDS]
        }.also {
            assertThat(it.cause).isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    @Test
    fun startFocusMetering_morePointsThanSupported_futureCompletes() {
        // Camera 0 supports only 3 AF, 3 AE, 1 AWB regions, here we try to have 1 AE region, 2 AWB
        // regions. It should still complete the future.
        val action = FocusMeteringAction.Builder(
            point1,
            FocusMeteringAction.FLAG_AE or FocusMeteringAction.FLAG_AWB
        ).addPoint(point2, FocusMeteringAction.FLAG_AWB)
            .build()

        val future = focusMeteringControl.startFocusAndMetering(action)

        // isFocused should be false since AF shouldn't trigger for lack of AF region.
        assertFutureFocusCompleted(future, false)
    }

    @Test
    @Ignore("aosp/2369189")
    fun startFocusMetering_noPointsAreValid_failFuture() {
        val focusMeteringControl = initFocusMeteringControl(CAMERA_ID_0)

        // These will generate MeteringRectangles (width == 0 or height ==0)
        val invalidPt1 = pointFactory.createPoint(2.0f, 2.0f)
        val invalidPt2 = pointFactory.createPoint(2.0f, 0.5f)
        val invalidPt3 = pointFactory.createPoint(-1.0f, -1.0f)
        val action = FocusMeteringAction.Builder(invalidPt1, FocusMeteringAction.FLAG_AF)
            .addPoint(invalidPt2, FocusMeteringAction.FLAG_AE)
            .addPoint(invalidPt3, FocusMeteringAction.FLAG_AWB).build()
        val future = focusMeteringControl.startFocusAndMetering(action)

        assertThrows(ExecutionException::class.java) {
            future[500, TimeUnit.MILLISECONDS]
        }.also {
            assertThat(it.cause).isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    @Test
    fun isFocusMeteringSupported_allSupportedPoints_shouldReturnTrue() {
        val action = FocusMeteringAction.Builder(
            point1,
            FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE or
                FocusMeteringAction.FLAG_AWB
        ).addPoint(point2, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
            .addPoint(point2, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
            .build()
        assertThat(focusMeteringControl.isFocusMeteringSupported(action)).isTrue()
    }

    @Test
    fun isFocusMeteringSupported_morePointsThanSupported_shouldReturnTrue() {
        // Camera 0 supports 3 AF, 3 AE, 1 AWB regions, here we try to have 1 AE region, 2 AWB
        // regions. But it should still be supported.
        val action = FocusMeteringAction.Builder(
            point1,
            FocusMeteringAction.FLAG_AE or FocusMeteringAction.FLAG_AWB
        )
            .addPoint(point2, FocusMeteringAction.FLAG_AWB)
            .build()
        assertThat(focusMeteringControl.isFocusMeteringSupported(action)).isTrue()
    }

    @Test
    fun isFocusMeteringSupported_noSupport3ARegion_shouldReturnFalse() {
        val action = FocusMeteringAction.Builder(point1).build()

        // No 3A regions are supported on Camera3
        val focusMeteringControl = initFocusMeteringControl(CAMERA_ID_3)
        assertThat(focusMeteringControl.isFocusMeteringSupported(action)).isFalse()
    }

    @Test
    fun isFocusMeteringSupported_allInvalidPoints_shouldReturnFalse() {
        val invalidPoint1 = pointFactory.createPoint(1.1f, 0f)
        val invalidPoint2 = pointFactory.createPoint(0f, 1.1f)
        val invalidPoint3 = pointFactory.createPoint(-0.1f, 0f)
        val invalidPoint4 = pointFactory.createPoint(0f, -0.1f)
        val action = FocusMeteringAction.Builder(invalidPoint1)
            .addPoint(invalidPoint2)
            .addPoint(invalidPoint3)
            .addPoint(invalidPoint4).build()
        assertThat(focusMeteringControl.isFocusMeteringSupported(action)).isFalse()
    }

    // TODO: Port the following tests once their corresponding logics have been implemented.
    //  - [b/255679866] triggerAfWithTemplate, triggerAePrecaptureWithTemplate,
    //          cancelAfAeTriggerWithTemplate
    //  - startFocusAndMetering_AfRegionCorrectedByQuirk
    //  - [b/262225455] cropRegionIsSet_resultBasedOnCropRegion
    //  - [b/205662153] autoCancelDuration_completeWithIsFocusSuccessfulFalse,
    //      shorterAutoCancelDuration_cancelIsCalled_completeActionFutureIsNotCalled,
    //      longerAutoCancelDuration_cancelIsCalled_afterCompleteWithIsFocusSuccessfulFalse,
    //      autoCancelDurationDisabled_completeAfterAutoFocusTimeoutDuration
    //  The following ones will depend on how exactly they will be implemented.
    //  - [b/205662153] cancelFocusAndMetering_* (probably many of these tests will no longer be
    //      applicable in this level since Controller3A handles things a bit differently)
    //  - [b/264018162] addFocusMeteringOptions_hasCorrectAfMode,
    //                  startFocusMetering_isAfAutoModeIsTrue,
    //                  startFocusMetering_AfNotInvolved_isAfAutoModeIsSet,
    //                  startAndThenCancel_isAfAutoModeIsFalse
    //      (an alternative way can be checking the AF mode
    //      at the frame with AF_TRIGGER_START request in capture callback, but this requires
    //      invoking actual camera operations, ref: TapToFocusDeviceTest)

    private fun assertFutureFocusCompleted(
        future: ListenableFuture<FocusMeteringResult>,
        isFocused: Boolean
    ) {
        val focusMeteringResult = future[3, TimeUnit.SECONDS]
        assertThat(focusMeteringResult.isFocusSuccessful).isEqualTo(isFocused)
    }

    private fun <T> assertFutureFailedWithOperationCancellation(future: ListenableFuture<T>) {
        assertThrows(ExecutionException::class.java) {
            future[3, TimeUnit.SECONDS]
        }.apply {
            assertThat(cause).isInstanceOf(CameraControl.OperationCanceledException::class.java)
        }
    }

    private val focusMeteringResultCallback = object : FutureCallback<FocusMeteringResult?> {
        private var latch = CountDownLatch(1)

        @Volatile
        var successResult: FocusMeteringResult? = null

        @Volatile
        var failureThrowable: Throwable? = null

        override fun onSuccess(result: FocusMeteringResult?) {
            successResult = result
            latch.countDown()
        }

        override fun onFailure(t: Throwable) {
            failureThrowable = t
            latch.countDown()
        }

        fun reset() {
            latch = CountDownLatch(1)
        }

        suspend fun await(timeoutMs: Long = 10000) {
            withContext(Dispatchers.IO) {
                latch.await(timeoutMs, TimeUnit.MILLISECONDS)
            }
        }
    }

    private fun startFocusMetering(action: FocusMeteringAction) {
        focusMeteringResultCallback.reset()

        val result = focusMeteringControl.startFocusAndMetering(action)
        Futures.addCallback<FocusMeteringResult>(
            result,
            focusMeteringResultCallback,
            Executors.newSingleThreadExecutor()
        )
    }

    private fun startFocusMeteringAndAwait(action: FocusMeteringAction) = runBlocking {
        startFocusMetering(action)
        focusMeteringResultCallback.await()
    }

    private val fakeUseCaseCamera = object : UseCaseCamera {
        override val runningUseCasesLiveData: MutableLiveData<Set<UseCase>> =
            MutableLiveData(emptySet())

        override val requestControl: UseCaseCameraRequestControl
            get() = fakeRequestControl

        override fun <T> setParameterAsync(
            key: CaptureRequest.Key<T>,
            value: T,
            priority: androidx.camera.core.impl.Config.OptionPriority
        ): Deferred<Unit> {
            TODO("Not yet implemented")
        }

        override fun setParametersAsync(
            values: Map<CaptureRequest.Key<*>, Any>,
            priority: androidx.camera.core.impl.Config.OptionPriority
        ): Deferred<Unit> {
            TODO("Not yet implemented")
        }

        override fun close(): Job {
            TODO("Not yet implemented")
        }
    }

    private fun initFocusMeteringControl(
        cameraId: String,
        useCases: Set<UseCase> = emptySet(),
    ) = FocusMeteringControl(
            cameraPropertiesMap[cameraId]!!, fakeUseCaseThreads
        ).apply {
            fakeUseCaseCamera.runningUseCasesLiveData.value = useCases
            useCaseCamera = fakeUseCaseCamera
        }

    private fun initCameraProperties(
        cameraIdStr: String,
        characteristics: Map<CameraCharacteristics.Key<*>, Any?>
    ): FakeCameraProperties {
        val cameraId = CameraId(cameraIdStr)
        return FakeCameraProperties(
            FakeCameraMetadata(
                cameraId = cameraId,
                characteristics = characteristics
            ),
            cameraId
        )
    }

    private fun loadCameraProperties() {
        // **** Camera 0 characteristics (640X480 sensor size)****//
        val characteristics0 = mapOf(
            CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE to
                Rect(0, 0, SENSOR_WIDTH, SENSOR_HEIGHT),
            CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES to
                intArrayOf(
                    CaptureResult.CONTROL_AF_MODE_CONTINUOUS_VIDEO,
                    CaptureResult.CONTROL_AF_MODE_CONTINUOUS_PICTURE,
                    CaptureResult.CONTROL_AF_MODE_AUTO,
                    CaptureResult.CONTROL_AF_MODE_OFF
                ),
            CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES to
                intArrayOf(
                    CaptureResult.CONTROL_AE_MODE_ON,
                    CaptureResult.CONTROL_AE_MODE_ON_ALWAYS_FLASH,
                    CaptureResult.CONTROL_AE_MODE_ON_AUTO_FLASH,
                    CaptureResult.CONTROL_AE_MODE_OFF
                ),
            CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES to
                intArrayOf(
                    CaptureResult.CONTROL_AWB_MODE_AUTO,
                    CaptureResult.CONTROL_AWB_MODE_OFF
                ),
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL to
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY,
            CameraCharacteristics.CONTROL_MAX_REGIONS_AF to 3,
            CameraCharacteristics.CONTROL_MAX_REGIONS_AE to 3,
            CameraCharacteristics.CONTROL_MAX_REGIONS_AWB to 1
        )

        cameraPropertiesMap[CAMERA_ID_0] = initCameraProperties(
            CAMERA_ID_0,
            characteristics0
        )

        // **** Camera 1 characteristics (1920x1080 sensor size) ****//
        val characteristics1 = mapOf(
            CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE to
                Rect(0, 0, SENSOR_WIDTH2, SENSOR_HEIGHT2),
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL to
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3,
            CameraCharacteristics.CONTROL_MAX_REGIONS_AF to 1,
            CameraCharacteristics.CONTROL_MAX_REGIONS_AE to 1,
            CameraCharacteristics.CONTROL_MAX_REGIONS_AWB to 1
        )

        cameraPropertiesMap[CAMERA_ID_1] = initCameraProperties(
            CAMERA_ID_1,
            characteristics1
        )

        // **** Camera 2 characteristics (640x480 sensor size, does not support AF_AUTO ****//
        val characteristics2 = mapOf(
            CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE to
                Rect(0, 0, SENSOR_WIDTH, SENSOR_HEIGHT),
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL to
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3,
            CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES to
                intArrayOf(
                    CaptureResult.CONTROL_AF_MODE_OFF
                ),
            CameraCharacteristics.CONTROL_MAX_REGIONS_AF to 1,
            CameraCharacteristics.CONTROL_MAX_REGIONS_AE to 1,
            CameraCharacteristics.CONTROL_MAX_REGIONS_AWB to 1
        )

        cameraPropertiesMap[CAMERA_ID_2] = initCameraProperties(
            CAMERA_ID_2,
            characteristics2
        )

        // ** Camera 3 characteristics (640x480 sensor size, does not support any 3A regions //
        val characteristics3 = mapOf(
            CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE to
                Rect(0, 0, SENSOR_WIDTH, SENSOR_HEIGHT),
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL to
                CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3,
            CameraCharacteristics.CONTROL_MAX_REGIONS_AF to 0,
            CameraCharacteristics.CONTROL_MAX_REGIONS_AE to 0,
            CameraCharacteristics.CONTROL_MAX_REGIONS_AWB to 0
        )

        cameraPropertiesMap[CAMERA_ID_3] = initCameraProperties(
            CAMERA_ID_3,
            characteristics3
        )
    }

    private fun createPreview(suggestedResolution: Size) =
        Preview.Builder()
            .setCaptureOptionUnpacker { _, _ -> }
            .setSessionOptionUnpacker() { _, _ -> }
            .build().apply {
                setSurfaceProvider(
                    CameraXExecutors.mainThreadExecutor(),
                    SurfaceTextureProvider.createSurfaceTextureProvider()
                )
            }.also {
                it.bindToCamera(FakeCamera("0"), null, null)
                it.updateSuggestedResolution(suggestedResolution)
            }
}
