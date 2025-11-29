import 'dart:convert';
import 'package:http/http.dart' as http;
import '../config.dart';
import '../models/generate_brd_request.dart';
import '../models/generate_brd_response.dart';
import '../models/brd_status_response.dart';
import '../models/brd_preview_response.dart';

class BrdApiClient {
  BrdApiClient({http.Client? client, String? baseUrl})
      : _client = client ?? http.Client(),
        _baseUrl = (baseUrl ?? API_BASE_URL).replaceAll(RegExp(r'/+$'), '');

  final http.Client _client;
  final String _baseUrl;

  Uri _uri(String path, [Map<String, String>? query]) {
    final q = query == null || query.isEmpty ? '' : '?${Uri(queryParameters: query).query}';
    return Uri.parse('$_baseUrl$path$q');
  }

  Future<GenerateBrdResponse> generate(GenerateBrdRequest req, {String? userId}) async {
    final res = await _client.post(
      _uri('/api/v1/brd/generate'),
      headers: {
        'Content-Type': 'application/json',
        if (userId != null) 'X-User-Id': userId,
      },
      body: jsonEncode(req.toJson()),
    );
    if (res.statusCode != 202 && res.statusCode != 200) {
      throw ApiException('Failed to start generation: ${res.statusCode} ${res.body}');
    }
    final json = jsonDecode(res.body) as Map<String, dynamic>;
    return GenerateBrdResponse.fromJson(json);
  }

  Future<BrdStatusResponse> getStatus(int requestId) async {
    final res = await _client.get(_uri('/api/v1/brd/$requestId/status'));
    if (res.statusCode != 200) {
      throw ApiException('Failed to get status: ${res.statusCode} ${res.body}');
    }
    final json = jsonDecode(res.body) as Map<String, dynamic>;
    return BrdStatusResponse.fromJson(json);
  }

  Future<BrdPreviewResponse> getPreview(int requestId, {String format = 'markdown'}) async {
    final res = await _client.get(_uri('/api/v1/brd/$requestId/preview', {'format': format}));
    if (res.statusCode != 200) {
      throw ApiException('Failed to get preview: ${res.statusCode} ${res.body}');
    }
    final json = jsonDecode(res.body) as Map<String, dynamic>;
    return BrdPreviewResponse.fromJson(json);
  }

  /// Construct a URL for downloading the document; open it with url_launcher
  Uri downloadUrl(int requestId, {String format = 'pdf'}) {
    return _uri('/api/v1/brd/$requestId/download', {'format': format});
  }
}

class ApiException implements Exception {
  ApiException(this.message);
  final String message;
  @override
  String toString() => 'ApiException: $message';
}
