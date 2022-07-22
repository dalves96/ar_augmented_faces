package io.carius.lars.ar_augmented_faces

import android.app.Activity
import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.View
import com.google.ar.core.*
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.UnavailableException
import com.google.ar.sceneform.*
import com.google.ar.sceneform.math.Vector3
import com.google.ar.sceneform.rendering.*
import com.google.ar.sceneform.ux.AugmentedFaceNode
import com.google.ar.sceneform.ux.FootprintSelectionVisualizer
import com.google.ar.sceneform.ux.TransformationSystem
import io.carius.lars.ar_augmented_faces.Serialization.deserializeMatrix4
import io.carius.lars.ar_augmented_faces.Serialization.serializeAnchor
import io.carius.lars.ar_augmented_faces.Serialization.serializeHitResult
import io.carius.lars.ar_augmented_faces.Serialization.serializePose
import io.carius.lars.ar_augmented_faces.utils.ArCoreUtils
import io.flutter.FlutterInjector
import io.flutter.embedding.engine.loader.FlutterLoader
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.platform.PlatformView
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.FloatBuffer


internal class AndroidARView(
        private val activity: Activity,
        context: Context,
        messenger: BinaryMessenger,
        id: Int
) : PlatformView, MethodChannel.MethodCallHandler {
    // constants
    private val tag: String = AndroidARView::class.java.name

    // Lifecycle variables
    private var footprintSelectionVisualizer = FootprintSelectionVisualizer()
    private lateinit var activityLifecycleCallbacks: Application.ActivityLifecycleCallbacks
    private val viewContext: Context

    // Platform channels
    private val sessionManagerChannel: MethodChannel = MethodChannel(messenger, "arsession_$id")
    private val objectManagerChannel: MethodChannel = MethodChannel(messenger, "arobjects_$id")
    private val anchorManagerChannel: MethodChannel = MethodChannel(messenger, "aranchors_$id")

    // UI variables
    private var arSceneView: ArSceneView? = null
    private var transformationSystem: TransformationSystem
    private var showFeaturePoints = false

    private var pointCloudNode = Node()
    private var worldOriginNode = Node()

    // Setting defaults
    private var enableRotation = false
    private var enablePans = false
    private var keepNodeSelected = true

    // Model builder
    private var modelBuilder = ArModelBuilder()

    // Cloud anchor handler
    private lateinit var cloudAnchorHandler: CloudAnchorHandler

    private lateinit var sceneUpdateListener: Scene.OnUpdateListener
    private lateinit var onNodeTapListener: Scene.OnPeekTouchListener

    private var faceSceneUpdateListener: Scene.OnUpdateListener
    private var faceMeshTexture: Texture? = null
    private val faceNodeMap = HashMap<AugmentedFace, AugmentedFaceNode>()
    private var faceRegionsRender: ModelRenderable? = null
    private val rcPermissions = 0x123
    private var isSupportedDevice = false
    private var installRequested: Boolean = false
    private val methodChannel: MethodChannel = MethodChannel(messenger, "arcore_flutter_plugin_$id")

    // Method channel handlers
    private val onSessionMethodCall =
            MethodChannel.MethodCallHandler { call, result ->
                Log.d(tag, "AndroidARView onSessionMethodCall received a call!")
                when (call.method) {
                    "init" -> {
                        initializeARView(call, result)
                    }
                    "getAnchorPose" -> {
                        val anchorNode = arSceneView!!.scene.findByName(call.argument("anchorId")) as AnchorNode?
                        if (anchorNode != null) {
                            result.success(serializePose(anchorNode.anchor!!.pose))
                        } else {
                            result.error("Error", "could not get anchor pose", null)
                        }
                    }
                    "getCameraPose" -> {
                        val cameraPose = arSceneView!!.arFrame?.camera?.displayOrientedPose
                        if (cameraPose != null) {
                            result.success(serializePose(cameraPose))
                        } else {
                            result.error("Error", "could not get camera pose", null)
                        }
                    }
                    "snapshot" -> {
                        val bitmap = Bitmap.createBitmap(arSceneView!!.width, arSceneView!!.height,
                                Bitmap.Config.ARGB_8888)

                        // Create a handler thread to offload the processing of the image.
                        val handlerThread = HandlerThread("PixelCopier")
                        handlerThread.start()
                        // Make the request to copy.
                        PixelCopy.request(arSceneView!!, bitmap, { copyResult: Int ->
                            Log.d(tag, "PIXEL COPY DONE")
                            if (copyResult == PixelCopy.SUCCESS) {
                                try {
                                    val mainHandler = Handler(context.mainLooper)
                                    val runnable = Runnable {
                                        val stream = ByteArrayOutputStream()
                                        bitmap.compress(Bitmap.CompressFormat.PNG, 90, stream)
                                        val data = stream.toByteArray()
                                        result.success(data)
                                    }
                                    mainHandler.post(runnable)
                                } catch (e: IOException) {
                                    result.error("e", e.message, e.stackTrace)
                                }
                            } else {
                                result.error("e", "failed to take screenshot", null)
                            }
                            handlerThread.quitSafely()
                        }, Handler(handlerThread.looper))
                    }
                    "dispose" -> {
                        dispose()
                    }
                    else -> {}
                }
            }
    private val onObjectMethodCall =
            MethodChannel.MethodCallHandler { call, result ->
                if (isSupportedDevice) {
                    when (call.method) {
                        "init" -> {
                            arSceneViewInit(result)
                        }
                        "loadMesh" -> {
                            val map = call.arguments as HashMap<*, *>
                            val textureBytes = map["textureBytes"] as ByteArray
                            val skin3DModelFilename = map["skin3DModelFilename"] as? String
                            loadMesh(textureBytes, skin3DModelFilename)
                        }
                        "dispose" -> {
                            dispose()
                        }
                        else -> {
                            result.notImplemented()
                        }
                    }
                } else {
                    result.error("Unsupported Device", "", null)
                }
            }
    private val onAnchorMethodCall =
            MethodChannel.MethodCallHandler { call, result ->
                when (call.method) {
                    "addAnchor" -> {
                        val anchorType: Int? = call.argument<Int>("type")
                        if (anchorType != null) {
                            when (anchorType) {
                                0 -> { // Plane Anchor
                                    val transform: ArrayList<Double>? = call.argument<ArrayList<Double>>("transformation")
                                    val name: String? = call.argument<String>("name")
                                    if (name != null && transform != null) {
                                        result.success(addPlaneAnchor(transform, name))
                                    } else {
                                        result.success(false)
                                    }

                                }
                                else -> result.success(false)
                            }
                        } else {
                            result.success(false)
                        }
                    }
                    "removeAnchor" -> {
                        val anchorName: String? = call.argument<String>("name")
                        anchorName?.let { name ->
                            removeAnchor(name)
                        }
                    }
                    "initGoogleCloudAnchorMode" -> {
                        if (arSceneView!!.session != null) {
                            val config = Config(arSceneView!!.session)
                            config.augmentedFaceMode = Config.AugmentedFaceMode.MESH3D
                            config.cloudAnchorMode = Config.CloudAnchorMode.ENABLED
                            config.updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                            config.focusMode = Config.FocusMode.AUTO
                            arSceneView!!.session?.configure(config)

                            cloudAnchorHandler = CloudAnchorHandler(arSceneView!!.session!!)
                        } else {
                            sessionManagerChannel.invokeMethod("onError", listOf("Error initializing cloud anchor mode: Session is null"))
                        }
                    }
                    "uploadAnchor" -> {
                        val anchorName: String? = call.argument<String>("name")
                        val ttl: Int? = call.argument<Int>("ttl")
                        anchorName?.let {
                            val anchorNode = arSceneView!!.scene.findByName(anchorName) as AnchorNode?
                            if (ttl != null) {
                                cloudAnchorHandler.hostCloudAnchorWithTtl(anchorName, anchorNode!!.anchor, CloudAnchorUploadedListener(), ttl)
                            } else {
                                cloudAnchorHandler.hostCloudAnchor(anchorName, anchorNode!!.anchor, CloudAnchorUploadedListener())
                            }
                            result.success(true)
                        }

                    }
                    "downloadAnchor" -> {
                        val anchorId: String? = call.argument<String>("cloudanchorid")
                        anchorId?.let {
                            cloudAnchorHandler.resolveCloudAnchor(anchorId, CloudAnchorDownloadedListener())
                        }
                    }
                    else -> {}
                }
            }

    override fun getView(): View {
        return arSceneView as View
    }

    private fun loadMesh(textureBytes: ByteArray?, skin3DModelFilename: String?) {
        if (skin3DModelFilename != null) {
            // Load the face regions render.
            // This is a skinned model that renders 3D objects mapped to the regions of the augmented face.
            ModelRenderable.builder()
                    .setSource(activity, Uri.parse(skin3DModelFilename))
                    .build()
                    .thenAccept { modelRenderable ->
                        faceRegionsRender = modelRenderable
                        modelRenderable.isShadowCaster = false
                        modelRenderable.isShadowReceiver = false
                    }
        }

        // Load the face mesh texture.
        Texture.builder()
                .setSource(BitmapFactory.decodeByteArray(textureBytes, 0, textureBytes!!.size))
                .build()
                .thenAccept { texture -> faceMeshTexture = texture }
    }

    private fun arSceneViewInit(result: MethodChannel.Result) {
        val enableAugmentedFaces = true// call.argument("enableAugmentedFaces")
        if (enableAugmentedFaces) {
            // This is important to make sure that the camera stream renders first so that
            // the face mesh occlusion works correctly.
            arSceneView!!.cameraStreamRenderPriority = Renderable.RENDER_PRIORITY_FIRST
            arSceneView!!.scene?.addOnUpdateListener(faceSceneUpdateListener)
        }

        result.success(null)
    }

    override fun dispose() {
        // Destroy AR session
        try {
            onPause()
            onDestroy()
            ArSceneView.destroyAllResources()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    init {
        methodChannel.setMethodCallHandler(this)
        if (ArCoreUtils.checkIsSupportedDeviceOrFinish(activity)) {
            isSupportedDevice = true
            arSceneView = ArSceneView(context)
            ArCoreUtils.requestCameraPermission(activity, rcPermissions)
            setupLifeCycle()
        }
        faceSceneUpdateListener = Scene.OnUpdateListener {
            run {
                if (faceMeshTexture == null) {
                    return@OnUpdateListener
                }
                val faceList = arSceneView?.session?.getAllTrackables(AugmentedFace::class.java)
                faceList?.let {
                    // Make new AugmentedFaceNodes for any new faces.
                    for (face in faceList) {
                        if (!faceNodeMap.containsKey(face)) {
                            val faceNode = AugmentedFaceNode(face)
                            faceNode.setParent(arSceneView?.scene)
                            faceNode.faceRegionsRenderable = faceRegionsRender
                            faceNode.faceMeshTexture = faceMeshTexture
                            faceNodeMap[face] = faceNode

                            // change assets on runtime
                        } else if (faceNodeMap[face]?.faceRegionsRenderable != faceRegionsRender || faceNodeMap[face]?.faceMeshTexture != faceMeshTexture) {
                            faceNodeMap[face]?.faceRegionsRenderable = faceRegionsRender
                            faceNodeMap[face]?.faceMeshTexture = faceMeshTexture
                        }
                    }

                    // Remove any AugmentedFaceNodes associated with an AugmentedFace that stopped tracking.
                    val i = faceNodeMap.iterator()
                    while (i.hasNext()) {
                        val entry = i.next()
                        val face = entry.key
                        if (face.trackingState == TrackingState.STOPPED) {
                            val faceNode = entry.value
                            faceNode.setParent(null)
                            i.remove()
                        }
                    }
                }
            }
        }
        viewContext = context

        arSceneView = ArSceneView(context)

        // Lastly request CAMERA permission which is required by ARCore.
        ArCoreUtils.requestCameraPermission(activity, rcPermissions)
        setupLifeCycle()
        sessionManagerChannel.setMethodCallHandler(onSessionMethodCall)
        objectManagerChannel.setMethodCallHandler(onObjectMethodCall)
        anchorManagerChannel.setMethodCallHandler(onAnchorMethodCall)

        MaterialFactory.makeTransparentWithColor(context, Color(255f, 255f, 255f, 0.3f))
                .thenAccept { mat ->
                    footprintSelectionVisualizer.footprintRenderable = ShapeFactory.makeCylinder(0.7f, 0.05f, Vector3(0f, 0f, 0f), mat)
                }

        transformationSystem =
                TransformationSystem(
                        activity.resources.displayMetrics,
                        footprintSelectionVisualizer)

        onResume() // call onResume once to setup initial session
        // TODO: find out why this does not happen automatically
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        if (isSupportedDevice) {
            when (call.method) {
                "init" -> {
                    arSceneViewInit(result)
                }
                "loadMesh" -> {
                    val map = call.arguments as HashMap<*, *>
                    val textureBytes = map["textureBytes"] as ByteArray
                    val skin3DModelFilename = map["skin3DModelFilename"] as? String
                    loadMesh(textureBytes, skin3DModelFilename)
                }
                "dispose" -> {
                    dispose()
                }
                else -> {
                    result.notImplemented()
                }
            }
        } else {
            result.error("Unsupported Device", "", null)
        }
    }


    private fun setupLifeCycle() {
        activityLifecycleCallbacks =
                object : Application.ActivityLifecycleCallbacks {
                    override fun onActivityCreated(
                            activity: Activity,
                            savedInstanceState: Bundle?
                    ) {
                        Log.d(tag, "onActivityCreated")
                    }

                    override fun onActivityStarted(activity: Activity) {
                        Log.d(tag, "onActivityStarted")
                    }

                    override fun onActivityResumed(activity: Activity) {
                        Log.d(tag, "onActivityResumed")
                        onResume()
                    }

                    override fun onActivityPaused(activity: Activity) {
                        Log.d(tag, "onActivityPaused")
                        onPause()
                    }

                    override fun onActivityStopped(activity: Activity) {
                        Log.d(tag, "onActivityStopped")
                        // onStopped()
                        onPause()
                    }

                    override fun onActivitySaveInstanceState(
                            activity: Activity,
                            outState: Bundle
                    ) {
                    }

                    override fun onActivityDestroyed(activity: Activity) {
                        Log.d(tag, "onActivityDestroyed")
//                        onPause()
//                        onDestroy()
                    }
                }

        activity.application.registerActivityLifecycleCallbacks(this.activityLifecycleCallbacks)
    }

    fun onResume() {
        if (arSceneView == null) {
            return
        }
        if (arSceneView?.session == null) {

            // request camera permission if not already requested
            if (!ArCoreUtils.hasCameraPermission(activity)) {
                ArCoreUtils.requestCameraPermission(activity, rcPermissions)
            }
            // If the session wasn't created yet, don't resume rendering.
            // This can happen if ARCore needs to be updated or permissions are not granted yet.
            try {
                val session = ArCoreUtils.createArSession(activity, installRequested, true)
                if (session == null) {
                    installRequested = false
                    return
                } else {
                    val config = Config(session)
                    config.augmentedFaceMode = Config.AugmentedFaceMode.MESH3D
                    config.updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                    session.configure(config)
                    arSceneView?.setupSession(session)
                }
            } catch (e: UnavailableException) {
                ArCoreUtils.handleSessionException(activity, e)
            }
        }
        try {
            arSceneView?.resume()
        } catch (ex: CameraNotAvailableException) {
            ArCoreUtils.displayError(activity, "Unable to get camera", ex)
            activity.finish()
            return
        }
    }

    fun onPause() {
        try {
            arSceneView!!.pause()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun onDestroy() {
        try {
            arSceneView?.scene?.removeOnUpdateListener(sceneUpdateListener)
            arSceneView?.scene?.removeOnUpdateListener(faceSceneUpdateListener)
            arSceneView?.scene?.removeOnPeekTouchListener(onNodeTapListener)
            arSceneView?.session?.close()
            arSceneView?.destroy()
            arSceneView!!.setupSession(null)
            arSceneView = null
        } catch (e: Exception) {
            e.printStackTrace()
        }

    }

    private fun initializeARView(call: MethodCall, result: MethodChannel.Result) {
        // Unpack call arguments
        val argShowFeaturePoints: Boolean? = call.argument<Boolean>("showFeaturePoints")
        val argPlaneDetectionConfig: Int? = call.argument<Int>("planeDetectionConfig")
        val argShowPlanes: Boolean? = call.argument<Boolean>("showPlanes")
        val argCustomPlaneTexturePath: String? = call.argument<String>("customPlaneTexturePath")
        val argShowWorldOrigin: Boolean? = call.argument<Boolean>("showWorldOrigin")
        val argHandleTaps: Boolean? = call.argument<Boolean>("handleTaps")
        val argHandleRotation: Boolean? = call.argument<Boolean>("handleRotation")
        val argHandlePans: Boolean? = call.argument<Boolean>("handlePans")

        sceneUpdateListener = Scene.OnUpdateListener {
            onFrame()
        }
        onNodeTapListener = Scene.OnPeekTouchListener { hitTestResult, motionEvent ->
            if (hitTestResult.node != null && motionEvent?.action == MotionEvent.ACTION_DOWN) {
                objectManagerChannel.invokeMethod("onNodeTap", listOf(hitTestResult.node?.name))
            }
            transformationSystem.onTouch(
                    hitTestResult,
                    motionEvent
            )
        }
        arSceneView!!.scene?.addOnUpdateListener(sceneUpdateListener)
        arSceneView!!.scene?.addOnPeekTouchListener(onNodeTapListener)

        // Configure feature points
        if (argShowFeaturePoints ==
                true) { // explicit comparison necessary because of nullable type
            arSceneView!!.scene.addChild(pointCloudNode)
            showFeaturePoints = true
        } else {
            showFeaturePoints = false
            while ((pointCloudNode.children?.size
                            ?: 0) > 0) {
                pointCloudNode.children?.first()?.setParent(null)
            }
            pointCloudNode.setParent(null)
        }

        // Configure plane detection
        val config = arSceneView!!.session?.config
        if (config == null) {
            sessionManagerChannel.invokeMethod("onError", listOf("session is null"))
        }
        when (argPlaneDetectionConfig) {
            1 -> {
                config?.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL
            }
            2 -> {
                config?.planeFindingMode = Config.PlaneFindingMode.VERTICAL
            }
            3 -> {
                config?.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
            }
            else -> {
                config?.planeFindingMode = Config.PlaneFindingMode.DISABLED
            }
        }
        arSceneView!!.session?.configure(config)

        // Configure whether or not detected planes should be shown
        arSceneView!!.planeRenderer.isVisible = argShowPlanes == true
        // Create custom plane renderer (use supplied texture & increase radius)
        argCustomPlaneTexturePath?.let {
            val loader: FlutterLoader = FlutterInjector.instance().flutterLoader()
            val key: String = loader.getLookupKeyForAsset(it)

            val sampler =
                    Texture.Sampler.builder()
                            .setMinFilter(Texture.Sampler.MinFilter.LINEAR)
                            .setWrapMode(Texture.Sampler.WrapMode.REPEAT)
                            .build()
            Texture.builder()
                    .setSource(viewContext, Uri.parse(key))
                    .setSampler(sampler)
                    .build()
                    .thenAccept { texture: Texture? ->
                        arSceneView!!.planeRenderer.material.thenAccept { material: Material ->
                            material.setTexture(PlaneRenderer.MATERIAL_TEXTURE, texture)
                            material.setFloat(PlaneRenderer.MATERIAL_SPOTLIGHT_RADIUS, 10f)
                        }
                    }
            // Set radius to render planes in
            arSceneView!!.scene.addOnUpdateListener {
                val planeRenderer = arSceneView!!.planeRenderer
                planeRenderer.material.thenAccept { material: Material ->
                    material.setFloat(
                            PlaneRenderer.MATERIAL_SPOTLIGHT_RADIUS,
                            10f) // Sets the radius in which to visualize planes
                }
            }
        }
        // Configure world origin
        if (argShowWorldOrigin == true) {
            worldOriginNode = modelBuilder.makeWorldOriginNode(viewContext)
            arSceneView!!.scene.addChild(worldOriginNode)
        } else {
            worldOriginNode.setParent(null)
        }
        // Configure Tap handling
        if (argHandleTaps == true) { // explicit comparison necessary because of nullable type
            arSceneView!!.scene.setOnTouchListener { hitTestResult: HitTestResult, motionEvent: MotionEvent? -> onTap(hitTestResult, motionEvent) }
        }
        // Configure gestures
        enableRotation = argHandleRotation == true
        enablePans = argHandlePans == true
        result.success(null)
    }

    private fun onFrame() {
        if (showFeaturePoints) {
            // remove points from last frame
            while ((pointCloudNode.children?.size
                            ?: 0) > 0) {
                pointCloudNode.children?.first()?.setParent(null)
            }
            val pointCloud = arSceneView!!.arFrame?.acquirePointCloud()
            // Access point cloud data (returns FloatBufferW with x,y,z coordinates and confidence value).
            val points = pointCloud?.points ?: FloatBuffer.allocate(0)
            // Check if there are any feature points
            if (points.limit() / 4 >= 1) {
                for (index in 0 until points.limit() / 4) {
                    // Add feature point to scene
                    val featurePoint =
                            modelBuilder.makeFeaturePointNode(
                                    viewContext,
                                    points.get(4 * index),
                                    points.get(4 * index + 1),
                                    points.get(4 * index + 2))
                    featurePoint.setParent(pointCloudNode)
                }
            }
            // Release resources
            pointCloud?.release()
        }
        val updatedAnchors = arSceneView!!.arFrame!!.updatedAnchors
        // Notify the cloudManager of all the updates.
        if (this::cloudAnchorHandler.isInitialized) {
            cloudAnchorHandler.onUpdate(updatedAnchors)
        }

        if (keepNodeSelected && transformationSystem.selectedNode != null && transformationSystem.selectedNode!!.isTransforming) {
            // If the selected node is currently transforming, we want to deselect it as soon as the transformation is done
            keepNodeSelected = false
        }
        if (!keepNodeSelected && transformationSystem.selectedNode != null && !transformationSystem.selectedNode!!.isTransforming) {
            // once the transformation is done, deselect the node and allow selection of another node
            transformationSystem.selectNode(null)
            keepNodeSelected = true
        }
        if (!enablePans && !enableRotation) {
            //unselect all nodes as we do not want the selection visualizer
            transformationSystem.selectNode(null)
        }

    }

    private fun onTap(hitTestResult: HitTestResult, motionEvent: MotionEvent?): Boolean {
        val frame = arSceneView!!.arFrame
        if (hitTestResult.node != null && motionEvent?.action == MotionEvent.ACTION_DOWN) {
            objectManagerChannel.invokeMethod("onNodeTap", listOf(hitTestResult.node?.name))
            return true
        }
        if (motionEvent != null && motionEvent.action == MotionEvent.ACTION_DOWN) {
            return if (transformationSystem.selectedNode == null || (!enablePans && !enableRotation)) {
                val allHitResults = frame?.hitTest(motionEvent) ?: listOf<HitResult>()
                val planeAndPointHitResults =
                        allHitResults.filter { ((it.trackable is Plane) || (it.trackable is Point)) }
                val serializedPlaneAndPointHitResults: ArrayList<HashMap<String, Any>> =
                        ArrayList(planeAndPointHitResults.map { serializeHitResult(it) })
                sessionManagerChannel.invokeMethod(
                        "onPlaneOrPointTap",
                        serializedPlaneAndPointHitResults
                )
                true
            } else {
                false
            }

        }
        return false
    }

    private fun addPlaneAnchor(transform: ArrayList<Double>, name: String): Boolean {
        return try {
            val position = floatArrayOf(deserializeMatrix4(transform).second.x, deserializeMatrix4(transform).second.y, deserializeMatrix4(transform).second.z)
            val rotation = floatArrayOf(deserializeMatrix4(transform).third.x, deserializeMatrix4(transform).third.y, deserializeMatrix4(transform).third.z, deserializeMatrix4(transform).third.w)
            val anchor: Anchor = arSceneView!!.session!!.createAnchor(Pose(position, rotation))
            val anchorNode = AnchorNode(anchor)
            anchorNode.name = name
            anchorNode.setParent(arSceneView!!.scene)
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun removeAnchor(name: String) {
        val anchorNode = arSceneView!!.scene.findByName(name) as AnchorNode?
        anchorNode?.let {
            // Remove corresponding anchor from tracking
            anchorNode.anchor?.detach()
            // Remove children
            for (node in anchorNode.children) {
                if (transformationSystem.selectedNode?.name == node.name) {
                    transformationSystem.selectNode(null)
                    keepNodeSelected = true
                }
                node.setParent(null)
            }
            // Remove anchor node
            anchorNode.setParent(null)
        }
    }

    private inner class CloudAnchorUploadedListener : CloudAnchorHandler.CloudAnchorListener {
        override fun onCloudTaskComplete(anchorName: String?, anchor: Anchor?) {
            val cloudState = anchor!!.cloudAnchorState
            if (cloudState.isError) {
                Log.e(tag, "Error uploading anchor, state $cloudState")
                sessionManagerChannel.invokeMethod("onError", listOf("Error uploading anchor, state $cloudState"))
                return
            }
            // Swap old an new anchor of the respective AnchorNode
            val anchorNode = arSceneView!!.scene.findByName(anchorName) as AnchorNode?
            val oldAnchor = anchorNode?.anchor
            anchorNode?.anchor = anchor
            oldAnchor?.detach()

            val args = HashMap<String, String?>()
            args["name"] = anchorName
            args["cloudanchorid"] = anchor.cloudAnchorId
            anchorManagerChannel.invokeMethod("onCloudAnchorUploaded", args)
        }
    }

    private inner class CloudAnchorDownloadedListener : CloudAnchorHandler.CloudAnchorListener {
        override fun onCloudTaskComplete(anchorName: String?, anchor: Anchor?) {
            val cloudState = anchor!!.cloudAnchorState
            if (cloudState.isError) {
                Log.e(tag, "Error downloading anchor, state $cloudState")
                sessionManagerChannel.invokeMethod("onError", listOf("Error downloading anchor, state $cloudState"))
                return
            }
            val newAnchorNode = AnchorNode(anchor)
            // Register new anchor on the Flutter side of the plugin
            anchorManagerChannel.invokeMethod("onAnchorDownloadSuccess", serializeAnchor(newAnchorNode, anchor), object : MethodChannel.Result {
                override fun success(result: Any?) {
                    newAnchorNode.name = result.toString()
                    newAnchorNode.setParent(arSceneView!!.scene)
                }

                override fun error(errorCode: String, errorMessage: String?, errorDetails: Any?) {
                    sessionManagerChannel.invokeMethod("onError", listOf("Error while registering downloaded anchor at the AR Flutter plugin: $errorMessage"))
                }

                override fun notImplemented() {
                    sessionManagerChannel.invokeMethod("onError", listOf("Error while registering downloaded anchor at the AR Flutter plugin"))
                }
            })
        }
    }

}


