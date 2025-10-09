{{- define "newsbot.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "newsbot.fullname" -}}
{{- printf "%s-%s" .Release.Name (include "newsbot.name" .) | trunc 63 | trimSuffix "-" -}}
{{- end -}}
