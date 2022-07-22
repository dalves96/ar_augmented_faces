import Flutter
import UIKit

public class SwiftArFlutterPlugin: NSObject, FlutterPlugin {
  public static func register(with registrar: FlutterPluginRegistrar) {
    let channel = FlutterMethodChannel(name: "ar_augmented_faces", binaryMessenger: registrar.messenger())
    let instance = SwiftArFlutterPlugin()
    registrar.addMethodCallDelegate(instance, channel: channel)
    
    let factory = IosARViewFactory(messenger: registrar.messenger())
    registrar.register(factory, withId: "ar_augmented_faces")
  }

  public func handle(_ call: FlutterMethodCall, result: @escaping FlutterResult) {
    result("iOS " + UIDevice.current.systemVersion)
  }

}
