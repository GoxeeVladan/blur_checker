import 'package:plugin_platform_interface/plugin_platform_interface.dart';

import 'blur_checker_method_channel.dart';

abstract class BlurCheckerPlatform extends PlatformInterface {
  /// Constructs a BlurCheckerPlatform.
  BlurCheckerPlatform() : super(token: _token);

  static final Object _token = Object();

  static BlurCheckerPlatform _instance = MethodChannelBlurChecker();

  /// The default instance of [BlurCheckerPlatform] to use.
  ///
  /// Defaults to [MethodChannelBlurChecker].
  static BlurCheckerPlatform get instance => _instance;

  /// Platform-specific implementations should set this with their own
  /// platform-specific class that extends [BlurCheckerPlatform] when
  /// they register themselves.
  static set instance(BlurCheckerPlatform instance) {
    PlatformInterface.verifyToken(instance, _token);
    _instance = instance;
  }

  Future<String?> getPlatformVersion() {
    throw UnimplementedError('platformVersion() has not been implemented.');
  }
}
