import 'package:flutter/material.dart';
import '../api/brd_api_client.dart';
import '../models/generate_brd_request.dart';
import 'status_screen.dart';

class GenerateScreen extends StatefulWidget {
  const GenerateScreen({super.key, required this.api});
  final BrdApiClient api;

  @override
  State<GenerateScreen> createState() => _GenerateScreenState();
}

class _GenerateScreenState extends State<GenerateScreen> {
  final _formKey = GlobalKey<FormState>();
  final _repoUrlCtrl = TextEditingController();
  final _branchCtrl = TextEditingController();
  final _featureCtrl = TextEditingController();
  final _tokenCtrl = TextEditingController();
  bool _forceReanalyze = false;
  bool _loading = false;

  @override
  void dispose() {
    _repoUrlCtrl.dispose();
    _branchCtrl.dispose();
    _featureCtrl.dispose();
    _tokenCtrl.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    if (!_formKey.currentState!.validate()) return;
    setState(() => _loading = true);
    try {
      final req = GenerateBrdRequest(
        repoUrl: _repoUrlCtrl.text.trim(),
        branch: _branchCtrl.text.trim().isEmpty ? null : _branchCtrl.text.trim(),
        featureContext: _featureCtrl.text.trim().isEmpty ? null : _featureCtrl.text.trim(),
        authType: _tokenCtrl.text.trim().isEmpty ? 'none' : 'token',
        authToken: _tokenCtrl.text.trim().isEmpty ? null : _tokenCtrl.text.trim(),
        forceReanalyze: _forceReanalyze,
      );
      final res = await widget.api.generate(req);
      if (!mounted) return;
      Navigator.of(context).push(MaterialPageRoute(
        builder: (_) => StatusScreen(api: widget.api, requestId: res.requestId),
      ));
    } catch (e) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text('Error: $e')));
    } finally {
      if (mounted) setState(() => _loading = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Generate BRD')),
      body: Center(
        child: ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: 700),
          child: Padding(
            padding: const EdgeInsets.all(16.0),
            child: Form(
              key: _formKey,
              child: SingleChildScrollView(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    TextFormField(
                      controller: _repoUrlCtrl,
                      decoration: const InputDecoration(labelText: 'Repository URL', hintText: 'https://github.com/org/repo'),
                      validator: (v) => (v == null || v.trim().isEmpty) ? 'Repo URL is required' : null,
                    ),
                    const SizedBox(height: 12),
                    TextFormField(
                      controller: _branchCtrl,
                      decoration: const InputDecoration(labelText: 'Branch (optional)'),
                    ),
                    const SizedBox(height: 12),
                    TextFormField(
                      controller: _featureCtrl,
                      maxLines: 4,
                      decoration: const InputDecoration(labelText: 'Feature context (optional)', alignLabelWithHint: true),
                    ),
                    const SizedBox(height: 12),
                    TextFormField(
                      controller: _tokenCtrl,
                      decoration: const InputDecoration(labelText: 'Auth token (optional, for private repos)'),
                      obscureText: true,
                    ),
                    const SizedBox(height: 8),
                    Row(
                      children: [
                        Checkbox(
                          value: _forceReanalyze,
                          onChanged: (v) => setState(() => _forceReanalyze = v ?? false),
                        ),
                        const Text('Force re-analyze (bypass cache)')
                      ],
                    ),
                    const SizedBox(height: 24),
                    ElevatedButton.icon(
                      onPressed: _loading ? null : _submit,
                      icon: _loading
                          ? const SizedBox(width: 18, height: 18, child: CircularProgressIndicator(strokeWidth: 2))
                          : const Icon(Icons.play_arrow),
                      label: const Text('Generate BRD'),
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
