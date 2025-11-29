import 'package:flutter/material.dart';
import 'package:flutter_markdown/flutter_markdown.dart';
import 'package:url_launcher/url_launcher.dart';
import '../api/brd_api_client.dart';

class PreviewScreen extends StatefulWidget {
  factory PreviewScreen({Key? key, required BrdApiClient api, required dynamic requestId}) {
    return PreviewScreen._(key: key, api: api, requestId: _parseId(requestId));
    
  }
  const PreviewScreen._({super.key, required this.api, required this.requestId});
  final BrdApiClient api;
  final int requestId;

  static int _parseId(dynamic v) {
    if (v is int) return v;
    if (v is num) return v.toInt();
    if (v is String) return int.tryParse(v) ?? (throw FormatException('Invalid requestId: $v'));
    throw FormatException('Unsupported requestId type: ${v.runtimeType}');
  }

  @override
  State<PreviewScreen> createState() => _PreviewScreenState();
}

class _PreviewScreenState extends State<PreviewScreen> {
  bool _loading = true;
  String? _error;
  String _markdown = '';

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      final res = await widget.api.getPreview(widget.requestId as int, format: 'markdown');
      if (!mounted) return;
      setState(() {
        _markdown = res.content;
      });
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _error = e.toString();
      });
    } finally {
      if (mounted) setState(() => _loading = false);
    }
  }

  Future<void> _openDownload(String format) async {
    final url = widget.api.downloadUrl(widget.requestId as int, format: format);
    if (!await launchUrl(url, mode: LaunchMode.externalApplication)) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('Could not launch download URL: $url')),
        );
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('BRD Preview')),
      body: SafeArea(
        child: SizedBox.expand(
          child: Center(
            child: ConstrainedBox(
              constraints: const BoxConstraints(maxWidth: 900),
              child: Padding(
                padding: const EdgeInsets.all(12.0),
                child: _loading
                    ? const Center(child: CircularProgressIndicator())
                    : _error != null
                        ? Column(
                            mainAxisSize: MainAxisSize.min,
                            children: [
                              const Icon(Icons.error, color: Colors.red),
                              const SizedBox(height: 8),
                              Text(_error!, textAlign: TextAlign.center),
                              const SizedBox(height: 12),
                              ElevatedButton.icon(
                                onPressed: _load,
                                icon: const Icon(Icons.refresh),
                                label: const Text('Retry'),
                              )
                            ],
                          )
                        : Column(
                            children: [
                              Row(
                                mainAxisAlignment: MainAxisAlignment.end,
                                children: [
                                  OutlinedButton.icon(
                                    onPressed: () => _openDownload('pdf'),
                                    icon: const Icon(Icons.picture_as_pdf),
                                    label: const Text('Download PDF'),
                                  ),
                                  const SizedBox(width: 8),
                                  OutlinedButton.icon(
                                    onPressed: () => _openDownload('docx'),
                                    icon: const Icon(Icons.description),
                                    label: const Text('Download DOCX'),
                                  ),
                                  const SizedBox(width: 8),
                                  OutlinedButton.icon(
                                    onPressed: () => _openDownload('md'),
                                    icon: const Icon(Icons.code),
                                    label: const Text('Download MD'),
                                  ),
                                  const SizedBox(width: 8),
                                  OutlinedButton.icon(
                                    onPressed: _load,
                                    icon: const Icon(Icons.refresh),
                                    label: const Text('Refresh'),
                                  ),
                                ],
                              ),
                              const Divider(),
                              Expanded(
                                child: Markdown(
                                  data: _markdown,
                                  selectable: true,
                                ),
                              ),
                            ],
                          ),
              ),
            ),
          ),
        ),
      ),
    );
  }
}
