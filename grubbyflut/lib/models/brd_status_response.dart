class BrdStatusResponse {
  BrdStatusResponse({
    required this.requestId,
    required this.status,
    required this.progressPct,
    required this.stage,
    this.errorMessage,
  });

  final int requestId;
  final String status;
  final int progressPct;
  final String stage;
  final String? errorMessage;

  static int _parseId(dynamic v) {
    if (v is int) return v;
    if (v is num) return v.toInt();
    if (v is String) return int.tryParse(v) ?? (throw FormatException('Invalid requestId: $v'));
    throw FormatException('Unsupported requestId type: ${v.runtimeType}');
  }

  factory BrdStatusResponse.fromJson(Map<String, dynamic> json) => BrdStatusResponse(
        requestId: _parseId(json['requestId']),
        status: json['status'] as String? ?? '',
        progressPct: (json['progressPct'] as num?)?.toInt() ?? 0,
        stage: json['stage'] as String? ?? '',
        errorMessage: json['errorMessage'] as String?,
      );

  bool get isTerminal => status == 'COMPLETED' || status == 'FAILED';
}
