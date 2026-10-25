# API

The web app is built on these HTTP endpoints. They are served on the same
port as the app: <http://localhost:8081> when run with Docker, and
<http://flipfinder.localhost:8080> on the homelab.

| Method | Path | Description |
| --- | --- | --- |
| `GET` | `/api/flips` | One page of items priced, filtered and sorted as flips |
| `GET` | `/api/items?page=0&size=25` | One page of Grand Exchange items by id |
| `GET` | `/api/items/{id}/history` | How an item's latest trading compares with its history |
| `GET` | `/api/features` | Which homelab features this server has turned on |
| `POST` | `/api/chat` | Ask the homelab's language model about flips (homelab only) |
| `PUT` | `/api/runelite/accounts/{accountHash}` | Report an account; the [RuneLite plugin](runelite-plugin.md) calls this (homelab only) |
| `GET` | `/api/runelite/accounts` | Every account the plugin has reported (homelab only) |
| `GET` | `/api/runelite/accounts/{accountHash}` | One account, with its buy limits still in effect (homelab only) |
| `GET` | `/v3/api-docs` | OpenAPI document |
| `GET` | `/swagger-ui.html` | Interactive Swagger UI |
| `GET` | `/actuator/health/liveness` | Kubernetes liveness check |
| `GET` | `/actuator/health/readiness` | Kubernetes readiness check |

## Flips

`/api/flips` accepts these query parameters, all optional. Values are
case-insensitive.

| Parameter | Default | Meaning |
| --- | --- | --- |
| `sort` | `estimatedProfit` | `estimatedProfit`, `potentialProfit`, `margin`, `roi`, `buyPrice`, `sellPrice`, `buyLimit`, `volume5m`, `lastTradeTime` or `name` |
| `direction` | `desc` (`asc` for `name`) | `asc` or `desc`; missing values always sort last |
| `page`, `size` | `0`, `25` | Zero-based page and page size, at most 200 |
| `basis` | `latest` | `latest` trade prices or `average5m` five-minute averages |
| `search` | | Case-insensitive part of the item name |
| `membership` | `all` | `all`, `f2p` or `members` |
| `maxTradeAgeMinutes` | `0` | Both sides must have traded this recently; `0` for any time |
| `minVolume5m` | `0` | Minimum items traded in the latest five-minute window |
| `budget` | | Coins available: hides items that cost more and limits quantity to what it buys |
| `account` | | Account hash from the [RuneLite plugin](runelite-plugin.md): see below |

For example, the best flips for a 10M budget among active members items:

```bash
curl 'http://localhost:8081/api/flips?budget=10000000&membership=members&minVolume5m=10&size=10'
```

Each result holds the `item` plus `buyPrice`, `sellPrice`, `tax`, `margin`,
`roi`, `quantity`, `potentialProfit`, `volume5m`, `fillableQuantity`,
`estimatedProfit`, `limitedBy` (`buyLimit`, `budget` or `volume`),
`lastTradeTime`, and from [trading history](#history) `volumeVsUsual` and a
`warning` such as `volume 6.2x usual, may not last` when the volume or margin
is at least three times usual. The quantity and profit fields are `null` for items with no
known buy limit. The page also reports how many matching flips are profitable
or losing, the `topFlip`, the `budget` it applied, and when the most recent
trade happened.

With `account`, the flips are for that account as the RuneLite plugin last
reported it: its coins become the budget unless `budget` is given, a
free-to-play account only sees free-to-play items, and each item's buy limit
shrinks by what the account already bought in its current four-hour window.
Those items also show `alreadyBought` and `limitResetsAt`. An account the plugin
has never reported returns 404.

The estimate caps the quantity at what could trade in four hours at the pace
of the latest five-minute window: 48 times the slower of the two sides' volume.
[Using Flip Finder](using-flip-finder.md#the-columns) explains each field.

Every item is priced and sorted in memory on each request, because the sort
keys depend on the request's price basis and budget.

## RuneLite accounts

These endpoints exist only on the homelab, where the `homelab` profile turns
them on. The [plugin](runelite-plugin.md) `PUT`s the account's latest data
whenever it changes; each report replaces the last.

`GET /api/runelite/accounts/{accountHash}` returns the account's
`displayName`, `members`, `membershipDays`, `ironman`, `inventoryCoins`,
`bankCoins` and their total `coins` (`null` until the bank has been seen), its
Grand Exchange `geOffers`, and its `buyLimits`: each item bought in a window
that is still running, with its `limit`, how many were `bought`, how many
`remaining` and when it `resetsAt`. `capturedAt` is when the plugin read the
data.

## Chat

`GET /api/features` returns `{"runelite": true, "history": true, "chat": true}`
on the homelab; the standalone version has only `history`, and the homelab has
no `chat` when the chart's `llm.enabled` is `false`. The web app shows the chat
only when `chat` is on.

`POST /api/chat` takes the conversation so far, oldest first and ending with
the user's question, and optionally the account to answer for:

```json
{
  "account": 1234567890,
  "messages": [{ "role": "user", "content": "What could I make with 50M?" }]
}
```

The server keeps no conversation: send the earlier messages each time. It
answers with newline-delimited JSON (`application/x-ndjson`), one event per
line, as the model works. Ignore events of a `type` you do not know; new ones
may be added.

| `type` | Fields | Meaning |
| --- | --- | --- |
| `status` | `message` | The model is starting, and the answer waits for it; `message` says so in words |
| `tool` | `name`, `search`, `budget` | The model is looking flips up |
| `flips` | `search`, `budget`, `sort`, `total`, `items` | The flips it looked up, shaped like `/api/flips` results |
| `history` | `search`, `history` | The item history it looked up, shaped like `/api/items/{id}/history`, or `null` when no item matched |
| `text` | `text` | The next piece of the answer |
| `error` | `message` | The model could not be reached, failed, or was still starting after the wait |
| `done` | | The answer is complete |

```bash
curl -N http://flipfinder.localhost:8080/api/chat -H 'Content-Type: application/json' \
  -d '{"messages":[{"role":"user","content":"What should I flip right now?"}]}'
```

## History

`GET /api/items/{id}/history` compares an item's latest trading with the
stored history, for example
`/api/items/536/history` for dragon bones. Volumes count both sides: items
bought at the high price plus items sold at the low price.

- `fiveMinuteHours` and `hourlyDays`: how much history the comparison uses.
- `volume`: `last5m`, the latest five-minute volume; `typical5m`, the median
  five-minute volume, from hourly history until there are six hours of
  five-minute data; `typical5mThisHour`, the median at this time of day: the
  same hour and the hours either side of it on earlier days; the `basis` of
  the comparison; their `ratio`, preferring the time of day; the
  `percentile` of five-minute periods that traded less; `lastHour` against
  `typicalHour`; and a `verdict`: `unusually high` (3 times typical or more),
  `above typical` (1.5 times), `typical` or `below typical` (half or less).
- `price`: the `current` average price over the last hour, its percentage
  change on a day, a week and 30 days ago, and the `low` and `high` of the
  history.
- `margin`: the `current` gap between the latest instant-buy and instant-sell
  prices, the `typical` gap between average buy and sell prices, their `ratio`
  and a `verdict`: `much wider than usual` (3 times or more), `wider than
  usual` (1.5 times), `typical` or `narrower than usual` (half or less).
- `notes`: the comparison in sentences, as the chat's language model reads it.

Fields are `null` until there is enough history to compare: a day of hourly
data or six hours of five-minute data, and about two days for the time of day.

## Items

`/api/items` returns the stored item data ordered by id: `name`, `examine`,
`members`, `lowAlchemy`, `highAlchemy`, `buyLimit`, `value`, `icon`, the latest
high and low prices with their trade times, and the five-minute average prices
and volumes. Missing values are `null`.
