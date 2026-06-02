<#
.SYNOPSIS
  Build, push, and deploy the micewriter-sandbox app + its v2 engine pipelines to k3s.
.EXAMPLE
  .\run.ps1 deploy           # install per-table pipelines + build/push/deploy the app
  .\run.ps1 pipelines-up     # only install the per-table pipelines
  .\run.ps1 pipelines-down   # uninstall the per-table pipelines
  .\run.ps1 undeploy         # tear down the app (keeps pipelines)
#>
param([Parameter(Mandatory)][string]$Target)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$kubeconfig = "$env:USERPROFILE\.kube\config"
if (-not (Test-Path $kubeconfig)) {
    Write-Error "kubeconfig not found at $kubeconfig - run install-k3s.yml first."
    exit 1
}

$registry     = "k8s-node-1.local:5000"
$image        = "micewriter-sandbox"
$tag          = "latest"
$fullTag      = "${registry}/${image}:${tag}"
$buildContext = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$namespace    = "micewriter-sandbox"
$infraNs      = "micewriter-infra"
$localInfra   = (Resolve-Path (Join-Path $PSScriptRoot "..\micewriter-local-infra")).Path
$chartPath    = "/local-infra/charts/table-pipeline"

# Iceberg tables this sandbox writes to. Each gets its own engine pipeline
# (one Helm release of charts/table-pipeline). Keep in sync with the
# @IcebergEntity classes under src/main/java/com/micewriter/sandbox/model/.
$tables = @("telemetry_events", "audit_events")

function Invoke-Kubectl {
    kubectl --kubeconfig $kubeconfig @args
}

function Invoke-Helm {
    docker run --rm -i `
        -v "${kubeconfig}:/kubeconfig:ro" `
        -e KUBECONFIG=/kubeconfig `
        -v "${localInfra}:/local-infra:ro" `
        alpine/helm:latest @args
}

function Install-Pipelines {
    foreach ($t in $tables) {
        $releaseName = "engine-" + ($t -replace "_", "-")
        Write-Host "Installing pipeline '$releaseName' for table '$t'..."
        Invoke-Helm upgrade --install $releaseName $chartPath `
            --namespace $infraNs `
            --set table=$t `
            --wait
    }
}

function Uninstall-Pipelines {
    foreach ($t in $tables) {
        $releaseName = "engine-" + ($t -replace "_", "-")
        Write-Host "Uninstalling pipeline '$releaseName'..."
        Invoke-Helm uninstall $releaseName --namespace $infraNs --ignore-not-found
    }
}

switch ($Target) {
    "deploy" {
        Install-Pipelines

        Write-Host "Building $image (context: $buildContext)..."
        docker build -f Dockerfile -t $fullTag $buildContext

        Write-Host "Pushing $fullTag..."
        docker push $fullTag

        Write-Host "Applying k8s manifests..."
        Invoke-Kubectl apply -f k8s/namespace.yaml
        Invoke-Kubectl apply -f k8s/deployment.yaml
        Invoke-Kubectl apply -f k8s/service.yaml
        Invoke-Kubectl apply -f k8s/ingress.yaml
        Invoke-Kubectl rollout status deployment/micewriter-sandbox -n $namespace --timeout=120s

        Write-Host ""
        Write-Host "Sandbox deployed."
        Write-Host "  POST http://k8s-node-1.local/events"
        Write-Host "  POST http://k8s-node-1.local/events/load?count=1000"
        Write-Host "  POST http://k8s-node-1.local/audit       (writes to audit_events)"
        Write-Host "  POST http://k8s-node-1.local/events/flush?table=telemetry_events"
    }

    "undeploy" {
        Invoke-Kubectl delete -f k8s/ingress.yaml    --ignore-not-found
        Invoke-Kubectl delete -f k8s/service.yaml    --ignore-not-found
        Invoke-Kubectl delete -f k8s/deployment.yaml --ignore-not-found
        Invoke-Kubectl delete -f k8s/namespace.yaml  --ignore-not-found
        Write-Host ""
        Write-Host "Sandbox app removed. Engine pipelines remain (run 'pipelines-down' to remove)."
    }

    "pipelines-up" {
        Install-Pipelines
    }

    "pipelines-down" {
        Uninstall-Pipelines
    }

    default { Write-Error "Unknown target '$Target'. Use: deploy | undeploy | pipelines-up | pipelines-down" }
}
