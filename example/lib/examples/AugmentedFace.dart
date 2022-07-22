import 'dart:typed_data';

import 'package:ar_augmented_faces/managers/ar_location_manager.dart';
import 'package:ar_augmented_faces/managers/ar_session_manager.dart';
import 'package:ar_augmented_faces/managers/ar_object_manager.dart';
import 'package:ar_augmented_faces/managers/ar_anchor_manager.dart';
import 'package:flutter/material.dart';
import 'package:ar_augmented_faces/ar_augmented_faces.dart';
import 'package:ar_augmented_faces/datatypes/config_planedetection.dart';
import 'package:flutter/services.dart';

class AugmentedFace extends StatefulWidget {
  AugmentedFace({Key key}) : super(key: key);
  @override
  _AugmentedFaceState createState() => _AugmentedFaceState();
}

class _AugmentedFaceState extends State<AugmentedFace> {
  ARSessionManager arSessionManager;
  ARObjectManager arObjectManager;
  bool _showFeaturePoints = false;
  bool _showPlanes = false;
  bool _showWorldOrigin = false;
  bool _showAnimatedGuide = true;
  String _planeTexturePath = "Images/triangle.png";
  bool _handleTaps = false;

  @override
  void dispose() {
    super.dispose();
    arSessionManager.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
        appBar: AppBar(
          title: const Text('Debug Options'),
        ),
        body: ARView(
          onARViewCreated: onARViewCreated,
          planeDetectionConfig: PlaneDetectionConfig.horizontalAndVertical,
          showPlatformType: false,
          enableAugmentedFaces : true
        ));
  }

  void onARViewCreated(
      ARSessionManager arSessionManager,
      ARObjectManager arObjectManager,
      ARAnchorManager arAnchorManager,
      ARLocationManager arLocationManager) {
    this.arSessionManager = arSessionManager;
    this.arObjectManager = arObjectManager;

    this.arSessionManager.onInitialize(
      showFeaturePoints: _showFeaturePoints,
      showPlanes: _showPlanes,
      customPlaneTexturePath: _planeTexturePath,
      showWorldOrigin: _showWorldOrigin,

      showAnimatedGuide: _showAnimatedGuide,
      handleTaps: _handleTaps,
    );

    this.arObjectManager.onInitialize();

    loadMesh();
  }

  loadMesh() async {
    final ByteData textureBytes =
    await rootBundle.load('assets/tomahawk-safety-glasses.png');//tomahawk-safety-glasses.png // fox_face_mesh_texture.png

    this.arObjectManager.loadMesh(
        textureBytes: textureBytes.buffer.asUint8List(),
        skin3DModelFilename: 'glasses.sfb');//fox_face//glasses.sfb
  }

  void updateSessionSettings() {
    this.arSessionManager.onInitialize(
      showFeaturePoints: _showFeaturePoints,
      showPlanes: _showPlanes,
      customPlaneTexturePath: _planeTexturePath,
      showWorldOrigin: _showWorldOrigin,
    );
  }
}
