import 'package:flutter_test/flutter_test.dart';
import 'package:blur_checker/blur_checker.dart';
import 'package:blur_checker/blur_checker_platform_interface.dart';
import 'package:blur_checker/blur_checker_method_channel.dart';
import 'package:plugin_platform_interface/plugin_platform_interface.dart';

class MockBlurCheckerPlatform
    with MockPlatformInterfaceMixin
    implements BlurCheckerPlatform {

  @override
  Future<String?> getPlatformVersion() => Future.value('42');
}

void main() {
  final BlurCheckerPlatform initialPlatform = BlurCheckerPlatform.instance;

  test('$MethodChannelBlurChecker is the default instance', () {
    expect(initialPlatform, isInstanceOf<MethodChannelBlurChecker>());
  });

  test('getPlatformVersion', () async {
    BlurChecker blurCheckerPlugin = BlurChecker();
    MockBlurCheckerPlatform fakePlatform = MockBlurCheckerPlatform();
    BlurCheckerPlatform.instance = fakePlatform;

    expect(await blurCheckerPlugin.getPlatformVersion(), '42');
  });
}
