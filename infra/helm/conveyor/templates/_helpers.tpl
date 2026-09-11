{{/* Common labels applied to every resource this chart renders. */}}
{{- define "conveyor.labels" -}}
app.kubernetes.io/part-of: conveyor
app.kubernetes.io/managed-by: {{ .Release.Service }}
helm.sh/chart: {{ .Chart.Name }}-{{ .Chart.Version }}
{{- end -}}

{{/* Standard probe block, shared by every app Deployment — all six services expose the same
     Spring Boot Actuator probe groups (management.endpoint.health.probes.enabled: true, since
     Phase 1/8), just on different ports. */}}
{{- define "conveyor.probes" -}}
livenessProbe:
  httpGet:
    path: /actuator/health/liveness
    port: http
  initialDelaySeconds: 10
  periodSeconds: 10
  timeoutSeconds: 3
  failureThreshold: 5
readinessProbe:
  httpGet:
    path: /actuator/health/readiness
    port: http
  initialDelaySeconds: 10
  periodSeconds: 5
  timeoutSeconds: 3
  failureThreshold: 3
startupProbe:
  httpGet:
    path: /actuator/health/liveness
    port: http
  periodSeconds: 2
  failureThreshold: 45
{{- end -}}

{{/* Read-only-rootfs-compatible securityContext. The JVM (and, for conveyor-verifier, its report
     writer) only ever writes to /tmp; every other path is read-only. */}}
{{- define "conveyor.securityContext" -}}
allowPrivilegeEscalation: false
readOnlyRootFilesystem: true
runAsNonRoot: true
capabilities:
  drop: ["ALL"]
seccompProfile:
  type: RuntimeDefault
{{- end -}}
