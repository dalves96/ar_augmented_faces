import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:ar_augmented_faces/ar_augmented_faces.dart';

void main() {
  const MethodChannel channel = MethodChannel('ar_augmented_faces');

  TestWidgetsFlutterBinding.ensureInitialized();

  setUp(() {
    channel.setMockMethodCallHandler((MethodCall methodCall) async {
      return '42';
    });
  });

  tearDown(() {
    channel.setMockMethodCallHandler(null);
  });

  test('getPlatformVersion', () async {
    expect(await ArFlutterPlugin.platformVersion, '42');
  });
}
