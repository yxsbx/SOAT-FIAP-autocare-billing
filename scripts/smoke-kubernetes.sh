#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

image="${1:?Informe uma imagem local de Billing com tag imutável}"
docker image inspect "$image" >/dev/null

work="$(mktemp -d)"
export KUBECONFIG="$work/kubeconfig"
cluster="billing-ci-${work##*.}"
cluster="${cluster,,}"
cleanup() {
    kind delete cluster --name "$cluster"
    rm -rf "$work"
}
trap cleanup EXIT

kind create cluster --name "$cluster" --wait 120s
kubectl create namespace billing-ci
kube=(kubectl --namespace billing-ci)

# Credenciais descartáveis exclusivas deste cluster.
db_password="$(openssl rand -hex 24)"
rabbit_password="$(openssl rand -hex 24)"
"${kube[@]}" run billing-db --image=postgres:16-alpine --port=5432 \
    --env=POSTGRES_DB=billing --env=POSTGRES_USER=billing --env="POSTGRES_PASSWORD=$db_password"
"${kube[@]}" expose pod billing-db --port=5432
"${kube[@]}" run autocare-rabbitmq --image=rabbitmq:4.3-management-alpine --port=5672 \
    --env=RABBITMQ_DEFAULT_USER=billing --env="RABBITMQ_DEFAULT_PASS=$rabbit_password"
"${kube[@]}" expose pod autocare-rabbitmq --port=5672
"${kube[@]}" wait --for=condition=Ready pod/billing-db pod/autocare-rabbitmq --timeout=180s
for attempt in {1..60}; do
    if "${kube[@]}" exec billing-db -- pg_isready -h 127.0.0.1 -U billing -d billing >/dev/null 2>&1 &&
        "${kube[@]}" exec autocare-rabbitmq -- rabbitmq-diagnostics -q check_running >/dev/null 2>&1; then
        break
    fi
    if [[ "$attempt" == 60 ]]; then
        printf 'PostgreSQL ou RabbitMQ não ficou disponível no prazo.\n' >&2
        exit 1
    fi
    sleep 2
done

umask 077
cat >"$work/runtime.env" <<EOF
BILLING_DB_URL=jdbc:postgresql://billing-db:5432/billing
BILLING_DB_USER=billing
BILLING_DB_PASSWORD=$db_password
RABBITMQ_HOST=autocare-rabbitmq
RABBITMQ_USER=billing
RABBITMQ_PASSWORD=$rabbit_password
EOF
"${kube[@]}" create secret generic autocare-billing-runtime --from-env-file="$work/runtime.env"

kind load docker-image "$image" --name "$cluster"
kubectl set image --local -f k8s/deployment.yaml "billing=$image" -o yaml |
    kubectl patch --local -f - --type merge \
        -p '{"spec":{"template":{"spec":{"imagePullSecrets":[]}}}}' -o yaml |
    "${kube[@]}" set env --local -f - OTEL_TRACES_EXPORTER=none OTEL_LOGS_EXPORTER=none -o yaml |
    "${kube[@]}" apply -f -
"${kube[@]}" apply -f k8s/service.yaml
if ! "${kube[@]}" rollout status deployment/autocare-billing --timeout=240s; then
    "${kube[@]}" get pods
    "${kube[@]}" logs deployment/autocare-billing --tail=60 || true
    exit 1
fi
"${kube[@]}" get deployment/autocare-billing
printf 'PASS: Billing pronto no Kubernetes com PostgreSQL e RabbitMQ.\n'
