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
$kubeconfig = "$env:USERPROFILE\.kube\config"
if (-not (Test-Path $kubeconfig)) {
    Write-Error "kubeconfig not found at $kubeconfig - run install-k3s.yml first."
    exit 1
}

# docker info check removed to avoid failing on benign stderr warnings

$registry     = "k8s-node-1.local:5000"
$image        = "micewriter-sandbox"
$tag          = "latest"
$fullTag      = "${registry}/${image}:${tag}"
# Build context is the parent dir: Dockerfile needs both sdk-java and sandbox source trees.
$buildContext = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$namespace    = "micewriter-sandbox"

function Invoke-Kubectl {
    kubectl --kubeconfig $kubeconfig @args
}

switch ($Target) {
    "deploy" {
        Write-Host "Building $image (context: $buildContext)..."
        docker build -f Dockerfile -t $fullTag $buildContext
        if ($LASTEXITCODE -ne 0) { throw "docker step failed ($LASTEXITCODE)" }

        Write-Host "Pushing $fullTag..."
        docker push $fullTag
        if ($LASTEXITCODE -ne 0) { throw "docker step failed ($LASTEXITCODE)" }

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
    }

    "undeploy" {
        Invoke-Kubectl delete -f k8s/ingress.yaml    --ignore-not-found
        Invoke-Kubectl delete -f k8s/service.yaml    --ignore-not-found
        Invoke-Kubectl delete -f k8s/deployment.yaml --ignore-not-found
        Invoke-Kubectl delete -f k8s/namespace.yaml  --ignore-not-found
    }

    default { Write-Error "Unknown target '$Target'. Use: deploy | undeploy" }
}
