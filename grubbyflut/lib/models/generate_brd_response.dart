class GenerateBrdResponse {
  GenerateBrdResponse({required this.requestId, required this.status, required this.message});

  final int requestId;
  final String status;
  final String message;

  static int _parseId(dynamic v) {
    if (v is int) return v;
    if (v is num) return v.toInt();
    if (v is String) return int.tryParse(v) ?? (throw FormatException('Invalid requestId: $v'));
    throw FormatException('Unsupported requestId type: ${v.runtimeType}');
  }

  factory GenerateBrdResponse.fromJson(Map<String, dynamic> json) => GenerateBrdResponse(
        requestId: _parseId(json['requestId']),
        status: json['status'] as String? ?? '',
        message: json['message'] as String? ?? '',
      );
}
