import 'package:flutter/material.dart';
import 'api/brd_api_client.dart';
import 'screens/generate_screen.dart';

void main() {
  runApp(const MyApp());
}

class MyApp extends StatelessWidget {
  const MyApp({super.key});

  @override
  Widget build(BuildContext context) {
    final api = BrdApiClient();
    return MaterialApp(
      title: 'Grubby BRD Generator',
      theme: ThemeData(
        colorScheme: ColorScheme.fromSeed(seedColor: Colors.indigo),
        useMaterial3: true,
      ),
      home: GenerateScreen(api: api),
    );
  }
}
