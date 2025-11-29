class GenerateBrdRequest {
  GenerateBrdRequest({
    required this.repoUrl,
    this.branch,
    this.commitSha,
    this.featureContext,
    this.projectType,
    this.authType,
    this.authToken,
    this.forceReanalyze = false,
    this.options = const {},
  });

  String repoUrl;
  String? branch;
  String? commitSha;
  String? featureContext;
  String? projectType;
  String? authType; // none|token|oauth
  String? authToken; // only for dev; in prod use vault approach
  bool forceReanalyze;
  Map<String, String> options;

  Map<String, dynamic> toJson() => {
        'repoUrl': repoUrl,
        if (branch != null && branch!.isNotEmpty) 'branch': branch,
        if (commitSha != null && commitSha!.isNotEmpty) 'commitSha': commitSha,
        if (featureContext != null && featureContext!.isNotEmpty) 'featureContext': featureContext,
        if (projectType != null && projectType!.isNotEmpty) 'projectType': projectType,
        if (authType != null && authType!.isNotEmpty) 'authType': authType,
        if (authToken != null && authToken!.isNotEmpty) 'authToken': authToken,
        'forceReanalyze': forceReanalyze,
        if (options.isNotEmpty) 'options': options,
      };
}
