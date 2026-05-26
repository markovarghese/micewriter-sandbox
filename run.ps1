<#
.SYNOPSIS
  Build, push, and deploy the micewriter-sandbox app to k3s.
.EXAMPLE
  .\run.ps1 deploy
  .\run.ps1 undeploy
#>
param([Parameter(Mandatory)][string]$Target)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

# Path to the kubeconfig produced by the k3sonhyperv Ansible playbook.
# Update this if your k3sonhyperv repo is in a different location.
$kubeconfig = "D:\githubrepos\k3sonhyperv\kubeconfig"
if (-not (Test-Path $kubeconfig)) {
    Write-Error "kubeconfig not found at $kubeconfig — run install-k3s.yml first."
    exit 1
}

docker info > $null 2>&1
if ($LASTEXITCODE -ne 0) { Write-Error "Docker is not running."; exit 1 }

$registry     = "k8s-node-1.local:5000"
$image        = "micewriter-sandbox"
$tag          = "latest"
$fullTag      = "${registry}/${image}:${tag}"
# Build context is the parent dir: Dockerfile needs both sdk-java and sandbox source trees.
$buildContext = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$namespace    = "micewriter-sandbox"

function Invoke-Kubectl {
    docker run --rm -i `
        -v "${kubeconfig}:/kubeconfig:ro" `
        -e KUBECONFIG=/kubeconfig `
        -v "${PSScriptRoot}:/workspace:ro" `
        -w /workspace `
        bitnami/kubectl:latest @args
}

switch ($Target) {
    "deploy" {
        Write-Host "Building $image (context: $buildContext)..."
        docker build -f Dockerfile -t $fullTag $buildContext

        Write-Host "Pushing $fullTag..."
        docker push $fullTag

        Write-Host "Applying k8s manifests..."
        Invoke-Kubectl apply -f k8s/namespace.yaml
        Invoke-Kubectl apply -f k8s/deployment.yaml
        Invoke-Kubectl apply -f k8s/service.yaml
        Invoke-Kubectl rollout status deployment/micewriter-sandbox -n $namespace --timeout=120s

        Write-Host ""
        Write-Host "Sandbox deployed."
        Write-Host "  POST http://k8s-node-1.local/events"
        Write-Host "  POST http://k8s-node-1.local/events/load?count=1000"
    }

    "undeploy" {
        Invoke-Kubectl delete -f k8s/service.yaml    --ignore-not-found
        Invoke-Kubectl delete -f k8s/deployment.yaml --ignore-not-found
        Invoke-Kubectl delete -f k8s/namespace.yaml  --ignore-not-found
    }

    default { Write-Error "Unknown target '$Target'. Use: deploy | undeploy" }
}
