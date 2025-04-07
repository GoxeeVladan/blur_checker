import 'package:flutter/services.dart';

import 'blur_checker_platform_interface.dart';

class BlurChecker {
  Future<String?> getPlatformVersion() {
    return BlurCheckerPlatform.instance.getPlatformVersion();
  }

  static const MethodChannel _channel = MethodChannel('blur_checker_native');

  static Future<double> getBlurVariance(String imagePath) async {
    final result = await _channel.invokeMethod('getLensDirtyScore', {'path': imagePath});
    return (result as num).toDouble();
  }
}
