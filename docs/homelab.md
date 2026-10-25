# Homelab deployment

On the [homelab], Argo CD deploys the Helm chart in
[`chart/flipfinder`](../chart/flipfinder) from this repository's `master`
branch. The homelab version keeps its price database on a persistent volume,
and features that rely on the homelab are enabled by its `homelab` Spring
profile: receiving account data from the
[RuneLite plugin](runelite-plugin.md), keeping trading history, and the chat,
which runs on the homelab's language model.

## The chart

The chart follows the homelab's application contract:

- Argo CD syncs the chart directly from this repository.
- The namespace receives `gateway.homelab/access: public` and
  `gateway.homelab/lan: "true"`.
- An `HTTPRoute` for `flipfinder.localhost` and `flipfinder.lab.internal`
  attaches to `gateway-system/homelab` on its `http` listener, for the homelab
  host, and its `lan` listener, for the home network.
- The service stays internal on port 80; the container listens on port 8080.
- The `homelab` Spring profile stores SQLite on a 1 Gi `ReadWriteOnce`
  volume.
- One replica runs, because a SQLite database has one writer.
- The Deployment carries the `homelab.local/llm: requested` label, which asks
  the homelab to run its [language model](#the-language-model) while Flip
  Finder runs.

| Value | Default | What it does |
| --- | --- | --- |
| `persistence.enabled` | `true` | `false` runs the in-memory `local` profile with no volume, and with no chat |
| `llm.enabled` | `true` | `false` turns the chat off and stops asking for the language model |

Check the chart without deploying it:

```bash
helm lint chart/flipfinder
helm template flipfinder chart/flipfinder --namespace flipfinder
helm template flipfinder chart/flipfinder --namespace flipfinder --set llm.enabled=false
```

## The language model

The chat sends questions to the homelab's llama.cpp server through its
OpenAI-compatible API, in-cluster at
`http://llama-server.llm.svc.cluster.local/v1`. Set `FLIPFINDER_LLM_URL` to
use another. The model answers by calling a `find_flips` tool, which runs the
same query as `/api/flips` for the selected account, so it needs a model and
server with tool calling; the homelab's Qwen3 does, with its thinking turned
off to answer faster. The server's slots are shared with the homelab's other
users, so an answer can wait while they are busy.

### Starting and stopping the model

The homelab does not keep its language model running: the model holds the GPU,
so it runs only while an application asks for it. The chart asks for it with
the `homelab.local/llm: requested` label on Flip Finder's Deployment. The
homelab starts the model while Flip Finder runs and stops it a few minutes
after Flip Finder stops or is scaled to zero.

Loading the model takes up to a couple of minutes, so the first question after
Flip Finder or the model starts may have to wait. Before it asks, Flip Finder
checks the server's `/health`, at the root of the URL above without its `/v1`.
While the model is loading, or not running yet, the chat says that the
homelab's language model is starting and checks again every two seconds. The
answer begins as soon as the model is ready. If it is still not ready after 90
seconds, the chat says so and asks you to try again shortly.

These settings change the wait:

| Property | Default | What it does |
| --- | --- | --- |
| `flipfinder.llm.startup-wait` | `90s` | How long a question waits for the model to start. Keep it well under `spring.mvc.async.request-timeout` (3 minutes), so the answer has time to stream once the model is up. |
| `flipfinder.llm.startup-poll` | `2s` | How often it checks whether the model is ready |
| `flipfinder.llm.health-url` | The base URL's root plus `/health` | Where it checks, for a server laid out differently from llama.cpp |

Set the chart's `llm.enabled` to `false` to turn the chat off. The label goes,
so Flip Finder stops asking for the GPU and the homelab can stop the model, and
the web app hides the chat. Everything else keeps working.

The homelab also has an LLM mode, for using the model on its own: it stops the
homelab's applications, Flip Finder among them, and runs only the model. Flip
Finder starts again when the homelab leaves LLM mode.

## Trading history

The homelab version stores each item's average prices and volumes from the
wiki's `/5m` and `/1h` endpoints, so the chat and the flips table can tell
whether an item's latest trading is typical. It keeps:

| History | Kept for | Rows | Disk |
| --- | --- | --- | --- |
| Five-minute buckets | 7 days | About 3.6 million | About 95 MB |
| Hourly buckets | 30 days | About 2.4 million | About 60 MB |

On its first start it fetches the last two hours of five-minute buckets and
two days of hourly ones within about 15 seconds, enough to compare with. The
rest, 24 hours of five-minute buckets and 30 days of hourly ones, about 1,000
requests, follows a request every second and a half in batches of 20, so the
full backfill takes under an hour. After that it fetches each new bucket as the
wiki publishes it and deletes buckets older than it keeps once an hour.

The history is stored in the SQLite database on Flip Finder's persistent
volume, so restarts, upgrades and Argo CD syncs keep it. A restart only
fetches the buckets missed while it was down. Deployments stop the old pod
before starting the new one, so two versions never write to the database at
once. Deleting the Kind cluster deletes the volume, like every `standard` PVC
on the homelab; the homelab's `./scripts/backup-dev.py create` archives it
with the others, and its
[recovery guide](https://github.com/griffinseibold/Homelab/blob/master/docs/recovery.md)
restores it.

The chat reads the history with its `item_history` tool, and the numbers are
worked out by the server rather than the language model, which is unreliable
at arithmetic over hundreds of values. No model training is involved: prices
change every five minutes, so the model looks them up instead of remembering
them.

These settings change what is kept:

| Property | Default |
| --- | --- |
| `flipfinder.history.five-minute-days` | `7` |
| `flipfinder.history.hourly-days` | `30` |
| `flipfinder.history.five-minute-backfill-hours` | `24` |

## Registering with Argo CD

[`deploy/argocd-application.yaml`](../deploy/argocd-application.yaml)
registers the chart the same way as the homelab's other applications: the
`default` project, the `flipfinder` namespace, and automated sync with pruning
and self-healing. Apply it once:

```bash
kubectl --context kind-homelab-dev apply -f deploy/argocd-application.yaml
```

The registration lives in the cluster, not in Git; the homelab's backup
exports it. Once it syncs, the app is at:

- Web app: <http://flipfinder.localhost:8080>
- API: <http://flipfinder.localhost:8080/api/flips>
- Swagger UI: <http://flipfinder.localhost:8080/swagger-ui.html>

Phones and other devices on your Wi-Fi can use `https://flipfinder.lab.internal`
once the homelab's
[home network access](https://github.com/griffinseibold/Homelab/blob/main/docs/operations.md#home-network-access)
is set up: add `flipfinder.lab.internal` to your router's DNS and trust the
homelab's root certificate on the device. Flip Finder has no login, so anyone
on your Wi-Fi can see your account's flips and use the chat.

To check on it:

```bash
kubectl --context kind-homelab-dev -n argocd get application flipfinder
kubectl --context kind-homelab-dev -n flipfinder logs deploy/flipfinder
```

## How changes reach the homelab

Argo CD applies whatever the chart on `master` describes, and the chart names
one image version. A merged chart change is applied within a few minutes; new
code ships when a [release](development.md#ci-and-releases) bumps that version.

### Running a master build

Every push to `master` publishes an image tagged `sha-<commit>`. To run one
before it is released, override the chart's image tag on the Application:

```bash
kubectl --context kind-homelab-dev -n argocd patch application flipfinder --type merge \
  -p '{"spec":{"source":{"helm":{"parameters":[{"name":"image.tag","value":"sha-5ba07f3"}]}}}}'
```

Go back to the chart's version by removing the override:

```bash
kubectl --context kind-homelab-dev -n argocd patch application flipfinder --type merge \
  -p '{"spec":{"source":{"helm":null}}}'
```

### Running a local build

To try uncommitted work, build the image, load it into the Kind nodes and
point the Application at it the same way:

```bash
tag=dev-$(date +%s)
docker build --tag "flipfinder:$tag" .
kind load docker-image "flipfinder:$tag" --name homelab-dev
kubectl --context kind-homelab-dev -n argocd patch application flipfinder --type merge \
  -p "{\"spec\":{\"source\":{\"helm\":{\"parameters\":[{\"name\":\"image.repository\",\"value\":\"flipfinder\"},{\"name\":\"image.tag\",\"value\":\"$tag\"}]}}}}"
```

Remove the override as above when you are done.

[homelab]: https://github.com/griffinseibold/Homelab
