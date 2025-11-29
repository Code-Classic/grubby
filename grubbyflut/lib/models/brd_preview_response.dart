class BrdPreviewResponse {
  BrdPreviewResponse({required this.requestId, required this.format, required this.content});

  final int requestId;
  final String format;
  final String content;

  static int _parseId(dynamic v) {
    if (v is int) return v;
    if (v is num) return v.toInt();
    if (v is String) return int.tryParse(v) ?? (throw FormatException('Invalid requestId: $v'));
    throw FormatException('Unsupported requestId type: ${v.runtimeType}');
  }

  factory BrdPreviewResponse.fromJson(Map<String, dynamic> json) => BrdPreviewResponse(
        requestId: _parseId(json['requestId']),
        format: json['format'] as String? ?? 'markdown',
        content: json['content'] as String? ?? '',
      );
}
