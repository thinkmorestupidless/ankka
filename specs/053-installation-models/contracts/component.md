# Contract: the model server in an installation

What `kustomization/components/models/` installs, who may connect, and what an overlay sets.
Decisions are in [research.md](../research.md) (R1 to R4, R11).

## The component

`kind: Component`, listed after `operator` and `controlplane` in an overlay's `components`, since
it patches both.

| Object | Namespace | What it is |
|---|---|---|
| Namespace `models-system` | — | not labelled `app.kubernetes.io/managed-by: ankka`: no route and no trust bundle belongs in it |
| ConfigMap `models-declaration` | `models-system` | `models.json`, `{"models": ["qwen3:0.6b"]}` as shipped |
| Certificate `ankka-models` | `models-system` | from `ClusterIssuer ankka-service`; URI `ankka://platform/models`; DNS `models`, `models.models-system`, `models.models-system.svc`, `models.models-system.svc.cluster.local`; usages `server auth`; secret `ankka-models-tls`; RSA 2048, PKCS8, `rotationPolicy: Always`; the workload default duration |
| StatefulSet `models` | `models-system` | one replica, below |
| Service `models` | `models-system` | ports `models` 8443 → keeper 8443, `status` 8080 → keeper 8080; `publishNotReadyAddresses: true`, so the operator and the control plane read `fetching` during the first fetch, before the pod is ready |
| NetworkPolicy `models` | `models-system` | below |
| patch on Deployment `ankka-operator`, container `ankka-operator` | `ankka-operator` | `ANKKA_MODELS_ADDRESS`, `ANKKA_MODELS_STATUS_URL`, `ANKKA_MODELS_NAMESPACE` |
| patch on Deployment `ankka-controlplane`, container `ankka-controlplane` | `ankka-controlplane` | `ANKKA_MODELS_ADDRESS` |

## The pod

```yaml
containers:
  - name: ollama
    image: ollama/ollama:0.40.6
    env:
      - { name: OLLAMA_HOST, value: "127.0.0.1:11434" }   # loopback only: the keeper is the way in
      - { name: OLLAMA_KEEP_ALIVE, value: "-1" }
      - { name: OLLAMA_NUM_PARALLEL, value: "1" }           # an overlay raises it
      - { name: OLLAMA_MODELS, value: /var/lib/ollama }
      - { name: OLLAMA_NO_CLOUD, value: "true" }
    ports: []                                               # none declared: nothing routes to it
    resources: { requests: { cpu: "1", memory: 2Gi }, limits: { cpu: "2", memory: 4Gi } }
    volumeMounts: [{ name: models, mountPath: /var/lib/ollama }]
  - name: keeper
    image: ankka-model-keeper:latest                        # the overlay's images: block renames it
    env:
      - { name: ANKKA_KEEPER_OLLAMA, value: "http://127.0.0.1:11434" }
      - { name: ANKKA_KEEPER_DECLARATION, value: /etc/ankka/models/models.json }
      - { name: ANKKA_KEEPER_ADDRESS, value: "https://models.models-system.svc:8443" }
      - { name: ANKKA_KEEPER_PARALLEL, value: "1" }                      # the same value as OLLAMA_NUM_PARALLEL
      - { name: ANKKA_OTLP_ENDPOINT, value: "http://otel-collector.ankka-telemetry.svc:4318" }
    ports:
      - { name: models, containerPort: 8443 }
      - { name: status, containerPort: 8080 }
      - { name: probe, containerPort: 7627 }
    readinessProbe: { httpGet: { path: /ready, port: probe }, periodSeconds: 5 }
    resources: { requests: { cpu: 250m, memory: 192Mi }, limits: { cpu: 250m, memory: 256Mi } }
    volumeMounts:
      - { name: ankka-service-tls, mountPath: /var/run/secrets/ankka/service, readOnly: true }
      - { name: declaration, mountPath: /etc/ankka/models, readOnly: true }
volumes:
  - { name: ankka-service-tls, secret: { secretName: ankka-models-tls } }
  - { name: declaration, configMap: { name: models-declaration } }
volumeClaimTemplates:
  - metadata: { name: models }
    spec: { accessModes: [ReadWriteOnce], resources: { requests: { storage: 20Gi } } }
```

`automountServiceAccountToken: false`. The pod has no ServiceAccount of note: nothing in it talks
to the API server.

## Who may connect

| Port | From |
|---|---|
| 8443 `models` | pods labelled `app.kubernetes.io/managed-by: ankka` in namespaces with that label (every service's platform container; the process shares the pod and is kept out by the handshake, not the policy); the control plane's pods in `ankka-controlplane` |
| 8080 `status` | the operator's pods in `ankka-operator` |
| 7627 `probe` | the kubelet |
| 11434 | nobody: Ollama listens on the pod's loopback and no Service names the port |

## What an overlay sets

Placement and size are a strategic-merge patch on the StatefulSet. `overlays/cloud/models-size.yaml`
is the example, every value marked `SET`:

```yaml
apiVersion: apps/v1
kind: StatefulSet
metadata: { name: models, namespace: models-system }
spec:
  template:
    spec:
      nodeSelector: { cloud.google.com/gke-accelerator: nvidia-l4 }        # SET
      tolerations: [{ key: nvidia.com/gpu, operator: Exists, effect: NoSchedule }]   # SET
      containers:
        - name: ollama
          env: [{ name: OLLAMA_NUM_PARALLEL, value: "4" }]                 # SET
        - name: keeper
          env: [{ name: ANKKA_KEEPER_PARALLEL, value: "4" }]                # SET, the same value
          resources:
            requests: { cpu: "4", memory: 16Gi }                            # SET
            limits:   { cpu: "8", memory: 32Gi, nvidia.com/gpu: 1 }         # SET
  volumeClaimTemplates:
    - metadata: { name: models }
      spec: { storageClassName: premium-rwo, resources: { requests: { storage: 200Gi } } }   # SET
```

The declaration is the ConfigMap's `models.json`, replaced by the overlay with a
`configMapGenerator` of the same name and `behavior: replace`. The GPU node pool, the driver
installer and the accelerator's name are the installation repository's.

## The local overlay

`kustomization/overlays/local-models/kustomization.yaml`:

```yaml
apiVersion: kustomize.config.k8s.io/v1beta1
kind: Kustomization
resources: [../local]
components: [../../components/models]
```

`ANKKA_LOCAL_MODELS=on ./kustomization/deploy-local.sh` renders it in place of `local`, loads
`ankka-model-keeper:latest` into kind, and waits for `statefulset/models` to be ready, which includes
the small model's fetch. `just deploy-models` is the short form. Without the flag, nothing of the
component is applied or loaded.

## What `RemoteOverlaySuite` holds

- `local` renders no object in `models-system` and no `ANKKA_MODELS_*` variable anywhere.
- `local-models` renders the component, and `ANKKA_MODELS_ADDRESS` exactly once on the operator's
  container and once on the control plane's, with no second container added by either patch.
- `cloud` renders the component with `models-size.yaml` applied: the selector, the toleration, the
  GPU limit and the claim's class present; the keeper's image renamed by the `images:` block.
- The Certificate names an issuer that exists and asks for PKCS8.
