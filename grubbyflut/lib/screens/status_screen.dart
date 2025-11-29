import 'dart:async';
import 'package:flutter/material.dart';
import '../api/brd_api_client.dart';
import '../models/brd_status_response.dart';
import 'preview_screen.dart';

class StatusScreen extends StatefulWidget {
  factory StatusScreen({Key? key, required BrdApiClient api, required dynamic requestId}) {
    return StatusScreen._(key: key, api: api, requestId: _parseId(requestId));
  }
  const StatusScreen._({super.key, required this.api, required this.requestId});
  final BrdApiClient api;
  final int requestId;

  static int _parseId(dynamic v) {
    if (v is int) return v;
    if (v is num) return v.toInt();
    if (v is String) return int.tryParse(v) ?? (throw FormatException('Invalid requestId: $v'));
    throw FormatException('Unsupported requestId type: ${v.runtimeType}');
  }

  @override
  State<StatusScreen> createState() => _StatusScreenState();
}

class _StatusScreenState extends State<StatusScreen> {
  Timer? _timer;
  BrdStatusResponse? _status;
  bool _loading = true;
  String? _error;

  @override
  void initState() {
    super.initState();
    _poll();
    _timer = Timer.periodic(const Duration(seconds: 2), (_) => _poll());
  }

  Future<void> _poll() async {
    try {
      final s = await widget.api.getStatus(widget.requestId);
      if (!mounted) return;
      setState(() {
        _status = s;
        _loading = false;
        _error = null;
      });
      if (s.isTerminal) {
        _timer?.cancel();
        if (s.status == 'COMPLETED' && mounted) {
          Navigator.of(context).pushReplacement(
            MaterialPageRoute(
              builder: (_) => PreviewScreen(api: widget.api, requestId: widget.requestId),
            ),
          );
        }
      }
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _error = e.toString();
        _loading = false;
      });
    }
  }

  @override
  void dispose() {
    _timer?.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final s = _status;
    return Scaffold(
      appBar: AppBar(title: const Text('BRD Generation Status')),
      body: Center(
        child: ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: 700),
          child: Padding(
            padding: const EdgeInsets.all(16),
            child: _loading
                ? const CircularProgressIndicator()
                : _error != null
                    ? Column(
                        mainAxisSize: MainAxisSize.min,
                        children: [
                          const Icon(Icons.error, color: Colors.red),
                          const SizedBox(height: 12),
                          Text(_error!, textAlign: TextAlign.center),
                          const SizedBox(height: 12),
                          ElevatedButton.icon(
                            onPressed: _poll,
                            icon: const Icon(Icons.refresh),
                            label: const Text('Retry'),
                          ),
                        ],
                      )
                    : Column(
                        mainAxisSize: MainAxisSize.min,
                        crossAxisAlignment: CrossAxisAlignment.center,
                        children: [
                          Text('Request ID: ${widget.requestId}', style: Theme.of(context).textTheme.bodySmall),
                          const SizedBox(height: 16),
                          Text('Status: ${s?.status ?? '-'}', style: Theme.of(context).textTheme.titleLarge),
                          const SizedBox(height: 12),
                          LinearProgressIndicator(value: (s?.progressPct ?? 0) / 100.0),
                          const SizedBox(height: 12),
                          Text('Stage: ${s?.stage ?? '-'}'),
                          if (s?.errorMessage != null) ...[
                            const SizedBox(height: 8),
                            Text('Error: ${s!.errorMessage}', style: const TextStyle(color: Colors.red)),
                          ],
                          const SizedBox(height: 24),
                          Row(
                            mainAxisAlignment: MainAxisAlignment.center,
                            children: [
                              ElevatedButton.icon(
                                onPressed: _poll,
                                icon: const Icon(Icons.refresh),
                                label: const Text('Refresh Now'),
                              ),
                            ],
                          )
                        ],
                      ),
          ),
        ),
      ),
    );
  }
}
