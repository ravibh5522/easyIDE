# infra

Suggested addition (not in the original spec): deployment/IaC config — Docker images, CI pipeline definitions, k8s/hosting manifests for `services/backend` once it exists, app-store release pipeline config for `services/mobile`.

Top-level sibling of `services/`, same reasoning as `tools/`: infra config describes *how* services get built/deployed, it isn't itself a service. Keep empty until `services/backend` or a real CI pipeline exists — no speculative Dockerfiles for a backend that doesn't exist yet.
