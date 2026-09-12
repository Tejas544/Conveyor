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
  # Phase 15 (BUG-0047): 45 (90s) was enough for a cold single-replica start, but not for several
  # JVMs starting at once under their own per-pod CPU *limit* (not cluster-wide contention — node
  # CPU stayed at ~33% throughout, see RESULTS.md) — Spring context init is CPU-bound and a
  # cgroup-throttled JVM competing with its own sibling replicas' startup measurably slows down,
  # observed live crash-looping saga-orchestrator (1000m limit) under Phase 15's own 6-replica
  # scale-up, never once reaching Ready inside the old 90s window. 90 (180s) gives real fan-out
  # startup enough room without masking an actually-hung pod forever.
  failureThreshold: 90
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
