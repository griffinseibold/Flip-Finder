# OSRS Flip Finder

Flip Finder ranks Old School RuneScape Grand Exchange items by how much you
could make flipping them after tax, using live prices from the RuneScape Wiki.
Set a budget and it shows the best flips you can afford, how fast each one is
trading, and what one four-hour buy limit of it could earn.

## Run it on your computer

You need [Docker](https://docs.docker.com/get-started/get-docker/).

```bash
docker run --rm --publish 8081:8080 ghcr.io/griffinseibold/osrs-flip-finder:latest
```

Open <http://localhost:8081>. Prices load a few seconds after it starts and
refresh every five minutes. Within about 15 seconds it also has the last two
days of trading history, so it can flag flips whose volume or margin is far
from usual. Press Ctrl+C to stop it.

## Run it on the homelab

The homelab version knows your account and adds a chat with the homelab's
local language model: ask what to flip and it answers for your coins,
membership and buy limits, and whether an item is trading more than usual.
The homelab runs the model while Flip Finder runs, so the first answer after
a start can wait a minute or two while it loads.
You need:

- a running [homelab]
- [RuneLite](https://runelite.net) with the
  [Flip Finder Agent](https://github.com/griffinseibold/flip-finder-plugin)
  plugin

Register Flip Finder with the homelab's Argo CD once:

```bash
kubectl --context kind-homelab-dev apply -f https://raw.githubusercontent.com/griffinseibold/Flip-Finder/master/deploy/argocd-application.yaml
```

Then set up the plugin: in RuneLite, install **Flip Finder Agent** from the
Plugin Hub, turn on its **Send account data** setting, and open your bank
once.
[RuneLite plugin](docs/runelite-plugin.md) has the details.

Open <http://flipfinder.localhost:8080>, or
`https://flipfinder.lab.internal` from a phone on your Wi-Fi once the homelab's
[home network access](docs/homelab.md#registering-with-argo-cd) is set up. It
opens on the **Chat** tab; **All flips** has the full table. Argo CD keeps it in step with this repository from
then on, and its price database survives restarts.

## Learn more

- [Using Flip Finder](docs/using-flip-finder.md): what each column means, how
  flips are ranked and what the homelab chat can answer.
- [API](docs/api.md): the HTTP endpoints behind the web app.
- [Development](docs/development.md): building, testing and running from
  source, with and without the homelab, and how releases work.
- [Homelab deployment](docs/homelab.md): the Helm chart, Argo CD, the
  language model and how updates reach the homelab.
- [RuneLite plugin](docs/runelite-plugin.md): setting up the plugin that tells
  the homelab version about your account.
- [Price data](docs/price-data.md): where prices, trading volumes, buy limits
  and tax rules come from.

[homelab]: https://github.com/griffinseibold/Homelab
