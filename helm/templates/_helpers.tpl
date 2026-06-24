{{/*
Expand the name of the chart.
*/}}
{{- define "posting-manager.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" -}}
{{- end }}

{{/*
Fully qualified app name. Truncated at 63 chars (DNS label limit).
*/}}
{{- define "posting-manager.fullname" -}}
{{- if .Values.fullnameOverride -}}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- $name := default .Chart.Name .Values.nameOverride -}}
{{- if contains $name .Release.Name -}}
{{- .Release.Name | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- printf "%s-%s" .Release.Name $name | trunc 63 | trimSuffix "-" -}}
{{- end -}}
{{- end -}}
{{- end }}

{{/*
Chart label string for k8s metadata.
*/}}
{{- define "posting-manager.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" -}}
{{- end }}

{{/*
Common labels.
*/}}
{{- define "posting-manager.labels" -}}
helm.sh/chart: {{ include "posting-manager.chart" . }}
{{ include "posting-manager.selectorLabels" . }}
{{- if .Chart.AppVersion }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
{{- end }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end }}

{{/*
Selector labels: identity-only — must not change across upgrades.
*/}}
{{- define "posting-manager.selectorLabels" -}}
app.kubernetes.io/name: {{ include "posting-manager.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end }}

{{/*
ServiceAccount name.
*/}}
{{- define "posting-manager.serviceAccountName" -}}
{{- if .Values.serviceAccount.create -}}
{{- default (include "posting-manager.fullname" .) .Values.serviceAccount.name -}}
{{- else -}}
{{- default "default" .Values.serviceAccount.name -}}
{{- end -}}
{{- end }}
