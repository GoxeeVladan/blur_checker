import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

import 'blur_checker_platform_interface.dart';

/// An implementation of [BlurCheckerPlatform] that uses method channels.
class MethodChannelBlurChecker extends BlurCheckerPlatform {
  /// The method channel used to interact with the native platform.
  @visibleForTesting
  final methodChannel = const MethodChannel('blur_checker');

  @override
  Future<String?> getPlatformVersion() async {
    final version = await methodChannel.invokeMethod<String>('getPlatformVersion');
    return version;
  }
}
