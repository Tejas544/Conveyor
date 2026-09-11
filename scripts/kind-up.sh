#!/usr/bin/env bash
# PLAN.md Phase 13: "kind/k3d bring-up script + metrics-server." Idempotent — safe to re-run
# against an already-up cluster (every step either no-ops or applies cleanly a second time).
# Mirrors `make up`'s role for docker-compose: the one command that gets a demoable stack running,
# just on Kubernetes instead of Compose. GNU Make is not installed on this machine (CONTEXT.md) —
# this is a plain script for that reason, invoked directly or via `make kind-up` in CI/elsewhere.
set -euo pipefail
cd "$(dirname "$0")/.."

CLUSTER_NAME=conveyor
NAMESPACE=conveyor
STRIMZI_MANIFEST=infra/k8s/strimzi/strimzi-cluster-operator-1.2.0.yaml
SERVICES=(order-service inventory-service payment-service saga-orchestrator dispatch-service conveyor-verifier)

echo "==> [1/8] kind cluster"
if ! kind get clusters | grep -qx "$CLUSTER_NAME"; then
  kind create cluster --config infra/k8s/kind-config.yaml
else
  echo "    cluster '$CLUSTER_NAME' already exists, reusing"
fi
kubectl config use-context "kind-$CLUSTER_NAME" >/dev/null

echo "==> [2/8] Calico CNI (kindnetd does not enforce NetworkPolicy — see kind-config.yaml's comment)"
kubectl apply -f infra/k8s/calico/calico.yaml
kubectl -n kube-system rollout status daemonset/calico-node --timeout=180s
kubectl -n kube-system rollout status deployment/calico-kube-controllers --timeout=180s

echo "==> [3/8] namespace + metrics-server"
kubectl apply -f infra/k8s/namespace.yaml
kubectl apply -f infra/k8s/metrics-server/components.yaml

echo "==> [4/8] Strimzi Kafka operator (cluster-scoped CRDs + namespace-scoped operator)"
# The Deployment/ServiceAccount/ConfigMap/RoleBindings in this manifest carry no explicit
# `namespace:` field (only the sed-rewritten `myproject` -> `conveyor` ones did) — without -n here
# they land in kubectl's *current-context* default namespace instead of conveyor, where the
# ClusterRoleBindings' subjects (namespace: conveyor) then don't match the ServiceAccount that
# actually got created. Found live this phase: the operator Deployment landed in `default` and
# never became Ready. -n conveyor makes every unqualified resource in the manifest land correctly.
kubectl apply -n "$NAMESPACE" -f "$STRIMZI_MANIFEST"
kubectl -n "$NAMESPACE" rollout status deployment/strimzi-cluster-operator --timeout=180s

echo "==> [5/8] Kafka cluster + topics (KRaft, single dual-role node — ARCHITECTURE.md §15.1)"
kubectl apply -f infra/k8s/kafka/kafka-cluster.yaml
kubectl -n "$NAMESPACE" wait kafka/conveyor-kafka --for=condition=Ready --timeout=300s
kubectl apply -f infra/k8s/kafka/kafka-topics.yaml

echo "==> [6/8] build + load the six service images (local tag, not pushed anywhere — GHCR push is Phase 14)"
IMAGE_TAG="${IMAGE_TAG:-local}"
# --provenance=false + a fixed SOURCE_DATE_EPOCH: PLAN.md Phase 13's "images are reproducible: same
# commit -> same digest" needs both a fixed jar timestamp (pom.xml's project.build.outputTimestamp)
# AND this — without it, BuildKit's default provenance attestation embeds a real build timestamp,
# which alone makes two builds of the identical commit produce different manifest-list digests even
# though the underlying image layers/config are already byte-identical (found and fixed live this
# phase; see BUGS.md).
for svc in "${SERVICES[@]}"; do
  docker build -f "$svc/Dockerfile" -t "conveyor/$svc:$IMAGE_TAG" \
    --provenance=false --build-arg SOURCE_DATE_EPOCH=1704067200 .
  kind load docker-image "conveyor/$svc:$IMAGE_TAG" --name "$CLUSTER_NAME"
done

echo "==> [7/8] helm install/upgrade"
helm upgrade --install conveyor infra/helm/conveyor \
  --namespace "$NAMESPACE" --create-namespace \
  --set image.tag="$IMAGE_TAG" \
  --wait --timeout 5m

echo "==> [8/8] waiting for every deployment to roll out"
kubectl -n "$NAMESPACE" get deploy -o name | xargs -I{} kubectl -n "$NAMESPACE" rollout status {} --timeout=180s

echo "==> done. kubectl -n $NAMESPACE get pods"
kubectl -n "$NAMESPACE" get pods
