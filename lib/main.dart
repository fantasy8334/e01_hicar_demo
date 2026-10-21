import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

void main() => runApp(const MyApp());

class MyApp extends StatelessWidget {
  const MyApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'E01 HiCar Demo',
      theme: ThemeData(primarySwatch: Colors.blue),
      home: const HomePage(),
    );
  }
}

class HomePage extends StatefulWidget {
  const HomePage({super.key});

  @override
  State<HomePage> createState() => _HomePageState();
}

class _HomePageState extends State<HomePage> {
  static const MethodChannel _channel =
      MethodChannel('com.example.hicar_e01_demo/native');

  String status = '就绪';

  @override
  void initState() {
    super.initState();
    _channel.setMethodCallHandler((call) async {
      if (call.method == 'onStateChange') {
        setState(() {
          status = '链路状态：${call.arguments}';
        });
      }
    });
  }

  Future<void> _startBle() async {
    try {
      final res = await _channel.invokeMethod('startBleServer');
      setState(() => status = 'BLE已启动: $res');
    } catch (e) {
      setState(() => status = 'BLE启动失败:$e');
    }
  }

  Future<void> _stopBle() async {
    await _channel.invokeMethod('stopBleServer');
    setState(() => status = 'BLE已停止');
  }

  Future<void> _startWifi() async {
    try {
      final res = await _channel.invokeMethod('startWifiP2p');
      setState(() => status = 'WiFi P2P启动:$res');
    } catch (e) {
      setState(() => status = 'WiFi启动失败:$e');
    }
  }

  Future<void> _stopWifi() async {
    await _channel.invokeMethod('stopWifiP2p');
    setState(() => status = 'WiFi P2P已停止');
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('E01 HiCar 车机端')),
      body: Column(
        children: [
          // 原生视频渲染区域
          Expanded(
            flex: 3,
            child: Container(
              color: Colors.black,
              child: const AndroidView(
                viewType: 'hicar_video_view',
                creationParams: <String, dynamic>{},
                creationParamsCodec: StandardMessageCodec(),
              ),
            ),
          ),
          Expanded(
            flex: 1,
            child: Center(
              child: Column(
                mainAxisAlignment: MainAxisAlignment.center,
                children: [
                  Text('状态：$status', style: const TextStyle(fontSize: 16)),
                  const SizedBox(height: 20),
                  Wrap(
                    spacing: 8,
                    runSpacing: 8,
                    children: [
                      ElevatedButton(
                          onPressed: _startBle, child: const Text('启动BLE广播')),
                      ElevatedButton(
                          onPressed: _stopBle, child: const Text('停止BLE')),
                      ElevatedButton(
                          onPressed: _startWifi, child: const Text('启动WiFi P2P')),
                      ElevatedButton(
                          onPressed: _stopWifi, child: const Text('停止WiFi P2P')),
                    ],
                  ),
                ],
              ),
            ),
          ),
        ],
      ),
    );
  }
}
